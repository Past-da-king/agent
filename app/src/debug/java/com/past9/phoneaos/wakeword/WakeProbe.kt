package com.past9.phoneaos.wakeword

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * DEBUG BUILDS ONLY (src/debug): speaks to the wake word listener on an emulator, which has no microphone to talk into.
 *   adb shell run-as <pkg> cp /data/local/tmp/phrase.wav files/phrase.wav
 *   adb shell am broadcast -n <pkg>/com.past9.phoneaos.wakeword.WakeProbe --es wav phrase.wav
 *     plays that 16 kHz mono 16-bit WAV (from the app's files folder) into the listener in place of the microphone,
 *     at real speed, through the same gate and model. Also feeds "Try it" and "Say it three times" when they are open.
 *   ... --es state 1     logs what the listener is doing and how many times it has woken (tag WakeProbe)
 *   ... --es off 1       gives the microphone back
 */
class WakeProbe : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("wav")?.let { name ->
            val f = File(context.filesDir, name)
            val pcm = runCatching { pcm(f) }.getOrElse { Log.w(TAG, "cannot read $f: ${it.message}"); return }
            val q = WakeEar.testFeed ?: ConcurrentLinkedQueue<ShortArray>().also { WakeEar.testFeed = it }
            var at = 0
            while (at < pcm.size) { val n = minOf(WakeEar.BLOCK, pcm.size - at); q.add(pcm.copyOfRange(at, at + n)); at += n }
            Log.i(TAG, "feeding $name: ${pcm.size / 16000.0} s")
        }
        if (intent.hasExtra("off")) { WakeEar.testFeed = null; Log.i(TAG, "microphone back") }
        if (intent.hasExtra("state")) WakeWord.live.value.let { Log.i(TAG, "state=${it.state} hits=${it.hits} lastHitAt=${it.lastHitAt} phrase=\"${WakeWord.phrase(context)}\" keywords=${WakeWord.keywords(context)}") }
    }

    private fun pcm(f: File): ShortArray {
        val all = f.readBytes(); val b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
        var at = 12
        while (at + 8 <= all.size) {
            val id = String(all, at, 4, Charsets.US_ASCII); val size = b.getInt(at + 4)
            if (id == "data") { val n = minOf(size, all.size - at - 8) / 2; val out = ShortArray(n); b.position(at + 8); b.asShortBuffer().get(out); return out }
            at += 8 + size + (size and 1)
        }
        error("no audio in it")
    }

    private companion object { const val TAG = "WakeProbe" }
}
