package com.past9.phoneaos.voice

import android.content.Context
import android.media.AudioManager
import com.past9.phoneaos.agent.AgentRuntime
import com.past9.phoneaos.data.AppDb
import com.past9.phoneaos.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class CallState(
    val active: Boolean = false,
    /** connecting | listening | speaking | ended | failed */
    val phase: String = "",
    val mine: Float = 0f,
    val theirs: Float = 0f,
    val lines: List<Pair<String, String>> = emptyList(),
    val delegated: String = "",
    val muted: Boolean = false,
    val error: String = "",
)

/**
 * A live voice call with the agent. The voice (Gemini Live) is the friendly front: it chats, and
 * hands anything that needs doing to the MAIN agent with delegate_to_agent. The main agent works
 * as usual (tools, browser, apps) and its results are read back into the call as they land.
 */
class CallController(private val context: Context, private val db: AppDb, private val settings: SettingsStore, private val runtime: AgentRuntime, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(CallState())
    val state: StateFlow<CallState> = _state
    private var live: LiveSession? = null
    private var watcher: Job? = null

    val available: Boolean get() = settings.state.value.liveEnabled && settings.voiceKey("gemini") != null

    private val tools = JSONArray().put(JSONObject().put("functionDeclarations", JSONArray()
        .put(JSONObject().put("name", "delegate_to_agent")
            .put("description", "Hand a task to the user's main agent, which can browse, use their apps, memory, tasks and routines. Use for ANYTHING that needs doing or looking up. Say a short 'on it' and keep chatting; results come back to you.")
            .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject().put("task", JSONObject().put("type", "STRING").put("description", "The task, clearly, with every detail the user gave"))).put("required", JSONArray().put("task"))))
        .put(JSONObject().put("name", "answer_agent_question")
            .put("description", "Answer the question the main agent is waiting on, with what the user said (or decided, if they told you to choose). Not for approvals of payments or sends: those need the user's tap on the screen.")
            .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()
                .put("answer", JSONObject().put("type", "STRING").put("description", "The answer: one of the options if there are options, otherwise the user's words"))
                .put("context", JSONObject().put("type", "STRING").put("description", "Anything else from the call the main agent needs to act on it")))
                .put("required", JSONArray().put("answer"))))
        .put(JSONObject().put("name", "agent_status")
            .put("description", "What the main agent is doing right now and the last thing it said.")
            .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject())))))

    fun start() {
        if (_state.value.active) return
        val key = settings.voiceKey("gemini") ?: run { _state.value = CallState(phase = "failed", error = context.getString(com.past9.phoneaos.R.string.svc_call_needs_key)); return }
        val s = settings.state.value
        val name = s.agentName.takeIf { it != "Your agent" } ?: "your agent"
        val system = """
            You are $name, ${s.userName.ifBlank { "the user" }}'s personal agent, talking on a voice call. Be warm, quick and natural; short sentences; this is speech.
            You are the voice. The real work is done by the main agent: for anything that needs doing, finding, checking or remembering, call delegate_to_agent with the full task, say a quick 'on it', and keep the conversation going.
            When you get an update from the main agent, tell the user the part that matters in a sentence or two. Never invent results.
            When the main agent asks the user something, it waits until it gets an answer, and your conversation carries on meanwhile. Put the question to the user naturally, at a good moment. If it's a choice or a question, take their answer (or decide yourself if they tell you to) and call answer_agent_question with it plus anything from the call that matters. If it needs them to DO something (hold Approve on a payment or send, sign in, type a code), tell them exactly what to tap: the card is on the call screen.
        """.trimIndent()
        _state.value = CallState(active = true, phase = "connecting")
        // Started while the call screen is in front, which is what Android requires for the mic.
        CallService.start(context)
        scope.launch { begin(key, system) }
    }

    /** Gives the voice the last stretch of the chat and the user's pinned facts, so it picks up mid-thread. */
    private suspend fun begin(key: String, base: String) {
        val s = settings.state.value
        val recent = db.chat().all().first().filter { it.kind == "user" || it.kind == "agent" }.takeLast(10)
            .joinToString("\n") { (if (it.kind == "user") "User: " else "You: ") + it.text.take(400) }
        val pinned = db.memory().pinned().joinToString("\n") { "- ${it.title}: ${it.body.take(200)}" }
        val system = base + (if (pinned.isNotBlank()) "\n\nWhat you know about them:\n$pinned" else "") +
            (if (recent.isNotBlank()) "\n\nThe chat so far (this call continues it):\n$recent" else "")
        callStart = System.currentTimeMillis(); callLines.clear()
        live = LiveSession(key, "gemini-3.1-flash-live-preview", s.ttsVoice.takeIf { s.ttsProvider == "gemini" && it.isNotBlank() } ?: "Kore", system, tools = tools) { e -> onEvent(e) }.also { it.start() }
        // Read the main agent's new messages and questions into the call.
        watcher = scope.launch {
            var last = db.chat().all().first().lastOrNull()?.id ?: 0L
            db.chat().all().collect { items ->
                items.filter { it.id > last && !JSONObject(it.meta).optBoolean("call") }.forEach { i ->
                    when (i.kind) {
                        "agent" -> live?.announce("[Update from the main agent, tell the user briefly] ${i.text.take(1200)}")
                        "question" -> {
                            val opts = JSONObject(i.meta).optJSONArray("options")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                            val approval = i.text.startsWith("APPROVAL|")
                            val q = com.past9.phoneaos.tools.ConnectRequest.parse(i.text)?.summary ?: if (approval) "Approve: " + i.text.split("|").getOrElse(1) { "" } else i.text
                            live?.announce(if (approval) "[The main agent needs the user's approval, which they must give by holding Approve on the card on screen. Tell them briefly what it is] ${q.take(600)}"
                                else "[The main agent is waiting on the user for this. Ask them, then call answer_agent_question] ${q.take(600)}" + if (opts.isNotEmpty()) " Options: ${opts.joinToString(", ")}." else "")
                        }
                    }
                }
                last = items.lastOrNull()?.id ?: last
            }
        }
    }

    var callStart = 0L
        private set
    /** The whole call as said, in order, for the main agent and for the chat. */
    private val callLines = mutableListOf<Pair<String, String>>()
    private var bufWho = ""; private val buf = StringBuilder()

    /** A speaker finished a stretch: put it in the chat as one bubble, like any message. */
    private fun flush() {
        val text = buf.toString().trim(); val who = bufWho
        buf.clear(); bufWho = ""
        if (text.isBlank()) return
        callLines += who to text
        scope.launch {
            db.chat().insert(com.past9.phoneaos.data.ChatItem(kind = if (who == "in") "user" else "agent", text = text,
                meta = JSONObject().put("call", true).toString()))
        }
    }

    private fun transcript(lastN: Int = 40) = callLines.takeLast(lastN).joinToString("\n") { (w, t) -> (if (w == "in") "User: " else "Voice: ") + t }

    private fun onEvent(e: LiveSession.Event) {
        val st = _state.value
        when (e) {
            is LiveSession.Event.SetupComplete -> { live?.openAudio(context.getSystemService(AudioManager::class.java)); _state.value = st.copy(phase = "listening") }
            is LiveSession.Event.Speaking -> { if (!e.on && bufWho == "out") flush(); _state.value = st.copy(phase = if (e.on) "speaking" else "listening") }
            is LiveSession.Event.Level -> _state.value = if (e.mine) st.copy(mine = e.value) else st.copy(theirs = e.value)
            is LiveSession.Event.Transcript -> {
                if (bufWho.isNotEmpty() && bufWho != e.who) flush()
                bufWho = e.who; buf.append(e.text)
                _state.value = st.copy(lines = (st.lines + (e.who to e.text)).takeLast(6))
            }
            is LiveSession.Event.ToolCall -> when (e.name) {
                "delegate_to_agent" -> {
                    val task = e.args.optString("task")
                    flush()
                    // The main agent gets the user's own words from the call, not just the voice's summary.
                    runtime.send("Task from our voice call: $task\n\nWhat was said on the call so far:\n${transcript()}", fromCall = task)
                    _state.value = st.copy(delegated = task)
                    // One thing at a time: if the main agent is blocked on a question, this waits in line behind it, so say so.
                    val waiting = runtime.pendingQuestionId()
                    live?.sendToolResult(e.id, e.name, if (waiting == null) JSONObject().put("status", "started").put("note", "The main agent is working on it. You'll get an update.")
                        else JSONObject().put("status", "queued").put("note", "Queued: the main agent first needs the user's answer to the question it asked. Get that answered (answer_agent_question), then this runs."))
                }
                "answer_agent_question" -> scope.launch {
                    val id = runtime.pendingQuestionId()
                    val item = id?.let { db.chat().get(it) }
                    val answer = e.args.optString("answer").trim(); val extra = e.args.optString("context").trim()
                    val res = when {
                        item == null -> JSONObject().put("error", "The main agent isn't waiting on anything right now.")
                        item.text.startsWith("APPROVAL|") && !Regex("^(no|decline|don't|do not|cancel|stop)", RegexOption.IGNORE_CASE).containsMatchIn(answer) ->
                            JSONObject().put("error", "This needs the user to hold Approve on the card on the call screen. Declining by voice is fine.")
                        else -> {
                            val opts = JSONObject(item.meta).optJSONArray("options")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                            // Match a spoken answer to an option where it clearly is one; otherwise pass the words on.
                            val picked = opts.firstOrNull { it.equals(answer, true) } ?: opts.firstOrNull { answer.contains(it, true) || it.contains(answer, true) }
                            val final = when {
                                item.text.startsWith("APPROVAL|") -> "Decline"
                                picked != null && extra.isBlank() -> picked
                                else -> (picked ?: answer) + if (extra.isNotBlank()) " (from our voice call: $extra)" else ""
                            }
                            runtime.answer(item.id, final)
                            JSONObject().put("status", "answered").put("answer", final)
                        }
                    }
                    live?.sendToolResult(e.id, e.name, res)
                }
                "agent_status" -> scope.launch {
                    val lastAgent = db.chat().all().first().lastOrNull { it.kind == "agent" }?.text.orEmpty()
                    val stNow = runtime.status.value
                    live?.sendToolResult(e.id, e.name, JSONObject().put("working", stNow.working).put("doing", stNow.label).put("last_message", lastAgent.take(800)))
                }
                else -> live?.sendToolResult(e.id, e.name, JSONObject().put("error", "unknown tool"))
            }
            is LiveSession.Event.Failed -> { _state.value = st.copy(active = false, phase = "failed", error = e.error); cleanup() }
            is LiveSession.Event.Closed -> { _state.value = st.copy(active = false, phase = "ended"); cleanup() }
            else -> {}
        }
    }

    fun toggleMute() { val m = !_state.value.muted; live?.setMuted(m); _state.value = _state.value.copy(muted = m) }

    fun end() { live?.stop(); _state.value = _state.value.copy(active = false, phase = "ended"); cleanup() }

    private fun cleanup() {
        CallService.stop(context)
        flush()
        // The call becomes part of the conversation: the main agent remembers what was said.
        if (callLines.isNotEmpty()) runtime.rememberCall(transcript(80))
        callLines.clear()
        watcher?.cancel(); watcher = null; live = null
    }
}
