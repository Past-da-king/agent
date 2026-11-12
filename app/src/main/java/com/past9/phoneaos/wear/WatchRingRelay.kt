package com.past9.phoneaos.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.past9.phoneaos.wakeup.Ringing
import com.past9.phoneaos.wakeup.WakeHub
import com.past9.phoneaos.wakeup.WakeRules
import com.past9.phoneaos.wearproto.RelayResult
import com.past9.phoneaos.wearproto.RingAnswer
import com.past9.phoneaos.wearproto.RingItem
import com.past9.phoneaos.wearproto.RingKind
import com.past9.phoneaos.wearproto.RingPaths
import com.past9.phoneaos.wearproto.RingRules
import com.past9.phoneaos.wearproto.RingWire

/**
 * The agent rings the watch (docs/wear.md, "Ring"). The phone's [WakeHub] stays the one place that decides what rings and the one
 * place that hears an answer, so an answer on any device stops them all: this relay only publishes what rings now (as one data item,
 * replaced each time; an empty list means nothing rings) and passes the watch's answer to the same [WakeHub.ack] / [WakeHub.snooze]
 * the phone's own buttons use. An approval that waits reaches the watch in the ordinary state item ([WatchStateBuilder.pending]).
 */
object WatchRingRelay {
    private const val TAG = "WatchRing"
    @Volatile private var lastSent: ByteArray? = null

    /** Wakes that ring on the phone now, as the watch is told of them. */
    fun items(list: List<Ringing>): List<RingItem> =
        list.map { r -> RingItem(r.wake.id, RingKind.WAKE, r.wake.severity, WakeRules.shownTitle(r.wake), r.wake.whatToDo, r.sound, r.wake.link) }

    /** Publish again even when nothing changed (a watch just opened and may have missed the last item). */
    fun republish() { lastSent = null }

    fun push(context: Context, list: List<Ringing>) {
        val bytes = RingWire.items(items(list))
        if (lastSent?.contentEquals(bytes) == true) return
        lastSent = bytes
        // Urgent: a ring must reach the wrist at once, and an empty list must stop one at once.
        val req = PutDataRequest.create(RingPaths.RING).setData(bytes).setUrgent()
        runCatching { Wearable.getDataClient(context).putDataItem(req) }.onFailure { Log.w(TAG, "put failed: ${it.message}") }
    }

    /** The watch's answer: only something that rings now, only ack or snooze, through the phone's own paths. */
    fun answer(context: Context, id: String, what: String): RelayResult {
        val ringing = WakeHub.ringing.value.map { it.wake.id }.toSet()
        val check = RingRules.check(ringing, id, what)
        if (!check.ok) return check
        if (what == RingAnswer.SNOOZE) WakeHub.snooze(context, id) else WakeHub.ack(context, id)
        return RelayResult(true)
    }
}
