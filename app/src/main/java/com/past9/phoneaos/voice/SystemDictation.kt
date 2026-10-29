package com.past9.phoneaos.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/** Something the composer's mic is running: [finish] = he tapped stop, [cancel] = throw it away. */
interface Dictating {
    fun finish()
    fun cancel()
}

/**
 * Voice-to-text for the composer with the phone's own speech recogniser: the one the keyboard and the assistant use
 * (on most phones Google's), in the phone's language, with live partial results into the message box.
 *
 * It keeps listening across pauses: each stretch he finishes is committed and the recogniser starts again, until he
 * taps stop or stays quiet. [onText] gets the whole transcript so far every time it changes, so the caller just
 * replaces the dictated part of the draft. [onEnd] fires exactly once; `error` is a sentence for the person, or null.
 * Everything runs on the main thread (SpeechRecognizer requires it).
 */
class SystemDictation(
    private val context: Context,
    private val onText: (String) -> Unit,
    private val onEnd: (error: String?) -> Unit,
) : Dictating {
    private val main = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private val committed = StringBuilder()
    private var partial = ""
    private var stopping = false
    private var ended = false
    private var busyRetries = 0
    private var netRetries = 0
    /** Whether this stretch heard anything; a stretch of silence after words means he is done. */
    private var heardThisStretch = false

    fun start() { main.post {
        if (ended) return@post
        val r = runCatching { service(context)?.let { SpeechRecognizer.createSpeechRecognizer(context, it) } ?: SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        if (r == null) { end("This phone's speech recogniser could not start."); return@post }
        r.setRecognitionListener(listener)
        sr = r
        listen()
    } }

    private fun listen() {
        heardThisStretch = false
        runCatching { sr?.startListening(intent(context)) }.onFailure { end("This phone's speech recogniser could not start: ${it.message}") }
    }

    override fun finish() { main.post {
        if (ended || stopping) return@post
        stopping = true
        runCatching { sr?.stopListening() }
        // The last words come as onResults; don't wait forever for them.
        main.postDelayed({ end(null) }, 3_000)
    } }

    override fun cancel() { main.post { ended = true; release() } }

    private fun text(): String = listOf(committed.toString().trim(), partial.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() { heardThisStretch = true }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (ended) return
            val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (p.isBlank()) return
            heardThisStretch = true
            partial = p
            onText(text())
        }

        override fun onResults(results: Bundle?) {
            if (ended) return
            val r = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().ifBlank { partial }
            partial = ""
            if (r.isNotBlank()) { if (committed.isNotEmpty()) committed.append(' '); committed.append(r.trim()) }
            onText(text())
            busyRetries = 0; netRetries = 0
            if (stopping) end(null) else listen()
        }

        override fun onError(error: Int) {
            if (ended) return
            when {
                stopping -> end(null)
                // Silence or a stretch it could not match: he may be thinking. Keep what it showed and listen again; only his
                // tap ends it (29 Oct 2026: it used to end here, cutting him off mid-dictation).
                error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (partial.isNotBlank()) { if (committed.isNotEmpty()) committed.append(' '); committed.append(partial.trim()); partial = ""; onText(text()) }
                    main.postDelayed({ if (!ended) listen() }, 100)
                }
                // Lost its service (mobile data): keep what it showed and try again, backing off, until he taps stop.
                error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT || error == SpeechRecognizer.ERROR_SERVER ||
                    error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> {
                    if (partial.isNotBlank()) { if (committed.isNotEmpty()) committed.append(' '); committed.append(partial.trim()); partial = ""; onText(text()) }
                    netRetries++
                    main.postDelayed({ if (!ended) listen() }, minOf(8_000L, 1_000L shl (netRetries - 1).coerceIn(0, 3)))
                }
                (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) && busyRetries < 3 -> { busyRetries++; runCatching { sr?.cancel() }; main.postDelayed({ if (!ended) listen() }, 400) }
                else -> end(describe(error))
            }
        }
    }

    private fun end(error: String?) {
        if (ended) return
        ended = true
        release()
        onEnd(error)
    }

    private fun release() {
        main.removeCallbacksAndMessages(null)
        runCatching { sr?.cancel() }
        runCatching { sr?.destroy() }
        sr = null
    }

    companion object {
        /** Whether this phone has any speech recogniser at all. */
        fun available(context: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

        /**
         * Which recogniser to use: the phone's own default when it is Google's; Google's when the default is another
         * maker's and Google's is installed (it is the keyboard's and Gemini's); otherwise null, the phone's default.
         */
        fun service(context: Context): ComponentName? {
            val installed = runCatching {
                context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                    .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            }.getOrDefault(emptyList())
            val default = runCatching { Settings.Secure.getString(context.contentResolver, "voice_recognition_service") }.getOrNull()
                ?.let { ComponentName.unflattenFromString(it) }?.takeIf { it in installed }
            return pick(default, installed)
        }

        /** [service]'s choice, apart from Android so it can be tested. */
        fun pick(default: ComponentName?, installed: List<ComponentName>): ComponentName? {
            if (default != null && default.packageName.startsWith("com.google.")) return default
            return GOOGLE_ORDER.firstNotNullOfOrNull { pkg -> installed.firstOrNull { it.packageName == pkg } } ?: default
        }

        /** Google's recognisers, best first: Speech Services by Google, then the Google app. */
        private val GOOGLE_ORDER = listOf("com.google.android.tts", "com.google.android.googlequicksearchbox")

        /** Free-form speech in the phone's own language, live partial results, and Google's best (online) model. */
        fun intent(context: Context, locale: Locale = Locale.getDefault()): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            val tag = locale.toLanguageTag()
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Room to pause and think mid-sentence before a stretch counts as finished.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
        }

        fun describe(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "The app needs the microphone permission to listen."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                "The speech recogniser couldn't reach its service. Check the connection and try again."
            SpeechRecognizer.ERROR_AUDIO -> "The microphone is busy or not working."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech recogniser is busy. Try again in a moment."
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "The speech recogniser doesn't have this phone's language."
            else -> "The speech recogniser stopped (error $error)."
        }
    }
}
