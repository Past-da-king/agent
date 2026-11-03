package com.past9.phoneaos.wakeup

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.past9.phoneaos.system.ForegroundGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The phone ring: a foreground service that loops the alarm sound on the ALARM stream (it passes Do Not Disturb's
 * default "alarms" rule) with the alarm volume raised for the ring and put back after, vibrates without stopping,
 * holds a wake lock, and shows the wake: a CATEGORY_ALARM notification with a full-screen intent (covers the lock
 * screen) and, while he is using the phone, a full-screen overlay over every app. It never stops by itself: only
 * I'm on it or Snooze (here, on the computer, or in Settings) ends it.
 */
class WakeUpService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrating = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var savedVolume: Int? = null
    private var overlay: View? = null
    private var shownId: String? = null
    private var soundKey = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ForegroundGuard.enter(this, NOTE_ID, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0) { notification(this, WakeHub.ringing.value.firstOrNull()) }
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "agent:wakeup").apply { setReferenceCounted(false); acquire(6 * 60 * 60_000L) }
        scope.launch { WakeHub.ringing.collectLatest { update(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (WakeHub.ringing.value.isEmpty()) WakeHub.evaluate(this)
        return START_STICKY
    }

    private fun update(list: List<Ringing>) {
        val top = list.firstOrNull()
        if (top == null) { stopSelf(); return }
        getSystemService(NotificationManager::class.java)?.notify(NOTE_ID, notification(this, top))
        sound(top)
        if (shownId != top.wake.id) {
            shownId = top.wake.id
            show(top)
        }
        updateOverlay(top)
    }

    /** Lock screen: the full-screen intent opens WakeUpActivity. In use: the overlay, and the activity behind it. */
    private fun show(top: Ringing) {
        val km = getSystemService(KeyguardManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        val locked = km?.isKeyguardLocked == true || pm?.isInteractive != true
        Log.i(WakeHub.TAG, "show ${top.wake.id.take(8)} locked=$locked overlay=${Settings.canDrawOverlays(this)}")
        if (!locked && Settings.canDrawOverlays(this)) addOverlay(top)
        // Allowed from the background while "Appear on top" is on; otherwise the full-screen intent does it.
        runCatching { startActivity(Intent(this, WakeUpActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
    }

    private fun addOverlay(top: Ringing) {
        if (overlay != null) return
        val wm = getSystemService(WindowManager::class.java) ?: return
        val view = WakeScreen.build(this, top, onAck = { WakeHub.ack(this, it) }, onSnooze = { WakeHub.snooze(this, it) }, onOpen = { id, link -> WakeScreen.open(this, link); WakeHub.ack(this, id) })
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON, PixelFormat.OPAQUE)
        runCatching { wm.addView(view, lp); overlay = view }.onFailure { Log.w(WakeHub.TAG, "overlay: ${it.message}") }
    }

    private fun updateOverlay(top: Ringing) {
        val v = overlay ?: return
        WakeScreen.bind(v, top)
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        runCatching { getSystemService(WindowManager::class.java)?.removeView(v) }
    }

    // ---- sound and vibration ------------------------------------------------------------------------------------

    private fun sound(top: Ringing) {
        val key = "${top.wake.id}|${top.sound}|${top.wake.severity}"
        if (key == soundKey) return
        soundKey = key
        handler.removeCallbacksAndMessages(null)
        stopPlayer()
        if (!top.sound) { stopVibrate(); return }
        raiseVolume()
        vibrate()
        if (top.wake.severity == "heads_up") {
            // One ring cycle a minute; the screen stays up.
            val cycle = object : Runnable {
                override fun run() {
                    startPlayer(); handler.postDelayed({ stopPlayer() }, 6_000)
                    handler.postDelayed(this, WakeRules.HEADS_UP_CYCLE_MS)
                }
            }
            cycle.run()
        } else startPlayer()
    }

    private fun startPlayer() {
        stopPlayer()
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@WakeUpService, uri)
                isLooping = true
                prepare(); start()
            }
        }.onFailure { Log.w(WakeHub.TAG, "alarm sound: ${it.message}") }.getOrNull()
    }

    private fun stopPlayer() { runCatching { player?.stop(); player?.release() }; player = null }

    private fun raiseVolume() {
        val am = getSystemService(AudioManager::class.java) ?: return
        if (savedVolume == null) savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        // In Do Not Disturb this needs Do Not Disturb access; without it the alarm plays at its own volume.
        runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
            .onFailure { Log.w(WakeHub.TAG, "alarm volume: ${it.message}") }
    }

    private fun restoreVolume() {
        val v = savedVolume ?: return
        savedVolume = null
        runCatching { getSystemService(AudioManager::class.java)?.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) }
    }

    private fun vibrate() {
        if (vibrating) return
        val v = getSystemService(Vibrator::class.java) ?: return
        vibrating = true
        val pattern = longArrayOf(0, 800, 400, 800, 400, 1200, 600)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, 0), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            else @Suppress("DEPRECATION") v.vibrate(pattern, 0)
        }
    }

    private fun stopVibrate() { if (vibrating) runCatching { getSystemService(Vibrator::class.java)?.cancel() }; vibrating = false }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopPlayer(); stopVibrate(); restoreVolume(); removeOverlay()
        runCatching { wakeLock?.release() }
        getSystemService(NotificationManager::class.java)?.cancel(NOTE_ID)
        scope.cancel()
        ForegroundGuard.left(this)
        Log.i(WakeHub.TAG, "ring stopped")
        super.onDestroy()
    }

    companion object {
        const val NOTE_ID = 7303
        const val CHANNEL = "wakeups_ring"

        fun ring(context: Context) { ForegroundGuard.start(context, WakeUpService::class.java) }
        fun stop(context: Context) { ForegroundGuard.stop(context, WakeUpService::class.java) }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) != null) return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Wake-ups", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rings until you answer when something needs you now"
                setSound(null, null) // the service plays the alarm itself, on the alarm stream
                enableVibration(false)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }

        fun notification(context: Context, top: Ringing?): Notification {
            ensureChannel(context)
            val w = top?.wake
            val full = PendingIntent.getActivity(context, 7304, Intent(context, WakeUpActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(if (w == null) "Wake-up" else "${WakeRules.label(w.severity)}: ${WakeRules.shownTitle(w)}")
                .setContentText(w?.whatToDo ?: "").setStyle(NotificationCompat.BigTextStyle().bigText(w?.whatToDo ?: ""))
                .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true).setAutoCancel(false).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(full, true).setContentIntent(full)
            if (w != null) {
                b.setColor(WakeRules.colour(w.severity).toInt()).setColorized(true)
                b.addAction(0, "I'm on it", action(context, "ack", w.id, 1))
                b.addAction(0, "Snooze", action(context, "snooze", w.id, 2))
            }
            return b.build()
        }

        private fun action(context: Context, what: String, id: String, code: Int): PendingIntent =
            PendingIntent.getBroadcast(context, 7310 + code, Intent(context, WakeActionReceiver::class.java).setAction("com.past9.phoneaos.wakeup.$what").putExtra("id", id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

/** I'm on it / Snooze from the notification. */
class WakeActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        when (intent.action?.substringAfterLast('.')) {
            "ack" -> WakeHub.ack(context, id)
            "snooze" -> WakeHub.snooze(context, id)
        }
    }
}
