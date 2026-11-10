package com.past9.phoneaos.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** The parts of the wake word that need no phone and no model: the phrase, its word pieces, the gate, the detector. */
class WakeCoreTest {
    private val pieces = File("src/main/assets/wakeword/bpe.vocab").inputStream().use { WordPieces.read(it) }

    // ---- the phrase ----

    @Test fun thePhraseFollowsTheAgentsNameUnlessThePersonTypedTheirOwn() {
        assertEquals("Hey Nova", WakePhrase.current("", "Nova"))
        assertEquals("Hey Thandi", WakePhrase.current("  ", " Thandi "))
        assertEquals("Okay computer", WakePhrase.current(" Okay computer ", "Nova"))
        // Before the agent has a name there is still something sayable.
        assertEquals("Hey Agent", WakePhrase.current("", "Your agent"))
        assertEquals("Hey Agent", WakePhrase.current("", ""))
    }

    @Test fun aPhraseBecomesPlainCapitalLetters() {
        assertEquals("HEY NOVA", WakePhrase.latin("Hey, Nova!"))
        assertEquals("HEY JOSE", WakePhrase.latin("hey José"))
        assertEquals("HEY ZOE", WakePhrase.latin("Hey Zoë"))
        assertEquals("HEY BJORN", WakePhrase.latin("Hey Bjørn"))
        assertEquals("HEY STRASSE", WakePhrase.latin("Hey Straße"))
        assertEquals("HEY O'NEIL", WakePhrase.latin("Hey O’Neil"))
        assertEquals("HEY R TWO D TWO", WakePhrase.latin("Hey R2-D2"))
        assertEquals("", WakePhrase.latin("  ?! "))
    }

    @Test fun writingThatIsNotLatinIsRomanisedWhenThePhoneCan() {
        // No romaniser (an old phone): nothing can be spelled, and the sheet says to teach it by voice.
        assertEquals("", WakePhrase.latin("ユキ"))
        assertEquals(WakePhrase.Fit.UNSPELLABLE, WakePhrase.fit(WakePhrase.latin("ユキ")))
        val fake = WakePhrase.Romanizer { it.replace("ユキ", "Yuki").replace("Амира", "Amira") }
        assertEquals("HEY YUKI", WakePhrase.latin("Hey ユキ", fake))
        assertEquals("AMIRA", WakePhrase.latin("Амира", fake))
        // A romaniser that throws must not take the phrase down with it.
        assertEquals("HEY", WakePhrase.latin("Hey ユキ") { error("no") })
    }

    @Test fun shortPhrasesAreCalledOut() {
        assertEquals(3, WakePhrase.syllables("HEY NOVA"))
        assertEquals(2, WakePhrase.syllables("HEY JANE"))
        assertEquals(4, WakePhrase.syllables("HEY HARUTO"))
        assertEquals(WakePhrase.Fit.GOOD, WakePhrase.fit("HEY NOVA"))
        assertEquals(WakePhrase.Fit.SHORT, WakePhrase.fit("HEY JANE"))
        assertEquals(WakePhrase.Fit.SHORT, WakePhrase.fit("NOVA"))
        assertTrue(WakePhrase.advice("Nova", "NOVA").contains("Three or more beats"))
        assertTrue(WakePhrase.advice("ユキ", "").contains("Say it three times"))
    }

    // ---- word pieces ----

    /** The expected splits come from the model's own SentencePiece file (tools/wakeword-eval/make_pieces_fixture.py). */
    @Test fun wordsAreSplitExactlyAsTheModelsOwnTokenizerSplitsThem() {
        val rows = File("src/test/resources/wakeword/pieces.tsv").readLines().filter { it.isNotBlank() }.map { it.split('\t') }
        assertTrue("the fixture is too small to prove anything: ${rows.size}", rows.size >= 300)
        val wrong = rows.filter { pieces.split(it[0]).joinToString(" ") != it[1] }
        assertTrue("${wrong.size} of ${rows.size} split differently, e.g. ${wrong.take(3).map { it[0] + " -> " + pieces.split(it[0]) + " want " + it[1] }}", wrong.isEmpty())
    }

