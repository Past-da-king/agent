package com.past9.phoneaos.voice

import android.content.Context
import android.media.MediaPlayer
import com.past9.phoneaos.agent.sharedHttp
import com.past9.phoneaos.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

data class VoiceChoice(val id: String, val label: String)

/**
 * Text to speech with whichever key the user has: Google Gemini, OpenAI or ElevenLabs. Falls back
 * to the phone's own voice. Returns an audio file so the same sound plays as a reply or a voice note.
 */
object Tts {
    val voices = mapOf(
        "gemini" to listOf("Kore", "Puck", "Aoede", "Charon", "Leda", "Fenrir", "Zephyr", "Orus").map { VoiceChoice(it, it) },
        "openai" to listOf("alloy", "nova", "shimmer", "echo", "onyx", "sage", "coral", "verse").map { VoiceChoice(it, it.replaceFirstChar { c -> c.uppercase() }) },
        "elevenlabs" to listOf("21m00Tcm4TlvDq8ikWAM" to "Rachel", "EXAVITQu4vr4xnSDxMaL" to "Bella", "pNInz6obpgDQGcFmaJgB" to "Adam", "ErXwobaYiN019PkySvjV" to "Antoni").map { VoiceChoice(it.first, it.second) },
    )

    suspend fun synth(context: Context, s: SettingsStore, text: String): File? = withContext(Dispatchers.IO) {
        val st = s.state.value
        val clean = stripEmoji(text).replace(Regex("[*_#`>]"), "").replace(Regex("\\[\\[([^\\]|]+)(?:\\|[^\\]]*)?]]"), "$1").take(4000)
        val out = File(context.cacheDir, "voice/${System.currentTimeMillis()}").apply { parentFile?.mkdirs() }
        runCatching {
            when (st.ttsProvider) {
                "gemini" -> gemini(s.voiceKey("gemini") ?: return@runCatching null, clean, st.ttsVoice.ifBlank { "Kore" }, File(out.path + ".wav"))
                "openai" -> openai(s.voiceKey("openai") ?: return@runCatching null, clean, st.ttsVoice.ifBlank { "nova" }, File(out.path + ".mp3"))
                "elevenlabs" -> eleven(s.voiceKey("elevenlabs") ?: return@runCatching null, clean, st.ttsVoice.ifBlank { "21m00Tcm4TlvDq8ikWAM" }, File(out.path + ".mp3"))
                else -> null
            }
        }.getOrNull()
    }

    private fun post(url: String, headers: Map<String, String>, body: JSONObject): ByteArray {
        val req = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType())).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        sharedHttp.newCall(req).execute().use { r -> check(r.isSuccessful) { "TTS ${r.code}: ${r.body?.string()?.take(200)}" }; return r.body!!.bytes() }
    }

    /**
     * Plain text only: Gemini TTS reads any "Say this:" preamble aloud, and the older preview model
     * sometimes answered the text instead of reading it (the clipped "What do you need?").
     * Free models are tried newest first; each project's quota is per model.
     */
    private fun gemini(key: String, text: String, voice: String, f: File): File {
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))))
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
        var last: Exception? = null
        for (model in listOf("gemini-3.8-flash-tts", "gemini-3.1-flash-tts-preview", "gemini-2.5-flash-preview-tts")) {
            try {
                val res = JSONObject(String(post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key", emptyMap(), body)))
                val b64 = res.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData").getString("data")
                var audio = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                // Some models send a whole WAV, others raw 24 kHz PCM: keep only the samples.
                if (audio.size > 44 && String(audio, 0, 4) == "RIFF") audio = audio.copyOfRange(44, audio.size)
                writeWav(f, audio, 24000)
                return f
            } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("No Gemini voice model answered")
    }

    private fun openai(key: String, text: String, voice: String, f: File): File {
        f.writeBytes(post("https://api.openai.com/v1/audio/speech", mapOf("Authorization" to "Bearer $key"),
            JSONObject().put("model", "gpt-4o-mini-tts").put("voice", voice).put("input", text).put("response_format", "mp3")))
        return f
    }

    private fun eleven(key: String, text: String, voice: String, f: File): File {
        f.writeBytes(post("https://api.elevenlabs.io/v1/text-to-speech/$voice", mapOf("xi-api-key" to key, "Accept" to "audio/mpeg"),
            JSONObject().put("text", text).put("model_id", "eleven_flash_v2_5")))
        return f
    }

    private val emoji = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]")
    fun stripEmoji(s: String) = emoji.replace(s, "").replace(Regex(" {2,}"), " ").trim()

    /** Raw 16-bit mono PCM -> a playable .wav. */
    fun writeWav(f: File, pcm: ByteArray, rate: Int) {
        RandomAccessFile(f, "rw").use { w ->
            fun i32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
            fun i16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
            w.write("RIFF".toByteArray()); w.write(i32(36 + pcm.size)); w.write("WAVEfmt ".toByteArray()); w.write(i32(16)); w.write(i16(1)); w.write(i16(1))
            w.write(i32(rate)); w.write(i32(rate * 2)); w.write(i16(2)); w.write(i16(16)); w.write("data".toByteArray()); w.write(i32(pcm.size)); w.write(pcm)
        }
    }

    private var player: MediaPlayer? = null
    fun play(f: File, onDone: () -> Unit = {}) {
        stop()
        player = MediaPlayer().apply { setDataSource(f.path); setOnCompletionListener { onDone() }; prepare(); start() }
    }
    fun stop() { runCatching { player?.stop(); player?.release() }; player = null }
    val playing: Boolean get() = runCatching { player?.isPlaying == true }.getOrDefault(false)
}
