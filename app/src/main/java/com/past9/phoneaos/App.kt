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
}

class App : Application() {
    val graph by lazy { Graph(this) }

    companion object {
        @Volatile private var fallback: Graph? = null
        fun graph(context: Context): Graph = (context.applicationContext as? App)?.graph
            ?: fallback ?: synchronized(this) { fallback ?: Graph(context).also { fallback = it } }
    }
}
