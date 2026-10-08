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
    /** A finished helper's own conversation (or its harness session), so it can be sent back to try again. */
    private val helperHistory = ConcurrentHashMap<Long, MutableList<Msg>>()
    private val helperSession = ConcurrentHashMap<Long, String>()
    /** Messages for helpers that are still working: each reads its queue before every step. */
    private val steerInbox = ConcurrentHashMap<Long, java.util.concurrent.ConcurrentLinkedQueue<String>>()

    /** The main agent may do a small job itself: at most this many action steps per turn, then it must delegate. */
    class StepBudget(val max: Int = 5) { @Volatile var used = 0 }
    private val mainBudget = StepBudget()

    /** An action tool in the main agent's hands: it counts against the per-turn step budget. */
    private class Budgeted(private val inner: Tool, private val budget: StepBudget) : Tool {
        override val spec = inner.spec
        override suspend fun run(input: JSONObject, ctx: ToolContext): String {
            if (budget.used >= budget.max) return "Step limit: you've used your ${budget.max} steps for this turn. This job is bigger than a quick one, so hand it to a helper with delegate and put what you've found so far in the brief."
            budget.used++
            return inner.run(input, ctx)
        }
    }
    private val _running = MutableStateFlow<List<HelperRun>>(emptyList())
    val running: StateFlow<List<HelperRun>> = _running
    /** Helper results waiting for the main agent to read them. */
    private val inbox = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, Long?>>()

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
    /** The user's message the current main turn is answering, so replies can quote it. */
    @Volatile private var turnOrigin: Long? = null
    /** This main turn answers helpers' results (so its replies come late and quote what they answer). */
    @Volatile private var turnLate = false

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
        // A fresh process has no live helpers: anything still marked working was cut off.
        scope.launch {
            db.chat().all().first().filter { it.kind == "helper" && JSONObject(it.meta).optString("state") == "working" }
                .forEach { db.chat().update(it.copy(meta = JSONObject(it.meta).put("state", "failed").put("result", "Interrupted when the app closed.").toString())) }
        }
    }

    /**
     * forHelper = false: the MAIN agent's tools. It understands the user and orchestrates: memory, goals and
     * tasks, asking, and handing work to helpers. It has nothing that acts on the world (no browser, web,
     * apps, code, files, machines or routine changes), so every action goes through a helper.
     * forHelper = true: the helpers' tools, everything that does the work.
     */
    fun tools(forHelper: Boolean = false): List<Tool> {
        if (forHelper) return workerTools()
        val own = listOf(
            NowTool(),
            MemorySearchTool(db.memory()), MemorySaveTool(db.memory()), MemoryGetTool(db.memory()), MemoryUpdateTool(db.memory()),
            TaskListTool(db.tasks()), TaskAddTool(db.tasks()), TaskUpdateTool(db.tasks()), GoalCreateTool(db.tasks()),
            NotificationsTool(db.notifications()) { settings.state.value.notifApps },
            ScheduleListTool(db.triggers()),
            AskUserTool(), NotifyTool(), VoiceNoteTool(context, settings, db.chat()),
            com.past9.phoneaos.tools.ReportTool(context) { item -> db.chat().insert(item.copy(meta = JSONObject(item.meta).apply { turnOrigin?.let { put("replyTo", it); if (turnLate) put("late", true) } }.toString())) },
            DelegateTool { task, label, model, profile, batch -> startHelper(task, label, model, profile, batch) },
            HelperSteerTool { id, message, restart -> steerHelper(id, message, restart) },
            HelperPushTool { id, message -> pushHelper(id, message) },
            HelpersStatusTool { helpersReport() },
            HelperStopTool { id -> stopHelper(id) },
            com.past9.phoneaos.tools.ProfileCreateTool(profiles) { slugs -> appAccounts(slugs) },
            com.past9.phoneaos.cards.CardListTool(cards), com.past9.phoneaos.cards.CardShowTool(cards, db.chat()), com.past9.phoneaos.cards.CardPinTool(cards),
        )
        // Small jobs (a page, a quick lookup) it may do itself, within the step budget. Routines and watchers stay with helpers.
        val mine = own.map { it.spec.name }.toSet() + setOf("report", "routine_create", "routine_delete", "watcher_save", "skill_save", "skill_find",
            "card_guide", "card_save", "card_update", "card_delete")
        return own + workerTools().filter { it.spec.name !in mine }.map { Budgeted(it, mainBudget) }
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
                             profile: com.past9.phoneaos.data.AgentProfile? = null): String {
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
                val roster = helperRoster()
                appendLine()
                appendLine("YOUR HELPERS (pick one per brief with `models` in delegate; the first is the default)")
                roster.forEach { h -> appendLine("- ${h.name}${if (h.id.isNotBlank() && h.id != h.name) " (id: ${h.id})" else ""}${if (h.tags.isNotEmpty()) ": good for ${h.tags.joinToString(", ")}" else ""}") }
                appendLine("- Match the job to the helper the user tagged for it. Fit the brief to the model: a frontier model (Opus, GPT-5, Gemini Pro and the like) needs the goal, all the context and what to bring back, then works out how by itself. A smaller or faster model can do the job too but needs a fuller brief: the steps in order, which site or tool to start with, what to check, what to do if something fails, and the exact shape of the answer.")
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
            }
            if (skills.isNotEmpty()) { appendLine("\nSKILLS EARLIER HELPERS LEFT (use them; they cost someone a struggle)"); skills.forEach { appendLine("## ${it.title}\n${it.body}\n") } }
            if (!main) appendLine("\n- Before anything fiddly, skill_find. If you struggled with something and then got it done, skill_save exactly what worked (steps, traps, how to check) so the next helper doesn't have to fight it.")
            if (role == PromptRole.HELPER && profile != null) {
                appendLine("\nYOUR PROFILE: ${profile.name}. This job belongs to that part of the user's life. Use ONLY these connected accounts: ${profile.accounts.joinToString { "${com.past9.phoneaos.tools.AppCatalog.name(it.slug)} ${it.label} (account ${it.id})" }.ifBlank { "none" }}. Any other account is off limits; if the job needs one that isn't here, say so in your result.")
                if (profile.browser.isNotBlank()) appendLine("Your browser is signed in as the \"${profile.browser}\" browser profile; use that one.")
            }
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
        launchTurn {
            val origin = if (fromCall != null) { db.chat().insert(ChatItem(kind = "activity", text = "On it: $fromCall", meta = JSONObject().put("tool", "call").toString())); null }
            else db.chat().insert(ChatItem(kind = "user", text = clean, meta = JSONObject().put("images", org.json.JSONArray(images.filter { !it.contains("/pdf-") }))
                .put("files", org.json.JSONArray(files)).put("voiceReply", voiceReply).toString()))
            val forModel = if (voiceReply) "$clean\n\n(The user is on the move: answer with a voice_note, then at most one short line of text. Don't repeat the voice note as text.)" else clean
            turnLock.withLock { runTurn(forModel, images, replyTo = origin) }
        }
    }

    /** A main-agent turn on the app's scope, tracked so stop() and awaitIdle() can reach it. */
    private fun launchTurn(block: suspend CoroutineScope.() -> Unit): Job {
        val j = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY, block = block)
        turnJobs += j; j.invokeOnCompletion { turnJobs.remove(j) }
        j.start(); return j
    }

    /** A helper's result goes back to the main agent as a new message (batched if several land together). */
    private fun deliver(note: String, origin: Long? = null) {
        inbox += note to origin
        launchTurn {
            turnLock.withLock {
                val batch = generateSequence { inbox.poll() }.toList()
                // The reply quotes the message that asked for this work (the newest, if several landed together).
                if (batch.isNotEmpty()) runTurn(batch.joinToString("\n\n") { it.first }, fromUser = false, replyTo = batch.mapNotNull { it.second }.maxOrNull())
            }
        }
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
        turnOrigin = replyTo; turnLate = !fromUser
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
                        is AgentEvent.Said -> say(e.text)
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
            _status.value = AgentStatus(helpers = helpers.size)
            phone.workFinished()
        }
    }

    /** The main agent said something: into the chat, quoting what it answers. "HOLD" means it chose to say nothing yet. */
    private suspend fun say(text: String) {
        if (isHold(text)) return
        db.chat().insert(ChatItem(kind = "agent", text = text, meta = JSONObject().apply { turnOrigin?.let { put("replyTo", it); if (turnLate) put("late", true) } }.toString()))
    }

    private suspend fun runSubscriptionTurn(userText: String, engine: SubscriptionEngine, images: List<String> = emptyList()) {
        _status.value = AgentStatus(true, "Thinking"); phone.workStarted()
        try {
            var resume = settings.extra("claude_session")
            for (attempt in 1..2) {
                var newSession: String? = null; var ok = false; var retry = false
                engine.turn(userText, systemPrompt(userText), resume, mcp.url, settings.localToken(), images, role = "main").collect { e ->
                    when (e.optString("type")) {
                        "session" -> newSession = e.optString("id")
                        "text" -> e.optString("text").let { t ->
                            when {
                                t.contains("API Error: 401") || t.startsWith("Failed to authenticate") -> {}
                                // The harness's own error, not the agent talking: one plain line, not raw JSON.
                                t.trimStart().startsWith("API Error:") -> db.chat().insert(ChatItem(kind = "notice", text = subError(t), meta = JSONObject().put("error", true).toString()))
                                else -> say(t)
                            }
                        }
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
                                // The same failure also came as text a moment ago: say it once.
                                m.contains("API Error:") -> {}
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
        } finally { _status.value = AgentStatus(helpers = helpers.size); phone.workFinished() }
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
    private suspend fun startHelper(task: String, label: String, wanted: String? = null, profileName: String? = null, batch: String? = null): Long {
        // Every helper works inside a profile; with none named, it gets General (none of the user's accounts).
        val profile = profileName?.trim()?.takeIf { it.isNotEmpty() }?.let { w -> profiles.find(w) ?: error("No profile called \"$w\". The user's profiles: ${profiles.profiles.value.joinToString { it.name }.ifBlank { "none yet" }}. Pick one, or make one with profile_create.") }
            ?: profiles.general()
        val st = settings.state.value
        val sub = subscription?.takeIf { st.mode == PowerMode.SUBSCRIPTION && it.ready }
        val provider = if (sub == null) providerFactory(settings) ?: error("No AI is set up for helpers yet") else null
        val roster = helperRoster()
        // The model the main agent asked for, matched by id or name; otherwise the first on the roster.
        val pick = wanted?.trim()?.takeIf { it.isNotEmpty() }?.let { w -> roster.firstOrNull { it.id.equals(w, true) } ?: roster.firstOrNull { it.name.equals(w, true) } } ?: roster.first()
        val model = pick.id.ifBlank { if (sub != null) st.subModel else st.model }
        val itemId = db.chat().insert(ChatItem(kind = "helper", text = task, meta = JSONObject().put("label", label).put("state", "working")
            .put("startedAt", System.currentTimeMillis()).put("model", model).apply {
                put("profile", profile.name).put("profileColor", profile.color); turnOrigin?.let { put("origin", it) }; batch?.let { put("batch", it) }
            }.toString()))
        helperProfile[itemId] = profile.id
        if (profile.browser.isNotBlank()) com.past9.phoneaos.tools.BrowserTool.lastProfile["helper-$itemId"] = profile.browser
        batch?.let { helperBatch[itemId] = it }
        launchHelper(itemId, label, mutableListOf(Msg.user(task)), task, null, provider, sub, model)
        return itemId
    }

    private fun launchHelper(itemId: Long, label: String, history: MutableList<Msg>, prompt: String, resume: String?, provider: LlmProvider?, sub: SubscriptionEngine?, model: String) {
        val ctx = ChatContext("helper-$itemId", interactive = true, helperItem = itemId, display = label)
        helperCtx[itemId] = ctx
        val j = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { runHelper(itemId, label, history, prompt, resume, ctx, provider, sub, model) }
        helpers[itemId] = HelperRun(itemId, label, history.first().text, System.currentTimeMillis(), j)
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
        val history = helperHistory[id] ?: mutableListOf(Msg.user(row.text))
        history += Msg.user(message)
        setHelperMeta(id) { it.put("state", "working").put("pushes", it.optInt("pushes") + 1).put("startedAt", System.currentTimeMillis()).remove("endedAt") }
        db.chat().insert(ChatItem(kind = "activity", text = "Sent back: ${message.take(140)}", meta = JSONObject().put("tool", "agent").put("by", meta.optString("label")).put("helper", id).toString()))
        launchHelper(id, meta.optString("label").ifBlank { "Helper" }, history, message, helperSession[id], provider, sub, meta.optString("model"))
        return "Helper #$id is back on it. Its new result will arrive as a message."
    }

    private suspend fun setHelperMeta(itemId: Long, edit: (JSONObject) -> Unit) {
        db.chat().get(itemId)?.let { row -> db.chat().update(row.copy(meta = JSONObject(row.meta).also(edit).toString())) }
    }

    /** Words a helper uses when it gives up. Usually it can do it; it needs sending back. */
    private val gaveUp = Regex("(?i)(^\\s*(unable|couldn't|could not|can't|cannot|failed)\\b)|\\b(I|we)\\s+(can't|cannot|can not|couldn't|could not|was unable|were unable|am unable|wasn't able|weren't able|am not able|don't have access|do not have access|have no access|wasn't allowed|got blocked|was blocked)\\b")

    private suspend fun runHelper(itemId: Long, label: String, history: MutableList<Msg>, prompt: String, resume: String?, ctx: ChatContext,
                                  provider: LlmProvider?, sub: SubscriptionEngine?, model: String) {
        phone.workStarted()
        var state = "failed"; var result = ""
        try {
            val sys = systemPrompt(history.first().text, role = PromptRole.HELPER, profile = helperProfile[itemId]?.let { profiles.find(it) })
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
                var pushes = 0
                setHelperMeta(itemId) { pushes = it.optInt("pushes"); it.put("state", state).put("result", result.take(6000)).put("endedAt", System.currentTimeMillis()).remove("now") }
                helpers.remove(itemId); helperCtx.remove(itemId); refreshHelpers()
                // Anything sent in that it never got to read goes into its conversation for a push later.
                steerInbox.remove(itemId)?.let { q -> generateSequence { q.poll() }.toList() }?.takeIf { it.isNotEmpty() && sub == null }
                    ?.let { history += Msg.user(it.joinToString("\n\n") { m -> "[The main agent, while you worked] $m" }) }
                pending.keys.filter { id -> runCatching { JSONObject(db.chat().get(id)?.meta ?: "{}").optLong("helper") == itemId }.getOrDefault(false) }.forEach { pending.remove(it)?.cancel() }
                com.past9.phoneaos.browser.BrowserService.release("helper-$itemId")
                phone.workFinished()
                // The main agent hears back unless the user stopped it (then there is nothing to report).
                if (state == "stopped") helperBatch.remove(itemId)?.let { b -> if (helperBatch.values.none { it == b }) batchResults.remove(b)?.takeIf { it.isNotEmpty() }?.let { all ->
                    deliver("[Helpers finished: the rest of the group was stopped. Give the user ONE packed answer from these.]\n\n" + all.joinToString("\n\n") { it.second }) } }
                if (state != "stopped") {
                    val nudge = if ((state == "failed" || gaveUp.containsMatchIn(result.trim().split(Regex("(?<=[.!?])\\s+|\\n")).first().take(300))) && pushes < 2)
                        "\n\n(It says it couldn't do all of it${if (pushes > 0) ", after $pushes push${if (pushes > 1) "es" else ""}" else ""}. Helpers usually can. Unless it's truly impossible, send it back with helper_push: name a concrete next thing to try and tell it you believe it can do it. Push at least twice before you accept it or tell the user.)"
                    else ""
                    val note = "[Helper #$itemId \"$label\" ${if (state == "done") "finished" else "failed"}${if (pushes > 0) " (after $pushes push${if (pushes > 1) "es" else ""})" else ""}]\n${result.take(12_000).ifBlank { "(no result text)" }}$nudge"
                    val origin = runCatching { JSONObject(db.chat().get(itemId)?.meta ?: "{}").optLong("origin").takeIf { it > 0 } }.getOrNull()
                    val batch = helperBatch.remove(itemId)
                    if (batch == null) deliver(note, origin)
                    else {
                        // Part of one answer: hold it until every helper in the group is done, then hand them over together.
                        batchResults.getOrPut(batch) { java.util.concurrent.ConcurrentLinkedQueue() }.add(itemId to note)
                        if (helperBatch.values.none { it == batch }) batchResults.remove(batch)?.let { all ->
                            deliver("[Helpers finished: all ${all.size} you started together are done. Give the user ONE packed answer.]\n\n" + all.joinToString("\n\n") { it.second }, origin)
                        }
                    }
                }
            }
        }
    }

    /** A helper on the user's subscription: the same harness, our worker tools over MCP. */
    private suspend fun runSubHelper(itemId: Long, prompt: String, sys: String, resume: String?, engine: SubscriptionEngine, model: String): String {
        var last = ""; var error: String? = null; var ok = false
        engine.turn(prompt, sys, resume, mcp.url + "/h/$itemId", settings.localToken(), emptyList(), model.ifBlank { null }, role = "helper").collect { e ->
            when (e.optString("type")) {
                "session" -> e.optString("id").takeIf { it.isNotBlank() }?.let { helperSession[itemId] = it }
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
        val provider = providerFactory(settings) ?: return "No AI provider configured"
        val ctx = ChatContext("routine", interactive = false)
        val history = mutableListOf(Msg.user("Routine \"$name\": $prompt"))
        phone.workStarted()
        return try {
            // A routine is work, not conversation: it runs with the helpers' tools.
            val out = AgentLoop(provider, settings.state.value.model, tools(forHelper = true)).run(systemPrompt(prompt, role = PromptRole.ROUTINE), history, ctx)
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

    fun clearConversation() = scope.launch { db.chat().clear(); db.chat().clearTurns("main"); settings.setExtra("claude_session", null); settings.setExtra("conversation_id", null) }

    // ---- tool context -----------------------------------------------------------------------

    private inner class ChatContext(override val agentLabel: String, private val interactive: Boolean, private val parentAsk: ToolContext? = null, private val helperItem: Long? = null,
                                    /** What the user sees as this agent's name ("Uber to airport"). */
                                    private val display: String = agentLabel) : ToolContext {
        override suspend fun activity(text: String, meta: JSONObject): Long =
            db.chat().insert(ChatItem(kind = "activity", text = text, meta = meta.put("by", display).apply { helperItem?.let { put("helper", it) } }.toString()))
        override suspend fun updateActivity(id: Long, text: String, meta: JSONObject) {
            db.chat().get(id)?.let { old -> db.chat().update(old.copy(text = text, meta = JSONObject(old.meta).also { m -> meta.keys().forEach { k -> m.put(k, meta.get(k)) } }.toString())) }
        }
        override suspend fun ask(question: String, options: List<String>): String? {
            if (!interactive) return parentAsk?.ask(question, options)
            val id = db.chat().insert(ChatItem(kind = "question", text = question, meta = JSONObject().put("options", org.json.JSONArray(options))
                .apply { helperItem?.let { put("helper", it).put("by", display) } }.toString()))
            val d = CompletableDeferred<String>(); pending[id] = d
            if (helperItem == null) mainQuestions += id
            val prev = _status.value; if (helperItem == null) _status.value = prev.copy(label = "Waiting for you")
            val isApproval = question.startsWith("APPROVAL|")
            val connect = com.past9.phoneaos.tools.ConnectRequest.parse(question)
            when {
                connect != null -> phone.notify("Connect ${connect.name}?", connect.summary, id, options)
                else -> phone.notify(if (isApproval) "Approve?" else "Your agent has a question", if (isApproval) question.split("|").getOrElse(1) { "" } else question, id, options)
            }
            return try { d.await() } finally { mainQuestions.remove(id); if (helperItem == null) _status.value = prev.copy(helpers = helpers.size) }
        }
        override suspend fun notify(title: String, body: String) = phone.notify(title, body)
    }

    companion object {
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
