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
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDb.open(app)
    val settings = SettingsStore(app)
    val phone = AndroidPhone(app)
    val runtime = AgentRuntime(app, db, settings, scope, phone).apply {
        onRoutineChanged = { t -> if (t != null) Routines.schedule(app, t) }
        onRoutineDeleted = { id -> Routines.cancel(app, id) }
    }
    val call by lazy { com.past9.phoneaos.voice.CallController(app, db, settings, runtime, scope) }

    private val systemVoice by lazy { com.past9.phoneaos.system.Speaker(app) }
    fun speakSystem(text: String) = systemVoice.say(text)

    /** Speak a reply with the user's chosen voice (cloud key if set, else the phone's own). */
    fun speak(text: String) = scope.launch {
        val f = if (settings.state.value.ttsProvider == "system") null else com.past9.phoneaos.voice.Tts.synth(app, settings, text)
        if (f != null) com.past9.phoneaos.voice.Tts.play(f) else systemVoice.say(text)
    }

    /** The runtime for whichever subscription the user picked (Claude, ChatGPT/Codex, OpenCode). */
    fun subRuntime() = com.past9.phoneaos.runtime.SubscriptionRuntime(app, settings.state.value.subKind)
    init {
        com.past9.phoneaos.tools.BrowserTool.profiles = { settings.state.value.browserProfiles }
        runtime.subscription = object : com.past9.phoneaos.agent.SubscriptionEngine {
            override val ready get() = subRuntime().let { it.available && it.installed && it.signedIn }
            override fun forgetBadSignIn() { subRuntime().setToken(null) }
            override fun turn(prompt: String, system: String, resume: String?, mcpUrl: String, mcpToken: String, images: List<String>) = subRuntime().turn(prompt, system, resume, settings.state.value.subModel.ifBlank { null }, mcpUrl, mcpToken, images)
        }
    }
}

class App : Application(), coil.ImageLoaderFactory {
    val graph by lazy { Graph(this) }

    /** App logos from Composio are SVGs; teach the image loader to draw them. */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this).components { add(coil.decode.SvgDecoder.Factory()) }.crossfade(true).build()

    companion object {
        @Volatile private var fallback: Graph? = null
        fun graph(context: Context): Graph = (context.applicationContext as? App)?.graph
            ?: fallback ?: synchronized(this) { fallback ?: Graph(context).also { fallback = it } }
    }
}
