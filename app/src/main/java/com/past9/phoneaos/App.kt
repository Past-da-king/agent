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
    /** The runtime for whichever subscription the user picked (Claude, ChatGPT/Codex, OpenCode). */
    fun subRuntime() = com.past9.phoneaos.runtime.SubscriptionRuntime(app, settings.state.value.subKind)
    init {
        com.past9.phoneaos.tools.BrowserTool.profiles = { settings.state.value.browserProfiles }
        runtime.subscription = object : com.past9.phoneaos.agent.SubscriptionEngine {
            override val ready get() = subRuntime().let { it.available && it.installed && it.signedIn }
            override fun turn(prompt: String, system: String, resume: String?, mcpUrl: String, mcpToken: String) = subRuntime().turn(prompt, system, resume, null, mcpUrl, mcpToken)
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
