package com.past9.phoneaos.wakeword

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Why the listener is not listening right now, with the line the notification and the sheet show. */
enum class WakePause(val line: String, /** The microphone is let go while paused for this reason. */ val releasesMic: Boolean = true) {
    NO_MIC_PERMISSION("Allow the microphone to listen for it."),
    NO_PHRASE("That phrase is too short to listen for. Open Wake word to change it."),
    ENGINE("The wake word model could not start on this phone."),
    USER("Paused. Tap Resume to listen again."),
    APP_MIC("Paused while you use the microphone in the app."),
    PHONE_CALL("Paused during a call."),
    ONLY_CHARGING("Waiting for the charger."),
    BATTERY_SAVER("Paused while battery saver is on."),
    LOW_BATTERY("Paused: battery under 20%."),
    MIC_TAKEN("Paused while another app uses the microphone."),
    MIC_SILENT("The microphone is giving no sound. Open the app to start it again."),
}

/** Everything outside the settings that decides whether the listener may listen. */
data class WakeWorld(
    val micAllowed: Boolean = true,
    /** The phrase has enough word pieces to listen for (typed or learned). */
    val phraseOk: Boolean = true,
    val engineOk: Boolean = true,
    /** The app's own voice features holding the microphone right now ([MicUse]). */
    val appMic: Set<String> = emptySet(),
    val inCall: Boolean = false,
    val charging: Boolean = false,
    val batterySaver: Boolean = false,
    /** 0 to 100, or -1 when unknown. */
    val batteryPercent: Int = -1,
    /** Some other recorder is running on the phone (another app, or a web page in the app's browser). */
    val otherRecorder: Boolean = false,
    /** The microphone handed over nothing but zeros (Android does that to a listener it has not let in). */
    val micSilent: Boolean = false,
)

sealed interface WakeState {
    /** Switched off: no service, no notification, no microphone. */
    data object Off : WakeState
    data object Listening : WakeState
    data class Paused(val why: WakePause) : WakeState
}

/** The rules, in one pure function so every case can be tested without a phone. */
object ListenRules {
    const val LOW_BATTERY = 20

    /** What the listener should be doing. The first reason that applies wins, most pressing first. */
    fun decide(s: WakeWordSettings, w: WakeWorld): WakeState = when {
        !s.enabled -> WakeState.Off
        !w.micAllowed -> WakeState.Paused(WakePause.NO_MIC_PERMISSION)
        !w.phraseOk -> WakeState.Paused(WakePause.NO_PHRASE)
        !w.engineOk -> WakeState.Paused(WakePause.ENGINE)
        s.userPaused -> WakeState.Paused(WakePause.USER)
        w.appMic.isNotEmpty() -> WakeState.Paused(WakePause.APP_MIC)
        w.inCall -> WakeState.Paused(WakePause.PHONE_CALL)
        s.onlyCharging && !w.charging -> WakeState.Paused(WakePause.ONLY_CHARGING)
        s.pauseOnSaver && w.batterySaver -> WakeState.Paused(WakePause.BATTERY_SAVER)
        // A phone on the charger is not running down, whatever the number says.
        s.pauseLowBattery && !w.charging && w.batteryPercent in 0 until LOW_BATTERY -> WakeState.Paused(WakePause.LOW_BATTERY)
        w.otherRecorder -> WakeState.Paused(WakePause.MIC_TAKEN)
        w.micSilent -> WakeState.Paused(WakePause.MIC_SILENT)
        else -> WakeState.Listening
    }

    /** The two lines of the ongoing notification. The phrase is always the person's own, quoted. */
    fun notice(state: WakeState, phrase: String): Pair<String, String> = when (state) {
        WakeState.Listening -> "Listening for \"$phrase\"" to "Heard on this phone only. Nothing is recorded or sent."
        is WakeState.Paused -> "Not listening for \"$phrase\"" to state.why.line
        WakeState.Off -> "Wake word is off" to ""
    }

    /** The button on the notification: Pause while it could listen, Resume once the person paused it. */
    fun action(state: WakeState): String? = when {
        state is WakeState.Paused && state.why == WakePause.USER -> "Resume"
        state is WakeState.Off -> null
        else -> "Pause"
    }
}

/**
 * The app's own voice features say here when they take the microphone and when they are done with it: the assist
 * sheet, dictation, a voice call, the WhatsApp call voice bridge. The wake word listener lets go the moment one of
 * them holds it ([onHold] runs before [hold] returns) and listens again when the last one releases.
 */
object MicUse {
    private val _held = MutableStateFlow<Set<String>>(emptySet())
    val held: StateFlow<Set<String>> = _held

    /** Set by the listener: let go of the microphone now, on the caller's thread. */
    @Volatile var onHold: (() -> Unit)? = null

    fun hold(who: String) {
        synchronized(this) { _held.value = _held.value + who }
        runCatching { onHold?.invoke() }
    }

    fun release(who: String) { synchronized(this) { _held.value = _held.value - who } }
}
