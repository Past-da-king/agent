package com.past9.phoneaos.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class CallState(
    val active: Boolean = false,
    /** connecting | listening | thinking | speaking | reconnecting | ended | failed */
    val phase: String = "",
    val mine: Float = 0f,
    val theirs: Float = 0f,
    /** Captions, oldest first: "in" is the user, anything else the agent. One entry per stretch of speech. */
    val lines: List<Pair<String, String>> = emptyList(),
    val delegated: String = "",
    /** The agent's latest reply since the call started, shown under "Working on". */
    val reply: String = "",
    val muted: Boolean = false,
    val error: String = "",
    /** The call was refused because the microphone permission is missing: the screen offers to ask for it. */
    val needsMic: Boolean = false,
    /** Where the sound comes out (an [AudioRoute.Kind] name), or "" before the audio is open. */
    val route: String = "",
)

/**
 * The call's own state machine, apart from Android, sockets and audio, so every path can be tested: one call at a
 * time, every event tied to the call it belongs to, and [release] run exactly once however the call ends (End, back,
 * the notification, a refusal, a timeout, a dropped connection).
 */
class CallCore(private val release: () -> Unit) {
    private val _state = MutableStateFlow(CallState())
    val state: StateFlow<CallState> = _state
    @Volatile private var gen = 0

    /** A new call: its number, or null when one is already connecting or live (a second tap changes nothing). */
    @Synchronized fun begin(): Int? {
        if (_state.value.active) return null
        gen++
        _state.value = CallState(active = true, phase = CONNECTING)
        return gen
    }

    /** Whether call [g] is still the one on. */
    fun current(g: Int): Boolean = g == gen && _state.value.active

    /** A change that is not tied to a phase (what it is working on, its last reply, the sound route). */
    fun patch(change: (CallState) -> CallState) = _state.update(change)

    private inline fun on(g: Int, crossinline change: (CallState) -> CallState) = _state.update { if (g == gen && it.active) change(it) else it }

    fun live(g: Int) = on(g) { if (it.phase == CONNECTING || it.phase == RECONNECTING) it.copy(phase = LISTENING) else it }
    fun speaking(g: Int, speaking: Boolean) = on(g) { if (it.phase == CONNECTING) it else it.copy(phase = if (speaking) SPEAKING else LISTENING, theirs = if (speaking) it.theirs else 0f) }
    fun thinking(g: Int) = on(g) { if (it.phase == LISTENING) it.copy(phase = THINKING) else it }
    fun reconnecting(g: Int) = on(g) { if (it.phase == CONNECTING) it else it.copy(phase = RECONNECTING, mine = 0f, theirs = 0f) }
    fun level(g: Int, mine: Boolean, value: Float) = on(g) { if (mine) it.copy(mine = if (it.muted) 0f else value) else it.copy(theirs = value) }
    fun caption(g: Int, who: String, text: String) = on(g) { it.copy(lines = merge(it.lines, who, text)) }
    fun muted(m: Boolean) = _state.update { if (it.active) it.copy(muted = m, mine = if (m) 0f else it.mine) else it }

    /** The call cannot start (no key, no microphone, no network, the computer said no). */
    fun refuse(g: Int, why: String, needsMic: Boolean = false) = finish(g, FAILED, why, needsMic)

    /** Call [g] has sat in [phase] too long. Nothing happens if it has moved on since. */
    fun timedOut(g: Int, phase: String, why: String) {
        if (current(g) && _state.value.phase == phase) finish(g, if (phase == CONNECTING) FAILED else ENDED, why)
    }

    /** The connection went and is not coming back: a failure before the call got going, a cut-off call after. */
    fun dropped(g: Int, why: String) {
        val connecting = _state.value.phase == CONNECTING
        finish(g, if (connecting) FAILED else ENDED, why.ifBlank { if (connecting) NOT_STARTED else CUT_OFF })
    }

    /** The user ended it. */
    fun end() = finish(gen, ENDED, "")

    private fun finish(g: Int, phase: String, error: String, needsMic: Boolean = false) {
        synchronized(this) {
            if (g != gen || !_state.value.active) return
            _state.update { it.copy(active = false, phase = phase, error = error, needsMic = needsMic, mine = 0f, theirs = 0f, muted = false) }
        }
        release()
    }

    companion object {
        const val CONNECTING = "connecting"
        const val LISTENING = "listening"
        const val THINKING = "thinking"
        const val SPEAKING = "speaking"
        const val RECONNECTING = "reconnecting"
        const val ENDED = "ended"
        const val FAILED = "failed"

        const val CONNECT_TIMEOUT_MS = 25_000L
        const val RECONNECT_TIMEOUT_MS = 25_000L

        const val NO_MIC = "Calls need the microphone. Tap Allow microphone. If nothing comes up, turn the microphone on for this app in your phone's Settings, under Apps, then Permissions."
        const val NO_NETWORK = "No internet connection. Check Wi-Fi or mobile data, then try again."
        const val TIMED_OUT = "The call took too long to connect. Check your connection, then try again."
        const val CUT_OFF = "The call was cut off. Check your connection, then call again."
        const val LOST = "The connection dropped and did not come back. Call again when you are online."
        const val NOT_STARTED = "The call could not start. Try again."

        /**
         * Captions as they arrive: a new piece from the same speaker grows that speaker's line in place (never a new
         * line per fragment); a change of speaker starts a new line.
         */
        fun merge(lines: List<Pair<String, String>>, who: String, text: String, keep: Int = 40): List<Pair<String, String>> {
            if (text.isEmpty()) return lines
            val last = lines.lastOrNull()
            return if (last != null && last.first == who) lines.dropLast(1) + (who to (last.second + text))
            else (lines + (who to text.trimStart())).takeLast(keep)
        }

        /** A socket or audio error in plain words. */
        fun plainError(raw: String): String {
            val e = raw.lowercase()
            return when {
                e.isBlank() -> ""
                "unable to resolve host" in e || "no address associated" in e || "unreachable" in e || "failed to connect" in e || "timeout" in e || "timed out" in e || "connection reset" in e || "connection abort" in e || "broken pipe" in e -> NO_NETWORK
                "401" in e || "403" in e || "api key" in e || "api_key" in e || "unauthenticated" in e -> "The Google key was refused. Check it in Settings, Voice, then call again."
                "429" in e || "quota" in e || "resource_exhausted" in e -> "The Google key is out of quota for now. Try again in a while."
                "microphone" in e -> "The microphone is busy or blocked. Close other apps that use it, then try again."
                "speaker" in e -> "The phone's sound could not start. Try again."
                else -> raw.take(200)
            }
        }

        /** The chat's one line for a call: "Voice call · 3 min · 12 messages". */
        fun rowTitle(messages: Int, firstAt: Long, lastAt: Long): String {
            val secs = ((lastAt - firstAt) / 1000).coerceAtLeast(0)
            val took = when { secs < 5 -> ""; secs < 60 -> "$secs sec"; else -> "${(secs + 30) / 60} min" }
            return listOf("Voice call", took, if (messages <= 0) "" else "$messages message${if (messages == 1) "" else "s"}").filter { it.isNotEmpty() }.joinToString(" · ")
        }
    }
}
