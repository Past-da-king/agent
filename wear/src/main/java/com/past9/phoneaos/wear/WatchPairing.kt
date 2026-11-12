package com.past9.phoneaos.wear

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.google.android.gms.wearable.Wearable
import com.past9.phoneaos.wearproto.Customisation
import com.past9.phoneaos.wearproto.CustomisationStore
import com.past9.phoneaos.wearproto.CustomisationWire
import com.past9.phoneaos.wearproto.PairHost
import com.past9.phoneaos.wearproto.PairOffer
import com.past9.phoneaos.wearproto.PairPaths
import com.past9.phoneaos.wearproto.PairReply
import com.past9.phoneaos.wearproto.PairWire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

/**
 * Which phone this watch serves (docs/wear.md, "Pairing"). Until one has paired, the watch shows a QR and a code and talks to nobody; the
 * phone scans or types it, [PairHost] checks it (right secret, not expired, not locked, one phone only) and the watch from then on talks to that
 * phone alone. Either side can unpair. The logic is [PairHost]'s (pure, tested); this keeps it, stores it and speaks the Data Layer.
 */
object WatchPairingState {
    private const val TAG = "WatchPairing"
    private const val PREFS = "watch_pairing"
    private var host: PairHost? = null
    private val _paired = MutableStateFlow(false)
    val paired: StateFlow<Boolean> = _paired
    private val _offer = MutableStateFlow<PairOffer?>(null)
    /** The QR and code on show (null while paired, or before the first one is made). */
    val offer: StateFlow<PairOffer?> = _offer
    private val _phoneName = MutableStateFlow("")
    val phoneName: StateFlow<String> = _phoneName

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized fun host(c: Context): PairHost = host ?: PairHost(
        { ByteArray(16).also { SecureRandom().nextBytes(it) } },
        boundPhone = prefs(c).getString("phone", null), boundName = prefs(c).getString("name", "").orEmpty(),
    ).also { host = it; _paired.value = it.paired; _phoneName.value = it.phoneName }

    internal val sent = ArrayList<Triple<String, String, ByteArray>>()

    /** Tests start from a watch that was never paired. */
    @Synchronized internal fun resetForTest(c: Context) { sent.clear(); sendMessage = { _, n, p, d -> sent += Triple(n, p, d) }; localNode = { "watch-1" }; host = null; prefs(c).edit().clear().apply(); _paired.value = false; _offer.value = null; _phoneName.value = ""; WatchCustomisationState.clear(c) }

    /** The paired phone's node id, or null when the watch is not paired (nothing is sent to anybody then). */
    fun phone(c: Context): String? = host(c).phone

    private fun save(c: Context) {
        val h = host(c)
        prefs(c).edit().putString("phone", h.phone).putString("name", h.phoneName).apply()
        _paired.value = h.paired; _phoneName.value = h.phoneName
        if (h.paired) _offer.value = null
    }

    /** The two things this object asks of the Data Layer, replaceable so a test can answer them (and record what the watch said). */
    internal var sendMessage: suspend (Context, String, String, ByteArray) -> Unit = { c, node, path, data ->
        withTimeoutOrNull(5_000) { runCatching { Wearable.getMessageClient(c.applicationContext).sendMessage(node, path, data).await() }.onFailure { Log.w(TAG, "send $path: ${it.message}") } }
    }
    internal var localNode: suspend (Context) -> String? = { c ->
        withTimeoutOrNull(5_000) { runCatching { Wearable.getNodeClient(c.applicationContext).localNode.await().id }.getOrNull() }
    }

    /** Make a fresh QR and code: the first time, when the last expired, or when he asks for a new one. */
    suspend fun newOffer(c: Context): PairOffer? {
        val h = host(c)
        if (h.paired) return null
        val node = localNode(c) ?: return null
        return h.newOffer(node, System.currentTimeMillis()).also { _offer.value = it }
    }

    /** The code on show now, while it is still good. */
    fun currentOffer(c: Context): PairOffer? = host(c).current(System.currentTimeMillis())

    /** A phone asked to pair. The answer goes back to it; if it was right the watch is bound and pulls the phone's customisations. */
    suspend fun onRequest(c: Context, from: String, data: ByteArray?) {
        sendMessage(c, from, PairPaths.RESULT, PairWire.result(answer(c, from, data)))
    }

