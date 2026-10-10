package com.past9.phoneaos

import android.app.Application
import android.content.Context
import com.past9.phoneaos.agent.AgentRuntime
import com.past9.phoneaos.data.AppDb
import com.past9.phoneaos.data.SettingsStore
import com.past9.phoneaos.system.AndroidPhone
import com.past9.phoneaos.triggers.Routines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Everything long-lived, built once per process. */
class Graph(context: Context) {
    val app: Context = context.applicationContext
    /** A failure in any background job is logged and stays in that job: it never reaches the process. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, t ->
        com.past9.phoneaos.agent.Crashes.record(context.applicationContext, "background job: ${t::class.java.simpleName} ${t.message}", t)
    })
    val db = AppDb.open(app)
    val settings = SettingsStore(app)
    val phone = AndroidPhone(app)
    /** Live cards the agent built (HTML + data), on Home and in chat. */
    val cards = com.past9.phoneaos.cards.CardStore(app)
    val runtime = AgentRuntime(app, db, settings, scope, phone).apply {
        onRoutineChanged = { t -> if (t != null) Routines.schedule(app, t) }
        onRoutineDeleted = { id -> Routines.cancel(app, id) }
    }
    init {
        scope.launch { runCatching { com.past9.phoneaos.triggers.Overnight.ensureRoutine(this@Graph) } }
        // Anything scheduled that was missed while the app was dead runs once now.
        scope.launch { runCatching { Routines.catchUp(app) } }
        // A consumer (ck_) Composio key: fetch Composio Connect's tools so the agent has them.
        settings.composioKey()?.takeIf { com.past9.phoneaos.tools.ComposioConnect.isConsumerKey(it) }?.let { k -> scope.launch { com.past9.phoneaos.tools.ComposioConnect.load(k.trim()) } }
    }
    val call by lazy { com.past9.phoneaos.voice.CallController(app, db, settings, runtime, scope) }

    private val systemVoice by lazy { com.past9.phoneaos.system.Speaker(app) }
    fun speakSystem(text: String) {}

    /** Replies already read out (or that existed before), so nothing is ever spoken twice. */
    private val spoken = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
    private val _speaking = kotlinx.coroutines.flow.MutableStateFlow(false)
    val speaking: kotlinx.coroutines.flow.StateFlow<Boolean> = _speaking
    private var speakJob: kotlinx.coroutines.Job? = null

    /** Mark everything already in the chat as heard (call once at start). */
    fun markHeard(ids: List<Long>) { spoken.addAll(ids) }

    /**
     * Read a reply aloud with the user's cloud voice. Never the robotic phone voice: with no voice
     * set up we stay quiet. Each message is spoken at most once.
     */
    fun speak(text: String, id: Long? = null) {
        if (id != null && !spoken.add(id)) return
        if (settings.state.value.ttsProvider == "system") return
        stopSpeaking()
        speakJob = scope.launch {
            val f = com.past9.phoneaos.voice.Tts.synth(app, settings, text) ?: return@launch
            _speaking.value = true
            com.past9.phoneaos.voice.Tts.play(f) { _speaking.value = false }
        }
    }

    /** Earphones in (wired, USB or Bluetooth)? Then voice can play without disturbing anyone. */
    fun earphonesIn(): Boolean {
        val am = app.getSystemService(android.media.AudioManager::class.java)
        val kinds = setOf(android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET, 26 /* BLE_HEADSET */, 27 /* BLE_SPEAKER */)
        return am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any { it.type in kinds }
    }

    /** Play a voice note the agent just sent, once. */
    fun autoPlayVoice(path: String, id: Long) {
        if (!spoken.add(id)) return
        val f = java.io.File(path); if (!f.exists()) return
        stopSpeaking(); _speaking.value = true
        com.past9.phoneaos.voice.Tts.play(f) { _speaking.value = false }
    }

    fun stopSpeaking() { speakJob?.cancel(); com.past9.phoneaos.voice.Tts.stop(); systemVoice.stop(); _speaking.value = false }

    /** The runtime for whichever subscription the user picked (Claude, ChatGPT/Codex, OpenCode). */
    fun subRuntime() = com.past9.phoneaos.runtime.SubscriptionRuntime(app, settings.state.value.subKind)
    init {
        com.past9.phoneaos.tools.BrowserTool.profiles = { settings.state.value.browserProfiles }
        runtime.subscription = object : com.past9.phoneaos.agent.SubscriptionEngine {
            override val ready get() = subRuntime().let { it.available && it.installed && it.signedIn }
            override fun forgetBadSignIn() { subRuntime().setToken(null) }
            override fun turn(prompt: String, system: String, resume: String?, mcpUrl: String, mcpToken: String, images: List<String>, model: String?, role: String) =
                subRuntime().turn(prompt, system, resume, model ?: settings.state.value.subModel.ifBlank { null }, mcpUrl, mcpToken, images, role)
            // Claude Code can fork a running session (the Agent SDK's forkSession); Codex and OpenCode can only continue one.
            override val canFork get() = settings.state.value.subKind == com.past9.phoneaos.data.SubKind.CLAUDE
            override fun forkTurn(prompt: String, system: String, parent: String, mcpUrl: String, mcpToken: String, images: List<String>, model: String?, role: String) =
                subRuntime().turn(prompt, system, parent, model ?: settings.state.value.subModel.ifBlank { null }, mcpUrl, mcpToken, images, role, fork = true)
        }
    }
}

class App : Application(), coil.ImageLoaderFactory {
    val graph by lazy { Graph(this) }

    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
    }

    /** App logos from Composio are SVGs; teach the image loader to draw them. */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this).components { add(coil.decode.SvgDecoder.Factory()) }.crossfade(true).build()

    companion object {
        @Volatile private var fallback: Graph? = null
        fun graph(context: Context): Graph = (context.applicationContext as? App)?.graph
            ?: fallback ?: synchronized(this) { fallback ?: Graph(context).also { fallback = it } }
    }
}

/**
 * An exception on a background thread (a reader, a socket, a library's worker) must not take every helper down with
 * it. It is logged and that thread ends; only a failure on the UI thread is left to crash as Android would.
 */
object CrashGuard {
    /** True when the exception should be swallowed (logged only): anything not on the main thread. */
    fun contain(thread: Thread, mainThread: Thread? = android.os.Looper.getMainLooper()?.thread) = thread !== mainThread

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            com.past9.phoneaos.agent.Crashes.record(app, "thread ${t.name}: ${e::class.java.simpleName} ${e.message}", e)
            if (!contain(t)) previous?.uncaughtException(t, e)
        }
    }
}
