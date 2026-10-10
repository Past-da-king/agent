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
    /**
     * @param role "main" = the orchestrator (only the phone's MCP tools, no web or shell of its own);
     *   "helper" = a worker that does the actual job.
     * @param model null = the user's chosen subscription model.
     */
    fun turn(prompt: String, system: String, resume: String?, mcpUrl: String, mcpToken: String, images: List<String> = emptyList(),
             model: String? = null, role: String = "main"): kotlinx.coroutines.flow.Flow<JSONObject>
    /** The harness can fork a running session (new session, same transcript so far). Codex and OpenCode can't: their branches start fresh with a handoff. */
    val canFork: Boolean get() = false
    /** Like [turn] but forked off `parent`: the new session starts with everything `parent` has done so far. */
    fun forkTurn(prompt: String, system: String, parent: String, mcpUrl: String, mcpToken: String, images: List<String> = emptyList(),
                 model: String? = null, role: String = "main"): kotlinx.coroutines.flow.Flow<JSONObject> = turn(prompt, system, null, mcpUrl, mcpToken, images, model, role)
}

/** Who a system prompt is for. */
enum class PromptRole { MAIN, HELPER, ROUTINE }

/** A helper the main agent set going: it lives until its job is done, the user stops it, or the app closes. */
data class HelperRun(val id: Long, val label: String, val task: String, val startedAt: Long, val job: Job)

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
        com.past9.phoneaos.runtime.LocalMcpServer(settings.localToken(), { tools() }, { ChatContext("main", interactive = true) },
            helper = { id -> helperCtx[id]?.let { ctx -> tools(forHelper = true) to ctx } },
            // A branch has its own endpoint, so its step budget and its activity are its own.
            branch = { id -> branches[id]?.let { b -> tools(turn = b.turn) to branchCtx.getValue(id) } },
            routine = { id -> routineCtx[id]?.let { ctx -> tools(forHelper = true) to ctx } },
            // Card scripts: read-only app tools, and nobody to approve, so anything that writes is refused.
            card = { workerTools().filter { it.spec.name.startsWith("apps_") || it.spec.name.startsWith("COMPOSIO_") } to QuietContext() }).start()
    }
    private val _status = MutableStateFlow(AgentStatus())
    val status: StateFlow<AgentStatus> = _status
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<String>>()
    /** Questions the MAIN agent is waiting on: typing in the chat answers these (helpers' questions are answered by tapping). */
    private val mainQuestions = ConcurrentHashMap.newKeySet<Long>()
    private val turnLock = Mutex()
    /** Main-agent turns: the user's messages and helper results coming back. */
    private val turnJobs = ConcurrentHashMap.newKeySet<Job>()

    /** Helpers working right now, by their chat item id. */
    private val helpers = ConcurrentHashMap<Long, HelperRun>()
    private val helperCtx = ConcurrentHashMap<Long, ToolContext>()
    /** Routines running on a subscription, by run id: what their MCP endpoint acts as. */
    private val routineCtx = ConcurrentHashMap<Long, ToolContext>()
    private val routineRuns = java.util.concurrent.atomic.AtomicLong()
    /** A finished helper's own conversation (or its harness session), so it can be sent back to try again. */
    private val helperHistory = ConcurrentHashMap<Long, MutableList<Msg>>()
    private val helperSession = ConcurrentHashMap<Long, String>()
    /** Messages for helpers that are still working: each reads its queue before every step. */
    private val steerInbox = ConcurrentHashMap<Long, java.util.concurrent.ConcurrentLinkedQueue<String>>()

    /** The main agent may do a small job itself: at most this many action steps per turn, then it must delegate. */
    class StepBudget(val max: Int = 5) { @Volatile var used = 0 }
    /** The main line, and the branches forked off it while it works (double texting). */
    private val lineSeq = java.util.concurrent.atomic.AtomicLong()
    private val mainLine = Line("main", lineSeq.incrementAndGet())
    private val mainTurn = Turn(mainLine)
    private val mainBudget get() = mainLine.budget
    /** Lines whose turn is running (or queued) right now. A message sent while any is non-empty forks instead of waiting. */
    private val activeLines = ConcurrentHashMap<String, Line>()
    private val mainPending = java.util.concurrent.atomic.AtomicInteger()
    private val branches = ConcurrentHashMap<Long, Branch>()
    private val branchCtx = ConcurrentHashMap<Long, ToolContext>()
    /** Branches that have finished but are not yet folded back into the main line. */
    private val unmerged = java.util.concurrent.atomic.AtomicInteger()
    /** Helper id -> the branch that started it: its result goes back to that branch, not the main line. */
    private val helperBranch = ConcurrentHashMap<Long, Long>()
    /** Messages are admitted one at a time, in the order they were sent, so rapid ones chain correctly. */
    private val admitTail = java.util.concurrent.atomic.AtomicReference<Job?>(null)
    /** Decides how a message sent mid-work relates to the work running. */
    var branchPolicy: BranchPolicy = HeuristicPolicy

    /** An action tool in the main agent's hands: it counts against the per-turn step budget. */
    private class Budgeted(private val inner: Tool, private val budget: StepBudget) : Tool {
        override val spec = inner.spec
        override suspend fun run(input: JSONObject, ctx: ToolContext): String {
            if (budget.used >= budget.max) return "Step limit: you've used your ${budget.max} steps for this turn. This job is bigger than a quick one, so hand it to a helper with delegate and put what you've found so far in the brief."
            budget.used++
            return inner.run(input, ctx)
        }
    }
    /**
     * A worker tool in a helper's hands: when the main agent answered or sent back a request the tool made
     * of the user, the helper hears it from the tool (pushed back: in place of the tool's own "declined").
     */
    private inner class Gated(private val inner: Tool) : Tool {
        override val spec = inner.spec
        override suspend fun run(input: JSONObject, ctx: ToolContext): String {
            val helper = ctx.agentLabel.removePrefix("helper-").toLongOrNull()
            // A helper's notification goes to the main agent, which decides whether the user hears it.
            if (helper != null && spec.name == "notify_user") {
                deliver("[Helper #$helper \"${helpers[helper]?.label ?: "helper"}\" wanted to notify the user] ${input.optString("title")}: ${input.optString("body")}\n" +
                    "(They don't see this unless you pass it on with notify_user or tell them. If it's only progress, HOLD.)")
                return "Passed to ${agentName()}, who tells the user if it matters. Put anything they need in your result."
            }
            val out = inner.run(input, ctx)
            val id = helper ?: return out
            val notes = redirects.remove(id)?.toList().orEmpty()
            if (notes.isEmpty()) return out
            val back = notes.filter { it.startsWith(PUSHED) }.map { it.removePrefix(PUSHED) }
            return if (back.isNotEmpty()) EscalationGate.pushedBack(agentName(), back) else out + "\n\n" + notes.joinToString("\n")
        }
    }

    private val _running = MutableStateFlow<List<HelperRun>>(emptyList())
    val running: StateFlow<List<HelperRun>> = _running
    /** Helper results waiting for the main agent to read them (with the helper whose request it is, for a request). */
    private data class Inbound(val note: String, val origin: Long?, val escalation: Long? = null)
    private val inbox = java.util.concurrent.ConcurrentLinkedQueue<Inbound>()

    /** A helper's request for the user, waiting on the main agent's triage (one per helper: it's blocked until then). */
    private class Escalation(val helperId: Long, val label: String, val kind: EscalationGate.Kind, val question: String, val options: List<String>,
                             val result: CompletableDeferred<EscalationGate.Triage> = CompletableDeferred(), @Volatile var reminded: Boolean = false)
    private val escalations = ConcurrentHashMap<Long, Escalation>()
    /** What the main agent said instead of the user, for the helper's tool result (helper id -> lines). */
    private val redirects = ConcurrentHashMap<Long, java.util.concurrent.ConcurrentLinkedQueue<String>>()
    val escalationLog = EscalationLog(java.io.File(context.filesDir, "escalations.jsonl"))

    /** Helpers the main agent kept, with their context, to hand later jobs to. */
    val pins = com.past9.phoneaos.data.PinnedAgentStore(java.io.File(context.filesDir, "pinned"))

    private fun agentName() = settings.state.value.agentName.takeIf { it.isNotBlank() && it != "Your agent" } ?: "The main agent"

    private fun refreshHelpers() {
        _running.value = helpers.values.sortedBy { it.startedAt }
        _status.value = _status.value.copy(helpers = helpers.size)
    }

    /** Servers and computers the user connected; the agent hands them heavy work over SSH. */
    val machines = com.past9.phoneaos.machines.MachineService(com.past9.phoneaos.machines.MachineStore(settings))
    val composio = ComposioClient({ settings.composioKey() }, settings.installId())
    /** Profiles (parts of the user's life, each a set of connected accounts) and each account's rules. */
    val profiles = com.past9.phoneaos.data.ProfileStore(context)
    /** The profile each helper was handed (helper id -> profile id): it may only use that profile's accounts. */
    private val helperProfile = ConcurrentHashMap<Long, String>()
    /** Helpers started together for one answer: held until all of them finish, then reported as one message. */
    private val helperBatch = ConcurrentHashMap<Long, String>()
    private val batchResults = ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<Pair<Long, String>>>()

    fun profileOf(ctx: ToolContext): com.past9.phoneaos.data.AgentProfile? =
        ctx.agentLabel.removePrefix("helper-").toLongOrNull()?.let { helperProfile[it] }?.let { profiles.find(it) }

    private val accountCache = ConcurrentHashMap<String, Pair<Long, List<com.past9.phoneaos.data.AccountRef>>>()
    /** The user's signed-in accounts for these app slugs (cached a minute; unknown slugs are simply empty). */
    suspend fun appAccounts(slugs: List<String>): List<com.past9.phoneaos.data.AccountRef> = slugs.flatMap { slug ->
        accountCache[slug]?.takeIf { System.currentTimeMillis() - it.first < 60_000 }?.second ?: run {
            val k = settings.composioKey()?.trim()
            val list = if (k != null && com.past9.phoneaos.tools.ComposioConnect.isConsumerKey(k))
                com.past9.phoneaos.tools.ComposioConnect.accounts(k, listOf(slug))[slug].orEmpty().filter { it.status == "ACTIVE" }
                    .map { com.past9.phoneaos.data.AccountRef(it.id, slug, it.label.ifBlank { com.past9.phoneaos.tools.AppCatalog.name(slug) }) }
            else composio.connections().filter { it.toolkit == slug && it.status == "ACTIVE" }.map { com.past9.phoneaos.data.AccountRef(it.id, slug, it.label) }
            list.also { accountCache[slug] = System.currentTimeMillis() to it }
        }
    }
    val appGuard = com.past9.phoneaos.tools.AppGuard({ profiles.rules(it) }, { slugs -> slugs.flatMap { s -> runCatching { appAccounts(listOf(s)) }.getOrDefault(emptyList()) } }, ::profileOf)

    /** A new account got connected mid-task: a helper's own profile takes it, and the user is offered to file it. */
    private suspend fun accountConnected(ctx: ToolContext, acc: com.past9.phoneaos.data.AccountRef) {
        accountCache.remove(acc.slug)
        profileOf(ctx)?.let { profiles.setMember(it.id, acc, true) }
        com.past9.phoneaos.system.UiBus.addToProfile.emit(acc)
    }
    /** The user's live cards (the same store the UI shows). */
    val cards: com.past9.phoneaos.cards.CardStore get() = com.past9.phoneaos.App.graph(context).cards

    init {
        // A fresh process has no live helpers: anything still marked working was cut off. Pick it up again.
        scope.launch { resumeCutOffHelpers() }
        // Same for branches: one still marked running was cut off. Keep what it did and fold it back into the main line cleanly.
        // Its own turn (not inside the launch above), so awaitIdle and the merge bookkeeping see it.
        launchTurn {
            val rows = db.chat().all().first().filter { it.kind == "branch" }
            rows.filter { JSONObject(it.meta).optString("state") == "running" }.forEach { r ->
                db.chat().update(r.copy(meta = JSONObject(r.meta).put("state", "interrupted").put("error", "Interrupted when the app closed.").toString()))
                db.chat().insert(ChatItem(kind = "notice", text = "I was partway through \"${r.text.take(80)}\" when the app closed. Tell me to carry on and I will."))
            }
            unmerged.set(rows.count { !JSONObject(it.meta).optBoolean("merged") })
            if (unmerged.get() > 0) mergeBranches()
        }
    }

    /**
     * The app died (a crash, or Android reclaimed it) while helpers were working. Each one is started again where it
     * left off, so a dead app no longer loses the job. A helper that keeps taking the app down with it gets
     * [MAX_RESUMES] tries, then is marked failed, so a bad job can't crash-loop the app.
     */
    internal suspend fun resumeCutOffHelpers() {
        val cut = db.chat().all().first().filter { it.kind == "helper" && JSONObject(it.meta).optString("state") == "working" }
        // The subscription runtime can take a moment to be ready on a fresh start.
        if (cut.isNotEmpty() && settings.state.value.mode == PowerMode.SUBSCRIPTION) repeat(20) { if (subscription?.ready != true) kotlinx.coroutines.delay(500) }
        for (row in cut) {
            val meta = JSONObject(row.meta)
            val tries = meta.optInt("resumes")
            val st = settings.state.value
            val sub = subscription?.takeIf { st.mode == PowerMode.SUBSCRIPTION && it.ready }
            val provider = if (sub == null) runCatching { providerFactory(settings) }.getOrNull() else null
            if (tries >= MAX_RESUMES || (sub == null && provider == null)) {
                meta.put("state", "failed").put("result", if (tries >= MAX_RESUMES) "Interrupted when the app closed, and it didn't get further after $tries tries." else "Interrupted when the app closed.")
                meta.remove("now")
                db.chat().update(row.copy(meta = meta.toString()))
                continue
            }
            val label = meta.optString("label").ifBlank { "Helper" }
            val session = meta.optString("session").takeIf { it.isNotBlank() }
            // What it had already done, from its own activity trail, so it carries on instead of starting over.
            val trail = db.chat().all().first().filter { it.kind == "activity" && JSONObject(it.meta).optLong("helper") == row.id }.takeLast(8).joinToString("\n") { "- ${it.text}" }
            val note = "[The app was closed while you were working, so you were cut off. Carry on from where you got to; don't redo what is already done." +
                (if (trail.isNotBlank()) " What you had done so far:\n$trail" else "") + "]"
            meta.optString("profile").takeIf { it.isNotBlank() }?.let { n -> profiles.find(n)?.let { helperProfile[row.id] = it.id } }
            db.chat().update(row.copy(meta = meta.put("resumes", tries + 1).put("startedAt", System.currentTimeMillis()).toString()))
            db.chat().insert(ChatItem(kind = "activity", text = "Picked up again after the app restarted", meta = JSONObject().put("tool", "agent").put("by", label).put("helper", row.id).toString()))
            val prompt = if (session != null) note else row.text + "\n\n" + note
            // A subscription helper resumes its own session, so its history only names the task; an API helper starts from the note.
            launchHelper(row.id, label, mutableListOf(Msg.user(if (sub != null) row.text else prompt)), prompt, session, provider, sub, meta.optString("model"))
        }
    }

    /**
     * forHelper = false: the MAIN agent's tools. It understands the user and orchestrates: memory, goals and
     * tasks, asking, and handing work to helpers. It has nothing that acts on the world (no browser, web,
     * apps, code, files, machines or routine changes), so every action goes through a helper.
     * forHelper = true: the helpers' tools, everything that does the work.
     */
    fun tools(forHelper: Boolean = false, turn: Turn = mainTurn): List<Tool> {
        if (forHelper) return workerTools().map { Gated(it) }
        val own = listOf(
            NowTool(),
            MemorySearchTool(db.memory()), MemorySaveTool(db.memory()), MemoryGetTool(db.memory()), MemoryUpdateTool(db.memory()),
            TaskListTool(db.tasks()), TaskAddTool(db.tasks()), TaskUpdateTool(db.tasks()), GoalCreateTool(db.tasks()),
            NotificationsTool(db.notifications()) { settings.state.value.notifApps },
            ScheduleListTool(db.triggers()),
            AskUserTool(), NotifyTool(), VoiceNoteTool(context, settings, db.chat()),
            com.past9.phoneaos.tools.ReportTool(context) { item -> db.chat().insert(item.copy(meta = JSONObject(item.meta).apply { replyMeta(turn, this) }.toString())) },
            DelegateTool { task, label, model, profile, batch, pinned -> startHelper(task, label, model, profile, batch, pinned, turn) },
            HelperSteerTool { id, message, restart -> steerHelper(id, message, restart) },
            HelperPushTool { id, message -> pushHelper(id, message) },
            HelpersStatusTool { helpersReport() },
            HelperStopTool { id -> stopHelper(id) },
            EscalationTriageTool { helper, decision, message, needs -> triage(helper, decision, message, needs) },
            AgentPinTool { helper, name, summary, goodFor, why -> pinAgent(helper, name, summary, goodFor, why) },
            AgentUnpinTool { name -> unpinAgent(name) },
            AgentsPinnedTool { pinnedReport() },
            com.past9.phoneaos.tools.ProfileCreateTool(profiles) { slugs -> appAccounts(slugs) },
            com.past9.phoneaos.cards.CardListTool(cards), com.past9.phoneaos.cards.CardShowTool(cards, db.chat()), com.past9.phoneaos.cards.CardPinTool(cards),
        )
        // Small jobs (a page, a quick lookup) it may do itself, within the step budget. Routines and watchers stay with helpers.
        val mine = own.map { it.spec.name }.toSet() + setOf("report", "routine_create", "routine_delete", "watcher_save", "skill_save", "skill_find",
            "card_guide", "card_save", "card_update", "card_delete")
        return own + workerTools().filter { it.spec.name !in mine }.map { Budgeted(it, turn.line.budget) }
    }

    /** Everything that acts: what helpers and routines work with. */
    private fun workerTools(): List<Tool> {
        val list = mutableListOf<Tool>(
            NowTool(), WebFetchTool(),
            MemorySearchTool(db.memory()), MemoryGetTool(db.memory()),
            TaskListTool(db.tasks()), TaskUpdateTool(db.tasks()),
            NotificationsTool(db.notifications()) { settings.state.value.notifApps },
            RunCodeTool(context),
            LocationTool(context), NotificationsAllowTool(context, settings), NotificationsStopTool(settings), FilesPickTool(context), PhotosTool(context),
            WaitForCodeTool(db, settings, NotificationsAllowTool(context, settings)),
            com.past9.phoneaos.machines.SendFileTool(listOf(context.filesDir, context.cacheDir, android.os.Environment.getExternalStorageDirectory())),
            com.past9.phoneaos.tools.PhoneFilesTool(context),
        )
        list += if (machines.store.machines.value.isEmpty()) listOf(com.past9.phoneaos.machines.MachineListTool(machines))
            else com.past9.phoneaos.machines.machineTools(machines, java.io.File(context.filesDir, "downloads/machines"))
        list += browserTools(context)
        val openSignIn: suspend (String) -> Unit = { url -> com.past9.phoneaos.system.UiBus.openInBrowser.emit(com.past9.phoneaos.system.UiBus.OpenInBrowser(url)) }
        if (settings.state.value.composioEnabled && com.past9.phoneaos.tools.ComposioConnect.isConsumerKey(settings.composioKey())) {
            list += com.past9.phoneaos.tools.ComposioConnect.asTools({ settings.composioKey()?.trim() }, openSignIn, appGuard) { ctx, acc -> accountConnected(ctx, acc) }
        } else if (settings.state.value.composioEnabled) {
            list += AppsListTool(composio); list += AppsFindToolsTool(composio)
            list += GuardedAppsRunTool(AppsRunTool(composio), appGuard)
            list += AppsConnectTool(composio) { _, url -> openSignIn(url) }
        }
        list += AskUserTool(); list += ApprovalTool(); list += NotifyTool()
        list += SkillFindTool(db.memory()); list += SkillSaveTool(db.memory())
        list += com.past9.phoneaos.cards.CardGuideTool(context)
        list += com.past9.phoneaos.cards.CardSaveTool(context, cards, settings, db.chat()) { c -> com.past9.phoneaos.cards.CardScripts.schedule(context, c, onRoutineChanged, onRoutineDeleted) }
        list += com.past9.phoneaos.cards.CardUpdateTool(cards); list += com.past9.phoneaos.cards.CardListTool(cards); list += com.past9.phoneaos.cards.CardShowTool(cards, db.chat())
        list += com.past9.phoneaos.cards.CardPinTool(cards)
        list += com.past9.phoneaos.cards.CardDeleteTool(cards) { c -> com.past9.phoneaos.cards.CardScripts.schedule(context, c, onRoutineChanged, onRoutineDeleted) }
        list += ScheduleCreateTool(db.triggers()) { onRoutineChanged(it) }
        list += ScheduleListTool(db.triggers())
        list += com.past9.phoneaos.triggers.WatcherSaveTool(context) { onRoutineChanged(it) }
        list += ScheduleDeleteTool(db.triggers()) { onRoutineDeleted(it) }
        return list
    }

    // ---- prompt ---------------------------------------------------------------------------

    suspend fun systemPrompt(latestUserText: String, background: Boolean = false, role: PromptRole = if (background) PromptRole.ROUTINE else PromptRole.MAIN,
                             profile: com.past9.phoneaos.data.AgentProfile? = null, pinnedAgent: com.past9.phoneaos.data.PinnedAgent? = null): String {
        val s = settings.state.value
        val now = ZonedDateTime.now()
        val pinned = db.memory().pinned()
        val allMem = db.memory().list()
        val recalled = Recall.rank(latestUserText, allMem.filter { it.kind != SKILL }, if (role == PromptRole.MAIN) 8 else 6).filter { m -> pinned.none { it.id == m.id } }
        val skills = if (role == PromptRole.MAIN) emptyList() else Recall.rank(latestUserText, allMem.filter { it.kind == SKILL }, 3)
        val goals = db.tasks().openGoals(); val tasks = db.tasks().openTasks()
        val main = role == PromptRole.MAIN
        return buildString {
            if (s.agentName != "Your agent") appendLine(if (main) "Your name is ${s.agentName}." else "You work for ${s.agentName}, the user's personal agent.")
            if (main) appendLine("You are the user's personal agent, living on their Android phone. One agent, one ongoing conversation.")
            else appendLine("You are a worker for the user's personal agent on their Android phone. You get things done: find, fetch, book, send, build, follow up.")
            if (s.userName.isNotBlank()) appendLine("The user's name is ${s.userName}.")
            appendLine("Now: ${now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm"))} (${now.zone}). Always resolve 'today', 'tomorrow', '10am' in THIS timezone and read concrete dates back to the user.")
            appendLine()
            if (main) {
                appendLine("YOUR JOB")
                appendLine("Understand who the user is and what they want, at every moment, and get it done exactly how they want it, through helpers.")
                appendLine("- Helpers do the work. You may do a SMALL job yourself only when it takes 5 steps or fewer (open a page at a URL, one quick lookup, one fetch, read a notification); you have a hard limit of 5 action steps per turn. Anything bigger, anything multi-step, anything with sign-ins, forms or back-and-forth with a site, any booking, buying or sending: delegate. When in doubt, delegate.")
                appendLine("- Routines and watchers are always created by a helper, never by you.")
                appendLine("- What you always do yourself: read and write your memory, keep goals and tasks in order, think, plan, and talk to the user.")
                appendLine("- Writing briefs is your craft. The user often rambles; work out what they actually want, search memory for everything relevant (people, places, accounts, preferences, past decisions), and give each helper a precise brief: the goal, the context, their preferences and constraints, exactly what to do, what to bring back, and what must get the user's approval before it goes out. A helper knows NOTHING except your brief, so never write 'as discussed' or 'the usual'.")
                appendLine("- Split independent work across helpers so it runs at once (one per site, person or option).")
                appendLine("- After delegating, tell the user in one short line what you set going, then end your turn. Never wait for helpers. The user can keep talking to you while they work, and anything new they ask for goes to another helper.")
                appendLine("- A helper's result arrives as a message starting \"[Helper #\" (or \"[Helpers finished\" for a group). Check it against what the user wanted. If it's right, tell the user the outcome (short, lead with the answer, name sources) and save anything durable to memory. If it's wrong or thin, delegate again with a sharper brief. Never paste a raw dump.")
                appendLine("- ONE PACKED ANSWER. When several helpers work on parts of ONE answer (compare three shops, research one decision from four angles), start them in one delegate call with together true: their results come back to you as one message once ALL are done, and you give the user one packed answer. Jobs that have nothing to do with each other: leave together off and report each as it lands.")
                appendLine("- You decide what is worth saying now. A result that is only a step toward something the user is still waiting on, or that changes nothing for them, doesn't need a message: reply with exactly HOLD (nothing else) and the user sees nothing. Something important or urgent (a deadline, money, a problem, a question only they can answer): tell them straight away, in a line. If they ask how things are going, always answer.")
                appendLine("- 'How's it going?' or 'what are the helpers doing?': helpers_status, then answer.")
                appendLine("- DOUBLE TEXTING: the user may message you while you are still working. The app then forks a branch of you (with all your context so far) to handle it, and folds it back in when done. A note starting \"[Branch merge\" in your history is that branch's finished work: use it, never redo it. A message you answered only with HOLD still counts as answered.")
                val ps = profiles.profiles.value
                appendLine()
                appendLine("PROFILES: every helper works inside exactly one (parts of the user's life, each with its own connected accounts)")
                ps.forEach { p -> appendLine("- ${p.name}: ${p.accounts.joinToString { "${com.past9.phoneaos.tools.AppCatalog.name(it.slug)} ${it.label}" }.ifBlank { "no accounts" }}${if (p.browser.isNotBlank()) "; browser sign-ins: ${p.browser}" else ""}") }
                if (ps.none { it.name == com.past9.phoneaos.data.AgentProfile.GENERAL }) appendLine("- ${com.past9.phoneaos.data.AgentProfile.GENERAL}: none of the user's accounts (made when first used)")
                appendLine("- Name the profile for each brief with `profiles` in delegate. A helper can ONLY use its profile's accounts; with one account of an app in it, that one is picked for it. Jobs that need none of their accounts (web research, the phone's own tools) go in ${com.past9.phoneaos.data.AgentProfile.GENERAL}, which is also what a helper gets when you name none.")
                appendLine("- If no profile fits the job, make one with profile_create (a clear name, the apps it needs) before delegating. The user sees it in Connections in its own colour.")
                appendLine("- Each connected account has the user's own rules (reading and changing things: allowed, ask first, or never). The app enforces them; if an action is refused, tell the user in a line and don't look for a way round it.")
                appendLine("- When the user adds to or corrects a job a helper is already doing ('wait, I actually meant...'), helper_steer that helper: it gets your message before its next step and keeps going. If it's heading the wrong way, helper_steer with restart true: it stops and starts again with the new information. If the job isn't wanted any more, helper_stop.")
                appendLine("- Helpers often say they can't do something when they can. When one gives up or comes back thin, send it back with helper_push (it keeps what it found): name a concrete next thing to try (another site, another search, the browser instead of a fetch, a handoff for a sign-in) and tell it plainly you believe it can do it. Push at least twice before you accept 'I can't' or tell the user.")
                appendLine("- HELPERS NEVER REACH THE USER DIRECTLY. A helper's question, approval, browser handoff or app connection comes to you first (\"[Helper #N ... wants the user]\") and the helper waits for escalation_triage. Forward only what truly needs the person (a captcha, a passkey or 2FA code on their device, their phone number, a payment, something sent or posted in their name, a choice only they can make), with one line on why. Answer it yourself when you know. Anything else, push back with exactly what to try: helpers give up on fiddly pages (dropdowns, date pickers, odd fields) that they can do by clicking each option, typing into the focused field or using arrow keys.")
                appendLine("- PINNED AGENTS: when a helper cracks something non-obvious (a hard sign-up, an undocumented route, a site that fights scraping) or will own an ongoing area, pin it with agent_pin: a name, what it knows and is good at, and why. It keeps its whole context; later jobs that fit go to it (delegate with agents) instead of a fresh helper. Pin rarely, keep what it knows up to date, agent_unpin stale ones. This is yours to manage: never ask or tell the user about pins.")
                val roster = helperRoster()
                appendLine()
                appendLine("YOUR HELPERS (pick one per brief with `models` in delegate; the first is the default)")
                roster.forEach { h -> appendLine("- ${h.name}${if (h.id.isNotBlank() && h.id != h.name) " (id: ${h.id})" else ""}${if (h.tags.isNotEmpty()) ": good for ${h.tags.joinToString(", ")}" else ""}") }
                appendLine("- Match the job to the helper the user tagged for it. Fit the brief to the model: a frontier model (Opus, GPT-5, Gemini Pro and the like) needs the goal, all the context and what to bring back, then works out how by itself. A smaller or faster model can do the job too but needs a fuller brief: the steps in order, which site or tool to start with, what to check, what to do if something fails, and the exact shape of the answer.")
                pins.prune()
                val kept = pins.agents.value
                if (kept.isNotEmpty()) {
                    appendLine()
                    appendLine("YOUR PINNED AGENTS (send a job that fits to one with `agents` in delegate; it remembers its earlier work)")
                    kept.sortedByDescending { it.lastUsedAt }.forEach { a ->
                        val busy = helpers.containsKey(a.lastHelper)
                        appendLine("- ${a.name}${if (a.goodFor.isNotEmpty()) " (good for ${a.goodFor.joinToString(", ")})" else ""}${if (busy) " [busy, #${a.lastHelper}]" else ""}: ${a.summary.take(400)}" +
                            " Jobs: ${a.jobs.size}, last used ${java.time.Instant.ofEpochMilli(a.lastUsedAt).atZone(now.zone).format(DateTimeFormatter.ofPattern("d MMM"))}.")
                    }
                }
                appendLine("- ASSUME YOU ALREADY KNOW. Before asking the user anything, search memory (memory_search, then memory_get). Ask (ask_user, with buttons) only when you've checked and truly don't know, or it's a choice only they can make. Needless questions annoy them.")
                appendLine("- Memory is your wiki about the user's life: one page per person, company, place, project or preference, in markdown, linking other pages as [[Name]], always tagged. Whenever you learn something durable (from them or from a helper's result), save or update the page right away (memory_get first to merge). Keep pages accurate and fix wrong ones. This is the most important thing you do.")
                appendLine("- The user uses you as their to-do list. Things THEY must do or asked to be reminded of go on their list (task_add owner user). The work you hand out lives under goals (goal_create) with a task per step; tick them off as helper results come in, and when stuck mark the step blocked and say what would unblock it.")
                appendLine("- Routines: you come up with the ideas (a morning brief, watching a price, chasing a reply) and suggest them. When the user agrees, a helper creates it: delegate with the exact schedule and the instruction the routine should run. routine_list shows what exists.")
                if (s.notifApps.isNotEmpty()) appendLine("- You can read the phone's notifications from the apps the user allowed (notifications_read): bank alerts, messages, deliveries. Read them when they help you understand what's going on.")
                appendLine("- CARDS: the user can have live cards, small screens in the app's own look that open from Home or the chat (card_list shows them). When they want something to keep an eye on or see at a glance (a tracker, a dashboard, prices every day, a daily brief, search results they'll come back to), delegate building a CARD. Brief the helper: what it must show and in what order (the most important number first), where the data comes from, and that data should refresh by a SCRIPT whenever code can fetch it (an API, a page, counting emails in a connected app), with the agent filling it only when it needs judgement (a brief, a summary, via a routine calling card_update). Pin it to Home only if they asked for it there. card_show puts an existing card in the chat.")
                appendLine("- Facts like phone numbers, addresses, prices and opening hours must come from a helper that actually read a source; pass the source on. If it isn't verified, say so. Never invent.")
                appendLine()
                appendLine("WHAT YOUR HELPERS CAN DO (brief them to use it; beyond a small job of 5 steps or fewer, this is their work, not yours)")
            } else {
                appendLine("HOW YOU WORK")
                appendLine("- Do the work yourself with your tools instead of telling the user how. Only ask when it is a real choice (ask_user with buttons), or you are truly blocked.")
                appendLine("- Before anything goes out in their name, costs money, or deletes something: request_approval with the exact content. Never skip this.")
                appendLine("- Search memory (memory_search) for anything about the user you need instead of asking.")
                if (role == PromptRole.HELPER) appendLine("- Whatever you ask of the user (ask_user, request_approval, browser_handoff, connecting an app) goes to the main agent first. It answers what it knows and sends back anything you can do yourself. So before a handoff, really try: click each option, type into the focused field, use arrow keys or Enter, browser_look to see the page, another route to the same result. Hand off only for what needs the person: a captcha, a passkey or code on their phone, their phone number, a payment, something sent in their name. Never finish by asking the user to do part of the job themselves.")
            }
            appendLine("- Phone permissions are OFF until needed. Their location (delivery, 'near me'): location_get. A document from the phone: files_pick. Their latest photo or screenshot: photos_recent. A one-time code during sign-in: notifications_wait_code. Watching an app's notifications: notifications_allow, a 'notification' routine, then notifications_stop when done.")
            appendLine("- run_code runs JavaScript (Node) on the phone: maths, data crunching, building files (CSV, HTML, SVG, images).")
            appendLine("- Images can be seen: photos the user sends, gallery photos (photos_recent), page screenshots (browser_look), scanned PDFs, and images code makes.")
            appendLine("- The browser_* tools drive a background browser that keeps going while the user is in other apps. If a page needs the user (sign-in, captcha), browser_handoff.")
            appendLine("- 'Sign in with Google' on other sites works best once the browser profile is signed in to Google: if it fails, open accounts.google.com first (handoff so the user signs in), then retry. Read any NOTE at the top of a page outline: it tells you about blocked sign-ins, downloads and app links.")
            appendLine("- Recurring or later work becomes a routine (routine_create). To keep an eye on something with no API (a price on Takealot or Amazon, stock, a page changing), build a WATCHER (watcher_save): find the data source, write and test a small script, save it. It only wakes the agent when it fires.")
            appendLine("- The browser shares the phone's location with sites that ask, and sites can send web notifications (they arrive from 'web:<site>').")
            appendLine("- For ANY file on the user's phone (recordings, downloads, WhatsApp statuses, screenshots) use phone_files, never run_code. If phone_files says access isn't given, ask once, then files_pick.")
            if (!main) appendLine("- To build or change a CARD: card_guide first (the design rules), then card_save. Look at the screenshot it returns and fix anything that isn't beautiful before reporting back.")
            appendLine("- Files move between the phone, machines and websites: phone_files, machine_run (ls, find), machine_download, browser_upload, machine_upload, send_file (shows one to the user).")
            val ms = machines.store.machines.value
            if (ms.isNotEmpty()) appendLine("- The user connected machines to work on over SSH: ${ms.joinToString { it.name + (if (it.about.isNotBlank()) " (" + it.about + ")" else "") }}. Heavy lifting goes there (building, compiling, installing, data crunching, long scripts, big downloads): machine_run for quick commands, machine_job_start for anything long, then machine_job_status. If a machine has a coding agent (claude, codex, opencode) it can run there in a job.")
            else appendLine("- No machines connected. If a task is too heavy for the phone, tell the user they can add any server or computer they can SSH into under Connections > Machines.")
            if (!s.composioEnabled) appendLine("- No apps are connected yet (Gmail, Calendar...). If a task needs one, tell the user they can connect apps in Connections.")
            else if (com.past9.phoneaos.tools.ComposioConnect.isConsumerKey(settings.composioKey())) appendLine("- Connected apps run through the COMPOSIO_* tools (Composio Connect): search for the right tool, check or start connections, then execute. If an app isn't connected, COMPOSIO_MANAGE_CONNECTIONS with action add and a short reason: the user gets a Connect card and the tool waits until it's done. Never paste sign-in links into the chat. An app can have several accounts (two Outlooks, four Gmails): COMPOSIO_MANAGE_CONNECTIONS list shows each with its email, and COMPOSIO_MULTI_EXECUTE_TOOL takes `account` to pick one. Act in the account the task belongs to; for 'my email' in general check every account and say which one each result came from. Never move data from one account through another unless asked, and always name the account used. Actions that send, post, pay or delete ask the user first automatically.")
            else appendLine("- Connected apps run through apps_find_tools then apps_run (apps_connected lists accounts; pass `account` to pick one). If an app isn't connected, apps_connect asks the user and opens the sign-in. Act in the account the task belongs to and name the account used.")
            appendLine()
            appendLine("- To show the user an image (a product photo, a map, a chart), put it in the reply as markdown: ![what it is](https://...). Several images in a row become a swipeable strip.")
            if (main) appendLine("- voice_note scripts are plain spoken words: no emoji, no markdown, no lists, no URLs. After sending one, don't repeat it as text.")
            appendLine("- NEVER use emoji, anywhere. They cheapen the app.")
            if (main) {
                appendLine("STYLE: TLDR, always. Lead with the answer in one or two short lines, then at most a few short bullets if they truly help. Never an essay, never a report in the chat, no headings, no recap of what you did. Plain, warm words. No em dashes.")
                appendLine("- Anything longer (research, a comparison, a plan, a write-up the user will read properly): put it in a REPORT with the report tool. It shows as a card that opens a full-screen reader they can share or save. In the chat say only the one-line takeaway.")
            } else appendLine("STYLE: short. Lead with the answer. Use short bullets for lists. No walls of text. Plain words. No em dashes.")
            if (role == PromptRole.ROUTINE) appendLine("\nThis is a BACKGROUND run (a routine). Nobody is watching. Do the work, then finish with a short summary the user will read later. Use notify_user if something needs their attention.")
            settings.extra("story")?.takeIf { it.isNotBlank() }?.let { appendLine("\n" + (if (main) "WHERE THINGS STAND (your own notes, written overnight; earlier chat is folded into these)" else "BACKGROUND ON THE USER (the agent's notes)") + "\n$it") }
            if (pinned.isNotEmpty()) { appendLine("\nCORE MEMORIES (pinned)"); pinned.forEach { appendLine("- ${Recall.format(it)}") } }
            if (recalled.isNotEmpty()) { appendLine("\nPOSSIBLY RELEVANT MEMORIES"); recalled.forEach { appendLine("- ${Recall.format(it)}") } }
            if (goals.isNotEmpty() || tasks.isNotEmpty()) {
                appendLine("\nOPEN GOALS AND TASKS")
                goals.forEach { appendLine("- Goal #${it.id}: ${it.title}") }
                tasks.take(25).forEach { appendLine("- Task #${it.id} [${it.status}${if (it.owner == "user") ", user's to-do" else ""}] ${it.title}${it.goalId?.let { g -> " (goal #$g)" } ?: ""}") }
            }
            if (main) {
                val live = helpers.values.sortedBy { it.startedAt }
                if (live.isNotEmpty()) { appendLine("\nHELPERS WORKING RIGHT NOW"); live.forEach { appendLine("- #${it.id} ${it.label}: ${it.task.take(160)}") } }
                if (branches.isNotEmpty()) { appendLine("\nBRANCHES OF YOU WORKING RIGHT NOW (each answers its own message and folds back in when done)"); branches.values.sortedBy { it.item }.forEach { appendLine("- ${it.relation.word}: ${it.userText.take(160)}") } }
            }
            if (skills.isNotEmpty()) { appendLine("\nSKILLS EARLIER HELPERS LEFT (use them; they cost someone a struggle)"); skills.forEach { appendLine("## ${it.title}\n${it.body}\n") } }
            if (!main) appendLine("\n- Before anything fiddly, skill_find. If you struggled with something and then got it done, skill_save exactly what worked (steps, traps, how to check) so the next helper doesn't have to fight it.")
            if (role == PromptRole.HELPER && profile != null) {
                appendLine("\nYOUR PROFILE: ${profile.name}. This job belongs to that part of the user's life. Use ONLY these connected accounts: ${profile.accounts.joinToString { "${com.past9.phoneaos.tools.AppCatalog.name(it.slug)} ${it.label} (account ${it.id})" }.ifBlank { "none" }}. Any other account is off limits; if the job needs one that isn't here, say so in your result.")
                if (profile.browser.isNotBlank()) appendLine("Your browser is signed in as the \"${profile.browser}\" browser profile; use that one.")
            }
            if (role == PromptRole.HELPER && pinnedAgent != null) appendLine("\nYOU ARE A PINNED AGENT: \"${pinnedAgent.name}\". The main agent kept you${if (pinnedAgent.why.isNotBlank()) " because ${pinnedAgent.why.trimEnd('.')}" else ""}, and hands you jobs that fit. What you know: ${pinnedAgent.summary}\nYour earlier work is in this conversation; use what you learned rather than starting from zero.")
            if (role == PromptRole.HELPER) appendLine("\nYou are a HELPER the main agent started for one job. Do it fully with your tools. Then reply with ONLY the result for the main agent: what you did, what you found (facts, numbers, links, sources), and anything left undone and why. No chat, no greetings.")
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
        if (fromCall == null && images.isEmpty() && files.isEmpty() && mainQuestions.isNotEmpty()) {
            val id = mainQuestions.maxOrNull()
            if (id != null) {
                scope.launch { db.chat().insert(ChatItem(kind = "user", text = clean)) }
                answer(id, clean)
                return
            }
        }
        // Messages are admitted one at a time, in the order sent: each decides against the newest line of work, so rapid ones chain.
        var prev: Job? = null
        val j = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { prev?.join(); admit(clean, images, files, voiceReply, fromCall) }
        prev = admitTail.getAndSet(j)
        turnJobs += j; j.invokeOnCompletion { turnJobs.remove(j) }
        j.start()
    }

    /** Put the user's message in the chat, then either run it as a turn, or (work is already running) fork a branch for it. */
    private suspend fun admit(clean: String, images: List<String>, files: List<String>, voiceReply: Boolean, fromCall: String?) {
        val target = if (fromCall == null) activeLines.values.maxByOrNull { it.seq } else null
        val rel = target?.let { branchPolicy.classify(clean, it.userText) }
        val origin = if (fromCall != null) { db.chat().insert(ChatItem(kind = "activity", text = "On it: $fromCall", meta = JSONObject().put("tool", "call").toString())); null }
        else db.chat().insert(ChatItem(kind = "user", text = clean, meta = JSONObject().put("images", org.json.JSONArray(images.filter { !it.contains("/pdf-") }))
            .put("files", org.json.JSONArray(files)).put("voiceReply", voiceReply).toString()))
        val forModel = if (voiceReply) "$clean\n\n(The user is on the move: answer with a voice_note, then at most one short line of text. Don't repeat the voice note as text.)" else clean
        if (target == null || rel == null) {
            mainLine.userText = clean
            launchMain { turnLock.withLock { runTurn(forModel, images, replyTo = origin) } }
            return
        }
        // "ok thanks": no agent is worth forking for it. The running line just hears it.
        if (rel == Relation.ACK) { target.notes += "[The user also said, no action needed] $clean"; pendingAdd("[The user said \"$clean\" while you worked. No action needed.]"); return }
        // Cost: never more than a few branches working at once; the rest wait their turn, in order.
        while (activeLines.values.count { it.id != "main" } >= Branching.MAX_BRANCHES) kotlinx.coroutines.delay(100)
        val parent = activeLines.values.maxByOrNull { it.seq } ?: target
        startBranch(parent, rel, clean, forModel, images, origin ?: 0)
    }

    /** Fork the running line's current state into a new agent that starts on the new message at once. */
    private suspend fun startBranch(parent: Line, rel: Relation, text: String, forModel: String, images: List<String>, originId: Long) {
        val itemId = db.chat().insert(ChatItem(kind = "branch", text = text, meta = JSONObject().put("state", "running").put("relation", rel.word)
            .put("parent", parent.id).put("origin", originId).put("startedAt", System.currentTimeMillis()).toString()))
        val line = Line("b$itemId", lineSeq.incrementAndGet()).also { it.userText = text }
        val b = Branch(itemId, line, parent, rel, originId, text).also { it.turn.origin = originId }
        branches[itemId] = b
        branchCtx[itemId] = ChatContext("branch-$itemId", interactive = true, branchItem = itemId, display = "Side thread")
        b.turns.incrementAndGet(); activeLines[line.id] = line
        unmerged.incrementAndGet()
        // Fork from where the working line is RIGHT NOW, then (for a correction) close it: its work so far is kept.
        val base = parent.snapshot; val parentSession = parent.session
        // Related work: the line already going hears about the addition, so the two coordinate instead of both doing it.
        if (rel == Relation.RELATED) parent.notes += "[Heads-up: the user just added \"${text.take(300)}\" to this work. A branch of you is handling that part now: carry on with what you're doing and don't duplicate it.]"
        if (rel == Relation.SUPERSEDE) {
            parent.supersededBy = itemId; parent.job?.cancel()
            if (parent.id != "main") branches[parent.id.removePrefix("b").toLongOrNull() ?: -1]?.outcome = "superseded"
            b.adopt = parent.id == "main"
        }
        val j = launchTurn {
            try { runBranchTurn(b, forModel, images, first = true, base = base, parentSession = parentSession) }
            finally { endBranchTurn(b) }
        }
        line.job = j
    }

    /** A main-agent turn on the app's scope, tracked so stop() and awaitIdle() can reach it. */
    private fun launchTurn(block: suspend CoroutineScope.() -> Unit): Job {
        val j = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY, block = block)
        turnJobs += j; j.invokeOnCompletion { turnJobs.remove(j) }
        j.start(); return j
    }

    /** A turn on the main line: while any is queued or running, the line counts as busy. When the last ends, finished branches fold back in. */
    private fun launchMain(block: suspend CoroutineScope.() -> Unit): Job {
        if (mainPending.getAndIncrement() == 0) activeLines["main"] = mainLine
        val j = launchTurn(block)
        j.invokeOnCompletion { if (mainPending.decrementAndGet() == 0) activeLines.remove("main"); if (unmerged.get() > 0) launchTurn { mergeBranches() } }
        return j
    }

    /** A helper's result goes to the branch that started it while that branch lives; otherwise to the main agent. */
    private fun deliverFor(branchId: Long?, note: String, origin: Long?) {
        val b = branchId?.let { branches[it] } ?: return deliver(note, origin)
        b.turns.incrementAndGet(); activeLines[b.line.id] = b.line
        launchTurn {
            try { runBranchTurn(b, note, emptyList(), first = false) }
            finally { endBranchTurn(b) }
        }
    }

    /** A helper's result goes back to the main agent as a new message (batched if several land together). */
    private fun deliver(note: String, origin: Long? = null, escalation: Long? = null) {
        inbox += Inbound(note, origin, escalation)
        launchMain {
            turnLock.withLock {
                val batch = generateSequence { inbox.poll() }.toList()
                // The reply quotes the message that asked for this work (the newest, if several landed together).
                if (batch.isNotEmpty()) runTurn(batch.joinToString("\n\n") { it.note }, fromUser = false, replyTo = batch.mapNotNull { it.origin }.maxOrNull())
                batch.mapNotNull { it.escalation }.forEach { untriaged(it) }
            }
        }
    }

    /**
     * The main agent read a helper's request but ended its turn without deciding. It gets one reminder; after
     * that the request goes to the user as before (a waiting helper must never hang), and the log says so.
     */
    private suspend fun untriaged(helperId: Long) {
        val e = escalations[helperId]?.takeIf { !it.result.isCompleted } ?: return
        if (!e.reminded) {
            e.reminded = true
            deliver("[Helper #$helperId \"${e.label}\" is still waiting on your decision about its request: ${EscalationGate.plain(e.question).take(300)}]\nCall escalation_triage (helper $helperId) now: forward, answer or push_back.", escalation = helperId)
            return
        }
        val pushes = gatePushes(helperId)
        if (e.result.complete(EscalationGate.Triage.Forward("", "untriaged")))
            escalationLog.add(EscalationLog.entry(helperId, e.label, e.kind, e.question, "forwarded_untriaged", "", "untriaged", pushes))
    }

    // ---- branches -----------------------------------------------------------------------------

    /** Run one turn on a branch: its first (forked from the parent's state), or a helper's result coming back to it. */
    private suspend fun runBranchTurn(b: Branch, text: String, images: List<String>, first: Boolean, base: List<Msg> = emptyList(), parentSession: String? = null) {
        val line = b.line
        b.lock.withLock {
            line.job = kotlin.coroutines.coroutineContext[Job]
            if (first) b.turn.late = false else { b.turn.late = true; b.turn.origin = b.originId }
            _status.value = _status.value.copy(working = true, label = "Thinking", helpers = helpers.size); phone.workStarted()
            try {
                val sub = subscription?.takeIf { settings.state.value.mode == PowerMode.SUBSCRIPTION && it.ready }
                if (sub != null) branchSub(b, sub, text, images, first, parentSession) else branchApi(b, text, images, first, base)
            } catch (e: CancellationException) {
                b.outcome = if (line.supersededBy != null) "superseded" else "stopped"
                if (line.supersededBy == null) db.chat().insert(ChatItem(kind = "notice", text = "Stopped.", meta = JSONObject().put("branch", b.item).toString()))
            } catch (e: Exception) {
                b.outcome = "failed"; b.error = friendlyError(e)
                db.chat().insert(ChatItem(kind = "notice", text = "A side thread (\"${b.userText.take(50)}\") failed: ${friendlyError(e)}", meta = JSONObject().put("error", true).put("branch", b.item).toString()))
            } finally { phone.workFinished() }
        }
    }

    private suspend fun branchApi(b: Branch, text: String, images: List<String>, first: Boolean, base: List<Msg>) {
        val provider = providerFactory(settings) ?: error(if (settings.state.value.mode == PowerMode.SUBSCRIPTION) "Your ${settings.state.value.subKind.label} subscription isn't set up on this phone yet." else "Add an API key in Settings.")
        val thread = "branch-${b.item}"
        if (first) {
            // Forked from the parent's transcript as it stands. If it hasn't got going yet, use what is saved.
            var from = base
            if (from.isEmpty()) from = kotlinx.coroutines.withTimeoutOrNull(1500) { while (b.parent.snapshot.isEmpty()) kotlinx.coroutines.delay(20); b.parent.snapshot } ?: loadHistory("main")
            b.history = Branching.fork(from)
        }
        val history = b.history ?: loadHistory("main").also { b.history = it }
        val msg = Msg.user(if (first) Branching.framed(text, b.relation, b.parent.userText) else text, if (first) images else emptyList())
        history += msg; db.chat().insertTurn(TurnRow(thread = thread, json = msg.toJson().toString())); b.line.snapshot = history.toList()
        val ctx = branchCtx.getValue(b.item)
        AgentLoop(provider, settings.state.value.model, tools(turn = b.turn)).run(systemPrompt(b.userText), history, ctx,
            onEvent = { e ->
                when (e) {
                    is AgentEvent.Thinking -> _status.value = _status.value.copy(working = true, label = if (e.step == 1) "Thinking" else "Working")
                    is AgentEvent.Said -> say(e.text, b.turn)
                    is AgentEvent.ToolStarted -> _status.value = _status.value.copy(label = labelFor(e.call.name))
                    is AgentEvent.ToolFinished -> {}
                }
            },
            onAppend = { m -> db.chat().insertTurn(TurnRow(thread = thread, json = m.toJson().toString())); b.line.snapshot = history.toList() },
            incoming = { generateSequence { b.line.notes.poll() }.toList() })
    }

    private suspend fun branchSub(b: Branch, engine: SubscriptionEngine, text: String, images: List<String>, first: Boolean, parentSession: String?) {
        val sys = systemPrompt(b.userText)
        val url = mcp.url + "/b/${b.item}"
        val canFork = engine.canFork && parentSession != null
        val flow = when {
            !first -> engine.turn(text, sys, b.line.session, url, settings.localToken(), images, role = "main")
            canFork -> engine.forkTurn(Branching.framed(text, b.relation, b.parent.userText), sys, parentSession!!, url, settings.localToken(), images, role = "main")
            // This engine can't fork (or the parent has no session yet): start fresh and hand it what the parent has been doing.
            else -> engine.turn(Branching.handoff(b.parent.userText, recentChat()) + Branching.framed(text, b.relation, b.parent.userText), sys, null, url, settings.localToken(), images, role = "main")
        }
        val r = consumeSub(flow, engine, b.turn, resumed = false)
        r.newSession?.let { b.line.session = it }
        if (r.error != null && !r.ok) { b.outcome = "failed"; b.error = r.error }
        // A correction takes over the main conversation: its session already holds everything the old line did.
        if (r.ok && b.adopt && b.line.session != null) settings.setExtra("claude_session", b.line.session)
    }

    private suspend fun recentChat(): List<String> = db.chat().all().first().filter { it.kind == "user" || it.kind == "agent" }.takeLast(14)
        .map { (if (it.kind == "user") "user: " else "you: ") + it.text.take(240) }

    private fun endBranchTurn(b: Branch) {
        activeLines.remove(b.line.id); lineEnded(b.line)
        b.turns.decrementAndGet(); trySettle(b)
    }

    /** A branch is settled once its turn is over and none of its helpers still work: then it can fold back in. */
    private fun trySettle(b: Branch) {
        if (b.turns.get() != 0 || helperBranch.values.any { it == b.item }) return
        if (branches.remove(b.item) == null) return
        branchCtx.remove(b.item)
        launchTurn {
            db.chat().get(b.item)?.let { row -> db.chat().update(row.copy(meta = JSONObject(row.meta).put("state", b.outcome).apply {
                b.error?.let { put("error", it) }; b.line.session?.let { put("session", it) }; if (b.adopt) put("adopt", true); put("endedAt", System.currentTimeMillis()) }.toString())) }
            mergeBranches()
        }
    }

    /**
     * Fold finished branches back into the main line, in the order the user sent their messages, each as one labelled
     * block, so the next turn sees everything the branches did and nothing is redone. Waits for the main line to be idle
     * and stops at the first branch still working (so the order never jumps).
     */
    private suspend fun mergeBranches() = turnLock.withLock {
        if (activeLines.isNotEmpty()) return@withLock
        val rows = db.chat().all().first().filter { it.kind == "branch" }.sortedBy { JSONObject(it.meta).optLong("origin") }
        val sub = settings.state.value.mode == PowerMode.SUBSCRIPTION && subscription?.ready == true
        for (row in rows) {
            val m = JSONObject(row.meta)
            if (m.optBoolean("merged")) continue
            if (m.optString("state") == "running") break
            val state = m.optString("state")
            val delta = db.chat().recentTurns("branch-${row.id}", 500).reversed().mapNotNull { runCatching { Msg.fromJson(JSONObject(it.json)) }.getOrNull() }
            var (steps, said) = Branching.digest(delta)
            if (delta.isEmpty()) {
                val mine = db.chat().all().first().filter { JSONObject(it.meta).optLong("branch") == row.id }
                steps = mine.filter { it.kind == "activity" }.map { it.text }
                said = mine.filter { it.kind == "agent" }.map { it.text }
            }
            // An adopted correction already lives in the main session; everything else is written into the main line.
            if (!(m.optBoolean("adopt") && sub && state == "done")) {
                val note = Branching.mergeNote(row.id.toInt(), runCatching { Relation.valueOf(m.optString("relation").uppercase()) }.getOrDefault(Relation.PARALLEL),
                    state, row.text, steps, said, m.optString("error").takeIf { it.isNotBlank() })
                if (sub) pendingAdd(note) else db.chat().insertTurn(TurnRow(thread = "main", json = Msg.user(note).toJson().toString()))
            }
            db.chat().update(row.copy(meta = m.put("merged", true).put("mergedAt", System.currentTimeMillis()).toString()))
            db.chat().clearTurns("branch-${row.id}")
            unmerged.updateAndGet { (it - 1).coerceAtLeast(0) }
        }
    }

    /** Notes for the main conversation that a harness session can't have written into it: carried in front of its next prompt. */
    private val pendingLock = Any()
    private fun pendingAdd(note: String) = synchronized(pendingLock) {
        val a = org.json.JSONArray(settings.extra("branch_pending") ?: "[]"); a.put(note); settings.setExtra("branch_pending", a.toString())
    }
    private fun pendingPeek(): List<String> = synchronized(pendingLock) { org.json.JSONArray(settings.extra("branch_pending") ?: "[]").let { a -> (0 until a.length()).map { a.getString(it) } } }
    private fun pendingDrop(n: Int) = synchronized(pendingLock) {
        val a = org.json.JSONArray(settings.extra("branch_pending") ?: "[]"); val keep = org.json.JSONArray()
        for (i in n until a.length()) keep.put(a.get(i)); settings.setExtra("branch_pending", if (keep.length() == 0) null else keep.toString())
    }

    private fun lineEnded(line: Line) {
        val others = activeLines.values.any { it !== line }
        _status.value = AgentStatus(working = others, label = if (others) _status.value.label else "", helpers = helpers.size)
    }

    /** A finished voice call goes into the agent's own history, so the chat carries on from it. */
    fun rememberCall(transcript: String) = scope.launch {
        if (transcript.isBlank()) return@launch
        db.chat().insertTurn(TurnRow(thread = "main", json = Msg.user("[Earlier, on a voice call with you]\n$transcript").toJson().toString()))
    }

    /** Wait until the main agent and every helper are done, including the turns their results start. */
    suspend fun awaitIdle() {
        while (true) {
            val live = (turnJobs + helpers.values.map { it.job }).filter { !it.isCompleted }
            if (live.isEmpty()) return
            live.forEach { it.join() }
        }
    }

    /** Stop everything: the main agent's turn and every helper. */
    fun stop() {
        turnJobs.forEach { it.cancel() }
        helpers.values.forEach { it.job.cancel() }
        inbox.clear()
        pending.values.forEach { it.cancel() }; pending.clear(); mainQuestions.clear()
        escalations.values.forEach { it.result.cancel() }; escalations.clear(); redirects.clear()
        _status.value = AgentStatus(helpers = helpers.size)
    }

    /** Stop one helper. False when it isn't running. */
    fun stopHelper(id: Long): Boolean {
        val h = helpers[id] ?: return false
        h.job.cancel(); return true
    }

    /** The question card the agent is waiting on right now, if any (newest first). */
    fun pendingQuestionId(): Long? = pending.keys.maxOrNull()

    /** The user tapped an option on a question card (in the app or on a notification). */
    fun answer(itemId: Long, option: String) {
        scope.launch {
            db.chat().get(itemId)?.let { db.chat().update(it.copy(meta = JSONObject(it.meta).put("answer", option).toString())) }
            pending.remove(itemId)?.complete(option); mainQuestions.remove(itemId)
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
        private const val INTERRUPTED = "Interrupted before it finished (the app was closed or stopped)."

        /**
         * Every provider rejects a history where tool calls and tool results don't pair up, and keeps
         * rejecting it on every message after ("Messages with role 'tool' must be a response to a preceding
         * message with 'tool_calls'"), so the chat is stuck until the user clears their data. Causes seen:
         * a run cut short (calls with no results), and something else writing to the conversation mid-tool
         * (a routine, a voice call, a typed answer) so the results land after an unrelated message.
         *
         * So: each assistant tool call gets its result placed straight after it (found anywhere before the
         * next assistant message, or a stub saying it was interrupted), and any result no call claims is dropped.
         */
        fun repair(msgs: List<Msg>): MutableList<Msg> {
            val out = mutableListOf<Msg>()
            for ((i, m) in msgs.withIndex()) {
                if (m.role == Role.ASSISTANT) {
                    out += m
                    val calls = m.toolCalls
                    if (calls.isEmpty()) continue
                    val ids = calls.map { it.id }.toSet()
                    val found = linkedMapOf<String, Block.ToolResult>()
                    var j = i + 1
                    while (j < msgs.size && msgs[j].role != Role.ASSISTANT) {
                        msgs[j].blocks.filterIsInstance<Block.ToolResult>().forEach { r -> if (r.callId in ids && r.callId !in found) found[r.callId] = r }
                        j++
                    }
                    // Pictures a tool handed back ride in the same message as the results.
                    val images = msgs.getOrNull(i + 1)?.takeIf { carriesResults(it) }?.blocks?.filterIsInstance<Block.Image>().orEmpty()
                    out += Msg(Role.USER, calls.map { c -> found[c.id] ?: Block.ToolResult(c.id, INTERRUPTED, true) } + images)
                } else {
                    val followsCalls = i > 0 && msgs[i - 1].role == Role.ASSISTANT && msgs[i - 1].toolCalls.isNotEmpty() && carriesResults(m)
                    val rest = m.blocks.filter { it !is Block.ToolResult && !(followsCalls && it is Block.Image) }
                    if (rest.isNotEmpty()) out += if (rest.size == m.blocks.size) m else Msg(m.role, rest)
                }
            }
            return out
        }

        private fun carriesResults(m: Msg) = m.role == Role.USER && m.blocks.any { it is Block.ToolResult }

        /**
         * Last resort when a provider still rejects the history's tool exchange: keep every word the user
         * and the agent said, drop the earlier tool calls and results. The current turn (after the last
         * thing the user typed) is left whole so the work in progress carries on.
         */
        fun flatten(msgs: List<Msg>): MutableList<Msg> {
            val lastUser = msgs.indexOfLast { it.role == Role.USER && it.blocks.any { b -> b is Block.Text } }.coerceAtLeast(0)
            val before = msgs.take(lastUser).mapNotNull { m ->
                val keep = m.blocks.filter { it is Block.Text || it is Block.Image }
                if (keep.isEmpty()) null else Msg(m.role, keep)
            }
            return (before + msgs.drop(lastUser)).toMutableList()
        }
    }

    /** @param fromUser a message the user sent: it starts a fresh 5-step budget. A helper's result coming back doesn't. */
    private suspend fun runTurn(userText: String, images: List<String> = emptyList(), fromUser: Boolean = true, replyTo: Long? = null) {
        if (fromUser) mainBudget.used = 0
        mainTurn.origin = replyTo; mainTurn.late = !fromUser
        if (settings.state.value.mode == PowerMode.SUBSCRIPTION && subscription?.ready == true) return runSubscriptionTurn(userText, subscription!!, images)
        val provider = providerFactory(settings)
        if (provider == null) {
            db.chat().insert(ChatItem(kind = "notice", text = if (settings.state.value.mode == PowerMode.SUBSCRIPTION)
                "Your ${settings.state.value.subKind.label} subscription isn't set up on this phone yet. Open Settings to finish setup, or switch to an API key." else "Add an API key in Settings so I can start working."))
            return
        }
        _status.value = _status.value.copy(working = true, label = "Thinking")
        phone.workStarted()
        mainLine.job = kotlin.coroutines.coroutineContext[Job]; mainLine.supersededBy = null
        val history = loadHistory("main")
        val first = Msg.user(userText, images)
        history += first; db.chat().insertTurn(TurnRow(thread = "main", json = first.toJson().toString()))
        mainLine.snapshot = history.toList()
        val ctx = ChatContext("main", interactive = true)
        try {
            val s = settings.state.value
            AgentLoop(provider, s.model, tools()).run(systemPrompt(userText), history, ctx,
                onEvent = { e ->
                    when (e) {
                        is AgentEvent.Thinking -> _status.value = _status.value.copy(working = true, label = if (e.step == 1) "Thinking" else "Working")
                        is AgentEvent.Said -> say(e.text)
                        is AgentEvent.ToolStarted -> _status.value = _status.value.copy(label = labelFor(e.call.name))
                        is AgentEvent.ToolFinished -> {}
                    }
                },
                onAppend = { m -> db.chat().insertTurn(TurnRow(thread = "main", json = m.toJson().toString())); mainLine.snapshot = history.toList() },
                incoming = { generateSequence { mainLine.notes.poll() }.toList() })
        } catch (e: CancellationException) {
            // A correction closed this line: its work is kept, and the branch that replaced it carries on. That isn't "Stopped".
            if (mainLine.supersededBy == null) db.chat().insert(ChatItem(kind = "notice", text = "Stopped."))
        } catch (e: Exception) {
            db.chat().insert(ChatItem(kind = "notice", text = friendlyError(e), meta = JSONObject().put("error", true).toString()))
        } finally {
            lineEnded(mainLine)
            phone.workFinished()
        }
    }

    /** The main agent said something: into the chat, quoting what it answers. "HOLD" means it chose to say nothing yet. */
    private suspend fun say(text: String, turn: Turn = mainTurn) {
        if (isHold(text)) return
        db.chat().insert(ChatItem(kind = "agent", text = text, meta = JSONObject().apply { replyMeta(turn, this) }.toString()))
    }

    /** What a reply carries so the chat can quote what it answers (and mark it as a branch's). */
    private fun replyMeta(turn: Turn, m: JSONObject) {
        turn.origin?.let { m.put("replyTo", it); if (turn.late) m.put("late", true) }
        turn.branchItem?.let { m.put("branch", it) }
    }

    /** What one run of the harness came to. */
    private class SubResult { var newSession: String? = null; var ok = false; var retry = false; var error: String? = null }

    /** Stream a harness turn into the chat, for the main line or a branch (`turn` says whose replies and steps these are). */
    private suspend fun consumeSub(flow: kotlinx.coroutines.flow.Flow<JSONObject>, engine: SubscriptionEngine, turn: Turn, resumed: Boolean): SubResult {
        val r = SubResult()
        val tag = { m: JSONObject -> turn.branchItem?.let { m.put("branch", it) }; m }
        flow.collect { e ->
            when (e.optString("type")) {
                "session" -> { r.newSession = e.optString("id"); turn.line.session = r.newSession }
                "text" -> e.optString("text").let { t ->
                    when {
                        t.contains("API Error: 401") || t.startsWith("Failed to authenticate") -> {}
                        // The harness's own error, not the agent talking: one plain line, not raw JSON.
                        t.trimStart().startsWith("API Error:") -> { r.error = subError(t); db.chat().insert(ChatItem(kind = "notice", text = subError(t), meta = tag(JSONObject().put("error", true)).toString())) }
                        else -> say(t, turn)
                    }
                }
                "tool" -> {
                    val name = e.optString("name")
                    _status.value = _status.value.copy(label = labelFor(name))
                    // Our own phone tools log themselves; show everything else (web search, the
                    // user's Claude connectors, Codex commands) as a live step too.
                    if (phoneToolNames.none { it == name }) db.chat().insert(ChatItem(kind = "activity", text = describeExternalTool(name, e.optString("detail")),
                        meta = tag(JSONObject().put("tool", toolGroup(name))).toString()))
                }
                "done" -> r.ok = e.optBoolean("ok", true)
                "error" -> {
                    val m = e.optString("message")
                    when {
                        // The saved conversation is gone (new install, cleared data): start a fresh one.
                        m.contains("No conversation found", true) && resumed -> { r.retry = true }
                        m.contains("Invalid bearer token", true) || m.contains("authentication_error", true) || m.contains("401") -> {
                            engine.forgetBadSignIn(); settings.setExtra("claude_session", null)
                            r.error = "sign-in rejected"
                            db.chat().insert(ChatItem(kind = "notice", text = "Your ${settings.state.value.subKind.label} sign-in didn't work. Open Settings, Subscription, and sign in again.", meta = tag(JSONObject().put("error", true)).toString()))
                        }
                        // The same failure also came as text a moment ago: say it once.
                        m.contains("API Error:") -> {}
                        m.isNotBlank() && !m.matches(Regex("exit \\d+")) -> { r.error = m.take(300); db.chat().insert(ChatItem(kind = "notice", text = "${settings.state.value.subKind.label}: " + m.take(300), meta = tag(JSONObject().put("error", true)).toString())) }
                    }
                }
            }
        }
        return r
    }

    private suspend fun runSubscriptionTurn(userText: String, engine: SubscriptionEngine, images: List<String> = emptyList()) {
        _status.value = _status.value.copy(working = true, label = "Thinking"); phone.workStarted()
        mainLine.job = kotlin.coroutines.coroutineContext[Job]; mainLine.supersededBy = null
        try {
            var resume = settings.extra("claude_session")
            for (attempt in 1..2) {
                mainLine.session = resume
                // What finished branches did, written in front of this prompt (a harness session can't be edited from outside).
                val merged = pendingPeek()
                val prompt = if (merged.isEmpty()) userText else merged.joinToString("\n\n") + "\n\n" + userText
                val r = consumeSub(engine.turn(prompt, systemPrompt(userText), resume, mcp.url, settings.localToken(), images, role = "main"), engine, mainTurn, resumed = resume != null)
                // Only remember a conversation that actually worked, so a failed first turn can't poison the next.
                if (r.ok && r.newSession != null) settings.setExtra("claude_session", r.newSession)
                if (r.ok) pendingDrop(merged.size)
                if (r.retry && attempt == 1) { settings.setExtra("claude_session", null); resume = null; continue }
                break
            }
        } catch (e: CancellationException) {
            if (mainLine.supersededBy == null) db.chat().insert(ChatItem(kind = "notice", text = "Stopped."))
        } catch (e: Exception) {
            db.chat().insert(ChatItem(kind = "notice", text = friendlyError(e), meta = JSONObject().put("error", true).toString()))
        } finally { lineEnded(mainLine); phone.workFinished() }
    }

    /** "API Error: 400 {...claude_code_version_too_old...}" -> a line the user can act on. */
    private fun subError(raw: String): String {
        val msg = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1)
        val who = settings.state.value.subKind.label
        return when {
            raw.contains("claude_code_version_too_old") -> "This model needs a newer Claude Code than this version of the app has. Update the app, or pick another model."
            raw.contains("rate_limit") || raw.contains(" 429") -> "$who says you've hit your plan's limit for now. Try again later or switch models."
            raw.contains("overloaded") || raw.contains(" 529") -> "$who is overloaded right now. Try again in a minute."
            else -> "$who: " + (msg ?: raw.removePrefix("API Error:").trim()).take(240)
        }
    }

    private val phoneToolNames by lazy { tools().map { it.spec.name }.toSet() }
    private val workerToolNames get() = tools(forHelper = true).map { it.spec.name }.toSet()

    // ---- helpers & routines -------------------------------------------------------------------

    /** The models helpers can run on, each with what it's good for. Never empty: falls back to the one helper model. */
    fun helperRoster(): List<com.past9.phoneaos.data.HelperModel> {
        val st = settings.state.value
        if (st.helperRoster.isNotEmpty()) return st.helperRoster
        val sub = st.mode == PowerMode.SUBSCRIPTION
        val id = if (sub) st.subHelperModel else st.helperModel.ifBlank { st.model }
        return listOf(com.past9.phoneaos.data.HelperModel(id, id.ifBlank { "Same as you" }.substringAfterLast('/')))
    }

    /** Set a helper going in the background and return its id at once. */
    private suspend fun startHelper(task: String, label: String, wanted: String? = null, profileName: String? = null, batch: String? = null, pinnedName: String? = null, turn: Turn = mainTurn): Long {
        // A pinned agent: the same identity and context, handed a new job.
        val pin = pinnedName?.trim()?.takeIf { it.isNotEmpty() }?.let { w -> pins.find(w) ?: error("No pinned agent called \"$w\". Pinned: ${pins.agents.value.joinToString { it.name }.ifBlank { "none" }}.") }
        if (pin != null && helpers.containsKey(pin.lastHelper)) error("${pin.name} is already on a job (#${pin.lastHelper}). Wait for it, or start a fresh helper.")
        // Every helper works inside a profile; with none named, it gets General (none of the user's accounts). A pinned agent keeps its own.
        val profile = profileName?.trim()?.takeIf { it.isNotEmpty() }?.let { w -> profiles.find(w) ?: error("No profile called \"$w\". The user's profiles: ${profiles.profiles.value.joinToString { it.name }.ifBlank { "none yet" }}. Pick one, or make one with profile_create.") }
            ?: pin?.profileId?.let { profiles.find(it) }
            ?: profiles.general()
        val st = settings.state.value
        val sub = subscription?.takeIf { st.mode == PowerMode.SUBSCRIPTION && it.ready }
        val provider = if (sub == null) providerFactory(settings) ?: error("No AI is set up for helpers yet") else null
        val roster = helperRoster()
        // The model the main agent asked for, matched by id or name; otherwise the first on the roster.
        val pick = wanted?.trim()?.takeIf { it.isNotEmpty() }?.let { w -> roster.firstOrNull { it.id.equals(w, true) } ?: roster.firstOrNull { it.name.equals(w, true) } }
        // A pinned agent stays on the model it was pinned on unless the main agent names another.
        val model = pick?.id?.ifBlank { null } ?: pin?.model?.ifBlank { null } ?: (pick ?: roster.first()).id.ifBlank { if (sub != null) st.subModel else st.model }
        val itemId = db.chat().insert(ChatItem(kind = "helper", text = task, meta = JSONObject().put("label", label).put("state", "working")
            .put("startedAt", System.currentTimeMillis()).put("model", model).apply {
                put("profile", profile.name).put("profileColor", profile.color); turn.origin?.let { put("origin", it) }; turn.branchItem?.let { put("branch", it) }; batch?.let { put("batch", it) }
                pin?.let { put("pinned", it.id).put("pinnedName", it.name) }
            }.toString()))
        helperProfile[itemId] = profile.id
        turn.branchItem?.let { helperBranch[itemId] = it }
        if (profile.browser.isNotBlank()) com.past9.phoneaos.tools.BrowserTool.lastProfile["helper-$itemId"] = profile.browser
        batch?.let { helperBatch[itemId] = it }
        if (pin == null) { launchHelper(itemId, label, mutableListOf(Msg.user(task)), task, null, provider, sub, model); return itemId }
        val job = "[A new job from the main agent. You are ${pin.name}; your earlier work is above, so use what you learned.]\n$task"
        val history = pins.loadHistory(pin.id).also { com.past9.phoneaos.data.PinnedAgentStore.appendUser(it, job) }
        pins.put(pin.copy(lastHelper = itemId, lastUsedAt = System.currentTimeMillis()))
        pin.session?.let { helperSession[itemId] = it }
        launchHelper(itemId, label, history, job, pin.session, provider, sub, model, brief = task)
        return itemId
    }

    /** @param brief this job's brief (a pinned agent's history starts with an older one). */
    private fun launchHelper(itemId: Long, label: String, history: MutableList<Msg>, prompt: String, resume: String?, provider: LlmProvider?, sub: SubscriptionEngine?, model: String,
                             brief: String = history.first().text) {
        val ctx = ChatContext("helper-$itemId", interactive = true, helperItem = itemId, display = label)
        helperCtx[itemId] = ctx
        val j = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { runHelper(itemId, label, history, prompt, resume, ctx, provider, sub, model, brief) }
        helpers[itemId] = HelperRun(itemId, label, brief, System.currentTimeMillis(), j)
        refreshHelpers(); j.start()
    }

    /**
     * Tell a helper more while it works. By default it reads the message before its next step and keeps going;
     * restart stops it and starts it again on the same conversation with the new information (for when it's
     * heading the wrong way). A helper that already finished is simply sent back with the message.
     */
    private suspend fun steerHelper(id: Long, message: String, restart: Boolean): String {
        val run = helpers[id] ?: return pushHelper(id, message)
        val label = run.label
        val subPath = settings.state.value.mode == PowerMode.SUBSCRIPTION && subscription?.ready == true
        // A subscription helper runs inside its own harness and can't read messages mid-turn: restart it on its session.
        if (restart || subPath) {
            run.job.cancel(); run.job.join()
            val out = pushHelper(id, message)
            return if (out.contains("back on it")) "Stopped helper #$id and started it again with your message. Its result will arrive as a message." else out
        }
        steerInbox.getOrPut(id) { java.util.concurrent.ConcurrentLinkedQueue() } += message
        db.chat().insert(ChatItem(kind = "activity", text = "Told it: ${message.take(140)}", meta = JSONObject().put("tool", "agent").put("by", label).put("helper", id).toString()))
        return "Helper #$id gets your message before its next step and keeps going."
    }

    /**
     * Send a finished helper back to work with a nudge ("try X, you can do this"). It carries on in its own
     * conversation, so it keeps everything it already found.
     */
    private suspend fun pushHelper(id: Long, message: String): String {
        if (helpers.containsKey(id)) return "Helper #$id is still working. Wait for its result."
        val row = db.chat().get(id)?.takeIf { it.kind == "helper" } ?: return "No helper #$id."
        val meta = JSONObject(row.meta)
        val st = settings.state.value
        val sub = subscription?.takeIf { st.mode == PowerMode.SUBSCRIPTION && it.ready }
        val provider = if (sub == null) providerFactory(settings) ?: return "No AI is set up for helpers." else null
        val pin = pins.agents.value.firstOrNull { it.lastHelper == id }
        val history = helperHistory[id] ?: pin?.let { pins.loadHistory(it.id).takeIf { h -> h.isNotEmpty() } } ?: mutableListOf(Msg.user(row.text))
        history += Msg.user(message)
        setHelperMeta(id) { it.put("state", "working").put("pushes", it.optInt("pushes") + 1).put("startedAt", System.currentTimeMillis()).remove("endedAt") }
        db.chat().insert(ChatItem(kind = "activity", text = "Sent back: ${message.take(140)}", meta = JSONObject().put("tool", "agent").put("by", meta.optString("label")).put("helper", id).toString()))
        launchHelper(id, meta.optString("label").ifBlank { "Helper" }, history, message, helperSession[id], provider, sub, meta.optString("model"), brief = row.text)
        return "Helper #$id is back on it. Its new result will arrive as a message."
    }

    private suspend fun setHelperMeta(itemId: Long, edit: (JSONObject) -> Unit) {
        db.chat().get(itemId)?.let { row -> db.chat().update(row.copy(meta = JSONObject(row.meta).also(edit).toString())) }
    }

    /** Words a helper uses when it gives up. Usually it can do it; it needs sending back. */
    // ---- the escalation gate ----------------------------------------------------------------

    /** How often the main agent has sent this helper's requests back. */
    private suspend fun gatePushes(helperId: Long): Int = runCatching { JSONObject(db.chat().get(helperId)?.meta ?: "{}").optInt("gatePushes") }.getOrDefault(0)

    /**
     * A helper asked something of the user: the main agent hears it first and decides (EscalationGate).
     * Only a forward reaches the user, as a card carrying the main agent's reason.
     */
    private suspend fun gatedAsk(helperId: Long, label: String, question: String, options: List<String>, ctx: ChatContext): String? {
        val kind = EscalationGate.kind(question, options)
        val e = Escalation(helperId, label, kind, question, options)
        // One request at a time per helper: an earlier one still open is sent back rather than left hanging.
        escalations[helperId]?.result?.complete(EscalationGate.Triage.PushBack("Ask one thing at a time; this was replaced by your newer request."))
        escalations[helperId] = e
        ctx.activity("Asked ${agentName()}: ${EscalationGate.plain(question).take(140)}", JSONObject().put("tool", "agent"))
        val origin = runCatching { JSONObject(db.chat().get(helperId)?.meta ?: "{}").optLong("origin").takeIf { it > 0 } }.getOrNull()
        deliver(EscalationGate.note(helperId, label, kind, question, options, gatePushes(helperId)), origin, escalation = helperId)
        val t = try { e.result.await() } finally { escalations.remove(helperId, e) }
        return when (t) {
            is EscalationGate.Triage.Forward -> ctx.askUser(question, options, t.why.ifBlank { null })
            is EscalationGate.Triage.Answer -> {
                redirects.getOrPut(helperId) { java.util.concurrent.ConcurrentLinkedQueue() } += "(That answer came from ${agentName()}, from what it knows, not from the user.)"
                t.text
            }
            is EscalationGate.Triage.PushBack -> { redirects.getOrPut(helperId) { java.util.concurrent.ConcurrentLinkedQueue() } += PUSHED + t.message; null }
        }
    }

    private suspend fun triage(helper: Long?, decision: String, message: String, needs: String): String {
        val waiting = escalations.values.filter { !it.result.isCompleted }
        val e = (if (helper != null) escalations[helper] else waiting.singleOrNull())
            ?: return when {
                waiting.isEmpty() -> "No helper is waiting on a request${helper?.let { " (#$it isn't)" } ?: ""}."
                else -> "Several helpers are waiting; name which with helper: ${waiting.joinToString { "#${it.helperId} ${it.label}" }}."
            }
        if (e.result.isCompleted) return "Helper #${e.helperId}'s request was already decided."
        val pushes = gatePushes(e.helperId)
        EscalationGate.check(e.kind, decision, message, needs, pushes)?.let { return it }
        val t = when (decision) {
            "forward" -> EscalationGate.Triage.Forward(message, needs.ifBlank { "other" })
            "answer" -> EscalationGate.Triage.Answer(message)
            else -> EscalationGate.Triage.PushBack(message)
        }
        if (!e.result.complete(t)) return "Helper #${e.helperId}'s request was already decided."
        if (t is EscalationGate.Triage.PushBack) setHelperMeta(e.helperId) { it.put("gatePushes", it.optInt("gatePushes") + 1) }
        val logged = when (t) { is EscalationGate.Triage.Forward -> "forwarded"; is EscalationGate.Triage.Answer -> "self_answered"; else -> "pushed_back" }
        escalationLog.add(EscalationLog.entry(e.helperId, e.label, e.kind, e.question, logged, message, if (t is EscalationGate.Triage.Forward) t.needs else "", pushes))
        val name = agentName()
        db.chat().insert(ChatItem(kind = "activity", text = when (t) {
            is EscalationGate.Triage.Forward -> "$name passed it to you: ${message.take(140)}"
            is EscalationGate.Triage.Answer -> "$name answered: ${message.take(140)}"
            else -> "$name sent it back: ${message.take(140)}"
        }, meta = JSONObject().put("tool", "agent").put("by", e.label).put("helper", e.helperId).toString()))
        return when (t) {
            is EscalationGate.Triage.Forward -> "Forwarded to the user with your reason. The helper carries on once they answer."
            is EscalationGate.Triage.Answer -> "Helper #${e.helperId} has your answer and carries on."
            else -> "Sent back to helper #${e.helperId} (pushed back ${pushes + 1} time${if (pushes == 0) "" else "s"} now). It carries on."
        }
    }

    // ---- pinned agents ------------------------------------------------------------------------

    private suspend fun pinAgent(helper: Long?, name: String, summary: String, goodFor: List<String>, why: String): String {
        if (name.isBlank()) return "Give it a short name."
        val existing = pins.find(name)
        if (helper == null) {
            existing ?: return "No pinned agent called \"$name\". To pin a new one, give the finished helper's id."
            pins.put(existing.copy(summary = summary.ifBlank { existing.summary }, goodFor = goodFor.ifEmpty { existing.goodFor }, why = why.ifBlank { existing.why }))
            return "Updated what ${existing.name} knows."
        }
        val row = db.chat().get(helper)?.takeIf { it.kind == "helper" } ?: return "No helper #$helper."
        val meta = JSONObject(row.meta)
        val state = meta.optString("state")
        if (helpers.containsKey(helper) || state == "working") return "Helper #$helper is still working. Pin it once it has finished."
        if (state != "done") return "Only a helper that got its job done can be pinned (#$helper ${if (state == "stopped") "was stopped" else "failed"})."
        if (summary.length < 40 || why.isBlank()) return "Not pinned. Say what it knows and is good at (summary: the route it found, the traps, what it can do again) and why it earned a pin. Pin only helpers that cracked something non-obvious or own an ongoing area."
        val already = pins.forHelper(helper)
        if (existing != null && existing.id != already?.id) return "There's already a pinned agent called ${existing.name}. Pick another name, or agent_unpin it first."
        if (already == null && pins.agents.value.size >= com.past9.phoneaos.data.PinnedAgentStore.MAX)
            return "You already keep ${com.past9.phoneaos.data.PinnedAgentStore.MAX} pinned agents. agent_unpin the stalest first: ${pins.agents.value.minBy { it.lastUsedAt }.name} was used least recently."
        val id = already?.id ?: com.past9.phoneaos.data.PinnedAgentStore.newId()
        // Its conversation: what it did in this app session, or (after a restart) its brief and its result.
        val history = helperHistory[helper]?.toList() ?: already?.let { pins.loadHistory(it.id).takeIf { h -> h.isNotEmpty() } }
            ?: listOf(Msg.user(row.text), Msg(Role.ASSISTANT, listOf(Block.Text(meta.optString("result").ifBlank { "(done)" }))))
        val label = meta.optString("label").ifBlank { name }
        pins.put(com.past9.phoneaos.data.PinnedAgent(id, name, summary, goodFor, why, model = meta.optString("model"),
            profileId = helperProfile[helper] ?: profiles.find(meta.optString("profile"))?.id.orEmpty(), fromHelper = already?.fromHelper ?: helper, lastHelper = helper,
            session = helperSession[helper] ?: already?.session, pinnedAt = already?.pinnedAt ?: System.currentTimeMillis(),
            jobs = already?.jobs ?: listOf(java.time.Instant.ofEpochMilli(meta.optLong("startedAt", row.createdAt)).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM")) + ": " + label)))
        pins.saveHistory(id, history)
        setHelperMeta(helper) { it.put("pinned", id).put("pinnedName", name) }
        return "Pinned \"$name\" (from #$helper). When a job fits, send it there: delegate with agents [\"$name\"]. It keeps its whole context."
    }

    private fun unpinAgent(name: String): String {
        val a = pins.find(name) ?: return "No pinned agent called \"$name\"."
        if (helpers.containsKey(a.lastHelper)) return "${a.name} is on a job (#${a.lastHelper}). Unpin it once it's done."
        pins.remove(a.id); return "Let ${a.name} go."
    }

    private fun pinnedReport(): String {
        pins.prune()
        val all = pins.agents.value
        if (all.isEmpty()) return "No pinned agents yet. Pin a helper that cracked something non-obvious with agent_pin."
        return all.sortedByDescending { it.lastUsedAt }.joinToString("\n\n") { a ->
            "${a.name}${if (helpers.containsKey(a.lastHelper)) " [busy, #${a.lastHelper}]" else ""}\nKnows: ${a.summary}" +
                (if (a.goodFor.isNotEmpty()) "\nGood for: ${a.goodFor.joinToString(", ")}" else "") +
                (if (a.why.isNotBlank()) "\nPinned because: ${a.why}" else "") +
                "\nJobs (${a.jobs.size}): ${a.jobs.takeLast(5).joinToString("; ")}"
        }
    }

    /** A helper finishing by giving the user a chore ("please set the birthday yourself"). */
    private val handsBack = Regex("(?i)\\b(please|could you|can you|you('ll| will)? need to|you have to|you must)\\b[^.\\n]{0,120}\\b(yourself|manually|by hand)\\b|\\bthe user (needs|will need|has|must) to\\b")

    private val gaveUp = Regex("(?i)(^\\s*(unable|couldn't|could not|can't|cannot|failed)\\b)|\\b(I|we)\\s+(can't|cannot|can not|couldn't|could not|was unable|were unable|am unable|wasn't able|weren't able|am not able|don't have access|do not have access|have no access|wasn't allowed|got blocked|was blocked)\\b")

    private suspend fun runHelper(itemId: Long, label: String, history: MutableList<Msg>, prompt: String, resume: String?, ctx: ChatContext,
                                  provider: LlmProvider?, sub: SubscriptionEngine?, model: String, brief: String = history.first().text) {
        phone.workStarted()
        var state = "failed"; var result = ""
        try {
            val sys = systemPrompt(brief, role = PromptRole.HELPER, profile = helperProfile[itemId]?.let { profiles.find(it) }, pinnedAgent = pins.agents.value.firstOrNull { it.lastHelper == itemId })
            result = if (sub != null) runSubHelper(itemId, prompt, sys, resume, sub, model) else
                AgentLoop(provider!!, model, tools(forHelper = true), maxSteps = 120).run(sys, history, ctx, onEvent = { e ->
                    if (e is AgentEvent.ToolStarted) setHelperMeta(itemId) { it.put("now", labelFor(e.call.name)) }
                }, incoming = { steerInbox[itemId]?.let { q -> generateSequence { q.poll() }.toList() }.orEmpty() })
            state = "done"
        } catch (e: CancellationException) {
            state = "stopped"; result = "Stopped before it finished."
        } catch (e: Exception) {
            result = friendlyError(e)
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                if (sub == null) helperHistory[itemId] = history
                escalations.remove(itemId)?.result?.cancel(); redirects.remove(itemId)
                // A pinned agent keeps what it just did (its conversation, or its harness session) for its next job.
                pins.agents.value.firstOrNull { it.lastHelper == itemId }?.let { pin ->
                    runCatching { if (sub == null) pins.saveHistory(pin.id, history) }
                    val job = java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM")) + ": " + label
                    pins.put(pin.copy(session = helperSession[itemId] ?: pin.session, lastUsedAt = System.currentTimeMillis(),
                        jobs = (if (pin.jobs.lastOrNull() == job) pin.jobs else pin.jobs + job).takeLast(12)))
                }
                var pushes = 0
                setHelperMeta(itemId) { pushes = it.optInt("pushes"); it.put("state", state).put("result", result.take(6000)).put("endedAt", System.currentTimeMillis()).remove("now") }
                helpers.remove(itemId); helperCtx.remove(itemId); refreshHelpers()
                // A helper a branch started reports back to THAT branch, not the main line.
                val branchId = helperBranch[itemId]
                // Anything sent in that it never got to read goes into its conversation for a push later.
                steerInbox.remove(itemId)?.let { q -> generateSequence { q.poll() }.toList() }?.takeIf { it.isNotEmpty() && sub == null }
                    ?.let { history += Msg.user(it.joinToString("\n\n") { m -> "[The main agent, while you worked] $m" }) }
                pending.keys.filter { id -> runCatching { JSONObject(db.chat().get(id)?.meta ?: "{}").optLong("helper") == itemId }.getOrDefault(false) }.forEach { pending.remove(it)?.cancel() }
                com.past9.phoneaos.browser.BrowserService.release("helper-$itemId")
                phone.workFinished()
                // The main agent hears back unless the user stopped it (then there is nothing to report).
                if (state == "stopped") helperBatch.remove(itemId)?.let { b -> if (helperBatch.values.none { it == b }) batchResults.remove(b)?.takeIf { it.isNotEmpty() }?.let { all ->
                    deliverFor(branchId, "[Helpers finished: the rest of the group was stopped. Give the user ONE packed answer from these.]\n\n" + all.joinToString("\n\n") { it.second }, null) } }
                if (state != "stopped") {
                    val chore = handsBack.containsMatchIn(result.take(3_000))
                    val nudge = if (chore && state == "done" && pushes < 2)
                        "\n\n(It's handing part of the job back to the user. Unless that part truly needs them (a captcha, a passkey or code on their device, their phone number, a payment, something sent in their name), send it back with helper_push and name exactly how to do it (click each option, type into the focused field, arrow keys). The user should never get a helper's chore.)"
                    else if ((state == "failed" || gaveUp.containsMatchIn(result.trim().split(Regex("(?<=[.!?])\\s+|\\n")).first().take(300))) && pushes < 2)
                        "\n\n(It says it couldn't do all of it${if (pushes > 0) ", after $pushes push${if (pushes > 1) "es" else ""}" else ""}. Helpers usually can. Unless it's truly impossible, send it back with helper_push: name a concrete next thing to try and tell it you believe it can do it. Push at least twice before you accept it or tell the user.)"
                    else ""
                    // Got there after being pushed: it cracked something, and might be worth keeping.
                    val keep = if (nudge.isEmpty() && state == "done" && pushes + gatePushes(itemId) > 0 && pins.agents.value.none { it.lastHelper == itemId })
                        "\n\n(It got there after being sent back. If what it cracked will come up again, agent_pin it.)" else ""
                    val note = "[Helper #$itemId \"$label\" ${if (state == "done") "finished" else "failed"}${if (pushes > 0) " (after $pushes push${if (pushes > 1) "es" else ""})" else ""}]\n${result.take(12_000).ifBlank { "(no result text)" }}$nudge$keep"
                    val origin = runCatching { JSONObject(db.chat().get(itemId)?.meta ?: "{}").optLong("origin").takeIf { it > 0 } }.getOrNull()
                    val batch = helperBatch.remove(itemId)
                    if (batch == null) deliverFor(branchId, note, origin)
                    else {
                        // Part of one answer: hold it until every helper in the group is done, then hand them over together.
                        batchResults.getOrPut(batch) { java.util.concurrent.ConcurrentLinkedQueue() }.add(itemId to note)
                        if (helperBatch.values.none { it == batch }) batchResults.remove(batch)?.let { all ->
                            deliverFor(branchId, "[Helpers finished: all ${all.size} you started together are done. Give the user ONE packed answer.]\n\n" + all.joinToString("\n\n") { it.second }, origin)
                        }
                    }
                }
                // Only now is the helper done with its branch (a result handed back already counts as a running turn there).
                helperBranch.remove(itemId); branchId?.let { id -> branches[id]?.let { trySettle(it) } }
            }
        }
    }

    /** A helper on the user's subscription: the same harness, our worker tools over MCP. */
    private suspend fun runSubHelper(itemId: Long, prompt: String, sys: String, resume: String?, engine: SubscriptionEngine, model: String): String {
        var last = ""; var error: String? = null; var ok = false
        engine.turn(prompt, sys, resume, mcp.url + "/h/$itemId", settings.localToken(), emptyList(), model.ifBlank { null }, role = "helper").collect { e ->
            when (e.optString("type")) {
                "session" -> e.optString("id").takeIf { it.isNotBlank() }?.let { helperSession[itemId] = it; setHelperMeta(itemId) { m -> m.put("session", it) } }
                "text" -> e.optString("text").takeIf { it.isNotBlank() }?.let { last = it }
                "tool" -> {
                    val name = e.optString("name")
                    setHelperMeta(itemId) { it.put("now", labelFor(name)) }
                    if (workerToolNames.none { it == name }) db.chat().insert(ChatItem(kind = "activity", text = describeExternalTool(name, e.optString("detail")),
                        meta = JSONObject().put("tool", toolGroup(name)).put("by", helpers[itemId]?.label ?: "helper").put("helper", itemId).toString()))
                }
                "done" -> ok = e.optBoolean("ok", true)
                "error" -> error = e.optString("message").takeIf { it.isNotBlank() && !it.matches(Regex("exit \\d+")) }
            }
        }
        if (!ok && last.isBlank()) throw IllegalStateException(error?.take(300) ?: "The helper stopped without an answer")
        return last
    }

    /** What every helper is doing (running ones first), for helpers_status. */
    private suspend fun helpersReport(): String {
        val all = db.chat().all().first()
        val hs = all.filter { it.kind == "helper" }.takeLast(10)
        if (hs.isEmpty()) return "No helpers have run yet."
        val nowMs = System.currentTimeMillis()
        return hs.sortedByDescending { JSONObject(it.meta).optString("state") == "working" }.joinToString("\n\n") { h ->
            val m = JSONObject(h.meta); val st = m.optString("state")
            val mins = ((m.optLong("endedAt").takeIf { it > 0 } ?: nowMs) - m.optLong("startedAt", h.createdAt)) / 60_000
            val steps = all.filter { it.kind == "activity" && JSONObject(it.meta).optLong("helper") == h.id }.takeLast(5).joinToString("; ") { it.text }
            "#${h.id} ${m.optString("label")} [$st, ${mins}m]\nBrief: ${h.text.take(240)}" +
                (if (st == "working") "\nNow: ${m.optString("now").ifBlank { "thinking" }}" + (if (steps.isNotBlank()) "\nRecent steps: $steps" else "")
                else "\nResult: ${m.optString("result").take(500)}")
        }
    }

    /** Run a routine with nobody watching. Posts its summary into the chat and as a notification. */
    suspend fun runBackground(name: String, prompt: String): String {
        val sub = subscription?.takeIf { settings.state.value.mode == PowerMode.SUBSCRIPTION && it.ready }
        val provider = if (sub == null) providerFactory(settings) ?: return "No AI provider configured" else null
        val ctx = ChatContext("routine", interactive = false)
        val history = mutableListOf(Msg.user("Routine \"$name\": $prompt"))
        phone.workStarted()
        return try {
            // A routine is work, not conversation: it runs with the helpers' tools.
            val sys = systemPrompt(prompt, role = PromptRole.ROUTINE)
            val out = if (sub != null) runSubRoutine(history.first().text, sys, sub, ctx)
                else AgentLoop(provider!!, settings.state.value.model, tools(forHelper = true)).run(sys, history, ctx)
            db.chat().insert(ChatItem(kind = "agent", text = out, meta = JSONObject().put("routine", name).toString()))
            phone.notify(name, out.lineSequence().firstOrNull { it.isNotBlank() }?.take(180) ?: "Done")
            out
        } catch (e: Exception) {
            val msg = friendlyError(e)
            db.chat().insert(ChatItem(kind = "notice", text = "Routine \"$name\" failed: $msg"))
            msg
        } finally { phone.workFinished() }
    }

    /** A routine on a subscription: the harness runs it as a worker, with the helpers' tools on the routine's own MCP endpoint. */
    private suspend fun runSubRoutine(prompt: String, sys: String, engine: SubscriptionEngine, ctx: ToolContext): String {
        val id = routineRuns.incrementAndGet()
        routineCtx[id] = ctx
        try {
            var last = ""; var error: String? = null; var ok = false
            engine.turn(prompt, sys, null, mcp.url + "/r/$id", settings.localToken(), role = "helper").collect { e ->
                when (e.optString("type")) {
                    "text" -> e.optString("text").takeIf { it.isNotBlank() }?.let { last = it }
                    "done" -> ok = e.optBoolean("ok", true)
                    "error" -> error = e.optString("message").takeIf { it.isNotBlank() && !it.matches(Regex("exit \\d+")) }
                }
            }
            if (!ok && last.isBlank()) throw IllegalStateException(error?.take(300) ?: "The routine stopped without an answer")
            return last
        } finally { routineCtx.remove(id) }
    }

    /**
     * A run nobody sees: no chat rows, no notifications. The night shift uses it. Returns the model's
     * final text, or null when no AI is set up.
     */
    suspend fun quietRun(prompt: String, withMemoryTools: Boolean): String? {
        val sys = systemPrompt(prompt, background = true)
        providerFactory(settings)?.let { provider ->
            val tools = if (withMemoryTools) tools().filter { it.spec.name.startsWith("memory_") || it.spec.name == "task_list" } else emptyList()
            return AgentLoop(provider, settings.state.value.model, tools, maxSteps = 40).run(sys, mutableListOf(Msg.user(prompt)), QuietContext())
        }
        val engine = subscription?.takeIf { settings.state.value.mode == PowerMode.SUBSCRIPTION && it.ready } ?: return null
        // The night run only writes notes: spend the action budget so the main route's action tools refuse.
        mainBudget.used = mainBudget.max
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

    fun clearConversation() = scope.launch {
        db.chat().clear(); db.chat().clearTurns("main"); db.chat().clearBranchTurns(); settings.setExtra("claude_session", null); settings.setExtra("conversation_id", null); settings.setExtra("branch_pending", null); unmerged.set(0)
    }

    // ---- tool context -----------------------------------------------------------------------

    private inner class ChatContext(override val agentLabel: String, private val interactive: Boolean, private val parentAsk: ToolContext? = null, private val helperItem: Long? = null,
                                    /** The branch this agent is, when it is one: its steps and questions are tagged so they fold into that branch. */
                                    private val branchItem: Long? = null,
                                    /** What the user sees as this agent's name ("Uber to airport"). */
                                    private val display: String = agentLabel) : ToolContext {
        override suspend fun activity(text: String, meta: JSONObject): Long =
            db.chat().insert(ChatItem(kind = "activity", text = text, meta = meta.put("by", display).apply { helperItem?.let { put("helper", it) }; branchItem?.let { put("branch", it) } }.toString()))
        override suspend fun updateActivity(id: Long, text: String, meta: JSONObject) {
            db.chat().get(id)?.let { old -> db.chat().update(old.copy(text = text, meta = JSONObject(old.meta).also { m -> meta.keys().forEach { k -> m.put(k, meta.get(k)) } }.toString())) }
        }
        override suspend fun ask(question: String, options: List<String>): String? {
            if (!interactive) return parentAsk?.ask(question, options)
            // Helpers never reach the user directly: the main agent triages first.
            if (helperItem != null) return gatedAsk(helperItem, display, question, options, this)
            return askUser(question, options, null)
        }

        /** Put the question to the user (a card and a notification) and wait. @param why the main agent's reason, on a forwarded request. */
        suspend fun askUser(question: String, options: List<String>, why: String?): String? {
            val id = db.chat().insert(ChatItem(kind = "question", text = question, meta = JSONObject().put("options", org.json.JSONArray(options))
                .apply { helperItem?.let { put("helper", it).put("by", display) }; why?.let { put("why", it).put("whyBy", agentName()) }; branchItem?.let { put("branch", it) } }.toString()))
            val d = CompletableDeferred<String>(); pending[id] = d
            if (helperItem == null) mainQuestions += id
            val prev = _status.value; if (helperItem == null) _status.value = prev.copy(label = "Waiting for you")
            val isApproval = question.startsWith("APPROVAL|")
            val connect = com.past9.phoneaos.tools.ConnectRequest.parse(question)
            when {
                connect != null -> phone.notify("Connect ${connect.name}?", connect.summary, id, options)
                isApproval -> phone.notify("Approve?", question.split("|").getOrElse(1) { "" }, id, options)
                why != null -> phone.notify("${agentName()} needs you", "$why\n$question", id, options)
                else -> phone.notify("Your agent has a question", question, id, options)
            }
            return try { d.await() } finally { mainQuestions.remove(id); if (helperItem == null) _status.value = prev.copy(helpers = helpers.size) }
        }
        override suspend fun notify(title: String, body: String) = phone.notify(title, body)
    }

    companion object {
        /** How many times a cut-off helper is picked up again before it is called failed. */
        const val MAX_RESUMES = 2
        /** Marks a redirect as "sent back" (vs. "answered for the user") on its way to the helper's tool result. */
        private const val PUSHED = "\u0000pushed:"

        /** The agent chose to say nothing yet (a result that's only a step toward a bigger answer). */
        fun isHold(text: String) = text.trim().trim('.', '*', '"', '`').equals("HOLD", true)

        fun defaultProvider(s: SettingsStore): LlmProvider? {
            val st = s.state.value
            if (st.mode != PowerMode.API_KEY) return null
            val key = s.apiKey(st.provider) ?: return null
            return providerFor(st.provider, key, st.baseUrl, st.allowHttp) { conversationId(s) }
        }

        /** One stable id per conversation (new when the chat is cleared), for providers that route by session. */
        fun conversationId(s: SettingsStore): String = s.extra("conversation_id") ?: java.util.UUID.randomUUID().toString().also { s.setExtra("conversation_id", it) }

        fun providerFor(p: Provider, key: String, baseUrl: String = p.baseUrl, allowHttp: Boolean = false, session: () -> String = { "check-" + java.util.UUID.randomUUID() }): LlmProvider = when (p) {
            // OpenCode Go/Zen route and cache by conversation: they require x-opencode-session.
            Provider.OPENCODE_GO, Provider.OPENCODE_ZEN -> OpenAiCompatProvider(p.name.lowercase(), key, baseUrl.ifBlank { p.baseUrl }, extraHeaders = { mapOf("x-opencode-session" to session()) }, allowHttp = allowHttp)
            Provider.ANTHROPIC -> AnthropicProvider(key, baseUrl.ifBlank { p.baseUrl }, allowHttp = allowHttp)
            // DeepSeek reasons by default and can spend a whole small budget thinking; tool use is snappier without it.
            Provider.DEEPSEEK -> OpenAiCompatProvider("deepseek", key, baseUrl.ifBlank { p.baseUrl }, extra = JSONObject().put("thinking", JSONObject().put("type", "disabled")), allowHttp = allowHttp)
            else -> OpenAiCompatProvider(p.name.lowercase(), key, baseUrl.ifBlank { p.baseUrl }, allowHttp = allowHttp)
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
 * Connected-app actions go through the account's rules (and a helper's profile), enforced in code
 * (not just asked of the model): reading and changing things are each Allowed, Ask me or Never.
 */
class GuardedAppsRunTool(private val inner: AppsRunTool, private val guard: com.past9.phoneaos.tools.AppGuard = com.past9.phoneaos.tools.AppGuard.DEFAULT) : Tool {
    override val spec = inner.spec
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val slug = input.optString("slug").uppercase()
        val args = input.optJSONObject("arguments")?.toString(2)?.take(1500) ?: "{}"
        val v = guard.check(ctx, listOf(slug), input.optString("account").takeIf { it.isNotBlank() }, args)
        v.refuse?.let { return it }
        v.account?.let { input.put("account", it) }
        return inner.run(input, ctx)
    }
}
