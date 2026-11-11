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
import com.past9.phoneaos.system.ForegroundGuard
import com.past9.phoneaos.system.Identity

/**
 * Holds the mic while he dictates, so locking the phone or switching apps does not hand the app silence (Android
 * gives a backgrounded app no microphone without a microphone foreground service). Stop is right in the notification.
 */
class DictationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        if (!ForegroundGuard.enter(this, ID, type) { notification() }) Log.w("DictationService", "not holding the mic: stopped before it started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) android.os.Handler(mainLooper).post { DictationHub.stop() }
        return START_NOT_STICKY
    }

    override fun onDestroy() { ForegroundGuard.left(this); super.onDestroy() }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(com.past9.phoneaos.R.string.svc_dictation_channel), NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
        val st = App.graph(this).settings.state.value
        val open = PendingIntent.getActivity(this, 17, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 18, Intent(this, DictationService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(Identity.statIcon(this, st.mascot))
            .setContentTitle(getString(com.past9.phoneaos.R.string.voice_listening))
            .setContentText(getString(com.past9.phoneaos.R.string.svc_dictation_text))
            .setOngoing(true).setSilent(true).setUsesChronometer(true).setWhen(System.currentTimeMillis())
            .setContentIntent(open).addAction(0, getString(com.past9.phoneaos.R.string.chat_stop), stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val ID = 79
        private const val CHANNEL = "dictation"
        private const val ACTION_STOP = "stop"
        /** The wake word listener lets go of the microphone first ([com.past9.phoneaos.wakeword.MicUse]) and takes it back when dictation stops. */
        fun start(context: Context): Boolean { com.past9.phoneaos.wakeword.MicUse.hold(MIC); return ForegroundGuard.start(context, DictationService::class.java).also { if (!it) com.past9.phoneaos.wakeword.MicUse.release(MIC) } }
        fun stop(context: Context) { ForegroundGuard.stop(context, DictationService::class.java); com.past9.phoneaos.wakeword.MicUse.release(MIC) }
        private const val MIC = "dictation"
    }
}
