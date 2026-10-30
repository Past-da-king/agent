package com.past9.phoneaos.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "AosDictation"

/**
 * The phone's own speech recogniser (Google's on most phones), fed with the app's audio through a pipe
 * (RecognizerIntent.EXTRA_AUDIO_SOURCE, Android 13+) instead of opening the mic itself. So a stretch it drops never
 * loses audio: the engine has every byte on the tape. Main thread only (SpeechRecognizer requires it).
 */
class RecognizerLive(private val context: Context) : LiveWords {
    private var sr: SpeechRecognizer? = null
    private var feeder: Feeder? = null
    private var readEnd: ParcelFileDescriptor? = null
    private var events: LiveEvents? = null

    override fun begin(events: LiveEvents): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        closeStretch()
        val r = sr ?: runCatching {
            SystemDictation.service(context)?.let { SpeechRecognizer.createSpeechRecognizer(context, it) } ?: SpeechRecognizer.createSpeechRecognizer(context)
        }.getOrNull() ?: return false
        sr = r
        val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull() ?: return false
        readEnd = pipe[0]
        feeder = Feeder(ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]))
        this.events = events
        r.setRecognitionListener(listener(events))
        val intent = SystemDictation.intent(context).apply {
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, DictationTape.RATE)
        }
        return runCatching { r.startListening(intent) }.onFailure { Log.w(TAG, "startListening: ${it.message}") }.isSuccess
    }

    override fun feed(pcm: ByteArray) { feeder?.write(pcm) }
    override fun backlog(): Long = feeder?.queued() ?: 0
    override fun end() { feeder?.finish(); runCatching { sr?.stopListening() } }
    override fun cancel() { events = null; runCatching { sr?.cancel() }; closeStretch() }

    /** Done with the recogniser altogether. */
    fun release() { cancel(); runCatching { sr?.destroy() }; sr = null }

    private fun closeStretch() {
        feeder?.abort(); feeder = null
        runCatching { readEnd?.close() }; readEnd = null
    }

    private fun listener(ev: LiveEvents) = object : RecognitionListener {
        private fun mine() = events === ev
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { if (mine()) ev.endOfSpeech() }
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {
            if (!mine()) return
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { ev.partial(it) }
        }
        override fun onResults(results: Bundle?) {
            if (!mine()) return
            events = null; closeStretch()
            ev.result(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
        }
        override fun onError(error: Int) {
            if (!mine()) return
            events = null; closeStretch()
            Log.i(TAG, "recogniser error $error")
            ev.error(kind(error))
        }
    }

    /** Writes the stretch's audio into the pipe on its own thread, so a recogniser that stops reading never blocks the app. */
    private class Feeder(private val out: OutputStream) {
        private val q = LinkedBlockingQueue<ByteArray>()
        private val queued = AtomicLong(0)
        private val dead = AtomicBoolean(false)
        private val t = thread(name = "aos-dictation-feed", isDaemon = true) {
            try {
                while (true) {
                    val b = q.take()
                    if (b.isEmpty()) break
                    out.write(b)
                    queued.addAndGet(-b.size.toLong())
                }
            } catch (_: InterruptedException) {
            } catch (_: IOException) {
            } finally { dead.set(true); runCatching { out.close() } }
        }
        fun write(b: ByteArray) { if (!dead.get()) { queued.addAndGet(b.size.toLong()); q.offer(b) } }
        fun queued(): Long = queued.get()
        fun finish() { q.offer(ByteArray(0)) }
        fun abort() { dead.set(true); t.interrupt(); runCatching { out.close() } }
    }

    companion object {
        fun kind(error: Int): LiveError = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> LiveError.NO_SPEECH
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> LiveError.NETWORK
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> LiveError.BUSY
            else -> LiveError.BROKEN
        }
    }
}

/**
 * The mic, read by the app itself for the whole dictation: 100 ms blocks of 16 kHz PCM16. A read error (the route
 * changed, Bluetooth came or went, the audio server restarted) reopens it instead of ending. Another app taking the
 * mic (a phone call) is reported as silenced, and it carries on by itself when the mic comes back.
 */
