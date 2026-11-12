package com.past9.phoneaos.wear

import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.past9.phoneaos.wearproto.CallPaths
import com.past9.phoneaos.wearproto.CallWire
import com.past9.phoneaos.wearproto.PairLog
import com.past9.phoneaos.wearproto.PairPaths
import com.past9.phoneaos.wearproto.PairReason
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import com.past9.phoneaos.wearproto.RingPaths
import com.past9.phoneaos.wearproto.RingWire
import com.past9.phoneaos.wearproto.WearPaths
import com.past9.phoneaos.wearproto.Wire

/**
 * What the phone sends the watch: the picture and the rings (data items, kept by the system until the watch reads them), the replies,
 * the call's plan, state and flush (messages), and the agent's voice during a call (a channel the phone opens).
 */
class WearListener : WearableListenerService() {
    // Whether the system starts this service at all is the first thing to read in the log when pairing does nothing.
    override fun onCreate() { super.onCreate(); PairLog.i("watch: WearListener started") }

    override fun onDataChanged(events: DataEventBuffer) {
        for (e in events) {
            val path = e.dataItem.uri.path
            // The phone's "this watch is paired with me" note: read even when the watch is not paired yet (that is the point of it).
            if (path == PairPaths.PAIRED) {
                if (e.type == DataEvent.TYPE_CHANGED) {
                    val host = e.dataItem.uri.host; val data = e.dataItem.data
                    runBlocking { withTimeoutOrNull(8_000) { WatchPairingState.onPairedNote(applicationContext, host, data) } }
                }
                continue
            }
            // Only what the paired phone put there is believed (the item's host is the node that wrote it).
            if (e.dataItem.uri.host != WatchPairingState.phone(applicationContext)) continue
            when {
                path == PairPaths.SETTINGS -> WatchCustomisationState.onItem(applicationContext, e.dataItem.uri.host, if (e.type == DataEvent.TYPE_CHANGED) e.dataItem.data else null)
                e.type == DataEvent.TYPE_CHANGED && path == WearPaths.STATE -> Wire.parseState(e.dataItem.data)?.let { WatchStore.update(applicationContext, it) }
                // An empty list is a real answer (nothing rings); a deleted item is the same.
                path == RingPaths.RING -> WatchRing.onRing(applicationContext, if (e.type == DataEvent.TYPE_CHANGED) RingWire.parseItems(e.dataItem.data).orEmpty() else emptyList())
            }
        }
    }

    override fun onMessageReceived(e: MessageEvent) {
        val app = applicationContext as WatchApp
        if (e.path.startsWith("/agent/pair/")) PairLog.i("watch: WearListener message ${e.path} from ${PairLog.node(e.sourceNodeId)}")
        // Pairing first: a phone asks to pair, or the paired phone unpairs. Everything else counts only from the paired phone.
        // The request is answered here, from the offer kept on disk, whether or not the app is in front. This callback runs on a worker
        // thread, so it waits for the answer to go out (the system may stop the process as soon as the service returns).
        when (e.path) {
            PairPaths.REQUEST -> { runBlocking { withTimeoutOrNull(10_000) { WatchPairingState.onRequest(applicationContext, e.sourceNodeId, e.data) } ?: PairLog.i("watch: onRequest timed out") }; return }
            PairPaths.UNPAIR -> { WatchPairingState.onUnpair(applicationContext, e.sourceNodeId); return }
        }
        val c = app.controller
        if (e.sourceNodeId != WatchPairingState.phone(applicationContext)) return
        when (e.path) {
            // The phone says it does not know this watch (it was unpaired there): the watch goes back to its pairing screen.
            WearPaths.ACK -> Wire.parseAck(e.data)?.let {
                if (it.reason == PairReason.NOT_PAIRED) WatchPairingState.onUnpair(applicationContext, e.sourceNodeId) else { c.onAck(it); app.call.onAck(it) }
            }
            WearPaths.HEARD -> Wire.parseHeard(e.data)?.let(c::onHeard)
            CallPaths.PLAN -> CallWire.parsePlan(e.data)?.let(app.call::onPlan)
            CallPaths.STATE -> CallWire.parseView(e.data)?.let(app.call::onPhone)
            CallPaths.FLUSH -> CallWire.parseId(e.data)?.let(app.call::onFlush)
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path.startsWith(CallPaths.SPK_CHANNEL + "/")) (applicationContext as WatchApp).call.onSpeakerChannel(channel)
    }
}
