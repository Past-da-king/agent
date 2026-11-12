package com.past9.phoneaos.wear

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService

/**
 * What lets the watch list this app under Settings > Apps > Choose default apps > Digital assistant app (and so lets a
 * long press of the Home key reach it). When the system calls the assistant, the session starts the app's own talk
 * screen at once and gets out of the way: the app draws everything, and no audio goes through the system.
 */
class WatchAssistService : VoiceInteractionService()

class WatchAssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = WatchAssistSession(this)
}

class WatchAssistSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        runCatching {
            startAssistantActivity(Intent(context, MainActivity::class.java).putExtra("go", "talk").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }
}

/** An assistant must name a recogniser; this one hands nothing back, the app records and writes things down itself. */
class WatchRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) { runCatching { listener?.error(android.speech.SpeechRecognizer.ERROR_CLIENT) } }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
