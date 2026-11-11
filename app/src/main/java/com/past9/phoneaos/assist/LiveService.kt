package com.past9.phoneaos.assist

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
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.past9.phoneaos.App
import com.past9.phoneaos.system.ForegroundGuard
import com.past9.phoneaos.system.Identity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Holds the live voice session in the foreground (microphone and media playback) for as long as it runs, so Android
 * does not take the microphone away when the sheet goes to a bubble and he moves between apps. While the sheet is
 * minimised it also shows the floating bubble. Its notification has End.
 */
class LiveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var bubble: LiveBubble? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        if (!ForegroundGuard.enter(this, ID, type) { notification() }) { Log.w(TAG, "not in the foreground: the session ended before it started"); return }
        scope.launch {
            LiveHost.minimised.collectLatest { min ->
                if (min && Settings.canDrawOverlays(this@LiveService)) {
                    if (bubble == null) bubble = LiveBubble(this@LiveService).also { it.show() }
                } else { bubble?.hide(); bubble = null }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END) { LiveHost.end(""); stopSelf() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        bubble?.hide(); bubble = null
        scope.cancel()
        ForegroundGuard.left(this)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(com.past9.phoneaos.R.string.live_channel), NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
        val st = App.graph(this).settings.state.value
        val name = st.agentName.takeIf { it.isNotBlank() && it != "Your agent" } ?: getString(com.past9.phoneaos.R.string.svc_your_agent)
        val open = PendingIntent.getActivity(this, 11, AssistEntry.intent(this, LiveHost.source), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val end = PendingIntent.getService(this, 12, Intent(this, LiveService::class.java).setAction(ACTION_END), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(Identity.statIcon(this, st.mascot))
            .setContentTitle(getString(com.past9.phoneaos.R.string.live_notif_title, name)).setContentText(getString(com.past9.phoneaos.R.string.live_notif_text))
            .addAction(0, getString(com.past9.phoneaos.R.string.voice_end), end)
            .setOngoing(true).setSilent(true).setContentIntent(open).setCategory(NotificationCompat.CATEGORY_SERVICE).build()
    }

    companion object {
        private const val TAG = "LiveService"
        private const val ID = 78
        private const val CHANNEL = "live_session"
        private const val ACTION_END = "end"
        fun start(context: Context): Boolean = ForegroundGuard.start(context, LiveService::class.java)
        fun stop(context: Context) = ForegroundGuard.stop(context, LiveService::class.java)
    }
}
