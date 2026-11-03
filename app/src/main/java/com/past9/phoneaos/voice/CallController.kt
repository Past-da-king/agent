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
    /** The agent's latest reply since the call started, shown under "Working on". */
    val reply: String = "",
    val muted: Boolean = false,
    val error: String = "",
)

/**
 * A live voice call with the agent. The voice (Gemini Live) IS the agent speaking: it chats, and starts anything
 * that needs doing in its own main chat (or a project chat, or a helper) with start_work. The work runs as usual
 * (tools, browser, apps) and its results are read back into the call as they land.
 */
class CallController(
    private val context: Context, private val localDb: AppDb, private val settings: SettingsStore, private val runtime: AgentRuntime, private val scope: CoroutineScope,
    /** The link while the phone uses a computer's agent, else null. */
    private val linked: () -> com.past9.phoneaos.remote.RemoteSession? = { com.past9.phoneaos.remote.RemoteHub.session.value },
) {
    private val _state = MutableStateFlow(CallState())
    val state: StateFlow<CallState> = _state
    private var live: LiveSession? = null
    private var watcher: Job? = null
    /** The link this call started on: its chat is the computer's, and so are its questions and status. */
    private var remote: com.past9.phoneaos.remote.RemoteSession? = null
    private val db: AppDb get() = remote?.db ?: localDb

    /** The voice runs on Gemini Live with this phone's Google key, alone or linked (the work runs wherever the agent does). */
    val available: Boolean get() = unavailableReason(linked() != null) == null

    /** Why this phone can't place a call right now, in plain words, or null when it can. */
    fun unavailableReason(linked: Boolean): String? = reason(settings.voiceKey("gemini") != null, linked)

    /** Linked: the computer mints a one-use token from its own key; if it has none, this phone's key (if any) is used. */
    private suspend fun linkedAuth(r: com.past9.phoneaos.remote.RemoteSession): LiveAuth.Token = try {
        LiveAuth.parse(r.call("voice.liveToken", emptyList(), 20_000))
    } catch (e: Exception) {
        LiveAuth.Token.No(if (LiveAuth.isOldComputer(e.message)) LiveAuth.OLD_COMPUTER else e.message ?: "Your computer didn't answer the call request.", false)
    }

    companion object {
        fun reason(hasGeminiKey: Boolean, linked: Boolean): String? = when {
            hasGeminiKey -> null
            // The linked computer starts the call with its own Google key (voice.liveToken); it says so if it has none.
            linked -> null
            else -> "Calls run on Gemini Live. Add a Google (Gemini) key in Settings, Voice, then tap the phone again."
        }

        /** The question the computer's agent is waiting on in a linked chat: the newest open question or approval card. */
        fun openQuestion(items: List<com.past9.phoneaos.data.ChatItem>): com.past9.phoneaos.data.ChatItem? =
            items.lastOrNull { (it.kind == "question" || it.kind == "approval") && !JSONObject(it.meta).has("answer") }

        fun isApproval(i: com.past9.phoneaos.data.ChatItem) = i.kind == "approval" || i.text.startsWith("APPROVAL|") ||
            runCatching { JSONObject(i.meta).optString("remoteKind") }.getOrNull() == "approval"

        /** What an approval card asks, in a few words ("send the invoice to Shey"). */
        fun approvalWhat(i: com.past9.phoneaos.data.ChatItem): String =
            if (i.text.startsWith("APPROVAL|")) i.text.split("|").getOrElse(1) { "" }.trim() else i.text.trim()

        /** The user really said yes to it, in their own words on the call: the only way an approval goes through by voice. */
        fun saidYes(words: String): Boolean {
            val w = words.trim().lowercase()
            if (w.isEmpty() || Regex("\\b(no|nope|don't|do not|not|cancel|stop|decline|wait|hold on)\\b").containsMatchIn(w)) return false
            return Regex("\\b(yes|yeah|yep|yup|sure|ok|okay|approve|approved|go ahead|do it|send it|go for it|confirm|please do|correct|fine)\\b").containsMatchIn(w)
        }

        fun isNo(answer: String) = Regex("^(no|nope|decline|don't|do not|cancel|stop)", RegexOption.IGNORE_CASE).containsMatchIn(answer.trim())

        /**
         * The voice's instructions. The voice talks; the agent does. Linked, the agent is the computer's full agent (the
         * same one he types to), so the voice must never say it can't: on 22 Oct 2026 it refused "delegate, talk to
         * someone, send it over" because it believed it had no way to act.
         */
        fun systemPrompt(name: String, userName: String, linked: Boolean): String {
            val who = userName.ifBlank { "the user" }
            val where = if (linked) "on their computer, with everything you have there" else "on this phone"
            // One identity (22 Oct 2026): the voice IS the agent, never "the voice of" someone else. Its main chat,
            // project chats and helpers are how it works, so it speaks of all of it in the first person.
            return """
                You are $name, $who's personal agent, now speaking with them on a voice call: the same one they type to $where. Be warm, quick and natural; short sentences; this is speech.
                There is only you. Speak in the first person about everything you do: "I'll do that", "I'm on it", "I've started a helper on it", "I'll check". Never speak of yourself as someone else, and never say you are passing work on to anyone: your main chat, your project chats and your helpers are simply how you work.
                You can do ANYTHING they ask: send emails and messages, reply to people, talk to or message someone for them, start helpers, use their connected apps (mail, calendar, Slack, WhatsApp and the rest), browse the web, run things, read and write files, remember things, manage tasks, routines and reminders, and look anything up.
                So NEVER say you can't do something, that you have no access, or that the user should do it themselves. For anything that needs doing, finding, checking, sending or remembering, call start_work with the full request and every detail the user gave (names, who to, what to say), say a quick "I'm on it", and keep talking. If it's unclear, ask one short question first, then start it.
                When an update on your work arrives, tell the user the part that matters in a sentence or two, as your own news ("I've sent it", "I found three"). Never invent results; if you don't know yet, say you're still on it (work_status tells you).
                When your work needs an answer from the user, it waits while the call carries on. Put the question to them out loud at a good moment. Take their answer (or decide if they tell you to) and call answer_question with it plus anything from the call that matters.
                When you need the user's OK, say plainly what you want to do and ask, for example "I need your OK to send the invoice to Shey. Approve?". If they clearly say yes, call answer_question with answer "Approve" and their exact words in user_said. If they say no, answer "Decline". Never approve on your own. They can also tap the card on the call screen. If they need to DO something (sign in, type a code), tell them exactly what to tap.
            """.trimIndent() + if (!linked) "" else "\n" + """
                Where the work goes (start_work target): when the user names a project ("tell the Agent Desktop App project to..."), a chat in one, or a helper ("ask the dictation helper..."), put that name in target as they said it; "start something new in project X" also sets new_chat. Anything not aimed at a project or helper goes to target "main", your main chat, which routes it from there. But when they DO name one and the name fits more than one in the list below (say "Acme" while there are Acme Website and Acme Inc), don't guess and don't send it to main: ask out loud which one ("The Website or Inc?"), then start it with that exact name. The same when a result says two names are close. Once it starts, say where in a few words ("I'm on it in the Acme Website project").
            """.trimIndent()
        }

        /** Who is on the computer, for the voice: the main agent, projects with their chats, helpers at work (voice.callTargets). */
        fun roster(targets: JSONArray?): String {
            if (targets == null || targets.length() == 0) return ""
            val lines = (0 until targets.length()).mapNotNull { targets.optJSONObject(it) }.map { t ->
                val doing = t.optString("doing").let { if (it.isBlank()) "" else ": $it" }
                val state = if (t.optBoolean("running")) "working$doing" else if (t.optString("kind") == "helper") "finished$doing" else "idle"
                when (t.optString("kind")) {
                    "main" -> "- Your main chat (target main), $state"
                    "project" -> {
                        val chats = t.optJSONArray("chats")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
                        val chatText = if (chats.isEmpty()) "no chats yet" else chats.takeLast(4).joinToString("; ") { c -> "\"" + c.optString("title").take(60) + "\"" + if (c.optBoolean("running")) " (working)" else "" }
                        "- Your project ${t.optString("name")}, $state. Chats: $chatText"
                    }
                    else -> "- Your helper \"${t.optString("name")}\", $state"
                }
            }
            return "Where your work can go on their computer right now (targets for start_work):\n" + lines.joinToString("\n")
        }

        fun callTools(linked: Boolean): JSONArray = JSONArray().put(JSONObject().put("functionDeclarations", JSONArray()
            .put(JSONObject().put("name", "start_work")
                .put("description", (if (linked) "Start ANY request yourself, on their computer where you have everything: it goes in as a message in your chat there. " else "Start ANY request yourself: it goes in as a message in your main chat. ") +
                    "You can send emails and messages, contact people, start helpers, use every connected app, browse, run things, read and write files, memory, tasks and routines. Use it for anything that needs doing or looking up; never tell the user you can't. Say a short \"I'm on it\" and keep chatting; results come back to you.")
                .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()
                    .put("task", JSONObject().put("type", "STRING").put("description", "The request, clearly, with every detail the user gave (who, what, where, wording)"))
                    .apply { if (linked) {
                        put("target", JSONObject().put("type", "STRING").put("description", "Where it goes: \"main\" (the default: your main chat, which routes it), or one of your projects, project chats or helpers by the name the user used"))
                        put("new_chat", JSONObject().put("type", "BOOLEAN").put("description", "For a project: start a new chat there (\"start something new in X\")"))
                    } })
                    .put("required", JSONArray().put("task"))))
            .put(JSONObject().put("name", "answer_question")
                .put("description", "Answer what your work is waiting on: a question (the user's answer, or your choice if they told you to decide) or an approval ('Approve' only after the user clearly said yes to it out loud, with their words in user_said; 'Decline' if they said no).")
                .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()
                    .put("answer", JSONObject().put("type", "STRING").put("description", "One of the options if there are options (Approve or Decline for an approval), otherwise the user's words"))
                    .put("user_said", JSONObject().put("type", "STRING").put("description", "For an approval: the user's own words agreeing, exactly as said"))
                    .put("context", JSONObject().put("type", "STRING").put("description", "Anything else from the call you need to act on it")))
                    .put("required", JSONArray().put("answer"))))
            .put(JSONObject().put("name", "work_status")
                .put("description", "What you are working on right now, the last thing you said in the chat, and anything you are waiting on the user for.")
                .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject())))))

        /** What the voice is told when the agent needs the user, so it says it out loud. */
        fun cardPrompt(i: com.past9.phoneaos.data.ChatItem): String {
            val opts = runCatching { JSONObject(i.meta).optJSONArray("options") }.getOrNull()?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            val by = runCatching { JSONObject(i.meta).optString("whyBy") }.getOrDefault("")
            val who = if (by.isNotBlank()) "Your helper $by needs" else "You need"
            return if (isApproval(i)) "[$who the user's OK to: ${approvalWhat(i).take(400)}. Ask them now, out loud, in one short line, e.g. 'I need your OK to ${approvalWhat(i).take(80)}. Approve?'. Yes: call answer_question with Approve and their words in user_said. No: Decline. The card is also on the call screen.]"
            else { val q = com.past9.phoneaos.tools.ConnectRequest.parse(i.text)?.summary ?: i.text
                "[$who the user's answer to this. Ask them, then call answer_question] ${q.take(600)}" + if (opts.isNotEmpty()) " Options: ${opts.joinToString(", ")}." else "" }
        }
    }

    private suspend fun pendingQuestion(): com.past9.phoneaos.data.ChatItem? =
        if (remote != null) openQuestion(db.chat().all().first()) else runtime.pendingQuestionId()?.let { db.chat().get(it) }

    private fun statusNow() = remote?.status?.value ?: runtime.status.value

    private val tools get() = callTools(remote != null)

    fun start() {
        if (_state.value.active) return
        remote = linked()
        val r = remote
        val key = settings.voiceKey("gemini")
        if (r == null && key == null) { _state.value = CallState(phase = "failed", error = unavailableReason(false).orEmpty()); return }
        val s = settings.state.value
        val name = s.agentName.takeIf { it != "Your agent" } ?: "your agent"
        val system = systemPrompt(name, s.userName, r != null)
        _state.value = CallState(active = true, phase = "connecting")
        // Pre-flight first (the key, or the computer's one-use token); the call service starts only once the call really
        // can, in begin(). A refusal is a message on the call screen, never a started-then-stopped service (that crashed
        // the app on 22 Oct 2026 when a computer on 0.2.47 refused the call). The call screen stays in front meanwhile,
        // which is what Android requires for the mic.
        scope.launch {
            if (r == null) { begin(LiveAuth.keyUrl(key!!), null, system, null, null); return@launch }
            when (val t = linkedAuth(r)) {
                is LiveAuth.Token.Ok -> begin(t.url, { (kotlinx.coroutines.runBlocking { linkedAuth(r) } as? LiveAuth.Token.Ok)?.url }, system, t.model, t.voice)
                // No key on the computer but one here: the call still goes, on this phone's key.
                is LiveAuth.Token.No -> if (key != null) begin(LiveAuth.keyUrl(key), null, system, null, null)
                    else { _state.value = CallState(phase = "failed", error = t.why); remote = null }
            }
        }
    }

    /** Gives the voice the last stretch of the chat and the user's pinned facts, so it picks up mid-thread. */
    private suspend fun begin(url: String, renew: (() -> String?)?, base: String, model: String?, voice: String?) {
        val s = settings.state.value
        val recent = db.chat().all().first().filter { it.kind == "user" || it.kind == "agent" }.takeLast(10)
            .joinToString("\n") { (if (it.kind != "user") "You: " else com.past9.phoneaos.remote.Sender.of(it.meta)?.let { s -> s.line + ": " } ?: "User: ") + it.text.take(400) }
        val pinned = db.memory().pinned().joinToString("\n") { "- ${it.title}: ${it.body.take(200)}" }
        // Something already waiting on him when the call starts: the voice brings it up.
        val waiting = pendingQuestion()
        // Linked: who is on the computer (main agent, projects and their chats, helpers), so a hand-off goes to the one he names.
        val who = remote?.let { roster(fetchTargets(it)) }.orEmpty()
        val system = base + (if (pinned.isNotBlank()) "\n\nWhat you know about them:\n$pinned" else "") +
            (if (who.isNotBlank()) "\n\n$who" else "") +
            (if (recent.isNotBlank()) "\n\nThe chat so far (this call continues it):\n$recent" else "") +
            (if (waiting != null) "\n\nWaiting on the user right now (bring it up early): ${cardPrompt(waiting)}" else "")
        callStart = System.currentTimeMillis(); callLines.clear(); callTimes.clear()
        // Ended while the token was on its way: nothing to start.
        if (!_state.value.active) { remote = null; return }
        CallService.start(context)
        // A computer's token is locked to the Live model its key reaches, so the call uses that model.
        val phoneVoice = s.ttsVoice.takeIf { s.ttsProvider == "gemini" && it.isNotBlank() }
        live = LiveSession(url, model?.ifBlank { null } ?: "gemini-3.1-flash-live-preview", phoneVoice ?: voice?.ifBlank { null } ?: "Kore", system,
            tools = tools, renew = renew) { e -> onEvent(e) }.also { it.start() }
        // Read the main agent's new messages and questions into the call.
        // By id seen, not "newer than the last id": a linked computer's rows are numbered by ITS clock, and a helper's
        // card can land with an older number than the newest row.
        watcher = scope.launch {
            val seen = HashSet<Long>(); db.chat().all().first().forEach { seen += it.id }
            val open = HashMap<Long, Boolean>() // card id -> is approval, while it waits
            waiting?.let { open[it.id] = isApproval(it) }
            db.chat().all().collect { items ->
                items.filter { it.id !in seen }.forEach { i ->
                    seen += i.id
                    if (JSONObject(i.meta).optBoolean("call")) return@forEach
                    when (i.kind) {
                        "agent" -> if (i.text.isNotBlank()) {
                            _state.value = _state.value.copy(reply = i.text.take(400))
                            tell("[An update on your own work, from your main chat. Tell the user the part that matters, briefly, in the first person] ${i.text.take(1200)}")
                        }
                        "question", "approval" -> if (!JSONObject(i.meta).has("answer")) { open[i.id] = isApproval(i); tell(cardPrompt(i)) }
                    }
                }
                // A card answered anywhere (a tap here, the chat, the computer): the voice stops asking about it.
                open.keys.toList().forEach { id ->
                    val now = items.firstOrNull { it.id == id }
                    val ans = now?.let { JSONObject(it.meta).optString("answer") }.orEmpty()
                    if (now == null || ans.isNotEmpty()) {
                        val approval = open.remove(id) == true
                        if (ans.isNotEmpty()) tell(if (approval) "[The user ${if (ans == "Approve") "approved" else "declined"} it (${approvalWhat(now!!).take(120)}). It is already answered: acknowledge in a few words, don't ask again and don't call answer_question for it.]"
                            else "[That question was answered: $ans. Don't ask it again or answer it.]")
                    }
                }
            }
        }
        // A project chat or helper the call handed work to: its outcome comes back from the computer's live cards.
        remote?.let { r -> cardWatcher = scope.launch {
            r.cards.collect { list ->
                list ?: return@collect
                sentTo.toList().forEach { (key, name) ->
                    val c = list.firstOrNull { if (key.startsWith("h:")) it.id == key.removePrefix("h:") else it.projectChat == key.removePrefix("p:") } ?: return@forEach
                    if (c.working) { workingSeen += key; return@forEach }
                    if (key !in workingSeen && c.endedAt in 1 until (sentAt[key] ?: 0L)) return@forEach
                    sentTo.remove(key); workingSeen.remove(key)
                    val what = c.result.ifBlank { c.done }.ifBlank { c.line }
                    _state.value = _state.value.copy(reply = "$name: ${what.take(360)}")
                    tell("[$name finished. Tell the user the part that matters, briefly, as your own work] ${what.take(1200)}")
                }
            }
        } }
    }

    /** Project chats and helpers the call sent work to ("p:<chat>" or "h:<id>") and their names, until they finish. */
    private val sentTo = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val sentAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val workingSeen: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var cardWatcher: Job? = null

    /** voice.callTargets from the computer, or null on one too old to have it. */
    private suspend fun fetchTargets(r: com.past9.phoneaos.remote.RemoteSession): JSONArray? =
        runCatching { r.call("voice.callTargets", emptyList(), 15_000) as? JSONArray }.getOrNull()

    /**
     * Linked: puts the call's request in front of the agent it names (voice.callHandoff on the computer): the main
     * agent, a project's chat (or a new one), or a helper. Returns the tool result for the voice.
     */
    private suspend fun handOff(r: com.past9.phoneaos.remote.RemoteSession, task: String, target: String, newChat: Boolean, message: String): JSONObject {
        val res = try {
            r.call("voice.callHandoff", listOf(JSONObject().put("target", target).put("message", message).put("newChat", newChat)), 30_000) as? JSONObject
        } catch (e: Exception) {
            // A computer older than voice.callHandoff: the main agent gets it (it can reach projects and helpers itself).
            if (LiveAuth.isOldComputer(e.message)) {
                val routed = if (target.isBlank() || target.equals("main", true)) message else "For $target: $message"
                runtime.send(routed, fromCall = task)
                _state.value = _state.value.copy(delegated = task)
                return JSONObject().put("status", "sent").put("sent_to", "your main chat").put("note", "Started in your main chat, which routes it" + (if (target.isNotBlank() && !target.equals("main", true)) " to $target" else "") + ". Tell the user you're on it.")
            }
            return JSONObject().put("status", "failed").put("error", "Couldn't reach the computer: ${e.message}. Tell the user and offer to try again.")
        } ?: return JSONObject().put("status", "failed").put("error", "The computer didn't answer.")
        if (!res.optBoolean("ok")) {
            val close = res.optJSONArray("ambiguous")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") } }.orEmpty()
            return if (close.size > 1) JSONObject().put("status", "which_one").put("options", JSONArray(close))
                .put("note", "Not started: ask the user out loud which one they mean (${close.joinToString(" or ")}), then call start_work again with that exact name as target.")
            else JSONObject().put("status", "not_sent").put("error", res.optString("error"))
        }
        val kind = res.optString("kind"); val name = res.optString("name")
        val label = when (kind) { "main" -> "Main chat"; "helper" -> "Helper $name"; else -> name }
        _state.value = _state.value.copy(delegated = if (kind == "main") task else "$label: $task")
        when (kind) {
            "helper" -> { sentTo["h:" + res.optString("id")] = "Your helper $name"; sentAt["h:" + res.optString("id")] = System.currentTimeMillis() }
            "project", "chat" -> res.optString("chat").takeIf { it.isNotBlank() }?.let { sentTo["p:$it"] = "Your work in the $name project"; sentAt["p:$it"] = System.currentTimeMillis() }
        }
        return JSONObject().put("status", "sent").put("sent_to", label).put("said", res.optString("said"))
            .put("note", "Tell the user in a few words, in the first person: ${res.optString("said")} The result comes to you as an update.")
    }

    var callStart = 0L
        private set
    /** The whole call as said, in order, for the main agent and for the chat. */
    private val callLines = mutableListOf<Pair<String, String>>()
    /** When each line was said, for the computer's copy of the call. */
    private val callTimes = mutableListOf<Long>()
    private var bufWho = ""; private val buf = StringBuilder()

    /** A speaker finished a stretch: put it in the chat as one bubble, like any message. */
    private fun flush() {
        val text = buf.toString().trim(); val who = bufWho
        buf.clear(); bufWho = ""
        if (text.isBlank()) return
        callLines += who to text
        callTimes += System.currentTimeMillis()
        // Linked: the chat is the computer's mirror, so phone-only bubbles would not belong there. The computer gets
        // the call's words with each task the voice hands it (start_work carries the transcript so far).
        if (remote != null) return
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
            is LiveSession.Event.ToolCall -> { android.util.Log.i("CallAgent", "tool call ${e.name}: ${e.args.toString().take(400)}"); when (e.name) {
                "start_work" -> {
                    val task = e.args.optString("task")
                    flush()
                    // The agent gets the user's own words from the call, not just the voice's summary.
                    val message = "Task from our voice call: $task\n\n(Reply in a line or two: it is read out on the call.)\n\nWhat was said on the call so far:\n${transcript()}"
                    val r = remote
                    // Linked: a real turn on the computer, in the chat of the agent he named (voice.callHandoff: the main
                    // chat, a project chat, or a helper), so it is done with everything the computer's agent has; its
                    // replies, questions and approvals come back here.
                    if (r != null) {
                        val target = e.args.optString("target").trim()
                        scope.launch { toolResult(e.id, e.name, handOff(r, task, target, e.args.optBoolean("new_chat"), message)) }
                        return
                    }
                    runtime.send(message, fromCall = task)
                    _state.value = st.copy(delegated = task)
                    // One thing at a time: if the main agent is blocked on a question, this waits in line behind it, so say so.
                    scope.launch {
                        val waiting = pendingQuestion()
                        toolResult(e.id, e.name, if (waiting == null) JSONObject().put("status", "started").put("note", "Started in your main chat; the result comes to you as an update. Tell the user you're on it.")
                            else JSONObject().put("status", "queued").put("note", "Queued: you first need the user's answer to what you asked (${approvalWhat(waiting).take(120)}). Get that answered (answer_question), then this runs."))
                    }
                }
                "answer_question" -> scope.launch {
                    val item = pendingQuestion()
                    val answer = e.args.optString("answer").trim(); val extra = e.args.optString("context").trim()
                    // What the user actually said lately (the call's own transcript, not the model's say-so).
                    val heard = (callLines.takeLast(6).filter { it.first == "in" }.takeLast(2).map { it.second } + (if (bufWho == "in") listOf(buf.toString()) else emptyList())).joinToString(" ")
                    val res = when {
                        item == null -> JSONObject().put("error", "Nothing is waiting on the user right now.")
                        isApproval(item) && !isNo(answer) && !saidYes(heard) ->
                            JSONObject().put("error", "Not approved: the user hasn't clearly said yes to this on the call. Ask them ('${approvalWhat(item).take(80)}. Approve?') and wait for a clear yes, or they can tap Approve on the card.")
                        else -> {
                            val opts = JSONObject(item.meta).optJSONArray("options")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                            // Match a spoken answer to an option where it clearly is one; otherwise pass the words on.
                            val picked = opts.firstOrNull { it.equals(answer, true) } ?: opts.firstOrNull { answer.contains(it, true) || it.contains(answer, true) }
                            val final = when {
                                isApproval(item) -> if (isNo(answer)) "Decline" else "Approve"
                                picked != null && extra.isBlank() -> picked
                                else -> (picked ?: answer) + if (extra.isNotBlank()) " (from our voice call: $extra)" else ""
                            }
                            runtime.answer(item.id, final)
                            JSONObject().put("status", "answered").put("answer", final)
                        }
                    }
                    toolResult(e.id, e.name, res)
                }
                "work_status" -> scope.launch {
                    val lastAgent = db.chat().all().first().lastOrNull { it.kind == "agent" }?.text.orEmpty()
                    val stNow = statusNow()
                    val waiting = pendingQuestion()
                    val who = remote?.let { roster(fetchTargets(it)) }.orEmpty()
                    toolResult(e.id, e.name, JSONObject().put("working", stNow.working).put("doing", stNow.label).put("last_message", lastAgent.take(800))
                        .put("waiting_on_user", waiting?.let { if (isApproval(it)) "Approval: ${approvalWhat(it).take(300)}" else it.text.take(300) } ?: "")
                        .apply { if (who.isNotBlank()) put("everyone", who) })
                }
                else -> toolResult(e.id, e.name, JSONObject().put("error", "unknown tool"))
            } }
            is LiveSession.Event.Failed -> { _state.value = st.copy(active = false, phase = "failed", error = e.error); cleanup() }
            is LiveSession.Event.Closed -> { _state.value = st.copy(active = false, phase = "ended"); cleanup() }
            else -> {}
        }
    }

    /** Something for the voice to say or know (an update, a card, an answer), logged under CallAgent. */
    private fun tell(text: String) {
        android.util.Log.i("CallAgent", "tell voice: ${text.take(400)}")
        live?.announce(text)
    }

    /** Every tool result goes back to the voice through here, and into logcat (tag CallAgent) for proof and support. */
    private fun toolResult(id: String, name: String, result: JSONObject) {
        android.util.Log.i("CallAgent", "tool result $name: ${result.toString().take(600)}")
        live?.sendToolResult(id, name, result)
    }

    /** Debug probe (src/debug CallProbe): the words go to the Live model as if the user said them. */
    fun probeSay(text: String) { live?.announce(text) }

    /** Debug probe: as if the model called [name] (for a run without a Live session). */
    fun probeTool(name: String, args: JSONObject) { onEvent(LiveSession.Event.ToolCall("probe-" + System.currentTimeMillis(), name, args)) }

    fun toggleMute() { val m = !_state.value.muted; live?.setMuted(m); _state.value = _state.value.copy(muted = m) }

    fun end() { live?.stop(); _state.value = _state.value.copy(active = false, phase = "ended"); cleanup() }

    private fun cleanup() {
        CallService.stop(context)
        flush()
        // The call becomes part of the conversation: the main agent remembers what was said.
        if (callLines.isNotEmpty() && remote == null) runtime.rememberCall(transcript(80))
        // Linked: the call lands in the computer's chat as one call row, like a call held there.
        val r = remote
        if (callLines.isNotEmpty() && r != null) {
            val record = LiveAuth.record(callStart, System.currentTimeMillis(), callLines.mapIndexed { i, (w, t) -> Triple(w, t, callTimes.getOrElse(i) { callStart }) })
            scope.launch { runCatching { r.call("voice.callSave", listOf(record), 30_000) } }
        }
        callLines.clear(); callTimes.clear()
        watcher?.cancel(); watcher = null; cardWatcher?.cancel(); cardWatcher = null
        sentTo.clear(); sentAt.clear(); workingSeen.clear()
        live = null; remote = null
    }
}
