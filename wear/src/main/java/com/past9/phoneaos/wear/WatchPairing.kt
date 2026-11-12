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
import com.past9.phoneaos.wearproto.PairLog
import com.past9.phoneaos.wearproto.PairOfferStore
import com.past9.phoneaos.wearproto.PairedRule
import com.past9.phoneaos.wearproto.PairedWire
import com.past9.phoneaos.wearproto.StoredOffer
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
    /** True while the pairing screen is in front (set by [PairGate]); a pairing that finishes in the background gets a notification instead. */
    @Volatile var screenUp: Boolean = false
    private val _phoneName = MutableStateFlow("")
    val phoneName: StateFlow<String> = _phoneName

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The offer (node, secret, expiry, wrong tries) kept on disk, so a process that comes back, or a service the system starts cold, holds the same code. */
    private class PrefsOfferStore(private val c: Context) : PairOfferStore {
        private fun p() = prefs(c)
        override fun load(): StoredOffer? {
            val s = p(); val secret = s.getString("offer_secret", null) ?: return null
            val node = s.getString("offer_node", null) ?: return null
            val exp = s.getLong("offer_expires", 0L)
            if (System.currentTimeMillis() >= exp) return null
            return StoredOffer(PairOffer(node, secret, exp), s.getInt("offer_wrong", 0))
        }
        override fun save(offer: PairOffer, wrong: Int) {
            p().edit().putString("offer_node", offer.node).putString("offer_secret", offer.secret).putLong("offer_expires", offer.expiresAt).putInt("offer_wrong", wrong).commit()
        }
        override fun clear() { p().edit().remove("offer_node").remove("offer_secret").remove("offer_expires").remove("offer_wrong").commit() }
    }

    @Synchronized fun host(c: Context): PairHost = host ?: PairHost(
        { ByteArray(16).also { SecureRandom().nextBytes(it) } },
        boundPhone = prefs(c).getString("phone", null), boundName = prefs(c).getString("name", "").orEmpty(), store = PrefsOfferStore(c.applicationContext),
    ).also {
        host = it; _paired.value = it.paired; _phoneName.value = it.phoneName
        _offer.value = it.current(System.currentTimeMillis())
        PairLog.i("watch: pairing state loaded paired=${it.paired} offer=${_offer.value != null}")
    }

    /** When he (or the phone) last unpaired this watch: a phone's older "paired" note must not undo it. */
    private fun unpairedAt(c: Context): Long = prefs(c).getLong("unpaired_at", 0L)
    private fun markUnpaired(c: Context) { prefs(c).edit().putLong("unpaired_at", System.currentTimeMillis()).commit() }

    internal val sent = ArrayList<Triple<String, String, ByteArray>>()

    /** Tests: the process died. What was kept on disk stays; everything in memory goes, and is read back on the next use. */
    @Synchronized internal fun dropMemoryForTest() { host = null; _paired.value = false; _offer.value = null }

    /** Tests start from a watch that was never paired. */
    @Synchronized internal fun resetForTest(c: Context) { sent.clear(); sendMessage = { _, n, p, d -> sent += Triple(n, p, d) }; localNode = { "watch-1" }; host = null; prefs(c).edit().clear().commit(); PairLog.clear(); _paired.value = false; _offer.value = null; _phoneName.value = ""; WatchCustomisationState.clear(c) }

    /** The paired phone's node id, or null when the watch is not paired (nothing is sent to anybody then). */
    fun phone(c: Context): String? = host(c).phone

    private fun save(c: Context) {
        val h = host(c)
        prefs(c).edit().putString("phone", h.phone).putString("name", h.phoneName).commit()
        _paired.value = h.paired; _phoneName.value = h.phoneName
        if (h.paired) _offer.value = null
    }

    /** The two things this object asks of the Data Layer, replaceable so a test can answer them (and record what the watch said). */
    internal var sendMessage: suspend (Context, String, String, ByteArray) -> Unit = { c, node, path, data ->
        withTimeoutOrNull(5_000) {
            runCatching { Wearable.getMessageClient(c.applicationContext).sendMessage(node, path, data).await() }
                .onSuccess { PairLog.i("watch: sent $path to ${PairLog.node(node)}") }
                .onFailure { PairLog.i("watch: send $path to ${PairLog.node(node)} failed: ${it.message}") }
        } ?: PairLog.i("watch: send $path to ${PairLog.node(node)} timed out")
    }
    internal var localNode: suspend (Context) -> String? = { c ->
        withTimeoutOrNull(5_000) { runCatching { Wearable.getNodeClient(c.applicationContext).localNode.await().id }.getOrNull() }
    }

    /**
     * The code on show: the same one while it is still good (so backing out of the screen and coming back within ten minutes changes nothing),
     * a fresh one the first time or when the last expired. [force] is "New code" pressed on purpose.
     */
    suspend fun newOffer(c: Context, force: Boolean = false): PairOffer? {
        val h = host(c)
        if (h.paired) return null
        val now = System.currentTimeMillis()
        if (!force) h.current(now)?.let {
            PairLog.i("watch: offer kept (valid for ${(it.expiresAt - now) / 1000}s) node=${PairLog.node(it.node)}")
            _offer.value = it; PairNotice.show(c); return it
        }
        val node = localNode(c)
        if (node == null) { PairLog.i("watch: offer not made, own node id unavailable"); return null }
        return h.newOffer(node, now).also {
            _offer.value = it
            PairLog.i("watch: offer created node=${PairLog.node(node)} lanPort=${WatchLanPair.port()} valid=${(it?.expiresAt ?: now) - now}ms force=$force")
            PairNotice.show(c)
        }
    }

    /** The code on show now, while it is still good. */
    fun currentOffer(c: Context): PairOffer? = host(c).current(System.currentTimeMillis())

    /** A phone asked to pair. The answer goes back to it; if it was right the watch is bound and pulls the phone's customisations. */
    suspend fun onRequest(c: Context, from: String, data: ByteArray?) {
        PairLog.i("watch: onRequest road=datalayer from=${PairLog.node(from)} bytes=${data?.size ?: 0}")
        val reply = answer(c, from, data)
        sendMessage(c, from, PairPaths.RESULT, PairWire.result(reply))
        PairLog.i("watch: answer sent road=datalayer ${describe(reply)}")
    }

    private fun describe(r: PairReply) = when (r) { is PairReply.Accepted -> "accepted"; is PairReply.Refused -> "refused(${r.reason})" }

    /** A phone asked over the local network (no sender to read: the phone says its own node id in the request). The answer carries this watch's node id. */
    suspend fun onLanRequest(c: Context, data: ByteArray?): PairReply {
        val phoneNode = PairWire.parseRequestNode(data)
        PairLog.i("watch: onLanRequest from=${PairLog.node(phoneNode)} bytes=${data?.size ?: 0}")
        if (phoneNode.isBlank()) return PairReply.Refused(com.past9.phoneaos.wearproto.PairReason.NO_NODE).also { PairLog.i("watch: lan answer ${describe(it)}") }
        return answer(c, phoneNode, data).also { PairLog.i("watch: lan answer ${describe(it)}") }
    }

    /** The checking and the binding, the same on both roads. */
    private suspend fun answer(c: Context, from: String, data: ByteArray?): PairReply {
        val req = PairWire.parseRequest(data)
        var reply: PairReply = if (req == null) PairReply.Refused("wrong_code")
        else host(c).request(from, req.first, req.second, Build.MODEL.orEmpty().ifBlank { "Watch" }, System.currentTimeMillis())
        if (reply is PairReply.Accepted) {
            save(c); WatchCustomisationState.clear(c); PairNotice.clear(c); if (!screenUp) PairNotice.paired(c, host(c).phoneName)
            (c.applicationContext as? WatchApp)?.controller?.opened()
            reply = reply.copy(node = localNode(c).orEmpty())
            PairLog.i("watch: paired with ${PairLog.node(from)}")
        } else PairLog.i("watch: request from ${PairLog.node(from)} ${describe(reply)}")
        return reply
    }

    /**
     * The phone may say "this watch is paired with me" in the Data Layer while the watch missed the answer (asleep, frozen, backed out). Called
     * when the watch opens, resumes its pairing screen, or gets the note in the background: if the note names this watch and is newer than
     * its last unpairing, the watch binds to that phone, so it never sits on the pairing screen while the phone says paired.
     */
    suspend fun reconcile(c: Context): Boolean {
        val h = host(c)
        if (h.paired) return false
        val me = localNode(c).orEmpty()
        val items = withTimeoutOrNull(5_000) {
            runCatching {
                val buf = Wearable.getDataClient(c.applicationContext).getDataItems(Uri.Builder().scheme("wear").path(PairPaths.PAIRED).build()).await()
                try { (0 until buf.count).map { buf[it].uri.host to buf[it].data } } finally { buf.release() }
            }.getOrNull()
        }.orEmpty()
        PairLog.i("watch: reconcile looked at ${items.size} paired note(s) me=${PairLog.node(me)}")
        for ((phone, data) in items) if (onPairedNote(c, phone, data, me)) return true
        return false
    }

    /** One "paired" note from the phone (read, or pushed to the service). True when the watch bound because of it. */
    suspend fun onPairedNote(c: Context, phoneNode: String?, data: ByteArray?, me: String? = null): Boolean {
        val h = host(c)
        val note = PairedWire.parse(data)
        val mine = me ?: localNode(c).orEmpty()
        val ok = phoneNode != null && PairedRule.accepts(note, mine, unpairedAt(c), h.paired)
        PairLog.i("watch: paired note from ${PairLog.node(phoneNode)} accepted=$ok")
        if (!ok || note == null || !h.adopt(phoneNode!!, note.phoneName)) return false
        save(c); WatchCustomisationState.clear(c); PairNotice.clear(c); if (!screenUp) PairNotice.paired(c, note.phoneName)
        (c.applicationContext as? WatchApp)?.controller?.opened()
        PairLog.i("watch: paired with ${PairLog.node(phoneNode)} (learned from the phone's note)")
        return true
    }

    /** What the pairing screen listens to while it is up: a request that came straight to it, whatever the system did with the service. */
    fun listener(c: Context, scope: kotlinx.coroutines.CoroutineScope) = com.google.android.gms.wearable.MessageClient.OnMessageReceivedListener { e ->
        when (e.path) {
            PairPaths.REQUEST -> scope.launch { onRequest(c, e.sourceNodeId, e.data) }
            PairPaths.UNPAIR -> onUnpair(c, e.sourceNodeId)
        }
    }

    /** The phone unpaired (or told this watch it does not know it). Only the paired phone counts. */
    fun onUnpair(c: Context, from: String) { PairLog.i("watch: unpair message from ${PairLog.node(from)}"); if (host(c).unpair(from)) { markUnpaired(c); afterUnpair(c) } }

    /** He unpaired on the watch: the phone is told, and everything of its is forgotten. */
    suspend fun unpair(c: Context) {
        val phone = phone(c)
        if (phone != null) sendMessage(c, phone, PairPaths.UNPAIR, ByteArray(0))
        if (host(c).unpair(null)) { markUnpaired(c); afterUnpair(c) }
        newOffer(c, force = true)
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
