package com.past9.phoneaos.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.past9.phoneaos.wearproto.WearPaths
import com.past9.phoneaos.wearproto.Wire

/** What the phone sends the watch: the picture (a data item, kept by the system until the watch reads it) and the replies. */
class WearListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for (e in events) {
            if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path == WearPaths.STATE) {
                Wire.parseState(e.dataItem.data)?.let { WatchStore.update(applicationContext, it) }
            }
        }
    }

    override fun onMessageReceived(e: MessageEvent) {
        val c = (applicationContext as WatchApp).controller
        when (e.path) {
            WearPaths.ACK -> Wire.parseAck(e.data)?.let(c::onAck)
            WearPaths.HEARD -> Wire.parseHeard(e.data)?.let(c::onHeard)
        }
    }
}
