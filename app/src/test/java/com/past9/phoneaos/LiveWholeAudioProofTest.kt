package com.past9.phoneaos

import com.past9.phoneaos.voice.DictationTape
import com.past9.phoneaos.voice.Quiet
import com.past9.phoneaos.voice.SpeechModel
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Real-API proof, off by default: WA_LIVE_DIR=<dir with a key file and 16 kHz mono *.wav clips> runs the real Kotlin path
 * on a long take once as before (pieces of at most 120 s, texts joined) and once now (one request over the whole file).
 * The key is read from the file and never printed.
 */
@RunWith(RobolectricTestRunner::class)
class LiveWholeAudioProofTest {
    private val names = listOf("Strat Edge", "SEI", "Strat CMS", "Strat Edge ID", "LondaCorp", "Mthashana", "Meqry", "Impetix", "Sinqobile Shoba", "Phumzile Motau", "Nkululeko", "Bongani", "Guavas", "Ben van Rooyen", "Creek", "GrowOS", "Cosmiprint", "Hina", "Ayanda")

    @Test fun oldPiecesAgainstOneWholePass() {
        val dir = System.getenv("WA_LIVE_DIR")?.let { File(it) }
        assumeTrue(dir != null && File(dir, "key").exists())
        dir!!
        val key = File(dir, "key").readText().trim()
        val tape = DictationTape(File(dir, "proof-tape.wav"))
        val order = System.getenv("WA_CLIPS")?.split(",") ?: listOf("take188", "msg2128", "zulu", "take188", "zulu", "msg2128", "take188")
        val gap = ByteArray(DictationTape.BYTES_PER_SECOND.toInt() * 3 / 2)
        for (c in order) {
            val b = File(dir, "$c.wav").readBytes()
            var at = 44
            while (at < b.size) { val n = minOf(DictationTape.BLOCK, b.size - at); tape.append(b.copyOfRange(at, at + n), n); at += n }
            tape.append(gap, gap.size)
        }
        val seconds = DictationTape.ms(tape.bytes) / 1000
        val pieces = Quiet.split(tape.levels(), 0, tape.bytes, maxMs = 120_000L)
        val before = pieces.joinToString(" ") { p -> SpeechModel.transcribe(key, tape.read(p.first, p.last + 1), names) }.trim()
        val after = SpeechModel.transcribe(key, tape.read(0, tape.bytes), names)
        File(dir, "proof-before.txt").writeText("seconds=$seconds pieces=${pieces.size}\n$before\n")
        File(dir, "proof-after.txt").writeText("seconds=$seconds requests=1\n$after\n")
    }
}
