package com.past9.phoneaos.voice

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** How a dictation stood when a message wanted to leave the box it was writing into. */
enum class Settled {
    /** No dictation for this box: the words in it are his own and go as they are. */
    NOT_DICTATING,
    /** The dictation finished: the box now holds the whole-take text (or the best there is when no whole-take pass can run). */
    FINAL,
    /** The whole-take text did not come (the pass failed or ran out of time): the box holds only the live caption. */
    NO_FINAL,
}

/**
 * Before a message leaves a box that a dictation is writing into: end the dictation if it is still listening, and wait
 * until the whole take has been written down from the saved audio and has replaced the live caption in the box.
 * The caption is only a preview (18 Nov 2026: a message went out as the Android caption while the whole-take pass was
 * still running, and the clean text arrived in the box after it was gone).
 *
 * [stop] ends the listening (the same as his tap on the mic); [retry] tries again a take that ended KEPT.
 * Waits at most [limitMs], a little over the engine's own finishing limit, so it can never hang.
 */
suspend fun settleDictation(
    ui: StateFlow<DictationHub.Ui?>, owner: Any?, stop: () -> Unit, retry: () -> Unit,
    limitMs: Long = DictationEngine.FINISH_MAX_MS + 5_000L,
): Settled {
    fun mine(u: DictationHub.Ui?) = u != null && u.owner == owner
    val first = ui.value
    if (!mine(first) || first!!.view.phase == DictationView.Phase.CANCELLED) return Settled.NOT_DICTATING
    when (first.view.phase) {
        DictationView.Phase.LISTENING, DictationView.Phase.RECONNECTING, DictationView.Phase.PAUSED -> stop()
        DictationView.Phase.KEPT -> retry()
        else -> {}
    }
    val last = withTimeoutOrNull(limitMs) {
        // Not ended yet, or still showing the KEPT we are retrying: keep waiting. The hub clears a DONE take a moment later.
        ui.first { u -> !mine(u) || (u!!.view.ended && !(u.view.phase == DictationView.Phase.KEPT && first.view.phase == DictationView.Phase.KEPT && u === first)) }
    } ?: return Settled.NO_FINAL
    return when {
        !mine(last) -> Settled.FINAL
        last!!.view.phase == DictationView.Phase.DONE -> Settled.FINAL
        else -> Settled.NO_FINAL
    }
}
