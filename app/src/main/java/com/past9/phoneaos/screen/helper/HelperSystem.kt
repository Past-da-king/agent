package com.past9.phoneaos.screen.helper

import android.content.AttributionSource
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/*
 * The parts of the helper that reach into Android as the shell user. This code only ever runs inside the helper
 * process (app_process, uid 2000), never inside the app.
 *
 * The approach follows two open-source projects, with thanks (see THIRD-PARTY-NOTICES.md):
 * - scrcpy (Apache-2.0, Genymobile): the virtual display flags for "--new-display", presenting as com.android.shell,
 *   and input events that carry a display id.
 * - Agent-VScreen (MIT, kilolonion): the order in which display flags are tried, and keeping a surface on the display
 *   for its whole life so the system does not put it to sleep.
 */

internal const val SHELL_PACKAGE = "com.android.shell"

internal class ExecResult(val code: Int, val out: String)

internal object Sys {
    val sdk: Int get() = Build.VERSION.SDK_INT

    /** Run a shell command as ourselves (the shell user). Never throws. */
    fun exec(vararg cmd: String, timeoutMs: Long = 15_000): ExecResult = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val buf = ByteArrayOutputStream()
        val reader = Thread { runCatching { p.inputStream.copyTo(buf) } }.apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) { p.destroyForcibly(); ExecResult(-1, "timed out: " + buf.toString().take(500)) }
        else { reader.join(1_000); ExecResult(p.exitValue(), buf.toString()) }
    } catch (t: Throwable) { ExecResult(-2, t.toString()) }

    /** A Context good enough to make a DisplayManager, without being an app. */
    val systemContext: Context by lazy {
        val at = Class.forName("android.app.ActivityThread")
        // The system's own way first: on Android 14 and later a hand-made ActivityThread has no configuration
        // controller, and making a display with it fails (seen on a Galaxy A35, Android 16).
        val thread = runCatching { at.getMethod("currentActivityThread").invoke(null) }.getOrNull()
            ?: runCatching { at.getMethod("systemMain").invoke(null) }.getOrNull()
            ?: run {
                val ctor = at.getDeclaredConstructor(); ctor.isAccessible = true
                val t = ctor.newInstance()
                at.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, t)
                runCatching { at.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(t, true) }
                t
            }
        at.getMethod("getSystemContext").invoke(thread) as Context
    }

    /** Says it is com.android.shell, which is who we are: the display service checks the package against the caller. */
    class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = SHELL_PACKAGE
        override fun getOpPackageName(): String = SHELL_PACKAGE
        override fun getApplicationContext(): Context = this
        override fun getAttributionSource(): AttributionSource =
            if (Build.VERSION.SDK_INT >= 31) AttributionSource.Builder(android.os.Process.myUid()).setPackageName(SHELL_PACKAGE).build()
            else super.getAttributionSource()
    }

    fun service(name: String): IBinder? =
        Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, name) as IBinder?

    fun field(o: Any, vararg names: String): Any? {
        for (n in names) runCatching { return o.javaClass.getField(n).get(o) }
        return null
    }

    fun cause(t: Throwable): Throwable = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t

    /**
     * adb kills everything in its own process group when it stops (wireless debugging switched off, cable pulled).
     * Moving to the root group lets the helper outlive the adb session that started it, the way Shizuku does.
     */
    fun leaveAdbGroup() {
        val pid = android.os.Process.myPid().toString()
        for (dir in listOf("/acct", "/dev/cg2_bpf", "/sys/fs/cgroup", "/dev/memcg/apps")) {
            runCatching { java.io.File(dir, "cgroup.procs").takeIf { it.canWrite() }?.appendText(pid) }
        }
    }
}

/** android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_*, the hidden ones by number. */
internal object Flags {
    const val PUBLIC = 1 shl 0
    const val OWN_CONTENT_ONLY = 1 shl 3
    const val SUPPORTS_TOUCH = 1 shl 6
    const val ROTATES_WITH_CONTENT = 1 shl 7
    const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    const val TRUSTED = 1 shl 10
    const val OWN_DISPLAY_GROUP = 1 shl 11
    const val ALWAYS_UNLOCKED = 1 shl 12
    const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
    const val OWN_FOCUS = 1 shl 14
    const val DEVICE_DISPLAY_GROUP = 1 shl 15
    const val STEAL_TOP_FOCUS_DISABLED = 1 shl 16

