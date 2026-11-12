package com.past9.phoneaos.wear

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.past9.phoneaos.wearproto.WearPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/** One voice take from the watch's own mic, as 16 kHz mono PCM16 (what the phone's dictation pass takes). */
class MicRecorder {
    class Take(val pcm: ByteArray, val peaks: List<Int>, val error: String? = null)

    @Volatile private var stopRequested = false
    fun requestStop() { stopRequested = true }

    @SuppressLint("MissingPermission")
    suspend fun record(onLevel: (Float) -> Unit): Take = withContext(Dispatchers.IO) {
        stopRequested = false
        val rate = WearPaths.PCM_RATE
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return@withContext Take(ByteArray(0), emptyList(), "The mic gave no buffer.")
        val win = rate * MicPolicy.WINDOW_MS / 1000
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, win * 8))
        }.getOrNull() ?: return@withContext Take(ByteArray(0), emptyList(), "The mic could not open.")
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return@withContext Take(ByteArray(0), emptyList(), "The mic could not open.") }
        val failed = runCatching { rec.startRecording() }.isFailure || rec.recordingState != AudioRecord.RECORDSTATE_RECORDING
        if (failed) { runCatching { rec.release() }; return@withContext Take(ByteArray(0), emptyList(), "The mic did not start.") }
        val out = ByteArrayOutputStream(); val peaks = ArrayList<Int>(); val buf = ShortArray(win)
        var error: String? = null
        try {
            loop@ while (!stopRequested) {
                var n = 0
                while (n < win) {
                    val r = rec.read(buf, n, win - n)
                    if (r <= 0) { error = "The mic stopped."; break@loop }
                    n += r
                }
                var peak = 0
                for (i in 0 until win) { val v = abs(buf[i].toInt()); if (v > peak) peak = v; out.write(buf[i].toInt() and 0xFF); out.write((buf[i].toInt() shr 8) and 0xFF) }
                peaks += peak; onLevel(MicPolicy.level(peak))
                if (MicPolicy.shouldStop(peaks)) break
            }
        } finally { runCatching { rec.stop() }; rec.release() }
        Take(out.toByteArray(), peaks, error)
    }

    companion object {
        /** Cheap checks before opening the mic: the feature, a built-in input and the permission. */
        fun preflight(context: Context): MicPolicy.Verdict {
            val pm = context.packageManager
            val am = context.getSystemService(android.media.AudioManager::class.java)
            val hasInput = runCatching { am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS).any { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC || it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET } }.getOrDefault(true)
            val granted = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            return MicPolicy.preflight(pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_MICROPHONE), hasInput, granted)
        }
    }
}
