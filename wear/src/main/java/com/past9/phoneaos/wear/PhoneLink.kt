package com.past9.phoneaos.wear

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.past9.phoneaos.wearproto.WatchState
import com.past9.phoneaos.wearproto.WearPaths
import com.past9.phoneaos.wearproto.Wire
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** The watch's one way to the phone: the Wearable Data Layer. Nothing else leaves the watch. */
class PhoneLink(private val context: Context) {
    /** The paired phone that runs the relay (it declares [WearPaths.CAP_PHONE]); any connected phone when none does. */
    // The phone this watch is paired with, and no other: with none paired nothing is sent to anybody (docs/wear.md, "Pairing").
    suspend fun phoneNode(): String? = WatchPairingState.phone(context)

    suspend fun send(path: String, data: ByteArray = ByteArray(0)): Boolean {
        val node = phoneNode() ?: return false
        return runCatching { Wearable.getMessageClient(context).sendMessage(node, path, data).await(); true }.getOrDefault(false)
    }

    /** A recorded take, over a channel; the phone answers with a HEARD message. */
    suspend fun sendAudio(id: String, pcm: ByteArray): Boolean {
        val node = phoneNode() ?: return false
        val client = Wearable.getChannelClient(context)
        return runCatching {
            val channel = client.openChannel(node, WearPaths.AUDIO_CHANNEL + "/" + id).await()
            client.getOutputStream(channel).await().use { it.write(pcm); it.flush() }
            client.close(channel).await()
            true
        }.getOrDefault(false)
    }

    /** The picture the phone last put in the Data Layer (the watch may have been off when it changed). */
    suspend fun readState(): WatchState? = withTimeoutOrNull(5_000) {
        runCatching {
            val buf = Wearable.getDataClient(context).getDataItems(Uri.Builder().scheme("wear").path(WearPaths.STATE).build()).await()
            try { if (buf.count > 0) Wire.parseState(buf[0].data) else null } finally { buf.release() }
        }.getOrNull()
    }

    /** What the phone last said rings (a watch that was off while a wake-up began still rings when it comes back). Null = unknown. */
    suspend fun readRing(): List<com.past9.phoneaos.wearproto.RingItem>? = withTimeoutOrNull(5_000) {
        runCatching {
            val buf = Wearable.getDataClient(context).getDataItems(Uri.Builder().scheme("wear").path(com.past9.phoneaos.wearproto.RingPaths.RING).build()).await()
            try { if (buf.count > 0) com.past9.phoneaos.wearproto.RingWire.parseItems(buf[0].data) else null } finally { buf.release() }
        }.getOrNull()
    }
}