    private val NAMES = listOf(PUBLIC to "PUBLIC", OWN_CONTENT_ONLY to "OWN_CONTENT_ONLY", SUPPORTS_TOUCH to "SUPPORTS_TOUCH",
        ROTATES_WITH_CONTENT to "ROTATES_WITH_CONTENT", DESTROY_CONTENT_ON_REMOVAL to "DESTROY_CONTENT_ON_REMOVAL", TRUSTED to "TRUSTED",
        OWN_DISPLAY_GROUP to "OWN_DISPLAY_GROUP", ALWAYS_UNLOCKED to "ALWAYS_UNLOCKED", TOUCH_FEEDBACK_DISABLED to "TOUCH_FEEDBACK_DISABLED",
        OWN_FOCUS to "OWN_FOCUS", DEVICE_DISPLAY_GROUP to "DEVICE_DISPLAY_GROUP", STEAL_TOP_FOCUS_DISABLED to "STEAL_TOP_FOCUS_DISABLED")

    fun names(flags: Int): List<String> = NAMES.filter { flags and it.first != 0 }.map { it.second }

    /**
     * The sets to try, best first. The best one is a trusted screen with its own focus that can never take the focus
     * from the screen the person is using (Android 14 and up). Stricter systems get plainer sets. [whileLocked] adds
     * the flag that lets it keep working behind the lock screen.
     */
    fun ladder(sdk: Int, whileLocked: Boolean): List<Int> {
        val basic = PUBLIC or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or ROTATES_WITH_CONTENT
        val unlocked = if (whileLocked) ALWAYS_UNLOCKED else 0
        val out = ArrayList<Int>()
        if (sdk >= 34) {
            out += basic or TRUSTED or OWN_DISPLAY_GROUP or unlocked or TOUCH_FEEDBACK_DISABLED or OWN_FOCUS or DEVICE_DISPLAY_GROUP or STEAL_TOP_FOCUS_DISABLED
            out += basic or TRUSTED or OWN_DISPLAY_GROUP or unlocked or TOUCH_FEEDBACK_DISABLED or OWN_FOCUS or DEVICE_DISPLAY_GROUP
        }
        if (sdk >= 33) {
            out += basic or TRUSTED or OWN_DISPLAY_GROUP or unlocked or TOUCH_FEEDBACK_DISABLED
            out += basic or TRUSTED
        }
        out += basic
        out += PUBLIC or OWN_CONTENT_ONLY
        // Every set first WITH "destroy content on removal": if the display is ever torn down (the helper dies, the
        // system removes it), its apps are destroyed there instead of being moved onto the person's own screen.
        // The same sets without it are the fallback for a system that refuses the flag.
        return (out.map { it or DESTROY_CONTENT_ON_REMOVAL } + out).distinct()
    }
}

