package com.past9.phoneaos.wakeword

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.past9.phoneaos.App
import com.past9.phoneaos.system.ForegroundGuard
import com.past9.phoneaos.system.Identity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The always-on listener: a microphone foreground service with a notification that says, in the person's own words,
 * what it is listening for, and a Pause button.
 *
 * It only ever does what [ListenRules.decide] says. Everything that can change the answer lands in [recompute]:
 * the settings, the agent's name (the phrase follows it at once), the app's own voice features taking the microphone
 * ([MicUse]), another app or a call taking it, the charger, battery saver, the battery level. While it is not
 * listening the microphone is released and the processor is allowed to sleep.
 */
class WakeWordService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var ear: WakeEar
    private var wakeLock: PowerManager.WakeLock? = null
    private var shown: Triple<String, String, String?>? = null
    private var state: WakeState = WakeState.Off
    private var up = false

    @Volatile private var micSilent = false
    @Volatile private var engineOk = true
    private var otherRecorder = false
    private var inCall = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ear = WakeEar(this, "service")
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        up = ForegroundGuard.enter(this, ID, type) { notification("Wake word", "Starting", null) }
        if (!up) { Log.w(TAG, "not listening: stopped before it started"); return }
        running = this
        ear.onHit = { main.post { heard() } }
        ear.onSilent = { s -> micSilent = s; main.post { recompute() } }
        ear.onFailed = { what -> if (what == "model") engineOk = false else micSilent = true; main.post { recompute() } }
        MicUse.onHold = { ear.stop() }

        val am = getSystemService(AudioManager::class.java)
        runCatching { am.registerAudioRecordingCallback(recordings, main) }
        // Told the moment a call starts where the phone can tell us (Android 12 on); asked every few seconds where it cannot.
        if (Build.VERSION.SDK_INT < 31 || runCatching { am.addOnModeChangedListener(mainExecutor, modeListener) }.isFailure) main.postDelayed(modePoll, 3000)
        inCall = busyMode(am.mode)
        registerReceiver(power, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED); addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
        val g = App.graph(this)
        scope.launch {
            combine(WakeWord.store(this@WakeWordService).state, g.settings.state.map { it.agentName to it.mascot }.distinctUntilChanged(), MicUse.held) { _, _, _ -> Unit }
                .collect { recompute() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> WakeWord.store(this).setUserPaused(true)
            ACTION_RESUME -> { micSilent = false; engineOk = true; WakeWord.store(this).setUserPaused(false) }
            // The app came to the screen (or the switch was just turned on): a fresh try at anything that failed.
            ACTION_POKE -> { micSilent = false; engineOk = true; recompute() }
        }
        // Sticky: if Android kills the app for memory, it brings the listener back when it can.
        return START_STICKY
    }

    override fun onDestroy() {
        if (running === this) running = null
        if (up) {
            MicUse.onHold = null
            runCatching { getSystemService(AudioManager::class.java).unregisterAudioRecordingCallback(recordings) }
            if (Build.VERSION.SDK_INT >= 31) runCatching { getSystemService(AudioManager::class.java).removeOnModeChangedListener(modeListener) }
            runCatching { unregisterReceiver(power) }
        }
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        ear.stop()
        lock(false)
        WakeWord.publish(WakeState.Off)
        ForegroundGuard.left(this)
        super.onDestroy()
    }

    // ---- what is going on around it ----

    private val recordings = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val mine = ear.sessionId
            val other = configs.any { c ->
                c.clientAudioSessionId != mine
                    // The phone's own always-on hotword listener is not somebody using the microphone.
                    && c.clientAudioSource != HOTWORD_SOURCE
                    // A recorder Android is feeding silence is not using it either.
                    && !(Build.VERSION.SDK_INT >= 29 && c.isClientSilenced)
            }
            if (other != otherRecorder) { otherRecorder = other; recompute() }
        }
    }
    private val modeListener by lazy { AudioManager.OnModeChangedListener { m -> busyMode(m).let { if (it != inCall) { inCall = it; recompute() } } } }
    private val modePoll = object : Runnable {
        override fun run() {
            busyMode(getSystemService(AudioManager::class.java).mode).let { if (it != inCall) { inCall = it; recompute() } }
            main.postDelayed(this, 3000)
        }
    }
    private fun busyMode(mode: Int) = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE

    private val power = object : BroadcastReceiver() {
        private var last: Triple<Boolean, Boolean, Int>? = null
        override fun onReceive(context: Context, intent: Intent) {
            // The battery broadcast arrives for every percent and every temperature change; only act when an answer could change.
            val now = powerNow()
            if (now != last) { last = now; recompute() }
        }
    }

    private fun powerNow(): Triple<Boolean, Boolean, Int> {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level < 0 || scale <= 0) -1 else level * 100 / scale
        return Triple(plugged, getSystemService(PowerManager::class.java)?.isPowerSaveMode == true, percent)
    }

    // ---- deciding and doing ----

    private fun recompute() {
        if (!up) return
        val store = WakeWord.store(this)
        val settings = store.state.value
        val phrase = WakeWord.phrase(this)
        val lines = runCatching { WakeWord.keywords(this, settings, phrase) }.getOrDefault(emptyList())
        val (charging, saver, percent) = powerNow()
        val world = WakeWorld(micAllowed = WakeWord.micAllowed(this), phraseOk = lines.isNotEmpty(), engineOk = engineOk, appMic = MicUse.held.value,
            inCall = inCall, charging = charging, batterySaver = saver, batteryPercent = percent, otherRecorder = otherRecorder, micSilent = micSilent)
        val next = ListenRules.decide(settings, world)
        state = next
        WakeWord.publish(next)
        when (next) {
            WakeState.Off -> { ear.stop(); lock(false); stopSelf(); return }
            WakeState.Listening -> { lock(true); ear.start(lines) }
            is WakeState.Paused -> { ear.stop(); lock(false) }
        }
        val (title, text) = ListenRules.notice(next, phrase)
        val action = ListenRules.action(next)
        val want = Triple(title, text, action)
        if (want != shown) { shown = want; runCatching { getSystemService(NotificationManager::class.java).notify(ID, notification(title, text, action)) } }
    }

    /** The processor may not sleep while listening (the screen can be off); it may the moment listening stops. */
    private fun lock(on: Boolean) {
        if (on) {
            if (wakeLock?.isHeld == true) return
            wakeLock = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "agent:wakeword")?.apply { setReferenceCounted(false); runCatching { acquire() } }
        } else { runCatching { wakeLock?.takeIf { it.isHeld }?.release() }; wakeLock = null }
    }

    /** The phrase was heard: a short sound, then the assistant. The microphone is free for it before it opens. */
    private fun heard() {
        if (state != WakeState.Listening) return
        WakeWord.hit()
        MicUse.hold(HIT)
        cue()
        WakeTarget.open(this)
        // The assistant takes the microphone itself (and says so through MicUse); this hold only covers the gap until it has.
        main.postDelayed({ MicUse.release(HIT) }, HIT_HOLD_MS)
    }

    private fun cue() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 150)
            main.postDelayed({ runCatching { tone.release() } }, 600)
        }
    }

    private fun notification(title: String, text: String, action: String?): Notification {
        WakeWord.ensureChannel(this)
        val st = App.graph(this).settings.state.value
        val b = NotificationCompat.Builder(this, WakeWord.CHANNEL).setSmallIcon(Identity.statIcon(this, st.mascot))
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true).setSilent(true).setShowWhen(false).setOnlyAlertOnce(true)
            .setContentIntent(WakeWord.openApp(this, 30)).setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (action != null) {
            val act = if (action == "Resume") ACTION_RESUME else ACTION_PAUSE
            b.addAction(0, action, PendingIntent.getService(this, if (action == "Resume") 34 else 33, Intent(this, WakeWordService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE))
        }
        return b.build()
    }

    companion object {
        private const val TAG = "WakeWordService"
        const val ID = 82
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_POKE = "poke"
        private const val HIT = "wake_hit"
        private const val HIT_HOLD_MS = 4000L
        /** MediaRecorder.AudioSource.HOTWORD, which the SDK does not name for apps. */
        private const val HOTWORD_SOURCE = 1999
        @Volatile private var running: WakeWordService? = null

        fun start(context: Context): Boolean = ForegroundGuard.start(context, WakeWordService::class.java)
        fun stop(context: Context) = ForegroundGuard.stop(context, WakeWordService::class.java)
        /** Try again at whatever failed (a silent microphone, a model that would not load). */
        fun poke() { running?.let { s -> s.main.post { s.micSilent = false; s.engineOk = true; s.recompute() } } }
    }
}
