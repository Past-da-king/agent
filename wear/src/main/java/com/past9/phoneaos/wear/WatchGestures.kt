package com.past9.phoneaos.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.past9.phoneaos.wearproto.gesture.DeviceProfile
import com.past9.phoneaos.wearproto.gesture.Gesture
import com.past9.phoneaos.wearproto.gesture.GestureContext
import com.past9.phoneaos.wearproto.gesture.GestureEffect
import com.past9.phoneaos.wearproto.gesture.GestureEngine
import com.past9.phoneaos.wearproto.gesture.GestureMap
import com.past9.phoneaos.wearproto.gesture.GestureRouter
import com.past9.phoneaos.wearproto.gesture.Sample
import com.past9.phoneaos.wearproto.gesture.SensorPlan

/**
 * Watch gestures for every user (docs/wear.md, "Gestures"). What the platform gives an app (Wear OS 7's double pinch and wrist turn,
 * through Compose's oneHandedGesture) is used by the screens; what it leaves to the app (flick, double flick, shake, double tap, raise)
 * is detected here from the accelerometer and the gyroscope, by the pure detectors in wearproto, only for the gestures he mapped to
 * something, and only while the app (or a call or a ring, which are ongoing) is up, unless he switched always-on on.
 */
object WatchGestures {
    private const val TAG = "WatchGestures"
    private const val PREFS = "watch_gestures"
    private val router = GestureRouter()
    private var host: GestureHost? = null
    private var foreground = 0
    @Volatile private var interactive = true
    private var screenReceiver: BroadcastReceiver? = null
    @Volatile var armedHint: (() -> Unit)? = null

    /** The foreground screen, when there is one: it gets the effect first and says whether it handled it. */
    @Volatile var uiHandler: ((GestureEffect) -> Boolean)? = null
    /** What the foreground screen knows that the router needs: is an approval on the screen. */
    @Volatile var approvalOnScreen: () -> Boolean = { false }

    fun map(c: Context): GestureMap = GestureMap.fromJson(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("map", null))

    fun save(c: Context, m: GestureMap) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("map", m.toJson()).apply()
        refresh(c)
    }

    /** What this watch has, so the settings screen offers only what can work here. */
    fun profile(c: Context): DeviceProfile {
        val sm = c.getSystemService(SensorManager::class.java)
        return DeviceProfile(Build.VERSION.SDK_INT, sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null, sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null, Build.MANUFACTURER.orEmpty())
    }

    @Synchronized fun resumed(c: Context) { foreground++; refresh(c) }
    @Synchronized fun paused(c: Context) { foreground = (foreground - 1).coerceAtLeast(0); refresh(c) }

    /** Register or let go of the sensors, and the always-on service, to match what is mapped and what is going on. */
    @Synchronized fun refresh(c: Context) {
        val app = c.applicationContext
        val m = map(app)
        val w = app as? WatchApp
        val ongoing = (w?.call?.state?.value?.active == true) || WatchRing.isRinging()
        val pm = app.getSystemService(PowerManager::class.java)
        val on = SensorPlan.active(m, appForeground = foreground > 0, ongoingActivity = ongoing, interactive = interactive && (pm?.isInteractive != false))
        val h = host ?: GestureHost(app).also { host = it }
        if (on) h.start(m, if (foreground > 0 || ongoing) SensorPlan.Mode.FOREGROUND else SensorPlan.Mode.ALWAYS_ON) else h.stop()
        if (m.alwaysOn && m.enabled().isNotEmpty()) GestureService.start(app) else GestureService.stop(app)
    }

    fun screen(c: Context, on: Boolean) { interactive = on; refresh(c) }

    /** A gesture was detected: the router decides what it means here and now, and the screen (or, with none, the headless path) does it. */
    internal fun onGesture(c: Context, g: Gesture) {
        val app = c.applicationContext
        val w = app as WatchApp
        val ctx = GestureContext(callLive = w.call.state.value.active, ringing = WatchRing.isRinging(), approvalOnScreen = approvalOnScreen())
        val effect = router.decide(g, map(app), ctx, SystemClock.elapsedRealtime())
        if (effect == GestureEffect.Ignore) return
        Log.i(TAG, "$g -> $effect")
        if (uiHandler?.invoke(effect) == true) return
        headless(app, w, effect)
    }

    /** No screen is up: only what needs no screen can be done (stop a ring, end or start a call); opening the talk screen is tried, and may be refused by the system. */
    private fun headless(app: Context, w: WatchApp, e: GestureEffect) {
        when (e) {
            GestureEffect.DismissRing -> WatchRing.dismissTop(app)
            GestureEffect.EndCall -> w.call.end()
            GestureEffect.StartCall -> w.call.start()
            GestureEffect.StartTalk -> runCatching { app.startActivity(Intent(app, MainActivity::class.java).putExtra("go", "talk").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            else -> {}
        }
    }

    fun registerScreenReceiver(c: Context) {
        if (screenReceiver != null) return
        val app = c.applicationContext
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { screen(app, intent.action == Intent.ACTION_SCREEN_ON) }
        }
        app.registerReceiver(screenReceiver, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) })
    }
}

