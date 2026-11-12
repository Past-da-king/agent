package com.past9.phoneaos.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.past9.phoneaos.Graph
import com.past9.phoneaos.voice.CallController
import com.past9.phoneaos.voice.CallState
import com.past9.phoneaos.voice.ExternalAudio
import com.past9.phoneaos.wearproto.CallPaths
import com.past9.phoneaos.wearproto.CallPlan
import com.past9.phoneaos.wearproto.CallStart
import com.past9.phoneaos.wearproto.CallView
import com.past9.phoneaos.wearproto.CallWire
import com.past9.phoneaos.wearproto.MicFramer
import com.past9.phoneaos.wearproto.PhoneCallGate
import com.past9.phoneaos.wearproto.RelayResult
import com.past9.phoneaos.wearproto.Side
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A call with the agent placed from the watch (docs/wear.md, "Call"). The call itself is the phone's own: [CallController], the same
 * Live voice session the phone's Call button starts, alone or through the linked computer (the one-use Live token and the
 * hand-off of work to the computer's agent are inside it), so nothing here knows about keys or brains. This object is the bridge:
 * it carries the watch's microphone in and the agent's voice out over the Data Layer, tells the watch where the call is every
 * second, and ends the call cleanly on this side when the watch goes away.
 */
object WatchCallBridge {
    private const val TAG = "WatchCall"
    private val gate = PhoneCallGate()
    private val framer = MicFramer()
    @Volatile private var node: String? = null
    @Volatile private var speaker: WatchSpeaker? = null
    private var pumpJob: Job? = null

    suspend fun start(g: Graph, from: String, req: CallStart): RelayResult {
        if (g.call.state.value.active) return RelayResult(false, "A call is already on.")
        val plan = gate.open(req, System.currentTimeMillis()) ?: return RelayResult(false, "A call is already on.")
        node = from; framer.reset()
        val out = if (plan.watchSpeaker) WatchSpeaker(g.app, from, plan.id) { send(g.app, from, CallPaths.FLUSH, CallWire.id(plan.id)) }.also { speaker = it } else null
        // The watch hears the plan first, so its mic stream and its speaker are ready before the voice speaks.
        send(g.app, from, CallPaths.PLAN, CallWire.plan(plan))
        val started = g.call.startFromWatch(CallController.WatchAudio(plan.phoneMic, plan.phoneSpeaker, out))
        if (!started) { release(plan.id); return RelayResult(false, "A call is already on.") }
        pumpJob = g.scope.launch { runCatching { pump(g, plan) }.onFailure { Log.w(TAG, "pump: ${it.message}") }; release(plan.id) }
        return RelayResult(true)
    }

    fun end(g: Graph, id: String): RelayResult {
        if (!gate.owns(id)) return RelayResult(true) // already over: ending twice is fine
        g.call.end()
        return RelayResult(true)
    }

    fun mute(g: Graph, id: String, on: Boolean): RelayResult {
        if (!gate.owns(id)) return RelayResult(false, "That call is over.")
        if (g.call.state.value.muted != on) g.call.toggleMute()
        return RelayResult(true)
    }

    fun beat(id: String) { gate.heard(id, System.currentTimeMillis()) }

