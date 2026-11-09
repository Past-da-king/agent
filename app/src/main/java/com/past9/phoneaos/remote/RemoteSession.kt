package com.past9.phoneaos.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.room.withTransaction
import com.past9.phoneaos.App
import com.past9.phoneaos.BuildConfig
import com.past9.phoneaos.agent.AgentStatus
import com.past9.phoneaos.agent.sharedHttp
import com.past9.phoneaos.data.AppDb
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.data.MemoryRow
import com.past9.phoneaos.data.TaskRow
import com.past9.phoneaos.data.TriggerRow
import com.past9.phoneaos.system.ChatOnScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** This phone as the computer should know it: its name, phone or tablet, and its screen in dp right now. */
fun phoneFacts(context: Context): DeviceFacts {
    val c = context.resources.configuration
    val name = runCatching { android.provider.Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL
    return DeviceFacts(name, Pairing.kindFor(c.smallestScreenWidthDp), BuildConfig.VERSION_NAME, c.screenWidthDp, c.screenHeightDp)
}

/** What the agent runtime hands over while the phone is linked: the computer runs the turns. */
interface RemoteSink {
    fun send(text: String, images: List<String>, quiet: Boolean)
    /** With files of any kind (a shared voice note, PDF or document): they must reach the computer with the message. */
    fun send(text: String, images: List<String>, files: List<String>, quiet: Boolean) = send(text, images, quiet)
    /** Returns once the computer accepted it; throws when it did not. */
    suspend fun sendAccepted(text: String, images: List<String>) = send(text, images, false)
    suspend fun sendAccepted(text: String, images: List<String>, files: List<String>) = sendAccepted(text, images)
    fun answer(itemId: Long, option: String)
    fun stop()
    fun stopHelper(id: Long): Boolean
}

/** Where the link stands, for the strip at the top of the screen. */
sealed interface LinkState {
    data object Connecting : LinkState
    data class Connected(val brain: BrainRef, val url: String) : LinkState
    /** Nobody can answer. [rescan]: only linking again fixes it (the computer forgot this phone, or its certificate changed). */
    data class NoAgents(val why: String, val rescan: Boolean = false) : LinkState
    /** The last try failed for [why]; the next one is at [atMs] (Retry tries now). */
    data class Retrying(val why: String, val atMs: Long) : LinkState
    data object Paused : LinkState
}

/** How long to wait before try number [failures] (1, 2, 4 ... 30 s, give or take a fifth so phones do not march in step). */
internal fun backoffMs(failures: Int, random: Double = Math.random()): Long {
    if (failures <= 0) return 0
    val base = minOf(RemoteSession.MAX_BACKOFF_MS, 1_000L shl minOf(failures - 1, 15))
    return (base * (0.8 + 0.4 * random)).toLong().coerceAtMost(RemoteSession.MAX_BACKOFF_MS)
}

/** What the screen says for each way a link fails: what happened and what to do. */
internal fun linkReason(problem: LinkProblem, name: String): String = when (problem) {
    LinkProblem.Unpaired -> "$name no longer knows this phone. Scan its QR code again: Settings, Linked devices, Link to a different computer."
    LinkProblem.CertChanged -> "$name's certificate changed since this phone was linked. Scan its QR code again to trust it."
    LinkProblem.Unreachable -> "Can't reach $name. Is the Agent app open there, with Devices on, on the same network?"
    LinkProblem.Dropped -> "The link to $name dropped."
}

/** The phone's link to its brains. One per link; [RemoteHub] owns it. */
class RemoteSession(private val app: Context, link: Link, val db: AppDb) : RemoteSink {
    @Volatile var link: Link = link
        private set
    val ids = RemoteIds()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = ConcurrentHashMap<String, OkHttpClient>()
    private val kick = Channel<Unit>(Channel.CONFLATED)

    private val _state = MutableStateFlow<LinkState>(LinkState.Connecting)
    val state: StateFlow<LinkState> = _state
    private val _status = MutableStateFlow(AgentStatus())
    /** The same shape the local agent reports, so the screens show the computer's work the same way. */
    val status: StateFlow<AgentStatus> = _status
    /** Every brain's last answer to /v1/health (null = not answering), for Settings > Linked devices. */
    private val _health = MutableStateFlow<Map<String, Health?>>(emptyMap())
    val health: StateFlow<Map<String, Health?>> = _health

    /**
     * The computer's helper and project agent cards (RPC helpers.cards, event helpers.cards): the brain is the one
     * source; the phone keeps only its newest list. Null while the computer does not publish them (an older build).
     */
    private val _cards = MutableStateFlow<List<LiveCard>?>(null)
    val cards: StateFlow<List<LiveCard>?> = _cards
    @Volatile private var cardsSeq = -1L
    /** The computer's newest list as it sent it; [cards] is this less what he swiped away. */
    @Volatile private var cardsRaw: List<LiveCard>? = null
    /** Cards he swiped away (row id -> when), so they leave at once and stay gone until they move again. */
    private val swiped = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** The cards were last heard from the computer at this time; with no link for STALE_CARDS_MS they are dropped. */
    @Volatile private var cardsStaleJob: kotlinx.coroutines.Job? = null

    private fun publishCards(list: List<LiveCard>?) {
        // One rule for the whole app (LiveCards.reconcile): the computer's newest list wins outright, and a card whose
        // brain is not answering is never counted as working.
        val live = rpc?.isOpen == true
        val next = LiveCards.reconcile(cardsRaw, list, live)
        cardsRaw = next
        // A swipe is forgotten once the computer no longer lists that card.
        if (next != null) swiped.keys.retainAll(next.map { it.row }.toSet())
        _cards.value = next?.let { LiveCards.visible(it, swiped) }
    }

    @Volatile private var rpc: RpcClient? = null

    // ---- data use (0.14.11): small reads on mobile data, deltas on reconnect, nothing re-pulled per chat nudge ----------
    // The computer (0.2.90 and later) understands device.prefs, data.since, data.page and list limits. An older one does
    // not: then the phone reads as before (bounded by that computer), but still never reloads on a chat nudge.
    @Volatile private var protocolV2 = false
    /** On a metered network (mobile data): only the newest messages and open cards up front, no media, no card stream. */
    @Volatile var metered = false
        private set
    @Volatile private var helpersOpen = false
    private var syncJob: Job? = null
    @Volatile private var lastSeq = -1L
    private val loadedKinds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val _earlierMore = MutableStateFlow(false)
    /** Older messages are on the computer: the chat offers Load earlier. */
    val earlierMore: StateFlow<Boolean> = _earlierMore
    private val _earlierLoading = MutableStateFlow(false)
    val earlierLoading: StateFlow<Boolean> = _earlierLoading
    private val cm: ConnectivityManager? by lazy { app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager }
    private val netCb = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { networkChanged() }
        override fun onLost(network: Network) { networkChanged() }
    }
    private fun readMetered(): Boolean = runCatching { cm?.isActiveNetworkMetered ?: false }.getOrDefault(false)
    init {
        metered = readMetered()
        runCatching { cm?.registerDefaultNetworkCallback(netCb) }
    }
    @Volatile private var current: Pick? = null
    @Volatile private var turnRunning = false
    /** Issue #15: the newest thing the agent had said when this turn started, so only the turn's own words are announced. */
    @Volatile private var replyAfterId = 0L
    @Volatile private var foreground = true
    @Volatile private var holdingWork = false
    private var loop: Job? = null
    private var pauseJob: Job? = null
    private var lastFacts: String = ""
    val commands by lazy { DeviceCommands(app, this) }

    // ---- connecting and failover ------------------------------------------------------------
    // One WebSocket per link carries every call and event; the health checks reuse pooled connections.
    // 22 Oct 2026: a socket still opening used to count as "not ok", so the next check replaced it, and closing the
    // old one kicked the next check at once. The check always beat the TLS handshake, so the phone opened about 15
    // new connections a second, forever, and never connected. Now a socket that is opening is left alone, a
    // replaced socket is detached before it is closed (no kick), and failures wait 1, 2, 4 ... 30 s.

    @Volatile private var failures = 0
    @Volatile private var nextTryAt = 0L
    @Volatile private var connectStartedAt = 0L
    private val trusts = ConcurrentHashMap<String, Pinning.PinnedTrust>()
    /** Brains that left a message unanswered, until when they are passed over. */
    private val notAnswering = ConcurrentHashMap<String, Long>()
    /** Messages on their way to a brain now. */
    private val pendingSends = java.util.concurrent.atomic.AtomicInteger()
    /** The brain the chat was last told is standing in, so it says so once. */
    @Volatile private var standIn: String? = null

    fun start() {
        if (loop?.isActive == true) return
        _state.value = if (current != null && rpc?.isOpen == true) _state.value else LinkState.Connecting
        loop = scope.launch {
            while (true) {
                // Backing off: a kick (a failed call, the socket dropping) does not cut the wait short; Retry does.
                val wait = nextTryAt - System.currentTimeMillis()
                if (wait > 0) { withTimeoutOrNull(wait) { kick.receive() }; continue }
                // An open socket is its own health check: on mobile data nothing else is asked (each check is a new TLS
                // handshake and a request). Off mobile data an open link is looked at once a minute, not every 15 s.
                val open = rpc?.isOpen == true && current != null
                if (!(open && metered)) runCatching { probeAndMove() }
                withTimeoutOrNull(if (open) (if (metered) 300_000L else 60_000L) else 15_000L) { kick.receive() }
            }
        }
    }

    /** Check every brain now (Retry, coming back on screen). */
    fun retry() {
        if (_state.value is LinkState.NoAgents || _state.value is LinkState.Retrying) _state.value = LinkState.Connecting
        nextTryAt = 0
        kick.trySend(Unit); start()
    }

    private fun clientFor(fp: String): OkHttpClient = clients.getOrPut(fp) {
        Pinning.client(sharedHttp, trusts.getOrPut(fp) { Pinning.PinnedTrust({ listOf(fp) }) })
    }

    /** The open socket goes without telling the loop (it is being replaced or the link is pausing). */
    private fun detach() {
        val old = rpc
        rpc = null
        old?.close()
    }

    private fun nameOf(pick: Pick?): String = (pick?.brain?.name ?: link.nodeName).ifBlank { "your computer" }

    /** Words a person can act on, for the strip and Settings. */
    internal fun reasonFor(problem: LinkProblem, name: String): String = linkReason(problem, name)

    private suspend fun probeAndMove() {
        val brains = link.brains
        val seen = HashMap<String, Health?>()
        val now = System.currentTimeMillis()
        val skip = notAnswering.filterValues { it > now }.keys.toSet()
        val top = BrainChooser.ordered(brains).firstOrNull()?.id
        val best = BrainChooser.firstReady(brains, skip) { b, u ->
            // The first brain answers fast or not at all. One further down may be a loaded server behind a relay:
            // give it longer, it is the one that answers when the first is gone.
            val h = RemoteApi(clientFor(b.fp)).health(u, if (b.id == top) 3_000 else 6_000)
            synchronized(seen) { if (h != null || !seen.containsKey(b.id)) seen[b.id] = h }
            h != null && h.ready && (h.meshId.isBlank() || link.meshId.isBlank() || h.meshId == link.meshId)
        }
        // Brains after the chosen one were not asked this round: ask them too, so Settings shows every brain's state,
        // beside the move and not before it.
        scope.launch {
            val all = synchronized(seen) { HashMap(seen) }
            for (b in brains) if (!all.containsKey(b.id)) all[b.id] = if (b.id in skip) null else b.urls.firstNotNullOfOrNull { u -> RemoteApi(clientFor(b.fp)).health(u, 2_000) }
            _health.value = all
        }
        val cur = current
        val r = rpc
        val curSeen = cur?.let { c -> synchronized(seen) { seen[c.brain.id] } }
        // A socket that is still opening is left to finish (it gets CONNECT_GRACE_MS); replacing it is what stormed.
        if (r != null && !r.isOpen && System.currentTimeMillis() - connectStartedAt < CONNECT_GRACE_MS) return
        // An open socket is the best sign the brain is there: one missed health check does not drop it, a brain
        // that says it is not ready does.
        val curOk = cur != null && r?.isOpen == true && curSeen?.ready != false
        when (val m = BrainChooser.decide(brains, cur, curOk, best, turnRunning)) {
            is BrainChooser.Move.To -> connectTo(m.pick)
            BrainChooser.Move.NoneAvailable -> {
                detach(); current = null
                val certChanged = brains.firstOrNull { trusts[it.fp]?.refused == true }
                _state.value = when {
                    certChanged != null -> LinkState.NoAgents(reasonFor(LinkProblem.CertChanged, certChanged.name.ifBlank { nameOf(null) }), rescan = true)
                    brains.size <= 1 -> LinkState.NoAgents(reasonFor(LinkProblem.Unreachable, brains.firstOrNull()?.name?.ifBlank { null } ?: nameOf(null)))
                    // A message on its way: he hears that it is safe, and that the computers are the problem (an overloaded server is one).
                    pendingSends.get() > 0 -> LinkState.NoAgents("None of your computers are answering right now. Still trying; your message is safe.")
                    else -> LinkState.NoAgents("None of your computers answered. Are the Agent apps open there, with Devices on, on the same network?")
                }
                _status.value = AgentStatus()
            }
            BrainChooser.Move.Stay -> {}
        }
    }

    /** A brain that didn't answer a message: left now, and not tried again for a little while. */
    private fun leaveUnanswering() {
        val cur = current ?: return
        notAnswering[cur.brain.id] = System.currentTimeMillis() + SendFlow.SKIP_MS
        android.util.Log.i("RemoteLink", "leaving ${cur.url}: no word back on a message")
        detach(); current = null
        nextTryAt = 0
        _state.value = LinkState.Connecting
        kick.trySend(Unit)
    }

    private fun connectTo(pick: Pick) {
        detach()
        current = pick
        // A new connection (maybe another brain) numbers its lists afresh.
        cardsSeq = -1L
        cardsStaleJob?.cancel(); cardsStaleJob = null
        if (_state.value !is LinkState.Retrying && _state.value !is LinkState.NoAgents) _state.value = LinkState.Connecting
        connectStartedAt = System.currentTimeMillis()
        val c = RpcClient(clientFor(pick.brain.fp), pick.url, link.pass, ::hello, object : RpcClient.Listener {
            override fun onOpen(rpc: RpcClient) {
                if (this@RemoteSession.rpc !== rpc) return
                _state.value = LinkState.Connected(pick.brain, pick.url)
                syncJob?.cancel()
                syncJob = scope.launch { connectSync(pick); runCatching { readRunState() }; pushDictations() }
            }
            override fun onEvent(rpc: RpcClient, name: String, payload: Any?) { if (this@RemoteSession.rpc === rpc) onEvent(name, payload) }
            override fun onClosed(rpc: RpcClient, why: String, opened: Boolean, problem: LinkProblem) {
                if (this@RemoteSession.rpc !== rpc) return
                this@RemoteSession.rpc = null
                // Cards from a computer the phone no longer hears are not shown for ever: with no link back within
                // STALE_CARDS_MS they go, and the next link brings the list as it is then.
                cardsStaleJob?.cancel()
                cardsStaleJob = scope.launch {
                    kotlinx.coroutines.delay(STALE_CARDS_MS)
                    if (this@RemoteSession.rpc == null && _cards.value != null) publishCards(emptyList())
                }
                val now = System.currentTimeMillis()
                // A link that stayed up a while was fine: try again at once. One that failed or fell over waits longer each time.
                failures = if (opened && now - rpc.openedAt >= STABLE_MS) 0 else failures + 1
                val name = nameOf(pick)
                android.util.Log.i("RemoteLink", "link to ${pick.url} ended: $why ($problem, opened=$opened, failures=$failures)")
                if (problem == LinkProblem.Unpaired || problem == LinkProblem.CertChanged) {
                    // Only linking again fixes these: say so, and look again only now and then.
                    current = null
                    nextTryAt = now + MAX_BACKOFF_MS
                    _state.value = LinkState.NoAgents(reasonFor(problem, name), rescan = true)
                    _status.value = AgentStatus()
                    return
                }
                nextTryAt = now + backoffMs(failures)
                if (foreground || holdingWork) {
                    _state.value = if (failures == 0) LinkState.Connecting else LinkState.Retrying(reasonFor(problem, name), nextTryAt)
                    kick.trySend(Unit)
                }
            }
        })
        rpc = c
        c.connect()
    }

    /** The brain list can change on the computer (a new brain, a new order): take it from GET /v1/devices. */
    private suspend fun refreshBrains(pick: Pick) {
        val fresh = RemoteApi(clientFor(pick.brain.fp)).brains(pick.url, link.pass) ?: return
        if (fresh.isEmpty()) return
        // The name he sees is the computer that answers first now, not the one this phone first scanned.
        val name = BrainChooser.ordered(fresh).first().name.ifBlank { link.nodeName }
        if (fresh.toSet() == link.brains.toSet() && name == link.nodeName) return
        link = link.copy(brains = fresh, nodeName = name)
        RemoteHub.save(app, link)
    }

    private suspend fun readRunState() {
        val v = call("chat.state", listOf()) as? JSONObject ?: return
        turnRunning = v.optBoolean("running"); publishStatus()
    }

    /** The cards as they are now on the computer (on connect, after a failover, after the app reopens). */
    suspend fun loadCards() {
        val v = try { call("helpers.cards", listOf(), 20_000) } catch (e: RpcException) {
            // An older computer has no cards to give: the chat keeps showing its helper rows as before.
            if (e.message.orEmpty().contains("not available")) publishCards(null)
            return
        }
        val (seq, list) = LiveCards.parse(v as? JSONObject) ?: return
        cardsSeq = seq; publishCards(list)
    }

    private fun onCards(payload: Any?) {
        val (seq, list) = LiveCards.parse(payload as? JSONObject) ?: return
        if (seq <= cardsSeq) return
        cardsSeq = seq; publishCards(list)
    }

    /**
     * A swipe on a card (the desktop X): it leaves the list at once; the computer stops it if it still works and
     * takes it off every device (helpers.dismiss). A computer too old for that stops a working card as before.
     */
    fun dismissCard(c: LiveCard) {
        swiped[c.row] = maxOf(System.currentTimeMillis(), c.movedAt)
        publishCards(cardsRaw)
        scope.launch {
            val done = runCatching { call("helpers.dismiss", listOf(c.id), 20_000) == true }
            done.onFailure { e ->
                if (e.message.orEmpty().contains("not available")) {
                    if (c.working && !c.waiting) runCatching { if (c.isProject) call("chat.stop", listOf(c.projectChat)) else call("helpers.stop", listOf(c.id)) }
                } else notice("Couldn't close ${c.label} on ${brainName()}: ${e.message}")
            }
        }
    }

    /** Is a brain answering this phone right now? A card with no brain behind it is not working (30 Oct 2026). */
    fun brainAlive(): Boolean = rpc?.isOpen == true

    /** The card as the phone's helper card draws it. */
    fun cardView(c: LiveCard) = LiveCards.view(c, ids)

    /** Stop from a card: a project agent's card stops its project chat; a helper's stops that helper. */
    fun stopCard(c: LiveCard) {
        scope.launch {
            runCatching { if (c.isProject) call("chat.stop", listOf(c.projectChat)) else call("helpers.stop", listOf(c.id)) }
                .onFailure { notice("Couldn't stop ${c.label} on ${brainName()}: ${it.message}") }
        }
    }

    // ---- what the phone says about itself ---------------------------------------------------

    fun facts(): DeviceFacts = phoneFacts(app)

    private fun hello(): JSONObject {
        val f = facts().toJson()
        lastFacts = f.toString()
        return JSONObject().put("device", f).put("tools", DeviceTools.catalogue(app))
    }

    /** The tools it lends changed (a switch in Settings): say hello again with the new list. */
    fun factsChangedForce() { rpc?.sendHello() }

    /** On rotation or a new window size: say hello again so replies fit the screen. */
    fun factsMaybeChanged() {
        val f = facts().toJson().toString()
        if (f != lastFacts) { lastFacts = f; rpc?.sendHello() }
    }

    // ---- the app being on screen or not -------------------------------------------------------

    fun onForeground() {
        foreground = true
        pauseJob?.cancel(); pauseJob = null
        // Back on screen: the working list and the Home count are re-read, so nothing that ended meanwhile is still shown.
        if (rpc?.isOpen == true) scope.launch { runCatching { loadCards() } }
        if (_state.value is LinkState.Paused) _state.value = LinkState.Connecting
        // Back on screen: try now rather than at the end of a backoff wait.
        if (rpc?.isOpen != true && nextTryAt > System.currentTimeMillis()) retry()
        start(); factsMaybeChanged()
    }

    /** Off screen: keep the socket only while the computer is working on a reply, never otherwise (battery). */
    fun onBackground() {
        foreground = false
        pauseJob?.cancel()
        pauseJob = scope.launch {
            delay(30_000)
            if (!foreground && !turnRunning) pause()
        }
        if (turnRunning) hold(true)
    }

    private fun pause() {
        loop?.cancel(); loop = null
        detach(); current = null
        _state.value = LinkState.Paused
        hold(false)
    }

    private fun hold(on: Boolean) {
        if (on == holdingWork) return
        holdingWork = on
        val phone = App.graph(app).phone
        if (on) phone.workStarted() else phone.workFinished()
    }

    fun close() {
        runCatching { cm?.unregisterNetworkCallback(netCb) }
        hold(false)
        detach()
        scope.cancel()
    }

    // ---- calls --------------------------------------------------------------------------------

    suspend fun call(method: String, args: List<Any?>, timeoutMs: Long = 30_000): Any? {
        val r = rpc ?: run { kick.trySend(Unit); throw RpcException("Not connected to a computer right now.") }
        return try { r.call(method, args, timeoutMs) } catch (e: RpcException) {
            if (!r.isOpen) kick.trySend(Unit)
            throw e
        }
    }

    /** Never a machine name in errors and status: which computer answers lives in Settings > Linked devices. Only a stand-in says its name, once. */
    private fun brainName(): String = "your computer"

    /** A line in the chat when something the person did couldn't reach the computer. */
    private fun notice(text: String) = scope.launch {
        db.chat().insert(ChatItem(id = ids.local("phone-notice-" + System.nanoTime(), System.currentTimeMillis()), kind = "notice", text = text))
    }

    /**
     * A message on its way, to whichever brain will take it: see [SendFlow]. Returns once a brain holds it, which on a
     * computer before 0.2.115 (the old chat.send) means once its turn has ended, as it always did.
     */
    private suspend fun deliver(words: String, images: List<String>, chat: String? = null, replyIn: String? = null, files: List<String> = emptyList()) {
        pendingSends.incrementAndGet()
        try { deliverNow(words, images, chat, replyIn, files) } finally { pendingSends.decrementAndGet() }
    }

    private suspend fun deliverNow(words: String, images: List<String>, chat: String?, replyIn: String?, files: List<String> = emptyList()) {
        val sendId = java.util.UUID.randomUUID().toString()
        val sentAt = System.currentTimeMillis()
        var attachments = JSONArray()
        var uploadedTo: String? = null
        // The same pictures and files go up once per brain, however many times the send has to be tried.
        val uploadKey = (images + files).joinToString("|")
        SendFlow(
            linkUp = { ms -> awaitLink(ms); rpc?.isOpen == true },
            prepare = {
                val here = (current?.brain?.id ?: "") + "#" + uploadKey
                if ((images.isNotEmpty() || files.isNotEmpty()) && uploadedTo != here) {
                    attachments = uploadEvery(images, files); uploadedTo = here
                }
            },
            attempt = { fresh ->
                val body = JSONObject().put("sendId", sendId).put("sentAt", sentAt).put("fresh", fresh).put("text", words).put("attachments", attachments)
                if (chat != null) body.put("chat", chat)
                if (replyIn != null) body.put("replyIn", replyIn)
                call("chat.sendOnce", listOf(body), SendFlow.ACK_WAIT_MS + 1_000) as? JSONObject
            },
            socketOpen = { rpc?.isOpen == true },
            leave = ::leaveUnanswering,
            legacy = { handOver(words, attachments, chat, replyIn) },
        ).run()
        sayWhoIsStandingIn()
    }

    /**
     * The old chat.send (a computer before 0.2.116), put on the wire once and NOT waited for: that call answers only
     * when the whole turn has ended, and waiting for it is what kept his words in the box and the Send button spinning
     * for minutes (30 Oct 2026). True: the frame went out, so this brain has the message.
     */
    private fun handOver(words: String, attachments: JSONArray, chat: String?, replyIn: String?): Boolean {
        val args: List<Any?> = when {
            replyIn != null -> Mentions.replyArgs(chat.orEmpty(), words, attachments)
            chat != null -> listOf(words, attachments, chat)
            attachments.length() > 0 -> listOf(words, attachments)
            else -> listOf(words)
        }
        val r = rpc ?: return false
        return r.cast("chat.send", args)
    }

    /** He is on a brain further down because the first isn't answering: the chat says so, once. */
    private fun sayWhoIsStandingIn() {
        val cur = current?.brain ?: return
        val top = BrainChooser.ordered(link.brains).firstOrNull()
        if (top == null || cur.id == top.id) { standIn = null; return }
        if (standIn == cur.id) return
        standIn = cur.id
        notice("Answered by ${cur.name.ifBlank { "your server" }}, your PC isn't responding")
    }

    /** The link may be between two sockets (the app just came back, the network moved): give it a moment. */
    private suspend fun awaitLink(ms: Long = 20_000) {
        if (rpc?.isOpen == true) return
        retry()
        withTimeoutOrNull(ms) { while (rpc?.isOpen != true) kotlinx.coroutines.delay(250) }
    }

    override suspend fun sendAccepted(text: String, images: List<String>) = sendAccepted(text, images, emptyList())

    override suspend fun sendAccepted(text: String, images: List<String>, files: List<String>) {
        val clean = text.trim().ifEmpty { if (images.isNotEmpty() || files.isNotEmpty()) "What do you make of this?" else "" }
        if (clean.isEmpty()) return
        try {
            turnRunning = true; publishStatus()
            deliver(clean, images, null, null, files)
            runCatching { readRunState() }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            turnRunning = false; publishStatus()
            notice("Couldn't send that to ${brainName()}: ${e.message}. Your words are still in the message box.")
            throw e
        }
    }

    /** What the computer lacks of the dictations saved here: words only, never the audio. */
    fun pushDictations() {
        scope.launch { runCatching { com.past9.phoneaos.voice.Dictations.push(app) { p -> call("recordings.fromDevice", listOf(p), 20_000) } } }
    }

    override fun send(text: String, images: List<String>, quiet: Boolean) = send(text, images, emptyList(), quiet)

    override fun send(text: String, images: List<String>, files: List<String>, quiet: Boolean) {
        val clean = text.trim().ifEmpty { if (images.isNotEmpty() || files.isNotEmpty()) "What do you make of this?" else "" }
        if (clean.isEmpty()) return
        scope.launch {
            try {
                turnRunning = true; publishStatus()
                deliver(clean, images, null, null, files)
                // A message the computer sent on to a project chat ('@Acme ...') starts no run here: read the real state,
                // or the chat stays on "Working" with a Stop button (29 Oct 2026, seen on his phone).
                runCatching { readRunState() }
            } catch (e: Exception) {
                turnRunning = false; publishStatus()
                notice("Couldn't send that to ${brainName()}: ${e.message}")
            }
        }
    }

    override fun answer(itemId: Long, option: String) {
        // A question from a lent tool (ask_user on this phone for the computer's agent), answered in the chat.
        if (RemoteAsk.answerItem(itemId, option)) {
            scope.launch { db.chat().get(itemId)?.let { db.chat().update(it.copy(meta = JSONObject(it.meta).put("answer", option).toString())) } }
            return
        }
        val rid = ids.remote(itemId) ?: return
        scope.launch {
            val item = db.chat().get(itemId)
            item?.let { db.chat().update(it.copy(meta = JSONObject(it.meta).put("answer", option).toString())) }
            runCatching {
                val approval = item?.kind == "approval" || item?.let { runCatching { JSONObject(it.meta).optString("remoteKind") }.getOrNull() } == "approval"
                if (approval) call("chat.decide", listOf(rid, if (isYes(option)) "approve" else "decline"))
                else call("chat.answer", listOf(rid, option))
            }.onFailure { notice("Couldn't answer on ${brainName()}: ${it.message}") }
        }
    }

    /**
     * The computer asks something (a question or an approval, from its agent, a helper or a project): a notification
     * with the answers as buttons, so he can answer from the shade (system/AnswerReceiver -> runtime.answer -> this
     * session -> chat.answer or chat.decide). Answered anywhere (here, the chat, the computer): the notification goes.
     */
    private fun cardNotice(row: JSONObject, isNew: Boolean) {
        val lid = ids.localOrNull(row.optString("id")) ?: return
        if (!RemoteRows.isOpenCard(row)) {
            runCatching { androidx.core.app.NotificationManagerCompat.from(app).cancel(lid.toInt()) }
            return
        }
        if (!isNew) return
        val item = RemoteRows.chat(row, ids, notes)
        val meta = runCatching { JSONObject(item.meta) }.getOrDefault(JSONObject())
        val approval = meta.optString("remoteKind") == "approval"
        val by = meta.optString("whyBy")
        val title = when {
            approval -> "Approve?"
            by.isNotBlank() -> "$by asks"
            else -> "Your agent asks"
        }
        val body = if (approval) item.text.split("|").getOrElse(1) { item.text } else item.text
        val opts = meta.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
        runCatching { App.graph(app).phone.notify(title, body, lid, if (approval) listOf("Approve", "Decline") else opts) }
    }

    private fun isYes(o: String): Boolean = o.trim().lowercase().let { s -> listOf("approve", "yes", "allow", "ok", "go", "send", "do it", "confirm").any { s.startsWith(it) } }

    override fun stop() { scope.launch { runCatching { call("chat.stop", listOf()) } } }

    override fun stopHelper(id: Long): Boolean {
        val rid = ids.remote(id) ?: return false
        scope.launch {
            val meta = runCatching { JSONObject(db.chat().get(id)?.meta ?: "{}") }.getOrDefault(JSONObject())
            val projectChat = meta.optString("projectChat")
            if (projectChat.isNotBlank()) runCatching { call("chat.stop", listOf(projectChat)) }
            else runCatching { call("helpers.stop", listOf(meta.optString("helper").ifBlank { rid })) }
        }
        return true
    }

    /**
     * Start a new chat on the computer (memory.newChat): it writes the New chat divider, forgets that thread's
     * session so the next turn carries none of the old conversation, and files the old chat to memory in the
     * background (nothing waits on that). The outcome says which kind of failure, if any: a computer too old to know
     * the call and one that cannot be reached need different words, and the phone is cleared either way.
     */
    suspend fun newChat(chat: String = "main"): NewChatOutcome = runCatching {
        call("memory.newChat", listOf(JSONObject().put("chat", chat).put("from", chat)), 20_000)
        reloadSoon()
        NewChatOutcome.STARTED
    }.getOrElse { error -> NewChatOutcome.of(error) }

    // ---- edits made on the phone's screens, written on the computer ------------------------------

    fun upsert(entity: String, row: JSONObject, after: suspend (JSONObject) -> Unit = {}) = scope.launch {
        runCatching { (call("data.upsert", listOf(entity, row)) as? JSONObject)?.let { after(it) } }
            .onFailure { notice("Couldn't save that on ${brainName()}: ${it.message}") }
    }

    fun remove(entity: String, localId: Long) = scope.launch {
        val rid = ids.remote(localId) ?: return@launch
        runCatching { call("data.remove", listOf(entity, rid)) }.onFailure { notice("Couldn't delete that on ${brainName()}: ${it.message}") }
    }

    // ---- the mirror: the computer's rows in this phone's own screens ----------------------------

    private suspend fun list(entity: String): List<JSONObject> {
        val a = call("data.list", listOf(entity), 90_000) as? JSONArray ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("id").isNotBlank() }
    }

    /** Read everything again (on connect, after a failover, or when the computer says many rows changed). */
    suspend fun reloadAll() {
        // A fresh read tries again any note that failed for a passing reason (the link was down, a timeout).
        noteFailed.entries.removeIf { it.value != NOTE_GONE }
        val goals = list("goal")
        val tasks = list("task")
        val memory = list("memory").filter(RemoteRows::isGeneralMemory)
        val triggers = list("trigger")
        val projects = list("project")
        val allChat = list("chat_item")
        // A New chat on the computer closes the rows before its divider without deleting them, so they come back in
        // data.list. Read the divider first and hide what it closed, exactly as the desktop's own chat does.
        dividerMain = RemoteRows.dividerAt(allChat)
        // The phone keeps the recent part of the main chat; the full history stays on the computer.
        // Plus every card still waiting on him, from any chat and however old: the computer counts those as waiting for
        // you, so Home's Needs you must show them and let him answer (22 Oct 2026: two helper cards were invisible).
        val mainTail = RemoteRows.openMain(allChat).sortedBy { it.optLong("createdAt") }.takeLast(CHAT_ROWS)
        val tailIds = mainTail.map { it.optString("id") }.toSet()
        val chat = (mainTail + allChat.filter { RemoteRows.isOpenCard(it) && it.optString("id") !in tailIds }).sortedBy { it.optLong("createdAt") }
        chat.forEach(::noteBrainRow)
        // Project chats show as helper rows with the project's icon.
        runCatching { (call("chat.states", listOf()) as? JSONArray)?.let { a -> running.clear(); for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optBoolean("running") }?.let { running += it.optString("chat") } } }
        projectOf.clear(); projects.forEach { p -> p.optJSONArray("chats").strings().forEach { c -> projectOf[c] = p } }
        projectRows.clear()
        allChat.forEach { r -> val c = RemoteRows.chatOf(r); if (projectOf.containsKey(c)) projectRows.getOrPut(c) { ArrayList() } += r }
        db.withTransaction {
            replace("goals", goals.map { RemoteRows.goal(it, ids) }.also { rows -> rows.forEach { putGoal(it) } }.map { it.id })
            replace("tasks", tasks.map { RemoteRows.task(it, ids) }.also { rows -> rows.forEach { putTask(it) } }.map { it.id })
            replace("memories", memory.map { RemoteRows.memory(it, ids) }.also { rows -> rows.forEach { putMemory(it) } }.map { it.id })
            replace("triggers", triggers.map { RemoteRows.trigger(it, ids) }.also { rows -> rows.forEach { db.triggers().upsert(it) } }.map { it.id })
            val keep = chat.map { RemoteRows.chat(it, ids, notes) }.onEach { putChat(it) }.map { it.id }.toMutableSet()
            projectRows.keys.forEach { c -> projectRow(c)?.let { putChat(it); keep += it.id } }
            // Questions from lent tools stay; notices the phone wrote itself go at the next full read.
            replace("chat_items", (keep + RemoteAsk.itemIds()).toList())
        }
        publishStatus()
        chat.forEach { fetchMedia(it) }
    }

    /** Delete rows of [table] that the computer no longer has. */
    private fun replace(table: String, keep: List<Long>) {
        val keepSet = keep.toSet()
        val gone = ArrayList<Long>()
        db.query("SELECT id FROM $table", null).use { c -> while (c.moveToNext()) c.getLong(0).let { if (it !in keepSet) gone += it } }
        val w = db.openHelper.writableDatabase
        gone.chunked(400).forEach { part -> w.execSQL("DELETE FROM $table WHERE id IN (${part.joinToString(",")})") }
    }

    private suspend fun putChat(c: ChatItem) { if (db.chat().get(c.id) == null) db.chat().insert(c) else db.chat().update(c) }
    private suspend fun putMemory(m: MemoryRow) { if (db.memory().get(m.id) == null) db.memory().insert(m) else db.memory().update(m) }
    private suspend fun putGoal(g: com.past9.phoneaos.data.GoalRow) { if (db.tasks().goal(g.id) == null) db.tasks().insertGoal(g) else db.tasks().updateGoal(g) }
    private suspend fun putTask(t: TaskRow) { if (db.tasks().task(t.id) == null) db.tasks().insertTask(t) else db.tasks().updateTask(t) }

    /** When the computer last started a new main chat (its "New chat" divider); 0 when it never has. */
    @Volatile private var dividerMain = 0L
    private var reloadJob: Job? = null
    private fun reloadSoon() { reloadJob?.cancel(); reloadJob = scope.launch { delay(400); runCatching { reloadAll() } } }

    /** One DataChange (desktop ipc.ts): the row as it is now, a deleted id, or reset. */
    suspend fun applyChange(o: JSONObject) {
        if (o.optBoolean("reset")) { if (protocolV2) resyncSoon() else reloadSoon(); return }
        val entity = o.optString("entity")
        val row = o.optJSONObject("row")
        if (o.optBoolean("deleted") || row == null) {
            val rid = o.optString("id").ifBlank { row?.optString("id").orEmpty() }
            val lid = ids.localOrNull(rid) ?: return
            db.withTransaction {
                when (entity) {
                    "chat_item" -> db.openHelper.writableDatabase.execSQL("DELETE FROM chat_items WHERE id = $lid")
                    "memory" -> db.memory().delete(lid)
                    "task" -> db.tasks().deleteTask(lid)
                    "goal" -> db.tasks().deleteGoal(lid)
                    "trigger" -> db.triggers().delete(lid)
                }
            }
            return
        }
        if (row.optString("id").isBlank()) return
        when (entity) {
            "chat_item" -> {
                val c = RemoteRows.chatOf(row)
                val isCard = row.optString("kind") in setOf("question", "approval")
                val newCard = RemoteRows.isOpenCard(row) && ids.localOrNull(row.optString("id")) == null
                // A New chat on the computer: what it closed comes off this phone at once, so the screen empties the
                // moment the person clears the chat (cards still waiting on them stay, as they do everywhere).
                if (c == "main" && RemoteRows.isDivider(row)) {
                    dividerMain = maxOf(dividerMain, row.optLong("createdAt"))
                    runCatching {
                        db.openHelper.writableDatabase.execSQL(
                            "DELETE FROM chat_items WHERE kind NOT IN ('question','approval') AND createdAt < $dividerMain")
                    }
                }
                // A row from before that divider is history: it is not put back (its open card, if any, still is).
                if (c == "main" && !RemoteRows.isDivider(row) && !RemoteRows.isOpenCard(row) && RemoteRows.closed(row, dividerMain)) return
                // A card from another chat: shown while it waits, and updated (answered) once the phone has it.
                if (c != "main" && (RemoteRows.isOpenCard(row) || (row.optString("kind") in setOf("question", "approval") && ids.localOrNull(row.optString("id")) != null))) {
                    putChat(RemoteRows.chat(row, ids, notes))
                }
                if (c == "main") { noteBrainRow(row); putChat(RemoteRows.chat(row, ids, notes)); fetchMedia(row) }
                else if (projectOf.containsKey(c)) {
                    val list = projectRows.getOrPut(c) { ArrayList() }
                    list.removeAll { it.optString("id") == row.optString("id") }; list += row
                    projectRow(c)?.let { putChat(it) }
                }
                if (isCard) cardNotice(row, newCard)
            }
            "project" -> if (!protocolV2) reloadSoon()
            "memory" -> if (RemoteRows.isGeneralMemory(row)) putMemory(RemoteRows.memory(row, ids)) else ids.localOrNull(row.optString("id"))?.let { db.memory().delete(it) }
            "task" -> putTask(RemoteRows.task(row, ids))
            "goal" -> putGoal(RemoteRows.goal(row, ids))
            "trigger" -> db.triggers().upsert(RemoteRows.trigger(row, ids))
        }
    }

    /**
     * What the computer says about the main chat's turn.
     *
     * Issue #15 (5 Nov 2026): when the computer runs the turn, the phone's own turn never ends, so nothing was posted
     * for the reply and the working notification simply went away with the computer's run. [tellReply] closes that.
     */
    internal fun runEvent(type: String) {
        when (type) {
            "run_started", "thinking" -> {
                // The first word of a turn marks the line: what the chat already holds is not news.
                if (!turnRunning) replyAfterId = newestAgentRowId()
                turnRunning = true; publishStatus()
            }
            "run_finished" -> {
                turnRunning = false; publishStatus()
                scope.launch { runCatching { tellReply() } }
                if (holdingWork && !foreground) scope.launch { delay(5_000); if (!foreground && !turnRunning) pause() }
                // Between turns: a higher brain that came back takes over now.
                kick.trySend(Unit)
            }
            // "chat_changed" says only that something changed: the rows themselves come as data.changed.
            // Reloading here pulled the whole chat again on every helper step (hundreds of MB an hour).
            "chat_changed" -> {}
        }
    }

    /**
     * A mirrored turn has ended: hand its last words to the same notification the phone's own turns use, unless the
     * chat is the screen in front of them. The words are read from the mirrored chat, because they arrive on their own
     * stream (data.changed) and can land a moment after the event that closed the turn.
     */
    internal suspend fun tellReply() {
        if (ChatOnScreen.showing()) return
        repeat(3) {
            val words = newestAgentWords()
            if (words != null) { App.graph(app).phone.replyPosted(words); return }
            delay(600)
        }
    }

    /** The newest thing the agent said since this turn started, if it said anything at all. */
    private fun newestAgentWords(): String? = runCatching {
        db.query("SELECT id, text FROM chat_items WHERE kind = 'agent' ORDER BY id DESC LIMIT 1", null).use { c ->
            if (!c.moveToFirst() || c.getLong(0) <= replyAfterId) null else c.getString(1)?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    /** The newest agent row the phone holds right now: the line this turn's own words will sit above. */
    private fun newestAgentRowId(): Long = runCatching {
        db.query("SELECT MAX(id) FROM chat_items WHERE kind = 'agent'", null).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }
    }.getOrDefault(0L)

    private fun publishStatus() {
        _status.value = AgentStatus(working = turnRunning, label = if (turnRunning) "Working" else "")
    }

    /** Every event the computer sends this phone, for listeners outside the session (mini-apps: data.changed, miniapps.push). */
    val events = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, Any?>>(extraBufferCapacity = 64)

    private fun onEvent(name: String, payload: Any?) {
        events.tryEmit(name to payload)
        when (name) {
            EVT_DATA -> (payload as? JSONObject)?.let { p -> scope.launch { runCatching { applyChange(p) }; p.optLong("seq", -1L).takeIf { it >= 0 }?.let(::noteSeq) } }
            EVT_CHAT -> (payload as? JSONObject)?.let { e ->
                val chat = e.optString("chat")
                if (chat.isNotEmpty() && chat != "main") {
                    // A project chat started or finished: its helper row follows.
                    when (e.optString("type")) { "run_started" -> running += chat; "run_finished" -> running -= chat; else -> return }
                    if (projectOf.containsKey(chat)) scope.launch { runCatching { projectRow(chat)?.let { putChat(it) } } }
                    return
                }
                runEvent(e.optString("type"))
            }
            EVT_CARDS -> onCards(payload)
            EVT_DEVICES -> current?.let { p -> scope.launch { runCatching { refreshBrains(p) } } }
            EVT_COMMAND -> (payload as? JSONObject)?.let { c -> scope.launch { commands.handle(c) } }
        }
    }

    // ---- @-mentions (29 Oct 2026): the picker's projects, and a project's own chat ----------------------------------

    private val mentionCache by lazy { MentionCache({ App.graph(app).settings.extra(it) }, { k, v -> App.graph(app).settings.setExtra(k, v) }) }
    private val _mentions by lazy { MutableStateFlow(mentionCache.projects) }
    /** The computer's projects for the '@' picker: the phone's copy at once, refreshed by [refreshMentions]. */
    val mentions: StateFlow<List<MentionProject>> get() = _mentions
    @Volatile private var mentionsAskedAt = 0L

    /**
     * The picker opened: show the kept list now and ask the computer whether it is still current, with the stamp the
     * phone has (a few bytes back when it is). At most once every 20 s, so a picker opened and closed costs nothing.
     */
    fun refreshMentions() {
        val now = System.currentTimeMillis()
        if (now - mentionsAskedAt < 20_000) return
        mentionsAskedAt = now
        scope.launch {
            runCatching { call("chat.mentions", listOf(mentionCache.stamp), 15_000) as? JSONObject }
                .onSuccess { r -> val changed = mentionCache.take(r); _mentions.value = mentionCache.projects; android.util.Log.i("Mentions", "list ${if (changed) "changed" else "same"} (${mentionCache.projects.size} projects, metered=$metered)") }
                .onFailure { mentionsAskedAt = 0L; android.util.Log.w("Mentions", "list not refreshed: ${it.message}") }
        }
    }

    /** A project chat's newest messages, oldest first (data.where by chat: a few rows, never the whole chat table). */
    suspend fun chatRows(chat: String, limit: Int = 40): List<JSONObject> {
        val where = JSONObject().put("chat", chat).put("kinds", JSONArray(listOf("user", "agent", "notice"))).put("limit", limit).put("newestFirst", true)
        val a = call("data.where", listOf("chat_item", where), 30_000) as? JSONArray ?: return emptyList()
        val rows = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        return RemoteRows.openIn(rows, chat).sortedBy { it.optLong("createdAt") }
    }

    /** Whether a chat on the computer is at work now. */
    suspend fun chatRunning(chat: String): Boolean = runCatching { (call("chat.state", listOf(chat), 15_000) as? JSONObject)?.optBoolean("running") == true }.getOrDefault(chat in running)

    /**
     * A message of his into one of the computer's chats (a project or helper chat), with its pictures. Until 0.15.11 this
     * always sent an empty attachment list, so a photo picked there never reached the computer (29 Oct 2026).
     */
    suspend fun sendTo(chat: String, text: String, images: List<String> = emptyList(), files: List<String> = emptyList()) {
        val words = text.trim().ifEmpty { if (images.isNotEmpty() || files.isNotEmpty()) "What do you make of this?" else "" }
        if (words.isEmpty()) return
        deliver(words, images, chat, files = files)
    }

    /**
     * Every picture of a message, saved on the computer first (files.savePasted: one call per picture, its bytes as
     * {$b64}), so chat.send carries only their paths. Same on Wi-Fi and mobile data: pictures are already scaled to
     * 1568 px JPEG on the phone. A picture that fails to go throws, so the message is never sent without it.
     */
    suspend fun uploadImages(images: List<String>): JSONArray = uploadEvery(images, emptyList())

    /**
     * Pictures and files on their way to the computer before the message (files.savePasted, the bytes), so the message
     * carries only their paths THERE. A shared voice note, PDF or document used to be listed by its path on the phone,
     * which the computer cannot open, so it silently never arrived (30 Oct 2026). Every upload is also kept where the
     * chat looks for a picture, so the bubble shows it at once instead of loading it back over the link.
     */
    private suspend fun uploadEvery(images: List<String>, files: List<String>): JSONArray {
        val attachments = JSONArray()
        for (p in images + files) {
            val f = File(p)
            if (!f.isFile) throw java.io.IOException("${f.name} is no longer on this phone.")
            val b64 = android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
            val saved = call("files.savePasted", listOf(f.name, mimeOf(f.name), JSONObject().put("\$b64", b64)), 120_000) as? JSONObject
                ?: throw java.io.IOException("Your computer didn't keep ${f.name}.")
            attachments.put(saved)
            rememberLocal(saved.optString("path"), f)
        }
        if (images.isNotEmpty() || files.isNotEmpty()) android.util.Log.i("Attach", "uploaded ${attachments.length()} of ${images.size + files.size} (metered=$metered)")
        return attachments
    }

    /** What this kind of file is, so the computer reads it the right way. */
    private fun mimeOf(name: String): String = when {
        name.endsWith(".png", true) -> "image/png"; name.endsWith(".webp", true) -> "image/webp"
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".opus", true) || name.endsWith(".ogg", true) || name.endsWith(".oga", true) -> "audio/ogg"
        name.endsWith(".m4a", true) -> "audio/mp4"; name.endsWith(".mp3", true) -> "audio/mpeg"; name.endsWith(".amr", true) -> "audio/amr"
        name.endsWith(".mp4", true) -> "video/mp4"; name.endsWith(".pdf", true) -> "application/pdf"
        name.endsWith(".txt", true) -> "text/plain"; name.endsWith(".csv", true) -> "text/csv"
        else -> "application/octet-stream"
    }

    /** The copy the chat draws: the very name the downloader would have used, so nothing is fetched back over the link. */
    private fun rememberLocal(pathOnComputer: String, f: File) {
        if (pathOnComputer.isBlank()) return
        UploadedMedia.remember(pathOnComputer, f.absolutePath)
        runCatching {
            val dir = File(app.cacheDir, "linked-media").apply { mkdirs() }
            val ext = pathOnComputer.substringAfterLast('.', "jpg").take(5).lowercase()
            val copy = File(dir, Integer.toHexString(pathOnComputer.hashCode()) + "-" + pathOnComputer.length + "." + ext)
            if (!copy.isFile || copy.length() == 0L) f.copyTo(copy, overwrite = true)
            UploadedMedia.remember(pathOnComputer, copy.absolutePath)
        }
    }

    /** A reply on a project's answer card, with its pictures: into that project chat, its answer back to the main chat. */
    suspend fun replyTo(chat: String, text: String, images: List<String> = emptyList(), files: List<String> = emptyList()) {
        val words = text.trim().ifEmpty { if (images.isNotEmpty() || files.isNotEmpty()) "What do you make of this?" else "" }
        if (words.isEmpty()) return
        deliver(words, images, chat, "main", files)
    }

    /** His machines on the link, with their state, for Connections. */
    suspend fun meshDevices(): List<MeshDevice> {
        val raw = call("devices.list", listOf(), 20_000)
        val answering = (state.value as? LinkState.Connected)?.brain?.id
        val ready = health.value.filterValues { it?.ready == true }.keys
        return MeshDevice.parse(raw, link.deviceId, answering, ready)
    }

    /** The web apps the computer's agent taught itself (read only here: they run there). */
    suspend fun learnedApps(): List<com.past9.phoneaos.connections.LearnedApp> = MeshDevice.learned(call("apps.learned", listOf(), 20_000))

    /**
     * The pictures of a row of another chat (a project chat the phone only looks into): each fetched once into the
     * same cache the main chat uses, path on the computer -> path here. On mobile data only when [force] (he tapped).
     */
    suspend fun picturesOf(meta: JSONObject, force: Boolean): Map<String, String> {
        val paths = RemoteRows.mediaPaths(meta)
        if (paths.isEmpty()) return emptyMap()
        val dir = File(app.cacheDir, "linked-media").apply { mkdirs() }
        val out = LinkedHashMap<String, String>()
        val (api, url) = downloader() ?: return emptyMap()
        for (p in paths) {
            val ext = p.substringAfterLast('.', "jpg").take(5).lowercase()
            val f = File(dir, Integer.toHexString(p.hashCode()) + "-" + p.length + "." + ext)
            if (f.exists() && f.length() > 0L) { out[p] = f.absolutePath; continue }
            if (metered && !force) continue
            runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { api.download(url, p, pass) { input, _ -> f.outputStream().use { input.copyTo(it) } } }; out[p] = f.absolutePath }
        }
        return out
    }

    // ---- the small way to sync (protocol v2) ------------------------------------------------------------------------

    private fun prefsJson(): JSONObject = JSONObject().put("v", 2).put("metered", metered).put("helpers", helpersOpen || !metered)

    private fun seqKey(pick: Pick) = "remote_seq_${link.deviceId}_${pick.brain.id}"
    private fun loadSeq(pick: Pick): Long = App.graph(app).settings.extra(seqKey(pick))?.toLongOrNull() ?: -1L

    /** The newest change-log number this phone holds; kept so a reconnect asks only for what came after. */
    private fun noteSeq(seq: Long) {
        if (seq <= lastSeq) return
        lastSeq = seq
        current?.let { p -> App.graph(app).settings.setExtra(seqKey(p), seq.toString()) }
    }

    private fun chatRowCount(): Int = db.query("SELECT COUNT(*) FROM chat_items WHERE kind IN ('user','agent')", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** First thing on every connection: say what this phone wants, then catch up by delta, or read the small first list. */
    private fun warmIds() {
        runCatching {
            db.query("SELECT id, meta FROM chat_items ORDER BY id DESC LIMIT 800", null).use { c ->
                while (c.moveToNext()) {
                    val rid = runCatching { JSONObject(c.getString(1)).optString("rid") }.getOrDefault("")
                    if (rid.isNotBlank()) ids.learn(rid, c.getLong(0))
                }
            }
        }
    }

    private suspend fun connectSync(pick: Pick) {
        loadedKinds.clear()
        warmIds()
        protocolV2 = try { call("device.prefs", listOf(prefsJson()), 15_000); true } catch (e: RpcException) { false }
        if (!protocolV2) {
            // An older computer: the way it always was (its own list is bounded).
            runCatching { refreshBrains(pick) }; runCatching { loadCards() }; runCatching { reloadAll() }
            return
        }
        if (!metered) runCatching { refreshBrains(pick) }
        lastSeq = loadSeq(pick)
        val caught = lastSeq >= 0 && chatRowCount() > 0 && runCatching { catchUp(lastSeq) }.getOrDefault(false)
        if (!caught) {
            lastSeq = -1L
            initialLoad(if (metered) FIRST_ROWS else CHAT_ROWS)
        }
        // The cards ARE the working list and the Home count: read on every link-up, mobile data or not (a few KB), so a
        // helper that ended while the phone was away, or before the computer restarted, can never stay on screen.
        runCatching { loadCards() }
        if (!metered) runCatching { fullSync(initial = caught) }
    }

    /** What changed after [seq], applied row by row. False: too much changed or the number is not known: read afresh. */
    private suspend fun catchUp(seq: Long): Boolean {
        val v = call("data.since", listOf(seq), 30_000) as? JSONObject ?: return false
        if (v.optBoolean("reset")) return false
        val a = v.optJSONArray("changes")
        if (a != null) for (i in 0 until a.length()) a.optJSONObject(i)?.let { runCatching { applyChange(it) } }
        noteSeq(v.optLong("seq", seq))
        publishStatus()
        return true
    }

    /** The newest [limit] messages of the main chat and every card waiting for an answer: nothing else. */
    private suspend fun initialLoad(limit: Int) {
        val r = call("data.list", listOf("chat_item", JSONObject().put("v", 2).put("limit", limit).put("withSeq", true)), 60_000) as? JSONObject ?: return
        val a = r.optJSONArray("rows") ?: return
        val rows = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("id").isNotBlank() }
        rows.forEach(::noteBrainRow)
        db.withTransaction { rows.forEach { putChat(RemoteRows.chat(it, ids, notes)) } }
        _earlierMore.value = r.optBoolean("more")
        noteSeq(r.optLong("seq", -1L))
        publishStatus()
        rows.forEach { fetchMedia(it) }
        rows.forEach { runCatching { cardNoticeIfOpen(it) } }
    }

    private fun cardNoticeIfOpen(row: JSONObject) { if (row.optString("kind") in setOf("question", "approval") && RemoteRows.isOpenCard(row)) cardNotice(row, false) }

    /** Off mobile data: everything, in the background, so it is all here when it is wanted. */
    private suspend fun fullSync(initial: Boolean) {
        if (initial) initialLoad(CHAT_ROWS)
        for (e in LISTS) runCatching { loadEntity(e) }
    }

    /** A list is read when its screen opens (mobile data) and the first time it is asked for on a connection. */
    fun need(vararg entities: String) {
        if (!protocolV2) return
        scope.launch { for (e in entities) runCatching { ensureLoaded(e) } }
    }

    private suspend fun ensureLoaded(entity: String) {
        if (!loadedKinds.add(entity)) return
        try { loadEntity(entity) } catch (e: Exception) { loadedKinds.remove(entity); throw e }
    }

    private suspend fun loadEntity(entity: String) {
        loadedKinds.add(entity)
        val r = call("data.list", listOf(entity, JSONObject().put("v", 2).put("withSeq", true)), 60_000) as? JSONObject ?: return
        val a = r.optJSONArray("rows") ?: return
        val rows = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("id").isNotBlank() }
        db.withTransaction {
            when (entity) {
                "goal" -> replace("goals", rows.map { RemoteRows.goal(it, ids) }.onEach { putGoal(it) }.map { it.id })
                "task" -> replace("tasks", rows.map { RemoteRows.task(it, ids) }.onEach { putTask(it) }.map { it.id })
                "memory" -> replace("memories", rows.filter(RemoteRows::isGeneralMemory).map { RemoteRows.memory(it, ids) }.onEach { putMemory(it) }.map { it.id })
                "trigger" -> replace("triggers", rows.map { RemoteRows.trigger(it, ids) }.onEach { db.triggers().upsert(it) }.map { it.id })
            }
        }
    }

    private var resyncJob: Job? = null
    /** The computer says many rows changed: read afresh, small on mobile data, everything off it. */
    private fun resyncSoon() { resyncJob?.cancel(); resyncJob = scope.launch { delay(400); runCatching { loadedKinds.clear(); initialLoad(if (metered) FIRST_ROWS else CHAT_ROWS); if (!metered) fullSync(initial = false) } } }

    /** The network changed between mobile data and Wi-Fi: tell the computer; catch up in full on Wi-Fi, stop big transfers on mobile. */
    private fun networkChanged() {
        val now = readMetered()
        if (now == metered) return
        metered = now
        android.util.Log.i("RemoteLink", "network is now ${if (now) "metered" else "unmetered"}")
        if (!protocolV2 || rpc?.isOpen != true) return
        syncJob?.cancel(); resyncJob?.cancel()
        scope.launch { runCatching { call("device.prefs", listOf(prefsJson()), 15_000) } }
        if (!now) syncJob = scope.launch { runCatching { fullSync(initial = true) } }
    }

    /** The helpers list is on screen (or left): the computer streams the cards only then while this phone is on mobile data. */
    fun setHelpersOpen(on: Boolean) {
        if (helpersOpen == on) return
        helpersOpen = on
        if (!protocolV2 || rpc?.isOpen != true) return
        scope.launch { runCatching { call("device.prefs", listOf(prefsJson()), 15_000) }; if (on) runCatching { loadCards() } }
    }

    /** Load earlier: the next [PAGE_ROWS] older messages of the main chat. */
    fun loadEarlier() {
        if (!protocolV2 || _earlierLoading.value) return
        _earlierLoading.value = true
        scope.launch {
            try {
                val oldest = db.query("SELECT MIN(createdAt) FROM chat_items WHERE kind IN ('user','agent')", null).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }
                val r = call("data.page", listOf(if (oldest > 0) oldest else null, PAGE_ROWS), 30_000) as? JSONObject ?: return@launch
                val a = r.optJSONArray("rows")
                val rows = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("id").isNotBlank() }
                db.withTransaction { rows.forEach { putChat(RemoteRows.chat(it, ids, notes)) } }
                rows.forEach { fetchMedia(it) }
                _earlierMore.value = r.optBoolean("more")
            } catch (e: Exception) {
                notice("Couldn't load earlier messages: ${e.message}")
            } finally { _earlierLoading.value = false }
        }
    }

    private fun toolkitsOf(v: Any?): List<com.past9.phoneaos.tools.Toolkit> {
        val a = v as? JSONArray ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("slug").isNotBlank() }
            .map { com.past9.phoneaos.tools.Toolkit(it.optString("slug"), it.optString("name").ifBlank { it.optString("slug") }, it.optString("line"), it.optString("logo"), 0, false, true) }
    }

    /** Every app the computer can connect: read when the Add panel opens, never in the background. */
    suspend fun appCatalog(): List<com.past9.phoneaos.tools.Toolkit> = toolkitsOf(call("apps.catalog", listOf(), 60_000))

    suspend fun searchApps(query: String): List<com.past9.phoneaos.tools.Toolkit> = toolkitsOf(call("apps.search", listOf(query), 30_000))

    /** The computer's agent profiles (id, name): a new account can go straight into one. */
    suspend fun agentProfiles(): List<Pair<String, String>> {
        val a = call("profiles.list", listOf(), 20_000) as? JSONArray ?: return emptyList()
        // General (the default, which holds none of his accounts) is the same as no profile: the phone offers it once, as General.
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("id").isNotBlank() && !it.optBoolean("general") }
            .map { it.optString("id") to it.optString("name").ifBlank { "Profile" } }
    }

    /** The computer's profiles with their accounts (General left out: it holds none), for the Profiles section when linked. */
    suspend fun computerProfiles(): List<com.past9.phoneaos.data.AgentProfile> =
        com.past9.phoneaos.data.AgentProfile.fromComputer(call("profiles.list", listOf(), 20_000))

    /** Ask the computer to start connecting [app]; the sign-in link comes back to open in this phone's browser. */
    suspend fun connectApp(app: String, profileId: String): String {
        val v = call("apps.connectLink", listOf(app, profileId), 60_000) as? JSONObject ?: throw RpcException("The computer didn't answer.")
        if (!v.optBoolean("ok")) throw RpcException(v.optString("message").ifBlank { "The computer could not start the sign-in." })
        return v.optString("link")
    }

    /** The apps connected on the computer (Settings > Connections of the linked computer), read when the screen opens. */
    suspend fun connectedApps(): Pair<List<com.past9.phoneaos.tools.Connection>, List<com.past9.phoneaos.tools.Toolkit>> {
        val v = call("apps.status", listOf(), 30_000) as? JSONObject ?: throw RpcException("The computer didn't answer.")
        val a = v.optJSONArray("accounts") ?: JSONArray()
        val accounts = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        // One row per connection id: the computer can list an account once per profile it is in.
        val connected = accounts.distinctBy { it.optString("id") }.map { o -> com.past9.phoneaos.tools.Connection(o.optString("id"), o.optString("app"), o.optString("status").uppercase(), o.optString("label").ifBlank { o.optString("appName") }) }
        val kits = accounts.distinctBy { it.optString("app") }.map { o -> com.past9.phoneaos.tools.Toolkit(o.optString("app"), o.optString("appName").ifBlank { o.optString("app") }, "", o.optString("logo"), 0, false, true) }
        return connected to kits
    }

    // ---- project chats as helper rows, and pictures from the computer ----------------------------------

    private val projectOf = ConcurrentHashMap<String, JSONObject>()
    private val projectRows = ConcurrentHashMap<String, MutableList<JSONObject>>()
    private val running: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Project chats the computer already shows with its own helper row in the main chat: the phone adds none for them. */
    private val brainShown: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun noteBrainRow(row: JSONObject) {
        if (row.optString("kind") != "helper") return
        val c = runCatching { JSONObject(row.optString("meta").ifBlank { "{}" }).optString("projectChat") }.getOrDefault("")
        if (c.isNotBlank() && brainShown.add(c)) ids.localOrNull("project-chat:$c")?.let { lid -> scope.launch { runCatching { db.openHelper.writableDatabase.execSQL("DELETE FROM chat_items WHERE id = $lid") } } }
    }

    private fun projectRow(chat: String): ChatItem? {
        if (chat in brainShown || _cards.value != null) return null
        val p = projectOf[chat] ?: return null
        return RemoteRows.projectHelper(p, chat, projectRows[chat].orEmpty().toList(), chat in running, ids)
    }

    // ---- voice notes from the computer ------------------------------------------------------------

    /** The computer's voice notes, copied here once (they are only note names there, in its voice-notes folder). */
    private val noteDir: File by lazy { File(app.filesDir, "linked-notes").apply { mkdirs() } }
    private val noteFailed = ConcurrentHashMap<String, String>()
    private val noteFetching: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val notes = object : RemoteRows.NoteAudio {
        override fun local(name: String): File = File(noteDir, name)
        override fun failed(name: String): String? = noteFailed[name]
        override fun fetching(name: String): Boolean = name in noteFetching
        override val onTap: Boolean get() = metered
    }

    /** Ticks whenever a note starts, lands or fails, so an open voice bubble redraws from [notes] at once. */
    private val _noteTick = kotlinx.coroutines.flow.MutableStateFlow(0)
    val noteTick: kotlinx.coroutines.flow.StateFlow<Int> = _noteTick

    /**
     * A voice note's audio comes over the link with voice.noteAudio (the same call the desktop's own player uses), is
     * saved next to the phone's data and the row then points at it. When it cannot be had, the row says so: it never
     * turns into text on its own.
     */
    private fun fetchNote(row: JSONObject) {
        if (row.optString("kind") != "voice_note") return
        val raw = row.optString("meta").ifBlank { "{}" }
        val meta = runCatching { JSONObject(raw) }.getOrNull() ?: return
        fetchNoteAudio(RemoteRows.noteName(meta), raw, ids.localOrNull(row.optString("id")))
    }

    /** Gets one note by its name and points row [localId] (when known) at the phone's copy. */
    private fun fetchNoteAudio(name: String, rawMeta: String, localId: Long?) {
        if (name.isEmpty() || notes.local(name).let { it.isFile && it.length() > 44 } || !noteFetching.add(name)) return
        _noteTick.value++
        scope.launch {
            try {
                android.util.Log.i("VoiceNote", "fetching $name (metered=$metered)")
                val bytes = try {
                    RemoteRows.noteBytes(call("voice.noteAudio", listOf(name), 120_000)) ?: run { noteFailed[name] = NOTE_GONE; null }
                } catch (e: Exception) {
                    noteFailed[name] = noteError(e); null
                }
                if (bytes != null) {
                    val tmp = File(noteDir, "$name.part")
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(notes.local(name))) { tmp.delete(); noteFailed[name] = "Couldn't save the audio on this phone." }
                    else noteFailed.remove(name)
                }
                android.util.Log.i("VoiceNote", "$name: ${bytes?.size ?: 0} bytes, ${noteFailed[name] ?: "saved"}")
                val lid = localId ?: return@launch
                db.chat().get(lid)?.let { item ->
                    val rid = runCatching { JSONObject(item.meta).optString("rid") }.getOrDefault("")
                    val m = RemoteRows.voiceMeta(rawMeta, notes).apply { if (rid.isNotBlank()) put("rid", rid) }
                    db.chat().update(item.copy(meta = m.toString()))
                }
            } finally { noteFetching.remove(name); _noteTick.value++ }
        }
    }

    /** Why a note did not come, in his words: a dropped or slow link is "couldn't reach", anything else keeps the reason. */
    private fun noteError(e: Exception): String {
        val why = e.message ?: e::class.java.simpleName
        val link = e is java.io.IOException || e is kotlinx.coroutines.TimeoutCancellationException ||
            listOf("pong", "timed out", "timeout", "closed", "not connected", "connection", "unreachable").any { why.contains(it, ignoreCase = true) }
        return if (link) "Couldn't reach ${brainName()}. Check your connection and try again." else "Couldn't get the audio from ${brainName()}: $why"
    }

    /**
     * He tapped a computer voice note (play, or Try again): get it now, on Wi-Fi or mobile data alike. Needs only the
     * phone's own row (its note name), never the computer's id, so it works after a restart too.
     */
    fun getNote(localId: Long) = scope.launch {
        val item = db.chat().get(localId) ?: return@launch
        val meta = runCatching { JSONObject(item.meta) }.getOrNull() ?: return@launch
        val name = meta.optString("remoteNote").ifBlank { RemoteRows.noteName(meta) }
        if (name.isEmpty()) return@launch
        noteFailed.remove(name)
        val raw = JSONObject(item.meta).apply { remove("path"); remove("audio"); remove("audioError"); remove("remoteNote") }.toString()
        fetchNoteAudio(name, raw, localId)
    }

    /** He tapped "Try again" on a note whose audio did not come. */
    fun retryNote(localId: Long) = getNote(localId)

    /** Pictures in a chat row are paths on the computer: fetch each once through /v1/file and point the row at the copy. */
    private fun fetchMedia(row: JSONObject) {
        // On mobile data a picture or a voice note comes only when he taps it (fetchMediaFor).
        if (metered) return
        fetchNow(row)
    }

    /** He tapped a picture or a note that was left on the computer to save data. */
    fun fetchMediaFor(localId: Long) {
        scope.launch {
            val item = db.chat().get(localId) ?: return@launch
            if (item.kind == "voice") { getNote(localId); return@launch }
            val rid = ids.remote(localId) ?: runCatching { JSONObject(item.meta).optString("rid") }.getOrDefault("").ifBlank { return@launch }
            fetchNow(JSONObject().put("id", rid).put("kind", if (item.kind == "voice") "voice_note" else item.kind).put("meta", item.meta))
        }
    }

    private fun fetchNow(row: JSONObject) {
        fetchNote(row)
        val meta = runCatching { JSONObject(row.optString("meta").ifBlank { "{}" }) }.getOrNull() ?: return
        val paths = RemoteRows.mediaPaths(meta).filter { !File(it).exists() }
        if (paths.isEmpty()) return
        scope.launch {
            val (api, url) = downloader() ?: return@launch
            val dir = File(app.cacheDir, "linked-media").apply { mkdirs() }
            val local = HashMap<String, String>()
            for (p in paths) runCatching {
                UploadedMedia.local(p)?.let { known -> local[p] = known; return@runCatching }
                val ext = p.substringAfterLast('.', "jpg").take(5).lowercase()
                val f = File(dir, Integer.toHexString(p.hashCode()) + "-" + p.length + "." + ext)
                if (!f.exists() || f.length() == 0L) api.download(url, p, pass) { input, _ -> f.outputStream().use { input.copyTo(it) } }
                local[p] = f.absolutePath
            }
            if (local.isEmpty()) return@launch
            val lid = ids.localOrNull(row.optString("id")) ?: return@launch
            db.chat().get(lid)?.let { db.chat().update(it.copy(meta = RemoteRows.withLocalMedia(meta, local).toString())) }
        }
    }

    /** Where a device command's file comes from: the brain answering now, with this phone's pass. */
    fun downloader(): Pair<RemoteApi, String>? = current?.let { RemoteApi(clientFor(it.brain.fp)) to it.url }
    val pass: String get() = link.pass

    // ---- a file an agent showed on the computer (media.fetch, desktop 0.2.118 and later) -------------

    /** The files an agent showed, as this phone holds them: one cache for the whole app run. */
    val shownFiles: MediaFiles by lazy { MediaFiles(File(app.cacheDir, "shown-media")) }
    private var shownServe: MediaServe? = null

    /** Why the last ask for a shown file did not come, in words a row can show. */
    @Volatile
    var mediaProblem: String = ""
        private set

    /** What a chat row says a shown file is: its token, and enough to draw it before a byte is asked for. */
    fun shownFromMeta(meta: JSONObject): ShownFile? {
        val token = meta.optString("token").trim()
        val size = meta.optLong("size", 0L)
        if (token.isEmpty() || size <= 0L) return null
        return ShownFile(
            chat = meta.optString("chat").ifBlank { "main" },
            token = token,
            name = meta.optString("name").ifBlank { "file" },
            mime = meta.optString("mime").ifBlank { "application/octet-stream" },
            kind = meta.optString("kind").ifBlank { "video" },
            size = size,
        )
    }

    /** One chunk of a shown file, from the computer. Null when it could not come, and [mediaProblem] says why. */
    private suspend fun shownChunk(info: ShownFile, offset: Long, length: Int): ByteArray? = try {
        val ask = JSONObject().put("chat", info.chat).put("token", info.token).put("offset", offset).put("length", length)
        val answer = call("media.fetch", listOf(ask), 90_000) as? JSONObject
        val b64 = answer?.optJSONObject("bytes")?.optString("\$b64").orEmpty()
        if (b64.isEmpty()) {
            mediaProblem = oldComputer(answer?.optString("error").orEmpty())
                .ifBlank { "The computer did not send that file." }
            null
        } else {
            java.util.Base64.getDecoder().decode(b64)
        }
    } catch (e: Exception) {
        mediaProblem = oldComputer(e.message.orEmpty()).ifBlank { "The computer did not answer." }
        null
    }

    /** The whole file on this phone: a picture has to be here before it can be drawn. */
    suspend fun shownWhole(info: ShownFile): File? {
        mediaProblem = ""
        val held = shownFiles.note(info)
        return if (shownFiles.whole(held) { offset, length -> shownChunk(info, offset, length) }) held.path else null
    }

    /** The address this phone's own player reads a video at, the server started on first use. */
    suspend fun shownUrl(info: ShownFile): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        mediaProblem = ""
        val serve = shownServe ?: MediaServe(shownFiles) { file, offset, length -> shownChunk(file, offset, length) }.start().also { shownServe = it }
        shownFiles.note(info)
        serve.url(info.token)
    }

    /**
     * "not available on this device" is what the device link answers when the computer has no such RPC at all, which
     * means its Agent is older than this app: 0.2.118 drew the card but shipped no media.fetch. Say what to do about it
     * instead of showing a phrase that reads like the file is missing.
     */
    private fun oldComputer(error: String): String =
        if (error.contains("not available on this device", ignoreCase = true)) {
            "This file is on your computer, and the Agent there is too old to send it. Update Agent on the computer (0.2.119 or newer) and it plays here."
        } else {
            error.trim()
        }

    companion object {
        /** The longest wait between tries. */
        const val MAX_BACKOFF_MS = 30_000L
        /** How long a socket may take to open before a check may replace it. */
        const val CONNECT_GRACE_MS = 20_000L
        /** A link that stayed up this long was fine; the next failure starts the waits from 1 s again. */
        const val STABLE_MS = 10_000L
        const val CHAT_ROWS = 400
        /** The first read on mobile data: the newest messages. */
        const val FIRST_ROWS = 20
        /** Load earlier: how many at a time. */
        const val PAGE_ROWS = 20
        /** The small lists, read on Wi-Fi or when their screen opens. */
        val LISTS = listOf("goal", "task", "memory", "trigger")
        const val NOTE_GONE = "The computer no longer has this voice note."
        const val EVT_DATA = "agent:evt:data.changed"
        const val EVT_CHAT = "agent:evt:chat.event"
        const val EVT_DEVICES = "agent:evt:devices.changed"
        const val EVT_CARDS = "agent:evt:helpers.cards"
        /** How long the cards stay with no link to the computer that sent them. */
        const val STALE_CARDS_MS = 20_000L
        const val EVT_COMMAND = "device.command"
    }
}

