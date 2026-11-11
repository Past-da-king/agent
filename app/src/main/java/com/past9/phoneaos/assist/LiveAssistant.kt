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
    @Volatile private var setupDone = false
    private var watch: Job? = null
    private var rows: List<ChatItem> = emptyList()
    private val lastId: Long get() = rows.lastOrNull()?.id ?: 0L
    private var startedAt = 0L
    private var frames: LiveFrames? = null
    @Volatile private var lastLoud = 0L

    val active: Boolean get() = !ended && session != null

    private fun push() = onChange(core.current)

    /** Opens the session. Needs the microphone permission already. */
    fun start() {
        if (session != null || ended) return
        LiveTiming.mark("start")
        startedAt = clock()
        val st = g.settings.state.value
        val name = st.agentName.takeIf { it.isNotBlank() && it != "Your agent" } ?: "your agent"
        val linked = RemoteHub.link.value?.on == true || RemoteHub.session.value != null
        val system = LiveAssistCore.systemPrompt(name, st.userName, linked)
        val phoneVoice = st.ttsVoice.takeIf { st.ttsProvider == "gemini" && it.isNotBlank() }
        val live = LiveSession("", DEFAULT_MODEL, phoneVoice ?: "Kore", system, tools = LiveAssistCore.tools(),
            renew = { kotlinx.coroutines.runBlocking { LiveToken.mint(context) as? LiveAuth.Token.Ok }?.url }) { e -> if (!ended) onEvent(e) }
        session = live
        // The microphone and speaker open now, in parallel with the token: nothing he says is lost.
        live.prepare(context.getSystemService(AudioManager::class.java))
        scope.launch {
            LiveTiming.mark("token_req")
            val auth = runCatching { LiveToken.take(context) }.getOrElse { LiveAuth.Token.No(it.message ?: "Live voice could not start.", false) }
            LiveTiming.mark("token_ok")
            if (ended) return@launch
            if (auth !is LiveAuth.Token.Ok) { fail((auth as LiveAuth.Token.No).why); return@launch }
            live.connectTo(auth.url, auth.model.ifBlank { DEFAULT_MODEL }, if (phoneVoice == null) auth.voice else "")
        }
        // Nothing heard, nothing said and nothing out for a while: let go.
        watch = scope.launch { while (!ended) { delay(2_000); if (core.idle(minimised())) { Log.i(TAG, "idle, ending"); end("") } } }
        // Never stuck connecting: the microphone being up does not mean the connection is.
        scope.launch { delay(CallCore.CONNECT_TIMEOUT_MS); if (!ended && !setupDone) fail(CallCore.TIMED_OUT) }
    }

    /** The chat rows or the agent's working flag changed: pass on what Live should say. */
    fun onRows(r: List<ChatItem>, w: Boolean) {
        rows = r
        if (ended) return
        core.rows(r, w).forEach { tell(it) }
        push()
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
        if (e.name != LiveAssistCore.TOOL) { result(e, JSONObject().put("error", "unknown tool")); return }
        val request = e.args.optString("request").trim()
        if (request.isEmpty()) { result(e, JSONObject().put("error", "Nothing to do: say what the job is.")); return }
        val wantPicture = e.args.optBoolean("include_screen")
        scope.launch {
            // Minimised: he is in another app now, so what the sheet saw when it opened is out of date. Read the screen as it is at this moment.
            val screen0 = if (minimised()) AssistScreenNow.capture(context) else AssistHub.screen.value
            // The words on screen go along; the picture only when the job needs it (include_screen).
            val screen = if (wantPicture) screen0 else screen0?.copy(screenshot = "")
            val jpeg = if (wantPicture && screen0?.blocked != true) frames?.latestJpeg() else null
            val spoken = core.spokenNow()
            val text = LiveAssistCore.message(request, e.args.optString("context"), source(), screen, spoken)
            core.handOff(request, lastId, wantPicture && jpeg != null)
            push()
            val sent = runCatching { deliver(text, request, spoken, jpeg) }
            sent.onSuccess { result(e, JSONObject().put("status", "sent").put("note", "The agent has it. Say a very short 'on it' (a few words). Its reply comes to you as an update.")) }
                .onFailure { err ->
                    core.handOffFailed(); push()
                    result(e, JSONObject().put("status", "failed").put("error", "It did not go: ${err.message.orEmpty().ifBlank { "the link to the computer dropped" }}. Tell the user and offer to try again."))
                }
        }
    }

    /**
     * Puts the job in front of the agent the way a voice call does, so it is never a message the user typed: on a linked
     * computer through voice.callHandoff (carrying origin "live_voice" and the user's own words), on this phone as a call
     * hand-off ("On it: ..." in the chat, no user bubble). The picture, when the job needs one, goes with it.
     */
    private suspend fun deliver(text: String, request: String, spoken: String, jpeg: ByteArray?) {
        val r = RemoteHub.session.value
        if (r != null) {
            val args = LiveAssistCore.handoffMeta(request, spoken, jpeg != null, core.sessionId).put("target", "main").put("message", text).put("newChat", false)
            if (jpeg != null) args.put("images", JSONArray().put(JSONObject().put("mime", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))))
            val res = try { r.call("voice.callHandoff", listOf(args), 30_000) as? JSONObject } catch (ex: Exception) {
                // A computer older than voice.callHandoff: the message still goes, the old way.
                if (LiveAuth.isOldComputer(ex.message)) { g.runtime.sendAccepted(text, listOfNotNull(jpeg?.let { tempPicture(it) })); return }
                throw ex
            } ?: throw IllegalStateException("The computer didn't answer.")
            if (!res.optBoolean("ok")) throw IllegalStateException(res.optString("error").ifBlank { "The computer would not take it." })
            return
        }
        g.runtime.send(text, images = listOfNotNull(jpeg?.let { tempPicture(it) }), fromCall = request)
    }

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
        watch?.cancel()
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
    }
}
