package com.past9.phoneaos

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
    fun theLanguageHintIsThePhonesOwnAndNothingInvented() {
        // en-ZA, not isiZulu: adding zu-ZA made "My guy" come back as "Shumayela" on his own recordings.
        assertEquals(listOf("en-ZA"), SpeechModel.languages(Locale.forLanguageTag("en-ZA")))
        assertEquals(listOf("en-GB"), SpeechModel.languages(Locale.forLanguageTag("en-GB")))
        assertEquals(listOf("zu-ZA"), SpeechModel.languages(Locale.forLanguageTag("zu-ZA")))
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
