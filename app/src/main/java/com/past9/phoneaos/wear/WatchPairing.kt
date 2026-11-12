package com.past9.phoneaos.wear

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import com.past9.phoneaos.wearproto.PairFacts
import com.past9.phoneaos.wearproto.PairDiagnosis
import com.past9.phoneaos.wearproto.PairLan
import com.past9.phoneaos.wearproto.PairProblem
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

    /** What a pairing try came to: the watch's answer, and, when none came, what is most likely wrong (so the sheet can say it). */
    data class Outcome(val reply: PairReply, val problem: PairProblem? = null)

    /** Scanned from the watch's QR: asks that one watch, over the Data Layer and, in parallel, at the address the QR carries. */
    suspend fun pairWithQr(g: Graph, text: String): Outcome {
        val o = PairCode.parseQr(text) ?: return Outcome(PairReply.Refused(PairReason.WRONG))
        return ask(g, listOf(o.node), PairProof(secret = o.secret), PairLan.parseHost(o.host))
    }

    /** Typed: asks every watch near this phone (Data Layer) and every watch found on the Wi-Fi; only the one showing that code accepts. */
    suspend fun pairWithCode(g: Graph, code: String): Outcome = ask(g, null, PairProof(code = PairCode.clean(code)), null)

    private suspend fun connectedNodes(g: Graph): Pair<List<String>, Boolean> =
        runCatching { withContext(Dispatchers.IO) { Tasks.await(Wearable.getNodeClient(g.app).connectedNodes, 4, TimeUnit.SECONDS).map { it.id } } }
            .fold({ it to true }, { emptyList<String>() to false })

    private suspend fun localNodeId(g: Graph): String =
        runCatching { withContext(Dispatchers.IO) { Tasks.await(Wearable.getNodeClient(g.app).localNode, 4, TimeUnit.SECONDS).id } }.getOrDefault("")

    private fun wifiConnected(g: Graph): Boolean = runCatching {
        val cm = g.app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)

    /**
     * Two roads at once, first yes wins: the Data Layer (the request goes to the watch's app through Play Services) and the local network (the
     * watch listens on the Wi-Fi while its pairing screen is up). A road that cannot work (the app in a Secure Folder, a frozen watch app) costs
     * nothing, and when neither does, the outcome names the likely cause instead of "no watch answered".
     */
    private suspend fun ask(g: Graph, only: List<String>?, proof: PairProof, qrHost: Pair<String, Int>?): Outcome = coroutineScope {
        val (listed, playServices) = connectedNodes(g)
        val nodes = only ?: listed
        val phoneNode = localNodeId(g)
        val refusals = java.util.concurrent.CopyOnWriteArrayList<String>()
        val sent = java.util.concurrent.atomic.AtomicInteger(0)
        val onWifi = java.util.concurrent.atomic.AtomicInteger(0)
        val win = CompletableDeferred<PairReply.Accepted>()

        val dataLayer = launch { dataLayerRoad(g, nodes, proof, phoneNode, sent, refusals)?.let { win.complete(it) } }
        val lan = launch {
            val request = PairWire.request(proof, Build.MODEL.orEmpty(), phoneNode)
            // The QR names the address; a typed code has to look for the watch.
            val where = if (qrHost != null) listOf(qrHost) else WatchLan.discover(g.app)
            onWifi.set(where.size)
            for ((host, port) in where) {
                val r = WatchLan.ask(host, port, request) ?: continue
                if (r is PairReply.Accepted) {
                    if (r.node.isBlank()) refusals += PairReason.NO_NODE else { onResult(g, r.node, r); win.complete(r) }
                } else if (r is PairReply.Refused) refusals += r.reason
            }
        }
        launch { joinAll(dataLayer, lan); win.cancel() }
        val accepted = runCatching { win.await() }.getOrNull()
        dataLayer.cancel(); lan.cancel()
        if (accepted != null) return@coroutineScope Outcome(accepted)
        // The watch itself said no (wrong code, expired, locked, paired elsewhere): that is the answer, not a mystery.
        refusals.firstOrNull { it != "none" }?.let { return@coroutineScope Outcome(PairReply.Refused(it), PairProblem.TOLD_NO) }
        val problem = PairDiagnosis.of(PairFacts(
            userId = android.os.Process.myUid() / 100_000, playServices = playServices, connectedWatches = listed.size,
            sentOverDataLayer = sent.get(), foundOnWifi = onWifi.get(), wifiConnected = wifiConnected(g)))
        Outcome(PairReply.Refused("none"), problem)
    }

    private suspend fun dataLayerRoad(g: Graph, nodes: List<String>, proof: PairProof, phoneNode: String, sent: java.util.concurrent.atomic.AtomicInteger, refusals: MutableList<String>): PairReply.Accepted? = coroutineScope {
        if (nodes.isEmpty()) return@coroutineScope null
        val mc = Wearable.getMessageClient(g.app)
        val data = PairWire.request(proof, Build.MODEL.orEmpty(), phoneNode)
        val answered = HashSet<String>()
        val targets = HashSet<String>()
        // Listening starts before anything is sent, so a quick answer cannot be missed.
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(10_000) {
                var won: PairReply.Accepted? = null
                results.first { (from, reply) ->
                    if (from !in synchronized(targets) { targets.toSet() } || from in answered) return@first false
                    answered += from
                    // One watch saying no is not the end: a typed code may be right for another watch near the phone.
                    if (reply is PairReply.Accepted) { won = reply; true } else { if (reply is PairReply.Refused) refusals += reply.reason; answered.size >= synchronized(targets) { targets.size } }
                }
                won
            }
        }
        for (n in nodes) if (runCatching { withContext(Dispatchers.IO) { Tasks.await(mc.sendMessage(n, PairPaths.REQUEST, data), 5, TimeUnit.SECONDS) }; true }.getOrDefault(false)) { synchronized(targets) { targets += n }; sent.incrementAndGet() }
        if (synchronized(targets) { targets.isEmpty() }) { waiter.cancel(); return@coroutineScope null }
        waiter.await()
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
