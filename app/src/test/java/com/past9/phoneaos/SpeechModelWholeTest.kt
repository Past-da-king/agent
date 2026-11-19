package com.past9.phoneaos

import com.past9.phoneaos.voice.DictationTape
import com.past9.phoneaos.voice.SpeechModel
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** One pass over the whole take (19 Nov 2026): what goes over the wire for a 188 s dictation. */
@RunWith(RobolectricTestRunner::class)
class SpeechModelWholeTest {
    private class Call(val url: String, val body: String, val headers: Map<String, String>)

    private fun fake(calls: MutableList<Call>, interactions: String = """{"output_text":"The final words."}"""): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val isUpload = req.url.toString().contains("/upload/")
            val raw = if (isUpload) "" else Buffer().also { req.body?.writeTo(it) }.readUtf8()
            calls.add(Call(req.url.toString(), raw, req.headers.names().associateWith { req.header(it).orEmpty() }))
            val json = "application/json".toMediaType()
            val b = Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("ok")
            when {
                req.header("X-Goog-Upload-Command") == "start" -> b.header("x-goog-upload-url", "https://generativelanguage.googleapis.com/upload/v1beta/files/session1").body("{}".toResponseBody(json))
                isUpload -> b.body("""{"file":{"name":"files/abc","uri":"https://generativelanguage.googleapis.com/v1beta/files/abc","state":"ACTIVE"}}""".toResponseBody(json))
                else -> b.body(interactions.toResponseBody(json))
            }.build()
        }).build()

    private val names = listOf("SEI", "Strat Edge", "Strat Edge ID", "LondaCorp", "Mthashana", "Meqry", "Impetix", "Sinqobile Shoba", "Phumzile Motau", "Nkululeko", "Bongani", "Guavas", "Ben van Rooyen", "Creek", "GrowOS", "Cosmiprint", "Hina", "Ayanda")
    private val langs = listOf("en-ZA", "zu-ZA", "af-ZA", "st-ZA", "xh-ZA", "tn-ZA")

    @Test fun aThreeMinuteTakeIsOneRequestWithTheWholeAudioAndHisNames() {
        val pcm = ByteArray(188 * DictationTape.BYTES_PER_SECOND.toInt()) { (it % 50).toByte() }
        val calls = ArrayList<Call>()
        val text = SpeechModel.transcribe("k", pcm, names, langs, fake(calls))
        assertEquals("The final words.", text)
        assertEquals("exactly one request", 1, calls.size)
        assertTrue(calls[0].url.endsWith("/v1beta/interactions"))
        val body = JSONObject(calls[0].body)
        val audio = body.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(0)
        val wav = android.util.Base64.decode(audio.getString("data"), android.util.Base64.DEFAULT)
        assertEquals("the whole file", pcm.size + 44, wav.size)
        val cfg = body.getJSONObject("generation_config").getJSONObject("transcription_config")
        val vocab = (0 until cfg.getJSONArray("custom_vocabulary").length()).map { cfg.getJSONArray("custom_vocabulary").getString(it) }
        assertEquals(names, vocab)
        assertEquals(langs.size, cfg.getJSONArray("language_codes").length())
    }

    @Test fun aTakeTooBigToSendInlineIsUploadedOnceAndAskedAboutOnce() {
        val pcm = ByteArray(SpeechModel.INLINE_MAX_BYTES + 1000)
        val calls = ArrayList<Call>()
        SpeechModel.transcribe("k", pcm, names, langs, fake(calls))
        assertEquals(listOf("start", "upload, finalize"), calls.filter { it.url.contains("/upload/") }.map { it.headers.entries.first { e -> e.key.equals("X-Goog-Upload-Command", true) }.value })
        val asks = calls.filter { it.url.endsWith("/v1beta/interactions") }
        assertEquals(1, asks.size)
        val audio = JSONObject(asks[0].body).getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(0)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/files/abc", audio.getString("uri"))
        assertTrue(!audio.has("data"))
    }

    @Test fun aGeneralModelFallbackGetsTheNamesAndTheWholeFile() {
        val pcm = ByteArray(10 * DictationTape.BYTES_PER_SECOND.toInt())
        val calls = ArrayList<Call>()
        val client = fake(calls, """{"output_text":"","candidates":[{"content":{"parts":[{"text":"Letterhead for Strat Edge ID."}]}}]}""")
        assertEquals("Letterhead for Strat Edge ID.", SpeechModel.transcribe("k", pcm, names, langs, client))
        assertEquals(2, calls.size)
        val gen = JSONObject(calls[1].body)
        val prompt = gen.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(prompt.contains("exactly what was said") && prompt.contains("in the language it was said"))
        assertTrue(names.all { prompt.contains(it) })
    }

    @Test fun theModelIsASettingAndAGeneralModelReadsTheWholeFileOnceWithThePrompt() {
        val pcm = ByteArray(5 * DictationTape.BYTES_PER_SECOND.toInt())
        val calls = ArrayList<Call>()
        val client = fake(calls, """{"candidates":[{"content":{"parts":[{"text":"Strat Edge ID."}]}}]}""")
        assertEquals("Strat Edge ID.", SpeechModel.transcribe("k", pcm, names, langs, client, model = "gemini-3.5-flash-lite"))
        assertEquals(1, calls.size)
        assertTrue(calls[0].url.contains("/models/gemini-3.5-flash-lite:generateContent"))
    }
}
