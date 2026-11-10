package com.past9.phoneaos.screen

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
import com.past9.phoneaos.App
import com.past9.phoneaos.MainActivity
import com.past9.phoneaos.system.ForegroundGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/** The apps on this phone that have a front door, by the names the person knows them by. */
class InstalledApps(private val context: Context) : ScreenApps {
    private fun all(): List<AppRef> = everything()

    /** Every app with a front door, for the pickers. */
    fun everything(): List<AppRef> {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0).mapNotNull { r ->
            val a = r.activityInfo ?: return@mapNotNull null
            AppRef(a.packageName, r.loadLabel(pm)?.toString().orEmpty().ifBlank { a.packageName }, a.packageName + "/" + a.name)
        }.filter { it.pkg != context.packageName }
    }

    override fun find(nameOrPackage: String): AppRef? {
        val q = nameOrPackage.trim(); if (q.isEmpty()) return null
        val list = all()
        return list.firstOrNull { it.pkg == q } ?: list.firstOrNull { it.label.equals(q, true) }
            ?: list.filter { it.label.startsWith(q, true) }.minByOrNull { it.label.length }
            ?: list.filter { it.label.contains(q, true) }.minByOrNull { it.label.length }
    }

    override fun label(pkg: String): String = runCatching {
        val pm = context.packageManager; pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    override fun near(q: String): List<String> {
        val words = q.lowercase().split(' ', '.', '_').filter { it.length >= 3 }
        return all().filter { a -> words.any { a.label.contains(it, true) || a.pkg.contains(it, true) } }.map { it.label }.distinct().take(6)
    }
}

/** What the open screen should show about the background screen: asked for by a tool, a notification or adb. */
object ScreenUi {
    const val PEEK = "peek"; const val SETUP = "setup"; const val RUNS = "runs"; const val BLOCKED = "blocked"; const val SAVE = "save"; const val TYPE = "type"
    /** Which sheet to show, or null. */
    val show = MutableStateFlow<String?>(null)
    /** Why setup is being shown, in the agent's words. */
    val reason = MutableStateFlow("")
}

/** The one background screen of this app, wired to the phone. */
object Screens {
    const val RULES = "screen_rules"; const val WORDS = "screen_words"; const val LOCKED = "screen_locked"
    /** Apps the person added to the blocked list: one package (or pattern) per line, with the app's name after a bar. */
    const val BLOCKED = "screen_blocked"
    const val RECORD = "screen_record"
    /** The whole feature's switch. Off by default: with it off there are no screen tools, no service and no setup prompts. */
    const val ON = "screen_on"