/** One hidden display and the latest picture of it. */
internal class HiddenDisplay private constructor(
    private val vd: VirtualDisplay, private val reader: ImageReader, private val thread: HandlerThread,
    val width: Int, val height: Int, val dpi: Int, val flags: Int,
) {
    val id: Int get() = vd.display.displayId
    private val frameLock = Any()
    private var held: Image? = null
    private var scratch: Bitmap? = null
    private var packed: ByteBuffer? = null
    /** Counts pictures as they arrive, so a caller can tell "nothing changed" from "new picture". */
    @Volatile var seq = 0L; private set
    @Volatile var lastFrameAt = 0L; private set

    private fun start() {
        // Always take the newest picture and give the older one back. A reader that is not emptied stalls the display.
        reader.setOnImageAvailableListener({ r ->
            val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            synchronized(frameLock) { held?.close(); held = img; seq++; lastFrameAt = SystemClock.uptimeMillis() }
        }, Handler(thread.looper))
    }

    class Frame(val jpeg: ByteArray, val width: Int, val height: Int, val seq: Long, val blank: Boolean)

    /** The latest picture as a JPEG no wider than [maxWidth]. Null when the display has drawn nothing yet. */
    fun frame(maxWidth: Int, quality: Int): Frame? = synchronized(frameLock) {
        val img = held ?: return null
        val plane = img.planes[0]
        val rowStride = plane.rowStride; val pixelStride = plane.pixelStride
        val src = plane.buffer; src.rewind()
        val w = img.width; val h = img.height
        val bmp = scratch?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { scratch?.recycle(); scratch = it }
        if (pixelStride == 4 && rowStride == w * 4 && src.remaining() >= w * h * 4) {
            bmp.copyPixelsFromBuffer(src)
        } else {
            // Rows are padded: copy them one by one into a tight buffer.
            val tight = packed?.takeIf { it.capacity() == w * h * 4 } ?: ByteBuffer.allocateDirect(w * h * 4).also { packed = it }
            tight.clear()
            val row = ByteArray(w * 4)
            for (y in 0 until h) {
                src.position(y * rowStride)
                src.get(row, 0, minOf(row.size, src.remaining()))
                tight.put(row)
            }
            tight.rewind(); bmp.copyPixelsFromBuffer(tight)
        }
        val blank = isBlank(bmp)
        val outW = maxWidth.coerceIn(120, w)
        val scaled = if (outW < w) Bitmap.createScaledBitmap(bmp, outW, (h.toLong() * outW / w).toInt().coerceAtLeast(1), true) else bmp
        val out = ByteArrayOutputStream(96 * 1024)
        scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 95), out)
        val f = Frame(out.toByteArray(), scaled.width, scaled.height, seq, blank)
        if (scaled !== bmp) scaled.recycle()
        f
    }

    /** One flat colour all over: nothing drawn, or a protected screen the system blacked out. */
    private fun isBlank(b: Bitmap): Boolean {
        val first = b.getPixel(b.width / 2, b.height / 2)
        for (iy in 1..11) for (ix in 1..7) if (b.getPixel(b.width * ix / 8, b.height * iy / 12) != first) return false
        return true
    }

    val destroysContent: Boolean get() = flags and Flags.DESTROY_CONTENT_ON_REMOVAL != 0

    fun release() {
        runCatching { vd.release() }
        synchronized(frameLock) { runCatching { held?.close() }; held = null; scratch?.recycle(); scratch = null }
        runCatching { reader.close() }
        thread.quitSafely()
    }

    companion object {
        /** Make the display, trying the flag sets in order. Throws the last refusal when the system takes none. */
        fun create(name: String, width: Int, height: Int, dpi: Int, whileLocked: Boolean): HiddenDisplay {
            val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
            val dm = ctor.newInstance(Sys.ShellContext(Sys.systemContext))
            val thread = HandlerThread("screen-frames").apply { start() }
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            var last: Throwable? = null
            for (f in Flags.ladder(Sys.sdk, whileLocked)) {
                try {
                    val vd = dm.createVirtualDisplay(name, width, height, dpi, reader.surface, f) ?: throw IllegalStateException("the system returned no display")
                    return HiddenDisplay(vd, reader, thread, width, height, dpi, f).also { it.start() }
                } catch (t: Throwable) { last = Sys.cause(t) }
            }
            runCatching { reader.close() }; thread.quitSafely()
            throw IllegalStateException("The system refused a hidden display: ${last?.message ?: last}")
        }
    }
}

