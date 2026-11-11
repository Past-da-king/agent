package com.past9.phoneaos.assist

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.past9.phoneaos.Graph
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.remote.RemoteHub
import com.past9.phoneaos.voice.CallCore
import com.past9.phoneaos.voice.LiveAuth
import com.past9.phoneaos.voice.LiveSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * One live voice session inside the assist sheet (Settings > Default assistant > Live voice in the assistant).
 *
 * Gemini Live is the voice: it listens, answers aloud at once, can be talked over, and has exactly one tool,
 * hand_to_agent, which puts a job in front of the same agent the typed sheet talks to (this phone's, or the linked
 * computer's) with what is on screen. The agent's reply, questions and approvals come back through the chat rows
 * the sheet already follows. Approvals are still the app's own hold-to-approve card, shown in the sheet.
 *
 * Auth is the same as a call: the linked computer mints a one-use token from its own key, else this phone's own
 * Google key. The agent's name comes from settings.
 */
class LiveAssistant(
    private val context: Context,
    private val g: Graph,
    private val scope: CoroutineScope,
    private val source: () -> String,
    /** The view changed: redraw. */
    private val onChange: (LiveView) -> Unit,
    /** The session is over (goodbye, idle, tap, or it could not start). [reason] is blank when it ended normally. */
    private val onEnded: (reason: String) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    val core = LiveAssistCore(clock)
    @Volatile private var session: LiveSession? = null
    @Volatile private var ended = false
    private var watch: Job? = null
    private var rows: List<ChatItem> = emptyList()
    private var working = false
    private val lastId: Long get() = rows.lastOrNull()?.id ?: 0L
    private var linked: com.past9.phoneaos.remote.RemoteSession? = null

    val active: Boolean get() = !ended && session != null

    private fun push() = onChange(core.current)

    /** Opens the session. Needs the microphone permission already. */
    fun start() {
        if (session != null || ended) return
        val st = g.settings.state.value
        linked = RemoteHub.session.value
        val key = g.settings.voiceKey("gemini")
        val name = st.agentName.takeIf { it.isNotBlank() && it != "Your agent" } ?: "your agent"
        val system = LiveAssistCore.systemPrompt(name, st.userName, linked != null)
        scope.launch {
            val auth = runCatching { resolve(key) }.getOrElse { LiveAuth.Token.No(it.message ?: "Live voice could not start.", false) }
            if (ended) return@launch
            if (auth !is LiveAuth.Token.Ok) { fail((auth as LiveAuth.Token.No).why); return@launch }
            val renew = if (linked != null) ({ (kotlinx.coroutines.runBlocking { resolve(key) } as? LiveAuth.Token.Ok)?.url }) else null
            val phoneVoice = st.ttsVoice.takeIf { st.ttsProvider == "gemini" && it.isNotBlank() }
            session = LiveSession(auth.url, auth.model.ifBlank { null } ?: DEFAULT_MODEL, phoneVoice ?: auth.voice.ifBlank { null } ?: "Kore", system,
                tools = LiveAssistCore.tools(), renew = renew) { e -> if (!ended) onEvent(e) }.also { it.start() }
            // Nothing heard, nothing said and nothing out for a while: let go.
            watch = scope.launch { while (!ended) { delay(2_000); if (core.idle()) { Log.i(TAG, "idle, ending"); end(""); } } }
            // Never stuck on "connecting".
            scope.launch { delay(CallCore.CONNECT_TIMEOUT_MS); if (!ended && core.current.phase == LivePhase.CONNECTING) fail(CallCore.TIMED_OUT) }
        }
    }

    /** The computer's one-use token while linked; this phone's own key otherwise (also when the computer has none). */
    private suspend fun resolve(key: String?): LiveAuth.Token {
        val r = linked
        if (r != null) {
            val t = try { LiveAuth.parse(r.call("voice.liveToken", emptyList(), 20_000)) } catch (e: Exception) {
                LiveAuth.Token.No(if (LiveAuth.isOldComputer(e.message)) LiveAuth.OLD_COMPUTER else e.message ?: "Your computer didn't answer.", false)
            }
            if (t is LiveAuth.Token.Ok || key == null) return t
        }
        if (key == null) return LiveAuth.Token.No("Live voice runs on Gemini Live. Add a Google (Gemini) key in Settings, Voice, or link a computer that has one.", true)
        return LiveAuth.Token.Ok(LiveAuth.keyUrl(key), DEFAULT_MODEL, "")
    }

    /** The chat rows or the agent's working flag changed: pass on what Live should say. */
    fun onRows(r: List<ChatItem>, w: Boolean) {
        rows = r; working = w
        if (ended) return
        core.rows(r, w).forEach { tell(it) }
        push()
    }

    private fun onEvent(e: LiveSession.Event) {
        when (e) {
            is LiveSession.Event.SetupComplete -> {
                session?.openAudio(context.getSystemService(AudioManager::class.java))
                core.connected(); push()
            }
            is LiveSession.Event.Transcript -> { core.caption(e.who, e.text); push() }
            is LiveSession.Event.Speaking -> { if (!e.on) Log.i(TAG, "turn: you=\"${core.current.you.take(160)}\" agent=\"${core.current.agent.take(160)}\""); if (core.speaking(e.on)) scope.launch { delay(400); end("") }; push() }
            is LiveSession.Event.ToolCall -> handle(e)
            is LiveSession.Event.Failed -> fail(CallCore.plainError(e.error).ifBlank { "Live voice stopped." })
            is LiveSession.Event.Closed -> end("")
            else -> {}
        }
    }

    private fun handle(e: LiveSession.Event.ToolCall) {
        Log.i(TAG, "tool call ${e.name}: ${e.args.toString().take(300)}")
        if (e.name != LiveAssistCore.TOOL) { result(e, JSONObject().put("error", "unknown tool")); return }
        val request = e.args.optString("request").trim()
        if (request.isEmpty()) { result(e, JSONObject().put("error", "Nothing to do: say what the job is.")); return }
        val text = LiveAssistCore.message(request, e.args.optString("context"), source(), AssistHub.screen.value)
        val screen = AssistHub.screen.value
        val images = listOfNotNull(screen?.screenshot?.takeIf { it.isNotBlank() && !screen.blocked })
        val before = lastId
        core.handOff(request, before)
        push()
        scope.launch {
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

    /** Talk over the agent: drop what it is saying now. The session itself handles real barge-in; this is the tap. */
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