    /** A phone asked over the local network (no sender to read: the phone says its own node id in the request). The answer carries this watch's node id. */
    suspend fun onLanRequest(c: Context, data: ByteArray?): PairReply {
        val phoneNode = PairWire.parseRequestNode(data)
        if (phoneNode.isBlank()) return PairReply.Refused(com.past9.phoneaos.wearproto.PairReason.NO_NODE)
        return answer(c, phoneNode, data)
    }

    /** The checking and the binding, the same on both roads. */
    private suspend fun answer(c: Context, from: String, data: ByteArray?): PairReply {
        val req = PairWire.parseRequest(data)
        var reply: PairReply = if (req == null) PairReply.Refused("wrong_code")
        else host(c).request(from, req.first, req.second, Build.MODEL.orEmpty().ifBlank { "Watch" }, System.currentTimeMillis())
        if (reply is PairReply.Accepted) {
            save(c); WatchCustomisationState.clear(c); (c.applicationContext as? WatchApp)?.controller?.opened()
            reply = reply.copy(node = localNode(c).orEmpty())
        }
        return reply
    }

    /** What the pairing screen listens to while it is up: a request that came straight to it, whatever the system did with the service. */
    fun listener(c: Context, scope: kotlinx.coroutines.CoroutineScope) = com.google.android.gms.wearable.MessageClient.OnMessageReceivedListener { e ->
        when (e.path) {
            PairPaths.REQUEST -> scope.launch { onRequest(c, e.sourceNodeId, e.data) }
            PairPaths.UNPAIR -> onUnpair(c, e.sourceNodeId)
        }
    }

    /** The phone unpaired (or told this watch it does not know it). Only the paired phone counts. */
    fun onUnpair(c: Context, from: String) { if (host(c).unpair(from)) afterUnpair(c) }

    /** He unpaired on the watch: the phone is told, and everything of its is forgotten. */
    suspend fun unpair(c: Context) {
        val phone = phone(c)
        if (phone != null) sendMessage(c, phone, PairPaths.UNPAIR, ByteArray(0))
        if (host(c).unpair(null)) afterUnpair(c)
        newOffer(c)
    }

    private fun afterUnpair(c: Context) {
        save(c); WatchCustomisationState.clear(c); WatchStore.clear(c); WatchRing.onRing(c, emptyList())
        _offer.value = null
    }
}

/** What the watch keeps of the phone's customisations: agent name, mascot, accent, theme, language, voice. The newest wins; only the paired phone is believed. */
object WatchCustomisationState {
    private const val PREFS = "watch_customisation"
    private val store = CustomisationStore()
    private val _state = MutableStateFlow(Customisation())
    val state: StateFlow<Customisation> = _state
    private var loaded = false

    @Synchronized fun load(c: Context) {
        if (loaded) return
        loaded = true
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("item", null)?.let { s ->
            CustomisationWire.parse(s.toByteArray())?.let { store.apply(it, true); _state.value = store.current }
        }
    }

    /** An item changed in the Data Layer. [host] is the node that put it there: it counts only if that is the paired phone. */
    @Synchronized fun onItem(c: Context, host: String?, data: ByteArray?): Boolean {
        load(c)
        val incoming = CustomisationWire.parse(data) ?: return false
        val fromPaired = host != null && host == WatchPairingState.phone(c)
        if (!store.apply(incoming, fromPaired)) return false
        _state.value = store.current
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("item", String(CustomisationWire.encode(store.current))).apply()
        return true
    }

    @Synchronized fun clear(c: Context) {
        loaded = true; store.clear(); _state.value = Customisation()
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Read what the phone last put in the Data Layer (the watch may have been off when it changed). */
    suspend fun pull(c: Context) {
        withTimeoutOrNull(5_000) {
            runCatching {
                val buf = Wearable.getDataClient(c.applicationContext).getDataItems(Uri.Builder().scheme("wear").path(PairPaths.SETTINGS).build()).await()
                try { for (i in 0 until buf.count) onItem(c, buf[i].uri.host, buf[i].data) } finally { buf.release() }
            }
        }
    }
}
