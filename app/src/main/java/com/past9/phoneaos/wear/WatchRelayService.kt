package com.past9.phoneaos.wear

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.past9.phoneaos.App
import com.past9.phoneaos.voice.PieceTranscriber
import com.past9.phoneaos.wearproto.Heard
import com.past9.phoneaos.wearproto.Limits
import com.past9.phoneaos.wearproto.Via
import com.past9.phoneaos.wearproto.WearPaths
import com.past9.phoneaos.wearproto.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Receives what the watch sends (messages and a recorded voice take) and hands it to [WatchRelay]. The system starts this
 * service when a message arrives, so the watch works with the phone app closed.
 */
class WatchRelayService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun onMessageReceived(e: MessageEvent) {
        if (!e.path.startsWith("/agent/")) return
        val g = App.graph(this)
        // Pairing comes first, and is the only thing an unpaired watch may say (docs/wear.md, "Pairing").
        when (e.path) {
            com.past9.phoneaos.wearproto.PairPaths.RESULT -> { com.past9.phoneaos.wearproto.PairWire.parseResult(e.data)?.let { WatchPairing.onResult(g, e.sourceNodeId, it) }; return }
            com.past9.phoneaos.wearproto.PairPaths.UNPAIR -> { WatchPairing.onUnpair(g, e.sourceNodeId); return }
        }
        // Anything else is served only for the watch this phone is paired with; any other gets told to pair, and nothing is run.
        if (!WatchPairing.serves(g, e.sourceNodeId)) {
            send(e.sourceNodeId, WearPaths.ACK, Wire.ack(com.past9.phoneaos.wearproto.Ack("", false, com.past9.phoneaos.wearproto.PairReason.NOT_PAIRED)))
            return
        }
        WatchRelay.seen(g, e.sourceNodeId)
        scope.launch {
            val reply = runCatching { WatchRelay.router(g).route(e.path, e.data) }.onFailure { Log.w(TAG, "route ${e.path}: ${it.message}") }.getOrNull() ?: return@launch
            send(e.sourceNodeId, reply.path, reply.data)
        }
    }

    /** A voice take from the watch's mic: written down with the phone's own dictation pass, then sent to the agent. */
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (!WatchPairing.serves(App.graph(this), channel.nodeId)) { runCatching { Wearable.getChannelClient(this).close(channel) }; return }
        // The watch's microphone for a call with the agent: streamed live into the call's session (WatchCallBridge).
        if (channel.path.startsWith(com.past9.phoneaos.wearproto.CallPaths.MIC_CHANNEL + "/")) {
            WatchCallBridge.onMicChannel(App.graph(this), channel)
            return
        }
        if (!channel.path.startsWith(WearPaths.AUDIO_CHANNEL + "/")) return
        val id = channel.path.substringAfterLast('/')
        val node = channel.nodeId
        val g = App.graph(this)
        WatchRelay.seen(g, node)
        scope.launch {
            val client = Wearable.getChannelClient(this@WatchRelayService)
            val heard = runCatching {
                val pcm = Tasks.await(client.getInputStream(channel), 10, TimeUnit.SECONDS).use { readCapped(it) }
                runCatching { Tasks.await(client.close(channel), 5, TimeUnit.SECONDS) }
                if (pcm.size < WearPaths.PCM_RATE / 4) return@runCatching Heard(id, false, reason = "That was too short to hear.")
                val text = PieceTranscriber.transcribe(this@WatchRelayService, pcm).trim()
                if (text.isEmpty()) return@runCatching Heard(id, false, reason = "I did not catch that.")
                val r = WatchRelay.router(g).let { rt -> rt.route(WearPaths.ASK, Wire.ask(com.past9.phoneaos.wearproto.Ask("voice-$id", text, Via.SPEECH))) }
                val ack = Wire.parseAck(r?.data)
                if (ack?.ok == true) Heard(id, true, text) else Heard(id, false, text, ack?.reason.orEmpty())
            }.getOrElse { Heard(id, false, reason = it.message.orEmpty().ifBlank { "The phone could not write that down." }) }
            send(node, WearPaths.HEARD, Wire.heard(heard))
        }
    }

    private fun readCapped(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (out.size() < Limits.AUDIO_MAX_BYTES) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
        return out.toByteArray()
    }

    private fun send(node: String, path: String, data: ByteArray) {
        runCatching { Wearable.getMessageClient(this).sendMessage(node, path, data) }.onFailure { Log.w(TAG, "send $path: ${it.message}") }
    }

    private companion object { const val TAG = "WatchRelay" }
}
