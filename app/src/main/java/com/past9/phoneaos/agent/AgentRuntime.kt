package com.past9.phoneaos.agent

import android.content.Context
import com.past9.phoneaos.data.AppDb
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.data.PowerMode
import com.past9.phoneaos.data.Provider
import com.past9.phoneaos.data.SettingsStore
import com.past9.phoneaos.data.TurnRow
import com.past9.phoneaos.tools.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** The Claude-subscription engine: runs a whole turn itself (Agent SDK), our tools over MCP. */
interface SubscriptionEngine {
    val ready: Boolean
    /** The stored sign-in was rejected: forget it so Settings asks the user to sign in again. */
    fun forgetBadSignIn() {}
    fun turn(prompt: String, system: String, resume: String?, mcpUrl: String, mcpToken: String, images: List<String> = emptyList()): kotlinx.coroutines.flow.Flow<JSONObject>
}

/** What the top bar shows. */
data class AgentStatus(val working: Boolean = false, val label: String = "", val helpers: Int = 0)

/** Hooks into the phone that the runtime needs but should not own (so tests can fake them). */
interface PhoneBridge {
    fun notify(title: String, body: String, questionItemId: Long? = null, options: List<String> = emptyList())
    fun openLink(url: String)
    fun workStarted()
    fun workFinished()
}

/**
 * The ONE agent. Owns the conversation, builds the system prompt (memory, tasks, time), runs the
 * provider-neutral loop on the app's own scope so it keeps going when the screen goes away, and
 * turns everything the loop does into chat items the UI renders.
 */
