package com.past9.phoneaos.wakeword

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The accuracy run behind the numbers in docs/wake-word.md. It is not a unit test: it needs the engine's Linux library
 * and a folder of test audio, so it skips itself unless both are given:
 *
 *   WAKE_NATIVE_DIR=<folder with libsherpa-onnx-jni.so>  WAKE_EVAL_DIR=<audio folder>  WAKE_EVAL_OUT=<result file>
 *   ./gradlew :app:testDebugUnitTest --tests "com.past9.phoneaos.wakeword.WakeAccuracyRun"
 *
 * It drives the very classes the phone runs ([WakePhrase], [WordPieces], [WakeKeywords], [SherpaKeywordEngine],
 * [SpellingLearner], [WakeDetector] with its [SpeechGate]) over 16 kHz mono WAV files, in the block size the service
 * reads, with the model files from the app's own assets.
 *
 * The audio folder holds:
 *   names.tsv   key <TAB> phrase as a person would type it
 *   clips.tsv   set <TAB> key <TAB> wav       set is pos (the phrase), teach (said to teach it), near (a phrase that
 *                                             sounds close and must NOT wake it)
 *   neg.tsv     kind <TAB> wav                long files with no wake phrase in them (speech, talk, music, noise)
 * WAKE_EVAL_ARGS takes "threads=4;modes=typed,taught;sens=LOW,MEDIUM,HIGH;names=a,b;neg=0|1;tune=MEDIUM:1.5:0.25:0.35".
 */
class WakeAccuracyRun {
    private class Job(val key: String, val phrase: String, val mode: String, val sens: Sensitivity, val tuning: WakeKeywords.Tuning)

