package com.past9.phoneaos.voice

import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The words of a dictation, from Google's speech-to-text model (11 Nov 2026).
 *
 * The composer's live caption is the phone's own recogniser, and it is only ever a caption: this is what he actually
 * gets. Google's transcribe model reads the saved audio in one go, is told the languages he speaks (South African
 * English and the isiZulu that runs through it) and a small vocabulary of names, and switches between the two by
 * itself. The plain generateContent prompt it used before carried none of that, which is how "Opus and Sonnet" came
 * back as "opuses and sonnets".
 */
object SpeechModel {
    private const val TAG = "AosSpeech"

    /** The model made for this. Its own endpoint: the Interactions API, where the transcription settings live. */
    const val MODEL = "gemini-3.5-transcribe"
    private const val INTERACTIONS = "https://generativelanguage.googleapis.com/v1beta/interactions"
    private const val GENERATE = "https://generativelanguage.googleapis.com/v1beta/models/"

    /** Strong general models that read audio, used only when the speech model cannot be reached. Never a lite one. */
    val FALLBACKS = listOf("gemini-3.6-flash", "gemini-3.5-flash", "gemini-2.5-flash")

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
            .build()
    }

    /**
     * The languages to tell the model, from the phone's own setting (11 Nov 2026). Empty is fine: it detects the
     * language, and switches between languages inside a sentence, by itself.
     *
     * isiZulu is deliberately NOT added for South African English. That was tried first and measured worse on his own
     * recordings: with zu-ZA in the list "My guy" came back as "Shumayela", and with en-ZA alone the same audio came
     * back word for word. isiZulu he mixes into English is still written as isiZulu; the English is not biased at it.
     */
    fun languages(locale: Locale = Locale.getDefault()): List<String> {
        val tag = locale.toLanguageTag().replace('_', '-')
        if (tag.isBlank() || tag == "und") return emptyList()
        return listOf(tag)
    }

    /** The request body for the speech model, with the settings that make the difference. */
    fun body(model: String, wavBase64: String, vocabulary: List<String>, languages: List<String>, mode: String = "verbatim"): JSONObject {
        val config = JSONObject().put("mode", mode)
        val codes = languages.filter { it.isNotBlank() }
        if (codes.isNotEmpty()) config.put("language_codes", JSONArray(codes))
        val terms = vocabulary.map { it.trim() }.filter { it.length in 2..60 }.distinctBy { it.lowercase() }.take(100)
        if (terms.isNotEmpty()) config.put("custom_vocabulary", JSONArray(terms))
        return JSONObject()
            .put("model", "models/$model")
            .put("input", JSONArray().put(JSONObject()
                .put("type", "user_input")
                .put("content", JSONArray().put(JSONObject()
                    .put("type", "audio")
                    .put("data", wavBase64)
                    .put("mime_type", "audio/wav")))))
            .put("generation_config", JSONObject().put("transcription_config", config))
    }

    /** The transcript out of an Interactions reply, whichever shape it uses. */
    fun textOf(reply: JSONObject): String {
        reply.optString("output_text").takeIf { it.isNotBlank() }?.let { return it.trim() }
        val steps = reply.optJSONArray("steps") ?: return ""
        val parts = StringBuilder()
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            val type = step.optString("type")
            if (type.isNotEmpty() && type != "model_output") continue
            val content = step.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                part.optString("text").takeIf { it.isNotBlank() }?.let { parts.append(it) }
                part.optJSONObject("audioTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let { parts.append(it) }
            }
        }
        return parts.toString().trim()
    }

    /** The transcript out of a generateContent reply: text, or the speech model's own audioTranscription. */
    fun generateTextOf(reply: JSONObject): String {
        val parts = reply.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        val out = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            part.optString("text").takeIf { it.isNotBlank() }?.let { out.append(it) }
            part.optJSONObject("audioTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let { out.append(it).append('\n') }
        }
        return out.toString().trim()
    }

    /**
     * One piece of saved audio as words, 16 kHz mono PCM16 in. Throws when nothing could be reached: the caller keeps
     * the audio and retries. [vocabulary] is his names (the linked computer adds its own from memory).
     */
    fun transcribe(
        key: String,
        pcm: ByteArray,
        vocabulary: List<String> = emptyList(),
        languages: List<String> = languages(),
        client: OkHttpClient = http,
    ): String {
        val wav = DictationTape.header(pcm.size.toLong()) + pcm
        val base64 = Base64.encodeToString(wav, Base64.NO_WRAP)
        var last: Exception? = null
        // 1. The speech model.
        try {
            val reply = post(client, INTERACTIONS, key, body(MODEL, base64, vocabulary, languages))
            val text = textOf(reply)
            if (text.isNotEmpty()) return text
            Log.i(TAG, "speech model answered no words")
        } catch (e: IOException) {
            last = e
            Log.i(TAG, "speech model: ${e.message}")
        }
        // 2. A strong general model reading the audio, told the same names and languages.
        val names = vocabulary.map { it.trim() }.filter { it.length in 2..60 }.distinctBy { it.lowercase() }.take(100)
        val hint = buildString {
            append("Transcribe this recording exactly as spoken. Add normal punctuation and capital letters. ")
            append("Reply with the transcript only. If nobody speaks, reply with nothing.")
            if (names.isNotEmpty()) append(" Names the speaker may say, spelled as they should be written: ${names.joinToString(", ")}. Only spell a name that is clearly said.")
            if (languages.isNotEmpty()) append(" He speaks ${languages.joinToString(" and ")}, often mixing them inside one sentence.")
        }
        for (model in FALLBACKS) {
            try {
                val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                    .put(JSONObject().put("text", hint))
                    .put(JSONObject().put("inline_data", JSONObject()
                        .put("mime_type", "audio/wav").put("data", base64))))))
                    .put("generationConfig", JSONObject().put("temperature", 0))
                return generateTextOf(post(client, GENERATE + model + ":generateContent", key, body))
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("Gemini couldn't write that down.")
    }

    private fun post(client: OkHttpClient, url: String, key: String, body: JSONObject): JSONObject {
        val request = Request.Builder().url(url).header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Gemini ${response.code}")
            return runCatching { JSONObject(text) }.getOrElse { throw IOException("Gemini answered something unreadable") }
        }
    }
}
