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
        val n = Notification.Builder(this, CHANNEL).setContentTitle("Your agent is browsing").setContentText("Tap to watch or take over")
            .setSmallIcon(R.drawable.ic_stat_agent).setOngoing(true).setContentIntent(open).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID, n)
        engine = BrowserEngine(this).also { it.create() }
        _running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() { engine?.destroy(); engine = null; _running.value = false; super.onDestroy() }

    companion object {
        private const val CHANNEL = "browser"
        private const val ID = 41
        @Volatile var engine: BrowserEngine? = null
            private set
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        fun start(context: Context) { context.startForegroundService(Intent(context, BrowserService::class.java)) }
        fun stop(context: Context) { context.stopService(Intent(context, BrowserService::class.java)) }

        /** Start the service if needed and wait for its engine. */
        suspend fun await(context: Context): BrowserEngine {
            engine?.let { return it }
            start(context)
            repeat(50) { kotlinx.coroutines.delay(100); engine?.let { kotlinx.coroutines.delay(300); return it } }
            error("The browser did not start")
        }
    }
}
