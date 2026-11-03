package com.past9.phoneaos.voice

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * A dictation's audio as it is captured: 16 kHz mono PCM16 written straight to a WAV file, with the header patched as
 * it grows, so whatever happens to the network, the recogniser or the app, every word he said is on the phone.
 * Also keeps the loudness of every 100 ms block, for finding speech and quiet places to cut.
 * Thread-safe: the mic thread appends, the engine reads ranges.
 */
class DictationTape(val file: File) : Closeable {
    private val raf: RandomAccessFile
    @Volatile var bytes: Long = 0; private set
    private val levels = ArrayList<Float>()
    private var accSquares = 0.0
    private var accSamples = 0
    private var lastPatch = 0L
    private var closed = false

    init {
        file.parentFile?.mkdirs()
        raf = RandomAccessFile(file, "rw")
        raf.setLength(0)
        raf.write(header(0))
    }

    @Synchronized fun append(pcm: ByteArray, n: Int = pcm.size) {
        if (closed || isSealed || n <= 0) return
        val len = n - (n % 2)
        raf.seek(HEADER + bytes)
        raf.write(pcm, 0, len)
        bytes += len
        var i = 0
        while (i + 1 < len) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toInt()
            accSquares += s.toDouble() * s
            accSamples++
            if (accSamples == BLOCK / 2) {
                levels.add(Math.sqrt(accSquares / accSamples).toFloat() / 32768f)
                accSquares = 0.0; accSamples = 0
            }
            i += 2
        }
        // A WAV that is valid at every moment: the sizes are written again about once a second.
        if (bytes - lastPatch >= BYTES_PER_SECOND) patch()
    }

    /** The audio between two byte offsets (clamped to what is there). */
    @Synchronized fun read(from: Long, to: Long): ByteArray {
        val a = from.coerceIn(0, bytes); val b = to.coerceIn(a, bytes)
        val out = ByteArray((b - a).toInt())
        if (out.isEmpty()) return out
        raf.seek(HEADER + a)
        raf.readFully(out)
        return out
    }

    /** Loudness 0..1 of every whole 100 ms block so far. */
    @Synchronized fun levels(): FloatArray = levels.toFloatArray()

    @Synchronized private fun patch() {
        val h = header(bytes)
        raf.seek(0); raf.write(h)
        lastPatch = bytes
    }

    /** No more audio: the header is final, and ranges can still be read (for Retry). */
    @Synchronized fun seal() { if (!closed && !isSealed) { runCatching { patch() }; isSealed = true } }
    @Volatile private var isSealed = false

    @Synchronized override fun close() {
        if (closed) return
        runCatching { patch() }
        closed = true
        runCatching { raf.close() }
    }

    companion object {
        const val RATE = 16_000
        const val BYTES_PER_SECOND = RATE * 2
        /** One loudness block: 100 ms. */
        const val BLOCK = BYTES_PER_SECOND / 10
        const val HEADER = 44L

        fun ms(bytes: Long): Long = bytes * 1000 / BYTES_PER_SECOND
        fun bytesFor(ms: Long): Long = (ms * BYTES_PER_SECOND / 1000) and 1L.inv()

        fun header(dataBytes: Long): ByteArray {
            val b = java.nio.ByteBuffer.allocate(HEADER.toInt()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()); b.putInt((36 + dataBytes).toInt()); b.put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(RATE); b.putInt(BYTES_PER_SECOND); b.putShort(2); b.putShort(16)
            b.put("data".toByteArray()); b.putInt(dataBytes.toInt())
            return b.array()
        }

        /** Keep the newest [keep] tapes in [dir]; older ones go. */
        fun prune(dir: File, keep: Int = 20) {
            val all = dir.listFiles { f -> f.name.endsWith(".wav") }?.sortedByDescending { it.lastModified() } ?: return
            all.drop(keep).forEach { runCatching { it.delete() } }
        }
    }
}

/** Where to cut audio, from its 100 ms loudness blocks. Pure, so it is tested with no phone. */
object Quiet {
    private const val BLOCK = DictationTape.BLOCK.toLong()

    /** The noise floor: the 15th percentile of the blocks so far (silence on this mic, in this place). */
    fun floor(levels: FloatArray): Float {
        if (levels.isEmpty()) return 0f
        val s = levels.sorted()
        return s[(s.size * 0.15).toInt().coerceAtMost(s.size - 1)]
    }

    // Three times the noise floor, kept between 0.012 and 0.04: when he starts talking at once there is no silence yet and the "floor" is his voice.
    private fun speechLevel(levels: FloatArray): Float = (floor(levels) * 3f).coerceIn(0.012f, 0.04f)

    /** Whether he said anything between two byte offsets: at least 300 ms of blocks well above the noise floor. */
    fun hasSpeech(levels: FloatArray, from: Long, to: Long): Boolean {
        val t = speechLevel(levels)
        val a = (from / BLOCK).toInt().coerceAtLeast(0); val b = (to / BLOCK).toInt().coerceAtMost(levels.size)
        var n = 0
        for (i in a until b) if (levels[i] > t) { n++; if (n >= 3) return true }
        return false
    }

    /**
     * Cut [from, to) into pieces of at most [maxMs], each cut at the quietest 100 ms in the last [searchMs] (10 s) of the piece
     * (the desktop's splitAtQuiet). Offsets are block-aligned bytes.
     */
    fun split(levels: FloatArray, from: Long, to: Long, maxMs: Long = 30_000, searchMs: Long = 10_000): List<LongRange> {
        val max = DictationTape.bytesFor(maxMs); val search = DictationTape.bytesFor(searchMs)
        val out = ArrayList<LongRange>()
        var cur = from
        while (to - cur > max) {
            val lo = ((cur + max - search) / BLOCK).toInt(); val hi = ((cur + max) / BLOCK).toInt()
            var best = hi; var bestLevel = Float.MAX_VALUE
            for (i in lo until hi) { val l = levels.getOrElse(i) { Float.MAX_VALUE }; if (l < bestLevel) { bestLevel = l; best = i } }
            val cut = (best.toLong() * BLOCK).coerceIn(cur + BLOCK, cur + max)
            out.add(cur until cut); cur = cut
        }
        if (to > cur) out.add(cur until to)
        return out
    }

    /**
     * Pieces mode (no live recogniser): where to close the piece that started at [from], with [to] captured so far.
     * After [minMs], at a pause of at least 400 ms (cut in its middle); at [maxMs] at the quietest place of the last 10 s.
     * Null: keep going.
     */
    fun nextCut(levels: FloatArray, from: Long, to: Long, minMs: Long = 12_000, maxMs: Long = 30_000): Long? {
        val span = to - from
        if (span < DictationTape.bytesFor(minMs)) return null
        val quiet = maxOf(0.006f, floor(levels) * 1.6f)
        val a = ((from + DictationTape.bytesFor(minMs - 4_000)) / BLOCK).toInt(); val b = (to / BLOCK).toInt().coerceAtMost(levels.size)
        var run = 0
        for (i in (b - 1) downTo a) {
            if (levels[i] <= quiet) { run++; if (run >= 4) return (i + run / 2).toLong() * BLOCK } else run = 0
        }
        if (span >= DictationTape.bytesFor(maxMs)) return split(levels, from, to, maxMs).first().last + 1
        return null
    }
}
