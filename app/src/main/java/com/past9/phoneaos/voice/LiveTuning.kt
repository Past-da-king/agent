package com.past9.phoneaos.voice

import org.json.JSONArray
import org.json.JSONObject

/**
 * How a Live session takes turns with the person, and how it keeps a long conversation. Every value is a setting with a
 * sane default; nothing here is tied to one user.
 *
 * Sources: Google's Live API capabilities page (automaticActivityDetection: startOfSpeechSensitivity,
 * endOfSpeechSensitivity, prefixPaddingMs, silenceDurationMs; manual activityStart/activityEnd) and the session
 * management page (contextWindowCompression, sessionResumption).
 * https://aistudio.google.com/docs/live-api/capabilities , https://aistudio.google.com/docs/live-api/session-management
 *
 * Before: the setup sent `automaticActivityDetection: {}`, so the server's own defaults decided when he had finished.
 * Those are tuned for quick back and forth (about 0.8 s of silence ends a turn), which cut in on anyone who pauses to
 * think or switches language mid-sentence. Now the defaults are patient.
 */
data class LiveTuning(
    val patience: Patience = Patience.PATIENT,
    /** True: the server's detector is off and the person marks each turn (Tap to talk, then I'm done). */
    val waitForMe: Boolean = false,
    /** True: no language is pinned, so the model follows whatever language(s) are spoken. False pins the requested one. */
    val autoLanguage: Boolean = false,
    /** Words the transcriber should get right (names of people, projects, agents). Up to [MAX_VOCAB]. */
    val vocabulary: List<String> = emptyList(),
    /** Conversation length, in tokens, at which the oldest turns are dropped (sliding window). */
    val compressionTriggerTokens: Int = DEFAULT_TRIGGER,
) {
    enum class Patience(val key: String, val silenceMs: Int, val prefixMs: Int, val endLow: Boolean) {
        /** Server default feel: about 0.8 s of quiet ends the turn. */
        QUICK("quick", 800, 100, false),
        /** The default: low end-of-speech sensitivity and 1.4 s of quiet, so a pause to think is not the end. */
        PATIENT("patient", 1400, 200, true),
        /** For people who pause a lot or dictate: 2.4 s of quiet. */
        VERY("very", 2400, 250, true);

        companion object { fun of(key: String?): Patience = values().firstOrNull { it.key == key } ?: PATIENT }
    }

    /** The `realtimeInputConfig` of the setup. */
    fun realtimeInputConfig(): JSONObject {
        val vad = JSONObject()
        if (waitForMe) {
            // Manual: no pre-speech buffer and no silence tolerance on the server (Google), so the client marks both ends.
            vad.put("disabled", true)
        } else {
            vad.put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW")
                .put("endOfSpeechSensitivity", if (patience.endLow) "END_SENSITIVITY_LOW" else "END_SENSITIVITY_HIGH")
                .put("prefixPaddingMs", patience.prefixMs)
                .put("silenceDurationMs", patience.silenceMs)
        }
        return JSONObject().put("automaticActivityDetection", vad)
    }

    /** `inputAudioTranscription`, with custom vocabulary when there is any. */
    fun inputTranscription(): JSONObject {
        val o = JSONObject()
        val v = vocabulary.map { it.trim() }.filter { it.length in 2..40 }.distinct().take(MAX_VOCAB)
        if (v.isNotEmpty()) o.put("customVocabulary", JSONArray(v))
        return o
    }

    /** `contextWindowCompression`: a sliding window, so a long conversation keeps going instead of ending at about 15 minutes. */
    fun compression(): JSONObject = JSONObject().put("slidingWindow", JSONObject())
        .put("triggerTokens", compressionTriggerTokens.toString())

    companion object {
        const val DEFAULT_TRIGGER = 100_000
        const val MAX_VOCAB = 60
        const val PIN_DEFAULT = "en-US"

        /** The language to pin in speechConfig, or null to pin none. */
        fun languageToPin(auto: Boolean, requested: String): String? = if (auto) null else requested.ifBlank { PIN_DEFAULT }
    }
}
