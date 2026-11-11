package com.past9.phoneaos.assist

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.past9.phoneaos.Graph
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.voice.CallCore
import com.past9.phoneaos.voice.Earcon
import com.past9.phoneaos.voice.LiveAuth
import com.past9.phoneaos.voice.LiveSession
import com.past9.phoneaos.voice.LiveTiming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * One live voice session inside the assist sheet (Settings > Default assistant > Live voice in the assistant). It is
 * owned by [LiveHost] and outlives the sheet, so it keeps going while the sheet is minimised to a bubble.
 *
 * Gemini Live is the voice: it listens, answers aloud at once, can be talked over, and has exactly one tool,
 * hand_to_agent, which puts a job in front of the same agent the typed sheet talks to (this phone's, or the linked
 * computer's) with what is on screen at that moment. The agent's reply, questions and approvals come back through the
 * chat rows. Approvals are still the app's own hold-to-approve card.
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

    val active: Boolean get() = !ended && session != null

    private fun push() = onChange(core.current)

    /** Opens the session. Needs the microphone permission already. */
    fun start() {
        if (session != null || ended) return
        LiveTiming.mark("start")
        val st = g.settings.state.value
        val name = st.agentName.takeIf { it.isNotBlank() && it != "Your agent" } ?: "your agent"
        val linked = com.past9.phoneaos.remote.RemoteHub.link.value?.on == true || com.past9.phoneaos.remote.RemoteHub.session.value != null
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
            is LiveSession.Event.SetupComplete -> { setupDone = true }
            // The voice's loudness drives the face's mouth. Only the voice (not the microphone) and only real changes.
            is LiveSession.Event.Level -> if (!e.mine && core.level(e.value)) push()
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
        scope.launch {
            // Minimised: he is in another app now, so what the sheet saw when it opened is out of date. Read the screen as it is at this moment.
            val screen = if (minimised()) AssistScreenNow.capture(context) else AssistHub.screen.value
            val text = LiveAssistCore.message(request, e.args.optString("context"), source(), screen)
            val images = listOfNotNull(screen?.screenshot?.takeIf { it.isNotBlank() && !screen.blocked })
            core.handOff(request, lastId)
            push()
            runCatching { g.runtime.sendAccepted(text, images) }
                .onSuccess { result(e, JSONObject().put("status", "sent").put("note", "The agent has it. Say a very short 'on it' (a few words). Its reply comes to you as an update.")) }
                .onFailure { err ->
                    core.handOffFailed(); push()
                    result(e, JSONObject().put("status", "failed").put("error", "It did not go: ${err.message.orEmpty().ifBlank { "the link to the computer dropped" }}. Tell the user and offer to try again."))
                }
        }
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

    /** The one way out: microphone, speaker and socket let go. Safe to call twice. */
    fun end(reason: String = "") {
        if (ended) return
        ended = true
        watch?.cancel()
        runCatching { session?.stop() }
        session = null
        onEnded(reason)
    }

    companion object {
        const val TAG = "LiveAssist"
        const val DEFAULT_MODEL = "gemini-3.1-flash-live-preview"
    }
}
