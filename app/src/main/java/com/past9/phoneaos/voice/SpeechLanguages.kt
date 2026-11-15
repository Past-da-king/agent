package com.past9.phoneaos.voice

import java.util.Locale

/**
 * The languages a speech model is told to listen for (12 Nov 2026).
 *
 * Measured with gemini-3.5-transcribe on English mixed with isiZulu, Afrikaans and Sesotho: told ONE language
 * (en-ZA, en-US, or none at all) it writes the English and silently DROPS the other languages (the isiZulu simply
 * vanished from a 55 s dictation). Told the person's own languages plus the other languages spoken where they are, it
 * writes every one of them and the English stays word for word. Two languages (en + zu) lose Afrikaans, so the set has
 * to be the region's, not a guess at one. No language is special: this is the system's own list first, then the
 * languages commonly mixed in with them in the same country.
 */
object SpeechLanguages {
    /** Most languages sent at once: past about eight the English begins to lose its first words. */
    const val MAX = 6

    /** Languages commonly spoken beside the national ones, by country (the region of the phone's locale). */
    private val NEIGHBOURS: Map<String, List<String>> = mapOf(
        "ZA" to listOf("zu-ZA", "af-ZA", "st-ZA", "xh-ZA", "tn-ZA"),
        "NA" to listOf("af-NA", "de-NA"),
        "BW" to listOf("tn-BW", "en-BW"),
        "LS" to listOf("st-LS", "zu-ZA"),
        "ZW" to listOf("sn-ZW", "nd-ZW"),
        "NG" to listOf("yo-NG", "ig-NG", "ha-NG"),
        "KE" to listOf("sw-KE"),
        "TZ" to listOf("sw-TZ"),
        "IN" to listOf("hi-IN", "ta-IN", "te-IN", "bn-IN", "mr-IN"),
        "PK" to listOf("ur-PK", "pa-PK"),
        "CH" to listOf("de-CH", "fr-CH", "it-CH"),
        "BE" to listOf("nl-BE", "fr-BE"),
        "CA" to listOf("fr-CA"),
        "PH" to listOf("fil-PH"),
    )

    private fun tag(l: Locale) = l.toLanguageTag().replace('_', '-').takeIf { it.isNotBlank() && it != "und" }

    /** [locales] is the person's own list, first choice first; then the neighbours of the first region found. */
    fun forLocales(locales: List<Locale>): List<String> {
        val own = locales.mapNotNull(::tag).distinct()
        val region = locales.firstNotNullOfOrNull { it.country.takeIf { c -> c.isNotBlank() } }
        val out = ArrayList<String>(own.take(MAX))
        val have = out.map { it.substringBefore('-').lowercase() }.toMutableSet()
        for (n in NEIGHBOURS[region?.uppercase()].orEmpty()) {
            if (out.size >= MAX) break
            val lang = n.substringBefore('-').lowercase()
            // The person's own tag for a language wins over ours (zu-ZA from the settings stays as it is).
            if (lang in have) continue
            out.add(n); have.add(lang)
        }
        return out
    }
}
