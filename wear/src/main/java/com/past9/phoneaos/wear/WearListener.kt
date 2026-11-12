package com.past9.phoneaos.wear

import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.past9.phoneaos.wearproto.CallPaths
import com.past9.phoneaos.wearproto.CallWire
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
            when {
                e.type == DataEvent.TYPE_CHANGED && path == WearPaths.STATE -> Wire.parseState(e.dataItem.data)?.let { WatchStore.update(applicationContext, it) }
                // An empty list is a real answer (nothing rings); a deleted item is the same.
                path == RingPaths.RING -> WatchRing.onRing(applicationContext, if (e.type == DataEvent.TYPE_CHANGED) RingWire.parseItems(e.dataItem.data).orEmpty() else emptyList())
            }
        }
    }

    override fun onMessageReceived(e: MessageEvent) {
        val app = applicationContext as WatchApp
        val c = app.controller
        when (e.path) {
            WearPaths.ACK -> Wire.parseAck(e.data)?.let { c.onAck(it); app.call.onAck(it) }
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
