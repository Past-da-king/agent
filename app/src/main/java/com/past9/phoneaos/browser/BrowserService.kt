package com.past9.phoneaos.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.past9.phoneaos.MainActivity
import com.past9.phoneaos.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground service that keeps the agent's browser alive while the user is in other apps.
 * Android 14 needs a visible notification for this; it reads "Your agent is browsing".
 */
class BrowserService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Background browsing", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("open", "browser"), PendingIntent.FLAG_IMMUTABLE)
        val n = {
            val st = com.past9.phoneaos.App.graph(this).settings.state.value
            com.past9.phoneaos.system.Identity.asAgent(this, androidx.core.app.NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("${st.agentName.ifBlank { "Your agent" }} is browsing").setContentText("Tap to watch or take over")
                .setSmallIcon(com.past9.phoneaos.system.Identity.statIcon(this, st.mascot)).setOngoing(true).setContentIntent(open), "", "I'm browsing. Tap to watch or take over.").build()
        }
        // Foreground first, through the guard (system/ForegroundGuard.kt): never stopped before it gets there.
        if (!com.past9.phoneaos.system.ForegroundGuard.enter(this, ID, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0, n)) return
        _running.value = true
        // Battery: a browser left open after a task kept a WebView and this service alive forever.
        // Close everything once nothing has used it for a few minutes; the next browse reopens it,
        // and sign-ins survive because cookies live in the profile, not the window.
        idle = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                val st = com.past9.phoneaos.App.graph(this@BrowserService).runtime.status.value
                val quiet = engines.values.none { it.attached || System.currentTimeMillis() - it.lastUsed < IDLE_MS }
                if (quiet && !st.working && st.helpers == 0) { stopSelf(); break }
            }
        }
    }
    private var idle: kotlinx.coroutines.Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() { com.past9.phoneaos.system.ForegroundGuard.left(this); idle?.cancel(); engines.values.forEach { it.destroy() }; engines.clear(); _engines.value = emptyMap(); _running.value = false; super.onDestroy() }

    companion object {
        private const val CHANNEL = "browser"
        private const val ID = 41
        /** One browser per agent: the main agent's, plus one for each helper while it works. */
        private val engines = java.util.concurrent.ConcurrentHashMap<String, BrowserEngine>()
        private val _engines = MutableStateFlow<Map<String, BrowserEngine>>(emptyMap())
        val live: StateFlow<Map<String, BrowserEngine>> = _engines
        /** The agent whose browser the user should see: whoever used one last (helpers do the browsing now). */
        @Volatile private var focusOwner: String? = null
        val engine: BrowserEngine? get() = focusOwner?.let { o -> engines.entries.firstOrNull { it.key.startsWith("$o/") }?.value }
            ?: engines.entries.firstOrNull { it.key.startsWith("main/") }?.value ?: engines.values.firstOrNull()
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running
        private const val MAX = 4
        private const val IDLE_MS = 3 * 60_000L

        fun start(context: Context) { com.past9.phoneaos.system.ForegroundGuard.start(context, BrowserService::class.java) }
        fun stop(context: Context) { com.past9.phoneaos.system.ForegroundGuard.stop(context, BrowserService::class.java) }

        /** Start the service if needed and get (or open) the browser for this agent. */
        suspend fun await(context: Context, owner: String = "main", profile: String = "Personal"): BrowserEngine {
            val key = "$owner/$profile"
            focusOwner = owner
            engines[key]?.let { it.touch(); return it }
            if (!_running.value) { start(context); repeat(50) { if (_running.value) return@repeat; kotlinx.coroutines.delay(100) } }
            check(_running.value) { "The browser did not start" }
            check(engines.size < MAX) { "Too many browsers open at once ($MAX). Finish one first." }
            val e = BrowserEngine(context.applicationContext, profile).also { it.create() }
            engines[key] = e; _engines.value = engines.toMap()
            kotlinx.coroutines.delay(400)
            return e
        }

        /** Settings > Browser changed the sites list: every open browser's profile gets it. */
        fun applyIsolation(origins: List<String>) { engines.values.forEach { it.applyIsolation(origins) } }

        /** The browser this agent already has open, if any. Never starts one. */
        fun existing(owner: String): BrowserEngine? = engines.entries.firstOrNull { it.key.startsWith("$owner/") }?.value

        /** A helper finished: close its browser. The main agent's stays open. */
        fun release(owner: String) {
            if (owner == "main") return
            if (focusOwner == owner) focusOwner = null
            engines.keys.filter { it.startsWith("$owner/") }.forEach { engines.remove(it)?.destroy() }; _engines.value = engines.toMap()
        }
    }
}
