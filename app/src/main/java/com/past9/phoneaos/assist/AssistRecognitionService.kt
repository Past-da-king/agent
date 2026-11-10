package com.past9.phoneaos.assist

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.past9.phoneaos.voice.SystemDictation

/**
 * Android asks every digital assistant to name a speech recogniser, and makes it the phone's default when the
 * assistant is picked. This app has no recogniser of its own, so this one passes each request on to the best one
 * the phone already has (Google's when installed, else any other) and hands its answers back unchanged. Other apps
 * that ask the phone's default recogniser to listen keep working exactly as before.
 */
class AssistRecognitionService : RecognitionService() {
    private var sr: SpeechRecognizer? = null

    override fun onStartListening(intent: Intent, callback: Callback) {
        release()
        val target = target(this)
        val r = runCatching { target?.let { SpeechRecognizer.createSpeechRecognizer(this, it) } }.getOrNull()
        if (r == null) { runCatching { callback.error(SpeechRecognizer.ERROR_CLIENT) }; return }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { runCatching { callback.readyForSpeech(params) } }
            override fun onBeginningOfSpeech() { runCatching { callback.beginningOfSpeech() } }
            override fun onRmsChanged(rmsdB: Float) { runCatching { callback.rmsChanged(rmsdB) } }
            override fun onBufferReceived(buffer: ByteArray?) { runCatching { callback.bufferReceived(buffer) } }
            override fun onEndOfSpeech() { runCatching { callback.endOfSpeech() } }
            override fun onError(error: Int) { runCatching { callback.error(error) }; release() }
            override fun onResults(results: Bundle?) { runCatching { callback.results(results ?: Bundle()) }; release() }
            override fun onPartialResults(partialResults: Bundle?) { runCatching { callback.partialResults(partialResults ?: Bundle()) } }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        sr = r
        runCatching { r.startListening(intent) }.onFailure { runCatching { callback.error(SpeechRecognizer.ERROR_CLIENT) }; release() }
    }

    override fun onStopListening(callback: Callback) { runCatching { sr?.stopListening() } }

    override fun onCancel(callback: Callback) { release() }

    override fun onDestroy() { release(); super.onDestroy() }

    private fun release() {
        runCatching { sr?.cancel() }
        runCatching { sr?.destroy() }
        sr = null
    }

    companion object {
        /** The recogniser speech is passed on to: never this app's own. Null when the phone has no other. */
        fun target(context: Context): ComponentName? = SystemDictation.service(context) ?: others(context).firstOrNull()

        fun others(context: Context): List<ComponentName> = runCatching {
            context.packageManager.queryIntentServices(Intent(SERVICE_INTERFACE), 0)
                .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }.filter { it.packageName != context.packageName }
        }.getOrDefault(emptyList())
    }
}
