package com.past9.phoneaos.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * DEBUG BUILDS ONLY (src/debug): drives a real recording-to-Send flow on an emulator that has no microphone and no speech
 * recogniser.
 *   adb push take.wav /data/local/tmp/take.wav; adb shell run-as <pkg> cp /data/local/tmp/take.wav files/take.wav
 *   adb shell am broadcast -n <pkg>/com.past9.phoneaos.voice.DictationProbe --es wav take.wav --es caption "what the live caption says"
 *     the next mic tap in any message box records THAT take (16 kHz mono 16-bit WAV, at real speed, then quiet) and shows
 *     that caption word by word as the faint live preview, through the real engine, tape, hub and Send.
 *   ... --es off 1     gives the real mic and recogniser back
 */
class DictationProbe : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("wav")?.let { name ->
            val all = runCatching { File(context.filesDir, name).readBytes() }.getOrElse { Log.w(TAG, "cannot read $name: ${it.message}"); return }
            val pcm = all.copyOfRange(44.coerceAtMost(all.size), all.size)
            val caption = intent.getStringExtra("caption").orEmpty()
            DictationSeam.source = Take(pcm, caption.split(" ").filter { it.isNotBlank() })
            Log.i(TAG, "next dictation plays $name: ${pcm.size / 32000.0} s, caption ${caption.length} chars")
        }
        if (intent.hasExtra("off")) { DictationSeam.source = null; Log.i(TAG, "real mic back") }
    }

    private class Take(val pcm: ByteArray, val words: List<String>) : DictationSeam.Source {
        private val main = Handler(Looper.getMainLooper())

        override fun mic(onPcm: (ByteArray, Int) -> Unit): DictationSeam.Mic = object : DictationSeam.Mic {
            private val running = AtomicBoolean(false)
            override fun start() {
                if (running.getAndSet(true)) return
                thread(name = "aos-seam-mic", isDaemon = true) {
                    var at = 0
                    val quiet = ByteArray(DictationTape.BLOCK)
                    while (running.get()) {
                        val n = minOf(DictationTape.BLOCK, pcm.size - at)
                        if (n > 0) { onPcm(pcm.copyOfRange(at, at + n), n); at += n } else onPcm(quiet, quiet.size)
                        Thread.sleep(100)
                    }
                }
            }
            override fun stop() { running.set(false) }
        }

        override fun live(): LiveWords = object : LiveWords {
            private var events: LiveEvents? = null
            private val running = AtomicBoolean(false)
            @Volatile private var shown = 0
            override fun begin(events: LiveEvents): Boolean {
                this.events = events
                running.set(true)
                // The caption grows with the audio: the words so far, in proportion to how much of the take has played.
                thread(name = "aos-seam-live", isDaemon = true) {
                    while (running.get() && shown < words.size) {
                        Thread.sleep(350)
                        val played = fed.toDouble() / maxOf(1, pcm.size)
                        val upTo = minOf(words.size, maxOf(shown, (played * words.size).toInt() + 1))
                        if (upTo != shown) { shown = upTo; val t = words.take(upTo).joinToString(" "); main.post { if (running.get()) events?.partial(t) } }
                    }
                }
                return true
            }
            @Volatile private var fed = 0L
            override fun feed(pcm: ByteArray) { fed += pcm.size }
            override fun backlog(): Long = 0
            override fun end() { running.set(false); val t = words.take(shown).joinToString(" "); main.post { events?.result(t) } }
            override fun cancel() { running.set(false); events = null }
        }
    }

    companion object { private const val TAG = "DictationProbe" }
}