    /** The watch's microphone stream: 16 kHz mono PCM16 in frames of 100 ms, until the call ends or the stream does. */
    fun onMicChannel(g: Graph, channel: ChannelClient.Channel) {
        val id = channel.path.substringAfterLast('/')
        val client = Wearable.getChannelClient(g.app)
        if (!gate.owns(id) || gate.currentPlan()?.watchMic != true) { runCatching { client.close(channel) }; return }
        thread(name = "watch-mic", isDaemon = true) {
            try {
                Tasks.await(client.getInputStream(channel), 10, TimeUnit.SECONDS).use { input ->
                    val buf = ByteArray(3_200)
                    while (gate.owns(id) && g.call.state.value.active) {
                        val n = input.read(buf)
                        if (n < 0) break
                        gate.heardAudio(id, System.currentTimeMillis())
                        for (frame in framer.feed(buf, n)) g.call.feedWatchAudio(frame)
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "mic stream: ${e.message}") }
            runCatching { client.close(channel) }
        }
    }

    /** Every second: the watch is told the state of the call, and a watch that has gone quiet is dealt with. */
    private suspend fun pump(g: Graph, plan: CallPlan) {
        val to = node ?: return
        var finals = 0
        while (true) {
            val st = g.call.state.value
            val verdict = gate.judge(System.currentTimeMillis())
            if (st.active && verdict == PhoneCallGate.Verdict.END) { Log.i(TAG, "the watch went quiet: ending the call"); g.call.end() }
            send(g.app, to, CallPaths.STATE, CallWire.view(view(g, plan, st, verdict)))
            if (!st.active) { if (++finals >= 3) break; delay(300); continue }
            delay(CallPaths.STATE_EVERY_MS)
        }
    }

    /** What the watch draws: the phone's own call state, with a drop-out shown as reconnecting. Pure of Android. */
    internal fun view(g: Graph?, plan: CallPlan, st: CallState, verdict: PhoneCallGate.Verdict, agent: String = ""): CallView {
        val last = st.lines.lastOrNull()
        val phase = when {
            st.active && verdict == PhoneCallGate.Verdict.DROP_OUT -> CallView.RECONNECTING
            st.phase.isBlank() -> CallView.CONNECTING
            else -> st.phase
        }
        val name = agent.ifBlank { g?.settings?.state?.value?.agentName?.takeIf { it.isNotBlank() && it != "Your agent" }.orEmpty() }
        return CallView(plan.id, phase, st.muted, last?.second.orEmpty(), last?.first.orEmpty(), plan.mic, plan.spk, st.error, name)
    }

    private fun release(id: String) {
        speaker?.close(); speaker = null
        framer.reset()
        gate.close(id)
    }

    internal fun send(context: Context, node: String, path: String, data: ByteArray) {
        runCatching { Wearable.getMessageClient(context).sendMessage(node, path, data) }.onFailure { Log.w(TAG, "send $path: ${it.message}") }
    }
}

/**
 * The agent's voice to the watch: 24 kHz mono PCM16 over a channel the phone opens. A writer thread owns the channel, so the Live
 * session's socket thread is never blocked by a slow link; if the link falls behind, the oldest sound is dropped (a voice that lags
 * the conversation is worse than a skipped syllable) and when the agent is cut off, everything queued is dropped at once.
 */
internal class WatchSpeaker(private val context: Context, private val node: String, private val callId: String, private val onFlush: () -> Unit) : ExternalAudio {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val queued = AtomicInteger()
    @Volatile private var open = true
    private val writer = thread(name = "watch-spk", isDaemon = true) { run() }

    override fun play(pcm: ByteArray) {
        if (!open || pcm.isEmpty()) return
        queue.offer(pcm); queued.addAndGet(pcm.size)
        // More than two seconds waiting: the link is behind, keep the newest.
        while (queued.get() > MAX_BYTES) { val d = queue.poll() ?: break; queued.addAndGet(-d.size) }
    }

    override fun flush() { queue.clear(); queued.set(0); onFlush() }

    fun close() { open = false; writer.interrupt() }

    private fun run() {
        val client = Wearable.getChannelClient(context)
        var channel: ChannelClient.Channel? = null
        try {
            channel = Tasks.await(client.openChannel(node, CallPaths.SPK_CHANNEL + "/" + callId), 10, TimeUnit.SECONDS)
            val out = Tasks.await(client.getOutputStream(channel), 10, TimeUnit.SECONDS)
            out.use { os ->
                while (open) {
                    val b = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    queued.addAndGet(-b.size)
                    os.write(b); os.flush()
                }
            }
        } catch (e: InterruptedException) {
            // closed
        } catch (e: Exception) {
            Log.w("WatchCall", "speaker stream: ${e.message}")
        } finally {
            open = false
            channel?.let { runCatching { client.close(it) } }
        }
    }

    private companion object { const val MAX_BYTES = CallPaths.SPK_RATE * 2 * 2 }
}
