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

    /** Settings extra key that picks the model (default [DEFAULT_MODEL]). A transcribe model, or any Gemini model that reads audio. */
    const val SETTING_KEY = "dictation_model"

    /** The default speech model on this phone and on the computer: measured 19 Nov 2026 on his mixed English and isiZulu, it kept every isiZulu line, names 10 of 11, and kept a quoted mistake as said. */
    const val DEFAULT_MODEL = "gemini-3.1-flash-lite"

    /** The speech model made for this. Its own endpoint: the Interactions API, where the transcription settings live. Tried when the chosen general model fails. */
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
     * The languages to tell the model (12 Nov 2026): the phone's own languages, then those spoken beside them in its
     * country (voice/SpeechLanguages.kt). ONE language, even none, makes the model drop the rest: isiZulu vanished
     * from a dictation when only en-ZA was sent. English is not biased away by the others (measured).
     */
    fun languages(locales: List<Locale> = phoneLocales()): List<String> = SpeechLanguages.forLocales(locales)

    /** All of the phone's languages, first choice first (not only the first one). */
    fun phoneLocales(): List<Locale> {
        val list = android.os.LocaleList.getDefault()
        return (0 until list.size()).map { list.get(it) }
    }

    /** The request body for the speech model, with the settings that make the difference. */
    fun body(model: String, wavBase64: String, vocabulary: List<String>, languages: List<String>, mode: String = "verbatim", fileUri: String? = null): JSONObject {
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
                    .apply { if (fileUri != null) put("uri", fileUri) else put("data", wavBase64) }
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

    /** The most audio sent inline in one request (WAV bytes). Over it the take is uploaded once to the Files API and referenced. Gemini's inline request limit is 20 MB with the base64 and the words. */
    const val INLINE_MAX_BYTES = 12_000_000

    /** What to tell a general model that has no transcription settings: exactly what was said, in the language said, names spelled from the list. */
    fun prompt(vocabulary: List<String>, languages: List<String>): String {
        val names = vocabulary.map { it.trim() }.filter { it.length in 2..60 }.distinctBy { it.lowercase() }.take(100)
        return buildString {
            append("Write down exactly what was said in this recording, in the language it was said. ")
            append("Do not translate and do not summarise. Add normal punctuation and capital letters. ")
            append("Reply with the transcript only. If nobody speaks, reply with nothing.")
            if (languages.isNotEmpty()) append(" The speaker uses ${languages.joinToString(", ")}, often mixing two languages inside one sentence.")
            if (names.isNotEmpty()) append(" Names the speaker may say, spelled as they should be written: ${names.joinToString(", ")}. Only spell a name that is clearly said.")
        }
    }

    /**
     * A whole take as words, 16 kHz mono PCM16 in: ONE pass over the whole audio (19 Nov 2026). The caller never cuts a
     * take into pieces: one request carries the entire file (inline, or uploaded once to the Files API when it is too big
     * for a request) so the model hears everything at once and can use the later words to settle the earlier ones.
     * Throws when nothing could be reached: the caller keeps the audio and retries. [vocabulary] is his names, best first.
     * [model] is the Settings pick: a transcribe model uses the Interactions API, any other reads the file with a prompt.
     */
    fun transcribe(
        key: String,
        pcm: ByteArray,
        vocabulary: List<String> = emptyList(),
        languages: List<String> = languages(),
        client: OkHttpClient = http,
        model: String = DEFAULT_MODEL,
    ): String {
        val wav = DictationTape.header(pcm.size.toLong()) + pcm
        var fileUri: String? = null
        var base64 = ""
        if (wav.size > INLINE_MAX_BYTES) fileUri = FilesApi.upload(client, key, wav, "audio/wav") else base64 = Base64.encodeToString(wav, Base64.NO_WRAP)
        var last: Exception? = null
        Log.i(TAG, "whole take: ${wav.size} bytes, ${if (fileUri != null) "uploaded" else "inline"}, ${vocabulary.size} names, languages $languages, model $model")
        val transcribeModel = model.contains("transcribe")
        val hint = prompt(vocabulary, languages)
        fun general(m: String): String {
            val audio = if (fileUri != null) JSONObject().put("file_data", JSONObject().put("mime_type", "audio/wav").put("file_uri", fileUri))
            else JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav").put("data", base64))
            val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", hint)).put(audio))))
                .put("generationConfig", JSONObject().put("temperature", 0))
            return generateTextOf(post(client, GENERATE + m + ":generateContent", key, body))
        }
        fun speech(m: String): String {
            val reply = post(client, INTERACTIONS, key, body(m, base64, vocabulary, languages, fileUri = fileUri))
            return textOf(reply)
        }
        // The order: the chosen model, then the speech model (when a general model was chosen), then strong general models.
        val steps = ArrayList<Pair<String, () -> String>>()
        if (transcribeModel) steps.add(model to { speech(model) }) else {
            steps.add(model to { general(model) })
            steps.add(MODEL to { speech(MODEL) })
        }
        for (m in FALLBACKS) if (m != model) steps.add(m to { general(m) })
        for ((name, run) in steps) {
            try {
                val text = run()
                if (text.isNotEmpty()) return text
                Log.i(TAG, "$name answered no words")
            } catch (e: IOException) {
                last = e
                Log.i(TAG, "$name: ${e.message}")
            }
        }
        throw last ?: IOException("Gemini couldn't write that down.")
    }

    /** The Gemini Files API: one resumable upload of a take too big to send inline. */
    internal object FilesApi {
        private const val UPLOAD = "https://generativelanguage.googleapis.com/upload/v1beta/files"
        private const val FILES = "https://generativelanguage.googleapis.com/v1beta/"

        fun upload(client: OkHttpClient, key: String, bytes: ByteArray, mime: String): String {
            val start = Request.Builder().url(UPLOAD).header("x-goog-api-key", key)
                .header("X-Goog-Upload-Protocol", "resumable").header("X-Goog-Upload-Command", "start")
                .header("X-Goog-Upload-Header-Content-Length", bytes.size.toString()).header("X-Goog-Upload-Header-Content-Type", mime)
                .post(JSONObject().put("file", JSONObject().put("display_name", "dictation-take")).toString().toRequestBody("application/json".toMediaType())).build()
            val target = client.newCall(start).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Gemini upload ${r.code}")
                r.header("x-goog-upload-url") ?: throw IOException("Gemini upload gave no address")
            }
            val send = Request.Builder().url(target).header("x-goog-api-key", key)
                .header("X-Goog-Upload-Offset", "0").header("X-Goog-Upload-Command", "upload, finalize")
                .post(bytes.toRequestBody(mime.toMediaType())).build()
            val file = client.newCall(send).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw IOException("Gemini upload ${r.code}")
                runCatching { JSONObject(text).getJSONObject("file") }.getOrElse { throw IOException("Gemini upload answered something unreadable") }
            }
            val uri = file.optString("uri").ifBlank { throw IOException("Gemini upload gave no file") }
            // Audio is usually ACTIVE at once; wait a little if it is still PROCESSING.
            var state = file.optString("state")
            var tries = 0
            while (state == "PROCESSING" && tries++ < 20) {
                Thread.sleep(1_000)
                val get = Request.Builder().url(FILES + file.optString("name")).header("x-goog-api-key", key).build()
                state = client.newCall(get).execute().use { r -> if (r.isSuccessful) runCatching { JSONObject(r.body?.string().orEmpty()).optString("state") }.getOrDefault("") else "" }
            }
            if (state == "FAILED") throw IOException("Gemini could not read the upload")
            return uri
        }
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
