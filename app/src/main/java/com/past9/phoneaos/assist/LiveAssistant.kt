package com.past9.phoneaos.assist

import android.content.Context
import android.media.AudioManager
import android.util.Base64
import android.util.Log
import com.past9.phoneaos.Graph
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.remote.RemoteHub
import com.past9.phoneaos.voice.CallCore
import com.past9.phoneaos.voice.Earcon
import com.past9.phoneaos.voice.LiveAuth
import com.past9.phoneaos.voice.LiveSession
import com.past9.phoneaos.voice.LiveTiming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One live voice session inside the assist sheet (Settings > Default assistant > Live voice in the assistant). It is
 * owned by [LiveHost] and outlives the sheet, so it keeps going while the sheet is minimised to a bubble.
 *
 * Gemini Live is the voice: it listens, answers aloud at once, can be talked over, and SEES the screen through frames
 * ([LiveFrames]), so questions about the screen are answered by Live itself. Real work goes through hand_to_agent, which
 * puts a job in front of the same agent the typed sheet talks to (this phone's, or the linked computer's). The agent's
 * reply, questions and approvals come back through the chat rows. Approvals are still the app's own hold-to-approve card.
 *
 * A live session is a CALL: it is recorded the way the app already records voice calls (one collapsed call block in the
 * chat, kind "live_assistant", with each hand-off listed inside it), and a hand-off never shows as something the user typed.
 *
 * Start-up is built to be fast: the microphone and speaker open at once and what he says is kept until the connection
 * is ready; the token is asked for while the sheet is still opening ([LiveToken.warm]); a soft earcon says "listening".
 */
