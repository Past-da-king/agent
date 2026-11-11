package com.past9.phoneaos.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * A very short, soft two-note "I'm listening" sound, made in code (no file, nothing recorded) and played on the
 * phone's notification-type volume. It stays quiet when the phone is on silent or vibrate.
 */
object Earcon {
    private const val RATE = 24_000

    /** Plays it unless the phone is silenced. Never throws. */
    fun play(context: Context) {
        runCatching {
            val am = context.getSystemService(AudioManager::class.java) ?: return
            if (!shouldPlay(am.ringerMode)) return
            val pcm = samples()
            val bytes = ByteArray(pcm.size * 2)
            pcm.forEachIndexed { i, s -> bytes[i * 2] = (s.toInt() and 0xff).toByte(); bytes[i * 2 + 1] = (s.toInt() shr 8).toByte() }
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(bytes.size).setTransferMode(AudioTrack.MODE_STATIC).build()
            track.write(bytes, 0, bytes.size)
            track.setNotificationMarkerPosition(pcm.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) { runCatching { t.release() } }
                override fun onPeriodicNotification(t: AudioTrack) {}
            })
            track.play()
        }
    }

    /** Only on a phone that is allowed to make sound. */
    fun shouldPlay(ringerMode: Int): Boolean = ringerMode == AudioManager.RINGER_MODE_NORMAL

    /** Two quick notes, a fifth apart, each with a soft attack and release so it never clicks. About 140 ms. */
    fun samples(): ShortArray {
        val notes = listOf(880.0 to 0.07, 1318.5 to 0.07)
        val out = ArrayList<Short>()
        for ((hz, secs) in notes) {
            val n = (RATE * secs).toInt()
            for (i in 0 until n) {
                val edge = minOf(i, n - 1 - i).toDouble() / (RATE * 0.012)
                val env = minOf(1.0, edge)
                out += (sin(2 * PI * hz * i / RATE) * env * 0.22 * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out.toShortArray()
    }
}
