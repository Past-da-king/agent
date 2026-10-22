package com.past9.phoneaos.remote

import android.content.Context
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
    data class NoAgents(val why: String) : LinkState
    data object Paused : LinkState
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

    @Volatile private var rpc: RpcClient? = null
    @Volatile private var current: Pick? = null
    @Volatile private var turnRunning = false
    @Volatile private var foreground = true
    @Volatile private var holdingWork = false
    private var loop: Job? = null
    private var pauseJob: Job? = null
    private var lastFacts: String = ""
    val commands by lazy { DeviceCommands(app, this) }

    // ---- connecting and failover ------------------------------------------------------------

    fun start() {
        if (loop?.isActive == true) return
        _state.value = if (current != null && rpc?.isOpen == true) _state.value else LinkState.Connecting
        loop = scope.launch {
            while (true) {
                runCatching { probeAndMove() }
                // Every 15 s, or at once when something failed.
                withTimeoutOrNull(15_000) { kick.receive() }
            }
        }
    }

    /** Check every brain now (Retry, a failed call, the socket dropping). */
    fun retry() { if (_state.value is LinkState.NoAgents) _state.value = LinkState.Connecting; kick.trySend(Unit); start() }

    private fun clientFor(fp: String): OkHttpClient = clients.getOrPut(fp) {
        Pinning.client(sharedHttp, Pinning.PinnedTrust({ listOf(fp) }))
    }

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
        val curOk = cur != null && rpc?.isOpen == true && seen[cur.brain.id]?.ready == true
        when (val m = BrainChooser.decide(brains, cur, curOk, best, turnRunning)) {
            is BrainChooser.Move.To -> connectTo(m.pick)
            BrainChooser.Move.NoneAvailable -> {
                rpc?.close(); rpc = null; current = null
                _state.value = LinkState.NoAgents("None of your computers answered.")
                _status.value = AgentStatus()
            }
            BrainChooser.Move.Stay -> {}
        }
    }

    private fun connectTo(pick: Pick) {
        rpc?.close()
        current = pick
        _state.value = LinkState.Connecting
        val c = RpcClient(clientFor(pick.brain.fp), pick.url, link.pass, ::hello, object : RpcClient.Listener {
            override fun onOpen(rpc: RpcClient) {
                if (this@RemoteSession.rpc !== rpc) return
                _state.value = LinkState.Connected(pick.brain, pick.url)
                scope.launch { runCatching { refreshBrains(pick) }; runCatching { reloadAll() }; runCatching { readRunState() } }
            }
            override fun onEvent(rpc: RpcClient, name: String, payload: Any?) { if (this@RemoteSession.rpc === rpc) onEvent(name, payload) }
            override fun onClosed(rpc: RpcClient, why: String) {
                if (this@RemoteSession.rpc !== rpc) return
                this@RemoteSession.rpc = null
                if (foreground || holdingWork) { _state.value = LinkState.Connecting; kick.trySend(Unit) }
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
        rpc?.close(); rpc = null; current = null
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
        hold(false)
        rpc?.close(); rpc = null
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

    private fun brainName(): String = (current?.brain?.name ?: link.nodeName).ifBlank { "your computer" }

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
                if (item?.kind == "approval") call("chat.decide", listOf(rid, if (isYes(option)) "approve" else "decline"))
                else call("chat.answer", listOf(rid, option))
            }.onFailure { notice("Couldn't answer on ${brainName()}: ${it.message}") }
        }
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
        val goals = list("goal")
        val tasks = list("task")
        val memory = list("memory").filter(RemoteRows::isGeneralMemory)
        val triggers = list("trigger")
        val projects = list("project")
        val allChat = list("chat_item")
        // The phone keeps the recent part of the main chat; the full history stays on the computer.
        val chat = allChat.filter(RemoteRows::inMainChat).sortedBy { it.optLong("createdAt") }.takeLast(CHAT_ROWS)
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
            val keep = chat.map { RemoteRows.chat(it, ids) }.onEach { putChat(it) }.map { it.id }.toMutableSet()
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
        if (o.optBoolean("reset")) { reloadSoon(); return }
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
                if (c == "main") { putChat(RemoteRows.chat(row, ids)); fetchMedia(row) }
                else if (projectOf.containsKey(c)) {
                    val list = projectRows.getOrPut(c) { ArrayList() }
                    list.removeAll { it.optString("id") == row.optString("id") }; list += row
                    projectRow(c)?.let { putChat(it) }
                }
            }
            "project" -> reloadSoon()
            "memory" -> if (RemoteRows.isGeneralMemory(row)) putMemory(RemoteRows.memory(row, ids)) else ids.localOrNull(row.optString("id"))?.let { db.memory().delete(it) }
            "task" -> putTask(RemoteRows.task(row, ids))
            "goal" -> putGoal(RemoteRows.goal(row, ids))
            "trigger" -> db.triggers().upsert(RemoteRows.trigger(row, ids))
        }
    }

    private fun publishStatus() {
        _status.value = AgentStatus(working = turnRunning, label = if (turnRunning) "Working on ${brainName()}" else "")
    }

    private fun onEvent(name: String, payload: Any?) {
        when (name) {
            EVT_DATA -> (payload as? JSONObject)?.let { p -> scope.launch { runCatching { applyChange(p) } } }
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
                    "chat_changed" -> reloadSoon()
                }
            }
            EVT_DEVICES -> current?.let { p -> scope.launch { runCatching { refreshBrains(p) } } }
            EVT_COMMAND -> (payload as? JSONObject)?.let { c -> scope.launch { commands.handle(c) } }
        }
    }

    // ---- project chats as helper rows, and pictures from the computer ----------------------------------

    private val projectOf = ConcurrentHashMap<String, JSONObject>()
    private val projectRows = ConcurrentHashMap<String, MutableList<JSONObject>>()
    private val running: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun projectRow(chat: String): ChatItem? {
        val p = projectOf[chat] ?: return null
        return RemoteRows.projectHelper(p, chat, projectRows[chat].orEmpty().toList(), chat in running, ids)
    }

    /** Pictures in a chat row are paths on the computer: fetch each once through /v1/file and point the row at the copy. */
    private fun fetchMedia(row: JSONObject) {
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
        const val CHAT_ROWS = 400
        const val EVT_DATA = "agent:evt:data.changed"
        const val EVT_CHAT = "agent:evt:chat.event"
        const val EVT_DEVICES = "agent:evt:devices.changed"
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