    /** The loopback port the helper listens on and the secret both sides must know: made once on this phone, kept in the app's own settings. */
    fun port(context: Context): Int {
        val s = App.graph(context).settings
        s.extra("screen_port")?.toIntOrNull()?.let { return it }
        return (20_000..60_000).random().also { s.setExtra("screen_port", it.toString()) }
    }
    fun token(context: Context): String {
        val s = App.graph(context).settings
        s.extra("screen_token")?.takeIf { it.length >= 32 }?.let { return it }
        val bytes = ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }.also { s.setExtra("screen_token", it) }
    }
    fun on(context: Context): Boolean = runCatching { App.graph(context).settings.extra(ON) == "1" }.getOrDefault(false)

    /** The person switches the background screen on or off (Settings). Off also closes whatever is open on it. */
    fun setOn(context: Context, on: Boolean) {
        val app = context.applicationContext
        App.graph(app).settings.setExtra(ON, if (on) "1" else "0")
        // The start script (with this phone's port and secret) is written where a computer's adb can run it.
        if (on) ScreenStarter.writeScript(app)
        // Switching it on is also the person saying "carry on": an earlier Stop is cleared.
        if (on) made?.userAllow()
        // Shizuku's door into the app only exists while the feature is on.
        runCatching {
            app.packageManager.setComponentEnabledSetting(android.content.ComponentName(app, "rikka.shizuku.ShizukuProvider"),
                if (on) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, android.content.pm.PackageManager.DONT_KILL_APP)
        }
        // A linked computer holds the tool list it was given at hello: say hello again, so the screen tools appear or go at once.
        runCatching { com.past9.phoneaos.remote.RemoteHub.session.value?.factsChangedForce() }
        if (!on) made?.let { s -> kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch { runCatching { s.close() } } }
    }
    @Volatile private var builtIn: String? = null
    @Volatile private var runsStore: ScreenRuns? = null

    /** The built-in list of banking and money apps, from the app's own file. */
    fun builtInBlocklist(context: Context): String = builtIn ?: runCatching { context.assets.open(ScreenBlocklist.ASSET).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrDefault("").also { builtIn = it }

    fun blockAdded(context: Context): List<String> = App.graph(context).settings.extra(BLOCKED).orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** The person adds an app to the blocked list. There is no way to take a built-in one off. */
    fun blockAdd(context: Context, pkg: String, label: String) {
        val s = App.graph(context).settings
        val lines = blockAdded(context).filter { it.substringBefore('|').trim() != pkg } + "$pkg | $label"
        s.setExtra(BLOCKED, lines.joinToString("\n"))
    }
    fun blockRemoveAdded(context: Context, pkg: String) {
        val s = App.graph(context).settings
        s.setExtra(BLOCKED, blockAdded(context).filter { it.substringBefore('|').trim() != pkg }.joinToString("\n"))
    }

    fun runs(context: Context): ScreenRuns = runsStore ?: synchronized(this) { runsStore ?: ScreenRuns(File(context.applicationContext.filesDir, "screen-runs")).also { runsStore = it } }

    /** Paint black over boxes given in screen pixels, in a picture of a screen [screenWidth] wide. */
    fun maskJpeg(jpeg: ByteArray, boxes: List<IntArray>, screenWidth: Int): ByteArray {
        val src = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val bmp = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val k = bmp.width.toFloat() / screenWidth.coerceAtLeast(1)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK }
        for (b in boxes) canvas.drawRect(b[0] * k, b[1] * k, b[2] * k, b[3] * k, paint)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, out)
        return out.toByteArray()
    }
    @Volatile private var made: ScreenSession? = null
    @Volatile private var previewLink: ScreenBackend? = null

    /** A hidden display the same shape as the phone's own, 720 wide: sharp enough to read, light enough to run beside the real one. */
    fun config(context: Context): ScreenConfig {
        val s = App.graph(context).settings
        val m = context.resources.displayMetrics
        val realW = minOf(m.widthPixels, m.heightPixels).coerceAtLeast(320); val realH = maxOf(m.widthPixels, m.heightPixels)
        val w = minOf(720, realW) / 2 * 2
        val h = (realH.toLong() * w / realW).toInt() / 2 * 2
        val added = blockAdded(context)
        return ScreenConfig(ScreenRules.parse(s.extra(RULES)), ScreenRules.extraWords(s.extra(WORDS)), s.extra(LOCKED) == "1", w, h, (m.densityDpi.toLong() * w / realW).toInt().coerceIn(120, 640),
            ScreenBlocklist(builtInBlocklist(context), added), added, s.extra(RECORD) != "0")
    }

    fun session(context: Context): ScreenSession = made ?: synchronized(this) {
        made ?: run {
            val app = context.applicationContext
            // In remote mode the phone runs no agent of its own, so this is the place that is always reached before a screen tool runs.
            com.past9.phoneaos.connections.learnedChanged = { runCatching { com.past9.phoneaos.remote.RemoteHub.session.value?.factsChangedForce() } }
            ScreenSession(
                backend = SocketBackend({ port(app) }, { token(app) }),
                apps = InstalledApps(app),
                config = { config(app) },
                pictures = File(app.cacheDir, "screen"),
                start = { start(app) },
                needSetup = { why -> askForSetup(app, why) },
                active = { on -> if (on) ForegroundGuard.start(app, ScreenService::class.java) else ForegroundGuard.stop(app, ScreenService::class.java) },
                readPicture = { path -> com.past9.phoneaos.tools.Ocr.text(path) },
                findInPicture = { path, words -> com.past9.phoneaos.tools.Ocr.find(path, words) },
                runs = runs(app),
                mask = { jpeg, boxes, width -> maskJpeg(jpeg, boxes, width) },
            ).also { made = it }
        }
    }

    /** A second line to the helper for the live picture and the person's own touches, so they never wait behind the agent. */
    fun preview(context: Context): ScreenBackend = previewLink ?: synchronized(this) { previewLink ?: SocketBackend({ port(context.applicationContext) }, { token(context.applicationContext) }).also { previewLink = it } }

    /** Start the helper with what this phone has. Null when it was started; otherwise why it could not be. */
    fun start(context: Context): String? {
        if (ScreenStarter.shizukuGranted()) return ScreenStarter.startWithShizuku(context)
        if (ScreenStarter.shizukuRunning()) { ScreenStarter.shizukuAsk(); return "Shizuku has not been allowed for this app yet" }
        if (ScreenStarter.shizukuInstalled(context)) return "Shizuku is installed but not started (it stops when the phone restarts)"
        return "it has not been set up on this phone yet"
    }

    private fun askForSetup(context: Context, why: String) {
        ScreenStarter.writeScript(context)
        ScreenUi.reason.value = why
        if (com.past9.phoneaos.system.ChatOnScreen.resumed) { ScreenUi.show.value = ScreenUi.SETUP; return }
        // Not looking at the app: a quiet notification that opens the steps. Never a pop-up.
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(ScreenService.CHANNEL, "Background screen", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, 4472, Intent(context, MainActivity::class.java).putExtra("open", "screen_setup").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val st = App.graph(context).settings.state.value
        runCatching {
            nm.notify(4472, androidx.core.app.NotificationCompat.Builder(context, ScreenService.CHANNEL)
                .setSmallIcon(com.past9.phoneaos.system.Identity.statIcon(context, st.mascot))
                .setContentTitle("The background screen needs setting up").setContentText("${st.agentName.ifBlank { "Your agent" }} wanted to use an app in the background. Tap for the steps.")
                .setAutoCancel(true).setContentIntent(open).build())
        }
    }
}