class MicCapture(
    private val context: Context,
    private val onPcm: (ByteArray, Int) -> Unit,
    private val onSilenced: (Boolean) -> Unit,
) {
    private val running = AtomicBoolean(false)
    @Volatile private var rec: AudioRecord? = null
    private val am = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var silenced = false
    private val callback: AudioManager.AudioRecordingCallback? = if (Build.VERSION.SDK_INT >= 29) object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
            val id = rec?.audioSessionId ?: return
            val mine = configs?.firstOrNull { it.clientAudioSessionId == id } ?: return
            val s = mine.isClientSilenced
            if (s != silenced) { silenced = s; onSilenced(s) }
        }
    } else null

    fun start() {
        if (running.getAndSet(true)) return
        val cb = callback; if (Build.VERSION.SDK_INT >= 29 && cb != null) runCatching { am.registerAudioRecordingCallback(cb, main) }
        thread(name = "aos-dictation-mic", isDaemon = true) {
            val buf = ByteArray(DictationTape.BLOCK)
            var failures = 0
            while (running.get()) {
                val r = rec ?: open()
                if (r == null) { failures++; if (failures % 10 == 1) Log.w(TAG, "mic would not open ($failures)"); Thread.sleep(300); continue }
                val n = r.read(buf, 0, buf.size)
                if (n > 0) { failures = 0; onPcm(buf.copyOf(n), n); continue }
                if (!running.get()) break
                Log.w(TAG, "mic read $n: reopening")
                runCatching { r.stop() }; runCatching { r.release() }; rec = null
                Thread.sleep(150)
            }
            rec?.let { runCatching { it.stop() }; runCatching { it.release() } }; rec = null
        }
    }

    @Suppress("MissingPermission")
    private fun open(): AudioRecord? = runCatching {
        val min = AudioRecord.getMinBufferSize(DictationTape.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, DictationTape.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, DictationTape.BYTES_PER_SECOND))
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return null }
        r.startRecording()
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { r.release(); return null }
        rec = r
        r
    }.getOrNull()

    fun stop() {
        running.set(false)
        val cb = callback; if (Build.VERSION.SDK_INT >= 29 && cb != null) runCatching { am.unregisterAudioRecordingCallback(cb) }
    }
}

/**
 * Writes a piece of saved audio down: through the linked computer (voice.transcribe, its own Google key) or, when the
 * phone is not linked, with this phone's own Gemini key. Throws when neither can be reached right now, which the
 * engine answers with a retry.
 */
object PieceTranscriber {
    private val http by lazy { okhttp3.OkHttpClient.Builder().callTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build() }

    fun available(context: Context): Boolean =
        com.past9.phoneaos.remote.RemoteHub.session.value != null || runCatching { com.past9.phoneaos.App.graph(context).settings.voiceKey("gemini") }.getOrNull() != null

    suspend fun transcribe(context: Context, pcm: ByteArray): String {
        val linked = com.past9.phoneaos.remote.RemoteHub.session.value
        if (linked != null) {
            val r = linked.call("voice.transcribe", listOf(JSONObject().put("\$b64", Base64.encodeToString(pcm, Base64.NO_WRAP))),
                DictationEngine.pieceLimitMs(pcm.size.toLong()))
            val o = r as? JSONObject ?: throw IOException("Your computer didn't answer.")
            if (!o.optBoolean("ok")) throw IOException(o.optString("error").ifBlank { "Your computer couldn't write that down." })
            return o.optString("text")
        }
        val key = com.past9.phoneaos.App.graph(context).settings.voiceKey("gemini") ?: throw IOException("Not linked to a computer, and no Gemini key on this phone.")
        return withContext(Dispatchers.IO) { gemini(key, pcm) }
    }

