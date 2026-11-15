package com.past9.phoneaos.assist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.past9.phoneaos.screen.Screens
import com.past9.phoneaos.voice.LiveSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Lets Gemini Live SEE the screen: while a live session is open (sheet or bubble) it takes a look through the phone's
 * own assistant path (the same one the side key uses, no screen recording), shrinks it to about 768 px, and sends it into
 * the session as a JPEG frame. About one a second while the screen changes, much slower when it holds still, and one at
 * once when the user starts speaking.
 *
 * Never when "read the screen" is off, never a banking or money app (the assistant's list and the background screen's),
 * never a protected window: then nothing is sent and Live is told the screen is private. Nothing is kept: each picture
 * is deleted as soon as it has been shrunk.
 */
class LiveFrames(
    private val context: Context,
    private val scope: CoroutineScope,
    private val session: () -> LiveSession?,
    private val onSeeing: (Seeing) -> Unit,
    private val announce: (String) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val throttle = FrameThrottle()
    @Volatile private var forced = false
    private var job: Job? = null
    private var lastGrid: IntArray? = null
    private var lastOther = ""
    private var seeing = Seeing.OFF

    fun start() {
        lastOther = AssistHub.screen.value?.pkg?.takeIf { it != context.packageName }.orEmpty()
        job = scope.launch(Dispatchers.Default) { while (isActive) { delay(250); runCatching { tick() }.onFailure { Log.w(TAG, "frame failed: ${it.message}") } } }
    }

    fun stop() { job?.cancel(); job = null; lastGrid = null; latest = null }

    /** The user's turn started: look now, so "look at this" sees what is on screen at this moment. */
    fun userTurnStarted() { forced = true }

    private suspend fun tick() {
        val s = session() ?: return
        if (!s.isReady) return
        if (!FrameGate.enabled(AssistPrefs.readScreen(context), AssistPrefs.liveSees(context))) { set(Seeing.OFF); return }
        val now = clock(); val force = forced
        if (!throttle.due(now, force)) return
        forced = false
        val ctx = AssistScreenNow.captureFrame(context)
        val top = ctx?.pkg.orEmpty()
        if (top.isNotBlank() && top != context.packageName) lastOther = top
        val app = FrameGate.appToJudge(top, context.packageName, lastOther)
        val label = if (app == top) ctx?.label.orEmpty() else ""
        val verdict = FrameGate.verdict(ctx, app, label) { p, l -> blocked(p, l) }
        when (verdict) {
            FrameGate.Verdict.PRIVATE -> {
                throttle.looked(now, false)
                latest = null; lastGrid = null
                ctx?.screenshot?.takeIf { it.isNotBlank() }?.let { runCatching { File(it).delete() } }
                if (seeing != Seeing.PRIVATE) announce("[The user's screen is private right now (a banking or money app, or a protected window). You cannot see it. Say so if they ask about it and do not guess.]")
                set(Seeing.PRIVATE)
            }
            FrameGate.Verdict.NOTHING -> { throttle.looked(now, false); ctx?.screenshot?.takeIf { it.isNotBlank() }?.let { runCatching { File(it).delete() } } }
            FrameGate.Verdict.SEND -> {
                val frame = encode(ctx!!.screenshot)
                if (frame == null) { throttle.looked(now, false); return }
                val changed = FrameDiff.differs(lastGrid, frame.grid)
                throttle.looked(now, changed)
                // An unchanged screen is not sent again, unless the user just started speaking.
                if (changed || force) {
                    lastGrid = frame.grid
                    latest = frame.jpeg
                    session()?.takeIf { it.isReady }?.sendImage(frame.base64, "image/jpeg")
                    LiveTimingHook.frameSent()
                }
                set(Seeing.SEEING)
            }
        }
    }

    private fun set(s: Seeing) { if (s != seeing) { seeing = s; onSeeing(s) } }

    private fun blocked(pkg: String, label: String): Boolean =
        ScreenGuard.blocked(pkg, label) || runCatching { Screens.config(context).blocklist.blocked(pkg, label) != null }.getOrDefault(false)

    class Frame(val base64: String, val grid: IntArray, val jpeg: ByteArray)

    @Volatile private var latest: ByteArray? = null

    /** The newest picture Live was given, for a hand-off that needs it; null while the screen is private or nothing was sent. In memory only. */
    fun latestJpeg(): ByteArray? = if (seeing == Seeing.SEEING) latest else null

    companion object {
        private const val TAG = "LiveFrames"
        const val LONG_SIDE = 768
        const val JPEG_QUALITY = 60

        /** Shrinks the picture at [path] to about [LONG_SIDE] px and a small brightness grid for the change check, and deletes the file. */
        fun encode(path: String): Frame? = runCatching {
            val f = File(path)
            val bmp = BitmapFactory.decodeFile(path) ?: return null
            f.delete()
            val scale = LONG_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
            val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
            val out = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            val tiny = Bitmap.createScaledBitmap(small, FrameDiff.SIDE, FrameDiff.SIDE, true)
            val px = IntArray(FrameDiff.SIDE * FrameDiff.SIDE)
            tiny.getPixels(px, 0, FrameDiff.SIDE, 0, 0, FrameDiff.SIDE, FrameDiff.SIDE)
            val grid = IntArray(px.size) { FrameDiff.luma(px[it]) }
            val bytes = out.toByteArray()
            Frame(Base64.encodeToString(bytes, Base64.NO_WRAP), grid, bytes)
        }.getOrNull()
    }
}

/** Counts the frames sent, for the timing log. */
internal object LiveTimingHook {
    @Volatile private var n = 0
    fun frameSent() { n++; if (n == 1 || n % 20 == 0) Log.i("LiveFrames", "frames sent: $n") }
}
