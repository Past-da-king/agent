package com.past9.phoneaos.wakeword

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/** Copies the model out of the APK once, so the engine can open it as ordinary files. */
object KeywordModelFiles {
    private const val ASSETS = "wakeword"

    @Synchronized fun ensure(context: Context): KeywordModel {
        val dir = File(context.filesDir, "wakeword/model")
        val model = KeywordModel(dir)
        // Copied once per installed build: an app update may carry a newer model under the same names.
        val build = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString() }.getOrDefault("0")
        val mark = File(dir, "copied-for")
        if (model.complete && runCatching { mark.readText() }.getOrNull() == build) return model
        dir.mkdirs()
        for (name in KeywordModel.NAMES) {
            val out = File(dir, name); val tmp = File(dir, "$name.part")
            context.assets.open("$ASSETS/$name").use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            out.delete(); tmp.renameTo(out)
        }
        mark.writeText(build)
        return model
    }

    fun pieces(context: Context): WordPieces = context.assets.open("$ASSETS/bpe.vocab").use { WordPieces.read(it) }
}

/**
 * The ear: holds the microphone at 16 kHz mono, runs the [WakeDetector] over what it hears and calls [onHit].
 * The always-on service uses one, and so does "Try it" in the settings sheet. Audio is only ever in memory, a
 * tenth of a second at a time; it is not kept, written or sent anywhere.
 */
class WakeEar(private val context: Context, private val tag: String = "WakeEar") {
    /** Called on the ear's own thread each time the phrase is heard. */
    @Volatile var onHit: (() -> Unit)? = null
    /** True after five seconds of nothing but zeros (a microphone Android has not let this listener have), false when sound is back. */
    @Volatile var onSilent: ((Boolean) -> Unit)? = null
    /** The microphone or the model could not start. */
    @Volatile var onFailed: ((String) -> Unit)? = null
    /** Whether the gate is open right now (somebody is making sound), for the live indicator. */
    @Volatile var hearing = false; private set
    /** Android's id for this recording, so the service can tell its own recorder from anybody else's. -1 when not recording. */
    @Volatile var sessionId = -1; private set
    @Volatile var frames = 0L; private set
    @Volatile var modelFrames = 0L; private set

    @Volatile private var run = false
    @Volatile private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var lines: List<String> = emptyList()

    val running: Boolean get() = run

    /** Start listening for [keywords] (or carry on, if already listening for exactly those). */
    @Synchronized fun start(keywords: List<String>) {
        if (run && keywords == lines) return
        stop()
        lines = keywords
        run = true
        worker = thread(name = "wake-ear", isDaemon = true) { loop(keywords) }
    }

    /** Let go of the microphone now. Safe from any thread; returns once the recorder is stopped. */
    @Synchronized fun stop() {
        run = false
        runCatching { record?.stop() }
        worker?.let { if (it !== Thread.currentThread()) runCatching { it.join(1500) } }
        worker = null; hearing = false
    }

