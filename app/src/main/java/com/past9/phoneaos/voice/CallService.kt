package com.past9.phoneaos.voice

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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.past9.phoneaos.App
import com.past9.phoneaos.MainActivity
import com.past9.phoneaos.system.Identity

/**
 * Keeps a voice call alive when the user locks the phone or pockets it, which is exactly what people
 * do with earphones in. Without a microphone foreground service Android hands a backgrounded app
 * silence instead of the mic, so the call "stopped hearing" the moment the screen went off.
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    private var foreground = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END) { App.graph(this).call.end(); stopSelf(); return START_NOT_STICKY }
        // startForeground exactly once: calling it again later re-runs Android 14's mic eligibility
        // check, which throws when the screen is off.
        if (!foreground) try {
            val n = notification()
            if (Build.VERSION.SDK_INT >= 30) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(ID, n)
            foreground = true
        } catch (e: Exception) {
            Log.w("CallService", "could not hold the mic: ${e.message}"); stopSelf()
        }
        return START_NOT_STICKY
    }

    /**
     * An ongoing-call notification (Android's CallStyle): the green call chip in the status bar and the top
     * of the shade, tap to go straight back to the call, End right there. It used to be a silent message-style
     * notification on a low channel, so it looked like nothing was happening and there was no way back.
     */
    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(com.past9.phoneaos.R.string.svc_calls_channel), NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null); enableVibration(false) })
        val st = App.graph(this).settings.state.value
        val name = st.agentName.takeIf { it.isNotBlank() && it != "Your agent" } ?: getString(com.past9.phoneaos.R.string.svc_your_agent)
        val open = PendingIntent.getActivity(this, 7, Intent(this, MainActivity::class.java).putExtra("open", "call").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val end = PendingIntent.getService(this, 8, Intent(this, CallService::class.java).setAction(ACTION_END), PendingIntent.FLAG_IMMUTABLE)
        val agent = androidx.core.app.Person.Builder().setName(name).setKey("agent").setImportant(true)
            .setIcon(androidx.core.graphics.drawable.IconCompat.createWithBitmap(Identity.avatar(this, st.mascot, st.accent, 192))).build()
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(Identity.statIcon(this, st.mascot))
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(agent, end))
            .setContentText(getString(com.past9.phoneaos.R.string.svc_call_text))
            .setOngoing(true).setSilent(true).setUsesChronometer(true).setWhen(System.currentTimeMillis())
            .setContentIntent(open).setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    companion object {
        private const val ID = 77
        private const val CHANNEL = "calls_live"
        private const val ACTION_END = "end"
        fun start(context: Context) = runCatching { context.startForegroundService(Intent(context, CallService::class.java)) }
        fun stop(context: Context) = runCatching { context.stopService(Intent(context, CallService::class.java)) }
    }
}
