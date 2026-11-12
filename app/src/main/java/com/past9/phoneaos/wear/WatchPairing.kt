package com.past9.phoneaos.wear

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.past9.phoneaos.Graph
import com.past9.phoneaos.data.AgentSettings
import com.past9.phoneaos.system.AppLocale
import com.past9.phoneaos.wearproto.Customisation
import com.past9.phoneaos.wearproto.CustomisationPublisher
import com.past9.phoneaos.wearproto.CustomisationWire
import com.past9.phoneaos.wearproto.PairCode
import com.past9.phoneaos.wearproto.PairPaths
import com.past9.phoneaos.wearproto.PairProof
import com.past9.phoneaos.wearproto.PairReason
import com.past9.phoneaos.wearproto.PairReply
import com.past9.phoneaos.wearproto.PairWire
import com.past9.phoneaos.wearproto.PhoneBinding
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Which watch this phone serves (docs/wear.md, "Pairing"). Until a watch has paired, the relay answers none: the watch shows a QR and a
 * code, this phone scans the QR (the same camera scanner the computer link uses) or takes the code, asks over the Data Layer, and binds to the
 * one watch that accepts. From then on only that watch is served, and the phone's customisations (agent name, mascot, accent, theme,
 * language, voice) are kept on the watch through one data item. Either side can unpair.
 */
object WatchPairing {
    private const val TAG = "WatchPairing"
    private const val K_WATCH = "watch_bound"
    private const val K_NAME = "watch_bound_name"
    private var binding: PhoneBinding? = null
    private val results = MutableSharedFlow<Pair<String, PairReply>>(extraBufferCapacity = 16)
    private val _bound = MutableStateFlow<String?>(null)
    /** The bound watch's name (empty when none), for the settings sheet. */
    val boundName: StateFlow<String?> = _bound
    val publisher = CustomisationPublisher()

    @Synchronized private fun b(g: Graph): PhoneBinding = binding ?: PhoneBinding(g.settings.extra(K_WATCH), g.settings.extra(K_NAME).orEmpty()).also { binding = it; _bound.value = it.watch?.let { _ -> it.watchName.ifBlank { "Watch" } } }

    fun bound(g: Graph): String? = b(g).watch
    fun serves(g: Graph, node: String): Boolean = b(g).serves(node)

    private fun save(g: Graph) {
        val x = b(g)
        g.settings.setExtra(K_WATCH, x.watch); g.settings.setExtra(K_NAME, if (x.watch == null) null else x.watchName)
        _bound.value = x.watch?.let { x.watchName.ifBlank { "Watch" } }
    }

    /** The watch answered a request this phone made. */
    fun onResult(g: Graph, from: String, reply: PairReply) {
        if (b(g).onResult(from, reply)) { save(g); publisher.forget(); WatchRelay.paired(g, from) }
        results.tryEmit(from to reply)
    }

    /** Scanned from the watch's QR: asks that one watch. */
    suspend fun pairWithQr(g: Graph, text: String): PairReply {
        val o = PairCode.parseQr(text) ?: return PairReply.Refused(PairReason.WRONG)
        return ask(g, listOf(o.node), PairProof(secret = o.secret))
    }

    /** Typed: asks every watch near this phone; only the one showing that code accepts. */
    suspend fun pairWithCode(g: Graph, code: String): PairReply {
        val nodes = runCatching { Tasks.await(Wearable.getNodeClient(g.app).connectedNodes).map { it.id } }.getOrDefault(emptyList())
        if (nodes.isEmpty()) return PairReply.Refused("none")
        return ask(g, nodes, PairProof(code = code))
    }

    private suspend fun ask(g: Graph, nodes: List<String>, proof: PairProof): PairReply = coroutineScope {
        val mc = Wearable.getMessageClient(g.app)
        val data = PairWire.request(proof, Build.MODEL.orEmpty())
        var last: PairReply = PairReply.Refused("none")
        val answered = HashSet<String>()
        val targets = HashSet<String>()
        // Listening starts before anything is sent, so a quick answer cannot be missed.
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(10_000) {
                var won: PairReply? = null
                results.first { (from, reply) ->
                    if (from !in targets || from in answered) return@first false
                    answered += from
                    // One watch saying no is not the end: a typed code may be right for another watch near the phone.
                    if (reply is PairReply.Accepted) { won = reply; true } else { last = reply; answered.size >= targets.size }
                }
                won ?: last
            }
        }
        for (n in nodes) if (runCatching { Tasks.await(mc.sendMessage(n, PairPaths.REQUEST, data)); true }.getOrDefault(false)) synchronized(targets) { targets += n }
        if (targets.isEmpty()) { waiter.cancel(); return@coroutineScope PairReply.Refused("none") }
        waiter.await() ?: last
    }

    /** He unpaired from the phone: the watch is told, and everything forgets it. */
    fun unpair(g: Graph) {
        val node = bound(g) ?: return
        runCatching { Wearable.getMessageClient(g.app).sendMessage(node, PairPaths.UNPAIR, ByteArray(0)) }
        b(g).unpair(null); save(g); WatchRelay.unpaired()
    }

    /** The watch unpaired from its side. Only the bound watch counts. */
    fun onUnpair(g: Graph, from: String) {
        if (b(g).unpair(from)) { save(g); WatchRelay.unpaired() }
    }

    // ---- the customisations the watch keeps in step ---------------------------------------------------------------------

    fun customisation(g: Graph, s: AgentSettings): Customisation = customisation(runCatching { AppLocale.current(g.app) }.getOrDefault(""), s)

    fun customisation(language: String, s: AgentSettings): Customisation = Customisation(
        agentName = s.agentName.takeIf { it.isNotBlank() && it != "Your agent" }.orEmpty(), mascot = s.mascot, accent = s.accent,
        // The phone follows the system's light or dark; there is no setting of its own to copy.
        theme = Customisation.THEME_SYSTEM, language = language,
        voiceProvider = s.ttsProvider, voice = s.ttsVoice,
    )

    /** Put the item in the Data Layer if there is something new to say (nothing before a watch is bound, nothing twice). */
    fun publish(g: Graph, s: AgentSettings) {
        val c = publisher.next(customisation(g, s), bound(g) != null, System.currentTimeMillis()) ?: return
        val req = PutDataRequest.create(PairPaths.SETTINGS).setData(CustomisationWire.encode(c)).setUrgent()
        runCatching { Wearable.getDataClient(g.app).putDataItem(req) }.onFailure { Log.w(TAG, "settings put failed: ${it.message}") }
    }
}