    @Test fun anyNameCanBeSplitAndNothingIsHardcoded() {
        for (name in listOf("NOVA", "THANDI", "YUKI", "KHALID", "XZQJ", "O'NEIL")) {
            val p = pieces.split("HEY $name")
            assertTrue("$name: $p", p.size >= 3 && pieces.known(p))
            assertEquals("HEY$name", p.joinToString("").replace(WordPieces.WORD, ""))
        }
        assertTrue(pieces.split("").isEmpty())
        assertFalse(pieces.known(listOf("▁HE", "not-a-piece")))
    }

    // ---- keyword lines ----

    @Test fun keywordLinesCarryTheTuningAndDropWhatIsTooShort() {
        val t = WakeKeywords.Tuning(1.5f, 0.25f, 0.4f)
        val typed = listOf("▁HE", "Y", "▁NO", "V", "A")
        val lines = WakeKeywords.lines(typed, listOf(listOf("▁HA", "Y", "▁NO", "V", "A"), typed, listOf("▁NO")), t)
        assertEquals(listOf("▁HE Y ▁NO V A :1.5 #0.25 @WAKE", "▁HA Y ▁NO V A :1.5 #0.4 @WAKE"), lines)
        // A phrase of one or two pieces is not listened for at all: it would fire on ordinary talk.
        assertTrue(WakeKeywords.lines(listOf("▁NO", "V"), emptyList(), t).isEmpty())
        // Nothing typed that can be spelled, but taught by voice: the learned spellings stand alone.
        assertEquals(1, WakeKeywords.lines(emptyList(), listOf(listOf("▁YU", "K", "I")), t).size)
    }

    @Test fun theThreeSensitivityStepsAreInOrder() {
        val (low, mid, high) = Sensitivity.entries.map { WakeKeywords.tuning(it) }
        assertTrue(low.threshold > mid.threshold && mid.threshold > high.threshold)
        assertTrue(low.boost <= mid.boost && mid.boost <= high.boost)
        assertEquals(Sensitivity.MEDIUM, Sensitivity.of(null)); assertEquals(Sensitivity.MEDIUM, Sensitivity.of("nonsense")); assertEquals(Sensitivity.HIGH, Sensitivity.of("HIGH"))
    }

    @Test fun learnedSpellingsAreKeptSmallAndClean() {
        val a = listOf("▁HE", "Y", "▁TA", "ND", "Y"); val b = listOf("▁HE", "Y", "▁T", "AN", "DI")
        val merged = Learned.merge(emptyList(), listOf(a, b, a, listOf("▁HE"), listOf("▁HE", "Y", "bogus")), pieces)
        assertEquals(listOf(a, b), merged)
        assertEquals(merged, Learned.decode(Learned.encode(merged)))
        val many = (1..9).map { listOf("▁HE", "Y") + List(it) { "A" } }
        assertEquals(Learned.MOST, Learned.merge(emptyList(), many, pieces).size)
        assertEquals(listOf("▁HE", "Y", "▁TA", "ND", "Y"), SpellingLearner.pieces(arrayOf(" HE", "Y", " TA", "ND", "Y", " ")))
    }

    // ---- the gate ----

    private fun tone(frames: Int, amp: Double, hz: Double = 220.0) = ShortArray(frames * SpeechGate.FRAME) { (sin(2 * PI * hz * it / 16000) * amp * (0.6 + 0.4 * sin(2 * PI * 4 * it / 16000))).toInt().toShort() }
    private fun hiss(frames: Int, amp: Int, seed: Int = 1): ShortArray { val r = java.util.Random(seed.toLong()); return ShortArray(frames * SpeechGate.FRAME) { (r.nextGaussian() * amp).toInt().toShort() } }
    private fun openFrames(g: SpeechGate, pcm: ShortArray): Int { var n = 0; for (f in 0 until pcm.size / SpeechGate.FRAME) if (g.frame(pcm, f * SpeechGate.FRAME)) n++; return n }