/**
 * The phone's link, if any. Stored in the same Keystore-encrypted prefs as the app's keys ([com.past9.phoneaos.data.SettingsStore.extra]);
 * the computer's rows live in their own database file (linked.db), apart from the phone's own data, which is never touched.
 */
object RemoteHub {
    private const val KEY = "remote_link"
    private val _session = MutableStateFlow<RemoteSession?>(null)
    /** Non-null while the phone uses a computer's agent. */
    val session: StateFlow<RemoteSession?> = _session
    /** A link code that arrived from outside (agent://pair from the camera app): the link screen opens with it. */
    val pendingOffer = MutableStateFlow<String?>(null)
    private val _link = MutableStateFlow<Link?>(null)
    /** The saved link, on or off. */
    val link: StateFlow<Link?> = _link
    @Volatile private var db: AppDb? = null
    @Volatile private var loaded = false

    private fun linkedDb(app: Context): AppDb = db ?: synchronized(this) {
        db ?: androidx.room.Room.databaseBuilder(app, AppDb::class.java, "linked.db").fallbackToDestructiveMigration(true).build().also { db = it }
    }

    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val l = Link.from(App.graph(context).settings.extra(KEY))
        _link.value = l
        if (l != null && l.on) open(context.applicationContext, l)
    }

    fun save(context: Context, link: Link) {
        App.graph(context).settings.setExtra(KEY, link.toJson())
        _link.value = link
    }

    private fun open(app: Context, l: Link) {
        _session.value?.close()
        val s = RemoteSession(app, l, linkedDb(app))
        App.graph(app).runtime.remote = s
        _session.value = s
        s.start()
        // Wake-ups reach the phone through their own tiny link while the app is off screen.
        runCatching { com.past9.phoneaos.wakeup.WakeChannel.ensure(app) }
    }

    /** Just paired: keep the pass and brains, start using them. */
    fun linked(context: Context, p: Paired) {
        val app = context.applicationContext
        val l = Link(p.meshId, p.deviceId, p.pass, p.nodeName, p.brains, System.currentTimeMillis(), on = true)
        save(app, l)
        loaded = true
        open(app, l)
    }

    /** Linked but using this phone's own agent (off), or back to the computer (on). Nothing is forgotten either way. */
    fun setOn(context: Context, on: Boolean) {
        val app = context.applicationContext
        val l = _link.value ?: return
        save(app, l.copy(on = on))
        if (on) open(app, l.copy(on = true)) else stopSession(app)
    }

    /** Forget the link: the pass and the copy of the computer's data go; this phone's own data stays as it was. */
    fun unlink(context: Context) {
        val app = context.applicationContext
        stopSession(app)
        App.graph(app).settings.setExtra(KEY, null)
        _link.value = null
        synchronized(this) { db?.close(); db = null }
        app.deleteDatabase("linked.db")
    }

    private fun stopSession(app: Context) {
        _session.value?.close()
        _session.value = null
        App.graph(app).runtime.remote = null
        runCatching { com.past9.phoneaos.wakeup.WakeChannel.stop(app) }
    }

    fun onForeground() { _session.value?.onForeground() }
    fun onBackground() { _session.value?.onBackground() }
}
