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
        val best = BrainChooser.firstReady(brains) { b, u ->
            val h = RemoteApi(clientFor(b.fp)).health(u)
            if (h != null || !seen.containsKey(b.id)) seen[b.id] = h
            h != null && h.ready && (h.meshId.isBlank() || link.meshId.isBlank() || h.meshId == link.meshId)
        }
        // Brains after the chosen one were not asked this round: ask them too, so Settings shows every brain's state.
        for (b in brains) if (!seen.containsKey(b.id)) seen[b.id] = b.urls.firstNotNullOfOrNull { u -> RemoteApi(clientFor(b.fp)).health(u, 2_000) }
        _health.value = seen
        val cur = current
        val r = rpc
        // A socket that is still opening is left to finish (it gets CONNECT_GRACE_MS); replacing it is what stormed.
        if (r != null && !r.isOpen && System.currentTimeMillis() - connectStartedAt < CONNECT_GRACE_MS) return
        // An open socket is the best sign the brain is there: one missed health check does not drop it, a brain
        // that says it is not ready does.
        val curOk = cur != null && r?.isOpen == true && seen[cur.brain.id]?.ready != false
        when (val m = BrainChooser.decide(brains, cur, curOk, best, turnRunning)) {
            is BrainChooser.Move.To -> connectTo(m.pick)
            BrainChooser.Move.NoneAvailable -> {
                detach(); current = null
                val certChanged = brains.firstOrNull { trusts[it.fp]?.refused == true }
                _state.value = when {
                    certChanged != null -> LinkState.NoAgents(reasonFor(LinkProblem.CertChanged, certChanged.name.ifBlank { nameOf(null) }), rescan = true)
                    brains.size <= 1 -> LinkState.NoAgents(reasonFor(LinkProblem.Unreachable, brains.firstOrNull()?.name?.ifBlank { null } ?: nameOf(null)))
                    else -> LinkState.NoAgents("None of your computers answered. Are the Agent apps open there, with Devices on, on the same network?")
                }
                _status.value = AgentStatus()
            }
            BrainChooser.Move.Stay -> {}
        }
    }

    private fun connectTo(pick: Pick) {
        detach()
        current = pick
        // A new connection (maybe another brain) numbers its lists afresh.
        cardsSeq = -1L
        if (_state.value !is LinkState.Retrying && _state.value !is LinkState.NoAgents) _state.value = LinkState.Connecting
        connectStartedAt = System.currentTimeMillis()
        val c = RpcClient(clientFor(pick.brain.fp), pick.url, link.pass, ::hello, object : RpcClient.Listener {
            override fun onOpen(rpc: RpcClient) {
                if (this@RemoteSession.rpc !== rpc) return
                _state.value = LinkState.Connected(pick.brain, pick.url)
                syncJob?.cancel()
                syncJob = scope.launch { connectSync(pick); runCatching { readRunState() } }
            }
            override fun onEvent(rpc: RpcClient, name: String, payload: Any?) { if (this@RemoteSession.rpc === rpc) onEvent(name, payload) }
            override fun onClosed(rpc: RpcClient, why: String, opened: Boolean, problem: LinkProblem) {
                if (this@RemoteSession.rpc !== rpc) return
                this@RemoteSession.rpc = null
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
        if (fresh.isEmpty() || fresh.toSet() == link.brains.toSet()) return
        link = link.copy(brains = fresh)
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
            if (e.message.orEmpty().contains("not available")) _cards.value = null
            return
        }
        val (seq, list) = LiveCards.parse(v as? JSONObject) ?: return
        cardsSeq = seq; _cards.value = list
    }

    private fun onCards(payload: Any?) {
        val (seq, list) = LiveCards.parse(payload as? JSONObject) ?: return
        if (seq <= cardsSeq) return
        cardsSeq = seq; _cards.value = list
    }

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

    /** Never a machine name in the conversation (notices, status): which computer answers lives in Settings > Linked devices. */
    private fun brainName(): String = "your computer"

    /** A line in the chat when something the person did couldn't reach the computer. */
    private fun notice(text: String) = scope.launch {
        db.chat().insert(ChatItem(id = ids.local("phone-notice-" + System.nanoTime(), System.currentTimeMillis()), kind = "notice", text = text))
    }

    override fun send(text: String, images: List<String>, quiet: Boolean) {
        val clean = text.trim().ifEmpty { if (images.isNotEmpty()) "What do you make of this?" else "" }
        if (clean.isEmpty()) return
        scope.launch {
            try {
                val attachments = JSONArray()
                for (p in images) {
                    val f = File(p)
                    if (!f.isFile) continue
                    val mime = if (f.name.endsWith(".png", true)) "image/png" else "image/jpeg"
                    val b64 = android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
                    (call("files.savePasted", listOf(f.name, mime, JSONObject().put("\$b64", b64)), 120_000) as? JSONObject)?.let { attachments.put(it) }
                }
                turnRunning = true; publishStatus()
                call("chat.send", if (attachments.length() > 0) listOf(clean, attachments) else listOf(clean), 60_000)
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
        // The phone keeps the recent part of the main chat; the full history stays on the computer.
        // Plus every card still waiting on him, from any chat and however old: the computer counts those as waiting for
        // you, so Home's Needs you must show them and let him answer (22 Oct 2026: two helper cards were invisible).
        val mainTail = allChat.filter(RemoteRows::inMainChat).sortedBy { it.optLong("createdAt") }.takeLast(CHAT_ROWS)
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

    private fun publishStatus() {
        _status.value = AgentStatus(working = turnRunning, label = if (turnRunning) "Working" else "")
    }

    private fun onEvent(name: String, payload: Any?) {
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
                when (e.optString("type")) {
                    "run_started", "thinking" -> { turnRunning = true; publishStatus() }
                    "run_finished" -> {
                        turnRunning = false; publishStatus()
                        if (holdingWork && !foreground) scope.launch { delay(5_000); if (!foreground && !turnRunning) pause() }
                        // Between turns: a higher brain that came back takes over now.
                        kick.trySend(Unit)
                    }
                    // "chat_changed" says only that something changed: the rows themselves come as data.changed.
                    // Reloading here pulled the whole chat again on every helper step (hundreds of MB an hour).
                    "chat_changed" -> {}
                }
            }
            EVT_CARDS -> onCards(payload)
            EVT_DEVICES -> current?.let { p -> scope.launch { runCatching { refreshBrains(p) } } }
            EVT_COMMAND -> (payload as? JSONObject)?.let { c -> scope.launch { commands.handle(c) } }
        }
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
        if (!metered) runCatching { fullSync(initial = caught) } else if (helpersOpen) runCatching { loadCards() }
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
        runCatching { loadCards() }
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
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("id").isNotBlank() }.map { it.optString("id") to it.optString("name").ifBlank { "Profile" } }
    }

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
        val connected = accounts.map { o -> com.past9.phoneaos.tools.Connection(o.optString("id"), o.optString("app"), o.optString("status").uppercase(), o.optString("label").ifBlank { o.optString("appName") }) }
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
    }

    /**
     * A voice note's audio comes over the link with voice.noteAudio (the same call the desktop's own player uses), is
     * saved next to the phone's data and the row then points at it. When it cannot be had, the row says so: it never
     * turns into text on its own.
     */
    private fun fetchNote(row: JSONObject) {
        if (row.optString("kind") != "voice_note") return
        val meta = runCatching { JSONObject(row.optString("meta").ifBlank { "{}" }) }.getOrNull() ?: return
        val name = RemoteRows.noteName(meta)
        if (name.isEmpty() || notes.local(name).let { it.isFile && it.length() > 44 } || !noteFetching.add(name)) return
        scope.launch {
            try {
                val bytes = try {
                    RemoteRows.noteBytes(call("voice.noteAudio", listOf(name), 120_000)) ?: run { noteFailed[name] = NOTE_GONE; null }
                } catch (e: Exception) {
                    noteFailed[name] = "Couldn't get the audio from ${brainName()}: ${e.message ?: e::class.java.simpleName}"; null
                }
                if (bytes != null) {
                    val tmp = File(noteDir, "$name.part")
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(notes.local(name))) { tmp.delete(); noteFailed[name] = "Couldn't save the audio on this phone." }
                    else noteFailed.remove(name)
                }
                val lid = ids.localOrNull(row.optString("id")) ?: return@launch
                db.chat().get(lid)?.let { db.chat().update(it.copy(meta = RemoteRows.voiceMeta(row.optString("meta"), notes).toString())) }
            } finally { noteFetching.remove(name) }
        }
    }

    /** He tapped "Try again" on a note whose audio did not come. */
    fun retryNote(localId: Long) = scope.launch {
        val item = db.chat().get(localId) ?: return@launch
        val name = runCatching { JSONObject(item.meta).optString("remoteNote") }.getOrDefault("")
        if (name.isEmpty()) return@launch
        noteFailed.remove(name)
        val rid = ids.remote(localId) ?: return@launch
        val meta = JSONObject(item.meta).apply { remove("path"); remove("audio"); remove("audioError"); remove("remoteNote") }
        db.chat().update(item.copy(meta = RemoteRows.voiceMeta(meta.toString(), notes).toString()))
        fetchNote(JSONObject().put("id", rid).put("kind", "voice_note").put("meta", meta.toString()))
    }

    /** Pictures in a chat row are paths on the computer: fetch each once through /v1/file and point the row at the copy. */
    private fun fetchMedia(row: JSONObject) {
        // On mobile data a picture or a voice note comes only when he taps it (fetchMediaFor).
        if (metered) return
        fetchNow(row)
    }

    /** He tapped a picture or a note that was left on the computer to save data. */
    fun fetchMediaFor(localId: Long) {
        val rid = ids.remote(localId) ?: return
        scope.launch {
            val item = db.chat().get(localId) ?: return@launch
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
    }

    fun onForeground() { _session.value?.onForeground() }
    fun onBackground() { _session.value?.onBackground() }
}
