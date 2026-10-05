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
        .put(JSONObject().put("name", "agent_status")
            .put("description", "What the main agent is doing right now and the last thing it said.")
            .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject())))))

    fun start() {
        if (_state.value.active) return
        val key = settings.voiceKey("gemini") ?: run { _state.value = CallState(phase = "failed", error = "Add a Google (Gemini) key in Settings, Voice."); return }
        val s = settings.state.value
        val name = s.agentName.takeIf { it != "Your agent" } ?: "your agent"
        val system = """
            You are $name, ${s.userName.ifBlank { "the user" }}'s personal agent, talking on a voice call. Be warm, quick and natural; short sentences; this is speech.
            You are the voice. The real work is done by the main agent: for anything that needs doing, finding, checking or remembering, call delegate_to_agent with the full task, say a quick 'on it', and keep the conversation going.
            When you get an update from the main agent, tell the user the part that matters in a sentence or two. Never invent results.
        """.trimIndent()
        _state.value = CallState(active = true, phase = "connecting")
        live = LiveSession(key, "gemini-3.1-flash-live-preview", s.ttsVoice.takeIf { s.ttsProvider == "gemini" && it.isNotBlank() } ?: "Kore", system, tools = tools) { e -> onEvent(e) }.also { it.start() }
        // Read the main agent's new messages and questions into the call.
        watcher = scope.launch {
            var last = db.chat().all().first().lastOrNull()?.id ?: 0L
            db.chat().all().collect { items ->
                items.filter { it.id > last }.forEach { i ->
                    when (i.kind) {
                        "agent" -> live?.announce("[Update from the main agent, tell the user briefly] ${i.text.take(1200)}")
                        "question" -> live?.announce("[The main agent needs the user to decide] ${i.text.removePrefix("APPROVAL|").take(600)}. Ask them; they can also answer in the app.")
                    }
                }
                last = items.lastOrNull()?.id ?: last
            }
        }
    }

    private fun onEvent(e: LiveSession.Event) {
        val st = _state.value
        when (e) {
            is LiveSession.Event.SetupComplete -> { live?.openAudio(context.getSystemService(AudioManager::class.java)); _state.value = st.copy(phase = "listening") }
            is LiveSession.Event.Speaking -> _state.value = st.copy(phase = if (e.on) "speaking" else "listening")
            is LiveSession.Event.Level -> _state.value = if (e.mine) st.copy(mine = e.value) else st.copy(theirs = e.value)
            is LiveSession.Event.Transcript -> _state.value = st.copy(lines = (st.lines + (e.who to e.text)).takeLast(6))
            is LiveSession.Event.ToolCall -> when (e.name) {
                "delegate_to_agent" -> {
                    val task = e.args.optString("task")
                    runtime.send(task)
                    _state.value = st.copy(delegated = task)
                    live?.sendToolResult(e.id, e.name, JSONObject().put("status", "started").put("note", "The main agent is working on it. You'll get an update."))
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

    private fun cleanup() { watcher?.cancel(); watcher = null; live = null }
}