class LiveAssistant(
    private val context: Context,
    private val g: Graph,
    private val scope: CoroutineScope,
    private val source: () -> String,
    /** True while the sheet is minimised to a bubble: the screen is then read fresh at hand-off time, and quiet lasts longer. */
    private val minimised: () -> Boolean,
    /** The view changed: redraw. */
    private val onChange: (LiveView) -> Unit,
    /** The session is over (goodbye, idle, tap, or it could not start). [reason] is blank when it ended normally. */
    private val onEnded: (reason: String) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    val core = LiveAssistCore(clock)
    @Volatile private var session: LiveSession? = null
    @Volatile private var ended = false
    @Volatile private var starting = false
    @Volatile private var setupDone = false
    /** The context read at start, awaited just before the setup goes out. */
    private var ctxJob: kotlinx.coroutines.Deferred<AssistContext?>? = null
    /** The newest context the updates gave; the voice is primed with it when the snapshot could not be read. */
    @Volatile private var latest: AssistContext? = null
    @Volatile private var primed = false
    /** The context text the voice last has: the setup's, or the last quiet update's. */
    @Volatile private var sentText = ""
    @Volatile private var lastNote = 0L
    private var noteJob: Job? = null
    private var watch: Job? = null
    private var rows: List<ChatItem> = emptyList()
    private val lastId: Long get() = rows.lastOrNull()?.id ?: 0L
    private var startedAt = 0L
    private var frames: LiveFrames? = null
    @Volatile private var lastLoud = 0L

    val active: Boolean get() = !ended && (session != null || starting)

    private fun push() = onChange(core.current)

    /** Opens the session. Needs the microphone permission already. */
    fun start() {
        if (session != null || ended || starting) return
        starting = true
        LiveTiming.mark("start")
        startedAt = clock()
        // What is going on right now goes into the instructions, so the voice starts knowing it. It is read in PARALLEL with
        // the token (never on the microphone's time) and waited for, bounded, just before the setup goes out: it used to be
        // dropped after 700 ms, which left the voice blind to its agents whenever the link or the database was slow.
        ctxJob = scope.async(Dispatchers.IO) {
            runCatching { AssistContextSource.snapshot(context, g, core.refs, clock()) }.onFailure { Log.w(TAG, "context read failed: ${it.message}") }.getOrNull()
        }
        starting = false
        open()
    }

    private fun linked(): Boolean = RemoteHub.link.value?.on == true || RemoteHub.session.value != null

    private fun open() {
        val st = g.settings.state.value
        val name = st.agentName.takeIf { it.isNotBlank() && it != "Your agent" } ?: "your agent"
        val phoneVoice = st.ttsVoice.takeIf { st.ttsProvider == "gemini" && it.isNotBlank() }
        val live = LiveSession("", DEFAULT_MODEL, phoneVoice ?: "Kore", LiveAssistCore.systemPrompt(name, st.userName, linked(), null), tools = LiveAssistCore.tools(),
            renew = { kotlinx.coroutines.runBlocking { LiveToken.mint(context) as? LiveAuth.Token.Ok }?.url }) { e -> if (!ended) onEvent(e) }
        // Every setup (the first, and a fresh reconnect) is built from what is true then.
        live.systemFrom { LiveAssistCore.systemPrompt(name, g.settings.state.value.userName, linked(), core.context) }
        session = live
        // The microphone and speaker open now, in parallel with the token: nothing he says is lost.
        live.prepare(context.getSystemService(AudioManager::class.java))
        scope.launch {
            LiveTiming.mark("token_req")
            val auth = runCatching { LiveToken.take(context) }.getOrElse { LiveAuth.Token.No(it.message ?: "Live voice could not start.", false) }
            LiveTiming.mark("token_ok")
            if (ended) return@launch
            if (auth !is LiveAuth.Token.Ok) { fail((auth as LiveAuth.Token.No).why); return@launch }
            // The token is here and the context has had that long. Wait for it, bounded, then prime the voice with it.
            val c = withTimeoutOrNull(CONTEXT_WAIT_MS) { ctxJob?.await() } ?: latest
            if (ended) return@launch
            prime(c)
            live.connectTo(auth.url, auth.model.ifBlank { DEFAULT_MODEL }, if (phoneVoice == null) auth.voice else "")
        }
        // Nothing heard, nothing said and nothing out for a while: let go.
        watch = scope.launch { while (!ended) { delay(2_000); if (core.idle(minimised())) { Log.i(TAG, "idle, ending"); end("") } } }
        // Never stuck connecting: the microphone being up does not mean the connection is.
        scope.launch { delay(CallCore.CONNECT_TIMEOUT_MS); if (!ended && !setupDone) fail(CallCore.TIMED_OUT) }
    }

    /** The context the setup carries. Logged (trimmed) so a session can be checked against what the voice was told. */
    private fun prime(c: AssistContext?) {
        core.primed(c)
        primed = true
        sentText = c?.prompt().orEmpty()
        Log.i(TAG, "context primed: " + (if (c == null) "NONE (could not be read)" else "agents=${c.agents.size} known=${c.agentsKnown} asks=${c.asks.size} turns=${c.turns.size} chars=${sentText.length}"))
        if (c != null) Log.i(TAG, "context: " + sentText.replace('\n', '|').take(700))
    }

    /** The chat rows or the agent's working flag changed: pass on what Live should say. */
    fun onRows(r: List<ChatItem>, w: Boolean) {
        rows = r
        if (ended) return
        core.rows(r, w).forEach { tell(it) }
        push()
    }

    /**
     * The agents or the chat moved: refresh the strip, tell the voice of a new question, one answered elsewhere, an agent
     * that finished, and keep its picture of everything current with a quiet context update (no answer asked for).
     */
    fun onContext(c: AssistContext) {
        if (ended || session == null) return
        latest = c
        // Before the setup is out the voice is primed with the snapshot: nothing is told twice and nothing is lost.
        if (!primed) return
        core.contextChanged(c).forEach { tell(it) }
        push()
        refreshVoice(c)
    }

    /** Sends the whole current context as a note the model keeps, when it differs from what it was last given. */
    private fun refreshVoice(c: AssistContext) {
        if (c.prompt() == sentText) return
        noteJob?.cancel()
        noteJob = scope.launch {
            delay(NOTE_DEBOUNCE_MS)
            val gap = NOTE_GAP_MS - (clock() - lastNote)
            if (gap > 0) delay(gap)
            val now = (latest ?: c).prompt()
            if (now == sentText) return@launch
            repeat(12) {
                if (ended) return@launch
                if (session?.note(NOTE_HEAD + now) == true) {
                    sentText = now; lastNote = clock()
                    Log.i(TAG, "context update sent: " + now.replace('\n', '|').take(400))
                    return@launch
                }
                delay(1_500)
            }
        }
    }

    /** He picked one in the choice sheet: the answer goes to that question. */
    fun pickAsk(ask: CtxAsk, answer: String) {
        scope.launch {
            core.choose(null)
            val r = runCatching { sendAnswer(ask, AssistRouter.finalAnswer(ask, answer)) }
            if (r.isFailure) tell("[The answer did not go to ${ask.by.ifBlank { "your main chat" }}. Tell the user in one short line.]")
            else tell("[The user picked ${ask.by.ifBlank { "your main chat" }} for their answer and it was sent. Confirm in a few words.]")
            push()
        }
    }

    /** He closed the choice sheet without picking: nothing was sent, and the voice asks out loud. */
    fun dismissChoice() {
        if (core.current.choice == null) return
        core.choose(null); push()
        tell("[The user closed the choice without picking. Ask out loud which one their answer is for.]")
    }

    /** Sends an answer to the agent that waits, by the same path the cards use (this phone's agent, or the computer's), and says where it went. */
    private fun sendAnswer(ask: CtxAsk, answer: String) {
        g.runtime.answer(ask.id, answer)
        core.answeredHere(ask.id)
        val to = ask.by.ifBlank { g.settings.state.value.agentName.takeIf { it.isNotBlank() && it != "Your agent" } ?: context.getString(com.past9.phoneaos.R.string.svc_your_agent) }
        core.routed(context.getString(com.past9.phoneaos.R.string.assist_ctx_sent_to, to, answer.take(80)))
        scope.launch { delay(ROUTED_MS); core.routed(""); push() }
    }

    private fun answerAsk(e: LiveSession.Event.ToolCall) {
        val answer = e.args.optString("answer").trim()
        val said = e.args.optString("user_said").trim().ifBlank { core.spokenNow() }
        scope.launch {
            // The questions as they are this moment: one may have been answered a second ago somewhere else.
            val now = runCatching { AssistContextSource.snapshot(context, g, core.refs, clock()) }.getOrNull()
            val asks = now?.asks ?: core.asks
            when (val r = AssistRouter.forTool(e.args.optString("ref"), answer, said, asks)) {
                is Routed.Answer -> {
                    val sent = runCatching { sendAnswer(r.ask, r.answer) }
                    result(e, if (sent.isSuccess) JSONObject().put("status", "sent").put("sent_to", r.ask.by.ifBlank { "your main chat" }).put("note", "Confirm in one short line who it went to.")
                        else JSONObject().put("status", "failed").put("error", "It did not go: ${sent.exceptionOrNull()?.message.orEmpty()}. Tell the user and offer to try again."))
                }
                is Routed.Which -> {
                    core.choose(AskChoice(r.answer, r.candidates)); push()
                    result(e, JSONObject().put("status", "which_one").put("options", JSONArray(r.candidates.map { it.by.ifBlank { "main chat" } + ": " + it.text.take(80) }))
                        .put("note", "More than one question could take that. The screen is asking them to pick. Say 'which one is it for?' in a few words and wait; do not call answer_ask again."))
                }
                is Routed.Refused -> result(e, JSONObject().put("status", "not_sent").put("error", r.reason))
                Routed.None -> result(e, JSONObject().put("status", "not_sent").put("error", "Nothing is waiting on the user that this answers."))
            }
        }
    }

    private fun onEvent(e: LiveSession.Event) {
        when (e) {
            is LiveSession.Event.MicReady -> {
                // Listening now: speech is kept until the connection is ready. A soft sound says so.
                core.connected(); push()
                LiveTiming.mark("listening_ui")
                Earcon.play(context)
            }
            is LiveSession.Event.SetupComplete -> {
                setupDone = true
                // Live can see the screen from now on (when the switches allow it).
                if (frames == null) frames = LiveFrames(context, scope, { session }, { s -> core.seeing(s); push() }, { tell(it) }, clock).also { it.start() }
            }
            is LiveSession.Event.Level -> if (e.mine) userLevel(e.value) else if (core.level(e.value)) push()
            is LiveSession.Event.Transcript -> { core.caption(e.who, e.text); push() }
            is LiveSession.Event.Speaking -> {
                if (!e.on) Log.i(TAG, "turn: you=\"${core.current.you.take(160)}\" agent=\"${core.current.agent.take(160)}\"")
                if (core.speaking(e.on)) scope.launch { delay(400); end("") }
                push()
            }
            is LiveSession.Event.ToolCall -> handle(e)
            is LiveSession.Event.Failed -> fail(CallCore.plainError(e.error).ifBlank { "Live voice stopped." })
            is LiveSession.Event.Closed -> end("")
            else -> {}
        }
    }

    /** The microphone's loudness: a turn starting after a quiet moment gets a fresh look at the screen at once. */
    private fun userLevel(v: Float) {
        if (v < LOUD) return
        val now = clock()
        if (now - lastLoud > QUIET_MS) frames?.userTurnStarted()
        lastLoud = now
    }

    private fun handle(e: LiveSession.Event.ToolCall) {
        Log.i(TAG, "tool call ${e.name}: ${e.args.toString().take(300)}")
        if (e.name == LiveAssistCore.PLAY_MEDIA || e.name == LiveAssistCore.MEDIA_CONTROL) {
            scope.launch {
                val out = runCatching { com.past9.phoneaos.media.MediaTools.runDirect(context, e.name, e.args) }.getOrElse { "failed: ${it.message ?: "it did not run"}" }
                result(e, JSONObject().put(if (out.startsWith("failed")) "error" else "result", out))
            }
            return
        }
        if (e.name == LiveAssistCore.ANSWER_ASK) { answerAsk(e); return }
        if (e.name != LiveAssistCore.TOOL) { result(e, JSONObject().put("error", "unknown tool")); return }
        val request = e.args.optString("request").trim()
        if (request.isEmpty()) { result(e, JSONObject().put("error", "Nothing to do: say what the job is.")); return }
        val wantPicture = e.args.optBoolean("include_screen")
        // Aimed at a named project or helper (linked to a computer only): it goes there, as a voice call's hand-off does.
        val target = e.args.optString("target").trim().takeIf { it.isNotBlank() && !it.equals("main", true) && RemoteHub.session.value != null }
        scope.launch {
            // Minimised: he is in another app now, so what the sheet saw when it opened is out of date. Read the screen as it is at this moment.
            val screen0 = if (minimised()) AssistScreenNow.capture(context) else AssistHub.screen.value
            // The words on screen go along; the picture only when the job needs it (include_screen).
            val screen = if (wantPicture) screen0 else screen0?.copy(screenshot = "")
            val jpeg = if (wantPicture && screen0?.blocked != true) frames?.latestJpeg() else null
            val spoken = core.spokenNow()
            val text = LiveAssistCore.message(request, e.args.optString("context"), source(), screen, spoken)
            core.handOff(request, lastId, wantPicture && jpeg != null, track = target == null)
            push()
            val sent = runCatching { deliver(text, request, spoken, jpeg, target ?: "main") }
            sent.onSuccess { where -> result(e, JSONObject().put("status", "sent").put("sent_to", where.ifBlank { "your main chat" })
                .put("note", if (target == null) "The agent has it. Say a very short 'on it' (a few words). Its reply comes to you as an update." else "Sent to ${where.ifBlank { target }}. Say where in a few words. If it matters, you are told when it finishes.")) }
                .onFailure { err ->
                    core.handOffFailed(); push()
                    if (err is WhichOne) { result(e, JSONObject().put("status", "which_one").put("options", JSONArray(err.names)).put("note", "Not sent: ask the user out loud which one they mean (${err.names.joinToString(" or ")}), then call hand_to_agent again with that exact name as target.")); return@onFailure }
                    result(e, JSONObject().put("status", "failed").put("error", "It did not go: ${err.message.orEmpty().ifBlank { "the link to the computer dropped" }}. Tell the user and offer to try again."))
                }
        }
    }

    /**
     * Puts the job in front of the agent the way a voice call does, so it is never a message the user typed: on a linked
     * computer through voice.callHandoff (carrying origin "live_voice" and the user's own words), on this phone as a call
     * hand-off ("On it: ..." in the chat, no user bubble). The picture, when the job needs one, goes with it.
     */
    private suspend fun deliver(text: String, request: String, spoken: String, jpeg: ByteArray?, target: String = "main"): String {
        val r = RemoteHub.session.value
        if (r != null) {
            val args = LiveAssistCore.handoffMeta(request, spoken, jpeg != null, core.sessionId).put("target", target).put("message", text).put("newChat", false)
            if (jpeg != null) args.put("images", JSONArray().put(JSONObject().put("mime", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))))
            val res = try { r.call("voice.callHandoff", listOf(args), 30_000) as? JSONObject } catch (ex: Exception) {
                // A computer older than voice.callHandoff: the message still goes, the old way.
                if (LiveAuth.isOldComputer(ex.message)) { g.runtime.sendAccepted(if (target == "main") text else "For $target: $text", listOfNotNull(jpeg?.let { tempPicture(it) })); return "" }
                throw ex
            } ?: throw IllegalStateException("The computer didn't answer.")
            if (!res.optBoolean("ok")) {
                // Two names fit what he said: the voice asks which, as a voice call does.
                val close = res.optJSONArray("ambiguous")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") } }.orEmpty()
                if (close.size > 1) throw WhichOne(close)
                throw IllegalStateException(res.optString("error").ifBlank { "The computer would not take it." })
            }
            return res.optString("name")
        }
        g.runtime.send(text, images = listOfNotNull(jpeg?.let { tempPicture(it) }), fromCall = request)
        return ""
    }

    /** More than one project, chat or helper fits the name the user said. */
    private class WhichOne(val names: List<String>) : Exception("Which one: ${names.joinToString(" or ")}")

    private fun tempPicture(jpeg: ByteArray): String {
        val dir = File(context.filesDir, "assist").apply { mkdirs() }
        return File(dir, "live-${clock()}.jpg").also { it.writeBytes(jpeg) }.absolutePath
    }

    private fun result(e: LiveSession.Event.ToolCall, body: JSONObject) {
        Log.i(TAG, "tool result ${e.name}: ${body.toString().take(300)}")
        session?.sendToolResult(e.id, e.name, body)
    }

    private fun tell(text: String) {
        Log.i(TAG, "tell voice: ${text.take(300)}")
        session?.announce(text)
    }

    fun mute(on: Boolean) { session?.setMuted(on); core.muted(on); push() }

    private fun fail(why: String) {
        if (ended) return
        core.failed(why); push()
        end(why)
    }

    /** The one way out: microphone, speaker and socket let go, and the session is recorded as a call. Safe to call twice. */
    fun end(reason: String = "") {
        if (ended) return
        ended = true
        watch?.cancel(); noteJob?.cancel(); ctxJob?.cancel()
        frames?.stop(); frames = null
        runCatching { session?.stop() }
        session = null
        runCatching { saveCall() }
        onEnded(reason)
    }

    /**
     * The session becomes one call in the chat, like any voice call: on a linked computer through voice.callSave (kind
     * "live_assistant", hand-offs inside), on this phone as call rows the chat folds into one block. See docs/live-voice.md.
     */
    private fun saveCall() {
        val lines = core.transcript; val hands = core.handOffs
        if (lines.isEmpty() && hands.isEmpty()) return
        val record = core.record(startedAt, clock())
        val r = RemoteHub.session.value
        if (r != null) { scope.launch { runCatching { r.call("voice.callSave", listOf(record), 30_000) } }; return }
        scope.launch {
            val entries = (lines.map { Triple(it.at, it.who, it.text) to null } + hands.map { Triple(it.at, "hand", it.request) to it }).sortedBy { it.first.first }
            entries.forEach { (e, h) ->
                val meta = JSONObject().put("call", true).put("live", true).put("session", core.sessionId)
                if (h != null) meta.put("handoff", true).put("spoken", h.spoken).put("include_screen", h.includeScreen)
                g.db.chat().insert(ChatItem(kind = if (e.second == "in") "user" else "agent", text = e.third, meta = meta.toString(), createdAt = e.first))
            }
            runCatching { g.runtime.rememberCall(lines.joinToString("\n") { (if (it.who == "in") "User: " else "Voice: ") + it.text }) }
        }
    }

    companion object {
        const val TAG = "LiveAssist"
        const val DEFAULT_MODEL = "gemini-3.1-flash-live-preview"
        /** The microphone is loud enough to be speech, and this long since the last speech counts as a new turn. */
        private const val LOUD = 0.25f
        private const val QUIET_MS = 1_500L
        /** The context is read while the token is fetched; the setup waits for it no longer than this (the mic never waits). */
        private const val CONTEXT_WAIT_MS = 3_500L
        /** Quiet context updates: let a burst of changes settle, and never send more often than the gap. */
        private const val NOTE_DEBOUNCE_MS = 1_200L
        private const val NOTE_GAP_MS = 4_000L
        /** Heads a quiet update: the model keeps it and does not speak it. */
        const val NOTE_HEAD = "[Context update, the newest. It replaces every earlier list. Do not say it aloud unless they ask.]\n"
        /** How long "Sent to ..." stays on the sheet. */
        private const val ROUTED_MS = 5_000L
    }
}