    @Test fun run() {
        val dir = System.getenv("WAKE_EVAL_DIR")?.let(::File)
        assumeTrue("no WAKE_EVAL_DIR: the accuracy run is skipped", dir != null && dir.isDirectory && System.getenv("WAKE_NATIVE_DIR") != null)
        dir!!
        val args = (System.getenv("WAKE_EVAL_ARGS") ?: "").split(';').mapNotNull { a -> a.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
        val out = File(System.getenv("WAKE_EVAL_OUT") ?: File(dir, "result.tsv").path)
        val model = KeywordModel(File("src/main/assets/wakeword").absoluteFile)
        check(model.complete) { "model files missing in ${model.dir}" }
        val pieces = model.vocab.inputStream().use { WordPieces.read(it) }

        val names = File(dir, "names.tsv").readLines().filter { it.isNotBlank() }.map { it.split('\t') }.associate { it[0] to it[1] }
            .filterKeys { k -> args["names"]?.split(',')?.contains(k) ?: true }
        val clips = File(dir, "clips.tsv").readLines().filter { it.isNotBlank() }.map { it.split('\t') }
        val negs = if (args["neg"] == "0") emptyList() else File(dir, "neg.tsv").readLines().filter { it.isNotBlank() }.map { it.split('\t') }
        val tunes = (args["tune"] ?: "").split(',').mapNotNull { t -> t.split(':').takeIf { it.size == 4 } }
            .associate { Sensitivity.valueOf(it[0]) to WakeKeywords.Tuning(it[1].toFloat(), it[2].toFloat(), it[3].toFloat()) }
        val modes = (args["modes"] ?: "typed,taught").split(',')
        val senses = (args["sens"] ?: "LOW,MEDIUM,HIGH").split(',').map { Sensitivity.valueOf(it) }
        val jobs = names.flatMap { (k, p) -> modes.flatMap { m -> senses.map { s -> Job(k, p, m, s, tunes[s] ?: WakeKeywords.tuning(s)) } } }

        val lines = java.util.Collections.synchronizedList(ArrayList<String>())
        val pool = Executors.newFixedThreadPool(args["threads"]?.toInt() ?: 4)
        for (j in jobs) pool.execute {
            runCatching { one(j, model, pieces, clips, negs, dir, lines) }.onFailure { lines.add("ERROR\t${j.key}\t${j.mode}\t${j.sens}\t${it}") }
            synchronized(out) { out.writeText(lines.sorted().joinToString("\n") + "\n") }
        }
        pool.shutdown(); pool.awaitTermination(24, TimeUnit.HOURS)
        out.writeText(lines.sorted().joinToString("\n") + "\n")
        println("wake accuracy run: ${jobs.size} jobs, results in $out")
    }

    private fun one(j: Job, model: KeywordModel, pieces: WordPieces, clips: List<List<String>>, negs: List<List<String>>, dir: File, lines: MutableList<String>) {
        val typed = pieces.split(WakePhrase.latin(j.phrase))
        var learned = emptyList<List<String>>()
        if (j.mode == "taught") {
            val learner = SpellingLearner(model)
            val heard = clips.filter { it[0] == "teach" && it[1] == j.key }.map { learner.hear(floats(wav(File(dir, it[2])))) }
            learner.close()
            learned = Learned.merge(emptyList(), heard, pieces)
        }
        val keywords = WakeKeywords.lines(typed, learned, j.tuning)
        val work = File(System.getProperty("java.io.tmpdir"), "wake-eval-${j.key}-${j.mode}-${j.sens}-${System.nanoTime()}")
        val engine = SherpaKeywordEngine(model, work)
        check(engine.listenFor(keywords)) { "the engine refused: $keywords" }
        val head = "${j.key}\t${j.mode}\t${j.sens}"
        lines.add("KEYWORDS\t$head\t${keywords.joinToString(" | ")}")
        try {
            for (set in clips.map { it[0] }.distinct().filter { it != "teach" }) {
                val mine = clips.filter { it[0] == set && it[1] == j.key }
                if (mine.isEmpty()) continue
                var hits = 0
                for (c in mine) { val d = WakeDetector(engine); engine.reset(); if (play(d, wav(File(dir, c[2]))) > 0) hits++ }
                lines.add("CLIPS\t$head\t$set\t${mine.size}\t$hits")
            }
            for ((kind, files) in negs.groupBy({ it[0] }, { it[1] })) {
                var hits = 0; var seconds = 0.0; var frames = 0L; var modelFrames = 0L
                val d = WakeDetector(engine); engine.reset()
                for (f in files) { val pcm = wav(File(dir, f)); hits += play(d, pcm); seconds += pcm.size / 16000.0 }
                frames += d.frames; modelFrames += d.modelFrames
                lines.add("NEG\t$head\t$kind\t${"%.0f".format(seconds)}\t$hits\t$frames\t$modelFrames")
            }
        } finally { engine.close(); work.deleteRecursively() }
    }

    /** Feed a whole file the way the service does (100 ms at a time); how many times it woke. */
    private fun play(d: WakeDetector, pcm: ShortArray): Int {
        var hits = 0; var at = 0
        val block = ShortArray(1600)
        while (at < pcm.size) {
            val n = minOf(block.size, pcm.size - at)
            System.arraycopy(pcm, at, block, 0, n); at += n
            if (d.feed(block, n)) hits++
        }
        return hits
    }

    private fun floats(pcm: ShortArray) = FloatArray(pcm.size) { pcm[it] / 32768f }

    companion object {
        /** A 16 kHz mono 16-bit WAV file as samples. */
        fun wav(f: File): ShortArray = RandomAccessFile(f, "r").use { r ->
            val all = ByteArray(r.length().toInt()); r.readFully(all)
            val b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
            var at = 12
            while (at + 8 <= all.size) {
                val id = String(all, at, 4, Charsets.US_ASCII); val size = b.getInt(at + 4)
                if (id == "fmt ") check(b.getShort(at + 10).toInt() == 1 && b.getInt(at + 12) == 16000 && b.getShort(at + 22).toInt() == 16) { "$f is not 16 kHz mono 16-bit" }
                if (id == "data") {
                    val n = minOf(size.toLong() and 0xffffffffL, (all.size - at - 8).toLong()).toInt() / 2
                    val out = ShortArray(n); b.position(at + 8); b.asShortBuffer().get(out); return@use out
                }
                at += 8 + size + (size and 1)
            }
            error("$f has no audio")
        }
    }
}
