package com.past9.phoneaos.wakeword

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The cheap check in front of the keyword model: is anybody making a sound that could be speech?
 *
 * The model costs real processor time; adding up the loudness of 20 ms of audio costs almost nothing. So the model
 * only runs while this gate is open. The gate follows the room's own noise floor (it drops to a quieter level at once
 * and creeps up slowly), opens when the sound rises clearly above that floor, and stays open for a short while after
 * the last loud frame so the end of a phrase is not cut off. A steady hum (a fan, a fridge, an engine) becomes the
 * floor within seconds and closes the gate again.
 */
class SpeechGate(
    /** How far above the room a frame must be to count as sound worth checking, in decibels. */
    private val riseDb: Float = 8f,
    /** Nothing quieter than this ever opens the gate (dB below full scale): the hiss of a silent microphone. */
    private val quietestDb: Float = -55f,
    /** Frames of sound, out of the last five, that open the gate. */
    private val openAfter: Int = 2,
    /** How long the gate stays open after the last frame of sound. */
    private val holdFrames: Int = 40,
    /** How fast the floor creeps up towards a steady noise, in decibels per frame (0.02 is 1 dB a second). */
    private val floorRise: Float = 0.02f,
) {
    var open = false; private set
    /** The room's level right now, dB below full scale. */
    var floorDb = -60f; private set
    private var recent = 0          // the last five frames, one bit each
    private var quietFor = 0

    /** Take one frame ([FRAME] samples of 16 kHz audio, 20 ms) and say whether the gate is open after it. */
    fun frame(pcm: ShortArray, from: Int = 0, n: Int = FRAME): Boolean {
        val level = levelDb(pcm, from, n)
        floorDb = if (level < floorDb) max(level, -90f) * 0.5f + floorDb * 0.5f else min(floorDb + floorRise, level)
        val sound = level > max(floorDb + riseDb, quietestDb)
        recent = ((recent shl 1) or (if (sound) 1 else 0)) and 0x1f
        if (sound) quietFor = 0 else quietFor++
        open = if (open) quietFor < holdFrames else Integer.bitCount(recent) >= openAfter
        return open
    }

    fun reset() { open = false; recent = 0; quietFor = 0; floorDb = -60f }

    companion object {
        /** 20 ms at 16 kHz. */
        const val FRAME = 320

        fun levelDb(pcm: ShortArray, from: Int, n: Int): Float {
            if (n <= 0) return -90f
            var sum = 0.0
            for (i in from until from + n) { val v = pcm[i].toDouble(); sum += v * v }
            val rms = sqrt(sum / n) / 32768.0
            return if (rms <= 0.0) -90f else max(-90f, (20 * log10(rms)).toFloat())
        }
    }
}
