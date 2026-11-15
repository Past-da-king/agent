package com.past9.phoneaos

import com.past9.phoneaos.voice.SpeechLanguages
import com.past9.phoneaos.voice.SpeechModel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The final words of a phone dictation come from Google's speech-to-text model, told his languages and his names
 * (11 Nov 2026). These pin the request and what is read back, so a later change cannot quietly drop them again.
 */
class SpeechModelTest {
    @Test
    fun theRequestCarriesHisLanguagesAndHisNames() {
        val body = SpeechModel.body(
            "gemini-3.5-transcribe",
            "AAAA",
            listOf("Ayanda", "Strat Edge", "Ayanda", "Impetix", "  "),
            listOf("en-ZA", "zu-ZA"),
        )
        assertEquals("models/gemini-3.5-transcribe", body.getString("model"))
        val audio = body.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(0)
        assertEquals("audio/wav", audio.getString("mime_type"))
        assertEquals("AAAA", audio.getString("data"))
        val config = body.getJSONObject("generation_config").getJSONObject("transcription_config")
        assertEquals(2, config.getJSONArray("language_codes").length())
        assertEquals("en-ZA", config.getJSONArray("language_codes").getString(0))
        assertEquals(3, config.getJSONArray("custom_vocabulary").length())
        assertEquals("verbatim", config.getString("mode"))
    }

    @Test
    fun aRequestWithNothingToSaySendsNoEmptyLists() {
        val config = SpeechModel.body("gemini-3.5-transcribe", "AAAA", emptyList(), emptyList())
            .getJSONObject("generation_config").getJSONObject("transcription_config")
        assertFalse(config.has("language_codes"))
        assertFalse(config.has("custom_vocabulary"))
        assertEquals("verbatim", config.getString("mode"))
    }

    @Test
    fun theLanguageHintIsEveryLanguageOfThePhoneAndItsCountryNotOne() {
        // One language (even en-ZA) made the model DROP the isiZulu in a mixed dictation (12 Nov 2026, measured).
        val za = SpeechModel.languages(listOf(Locale.forLanguageTag("en-ZA")))
        assertEquals("en-ZA", za[0])
        assertTrue(za.containsAll(listOf("zu-ZA", "af-ZA", "st-ZA", "xh-ZA")))
        assertTrue(za.size <= SpeechLanguages.MAX)
        // The person's own tag for a language is kept, and not added twice.
        val own = SpeechModel.languages(listOf(Locale.forLanguageTag("en-ZA"), Locale.forLanguageTag("zu-ZA")))
        assertEquals(1, own.count { it.startsWith("zu") })
        // A country with no neighbours listed: their own list, nothing invented.
        assertEquals(listOf("en-GB"), SpeechModel.languages(listOf(Locale.forLanguageTag("en-GB"))))
        assertEquals(listOf("en-US", "es-US"), SpeechModel.languages(listOf(Locale.forLanguageTag("en-US"), Locale.forLanguageTag("es-US"))))
        assertEquals(emptyList<String>(), SpeechModel.languages(emptyList()))
    }

    @Test
    fun theTranscriptIsReadOutOfEveryReplyShape() {
        assertEquals("Hello there.", SpeechModel.textOf(JSONObject("""{"output_text":" Hello there. "}""")))
        assertEquals(
            "One. Two.",
            SpeechModel.textOf(JSONObject("""{"steps":[{"type":"model_output","content":[{"text":"One. "},{"text":"Two."}]}]}""")),
        )
        assertEquals(
            "Spoken words.",
            SpeechModel.textOf(JSONObject("""{"steps":[{"content":[{"audioTranscription":{"text":"Spoken words."}}]}]}""")),
        )
        assertEquals("", SpeechModel.textOf(JSONObject("""{"steps":[]}""")))
        assertEquals(
            "Spoken here.",
            SpeechModel.generateTextOf(JSONObject("""{"candidates":[{"content":{"parts":[{"audioTranscription":{"text":"Spoken here."}}]}}]}""")),
        )
    }

    @Test
    fun aVeryLongVocabularyIsCutToWhatTheModelTakes() {
        val many = (0 until 130).map { "Name$it" }
        val config = SpeechModel.body("gemini-3.5-transcribe", "AAAA", many, listOf("en-ZA"))
            .getJSONObject("generation_config").getJSONObject("transcription_config")
        assertEquals(100, config.getJSONArray("custom_vocabulary").length())
        assertTrue(true)
    }
}