    private fun gemini(key: String, pcm: ByteArray): String {
        val wav = DictationTape.header(pcm.size.toLong()) + pcm
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
            .put(JSONObject().put("text", "Write down exactly what is said in this recording: South African English, with isiZulu mixed in. Only the words, no notes."))
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav").put("data", Base64.encodeToString(wav, Base64.NO_WRAP)))))))
        var last: Exception? = null
        for (model in listOf("gemini-3.5-flash-lite", "gemini-2.5-flash")) {
            val req = okhttp3.Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", key).post(body.toString().toRequestBody("application/json".toMediaType())).build()
            try {
                http.newCall(req).execute().use { res ->
                    val text = res.body?.string().orEmpty()
                    if (res.code == 404) { last = IOException("no $model"); return@use }
                    if (!res.isSuccessful) throw IOException("Gemini ${res.code}")
                    val parts = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    return (0 until (parts?.length() ?: 0)).joinToString("") { parts!!.optJSONObject(it)?.optString("text").orEmpty() }.trim()
                }
            } catch (e: IOException) { last = e }
        }
        throw last ?: IOException("Gemini couldn't write that down.")
    }

}

/**
 * The dictation running now, for the whole app: the mic, the tape, the engine and what the message box shows.
 * Lives outside the activity, so the screen turning off or the activity being rebuilt does not end it.
 */
object DictationHub {
    data class Ui(val owner: Any?, val view: DictationView)

    private val _ui = MutableStateFlow<Ui?>(null)
    val ui: StateFlow<Ui?> = _ui

