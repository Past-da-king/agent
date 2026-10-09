package com.past9.phoneaos.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Voice-to-text for the composer, streamed to Google's Gemini 3.5 Transcribe Live model.
 *
 * It replaces the keyboard's speech recogniser (which mangled South African English mixed with isiZulu).
 * Audio goes up as 16 kHz mono PCM16; text comes back while he is still talking: `interimInputTranscription`
 * is the speculative caption, `inputTranscription` is the committed text for a stretch he has finished.
 * The model takes no prompt, so the steering is a language hint (English-ZA + isiZulu, which it may mix
 * freely) and a custom vocabulary of names the agent hears a lot.
 *
 * [onText] gets the whole transcript so far every time it changes (committed + current caption), so the
 * caller just replaces the dictated part of the draft. [onEnd] fires exactly once; `error` is non-null when it
 * could not run at all, which is the caller's cue to fall back to the device recogniser.
 */
class Dictation(
    private val apiKey: String,
    private val vocabulary: List<String> = emptyList(),
    private val onText: (String) -> Unit,
    private val onEnd: (error: String?) -> Unit,
) {
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private var rec: AudioRecord? = null
    private val running = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)
    private val ended = AtomicBoolean(false)
    private val committed = StringBuilder()
    @Volatile private var interim = ""
    @Volatile private var gotAnything = false
    @Volatile private var hints = true
    @Volatile private var finishing = false

    val listening: Boolean get() = running.get() && !finishing

    fun start() {
        if (running.getAndSet(true)) return
        connect()
    }

    private fun connect() {
        ready.set(false)
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { if (webSocket === ws) webSocket.send(setup().toString()) }
            override fun onMessage(webSocket: WebSocket, text: String) { if (webSocket === ws) handle(text) }
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) { if (webSocket === ws) handle(bytes.utf8()) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== ws) return
                Log.w(TAG, "failed: ${t.message}")
                end(if (gotAnything) null else (t.message ?: "no connection"))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { runCatching { webSocket.close(1000, null) } }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== ws) return
                Log.i(TAG, "closed $code $reason")
                // Refused our language hint or vocabulary: say it again with neither, once.
                if (code == 1007 && hints && !ready.get() && running.get()) { hints = false; connect(); return }
                end(if (gotAnything || finishing) null else "closed $code $reason")
            }
        })
    }

    private fun setup(): JSONObject {
        val tr = JSONObject()
        if (hints) {
            // English as spoken here, with isiZulu mixed through it. Both are listed so the model does not have to guess one.
            tr.put("languageCodes", JSONArray().put("en-ZA").put("zu-ZA"))
            if (vocabulary.isNotEmpty()) tr.put("customVocabulary", JSONArray(vocabulary.take(100)))
        } else tr.put("languageCodes", JSONArray())
        return JSONObject().put("setup", JSONObject()
            .put("model", "models/$MODEL")
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT")))
            .put("inputAudioTranscription", tr))
    }

    private fun handle(raw: String) {
        try {
            val m = JSONObject(raw)
            if (m.has("setupComplete")) { ready.set(true); startMic(); return }
            val sc = m.optJSONObject("serverContent") ?: return
            sc.optJSONObject("interimInputTranscription")?.optString("text")?.let { interim = it; gotAnything = true; push() }
            sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
                gotAnything = true
                if (committed.isNotEmpty() && !committed.last().isWhitespace() && !it.first().isWhitespace()) committed.append(' ')
                committed.append(it); interim = ""; push()
            }
            // After he stops, the model finishes the last stretch and the turn completes: that is our cue to close.
            if (finishing && (sc.optBoolean("turnComplete") || sc.optBoolean("generationComplete"))) end(null)
        } catch (e: Exception) { Log.w(TAG, "bad frame: ${e.message}") }
    }

    private fun push() { onText((committed.toString() + (if (interim.isNotEmpty() && committed.isNotEmpty() && !interim.first().isWhitespace()) " " else "") + interim).trim()) }

    private fun startMic() {
        if (rec != null) return
        try {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            @Suppress("MissingPermission")
            val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE / 5 * 2))
            if (r.state != AudioRecord.STATE_INITIALIZED) { end("microphone unavailable"); return }
            rec = r; r.startRecording()
            thread(name = "aos-dictate", isDaemon = true) {
                val buf = ByteArray(3200) // 100 ms
                while (running.get() && !finishing) {
                    val n = r.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val msg = JSONObject().put("realtimeInput", JSONObject().put("audio",
                        JSONObject().put("data", Base64.encodeToString(buf.copyOf(n), Base64.NO_WRAP)).put("mimeType", "audio/pcm;rate=$RATE")))
                    ws?.send(msg.toString())
                }
            }
        } catch (e: Exception) { end("microphone unavailable: ${e.message}") }
    }

    /** He tapped stop: stop listening, let the model finish the last words, then close. */
    fun finish() {
        if (!running.get() || finishing) return
        finishing = true
        runCatching { rec?.stop(); rec?.release() }; rec = null
        runCatching { ws?.send(JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString()) }
        // Don't wait forever for a turn-complete that may never come.
        thread(isDaemon = true, name = "aos-dictate-end") { Thread.sleep(2500); end(null) }
    }

    /** Throw it away (leaving the screen): nothing more is delivered. */
    fun cancel() { ended.set(true); close() }

    private fun end(error: String?) {
        if (!ended.compareAndSet(false, true)) return
        close()
        onEnd(error)
    }

    private fun close() {
        running.set(false); ready.set(false)
        runCatching { rec?.stop(); rec?.release() }; rec = null
        runCatching { ws?.close(1000, "done") }; ws = null
    }

    companion object {
        private const val TAG = "AosDictate"
        const val MODEL = "gemini-3.5-transcribe-live"
        private const val RATE = 16000
    }
}