class AgentRuntime(
    private val context: Context,
    val db: AppDb,
    val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val phone: PhoneBridge,
    /** Overridable for tests: build a provider from settings. */
    private val providerFactory: (SettingsStore) -> LlmProvider? = ::defaultProvider,
    /** Routines call this when they change, to (re)schedule WorkManager jobs. */
    var onRoutineChanged: suspend (com.past9.phoneaos.data.TriggerRow?) -> Unit = {},
    var onRoutineDeleted: suspend (Long) -> Unit = {},
    var subscription: SubscriptionEngine? = null,
) {
    /** Our tools, served to Claude Code on 127.0.0.1 with a per-install token. Started on first use. */
    val mcp by lazy {
        com.past9.phoneaos.runtime.LocalMcpServer(settings.localToken(), { tools() }, { ChatContext("main", interactive = true) }).start()
    }
    private val _status = MutableStateFlow(AgentStatus())
    val status: StateFlow<AgentStatus> = _status
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<String>>()
    private val turnLock = Mutex()
    private var job: Job? = null

    val composio = ComposioClient({ settings.composioKey() }, settings.installId())

    init {
        // A fresh process has no live helpers: anything still marked working was cut off.
        scope.launch {
            db.chat().all().first().filter { it.kind == "helper" && JSONObject(it.meta).optString("state") == "working" }
                .forEach { db.chat().update(it.copy(meta = JSONObject(it.meta).put("state", "failed").put("result", "Interrupted when the app closed.").toString())) }
        }
    }

    fun tools(forHelper: Boolean = false): List<Tool> {
        val list = mutableListOf<Tool>(
            NowTool(), WebFetchTool(),
            MemorySearchTool(db.memory()), MemorySaveTool(db.memory()), MemoryGetTool(db.memory()), MemoryUpdateTool(db.memory()),
            TaskListTool(db.tasks()), TaskAddTool(db.tasks()), TaskUpdateTool(db.tasks()), GoalCreateTool(db.tasks()),
            NotificationsTool(db.notifications()) { settings.state.value.notifApps },
            RunCodeTool(context),
        )
        if (!forHelper) list += VoiceNoteTool(context, settings, db.chat())
        if (!forHelper) { list += LocationTool(context); list += NotificationsAllowTool(context, settings); list += NotificationsStopTool(settings); list += FilesPickTool(context); list += PhotosTool(context)
            list += WaitForCodeTool(db, settings, NotificationsAllowTool(context, settings)) }
        list += browserTools(context).filter { !forHelper || it !is BrowserHandoffTool }
        if (settings.state.value.composioEnabled && com.past9.phoneaos.tools.ComposioConnect.isConsumerKey(settings.composioKey())) {
            list += com.past9.phoneaos.tools.ComposioConnect.asTools { settings.composioKey()?.trim() }
        } else if (settings.state.value.composioEnabled) {
            list += AppsListTool(composio); list += AppsFindToolsTool(composio)
            list += GuardedAppsRunTool(AppsRunTool(composio))
            if (!forHelper) list += AppsConnectTool(composio) { _, url -> com.past9.phoneaos.system.UiBus.openInBrowser.emit(com.past9.phoneaos.system.UiBus.OpenInBrowser(url)) }
        }
        if (!forHelper) {
            list += AskUserTool(); list += ApprovalTool(); list += NotifyTool()
            list += ScheduleCreateTool(db.triggers()) { onRoutineChanged(it) }
            list += ScheduleListTool(db.triggers())
            list += com.past9.phoneaos.triggers.WatcherSaveTool(context) { onRoutineChanged(it) }
            list += ScheduleDeleteTool(db.triggers()) { onRoutineDeleted(it) }
            list += DelegateTool { task, label, ctx -> runHelper(task, label, ctx) }
        }
        return list
    }

    // ---- prompt ---------------------------------------------------------------------------

    suspend fun systemPrompt(latestUserText: String, background: Boolean = false): String {
        val s = settings.state.value
        val now = ZonedDateTime.now()
        val pinned = db.memory().pinned()
        val recalled = Recall.rank(latestUserText, db.memory().list(), 6).filter { m -> pinned.none { it.id == m.id } }
        val goals = db.tasks().openGoals(); val tasks = db.tasks().openTasks()
        return buildString {
            if (s.agentName != "Your agent") appendLine("Your name is ${s.agentName}.")
            appendLine("You are the user's personal agent, living on their Android phone. One agent, one ongoing conversation. You get things done for them: find, fetch, plan, follow up, remember.")
            if (s.userName.isNotBlank()) appendLine("The user's name is ${s.userName}.")
            appendLine("Now: ${now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm"))} (${now.zone}). Always resolve 'today', 'tomorrow', '10am' in THIS timezone and read concrete dates back to the user.")
            appendLine()
            appendLine("HOW YOU WORK")
            appendLine("- Do the work yourself with your tools instead of telling the user how. Only ask when it is a real choice (ask_user with buttons).")
            appendLine("- Before anything goes out in their name, costs money, or deletes something: request_approval with the exact content. Never skip this.")
            appendLine("- Memory is your wiki about the user's life: one page per person, company, place, project or preference, in markdown, linking other pages as [[Name]], always tagged. When you learn something durable, save or update the page right away (memory_get first to merge). Search memory before asking anything they may have told you. Keep pages accurate; fix wrong ones.")
            appendLine("- The user uses you as their to-do list. Things THEY must do or asked to be reminded of go on their list (task_add owner user). Your own working steps live under goals (goal_create) and you tick them off as you go; when stuck, mark the step blocked and say what would unblock it.")
            appendLine("- Phone permissions are OFF until you need one. Need their location (delivery, 'near me')? Use location_get. Need a document from their phone? files_pick. Their latest photo or screenshot? photos_recent. A site sent a one-time code during sign-in? notifications_wait_code picks it up so you can finish on your own. Need to keep an eye on an app's notifications? notifications_allow, then a 'notification' routine, and notifications_stop when done. Don't ask for things you don't need.")
            if (s.notifApps.isNotEmpty()) appendLine("- You can read the phone's notifications from the apps the user allowed (notifications_read): bank alerts, messages, deliveries. Check them when they would help.")
            appendLine("- For parallel research or many pages, use delegate to run helper agents at once.")
            appendLine("- You have your own small computer: run_code runs JavaScript (Node) on the phone. Use it for maths, data crunching, building files (CSV, HTML, SVG, images) and anything code does better than words.")
            appendLine("- You can see images: photos the user sends, gallery photos (photos_recent), page screenshots (browser_look), scanned PDFs, and images your code makes.")
            appendLine("- The browser_* tools drive your own background browser; it keeps going while the user is in other apps. If a page needs them (sign-in, captcha) use browser_handoff.")
            appendLine("- 'Sign in with Google' on other sites works best once the browser profile is already signed in to Google: if it fails or goes blank, open accounts.google.com first (handoff so the user signs in), then retry the site. Read any NOTE at the top of a page outline: it tells you about blocked sign-ins, downloads and app links.")
            appendLine("- Recurring or later work becomes a routine (routine_create).")
            appendLine("- To keep an eye on something that has no API or connected app (a price on Takealot or Amazon, stock, a page changing), build a WATCHER (watcher_save): find the data source yourself, write and test a small script, save it. It checks on its own without waking you and only wakes you when it fires. Prefer this over a routine that re-browses every time.")
            appendLine("- Your browser shares the phone's location with sites that ask (store finders, delivery), so you don't need to type the address. Sites open in your browser can send you web notifications; they arrive as notifications from 'web:<site>' and can trigger notification routines.")
            appendLine("- Facts like phone numbers, addresses, prices and opening hours must come from a page you actually read this session; name the source. If you could not verify it, say so. Never invent.")
            if (!s.composioEnabled) appendLine("- No apps are connected yet (Gmail, Calendar...). If a task needs one, tell the user they can connect apps in Connections.")
            else if (com.past9.phoneaos.tools.ComposioConnect.isConsumerKey(settings.composioKey())) appendLine("- Connected apps run through the COMPOSIO_* tools (Composio Connect): search for the right tool, check or start connections (if an app isn't connected, give the user the sign-in link it returns and wait for them), then execute. Actions that send, post, pay or delete ask the user first automatically.")
            else appendLine("- Connected apps run through apps_find_tools then apps_run. If the task needs an app that isn't connected, use apps_connect: it asks the user, opens the sign-in in your browser, and waits until it's done, then carry on.")
            appendLine()
            appendLine("- To show the user an image (a product photo, a map, a chart from a page), put it in your reply as markdown: ![what it is](https://...). Several images in a row become a swipeable strip.")
            appendLine("- voice_note scripts are plain spoken words: no emoji, no markdown, no lists, no URLs. After sending one, don't repeat it as text.")
            appendLine("- NEVER use emoji, anywhere. They cheapen the app.")
            appendLine("STYLE: short and warm. Lead with the answer. Use short bullets for lists. No walls of text. Plain words. No em dashes.")
            if (background) appendLine("\nThis is a BACKGROUND run (a routine). Nobody is watching. Do the work, then finish with a short summary the user will read later. Use notify_user if something needs their attention.")
            settings.extra("story")?.takeIf { it.isNotBlank() }?.let { appendLine("\nYOUR OWN NOTES SO FAR (written overnight; earlier chat is folded into these)\n$it") }
            if (pinned.isNotEmpty()) { appendLine("\nCORE MEMORIES (pinned)"); pinned.forEach { appendLine("- ${Recall.format(it)}") } }
            if (recalled.isNotEmpty()) { appendLine("\nPOSSIBLY RELEVANT MEMORIES"); recalled.forEach { appendLine("- ${Recall.format(it)}") } }
            if (goals.isNotEmpty() || tasks.isNotEmpty()) {
                appendLine("\nOPEN GOALS AND TASKS")
                goals.forEach { appendLine("- Goal #${it.id}: ${it.title}") }
                tasks.take(25).forEach { appendLine("- Task #${it.id} [${it.status}${if (it.owner == "user") ", user's to-do" else ""}] ${it.title}${it.goalId?.let { g -> " (goal #$g)" } ?: ""}") }
            }
        }
    }

    // ---- the main conversation --------------------------------------------------------------

    /**
     * @param voiceReply the user swiped up to send: answer with a voice note.
     * @param fromCall the voice agent handed this over mid-call: no user bubble (the call transcript is
     *   already in the chat), just a line saying the work started.
     */
    fun send(text: String, images: List<String> = emptyList(), files: List<String> = emptyList(), voiceReply: Boolean = false, fromCall: String? = null) {
        val clean = text.trim().ifEmpty { if (images.isNotEmpty() || files.isNotEmpty()) "What do you make of this?" else "" }; if (clean.isEmpty()) return
        // A question card is waiting and the user typed instead of tapping: the typed words ARE the answer.
        // Before, the message queued behind the open question and nothing happened.
        if (fromCall == null && images.isEmpty() && files.isEmpty() && pending.isNotEmpty()) {
            val id = pending.keys.maxOrNull()
            if (id != null) {
                scope.launch { db.chat().insert(ChatItem(kind = "user", text = clean)) }
                answer(id, clean)
                return
            }
        }
        job = scope.launch {
            if (fromCall != null) db.chat().insert(ChatItem(kind = "activity", text = "On it: $fromCall", meta = JSONObject().put("tool", "call").toString()))
            else db.chat().insert(ChatItem(kind = "user", text = clean, meta = JSONObject().put("images", org.json.JSONArray(images.filter { !it.contains("/pdf-") }))
                .put("files", org.json.JSONArray(files)).put("voiceReply", voiceReply).toString()))
            val forModel = if (voiceReply) "$clean\n\n(The user is on the move: answer with a voice_note, then at most one short line of text. Don't repeat the voice note as text.)" else clean
            turnLock.withLock { runTurn(forModel, images) }
        }
    }

    /** A finished voice call goes into the agent's own history, so the chat carries on from it. */
    fun rememberCall(transcript: String) = scope.launch {
        if (transcript.isBlank()) return@launch
        db.chat().insertTurn(TurnRow(thread = "main", json = Msg.user("[Earlier, on a voice call with you]\n$transcript").toJson().toString()))
    }

    /** Wait for the current turn to finish (tests, and routines that must not overlap a turn). */
    suspend fun awaitIdle() { job?.join() }

    fun stop() {
        job?.cancel(); job = null
        pending.values.forEach { it.cancel() }; pending.clear()
        _status.value = AgentStatus()
    }

    /** The user tapped an option on a question card (in the app or on a notification). */
    fun answer(itemId: Long, option: String) {
        scope.launch {
            db.chat().get(itemId)?.let { db.chat().update(it.copy(meta = JSONObject(it.meta).put("answer", option).toString())) }
            pending.remove(itemId)?.complete(option)
        }
    }

    private suspend fun loadHistory(thread: String): MutableList<Msg> {
        val rows = db.chat().recentTurns(thread, 60).reversed()
        val msgs = rows.mapNotNull { runCatching { Msg.fromJson(JSONObject(it.json)) }.getOrNull() }.toMutableList()
        // Never start mid tool-exchange: the first message must be a real user message.
        while (msgs.isNotEmpty() && !(msgs.first().role == Role.USER && msgs.first().blocks.any { it is Block.Text })) msgs.removeAt(0)
        return Repair.repair(msgs)
    }

    object Repair {
        /**
         * A run cut short (app killed, phone restarted, user stopped it) can leave tool calls with no
         * results, and every provider rejects that forever after. Give each orphaned call a result
         * saying it was interrupted, so the conversation always stays valid.
         */
        fun repair(msgs: List<Msg>): MutableList<Msg> {
            val out = mutableListOf<Msg>()
            var i = 0
            while (i < msgs.size) {
                val m = msgs[i]; out += m
                val calls = if (m.role == Role.ASSISTANT) m.toolCalls else emptyList()
                if (calls.isNotEmpty()) {
                    val next = msgs.getOrNull(i + 1)
                    val answered = next?.takeIf { it.role == Role.USER }?.blocks?.filterIsInstance<Block.ToolResult>()?.map { it.callId }?.toSet() ?: emptySet()
                    val missing = calls.filter { it.id !in answered }
                    if (missing.isNotEmpty()) {
                        val fill = missing.map { Block.ToolResult(it.id, "Interrupted before it finished (the app was closed or stopped).", true) }
                        if (next != null && next.role == Role.USER && answered.isNotEmpty()) { out += Msg(Role.USER, next.blocks + fill); i += 2; continue }
                        out += Msg(Role.USER, fill)
                    }
                }
                i++
            }
            return out
        }
    }

    private suspend fun runTurn(userText: String, images: List<String> = emptyList()) {
        if (settings.state.value.mode == PowerMode.SUBSCRIPTION && subscription?.ready == true) return runSubscriptionTurn(userText, subscription!!, images)
        val provider = providerFactory(settings)
        if (provider == null) {
            db.chat().insert(ChatItem(kind = "notice", text = if (settings.state.value.mode == PowerMode.SUBSCRIPTION)
                "Your ${settings.state.value.subKind.label} subscription isn't set up on this phone yet. Open Settings to finish setup, or switch to an API key." else "Add an API key in Settings so I can start working."))
            return
        }
        _status.value = AgentStatus(true, "Thinking")
        phone.workStarted()
        val history = loadHistory("main")
        val first = Msg.user(userText, images)
        history += first; db.chat().insertTurn(TurnRow(thread = "main", json = first.toJson().toString()))
        val ctx = ChatContext("main", interactive = true)
        try {
            val s = settings.state.value
            AgentLoop(provider, s.model, tools()).run(systemPrompt(userText), history, ctx,
                onEvent = { e ->
                    when (e) {
                        is AgentEvent.Thinking -> _status.value = _status.value.copy(working = true, label = if (e.step == 1) "Thinking" else "Working")
                        is AgentEvent.Said -> db.chat().insert(ChatItem(kind = "agent", text = e.text))
                        is AgentEvent.ToolStarted -> _status.value = _status.value.copy(label = labelFor(e.call.name))
                        is AgentEvent.ToolFinished -> {}
                    }
                },
                onAppend = { m -> db.chat().insertTurn(TurnRow(thread = "main", json = m.toJson().toString())) })
        } catch (e: CancellationException) {
            db.chat().insert(ChatItem(kind = "notice", text = "Stopped."))
        } catch (e: Exception) {
            db.chat().insert(ChatItem(kind = "notice", text = friendlyError(e), meta = JSONObject().put("error", true).toString()))
        } finally {
            _status.value = AgentStatus()
            phone.workFinished()
        }
    }

    private suspend fun runSubscriptionTurn(userText: String, engine: SubscriptionEngine, images: List<String> = emptyList()) {
        _status.value = AgentStatus(true, "Thinking"); phone.workStarted()
        try {
            var resume = settings.extra("claude_session")
            for (attempt in 1..2) {
                var newSession: String? = null; var ok = false; var retry = false
                engine.turn(userText, systemPrompt(userText), resume, mcp.url, settings.localToken(), images).collect { e ->
                    when (e.optString("type")) {
                        "session" -> newSession = e.optString("id")
                        "text" -> e.optString("text").takeIf { !it.contains("API Error: 401") && !it.startsWith("Failed to authenticate") }
                            ?.let { db.chat().insert(ChatItem(kind = "agent", text = it)) }
                        "tool" -> {
                            val name = e.optString("name")
                            _status.value = _status.value.copy(label = labelFor(name))
                            // Our own phone tools log themselves; show everything else (web search, the
                            // user's Claude connectors, Codex commands) as a live step too.
                            if (phoneToolNames.none { it == name }) db.chat().insert(ChatItem(kind = "activity", text = describeExternalTool(name, e.optString("detail")),
                                meta = JSONObject().put("tool", toolGroup(name)).toString()))
                        }
                        "done" -> ok = e.optBoolean("ok", true)
                        "error" -> {
                            val m = e.optString("message")
                            when {
                                // The saved conversation is gone (new install, cleared data): start a fresh one.
                                m.contains("No conversation found", true) && resume != null -> { retry = true }
                                m.contains("Invalid bearer token", true) || m.contains("authentication_error", true) || m.contains("401") -> {
                                    engine.forgetBadSignIn(); settings.setExtra("claude_session", null)
                                    db.chat().insert(ChatItem(kind = "notice", text = "Your ${settings.state.value.subKind.label} sign-in didn't work. Open Settings, Subscription, and sign in again.", meta = JSONObject().put("error", true).toString()))
                                }
                                m.isNotBlank() && !m.matches(Regex("exit \\d+")) -> db.chat().insert(ChatItem(kind = "notice", text = "${settings.state.value.subKind.label}: " + m.take(300), meta = JSONObject().put("error", true).toString()))
                            }
                        }
                    }
                }
                // Only remember a conversation that actually worked, so a failed first turn can't poison the next.
                if (ok && newSession != null) settings.setExtra("claude_session", newSession)
                if (retry && attempt == 1) { settings.setExtra("claude_session", null); resume = null; continue }
                break
            }
        } catch (e: CancellationException) {
            db.chat().insert(ChatItem(kind = "notice", text = "Stopped."))
        } catch (e: Exception) {
            db.chat().insert(ChatItem(kind = "notice", text = friendlyError(e), meta = JSONObject().put("error", true).toString()))
        } finally { _status.value = AgentStatus(); phone.workFinished() }
    }

    private val phoneToolNames by lazy { tools().map { it.spec.name }.toSet() }

    // ---- helpers & routines -------------------------------------------------------------------

    private suspend fun runHelper(task: String, label: String, parent: ToolContext): String {
        val provider = providerFactory(settings) ?: error("No provider")
        _status.value = _status.value.copy(helpers = _status.value.helpers + 1)
        val itemId = db.chat().insert(ChatItem(kind = "helper", text = task, meta = JSONObject().put("label", label).put("state", "working").toString()))
        val ctx = ChatContext(label, interactive = false, parentAsk = parent, helperItem = itemId)
        return try {
            val history = mutableListOf(Msg.user(task))
            val sys = systemPrompt(task) + "\n\nYou are a HELPER the main agent started. Do this one job with your tools, then reply with only the findings (facts, links, numbers), no chat. Do not ask the user anything."
            val result = AgentLoop(provider, settings.state.value.helperModel, tools(forHelper = true), maxSteps = 60).run(sys, history, ctx)
            db.chat().get(itemId)?.let { db.chat().update(it.copy(meta = JSONObject(it.meta).put("state", "done").put("result", result.take(4000)).toString())) }
            result
        } catch (e: Exception) {
            db.chat().get(itemId)?.let { db.chat().update(it.copy(meta = JSONObject(it.meta).put("state", "failed").toString())) }
            throw e
        } finally {
            com.past9.phoneaos.browser.BrowserService.release(label)
            _status.value = _status.value.copy(helpers = (_status.value.helpers - 1).coerceAtLeast(0))
        }
    }

    /** Run a routine with nobody watching. Posts its summary into the chat and as a notification. */
    suspend fun runBackground(name: String, prompt: String): String {
        val provider = providerFactory(settings) ?: return "No AI provider configured"
        val ctx = ChatContext("routine", interactive = false)
        val history = mutableListOf(Msg.user("Routine \"$name\": $prompt"))
        phone.workStarted()
        return try {
            val out = AgentLoop(provider, settings.state.value.model, tools()).run(systemPrompt(prompt, background = true), history, ctx)
            db.chat().insert(ChatItem(kind = "agent", text = out, meta = JSONObject().put("routine", name).toString()))
            phone.notify(name, out.lineSequence().firstOrNull { it.isNotBlank() }?.take(180) ?: "Done")
            out
        } catch (e: Exception) {
            val msg = friendlyError(e)
            db.chat().insert(ChatItem(kind = "notice", text = "Routine \"$name\" failed: $msg"))
            msg
        } finally { phone.workFinished() }
    }

    /**
     * A run nobody sees: no chat rows, no notifications. The night shift uses it. Returns the model's
     * final text, or null when no AI is set up.
     */
    suspend fun quietRun(prompt: String, withMemoryTools: Boolean): String? {
        val sys = systemPrompt(prompt, background = true)
        providerFactory(settings)?.let { provider ->
            val tools = if (withMemoryTools) tools(forHelper = true).filter { it.spec.name.startsWith("memory_") || it.spec.name == "task_list" } else emptyList()
            return AgentLoop(provider, settings.state.value.model, tools, maxSteps = 40).run(sys, mutableListOf(Msg.user(prompt)), QuietContext())
        }
        val engine = subscription?.takeIf { settings.state.value.mode == PowerMode.SUBSCRIPTION && it.ready } ?: return null
        var last = ""
        engine.turn(prompt + "\n\nDon't use any tools for this; just reply.", sys, null, mcp.url, settings.localToken()).collect { e -> if (e.optString("type") == "text") last = e.optString("text") }
        return last.ifBlank { null }
    }

    /** Overnight: the model's transcript is folded into the running story, so each day starts clean. The chat the user sees stays. */
    suspend fun compact() = turnLock.withLock {
        db.chat().clearTurns("main"); settings.setExtra("claude_session", null)
    }

    private inner class QuietContext : ToolContext {
        override val agentLabel = "overnight"
        override suspend fun activity(text: String, meta: JSONObject): Long = 0
        override suspend fun updateActivity(id: Long, text: String, meta: JSONObject) {}
        override suspend fun ask(question: String, options: List<String>): String? = null
        override suspend fun notify(title: String, body: String) {}
    }

    fun clearConversation() = scope.launch { db.chat().clear(); db.chat().clearTurns("main"); settings.setExtra("claude_session", null); settings.setExtra("conversation_id", null) }

    // ---- tool context -----------------------------------------------------------------------

    private inner class ChatContext(override val agentLabel: String, private val interactive: Boolean, private val parentAsk: ToolContext? = null, private val helperItem: Long? = null) : ToolContext {
        override suspend fun activity(text: String, meta: JSONObject): Long =
            db.chat().insert(ChatItem(kind = "activity", text = text, meta = meta.put("by", agentLabel).apply { helperItem?.let { put("helper", it) } }.toString()))
        override suspend fun updateActivity(id: Long, text: String, meta: JSONObject) {
            db.chat().get(id)?.let { old -> db.chat().update(old.copy(text = text, meta = JSONObject(old.meta).also { m -> meta.keys().forEach { k -> m.put(k, meta.get(k)) } }.toString())) }
        }
        override suspend fun ask(question: String, options: List<String>): String? {
            if (!interactive) return parentAsk?.ask(question, options)
            val id = db.chat().insert(ChatItem(kind = "question", text = question, meta = JSONObject().put("options", org.json.JSONArray(options)).toString()))
            val d = CompletableDeferred<String>(); pending[id] = d
            val prev = _status.value; _status.value = prev.copy(label = "Waiting for you")
            val isApproval = question.startsWith("APPROVAL|")
            phone.notify(if (isApproval) "Approve?" else "Your agent has a question", if (isApproval) question.split("|").getOrElse(1) { "" } else question, id, options)
            return try { d.await() } finally { _status.value = prev }
        }
        override suspend fun notify(title: String, body: String) = phone.notify(title, body)
    }

    companion object {
        fun defaultProvider(s: SettingsStore): LlmProvider? {
            val st = s.state.value
            if (st.mode != PowerMode.API_KEY) return null
            val key = s.apiKey(st.provider) ?: return null
            return providerFor(st.provider, key, st.baseUrl) { conversationId(s) }
        }

        /** One stable id per conversation (new when the chat is cleared), for providers that route by session. */
        fun conversationId(s: SettingsStore): String = s.extra("conversation_id") ?: java.util.UUID.randomUUID().toString().also { s.setExtra("conversation_id", it) }

        fun providerFor(p: Provider, key: String, baseUrl: String = p.baseUrl, session: () -> String = { "check-" + java.util.UUID.randomUUID() }): LlmProvider = when (p) {
            // OpenCode Go/Zen route and cache by conversation: they require x-opencode-session.
            Provider.OPENCODE_GO, Provider.OPENCODE_ZEN -> OpenAiCompatProvider(p.name.lowercase(), key, baseUrl.ifBlank { p.baseUrl }, extraHeaders = { mapOf("x-opencode-session" to session()) })
            Provider.ANTHROPIC -> AnthropicProvider(key, baseUrl.ifBlank { p.baseUrl })
            // DeepSeek reasons by default and can spend a whole small budget thinking; tool use is snappier without it.
            Provider.DEEPSEEK -> OpenAiCompatProvider("deepseek", key, baseUrl.ifBlank { p.baseUrl }, extra = JSONObject().put("thinking", JSONObject().put("type", "disabled")))
            else -> OpenAiCompatProvider(p.name.lowercase(), key, baseUrl.ifBlank { p.baseUrl })
        }

        fun toolGroup(name: String) = when {
            name.contains("browser", true) -> "browser"; name.contains("search", true) || name.contains("fetch", true) -> "web"
            name.contains("memory", true) -> "memory"; name.startsWith("mcp__", true) -> "apps"; else -> "code"
        }

        /** "mcp__claude_ai_Gmail__search_emails" + {"query":"invoice"} -> "Gmail: search emails · invoice" */
        fun describeExternalTool(name: String, detail: String): String {
            val clean = name.removePrefix("mcp__").replace("claude_ai_", "").split("__").let { parts ->
                if (parts.size >= 2) parts[0].replace('_', ' ') + ": " + parts.drop(1).joinToString(" ").replace('_', ' ') else parts[0].replace('_', ' ')
            }
            val pretty = when (clean) { "WebSearch" -> "Searching the web"; "WebFetch" -> "Reading a page"; "TodoWrite" -> "Planning"; "Task" -> "Starting a helper"; else -> clean }
            return pretty + (detail.takeIf { it.isNotBlank() }?.let { " · ${it.take(80)}" } ?: "")
        }

        fun labelFor(tool: String) = when {
            tool.startsWith("browser") -> "Browsing"
            tool.startsWith("memory") -> "Remembering"
            tool.startsWith("apps") -> "Using your apps"
            tool == "delegate" -> "Helpers working"
            tool == "web_fetch" -> "Reading the web"
            tool.startsWith("task") || tool.startsWith("goal") -> "Updating tasks"
            tool.startsWith("routine") -> "Setting up a routine"
            tool == "ask_user" || tool == "request_approval" -> "Waiting for you"
            else -> "Working"
        }

        fun friendlyError(e: Exception): String = when {
            e is ProviderException && e.status == 401 -> "Your API key was rejected. Check it in Settings."
            e is ProviderException && e.status == 429 -> "The AI provider says we are sending too much right now (rate limit or out of credit). Try again shortly."
            e is ProviderException && e.status == 0 -> "I could not reach the AI provider. Check your connection."
            e is ProviderException && e.status == 400 && (e.message ?: "").contains("image", true) -> "This model can't look at images. Pick a vision model in Settings, or send it as text."
            else -> "Something went wrong: ${e.message?.take(240)}"
        }
    }
}

/**
 * Write actions in connected apps need the user's yes, enforced in code (not just asked of the
 * model): sending, posting, deleting, paying, inviting, sharing...
 */
class GuardedAppsRunTool(private val inner: AppsRunTool) : Tool {
    override val spec = inner.spec
    private val writeWords = Regex("(SEND|REPLY|FORWARD|CREATE|DELETE|REMOVE|TRASH|UPDATE|PATCH|POST|PUBLISH|PAY|PURCHASE|ARCHIVE|MOVE|INVITE|SHARE|ADD|INSERT|UPLOAD|ACCEPT|DECLINE|CANCEL)")
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val slug = input.optString("slug").uppercase()
        if (writeWords.containsMatchIn(slug.substringAfter('_'))) {
            val args = input.optJSONObject("arguments")?.toString(2)?.take(1500) ?: "{}"
            val a = ctx.ask("APPROVAL|${slug.lowercase().replace('_', ' ')}|$args", listOf("Approve", "Decline"))
            if (a != "Approve") return if (a == null) "Needs the user's approval and nobody is around. Not done." else "The user declined. Not done."
        }
        return inner.run(input, ctx)
    }
}
