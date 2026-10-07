package com.past9.phoneaos.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
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
 * The voice call, entirely in Kotlin. No WebView, no AudioContext, no browser permission model.
 *
 * This replaces the headless web page that used to run the call. Every bug that cost us a round trip
 * came from that seam: a suspended AudioContext silently dropping every captured sample, getUserMedia
 * refused because the app was backgrounded, the page never opening the gateway connection, the orb not
 * knowing its own call id. None of those failure modes exist here — AudioRecord either opens or throws,
 * and the socket is the same socket the whole time.
 *
 * Wire format is Gemini Live (BidiGenerateContent):
 *   up   — 16 kHz mono PCM16, base64, as realtimeInput.audio
 *   down — 24 kHz mono PCM16, base64, in serverContent.modelTurn.parts[].inlineData.data
 * Those two rates are fixed by the API and are deliberately different; resampling is not needed
 * because each direction gets its own AudioRecord/AudioTrack configured for its own rate.
 */
class LiveSession(
    private val apiKey: String,
    private val model: String,
    private val voice: String,
    private val systemInstruction: String,
    /** BCP-47 speech language. Pinned because his English was transcribed as French/German/Italian. */
    private val language: String = "en-US",
    private val tools: JSONArray?,
    /**
     * DICTATION mode: text out instead of audio, and no voice or tools.
     *
     * Voice notes stream through this same client so the transcript arrives WHILE he speaks rather
     * than after he stops — waiting for a batch upload at the end meant standing there watching a
     * spinner before he could send. Same Live model as the call, so both hear him identically.
     */
    private val dictation: Boolean = false,
    private val onEvent: (Event) -> Unit,
) {
    sealed class Event {
        object Connected : Event()
        object SetupComplete : Event()
        data class Speaking(val on: Boolean) : Event()
        data class Transcript(val who: String, val text: String) : Event()
        /** Where the voice is coming out now — earphones or the loudspeaker. Fires again on a change. */
        data class Route(val route: AudioRoute.Route) : Event()
        /** Loudness 0..1 of his voice (`mine`) or the model's, about ten times a second, for the orb. */
        data class Level(val mine: Boolean, val value: Float) : Event()
        data class ToolCall(val id: String, val name: String, val args: JSONObject) : Event()
        data class Closed(val reason: String) : Event()
        data class Failed(val error: String) : Event()
    }

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // a websocket that is merely quiet is not dead
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private val running = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)
    private val micOpen = AtomicBoolean(false)
    @Volatile private var muted = false

    fun start() {
        if (running.getAndSet(true)) return
        val url = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onEvent(Event.Connected)
                webSocket.send(setupMessage().toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) = handle(bytes.utf8())

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "socket failed: ${t.message}")
                onEvent(Event.Failed(t.message ?: "connection failed"))
                stop()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "socket closed $code $reason")
                onEvent(Event.Closed(reason.ifBlank { "closed" }))
                stop()
            }
        })
    }

    private fun setupMessage(): JSONObject {
        // AUDIO even for dictation. The live model REFUSES text-only:
        //   1007 The requested combination of response modalities (TEXT) is not supported by the
        //   model. models/gemini-3.1-flash-live-preview
        // So the reply comes back as audio and is simply discarded — with playback disabled there is
        // no AudioTrack to write it to. inputAudioTranscription is what we actually came for.
        val generation = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put(
                "speechConfig",
                // Without a language the model guessed his accent: all four 11 Sep sessions had English
                // transcribed as German, French or Italian.
                JSONObject().put("languageCode", language.ifEmpty { "en-US" }).put(
                    "voiceConfig",
                    JSONObject().put(
                        "prebuiltVoiceConfig",
                        JSONObject().put("voiceName", voice.ifEmpty { "Puck" })
                    )
                )
            )
        val setup = JSONObject()
            .put("model", if (model.startsWith("models/")) model else "models/$model")
            .put("generationConfig", generation)
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
            )
            // Transcripts both ways so the call can be written to Voice history like any other session.
            .put("inputAudioTranscription", JSONObject())
            .put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", JSONObject()))
        // Only the call needs to hear ITSELF back; dictation only ever wants his own words.
        if (!dictation) setup.put("outputAudioTranscription", JSONObject())
        if (tools != null && !dictation) setup.put("tools", tools)
        return JSONObject().put("setup", setup)
    }

    private fun handle(raw: String) {
        try {
            val m = JSONObject(raw)
            if (m.has("setupComplete")) {
                ready.set(true)
                onEvent(Event.SetupComplete)
                return
            }
            m.optJSONObject("toolCall")?.let { tc ->
                val calls = tc.optJSONArray("functionCalls") ?: JSONArray()
                for (i in 0 until calls.length()) {
                    val c = calls.optJSONObject(i) ?: continue
                    onEvent(
                        Event.ToolCall(
                            c.optString("id"),
                            c.optString("name"),
                            c.optJSONObject("args") ?: JSONObject()
                        )
                    )
                }
                return
            }
            val sc = m.optJSONObject("serverContent") ?: run {
                if (dictation) Log.d(TAG, "frame(no serverContent): ${raw.take(220)}")
                return
            }
            if (dictation) Log.d(TAG, "serverContent keys: ${sc.keys().asSequence().toList()}")
            // The model was cut off mid-sentence: drop anything still queued or it talks over itself.
            if (sc.optBoolean("interrupted")) {
                runCatching { track?.pause(); track?.flush(); track?.play() }
                onEvent(Event.Speaking(false))
            }
            sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }
                ?.let { onEvent(Event.Transcript("in", it)) }
            sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }
                ?.let { onEvent(Event.Transcript("out", it)) }

            val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null) {
                onEvent(Event.Speaking(true))
                for (i in 0 until parts.length()) {
                    val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data")
                    if (!data.isNullOrEmpty()) {
                        val pcm = Base64.decode(data, Base64.DEFAULT)
                        onEvent(Event.Level(false, loudness(pcm, pcm.size)))
                        track?.write(pcm, 0, pcm.size)
                    }
                }
            }
            if (sc.optBoolean("turnComplete") || sc.optBoolean("generationComplete")) {
                onEvent(Event.Speaking(false))
            }
        } catch (e: Exception) {
            Log.w(TAG, "bad frame: ${e.message}")
        }
    }

    /** Send a text turn as if the operator had said it — used to deliver the agent's briefing. */
    fun announce(text: String) {
        val msg = JSONObject().put(
            "clientContent",
            JSONObject()
                .put(
                    "turns",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", text)))
                    )
                )
                .put("turnComplete", true)
        )
        ws?.send(msg.toString())
    }

    fun sendToolResult(id: String, name: String, result: JSONObject) {
        val response = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("response", result)
        ws?.send(
            JSONObject().put(
                "toolResponse",
                JSONObject().put("functionResponses", JSONArray().put(response))
            ).toString()
        )
    }

    /**
     * Put an image in the model's view — a screenshot a PC tool just took. Sent as one realtime video
     * frame, the same channel the desktop orb's vision mode streams on, right after the tool's reply.
     */
    fun sendImage(base64: String, mimeType: String) {
        ws?.send(
            JSONObject().put(
                "realtimeInput",
                JSONObject().put("video", JSONObject().put("data", base64).put("mimeType", mimeType))
            ).toString()
        )
    }

    /**
     * Stop LISTENING without ending the session — for when he is talking to someone else in the room
     * and does not want the agent answering every sentence. The socket, the voice and any tool still
     * running carry on; only his microphone stops being sent.
     */
    fun setMuted(m: Boolean) {
        if (muted == m) return
        muted = m
        if (m) {
            // Tell the model the stream has paused, or its voice-activity detector sits waiting for
            // the end of a sentence that was cut off mid-word and never replies to what it did hear.
            runCatching {
                ws?.send(JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString())
            }
            onEvent(Event.Level(true, 0f))
        }
    }

    val isMuted: Boolean get() = muted

    /**
     * Open the mic and the speaker and start streaming. Called on ANSWER, never during the ring — the
     * app has no microphone foreground-service type until then, so opening earlier would be refused.
     */
    /**
     * @param playback false for DICTATION — capture only. A voice note has nothing to play back, and
     *   putting the device in communication mode + speakerphone for it would duck his music and
     *   hijack the audio route for what is really just typing.
     */
    fun openAudio(am: AudioManager?, playback: Boolean = true) {
        if (micOpen.getAndSet(true)) return
        if (!playback) {
            startCapture()
            return
        }
        // Communication mode, routed to his EARPHONES when he is wearing any and to the loudspeaker
        // when he is not. Never the earpiece (the default for USAGE_VOICE_COMMUNICATION): this is a
        // hands-free agent, not a phone held to the ear. It used to force the loudspeaker always,
        // which is why it talked out of the phone with earphones in — see AudioRoute.
        if (am != null) {
            router = AudioRouter(am) { onEvent(Event.Route(it)) }.also { it.start() }
        }
        startPlayback()
        startCapture()
    }

    private fun startPlayback() {
        try {
            val min = AudioTrack.getMinBufferSize(
                OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(OUT_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(min, OUT_RATE)) // ~0.5s of slack against jitter
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .apply { play() }
        } catch (e: Exception) {
            Log.w(TAG, "playback failed: ${e.message}")
            onEvent(Event.Failed("speaker unavailable — ${e.message}"))
        }
    }

    private fun startCapture() {
        try {
            val min = AudioRecord.getMinBufferSize(
                IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufBytes = maxOf(min, IN_RATE / 5 * 2) // ~200ms
            @Suppress("MissingPermission")
            val rec = AudioRecord(
                // VOICE_COMMUNICATION gives us the platform's echo canceller, so the model does not
                // hear itself through the speaker and interrupt its own sentences.
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                IN_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufBytes
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                onEvent(Event.Failed("microphone unavailable"))
                return
            }
            recorder = rec
            rec.startRecording()
            thread(name = "aos-mic", isDaemon = true) {
                val buf = ByteArray(CHUNK_BYTES)
                while (running.get() && micOpen.get()) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    if (muted || !ready.get()) continue
                    onEvent(Event.Level(true, loudness(buf, n)))
                    val b64 = Base64.encodeToString(buf.copyOf(n), Base64.NO_WRAP)
                    val msg = JSONObject().put(
                        "realtimeInput",
                        JSONObject().put(
                            "audio",
                            JSONObject()
                                .put("data", b64)
                                .put("mimeType", "audio/pcm;rate=$IN_RATE")
                        )
                    )
                    ws?.send(msg.toString())
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "capture failed: ${e.message}")
            onEvent(Event.Failed("microphone unavailable — ${e.message}"))
        }
    }

    /** Set by openAudio so stop() can put the device back the way it found it. */
    private var router: AudioRouter? = null

    /**
     * End the SPEECH but not the session.
     *
     * Closing the socket the instant he taps stop threw away the tail of the transcript: the model
     * emits the last inputTranscription after it sees the turn end, so hanging up first meant the
     * final words never arrived. Stops capture, marks the stream ended, and leaves the socket open
     * for the caller to close once the transcript has landed.
     */
    fun finishInput() {
        micOpen.set(false)
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { ws?.send(JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString()) }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        router?.stop()
        router = null
        micOpen.set(false)
        ready.set(false)
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        runCatching { ws?.close(1000, "done") }
        ws = null
    }

    companion object {
        private const val TAG = "AosLive"
        private const val IN_RATE = 16000
        private const val OUT_RATE = 24000
        private const val CHUNK_BYTES = 3200 // 100ms of 16k mono PCM16

        /**
         * How loud a chunk of little-endian PCM16 is, 0..1, shaped so ordinary speech lands around
         * the middle. Raw RMS is useless for a visual: conversation sits near 0.03 of full scale and a
         * shout near 0.3, so a linear orb would barely move and then jump. A square root spreads the
         * quiet end out, which is roughly how loudness is heard.
         */
        fun loudness(pcm: ByteArray, n: Int): Float {
            val samples = n / 2
            if (samples <= 0) return 0f
            var sum = 0.0
            var i = 0
            // Every fourth sample is plenty for a meter and keeps this off the audio thread's back.
            var counted = 0
            while (i + 1 < n) {
                val s = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)
                sum += s.toDouble() * s
                counted++
                i += 8
            }
            if (counted == 0) return 0f
            val rms = Math.sqrt(sum / counted) / 32768.0
            return Math.sqrt(rms * 4.0).toFloat().coerceIn(0f, 1f)
        }
    }
}
