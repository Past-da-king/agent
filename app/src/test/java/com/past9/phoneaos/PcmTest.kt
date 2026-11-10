package com.past9.phoneaos

import com.past9.phoneaos.voice.Pcm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sums behind a call whose sound comes from a page: rates and frames. */
class PcmTest {
    private fun pcm(vararg samples: Int): ByteArray = ByteArray(samples.size * 2).also { b ->
        samples.forEachIndexed { i, v -> b[2 * i] = (v and 0xff).toByte(); b[2 * i + 1] = ((v shr 8) and 0xff).toByte() }
    }
    private fun samples(b: ByteArray) = (0 until b.size / 2).map { Pcm.sample(b, it) }

    @Test fun samplesAreSignedLittleEndian() {
        assertEquals(listOf(0, 1, -1, 32767, -32768, 258), samples(pcm(0, 1, -1, 32767, -32768, 258)))
    }

    @Test fun theSameRateIsUntouched() {
        val p = pcm(5, -7, 900)
        assertArrayEquals(p, Pcm.resample(p, 16000, 16000))
    }

    @Test fun fortyEightToSixteenAveragesEveryThree() {
        val out = Pcm.resample(pcm(3, 6, 9, -30, -30, -30, 100, 200, 300), 48000, 16000)
        assertEquals(listOf(6, -30, 200), samples(out))
    }

    @Test fun aSecondAtAnyRateIsASecondAtSixteen() {
        for (rate in listOf(8000, 22050, 24000, 44100, 48000)) {
            val out = Pcm.resample(ByteArray(rate * 2), rate, 16000)
            assertEquals("from $rate", 16000, out.size / 2)
        }
    }

    @Test fun fortyFourOneKeepsATone() {
        // A 440 Hz tone at 44.1 kHz, half scale: still that loud, and still crossing zero 880 times a second, at 16 kHz.
        val n = 44100
        val src = pcm(*IntArray(n) { (Math.sin(2 * Math.PI * 440 * it / 44100.0) * 16000).toInt() })
        val out = samples(Pcm.resample(src, 44100, 16000))
        assertEquals(16000, out.size)
        assertTrue("peak ${out.max()}", out.max() in 14000..16100)
        val crossings = out.zipWithNext().count { (a, b) -> (a < 0) != (b < 0) }
        assertTrue("crossings $crossings", crossings in 870..890)
    }

    @Test fun goingUpDrawsALineBetweenSamples() {
        val out = samples(Pcm.resample(pcm(0, 100, 200), 8000, 16000))
        assertEquals(listOf(0, 50, 100, 150, 200, 200), out)
    }

    @Test fun aStrayByteIsDropped() {
        assertEquals(2, Pcm.even(byteArrayOf(1, 2, 3)).size)
        assertEquals(listOf(6), samples(Pcm.resample(pcm(3, 6, 9) + byteArrayOf(7), 48000, 16000)))
    }

    @Test fun nothingInNothingOut() {
        assertEquals(0, Pcm.resample(ByteArray(0), 48000, 16000).size)
        assertEquals(0f, Pcm.peak(ByteArray(0)), 0f)
    }

    @Test fun peakIsTheLoudestSample() {
        assertEquals(0.5f, Pcm.peak(pcm(10, -16384, 200)), 0.001f)
        assertEquals(1f, Pcm.peak(pcm(-32768)), 0f)
    }

    @Test fun framesAreExactAndNothingIsLost() {
        val c = Pcm.Chunker(3200)
        val all = ByteArray(10_000) { (it % 251).toByte() }
        val out = ArrayList<ByteArray>()
        // Pieces of awkward lengths, like a page's audio callbacks.
        var at = 0
        for (len in listOf(1364, 1366, 1364, 2730, 3176)) { out += c.push(all.copyOfRange(at, at + len)); at += len }
        assertEquals(10_000, at)
        assertEquals(3, out.size)
        assertTrue(out.all { it.size == 3200 })
        assertEquals(400, c.pending)
        val joined = out.fold(ByteArray(0)) { a, b -> a + b } + c.drain()
        assertArrayEquals(all, joined)
        assertEquals(0, c.pending)
    }

    @Test fun aBigPieceBecomesSeveralFrames() {
        val c = Pcm.Chunker(3200)
        assertEquals(3, c.push(ByteArray(9600)).size)
        assertEquals(0, c.pending)
        assertEquals(0, c.push(ByteArray(0)).size)
    }

    @Test(expected = IllegalArgumentException::class) fun aFrameIsWholeSamples() { Pcm.Chunker(3201) }
}