/**
 * Keeps the app awake while the background screen is open, so Samsung and others do not freeze it mid-task. Its
 * notification is the person's handle on it: tap to watch or take over, Stop to halt the agent there at once.
 */
class ScreenService : Service() {
    private var jobs: Job? = null
    /** A small live picture of the hidden screen, shown on the notification while a job runs. */
    @Volatile private var thumb: android.graphics.Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun build(): Notification {
        val st = App.graph(this).settings.state.value
        val s = Screens.session(this).state.value
        val name = st.agentName.ifBlank { "Your agent" }
        // Straight to the full-screen watch view, in its own task: the app he was in is not disturbed and comes back when he leaves it.
        val open = PendingIntent.getActivity(this, 0, Intent(this, ScreenWatchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun act(action: String, code: Int) = PendingIntent.getService(this, code, Intent(this, ScreenService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = when {
            s.stopped -> "Background screen stopped"
            s.takenOver -> "You have it"
            else -> "${s.owner.takeIf { it.isNotBlank() && it != "main" && it != "linked computer" } ?: name} is using ${s.app.ifBlank { "an app" }} in the background"
        }
        val text = when {
            s.stopped -> "$name can do nothing more there until you allow it"
            else -> s.step.ifBlank { "Tap to watch or take over" }
        }
        val b = androidx.core.app.NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(com.past9.phoneaos.system.Identity.statIcon(this, st.mascot)).setContentTitle(title).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
        thumb?.let { b.setLargeIcon(it) }
        // A live update where the phone has them (Android 16: the status chip and Samsung's Now Bar): asked for by the
        // platform's own keys, so nothing here depends on the library version. Where it is not promoted, this is a plain ongoing notification.
        if (!s.stopped) b.addExtras(android.os.Bundle().apply {
            putBoolean("android.requestPromotedOngoing", true)
            putString("android.shortCriticalText", s.app.ifBlank { "Working" }.take(7))
        })
        if (s.stopped) b.addAction(0, "Allow again", act(ALLOW, 2)) else b.addAction(0, "Stop", act(STOP, 1))
        if (s.takenOver) b.addAction(0, "Give back to the agent", act(GIVE, 3)) else if (!s.stopped && s.open) b.addAction(0, "Take over", act(TAKE, 4))
        if (!s.takenOver) b.addAction(0, "Watch", open)
        return b.build()
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Background screen", NotificationManager.IMPORTANCE_LOW))
        if (!ForegroundGuard.enter(this, ID, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0) { build() }) return
        val session = Screens.session(this)
        jobs = CoroutineScope(Dispatchers.Main).launch {
            launch { session.state.collectLatest { runCatching { nm.notify(ID, build()) } } }
            // The live picture: gently, every two seconds, only while something is open and only when it changed.
            launch {
                val link = Screens.preview(this@ScreenService)
                var seq = -1L
                while (true) {
                    delay(2_000)
                    if (!session.state.value.open) continue
                    val m = runCatching { link.call(op("frame").put("max", 160).put("q", 50).put("after", seq), 3_000) }.getOrNull() ?: continue
                    val bin = m.bin ?: continue
                    seq = m.json.optLong("seq")
                    android.graphics.BitmapFactory.decodeByteArray(bin, 0, bin.size)?.let { thumb = it; runCatching { nm.notify(ID, build()) } }
                }
            }
            // Battery and privacy: an app left open on a display nobody watches is closed after a few idle minutes.
            while (true) {
                delay(30_000)
                val st = App.graph(this@ScreenService).runtime.status.value
                runCatching { session.closeIfIdle(IDLE_MS, st.working || st.helpers > 0) }
                if (!session.state.value.open) { stopSelf(); break }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> { val s = Screens.session(this); s.userStop(); CoroutineScope(Dispatchers.Default).launch { runCatching { s.close() } } }
            TAKE -> { val s = Screens.session(this); CoroutineScope(Dispatchers.Default).launch { runCatching { s.userTakeOverReal() } } }
            GIVE -> { val s = Screens.session(this); CoroutineScope(Dispatchers.Default).launch { runCatching { s.userGiveBack() } } }
            ALLOW -> Screens.session(this).userAllow()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { ForegroundGuard.left(this); jobs?.cancel(); super.onDestroy() }

    companion object {
        const val CHANNEL = "screen"
        const val ID = 43
        const val STOP = "com.past9.phoneaos.screen.STOP"
        const val ALLOW = "com.past9.phoneaos.screen.ALLOW"
        const val TAKE = "com.past9.phoneaos.screen.TAKE"
        const val GIVE = "com.past9.phoneaos.screen.GIVE"
        const val IDLE_MS = 3 * 60_000L
    }
}
