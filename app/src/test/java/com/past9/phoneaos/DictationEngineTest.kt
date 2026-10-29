package com.past9.phoneaos

import com.past9.phoneaos.voice.DictationEngine
import com.past9.phoneaos.voice.DictationTape
import com.past9.phoneaos.voice.DictationView
import com.past9.phoneaos.voice.LiveError
import com.past9.phoneaos.voice.LiveEvents
import com.past9.phoneaos.voice.LiveWords
import com.past9.phoneaos.voice.Quiet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * Phone dictation that only ends when he taps stop (29 Oct 2026: it cut off mid-dictation on mobile data).
 *
 * The audio is synthetic and carries its own words: the first 100 ms block of every spoken second holds a marker
 * sample (1000 + the second), so a fake recogniser and a fake transcriber "hear" the word "w<second>". If the engine
 * leaves a gap anywhere a word is missing; if it overlaps anything a word appears twice. Every test asserts the box
 * holds every word exactly once, in order. Fake clock (virtual time), fake network drops.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DictationEngineTest {
    private val dir: File = Files.createTempDirectory("dict").toFile()
    private val log = ArrayList<String>()

    // ---- the synthetic speech ----

    /** One 100 ms block of second [s], block [b] (0..9). Loud when [speaking]. */
    private fun block(s: Int, b: Int, speaking: Boolean): ByteArray {
        val buf = ByteBuffer.allocate(DictationTape.BLOCK).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until DictationTape.BLOCK / 2) {
            val v = when {
                !speaking -> 0
                b == 0 -> 1000 + s
                else -> if (i % 2 == 0) 3000 else -3000
            }
            buf.putShort(v.toShort())
        }
        return buf.array()
    }

    /** The words in some audio: one per spoken second whose first block is in it. */
    private fun words(pcm: ByteArray): List<String> {
        val out = ArrayList<String>()
        var i = 0
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        while (i + DictationTape.BLOCK <= pcm.size) {
            val first = bb.getShort(i).toInt(); val second = bb.getShort(i + 2).toInt()
            if (first in 1000..1999 && second == first) out.add("w${first - 1000}")
            i += DictationTape.BLOCK
        }
        return out
    }

    /** Speech pattern: [talk] s of words then [pause] s of quiet, over and over. */
    private fun speaking(s: Int, talk: Int = 8, pause: Int = 3) = s % (talk + pause) < talk

    private fun expected(seconds: Int, talk: Int = 8, pause: Int = 3) = (0 until seconds).filter { speaking(it, talk, pause) }.map { "w$it" }

    // ---- fakes ----

    /** A recogniser like Google's: partials as he talks, a result ~0.5 s after 2.5 s of quiet, NO_SPEECH after 5 s of nothing. */
    inner class FakeLive(private val scope: CoroutineScope) : LiveWords {
        var online = true
        var reading = true
        /** Never answers end(): its last words never come. */
        var hangOnEnd = false
        var stretches = 0
        private var ev: LiveEvents? = null
        private var heard = ArrayList<String>()
        private var quiet = 0
        private var spoke = false
        private var ended = false
        private var fedBytes = 0L

        override fun begin(events: LiveEvents): Boolean {
            ev = events; heard = ArrayList(); quiet = 0; spoke = false; ended = false; fedBytes = 0; stretches++
            if (!online) { val e = events; scope.launch { delay(300); if (ev === e) { ev = null; e.error(LiveError.NETWORK) } } }
            return true
        }

        override fun feed(pcm: ByteArray) {
            fedBytes += pcm.size
            val e = ev ?: return
            if (!reading || !online || ended) return
            var i = 0
            while (i + DictationTape.BLOCK <= pcm.size) {
                val b = pcm.copyOfRange(i, i + DictationTape.BLOCK)
                val w = words(b)
                val loud = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getShort(4).toInt() != 0
                if (w.isNotEmpty()) heard.addAll(w)
                if (loud) { spoke = true; quiet = 0; if (w.isNotEmpty()) e.partial(heard.joinToString(" ")) } else quiet++
                if (spoke && quiet >= 25) {
                    ended = true; e.endOfSpeech()
                    val text = heard.joinToString(" ")
                    scope.launch { delay(500); if (ev === e) { ev = null; e.result(text) } }
                    return
                }
                if (!spoke && quiet >= 50) { ended = true; scope.launch { delay(50); if (ev === e) { ev = null; e.error(LiveError.NO_SPEECH) } }; return }
                i += DictationTape.BLOCK
            }
        }

        override fun backlog(): Long = if (reading) 0 else fedBytes

        override fun end() {
            val e = ev ?: return
            if (hangOnEnd) return
            ended = true
            val text = heard.joinToString(" ")
            scope.launch { delay(300); if (ev === e) { ev = null; if (online) e.result(text) else e.error(LiveError.NETWORK) } }
        }

        override fun cancel() { ev = null }

        /** The network drops mid-stretch. */
        fun drop() { online = false; val e = ev ?: return; ev = null; scope.launch { delay(200); e.error(LiveError.NETWORK) } }
    }

    inner class FakePc {
        var online = true
        var calls = 0
        val transcribe: suspend (ByteArray) -> String = { pcm ->
            calls++
            delay(800)
            if (!online) throw java.io.IOException("Not connected.")
            words(pcm).joinToString(" ")
        }
    }

    private class Run(val engine: DictationEngine, val tape: DictationTape, val views: ArrayList<DictationView>)

    private fun TestScope.engine(live: LiveWords?, pc: FakePc?): Run {
        val tape = DictationTape(File(dir, "t-${System.nanoTime()}.wav"))
        val views = ArrayList<DictationView>()
        val e = DictationEngine(backgroundScope, { testScheduler.currentTime }, tape, live, pc?.transcribe, { views.add(it) }, { log.add(it) })
        e.start()
        return Run(e, tape, views)
    }

    /** Speak second by second (100 ms blocks, real time on the fake clock); [at] runs before each second. */
    private fun TestScope.speak(r: Run, from: Int, to: Int, talk: Int = 8, pause: Int = 3, at: (Int) -> Unit = {}) {
        for (s in from until to) {
            at(s)
            for (b in 0 until 10) {
                val blk = block(s, b, speaking(s, talk, pause))
                r.tape.append(blk); r.engine.audio(blk)
                advanceTimeBy(100); runCurrent()
            }
        }
    }

    private fun TestScope.untilEnded(r: Run, maxMs: Long = 200_000) {
        var t = 0L
        while (!r.engine.view().ended && t < maxMs) { advanceTimeBy(500); runCurrent(); t += 500 }
    }

    private fun boxWords(r: Run) = r.engine.text().split(" ").filter { it.isNotBlank() }

    // ---- the tests ----

    @Test fun twoMinutesWithANetworkDropInTheMiddleKeepsEveryWord() = runTest {
        val live = FakeLive(backgroundScope); val pc = FakePc()
        val r = engine(live, pc)
        speak(r, 0, 120) { s ->
            if (s == 50) { live.drop(); pc.online = false }   // svc data disable
            if (s == 60) { live.online = true; pc.online = true; r.engine.networkBack() }   // and back ~10 s later
        }
        assertFalse("it must still be listening at 2:00", r.engine.view().ended)
        assertTrue("the drop showed Reconnecting", r.views.any { it.phase == DictationView.Phase.RECONNECTING })
        r.engine.finish()
        untilEnded(r)
        assertEquals(DictationView.Phase.DONE, r.engine.view().phase)
        assertEquals(expected(120), boxWords(r))
        assertTrue("the missed stretch was written down from the saved audio", pc.calls > 0)
    }

    @Test fun silenceNeverStopsIt() = runTest {
        val live = FakeLive(backgroundScope); val pc = FakePc()
        val r = engine(live, pc)
        speak(r, 0, 10, talk = 5, pause = 100)  // 5 s of words, then quiet
        speak(r, 10, 130, talk = 0, pause = 1)    // two minutes of nothing
        assertFalse("two minutes of silence must not end it", r.engine.view().ended)
        assertEquals(DictationView.Phase.LISTENING, r.engine.view().phase)
        assertTrue("the recogniser was restarted through the quiet", live.stretches > 10)
        r.engine.finish(); untilEnded(r)
        assertEquals(listOf("w0", "w1", "w2", "w3", "w4"), boxWords(r))
    }

    @Test fun onlyTheStopButtonEndsIt() = runTest {
        val live = FakeLive(backgroundScope); val pc = FakePc()
        val r = engine(live, pc)
        speak(r, 0, 300)  // five minutes
        advanceTimeBy(60_000); runCurrent()
        assertFalse(r.views.any { it.ended })
        r.engine.finish(); untilEnded(r)
        assertEquals(expected(300), boxWords(r))
    }

    @Test fun aRecogniserThatWillNotTakeOurAudioHandsOverToPieces() = runTest {
        val live = FakeLive(backgroundScope).apply { reading = false }; val pc = FakePc()
        val r = engine(live, pc)
        speak(r, 0, 90)
        assertFalse(r.engine.view().live)
        assertTrue("words appear while he is still talking", boxWords(r).size > 20)
        r.engine.finish(); untilEnded(r)
        assertEquals(DictationView.Phase.DONE, r.engine.view().phase)
        assertEquals(log.joinToString("; "), expected(90), boxWords(r))
    }

    @Test fun offlineAtStopKeepsTheAudioAndRetryFinishesIt() = runTest {
        val live = FakeLive(backgroundScope); val pc = FakePc()
        val r = engine(live, pc)
        speak(r, 0, 60) { s -> if (s == 40) { live.drop(); pc.online = false } }
        r.engine.finish(); untilEnded(r)
        val v = r.engine.view()
        assertEquals(DictationView.Phase.KEPT, v.phase)
        assertTrue("it says how much it could not write down", v.lostMs >= 15_000)
        assertTrue("what it heard before the drop is in the box", boxWords(r).containsAll(expected(37)))
        assertTrue("the audio is on the phone", r.tape.file.length() > 60L * DictationTape.BYTES_PER_SECOND)
        pc.online = true
        r.engine.retry(); untilEnded(r)
        assertEquals(DictationView.Phase.DONE, r.engine.view().phase)
        assertEquals(expected(60), boxWords(r))
    }

    @Test fun lastWordsThatNeverComeAreWrittenFromTheTape() = runTest {
        val live = FakeLive(backgroundScope).apply { hangOnEnd = true }; val pc = FakePc()
        val r = engine(live, pc)
        speak(r, 0, 20, talk = 20, pause = 1)  // talking right up to the stop
        r.engine.finish(); untilEnded(r, 60_000)
        assertEquals(DictationView.Phase.DONE, r.engine.view().phase)
        assertEquals((0 until 20).map { "w$it" }, boxWords(r))
    }

    @Test fun finishingNeverHangs() = runTest {
        val live = FakeLive(backgroundScope); val pc = FakePc()
        val r = engine(live, pc)
        speak(r, 0, 30) { s -> if (s == 5) { live.drop(); pc.online = false } }
        r.engine.finish()
        untilEnded(r, 200_000)
        assertTrue("it ends by itself within its limit", r.engine.view().ended)
        assertEquals(DictationView.Phase.KEPT, r.engine.view().phase)
    }

    @Test fun anotherAppTakingTheMicPausesAndResumes() = runTest {
        val live = FakeLive(backgroundScope); val pc = FakePc()
        val r = engine(live, pc)
        speak(r, 0, 5)
        r.engine.micSilenced(true)
        assertEquals(DictationView.Phase.PAUSED, r.engine.view().phase)
        r.engine.micSilenced(false)
        assertEquals(DictationView.Phase.LISTENING, r.engine.view().phase)
        assertFalse(r.engine.view().ended)
    }

    @Test fun tapeIsAValidWavWhileRecording() {
        val tape = DictationTape(File(dir, "w.wav"))
        repeat(25) { tape.append(block(it / 10, it % 10, true)) }
        val f = tape.file.readBytes()
        val bb = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(f, 0, 4)); assertEquals("WAVE", String(f, 8, 4))
        assertEquals(16_000, bb.getInt(24))
        assertTrue("the header's size is at most a second behind", bb.getInt(40).toLong() >= tape.bytes - DictationTape.BYTES_PER_SECOND)
        assertEquals(listOf("w1"), words(tape.read(10L * DictationTape.BLOCK, 20L * DictationTape.BLOCK)))
        tape.seal()
        assertEquals(tape.bytes.toInt(), ByteBuffer.wrap(tape.file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
        tape.close()
    }

    @Test fun piecesAreAtMostThirtySecondsAndCutInTheQuiet() {
        val tape = DictationTape(File(dir, "q.wav"))
        for (s in 0 until 95) for (b in 0 until 10) tape.append(block(s, b, speaking(s)))
        val pieces = Quiet.split(tape.levels(), 0, tape.bytes)
        assertTrue(pieces.size >= 4)
        pieces.forEach { assertTrue(it.last + 1 - it.first <= DictationTape.bytesFor(30_000)) }
        // Every cut but the end lands in a pause.
        pieces.dropLast(1).forEach { p -> val sec = ((p.last + 1) / DictationTape.BYTES_PER_SECOND).toInt(); assertFalse("cut at ${sec}s is mid-word", speaking(sec)) }
        assertEquals(expected(95), pieces.flatMap { words(tape.read(it.first, it.last + 1)) })
        tape.close()
    }
}
