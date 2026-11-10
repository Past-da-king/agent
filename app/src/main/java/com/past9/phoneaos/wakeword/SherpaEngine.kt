package com.past9.phoneaos.wakeword

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/** The model's files, in one folder on disk (on the phone they are copied out of the APK once: [KeywordModelFiles]). */
class KeywordModel(val dir: File) {
    val encoder get() = File(dir, "encoder.int8.onnx")
    val decoder get() = File(dir, "decoder.int8.onnx")
    val joiner get() = File(dir, "joiner.int8.onnx")
    val tokens get() = File(dir, "tokens.txt")
    val vocab get() = File(dir, "bpe.vocab")
    val complete: Boolean get() = listOf(encoder, decoder, joiner, tokens, vocab).all { it.isFile && it.length() > 0 }

    internal fun config(threads: Int) = OnlineModelConfig(
        transducer = OnlineTransducerModelConfig(encoder = encoder.path, decoder = decoder.path, joiner = joiner.path),
        tokens = tokens.path, numThreads = threads, provider = "cpu", modelType = "zipformer2",
    )

    companion object { val NAMES = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt", "bpe.vocab") }
}

/**
 * The keyword model itself: sherpa-onnx's open-vocabulary keyword spotter (a very small streaming speech model whose
 * search is held to the phrases it is given). It runs on the phone's processor, one thread, and never opens a socket.
 * The phrase is plain text in a file next to the model, so a new phrase is a new file, not a new model.
 */
class SherpaKeywordEngine(private val model: KeywordModel, private val work: File, private val threads: Int = 1) : KeywordEngine {
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null

    @Synchronized override fun listenFor(lines: List<String>): Boolean {
        release()
        if (lines.isEmpty() || !model.complete) return false
        return runCatching {
            work.mkdirs()
            val file = File(work, "keywords.txt").apply { writeText(lines.joinToString("\n") + "\n") }
            val s = KeywordSpotter(config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = WakeDetector.RATE, featureDim = 80),
                modelConfig = model.config(threads),
                maxActivePaths = 4, keywordsFile = file.path, numTrailingBlanks = 1,
            ))
            spotter = s; stream = s.createStream()
            true
        }.getOrElse { release(); false }
    }

    @Synchronized override fun feed(samples: FloatArray): Boolean {
        val s = spotter ?: return false
        val st = stream ?: return false
        st.acceptWaveform(samples, WakeDetector.RATE)
        var hit = false
        while (s.isReady(st)) {
            s.decode(st)
            if (s.getResult(st).keyword.isNotEmpty()) { hit = true; s.reset(st) }
        }
        return hit
    }

    @Synchronized override fun reset() { val s = spotter ?: return; stream?.let { s.reset(it) } }

    @Synchronized override fun close() = release()

    private fun release() {
        runCatching { stream?.release() }; stream = null
        runCatching { spotter?.release() }; spotter = null
    }
}

/**
 * "Say it three times": hears a phrase with the same model, with its search let loose, and returns the word pieces
 * the model heard. Those pieces go beside the typed ones, so the spotter listens for the name the way this person
 * says it, in whatever language. Nothing is recorded: the audio is used and dropped.
 */
class SpellingLearner(private val model: KeywordModel, private val threads: Int = 1) {
    private var recognizer: OnlineRecognizer? = null

    /** The pieces heard in this stretch of 16 kHz audio; empty when nothing was said. */
    @Synchronized fun hear(samples: FloatArray): List<String> {
        if (!model.complete) return emptyList()
        return runCatching {
            val r = recognizer ?: OnlineRecognizer(config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = WakeDetector.RATE, featureDim = 80),
                modelConfig = model.config(threads), enableEndpoint = false, decodingMethod = "greedy_search",
            )).also { recognizer = it }
            val st = r.createStream()
            try {
                st.acceptWaveform(samples, WakeDetector.RATE)
                // A little silence after the words, so the last piece is not left waiting for more sound.
                st.acceptWaveform(FloatArray(WakeDetector.RATE / 2), WakeDetector.RATE)
                st.inputFinished()
                while (r.isReady(st)) r.decode(st)
                pieces(r.getResult(st).tokens)
            } finally { st.release() }
        }.getOrDefault(emptyList())
    }

    @Synchronized fun close() { runCatching { recognizer?.release() }; recognizer = null }

    companion object {
        /** The engine hands pieces back with a space where the model's vocabulary has its word mark. */
        fun pieces(tokens: Array<String>): List<String> = tokens.map { it.trim('\u0000') }.filter { it.isNotBlank() }
            .map { if (it.startsWith(" ") || it.startsWith(WordPieces.WORD)) WordPieces.WORD + it.drop(1).trim() else it.trim() }
            .filter { it != WordPieces.WORD }
    }
}

/** What was learned from the person's voice, kept small and tidy. */
object Learned {
    const val MOST = 4

    /**
     * Add what was heard this time to what was heard before: the spellings heard most often first, at most [MOST].
     * A hearing with too few pieces (a cough, a single word) or pieces the model does not know is not a spelling.
     */
    fun merge(before: List<List<String>>, heard: List<List<String>>, pieces: WordPieces): List<List<String>> {
        val good = (before + heard).filter { it.size >= WakeKeywords.FEWEST_PIECES && it.size <= 24 && pieces.known(it) }
        return good.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(MOST)
    }

    fun encode(spellings: List<List<String>>): String = spellings.joinToString("\n") { it.joinToString(" ") }
    fun decode(raw: String): List<List<String>> = raw.lines().map { l -> l.trim().split(' ').filter { it.isNotEmpty() } }.filter { it.isNotEmpty() }
}
