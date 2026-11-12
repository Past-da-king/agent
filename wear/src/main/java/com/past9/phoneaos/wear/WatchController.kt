package com.past9.phoneaos.wear

import android.content.Context
import com.past9.phoneaos.wearproto.Ack
import com.past9.phoneaos.wearproto.Answer
import com.past9.phoneaos.wearproto.Ask
import com.past9.phoneaos.wearproto.Heard
import com.past9.phoneaos.wearproto.Phase
import com.past9.phoneaos.wearproto.TurnView
import com.past9.phoneaos.wearproto.Via
import com.past9.phoneaos.wearproto.WearPaths
import com.past9.phoneaos.wearproto.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Everything the watch does, in one place the screens call. It holds no key and no model: every request goes to the phone
 * ([PhoneLink]) and every picture comes back from it ([WatchStore]).
 */
class WatchController(private val app: Context, val link: PhoneLink = PhoneLink(app)) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val state = WatchStore.state

    /** What he asked just now, so the answer screen can say "Sending" before the phone has answered. */
    data class Asked(val id: String, val text: String, val onPhone: Boolean = false)
    private val _asked = MutableStateFlow<Asked?>(null)
    val asked: StateFlow<Asked?> = _asked

    /** One short line when something went wrong (shown on the talk and answer screens). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice
    fun clearNotice() { _notice.value = null }

    enum class MicPhase { IDLE, LISTENING, SENDING }
    private val _micPhase = MutableStateFlow(MicPhase.IDLE)
    val micPhase: StateFlow<MicPhase> = _micPhase
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level
    private val _micDeadAt = MutableStateFlow(WatchStore.micDeadAt(app))
    val micDeadAt: StateFlow<Long> = _micDeadAt
    private var recorder: MicRecorder? = null

    /** "ok" when the phone answered lately, "none" when it cannot be reached. */
    private val _reach = MutableStateFlow("ok")
    val reach: StateFlow<String> = _reach

    private val acks = MutableSharedFlow<Ack>(replay = 8, extraBufferCapacity = 8)
    private val heards = MutableSharedFlow<Heard>(replay = 4, extraBufferCapacity = 4)

    fun onAck(a: Ack) { acks.tryEmit(a) }
    fun onHeard(h: Heard) { heards.tryEmit(h) }

    /** The watch opened: ask the phone for a fresh picture and read the last one it left. */
    fun opened() {
        scope.launch {
            WatchStore.cached(app)
            link.readRing()?.let { WatchRing.onRing(app, it) }
            val sent = link.send(WearPaths.HELLO)
            _reach.value = if (sent) "ok" else "none"
            link.readState()?.let { WatchStore.update(app, it) }
        }
    }

    private suspend fun sendForAck(id: String, path: String, data: ByteArray): Ack {
        val sent = link.send(path, data)
        if (!sent) { _reach.value = "none"; return Ack(id, false, "none") }
        val ack = withTimeoutOrNull(8_000) { acks.first { it.id == id } }
        _reach.value = if (ack == null) "none" else "ok"
        return ack ?: Ack(id, false, "none")
    }

    /** Send words to the agent. True when the phone took them. */
    suspend fun ask(text: String, via: String): Boolean {
        val clean = text.trim(); if (clean.isEmpty()) return false
        val id = UUID.randomUUID().toString()
        _asked.value = Asked(id, clean); _notice.value = null
        val ack = sendForAck(id, WearPaths.ASK, Wire.ask(Ask(id, clean, via)))
        if (!ack.ok) { _notice.value = reasonText(ack.reason); _asked.value = null }
        return ack.ok
    }

    suspend fun answer(card: Long, option: String): Boolean {
        val ack = sendForAck("card:$card", WearPaths.ANSWER, Wire.answer(Answer(card, option)))
        if (!ack.ok) _notice.value = reasonText(ack.reason)
        return ack.ok
    }

    fun stop() { scope.launch { link.send(WearPaths.STOP) } }

    /** The phone's own assist sheet listens; its words go to the agent and the answer comes back here. */
    suspend fun speakOnPhone(): Boolean {
        _asked.value = Asked("", "", onPhone = true); _notice.value = null
        val ack = sendForAck("speak", WearPaths.SPEAK_ON_PHONE, ByteArray(0))
        if (!ack.ok) { _notice.value = reasonText(ack.reason); _asked.value = null }
        return ack.ok
    }

    sealed interface Listen {
        data class Sent(val text: String) : Listen
        data class NoMic(val verdict: MicPolicy.Verdict) : Listen
        data object NoSpeech : Listen
        data class Failed(val reason: String) : Listen
    }

    /** Record on the watch, send the take to the phone to be written down, and wait for what it heard. */
    suspend fun listen(): Listen {
        val pre = MicRecorder.preflight(app)
        if (pre != MicPolicy.Verdict.OK) {
            if (pre == MicPolicy.Verdict.NO_HARDWARE) markMic(true)
            return Listen.NoMic(pre)
        }
        val rec = MicRecorder().also { recorder = it }
        _micPhase.value = MicPhase.LISTENING; _level.value = 0f
        val take = try { rec.record { _level.value = it } } finally { recorder = null }
        if (take.error != null && take.pcm.isEmpty()) { _micPhase.value = MicPhase.IDLE; markMic(true); return Listen.NoMic(MicPolicy.Verdict.FAILED) }
        if (MicPolicy.judge(take.peaks) == MicPolicy.Verdict.SILENT) { _micPhase.value = MicPhase.IDLE; markMic(true); return Listen.NoMic(MicPolicy.Verdict.SILENT) }
        markMic(false)
        if (!MicPolicy.heardSpeech(take.peaks)) { _micPhase.value = MicPhase.IDLE; return Listen.NoSpeech }
        _micPhase.value = MicPhase.SENDING
        val id = UUID.randomUUID().toString()
        _asked.value = Asked("voice-$id", "…"); _notice.value = null
        try {
            if (!link.sendAudio(id, take.pcm)) { _reach.value = "none"; _asked.value = null; return Listen.Failed(reasonText("none")) }
            val h = withTimeoutOrNull(25_000) { heards.first { it.id == id } }
            if (h == null) { _asked.value = null; return Listen.Failed(reasonText("none")) }
            if (!h.ok) { _asked.value = null; return Listen.Failed(h.reason.ifBlank { reasonText("none") }) }
            _asked.value = Asked(h.id, h.text)
            return Listen.Sent(h.text)
        } finally { _micPhase.value = MicPhase.IDLE }
    }

    fun stopListening() { recorder?.requestStop() }

    /** A dead mic is remembered so the talk screen puts the fallbacks first; he can try it again. */
    private fun markMic(dead: Boolean) { WatchStore.setMicDead(app, dead); _micDeadAt.value = WatchStore.micDeadAt(app) }
    fun tryMicAgain() { markMic(false) }

    private fun reasonText(reason: String): String = when (reason) {
        "none", "" -> app.getString(R.string.phone_unreachable)
        else -> reason
    }

    /**
     * What the answer screen shows: the phone's turn once it matches what was just asked, else a local "sending". A new
     * ask never shows the previous answer.
     */
    companion object {
        val VIAS = Via

        fun turnFor(state: com.past9.phoneaos.wearproto.WatchState?, asked: Asked?): TurnView? {
            if (asked == null) return state?.turn
            val t = state?.turn
            if (asked.onPhone) return if (t != null && t.heard.isNotBlank() && t.heard != "…") t else TurnView(Phase.SENDING)
            return if (t != null && t.heard == asked.text) t else TurnView(Phase.SENDING, heard = asked.text)
        }
    }
}