    @SuppressLint("MissingPermission")
    private fun loop(keywords: List<String>) {
        var engine: SherpaKeywordEngine? = null
        var rec: AudioRecord? = null
        try {
            // Anything that goes wrong bringing the model up (files, the native library, a refused phrase) is the model's failure.
            val ready = runCatching {
                engine = SherpaKeywordEngine(KeywordModelFiles.ensure(context), File(context.filesDir, "wakeword/work-$tag"))
                engine!!.listenFor(keywords)
            }.getOrElse { Log.w(TAG, "the model did not start: $it"); false }
            if (!ready) { if (run) onFailed?.invoke("model"); return }
            val detector = WakeDetector(engine!!)
            rec = open()
            record = rec
            sessionId = rec?.audioSessionId ?: -1
            if (rec == null && testFeed == null) { if (run) onFailed?.invoke("microphone"); return }
            val buf = ShortArray(BLOCK)
            var zeros = 0; var silent = false
            while (run) {
                var n = rec?.read(buf, 0, BLOCK) ?: run { Thread.sleep(100); buf.fill(0); BLOCK }
                if (n < 0) { if (run) onFailed?.invoke("microphone"); break }
                if (n == 0) continue
                // Debug builds on an emulator have no voice to hear: WakeProbe queues audio here and it replaces the microphone's.
                val fed = testFeed?.poll()
                if (fed != null) { n = minOf(fed.size, BLOCK); System.arraycopy(fed, 0, buf, 0, n) }
                var quiet = true
                for (i in 0 until n) if (buf[i].toInt() != 0) { quiet = false; break }
                zeros = if (quiet) zeros + 1 else 0
                if (zeros == SILENT_BLOCKS && !silent) { silent = true; onSilent?.invoke(true) }
                if (!quiet && silent) { silent = false; onSilent?.invoke(false) }
                val hit = detector.feed(buf, n)
                hearing = detector.gateOpen; frames = detector.frames; modelFrames = detector.modelFrames
                if (hit && run) onHit?.invoke()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "the ear stopped: $t")
            if (run) onFailed?.invoke("microphone")
        } finally {
            runCatching { rec?.stop() }; runCatching { rec?.release() }
            record = null; sessionId = -1; hearing = false
            runCatching { engine?.close() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(): AudioRecord? = runCatching {
        val min = AudioRecord.getMinBufferSize(WakeDetector.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // VOICE_RECOGNITION: the phone's own microphone tuned for speech, without the call processing that eats far voices.
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeDetector.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, BLOCK * 8))
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return null }
        r.startRecording()
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { r.release(); return null }
        r
    }.getOrNull()

    companion object {
        private const val TAG = "WakeEar"
        /** A tenth of a second. */
        const val BLOCK = 1600
        private const val SILENT_BLOCKS = 50

        /** Debug builds only set this (src/debug WakeProbe): blocks of audio that stand in for the microphone. */
        @Volatile var testFeed: ConcurrentLinkedQueue<ShortArray>? = null

        /**
         * Hear one saying, for "Say it three times": waits for a voice, takes it until it stops (or [maxMs]), and
         * returns it. Null when nobody spoke or the microphone would not open. The audio lives only in the returned array.
         */
        @SuppressLint("MissingPermission")
        fun hearOnce(maxMs: Int = 6000, keepGoing: () -> Boolean = { true }): ShortArray? {
            val min = AudioRecord.getMinBufferSize(WakeDetector.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeDetector.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, BLOCK * 8))
                    .also { if (it.state == AudioRecord.STATE_INITIALIZED) it.startRecording() }
            }.getOrNull()?.takeIf { it.recordingState == AudioRecord.RECORDSTATE_RECORDING }
            if (rec == null && testFeed == null) return null
            try {
                val gate = SpeechGate()
                val all = ShortArray(maxMs * 16); var at = 0
                val buf = ShortArray(SpeechGate.FRAME)
                var spoke = false; var firstVoice = -1
                var pending: ShortArray? = null; var pendingAt = 0
                while (keepGoing() && at + SpeechGate.FRAME <= all.size) {
                    val n = rec?.read(buf, 0, SpeechGate.FRAME) ?: run { Thread.sleep(20); buf.fill(0); SpeechGate.FRAME }
                    if (n < SpeechGate.FRAME) { if (n < 0) return null; continue }
                    if (pending == null || pendingAt >= pending.size) { pending = testFeed?.poll(); pendingAt = 0 }
                    pending?.let { p -> val k = minOf(SpeechGate.FRAME, p.size - pendingAt); buf.fill(0); System.arraycopy(p, pendingAt, buf, 0, k); pendingAt += k }
                    System.arraycopy(buf, 0, all, at, SpeechGate.FRAME); at += SpeechGate.FRAME
                    val open = gate.frame(buf)
                    if (open && !spoke) { spoke = true; firstVoice = at }
                    if (spoke && !open) break
                }
                if (!spoke) return null
                // Keep half a second from before the voice, so the first sound of the phrase is whole.
                val from = maxOf(0, firstVoice - WakeDetector.PRE_ROLL)
                return all.copyOfRange(from, at)
            } finally { runCatching { rec?.stop() }; runCatching { rec?.release() } }
        }
    }
}
