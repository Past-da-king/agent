package com.past9.phoneaos.voice

/** Plain sums on 16-bit little-endian mono sound, for audio that does not come from this phone's own microphone. */
object Pcm {
    fun sample(pcm: ByteArray, i: Int): Int = ((pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)).toShort().toInt()

    private fun put(out: ByteArray, i: Int, v: Int) {
        val c = v.coerceIn(-32768, 32767)
        out[2 * i] = (c and 0xff).toByte(); out[2 * i + 1] = ((c shr 8) and 0xff).toByte()
    }

    /** Whole samples only: a stray last byte is dropped. */
    fun even(pcm: ByteArray): ByteArray = if (pcm.size % 2 == 0) pcm else pcm.copyOf(pcm.size - 1)

    /**
     * [pcm] at [from] Hz as the same sound at [to] Hz. Going down, each new sample is the average of the old ones it
     * covers (which also keeps the hiss of the dropped samples out); going up, a straight line between neighbours.
     */
    fun resample(pcm: ByteArray, from: Int, to: Int): ByteArray {
        require(from > 0 && to > 0) { "rates must be positive" }
        val src = even(pcm)
        val n = src.size / 2
        if (from == to || n == 0) return src
        val m = (n.toLong() * to / from).toInt()
        val out = ByteArray(m * 2)
        if (to < from) {
            for (i in 0 until m) {
                val a = (i.toLong() * from / to).toInt()
                val b = (((i + 1).toLong() * from / to).toInt()).coerceIn(a + 1, n)
                var sum = 0L
                for (k in a until b) sum += sample(src, k)
                put(out, i, Math.round(sum.toDouble() / (b - a)).toInt())
            }
        } else {
            for (i in 0 until m) {
                val pos = i.toDouble() * from / to
                val a = pos.toInt().coerceAtMost(n - 1)
                val b = (a + 1).coerceAtMost(n - 1)
                val f = pos - a
                put(out, i, Math.round(sample(src, a) * (1 - f) + sample(src, b) * f).toInt())
            }
        }
        return out
    }

    /** The loudest sample, 0..1. */
    fun peak(pcm: ByteArray): Float {
        var p = 0
        for (i in 0 until pcm.size / 2) { val v = Math.abs(sample(pcm, i)); if (v > p) p = v }
        return (p / 32768f).coerceIn(0f, 1f)
    }

    /**
     * Cuts a stream that arrives in pieces of any length into frames of exactly [frameBytes], in order, losing
     * nothing: what does not fill a frame waits for the next piece. Not thread safe; one caller.
     */
    class Chunker(private val frameBytes: Int) {
        init { require(frameBytes > 0 && frameBytes % 2 == 0) { "a frame is a whole number of samples" } }
        private var held = ByteArray(0)

        fun push(pcm: ByteArray): List<ByteArray> {
            if (pcm.isEmpty()) return emptyList()
            val all = if (held.isEmpty()) pcm else held + pcm
            val frames = all.size / frameBytes
            val out = ArrayList<ByteArray>(frames)
            for (i in 0 until frames) out += all.copyOfRange(i * frameBytes, (i + 1) * frameBytes)
            held = all.copyOfRange(frames * frameBytes, all.size)
            return out
        }

        /** What is still waiting (shorter than a frame), handed over and forgotten. */
        fun drain(): ByteArray { val h = even(held); held = ByteArray(0); return h }
        val pending: Int get() = held.size
    }
}
