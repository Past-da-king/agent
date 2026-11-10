package com.past9.phoneaos.wakeword

/** The keyword model, behind one small door so the detector can be tested without it. */
interface KeywordEngine {
    /** Listen for these keyword lines from now on (see [WakeKeywords.lines]). False when the engine refused them. */
    fun listenFor(lines: List<String>): Boolean
    /** Take 16 kHz audio (-1..1). True when the phrase was just heard. */
    fun feed(samples: FloatArray): Boolean
    /** Forget what was heard so far (a new stretch of sound starts clean). */
    fun reset()
    fun close()
}

/** How easily the phrase is accepted. Three steps, as in the settings sheet. */
enum class Sensitivity(val label: String, val line: String) {
    LOW("Strict", "Fewest false wake-ups. You may need to speak up."),
    MEDIUM("Balanced", "Right for most rooms."),
    HIGH("Easy", "Hears you from further away. Wakes by mistake more often.");

    companion object { fun of(name: String?): Sensitivity = entries.firstOrNull { it.name == name } ?: MEDIUM }
}

/** Builds the lines the keyword model listens for, from the typed phrase and anything it learned from the person's voice. */
object WakeKeywords {
    /** How strongly the model is pulled towards the phrase ([boost]) and how sure it must be to call it ([threshold]). */
    data class Tuning(val boost: Float, val threshold: Float, val learnedThreshold: Float)

    /** Measured on the server over nine names (docs/wake-word.md); change them only with a new run of the same test. */
    fun tuning(s: Sensitivity): Tuning = when (s) {
        Sensitivity.LOW -> Tuning(boost = 1.0f, threshold = 0.35f, learnedThreshold = 0.45f)
        Sensitivity.MEDIUM -> Tuning(boost = 1.5f, threshold = 0.25f, learnedThreshold = 0.35f)
        Sensitivity.HIGH -> Tuning(boost = 2.0f, threshold = 0.15f, learnedThreshold = 0.25f)
    }

    const val LABEL = "WAKE"
    /** The fewest pieces a phrase may have. Fewer and half of ordinary speech would match. */
    const val FEWEST_PIECES = 3

    /**
     * One line per way of spelling the phrase: "piece piece piece :boost #threshold @WAKE". [typed] is the phrase as
     * written; [learned] are the spellings the model itself heard when the person said it. Same spelling twice is one line.
     */
    fun lines(typed: List<String>, learned: List<List<String>>, t: Tuning): List<String> {
        val out = LinkedHashMap<String, String>()
        if (typed.size >= FEWEST_PIECES) typed.joinToString(" ").let { out[it] = "$it :${t.boost} #${t.threshold} @$LABEL" }
        for (l in learned) if (l.size >= FEWEST_PIECES) l.joinToString(" ").let { if (it !in out) out[it] = "$it :${t.boost} #${t.learnedThreshold} @$LABEL" }
        return out.values.toList()
    }
}

/**
 * The whole listener, minus Android: audio in, "the phrase was said" out. The service on the phone and the accuracy
 * run on the server both feed this same class, so the numbers in docs/wake-word.md are numbers for the shipped code.
 *
 * Audio arrives in any block size. It is cut into 20 ms frames for the [SpeechGate]; while the gate is shut nothing
 * reaches the model, and the last [PRE_ROLL] of audio is kept so that when the gate opens the model also hears the
 * start of the word that opened it. After a hit the detector is deaf for [deafFrames] so one phrase wakes once.
 */
class WakeDetector(
    private val engine: KeywordEngine,
    private val gate: SpeechGate = SpeechGate(),
    private val deafFrames: Int = 100,
) {
    private val frame = ShortArray(SpeechGate.FRAME)
    private var filled = 0
    private val pre = ShortArray(PRE_ROLL)
    private var preAt = 0
    private var preCount = 0
    private var wasOpen = false
    private var deaf = 0
    private val toModel = FloatArray(SpeechGate.FRAME)

    /** Frames seen, and frames that went to the model: the second over the first is how much of the time the model ran. */
    var frames = 0L; private set
    var modelFrames = 0L; private set
    val gateOpen: Boolean get() = gate.open

    /** Feed [n] samples of 16 kHz mono audio. True when the phrase was heard somewhere in them. */
    fun feed(pcm: ShortArray, n: Int = pcm.size): Boolean {
        var hit = false
        var i = 0
        while (i < n) {
            val take = minOf(SpeechGate.FRAME - filled, n - i)
            System.arraycopy(pcm, i, frame, filled, take)
            filled += take; i += take
            if (filled == SpeechGate.FRAME) { filled = 0; if (oneFrame()) hit = true }
        }
        return hit
    }

    private fun oneFrame(): Boolean {
        frames++
        if (deaf > 0) { deaf--; remember(); return false }
        val open = gate.frame(frame)
        var hit = false
        if (open) {
            if (!wasOpen) hit = give(preRoll())
            if (!hit) hit = give(floats(frame, SpeechGate.FRAME))
            modelFrames++
        } else {
            if (wasOpen) engine.reset()
            remember()
        }
        wasOpen = open
        if (hit) { deaf = deafFrames; engine.reset(); gate.reset(); wasOpen = false; preCount = 0 }
        return hit
    }

    private fun give(samples: FloatArray): Boolean = samples.isNotEmpty() && engine.feed(samples)

    private fun remember() {
        for (s in frame) { pre[preAt] = s; preAt = (preAt + 1) % PRE_ROLL }
        preCount = minOf(PRE_ROLL, preCount + SpeechGate.FRAME)
    }

    private fun preRoll(): FloatArray {
        val out = FloatArray(preCount)
        val start = (preAt - preCount + PRE_ROLL) % PRE_ROLL
        for (k in 0 until preCount) out[k] = pre[(start + k) % PRE_ROLL] / 32768f
        preCount = 0
        return out
    }

    private fun floats(pcm: ShortArray, n: Int): FloatArray { for (k in 0 until n) toModel[k] = pcm[k] / 32768f; return if (n == toModel.size) toModel.copyOf() else toModel.copyOf(n) }

    fun reset() { filled = 0; preCount = 0; preAt = 0; wasOpen = false; deaf = 0; gate.reset(); engine.reset() }

    companion object {
        /** Half a second of 16 kHz audio kept from before the gate opened. */
        const val PRE_ROLL = 8000
        const val RATE = 16000
    }
}