/** Owns the sensor registrations for the gestures that are on. Samples are timestamped by the sensor, not by when this ran. */
internal class GestureHost(private val c: Context) : SensorEventListener {
    private val sm = c.getSystemService(SensorManager::class.java)
    private var engine: GestureEngine? = null
    private var key = ""

    fun start(map: GestureMap, mode: SensorPlan.Mode) {
        val k = map.toJson() + mode
        if (k == key && engine != null) return
        stop()
        val enabled = map.enabled()
        val e = GestureEngine(enabled); engine = e; key = k
        for (r in SensorPlan.requests(enabled, mode)) {
            val sensor = sm?.getDefaultSensor(if (r.sensor == "gyro") Sensor.TYPE_GYROSCOPE else Sensor.TYPE_ACCELEROMETER) ?: continue
            val ok = runCatching { sm.registerListener(this, sensor, r.periodUs, r.maxLatencyUs) }.getOrDefault(false)
            if (!ok) Log.w("WatchGestures", "could not register ${r.sensor}")
        }
    }

    fun stop() { runCatching { sm?.unregisterListener(this) }; engine = null; key = "" }

    override fun onSensorChanged(e: SensorEvent) {
        val eng = engine ?: return
        val s = Sample(e.timestamp / 1_000_000L, e.values[0], e.values[1], e.values[2])
        val found = when (e.sensor.type) { Sensor.TYPE_GYROSCOPE -> eng.onGyro(s); Sensor.TYPE_ACCELEROMETER -> eng.onAccel(s); else -> return }
        found.forEach { WatchGestures.onGesture(c, it) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

/**
 * Always-on gestures: a foreground service with an ongoing notification (so he can see it and stop it), kept only while he has
 * it switched on and at least one gesture mapped. It only keeps the process alive and tells [WatchGestures] when the screen turns
 * on or off; the sensors are registered by [GestureHost] and let go of when the screen is off.
 */
class GestureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.gestures_channel), NotificationManager.IMPORTANCE_MIN))
        val n: Notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_agent).setContentTitle(getString(R.string.gestures_on))
            .setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        runCatching { startForeground(ID, n, type) }.onFailure { runCatching { startForeground(ID, n) } }
        WatchGestures.registerScreenReceiver(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { WatchGestures.refresh(this); return START_STICKY }

    companion object {
        private const val ID = 4402
        private const val CHANNEL = "gestures"
        fun start(c: Context) { runCatching { c.startForegroundService(Intent(c, GestureService::class.java)) }.onFailure { Log.w("WatchGestures", "service: ${it.message}") } }
        fun stop(c: Context) { runCatching { c.stopService(Intent(c, GestureService::class.java)) } }
    }
}
