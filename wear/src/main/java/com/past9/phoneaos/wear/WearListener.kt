package com.past9.phoneaos.wear

import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.past9.phoneaos.wearproto.CallPaths
import com.past9.phoneaos.wearproto.CallWire
import com.past9.phoneaos.wearproto.PairPaths
import com.past9.phoneaos.wearproto.PairReason
import kotlinx.coroutines.launch
import com.past9.phoneaos.wearproto.RingPaths
import com.past9.phoneaos.wearproto.RingWire
import com.past9.phoneaos.wearproto.WearPaths
import com.past9.phoneaos.wearproto.Wire

/**
 * What the phone sends the watch: the picture and the rings (data items, kept by the system until the watch reads them), the replies,
 * the call's plan, state and flush (messages), and the agent's voice during a call (a channel the phone opens).
 */
class WearListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for (e in events) {
            val path = e.dataItem.uri.path
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
        val c = app.controller
        // Pairing first: a phone asks to pair, or the paired phone unpairs. Everything else counts only from the paired phone.
        when (e.path) {
            PairPaths.REQUEST -> { app.controller.scope.launch { WatchPairingState.onRequest(applicationContext, e.sourceNodeId, e.data) }; return }
            PairPaths.UNPAIR -> { WatchPairingState.onUnpair(applicationContext, e.sourceNodeId); return }
        }
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
