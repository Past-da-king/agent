package com.past9.phoneaos.wakeword

import java.io.InputStream

/**
 * Splits a phrase into the keyword model's own word pieces, on the phone, with no library: the same split its training
 * used (SentencePiece, unigram: of all the ways to cut the word into known pieces, the most likely one).
 *
 * The pieces and their scores come from the model's vocabulary file (assets/wakeword/bpe.vocab, "piece<TAB>score").
 * A piece that starts a word carries the mark [WORD]. Every capital letter is a piece by itself, so any word written
 * in A to Z can be split.
 */
class WordPieces(private val scores: Map<String, Float>) {
    private val longest = scores.keys.maxOfOrNull { it.length } ?: 1

    /** "HEY NOVA" to [▁HE, Y, ▁NO, V, A]. Letters the model has no piece for are left out. */
    fun split(latin: String): List<String> = latin.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.flatMap { word(it) }

    private fun word(w: String): List<String> {
        val s = WORD + w.filter { scores.containsKey(it.toString()) }
        if (s.length == 1) return emptyList()
        val n = s.length
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val from = IntArray(n + 1) { -1 }
        best[0] = 0.0
        for (end in 1..n) for (start in maxOf(0, end - longest) until end) {
            if (best[start] == Double.NEGATIVE_INFINITY) continue
            val score = scores[s.substring(start, end)] ?: continue
            if (best[start] + score > best[end]) { best[end] = best[start] + score; from[end] = start }
        }
        if (from[n] < 0) return emptyList()
        val out = ArrayList<String>()
        var end = n
        while (end > 0) { out.add(s.substring(from[end], end)); end = from[end] }
        return out.asReversed().toList()
    }

    /** True when every piece is one the model knows (a learned spelling read back from storage is checked with this). */
    fun known(pieces: List<String>): Boolean = pieces.isNotEmpty() && pieces.all { scores.containsKey(it) }

    companion object {
        const val WORD = "▁"

        fun read(vocab: InputStream): WordPieces = WordPieces(vocab.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapNotNull { l ->
                val tab = l.lastIndexOf('\t')
                val piece = if (tab > 0) l.substring(0, tab) else return@mapNotNull null
                // Control pieces (<blk>, <unk>) are not letters.
                if (piece.startsWith("<")) null else l.substring(tab + 1).toFloatOrNull()?.let { piece to it }
            }.toMap()
        })
    }
}