/** Touches and keys, sent to one display only. */
internal object Input {
    private val manager: Any by lazy {
        val cls = runCatching { Class.forName("android.hardware.input.InputManagerGlobal") }.getOrElse { Class.forName("android.hardware.input.InputManager") }
        cls.getDeclaredMethod("getInstance").invoke(null)!!
    }
    private val inject by lazy { manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType) }
    private val setDisplayId by lazy { InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType) }
    private const val ASYNC = 0
    private var downTime = 0L

    private fun send(e: InputEvent, displayId: Int): Boolean {
        // Never the person's own screen: the helper only ever touches the display it made.
        if (displayId <= 0) return false
        setDisplayId.invoke(e, displayId)
        return inject.invoke(manager, e, ASYNC) as Boolean
    }

    /** action: MotionEvent.ACTION_DOWN, MOVE or UP. */
    @Synchronized fun touch(displayId: Int, action: Int, x: Float, y: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f; size = 1f })
        val e = MotionEvent.obtain(downTime, now, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        return try { send(e, displayId) } finally { e.recycle() }
    }

    fun tap(displayId: Int, x: Float, y: Float, holdMs: Long): Boolean {
        val a = touch(displayId, MotionEvent.ACTION_DOWN, x, y)
        Thread.sleep(holdMs.coerceIn(30, 5_000))
        return touch(displayId, MotionEvent.ACTION_UP, x, y) && a
    }

    fun swipe(displayId: Int, x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        val total = ms.coerceIn(60, 5_000); val steps = (total / 12).toInt().coerceAtLeast(4)
        var ok = touch(displayId, MotionEvent.ACTION_DOWN, x1, y1)
        for (i in 1..steps) {
            Thread.sleep(total / steps)
            val t = i.toFloat() / steps
            ok = touch(displayId, MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t) && ok
        }
        // A short rest at the end, so a drag does not turn into a fling it was not meant to be.
        Thread.sleep(30)
        return touch(displayId, MotionEvent.ACTION_UP, x2, y2) && ok
    }

    fun key(displayId: Int, code: Int, long: Boolean = false, meta: Int = 0): Boolean {
        val t = SystemClock.uptimeMillis()
        fun ev(action: Int, repeat: Int = 0, flags: Int = 0) =
            KeyEvent(t, SystemClock.uptimeMillis(), action, code, repeat, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, InputDevice.SOURCE_KEYBOARD)
        var ok = send(ev(KeyEvent.ACTION_DOWN), displayId)
        if (long) { Thread.sleep(600); ok = send(ev(KeyEvent.ACTION_DOWN, 1, KeyEvent.FLAG_LONG_PRESS), displayId) && ok }
        return send(ev(KeyEvent.ACTION_UP), displayId) && ok
    }
}

/** Where apps are: which display each task is on, and moving or closing them. */
internal object Tasks {
    class Row(val taskId: Int, val displayId: Int, val top: ComponentName?, val visible: Boolean, val children: IntArray)

    private val atm: Any by lazy { Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!! }

    /** Every root task, top of each display first. */
    fun all(): List<Row> {
        val list = runCatching { atm.javaClass.getMethod("getAllRootTaskInfos").invoke(atm) }
            .getOrElse { atm.javaClass.getMethod("getAllStackInfos").invoke(atm) } as List<*>
        return list.filterNotNull().map { o ->
            Row((Sys.field(o, "taskId", "stackId") as? Int) ?: -1, (Sys.field(o, "displayId") as? Int) ?: 0,
                Sys.field(o, "topActivity") as? ComponentName,
                (Sys.field(o, "visible", "isVisible") as? Boolean) ?: false,
                (Sys.field(o, "childTaskIds", "taskIds") as? IntArray) ?: IntArray(0))
        }
    }

    fun move(taskId: Int, displayId: Int) {
        val m = runCatching { atm.javaClass.getMethod("moveRootTaskToDisplay", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType) }
            .getOrElse { atm.javaClass.getMethod("moveStackToDisplay", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType) }
        try { m.invoke(atm, taskId, displayId) } catch (e: java.lang.reflect.InvocationTargetException) { throw e.targetException }
    }

    /** Bring a task back to the front of its own display (what the person had open). */
    fun toFront(taskId: Int): Boolean = runCatching {
        atm.javaClass.getMethod("startActivityFromRecents", Int::class.javaPrimitiveType, android.os.Bundle::class.java).invoke(atm, taskId, null); true
    }.getOrDefault(false)

    /** The displays of ours that exist now (the system lists them by name). */
    fun ourDisplays(name: String): Set<Int> = runCatching {
        val dm = Sys.systemContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.displays.filter { it.name == name }.map { it.displayId }.toSet()
    }.getOrDefault(emptySet())

    fun remove(taskId: Int): Boolean = runCatching {
        atm.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType).invoke(atm, taskId) as? Boolean ?: true
    }.getOrDefault(false)

    /** Keep the keyboard off the person's screen: fields on the hidden display never raise it anywhere. */
    fun hideKeyboardOn(displayId: Int): String = try {
        val wm = Class.forName("android.view.IWindowManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, Sys.service("window"))!!
        if (Sys.sdk >= 31) { wm.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(wm, displayId, 2); "hide" }
        else { wm.javaClass.getMethod("setShouldShowIme", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).invoke(wm, displayId, false); "main" }
    } catch (t: Throwable) { "unchanged: " + (Sys.cause(t).message ?: t.toString()) }
}
