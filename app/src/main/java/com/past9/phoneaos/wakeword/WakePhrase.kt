package com.past9.phoneaos.wakeword

import java.text.Normalizer

/**
 * The words that wake the agent, and how they become something the keyword spotter can listen for.
 *
 * Nothing here knows any name. The phrase is "Hey " plus whatever the person called their agent, read from the app's
 * settings every time it is needed, or a phrase they typed themselves. The spotter's model was trained on English
 * speech and listens for word pieces written in A to Z, so a phrase goes through three steps:
 *  1. [latin]: accents come off (José is JOSE), a few letters English lacks are spelled out, and writing that is not
 *     Latin at all (Cyrillic, Greek, kana, Hangul, Arabic) is romanised by the phone when it can ([Romanizer]).
 *  2. [WordPieces] splits the letters into the model's own pieces.
 *  3. If the model would not hear the name the way English spells it, the person says it three times and the pieces
 *     the model actually heard are kept beside the typed ones ([SpellingLearner]).
 */
object WakePhrase {
    const val PREFIX = "Hey"
    /** The agent's name before the person gave it one (data/Settings.kt). Not something anybody says. */
    private const val UNNAMED = "Your agent"
    private const val APOSTROPHE = '\''

    /** "Hey <name>": the phrase when the person has not typed their own. */
    fun defaultFor(agentName: String): String {
        val n = agentName.trim().takeIf { it.isNotEmpty() && !it.equals(UNNAMED, ignoreCase = true) } ?: "Agent"
        return "$PREFIX $n"
    }

    /** The phrase in force: the person's own if they typed one, else "Hey <name>" with the name as it is right now. */
    fun current(custom: String, agentName: String): String = custom.trim().ifEmpty { defaultFor(agentName) }

    /** Turns writing that is not Latin into Latin letters, or returns it unchanged when it cannot. */
    fun interface Romanizer { fun romanize(text: String): String }

    private val spelled = mapOf(
        'ß' to "ss", 'æ' to "ae", 'œ' to "oe", 'ø' to "o", 'đ' to "d", 'ð' to "d", 'þ' to "th", 'ł' to "l", 'ı' to "i", 'ħ' to "h", 'ŋ' to "ng",
        '0' to " zero ", '1' to " one ", '2' to " two ", '3' to " three ", '4' to " four ", '5' to " five ", '6' to " six ", '7' to " seven ", '8' to " eight ", '9' to " nine ",
    )

    /**
     * The phrase in capital A to Z, apostrophes and single spaces: what the word pieces are made from. Empty when
     * nothing in it can be written in Latin letters (then only teaching by voice works).
     */
    fun latin(phrase: String, romanizer: Romanizer? = null): String {
        var s = phrase.trim()
        if (romanizer != null && s.any { it.isLetter() && !isLatin(it) }) s = runCatching { romanizer.romanize(s) }.getOrDefault(s)
        s = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD)
        val out = StringBuilder()
        for (c in s) when {
            c in 'a'..'z' -> out.append(c)
            c == APOSTROPHE || c == '’' || c == 'ʼ' -> if (out.isNotEmpty() && out.last() != ' ') out.append(APOSTROPHE)
            spelled.containsKey(c) -> out.append(spelled.getValue(c))
            Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> Unit
            else -> out.append(' ')
        }
        return out.toString().uppercase().split(' ').map { it.trim(APOSTROPHE) }.filter { it.isNotEmpty() }.joinToString(" ")
    }

    fun isLatin(c: Char): Boolean = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN

    /** Roughly how many beats the phrase has when spoken: groups of vowels in its Latin form. */
    fun syllables(latin: String): Int = latin.split(' ').sumOf { w ->
        val groups = Regex("[AEIOUY]+").findAll(w).count()
        // A silent final E ("Jane") is not a beat, but a word always has one.
        val silentE = if (groups > 1 && w.endsWith("E") && w.length > 2 && w[w.length - 2] !in "AEIOUY") 1 else 0
        if (w.isEmpty()) 0 else maxOf(1, groups - silentE)
    }

    enum class Fit { GOOD, SHORT, UNSPELLABLE }

    /** Whether the phrase is long enough to tell apart from ordinary talk: three beats or more. */
    fun fit(latin: String): Fit = when {
        latin.isBlank() -> Fit.UNSPELLABLE
        syllables(latin) < 3 -> Fit.SHORT
        else -> Fit.GOOD
    }

    /** One plain line for the settings sheet about the phrase as typed. */
    fun advice(phrase: String, latin: String): String = when (fit(latin)) {
        Fit.UNSPELLABLE -> "This can't be written in Latin letters on this phone. Use Say it three times so it learns the sound from you."
        Fit.SHORT -> "Short phrases get mistaken for ordinary talk. Three or more beats works best, like \"Hey\" and a two-beat name."
        Fit.GOOD -> if (phrase.any { it.isLetter() && !isLatin(it) }) "Listening for it as \"${latin.lowercase()}\". If it misses you, use Say it three times."
            else "Good length. If it misses you, use Say it three times so it learns how you say it."
    }
}