    @Test fun theGateStaysShutInAQuietRoomAndOpensForAVoice() {
        val g = SpeechGate()
        assertEquals("a quiet room must never open the gate", 0, openFrames(g, hiss(500, 20)))
        assertTrue("a voice must open it", openFrames(g, tone(50, 6000.0)) >= 45)
        // It holds for a moment after the voice stops, then shuts.
        val after = openFrames(g, hiss(200, 20))
        assertTrue("held $after frames", after in 30..60)
        assertFalse(g.open)
    }

    @Test fun aSteadyHumBecomesTheRoomAndTheGateShutsAgain() {
        val g = SpeechGate()
        openFrames(g, hiss(100, 20))
        val hum = hiss(3000, 1500, seed = 3)                 // a minute of loud, steady noise
        val first = openFrames(g, hum.copyOfRange(0, 1500 * SpeechGate.FRAME))
        val later = openFrames(g, hum.copyOfRange(1500 * SpeechGate.FRAME, hum.size))
        assertTrue("the hum should stop holding the gate open: later=$later", later < 75)
        assertTrue(first >= later)
        // And a voice over the hum still opens it.
        val both = tone(50, 12000.0).also { v -> val h = hiss(50, 1500, 4); for (i in v.indices) v[i] = (v[i] + h[i]).coerceIn(-32768, 32767).toShort() }
        assertTrue(openFrames(g, both) >= 40)
    }

    @Test fun digitalSilenceIsHandled() {
        val g = SpeechGate()
        assertEquals(0, openFrames(g, ShortArray(100 * SpeechGate.FRAME)))
        assertEquals(-90f, SpeechGate.levelDb(ShortArray(320), 0, 320), 0.01f)
    }

    // ---- the detector ----

    /** Stands in for the model: "hears" the phrase when it has been fed [after] samples since the last reset. */
    private class FakeEngine(private val after: Int) : KeywordEngine {
        var fed = 0; var total = 0; var resets = 0; var first: FloatArray? = null
        override fun listenFor(lines: List<String>) = true
        override fun feed(samples: FloatArray): Boolean { if (first == null) first = samples; fed += samples.size; total += samples.size; return fed >= after }
        override fun reset() { fed = 0; resets++ }
        override fun close() {}
    }

    @Test fun theModelOnlyRunsWhileTheGateIsOpen() {
        val e = FakeEngine(Int.MAX_VALUE); val d = WakeDetector(e)
        d.feed(hiss(500, 20))
        assertEquals("ten seconds of quiet must cost the model nothing", 0, e.total)
        assertEquals(500, d.frames); assertEquals(0, d.modelFrames)
        d.feed(tone(50, 6000.0))
        assertTrue(d.modelFrames in 45..50)
        // The model also got the half second from before the gate opened, so the start of the word is not lost.
        assertTrue("pre-roll: ${e.first?.size}", (e.first?.size ?: 0) in 4000..WakeDetector.PRE_ROLL)
        d.feed(hiss(200, 20))
        assertTrue("when the gate shuts the model is told to forget", e.resets >= 1)
    }

    @Test fun oneSayingWakesOnceAndAudioMayArriveInAnyBlockSize() {
        val e = FakeEngine(after = 12000); val d = WakeDetector(e)
        d.feed(hiss(100, 20))
        val voice = tone(120, 6000.0)                       // the phrase and a second and a half of talk after it
        var hits = 0; var at = 0
        for (block in generateSequence(137) { (it * 7 + 13) % 2000 + 1 }) { val n = minOf(block, voice.size - at); if (n <= 0) break; if (d.feed(voice.copyOfRange(at, at + n), n)) hits++; at += n }
        assertEquals("one saying, one wake (then deaf for two seconds)", 1, hits)
        d.feed(hiss(200, 20))
        assertTrue(d.feed(tone(100, 6000.0)))               // and it hears the next one
    }
}