    private var scope: CoroutineScope? = null
    private var engine: DictationEngine? = null
    private var mic: MicCapture? = null
    private var recogniser: RecognizerLive? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var appContext: Context? = null
    private var owner: Any? = null
    private var onText: ((String) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private var currentId: String? = null
    private var currentStarted = 0L
    private var currentChat = ""
    private val interrupted = AtomicBoolean(false)

    private fun noteState(v: DictationView): String = when (v.phase) {
        DictationView.Phase.DONE -> "done"
        DictationView.Phase.KEPT -> "kept"
        DictationView.Phase.CANCELLED -> "cancelled"
        else -> "recording"
    }

    /** The process just started: a dictation still marked as recording was cut off when the last one ended. */
    fun onProcessStart(context: Context) {
        if (Dictations.of(context).closeInterrupted().isNotEmpty()) interrupted.set(true)
    }

    /** Whether there is a cut-off dictation still to tell him about. Once. */
    fun takeInterrupted(): Boolean = interrupted.getAndSet(false)

    val active: Boolean get() = _ui.value?.view?.let { !it.ended } == true

    /** Whether the new dictation can run on this phone (Android 13+ for our own audio into the recogniser, or a way to write pieces down). */
    fun supported(context: Context): Boolean =
        (Build.VERSION.SDK_INT >= 33 && SystemDictation.available(context)) || PieceTranscriber.available(context)

    /** Start listening into [owner]'s box. [onText] gets the whole dictated text every time it changes. Main thread. */
    fun start(context: Context, owner: Any?, chat: String = "", onText: (String) -> Unit): Boolean {
        if (active) return false
        release()
        val ctx = context.applicationContext
        appContext = ctx
        this.owner = owner; this.onText = onText
        val dir = File(ctx.filesDir, "dictation").apply { mkdirs() }
        val saved = Dictations.of(ctx)
        saved.prune()
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val tape = runCatching { DictationTape(File(dir, "$stamp.wav")) }.getOrNull() ?: return false
        // The words beside the audio, as they arrive (voice/Recordings.kt).
        val id = tape.file.name.removeSuffix(".wav"); val startedAt = System.currentTimeMillis()
        saved.note(id, startedAt, 0, "", "recording", chat)
        currentId = id; currentStarted = startedAt; currentChat = chat
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        val live = if (Build.VERSION.SDK_INT >= 33 && SystemDictation.available(ctx)) RecognizerLive(ctx) else null
        recogniser = live
        val transcribe: (suspend (ByteArray) -> String)? = if (PieceTranscriber.available(ctx) || com.past9.phoneaos.remote.RemoteHub.session.value != null)
            ({ pcm: ByteArray -> PieceTranscriber.transcribe(ctx, pcm) }) else null
        val e = DictationEngine(s, { android.os.SystemClock.elapsedRealtime() }, tape, live, transcribe,
            onView = { v ->
                _ui.value = Ui(this.owner, v); this.onText?.invoke(v.text)
                saved.note(id, startedAt, v.elapsedMs, v.text, noteState(v), chat, flush = v.ended)
                if (v.ended) ended(v)
            },
            log = { Log.i(TAG, it) })
        engine = e
        mic = MicCapture(ctx, onPcm = { b, n -> tape.append(b, n); main.post { engine?.audio(b) } }, onSilenced = { on -> main.post { engine?.micSilenced(on) } }).also { it.start() }
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        netCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Now, and again a little later for the link to the computer to come back up.
                main.post { engine?.networkBack() }
                main.postDelayed({ engine?.networkBack() }, 4_000)
            }
        }.also { cb -> runCatching { cm.registerDefaultNetworkCallback(cb) } }
        runCatching { DictationService.start(ctx) }
        Log.i(TAG, "dictation started: live=${live != null} pieces=${transcribe != null} tape=${tape.file.name}")
        e.start()
        return true
    }

    /** He tapped stop. */
    fun stop() { mic?.stop(); mic = null; engine?.finish() }

    /** Throw it away. */
    fun cancel() {
        mic?.stop(); mic = null
        engine?.cancel()
        appContext?.let { c -> currentId?.let { id -> Dictations.of(c).note(id, currentStarted, _ui.value?.view?.elapsedMs ?: 0, _ui.value?.view?.text.orEmpty(), "cancelled", currentChat, flush = true) } }
        _ui.value = null
        release()
    }

    /** Try the parts it could not write down again, from the saved audio. */
    fun retry() {
        val e = engine ?: return
        if (_ui.value?.view?.phase != DictationView.Phase.KEPT) return
        runCatching { appContext?.let { DictationService.start(it) } }
        e.retry()
    }

    /** The status row was dismissed (after a KEPT dictation): the tape stays on the phone. */
    fun dismiss() { if (!active) { _ui.value = null; release() } }

    private fun ended(v: DictationView) {
        Log.i(TAG, "dictation ended: ${v.phase} ${v.text.length} chars, lost ${v.lostMs} ms")
        com.past9.phoneaos.remote.RemoteHub.session.value?.pushDictations()
        appContext?.let { runCatching { DictationService.stop(it) } }
        mic?.stop(); mic = null
        netCallback?.let { cb -> runCatching { appContext?.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }; netCallback = null
        recogniser?.release(); recogniser = null
        // A finished dictation clears its status row a moment later; a KEPT one stays until he retries or sends.
        if (v.phase == DictationView.Phase.DONE) main.postDelayed({ if (_ui.value?.view?.phase == DictationView.Phase.DONE) { _ui.value = null; release() } }, 1_500)
    }

    private fun release() {
        netCallback?.let { cb -> runCatching { appContext?.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }; netCallback = null
        recogniser?.release(); recogniser = null
        engine?.release(); engine = null
        scope?.cancel(); scope = null
        onText = null
    }

    /** What the status row says. Plain words; null = nothing to show. */
    fun line(v: DictationView): String? = when (v.phase) {
        DictationView.Phase.LISTENING -> if (v.pendingMs > 0) "Listening. Writing down ${secs(v.pendingMs)} it missed..." else if (v.live) "Listening" else "Listening. Your words appear every few seconds."
        DictationView.Phase.RECONNECTING -> "Reconnecting... still recording, nothing is lost"
        DictationView.Phase.PAUSED -> "Paused: another app has the mic. It carries on by itself."
        DictationView.Phase.FINISHING -> if (v.pendingMs > 0) "Writing down the last ${secs(v.pendingMs)}..." else "Finishing..."
        DictationView.Phase.KEPT -> "Couldn't write down ${secs(v.lostMs)} of it. The audio is saved on this phone."
        DictationView.Phase.DONE, DictationView.Phase.CANCELLED -> null
    }

    private fun secs(ms: Long): String { val s = (ms + 500) / 1000; return if (s >= 60) "${s / 60}:${(s % 60).toString().padStart(2, '0')}" else "$s s" }
}
