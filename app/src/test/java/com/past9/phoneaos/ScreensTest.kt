package com.past9.phoneaos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.agent.AgentStatus
import com.past9.phoneaos.data.*
import com.past9.phoneaos.data.SubKind
import com.past9.phoneaos.tools.Connection
import com.past9.phoneaos.tools.Toolkit
import com.past9.phoneaos.ui.screens.*
import com.past9.phoneaos.ui.theme.AppTheme
import org.json.JSONArray
import androidx.compose.foundation.layout.fillMaxSize
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Every screen at phone size (390dp wide), light and dark. PNGs land in app/build/screens/. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ScreensTest(private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
    @get:Rule val rule = createComposeRule()

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme(dark = dark) { content() } }
        rule.mainClock.advanceTimeBy(900)
        rule.onRoot().captureRoboImage(File("build/screens/$name-${if (dark) "dark" else "light"}.png").path)
    }

    private val now = System.currentTimeMillis()
    private var nextId = 1L
    private fun item(kind: String, text: String, meta: JSONObject = JSONObject()) = ChatItem(id = nextId++, kind = kind, text = text, meta = meta.toString(), createdAt = now)

    private val conversation get() = listOf(
        item("user", "Find me the three cheapest flights from Joburg to Durban on Friday the 9th, morning only"),
        item("activity", "Searched memory for \"flights preferences\" · 2 found", JSONObject().put("tool", "memory")),
        item("helper", "Check FlySafair for Friday 9 Oct JNB to DUR departures before 12:00, return prices and times", JSONObject().put("label", "Helper 1").put("state", "done").put("result", "- 06:05 R 899\n- 09:40 R 1,049")),
        item("helper", "Check Airlink and CemAir for the same route and window", JSONObject().put("label", "Helper 2").put("state", "working")),
        item("activity", "Opened FlySafair · Book flights", JSONObject().put("tool", "browser")),
        item("activity", "Clicked Search flights", JSONObject().put("tool", "browser")),
        item("activity", "Remembered: Prefers window seats", JSONObject().put("tool", "memory")),
        item("agent", "Here's what I found for **Friday 9 October**, before noon:\n\n1. **FlySafair 06:05**, R 899 (window seats left)\n2. **Lift 07:30**, R 1,020\n3. **FlySafair 09:40**, R 1,049\n\nSources: flysafair.co.za and lift.co.za, checked just now. Want me to hold the 06:05?"),
        item("question", "APPROVAL|Book FlySafair FA 151, Fri 9 Oct 06:05|Passenger: Sam Dlamini\nSeat: 14A (window)\nFare: R 899 incl. taxes\nPaid with: card ending 4421"),
    )

    @Test fun keyStepPlainHttp() { shot("03b-key-plain-http") { com.past9.phoneaos.ui.screens.KeyStep(com.past9.phoneaos.data.Provider.CUSTOM, {}, OnboardingActions(), initialBaseUrl = "http://192.168.1.20:8080/v1") {} } }
    @Test fun onboarding() { shot("01-onboarding-hello") { OnboardingScreen(OnboardingActions()) } }
    @Test fun onboardingPower() { shot("02-onboarding-power") { OnboardingScreen(OnboardingActions(), start = OnbStep.POWER) } }
    @Test fun onboardingKey() { shot("03-onboarding-key") { OnboardingScreen(OnboardingActions(), start = OnbStep.KEY) } }
    @Test fun onboardingName() { shot("06-onboarding-name") { OnboardingScreen(OnboardingActions(), start = OnbStep.NAME, initialUser = "Sam", initialAgent = "Nova") } }
    @Test fun onboardingStyle() { shot("07-onboarding-style") { OnboardingScreen(OnboardingActions(), start = OnbStep.STYLE, initialAccent = "teal", initialMascot = "ghost", initialUser = "Sam", initialAgent = "Nova") } }
    @Test fun onboardingSub() { shot("08-onboarding-sub") { OnboardingScreen(OnboardingActions(), start = OnbStep.SUB, initialSub = SubKind.CODEX) } }
    @Test fun onboardingReady() { shot("09-onboarding-ready") { OnboardingScreen(OnboardingActions(), start = OnbStep.READY, initialAccent = "ember", initialUser = "Sam") } }
    @Test fun chatImages() {
        val files = listOf(0xFF4A3FD1.toInt() to (1200 to 800), 0xFF00696B.toInt() to (800 to 1200), 0xFFB0410C.toInt() to (1000 to 1000)).mapIndexed { i, (c, wh) ->
            val bmp = android.graphics.Bitmap.createBitmap(wh.first, wh.second, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(c) }
            android.graphics.Canvas(bmp).drawCircle(wh.first / 2f, wh.second / 2f, wh.first / 4f, android.graphics.Paint().apply { color = 0xFFFAF8F4.toInt() })
            File(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "img$i.png").also { f -> f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) } }
        }
        val big = android.graphics.Bitmap.createBitmap(1000, 3000, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2E6A2F.toInt()) }
        val bigFile = File(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "tall.png").also { f -> f.outputStream().use { big.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) } }
        val items = listOf(
            item("user", "Show me three cafés near Rosebank with good Wi-Fi"),
            item("agent", "Here are three, all under 10 minutes away:\n\n![Father Coffee](file://${files[0].path}) ![Bean There](file://${files[1].path}) ![Starbucks Keyes](file://${files[2].path})\n\n1. **Father Coffee**, quiet upstairs, fast Wi-Fi\n2. **Bean There**, big tables\n3. **Starbucks Keyes Art Mile**, open till 21:00"),
            item("user", "And the menu at Father?"),
            item("agent", "Their menu board (it's a tall photo, tap to zoom):\n\n![Menu board](file://${bigFile.path})"),
        )
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(ctx).dispatcher(kotlinx.coroutines.Dispatchers.Unconfined)
            .interceptorDispatcher(kotlinx.coroutines.Dispatchers.Unconfined).fetcherDispatcher(kotlinx.coroutines.Dispatchers.Unconfined)
            .decoderDispatcher(kotlinx.coroutines.Dispatchers.Unconfined).transformationDispatcher(kotlinx.coroutines.Dispatchers.Unconfined).crossfade(false).build())
        shot("14-chat-images") { ChatScreen(items, AgentStatus(), "Nova", "Sam", false, "", {}, ChatActions()) }
    }
    @Test fun onboardingApps() { shot("04-onboarding-apps") { OnboardingScreen(OnboardingActions(), start = OnbStep.APPS) } }

    @Test fun chatEmpty() { shot("10-chat-empty") { ChatScreen(emptyList(), AgentStatus(), "Your agent", "Sam", false, "", {}, ChatActions()) } }
    @Test fun chatBusy() { shot("11-chat-working") { ChatScreen(conversation.dropLast(1), AgentStatus(true, "Browsing", 1), "Nova", "Sam", true, "", {}, ChatActions(), openCount = 3) } }
    @Test fun chatApproval() { shot("12-chat-approval") { ChatScreen(conversation, AgentStatus(true, "Waiting for you"), "Nova", "Sam", true, "Also check", {}, ChatActions(), openCount = 3) } }
    @Test fun chatQuestion() {
        val items = listOf(
            item("user", "Every morning at 7, give me the news that matters to me"),
            item("activity", "New routine: Morning brief", JSONObject().put("tool", "routines")),
            item("question", "Which topics should the brief cover?", JSONObject().put("options", JSONArray(listOf("SA business + tech", "Just tech", "Let me type it")))),
        )
        shot("13-chat-question") { ChatScreen(items, AgentStatus(true, "Waiting for you"), "Your agent", "Sam", false, "", {}, ChatActions()) }
    }

    @Test fun tasks() {
        val goals = listOf(GoalRow(1, "Plan the Durban trip", "Long weekend, 9 to 12 Oct"))
        val tasks = listOf(
            TaskRow(1, 1, "Book flights", status = "done"), TaskRow(2, 1, "Book a place near uMhlanga", status = "doing"), TaskRow(3, 1, "Car hire for 3 days"),
            TaskRow(4, null, "Renew driver's licence", notes = "Bring 2 photos", dueAt = now + 3 * 86_400_000L, status = "blocked"),
            TaskRow(5, null, "Send Lerato the invoice", dueAt = now + 86_400_000L), TaskRow(6, null, "Cancel the old gym debit order", status = "done"),
        )
        shot("20-tasks") { TasksScreen(goals, tasks, TaskActions()) }
    }
    @Test fun tasksEmpty() { shot("21-tasks-empty") { TasksScreen(emptyList(), emptyList(), TaskActions()) } }

    @Test fun memory() {
        val m = listOf(
            MemoryRow(1, "Name", "His name is Sam; he goes by Sam.", "fact", pinned = true, updatedAt = now - 86_400_000L * 3),
            MemoryRow(2, "Seat preference", "Prefers window seats and no red-eye flights.", "preference", updatedAt = now - 3_600_000),
            MemoryRow(3, "Lerato", "Lerato is his business partner at Acme; invoices go to her on the 25th.", "person", source = "user", updatedAt = now - 86_400_000L),
            MemoryRow(4, "Gym", "Cancelled Virgin Active on 2 Oct; watch for a final debit.", "event", updatedAt = now - 600_000),
        )
        shot("30-memory") { MemoryScreen(m + demoMem.map { it.copy(id = it.id + 10, pinned = false) }, MemoryActions()) }
    }

    @Test fun routines() {
        val r = listOf(
            TriggerRow(1, "Morning brief", "daily", "07:00", "Brief me on SA business and tech news, my calendar and anything due today.", lastRunAt = now - 3_600_000 * 5, lastResult = "3 stories, 2 meetings, 1 task due (licence renewal)."),
            TriggerRow(2, "Bank alerts", "email", "from:alerts@mybank.co.za", "Tell me what changed and flag anything over R 1,000."),
            TriggerRow(3, "Check flight price", "interval", "180", "Check FlySafair JNB-DUR Fri 9 Oct 06:05 and tell me if it drops below R 800.", enabled = false),
            TriggerRow(5, "Overnight: tidy up and plan your morning", "nightly", "02:00", "While your phone charges: go over the day, save what I learned to memory, tidy the chat, and get your morning screen ready.", lastRunAt = now - 3_600_000 * 17, lastResult = "Morning screen ready: Want me to chase the Uber refund?"),
            TriggerRow(6, "Sunday plan", "daily", "18:00", "If it's Sunday, plan my week from my open tasks."),
        )
        shot("40-routines") { RoutinesScreen(r, RoutineActions()) }
    }

    @Test fun chatConnect() {
        val items = listOf(
            item("user", "check my mail"),
            item("activity", "Composio: search tools", JSONObject().put("tool", "apps")),
            item("question", "CONNECT|slack|Slack|post the summary to #team", JSONObject().put("options", JSONArray(listOf("Connect", "Decline"))).put("answer", "Decline")),
            item("question", com.past9.phoneaos.tools.ConnectRequest.of("gmail", "check your inbox and tell you what's new").text, JSONObject().put("options", JSONArray(listOf("Connect", "Decline")))),
        )
        shot("16-chat-connect") { ChatScreen(items, AgentStatus(true, "Waiting for you"), "Juno", "Sam", false, "", {}, ChatActions()) }
    }
    @Test fun connectionsConsumer() {
        val st = ConnectionsState(hasKey = true, consumer = true, toolkits = com.past9.phoneaos.tools.AppCatalog.popular,
            connected = listOf(Connection("gmail_1", "gmail", "ACTIVE", "gmail"), Connection("notion_1", "notion", "ACTIVE", "notion")))
        shot("52-connections-consumer") { ConnectionsScreen(st, ConnectionsActions()) }
    }
    private val lab = com.past9.phoneaos.machines.Machine("a1", "Uni lab", "login.cs.example.edu", 22, "sdlamini001", about = "Linux 5.15 · 64 cores")
    private val vps = com.past9.phoneaos.machines.Machine("b2", "Build server", "203.0.113.20", 2222, "deploy", com.past9.phoneaos.machines.MachineAuth.NEW_KEY, trusted = true, about = "Linux 6.8 · 8 cores", hostKey = "SHA256:3xg0Qm1rT8wq1V8pJm8n0aWJxw2v1y0iYpK3b9c7D2E")
    private val pc = com.past9.phoneaos.machines.Machine("c3", "Home PC", "100.84.55.108", 22, "sam", about = "Windows 11 · 12 cores")
    @Test fun machinesSheetEmpty() { sheet("56-machines-empty") { MachinesSheet(emptyList(), MachineActions(), {}, {}, checkOnOpen = false) } }
    @Test fun machinesSheet() { sheet("57-machines") { MachinesSheet(listOf(vps, lab, pc), MachineActions(), {}, {}, checkOnOpen = false, initialStatus = mapOf("b2" to "", "a1" to "", "c3" to "Couldn't reach Home PC")) } }
    @Test fun machineNew() { sheet("53-machine-new") { MachineSheet(null, listOf(lab), MachineActions(), {}, {}) } }
    @Test fun machineKey() { sheet("54-machine-key") { MachineSheet(vps, listOf(lab, vps), MachineActions(publicKey = { "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIK8xH2kq9b7m3Wf0pQZ1vLr8sYt4uN6cE5dA2gB1hJ0k agent-build-server" }), {}, {}) } }
    @Test fun machineConnected() { sheet("58-machine-connected") { MachineSheet(lab, listOf(lab, vps), MachineActions(), {}, {}, initialResult = "") } }
    @Test fun connectionsMachines() { shot("55-connections-machines") { ConnectionsScreen(ConnectionsState(hasKey = false, machines = listOf(lab, vps)), ConnectionsActions()) } }
    @Test fun connectionsNoKey() { shot("50-connections-nokey") { ConnectionsScreen(ConnectionsState(hasKey = false), ConnectionsActions()) } }
    @Test fun connections() {
        val st = ConnectionsState(hasKey = true, connected = listOf(Connection("1", "gmail", "ACTIVE", "sam@example.com"), Connection("2", "googlecalendar", "INITIATED", "googlecalendar")),
            toolkits = listOf(Toolkit("gmail", "Gmail", "Read, search, draft and send email", "", 40, false, true), Toolkit("googlecalendar", "Google Calendar", "Events, invites and free time", "", 30, false, true),
                Toolkit("googledrive", "Google Drive", "Find, read and share files", "", 35, false, true), Toolkit("slack", "Slack", "Messages, channels and threads", "", 60, false, true), Toolkit("notion", "Notion", "Pages and databases", "", 25, false, true)),
            notificationsAllowed = false)
        shot("51-connections") { ConnectionsScreen(st, ConnectionsActions()) }
    }

    @Test fun settings() {
        val s = AgentSettings(mode = PowerMode.API_KEY, provider = Provider.ANTHROPIC, model = "claude-sonnet-5-5", helperModel = "claude-haiku-4-5-20251001", hasKey = true, userName = "Sam", agentName = "Nova", onboarded = true)
        shot("60-settings") { SettingsScreen(s, RuntimeInfo(false, 0, false, "Runtime pack not in this build"), "0.1.0", SettingsActions()) }
    }

    private val demoGoals = listOf(GoalRow(1, "Plan the Durban trip", "Long weekend, 9 to 12 Oct"), GoalRow(2, "Get the car licence renewed", ""))
    private val demoTasks get() = listOf(
        TaskRow(1, 1, "Book flights", status = "done", owner = "agent"), TaskRow(2, 1, "Shortlist places near uMhlanga", status = "doing", owner = "agent"),
        TaskRow(3, 1, "Car hire for 3 days", owner = "agent"), TaskRow(7, 1, "Approve the guesthouse", owner = "user"),
        TaskRow(8, 2, "Find the nearest DLTC with open slots", status = "blocked", owner = "agent", blocker = "The booking site needs your ID number to show slots"),
        TaskRow(4, null, "Renew driver's licence", notes = "Bring 2 photos", dueAt = now + 3 * 86_400_000L, owner = "user"),
        TaskRow(5, null, "Send Lerato the invoice", dueAt = now - 3_600_000L, owner = "user"), TaskRow(6, null, "Call mom back", owner = "user"),
    )
    private val demoMem get() = listOf(
        MemoryRow(1, "Sam", "Sam Dlamini runs product at [[Acme]] and lives in Johannesburg. Prefers short answers, window seats and no red-eye flights.", "person", "self, preferences", pinned = true, updatedAt = now - 86_400_000L * 3),
        MemoryRow(2, "Lerato", "Lerato is [[Sam]]'s sister. She runs the books at [[Acme]]; invoices go to her on the 25th.\n\n- Birthday: 14 March\n- Lives in [[Durban]]", "person", "family, acme, invoices", updatedAt = now - 3_600_000),
        MemoryRow(3, "Acme", "A fintech company where [[Sam]] works. Office in Rosebank.", "company", "work", updatedAt = now - 86_400_000L),
    )
    private val demoHelpers: List<com.past9.phoneaos.data.ChatItem> get() { val first = item("helper", "Find guesthouses in uMhlanga for Fri 9 to Sun 11 Oct, 2 adults, under R 1,200 a night, sea view preferred (Sam loves waking up to the ocean). Use Booking.com and Airbnb. Bring back the best five with price per night, rating, distance to the beach and a link.",
            JSONObject().put("label", "uMhlanga stays").put("state", "working").put("now", "Browsing").put("startedAt", now - 4 * 60_000).put("model", "claude-haiku-4-5").put("profile", "Personal").put("profileColor", "mint"))
        return listOf(first,
        item("activity", "Opened Booking.com · uMhlanga, 9 to 11 Oct", JSONObject().put("tool", "browser").put("helper", first.id)),
        item("activity", "Read 24 results, sorted by price", JSONObject().put("tool", "browser").put("helper", first.id)),
        item("helper", "Check Uber and Bolt prices from King Shaka airport to uMhlanga Rocks on Friday around 18:00 and say which is cheaper.",
            JSONObject().put("label", "Airport ride").put("state", "working").put("now", "Reading the web").put("startedAt", now - 60_000).put("model", "claude-haiku-4-5").put("profile", "Northwind work").put("profileColor", "ocean")),
        item("helper", "Look up the Sharks game times for the weekend of 10 Oct and whether tickets are left.",
            JSONObject().put("label", "Sharks tickets").put("state", "done").put("startedAt", now - 30 * 60_000).put("endedAt", now - 22 * 60_000)
                .put("result", "**Sharks v Stormers**, Sat 10 Oct, 17:00 at Kings Park. Tickets from R 180 on Ticketmaster, about 40 left in the West stand.\n\nSource: ticketmaster.co.za, checked 14:05.")),
    ) }
    private val demoHome get() = HomeState("Nova", "Sam", AgentStatus(false, "", 2),
        demoHelpers + listOf(item("agent", "Found three places in uMhlanga under R 1,200 a night. **Ocean Breeze** has the best reviews; want me to hold it?"),
            item("question", "Which guesthouse should I book?", JSONObject().put("options", JSONArray(listOf("Ocean Breeze", "The Palms", "Show me more"))))),
        demoGoals, demoTasks,
        listOf(TriggerRow(1, "Morning brief", "daily", "07:00", "Brief me"), TriggerRow(2, "Bank alerts", "notification", "MyBank|", "Flag anything over R 1,000")),
        notifications24h = 14, notifApps = 3, browserLive = true)

    @Test fun home() { shot("15-home") { HomeScreen(demoHome, HomeActions()) ; androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) { HomeBar("memory", {}, {}) } } }
    @Test fun profile() { shot("16-profile") { ProfileScreen(demoHome, demoMem, ProfileActions()) } }
    @Test fun wikiPage() { shot("31-memory-page") { WikiPageScreen(demoMem[1], demoMem, {}, {}, {}, {}, {}) } }
    @Test fun tasksAgent() { shot("22-tasks-agent") { TasksScreen(demoGoals, demoTasks, TaskActions(), startTab = 1) } }
    @Test fun tasksMine() { shot("23-tasks-mine") { TasksScreen(demoGoals, demoTasks, TaskActions(), startTab = 0) } }
    @Test fun notifications() {
        val rows = listOf(NotificationRow(1, "com.mybank", "MyBank", "Payment received", "R 2,500.00 from ACME PTY LTD into Savings", now - 600_000), NotificationRow(2, "com.whatsapp", "WhatsApp", "Lerato", "Did you send the invoice?", now - 3_600_000))
        shot("52-notifications") { NotificationsScreen(false, listOf(PhoneApp("com.mybank", "MyBank", null), PhoneApp("com.whatsapp", "WhatsApp", null), PhoneApp("com.takealot", "Takealot", null)), setOf("com.mybank", "com.whatsapp"), rows, NotificationsActions()) }
    }
    @Test fun styleRed() { shot("17-onboarding-style-red") { OnboardingScreen(OnboardingActions(), start = OnbStep.STYLE, initialAccent = "red", initialMascot = "scout", initialUser = "Sam", initialAgent = "Nova") } }

    private fun sheet(name: String, content: @Composable () -> Unit) = shot(name) {
        androidx.compose.runtime.CompositionLocalProvider(com.past9.phoneaos.ui.LocalSheetPreview provides true) { content() }
    }
    @Test fun helperSheetWorking() { sheet("84-sheet-helper-working") { com.past9.phoneaos.ui.HelperSheet(com.past9.phoneaos.ui.HelperView.from(demoHelpers)[0], {}, {}, now = now) } }
    @Test fun helperSheetDone() { sheet("85-sheet-helper-done") { com.past9.phoneaos.ui.HelperSheet(com.past9.phoneaos.ui.HelperView.from(demoHelpers)[2], {}, {}, now = now) } }
    @Test fun finishedHelpersLeaveTheChatOnlyAfterTheAgentReplies() {
        val h = ChatItem(id = 500, kind = "helper", text = "x", meta = JSONObject().put("state", "failed").put("endedAt", now).put("result", "boom").toString(), createdAt = now - 10)
        val stopped = ChatItem(id = 501, kind = "helper", text = "y", meta = JSONObject().put("state", "stopped").put("endedAt", now).toString(), createdAt = now - 10)
        fun shown(items: List<ChatItem>) = group(items).filterIsInstance<Row_.Single>().map { it.item.id }
        // No reply after it (the main turn failed): the failed helper stays visible; a stopped one never shows.
        assertEquals(listOf(500L, 502L), shown(listOf(h, stopped, ChatItem(id = 502, kind = "notice", text = "Something went wrong", createdAt = now + 5))))
        // The agent replied after it: it leaves.
        assertEquals(listOf(503L), shown(listOf(h, stopped, ChatItem(id = 503, kind = "agent", text = "It failed: boom", createdAt = now + 5))))
    }

    @Test fun chatTable() { shot("13-chat-table") { ChatScreen(listOf(item("user", "compare them in a table"), item("agent", "Here's how the three helpers did:\n\n| Test | Helper | What it found | Source |\n|---|---|---|---|\n| Rand rate | Helper 1 | 1 USD = R 16.52 | xe.com |\n| Weather | Helper 2 | 13°, clear night | weather.com |\n| Power bank | Helper 3 | R 399 (Romoss 20k) | takealot.com |\n\nAll three ran at the same time.")),
        AgentStatus(), "Nova", "Sam", false, "", {}, ChatActions()) } }
    @Test fun cardSheet() { sheet("87-sheet-card") { com.past9.phoneaos.ui.CardSheet(com.past9.phoneaos.cards.Card("monitor-prices", "Monitor prices", "tag", "", headline = "R 3,299 cheapest", source = "takealot.com", script = "x"), dark, "iris", com.past9.phoneaos.ui.CardSheetActions(), {}) } }
    @Test fun homeWithCards() { shot("15b-home-cards") { HomeScreen(demoHome.copy(cards = listOf(
        com.past9.phoneaos.cards.Card("m", "Monitor prices", "tag", "", headline = "R 3,299 cheapest", updatedAt = now - 2 * 3_600_000),
        com.past9.phoneaos.cards.Card("o", "Outreach today", "mail", "", headline = "14 sent, 3 replies", updatedAt = now - 20 * 60_000),
        com.past9.phoneaos.cards.Card("b", "Daily brief", "sun", "", headline = "Interview prep first", updatedAt = now - 5 * 3_600_000))), HomeActions()) } }
    @Test fun helperRoster() { sheet("86-sheet-helper-roster") { HelperRosterSheet(listOf(
        com.past9.phoneaos.agent.ModelInfo("anthropic/claude-opus-5-5", "Claude Opus 5.5", true, "2026-08-01"),
        com.past9.phoneaos.agent.ModelInfo("anthropic/claude-sonnet-5-5", "Claude Sonnet 5.5", true, "2026-08-01"),
        com.past9.phoneaos.agent.ModelInfo("deepseek/deepseek-v4.1-flash", "DeepSeek V4.1 Flash", false, "2026-09-01"),
        com.past9.phoneaos.agent.ModelInfo("google/gemini-3-flash", "Gemini 3 Flash", true, "2026-07-01"),
        com.past9.phoneaos.agent.ModelInfo("openai/gpt-5-mini", "GPT-5 mini", true, "2026-05-01")), null,
        listOf(com.past9.phoneaos.data.HelperModel("anthropic/claude-sonnet-5-5", "Claude Sonnet 5.5", listOf("Web research", "Browsing sites", "Writing")),
            com.past9.phoneaos.data.HelperModel("deepseek/deepseek-v4.1-flash", "DeepSeek V4.1 Flash", listOf("Quick lookups", "Cheap bulk work", "Job applications"))), {}, {}) } }
    @Test fun sheetTask() { sheet("80-sheet-task") { AddTaskSheet({}, {}) { _, _ -> } } }
    @Test fun sheetMemory() { sheet("81-sheet-memory") { PageEditor(MemoryRow(id = 3, title = "Lerato", body = "Lerato is his business partner at Acme. Invoices go to her on the 25th.", kind = "person", topics = "work, acme"), {}, {}, {}) } }
    @Test fun sheetMemoryNew() { sheet("82-sheet-memory-new") { PageEditor(MemoryRow(id = 0, title = "", body = "", source = "user"), {}, {}) } }
    @Test fun sheetRoutineNew() { sheet("83-sheet-routine-new") { RoutineEditor(TriggerRow(name = "", kind = "daily", spec = "07:00", prompt = ""), {}, {}, null, null) } }
    @Test fun sheetRoutineEdit() { sheet("84-sheet-routine-edit") { RoutineEditor(TriggerRow(id = 4, name = "Bank alerts", kind = "email", spec = "from:alerts@mybank.co.za", prompt = "Tell me what changed and flag anything over R 1,000."), {}, {}, {}, {}) } }
    @Test fun sheetRoutineWeekly() { sheet("85-sheet-routine-weekly") { RoutineEditor(TriggerRow(id = 7, name = "Sunday week planning", kind = "weekly", spec = "SUN 18:00", prompt = "Look at my open tasks and the week ahead and give me a simple plan."), {}, {}, {}, {}) } }
    @Test fun browserEmpty() { shot("70-browser-empty") { BrowserScreen(emptyMap(), listOf("Personal", "Work"), BrowserActions()) } }

    // ---- profiles, account rules, reply quotes and reports (0.10) ----
    private val gmailWork = Connection("ca_w", "gmail", "ACTIVE", "sam@northwind.example")
    private val gmailHome = Connection("ca_h", "gmail", "ACTIVE", "sam.dlamini@gmail.com")
    private val slackWork = Connection("ca_s", "slack", "ACTIVE", "Northwind")
    private val outlookWork = Connection("ca_o", "outlook", "ACTIVE", "sam@northwind.example")
    private val teamsWork = Connection("ca_t", "microsoft_teams", "ACTIVE", "microsoft_teams")
    private val workProfile = AgentProfile("p1", "Northwind work", listOf(slackWork, outlookWork, teamsWork, gmailWork).map { it.ref() }, "Work", color = "ocean")
    private val homeProfile = AgentProfile("p2", "Personal", listOf(gmailHome).map { it.ref() }, color = "mint")
    private val kits = listOf(Toolkit("gmail", "Gmail", "Read, search, draft and send email", "", 40, false, true), Toolkit("slack", "Slack", "Messages, channels and threads", "", 60, false, true),
        Toolkit("outlook", "Outlook", "Mail and calendar", "", 40, false, true), Toolkit("microsoft_teams", "Microsoft Teams", "Chats and meetings", "", 30, false, true))
    @Test fun connectionsProfiles() {
        val st = ConnectionsState(hasKey = true, consumer = true, toolkits = kits, connected = listOf(gmailWork, gmailHome, slackWork, outlookWork, teamsWork),
            profiles = listOf(workProfile, homeProfile), rules = mapOf("ca_h" to AccountRules(change = com.past9.phoneaos.data.Rule.ALLOW), "ca_t" to AccountRules(change = com.past9.phoneaos.data.Rule.NEVER)))
        shot("59-connections-profiles") { ConnectionsScreen(st, ConnectionsActions()) }
    }
    @Test fun connectionsNoProfiles() {
        val st = ConnectionsState(hasKey = true, consumer = true, toolkits = kits, connected = listOf(gmailWork, slackWork))
        shot("59b-connections-no-profiles") { ConnectionsScreen(st, ConnectionsActions()) }
    }
    @Test fun sheetProfile() { sheet("87-sheet-profile") { ProfileSheet(workProfile, listOf(gmailWork, gmailHome, slackWork, outlookWork, teamsWork).map { it.ref() }, emptyMap(), listOf("Personal", "Work"), AccountProfileActions()) {} } }
    @Test fun sheetProfileNew() { sheet("88-sheet-profile-new") { ProfileSheet(null, listOf(gmailWork, gmailHome, slackWork).map { it.ref() }, emptyMap(), listOf("Personal"), AccountProfileActions()) {} } }
    @Test fun sheetAccount() { sheet("89-sheet-account") { AccountSheet(gmailWork.ref(), "", AccountRules(change = com.past9.phoneaos.data.Rule.ASK), listOf(workProfile, homeProfile), AccountProfileActions(), {}) {} } }
    @Test fun sheetAddToProfile() { sheet("90-sheet-add-to-profile") { AddToProfileSheet(outlookWork.ref(), "", AccountRules.DEFAULT, listOf(workProfile, homeProfile), AccountProfileActions()) {} } }
    @Test fun chatQuoteAndReport() {
        val ask = item("user", "Can you check if we qualify for the Transnet cyber tender and what we'd need?")
        val items = listOf(ask,
            item("agent", "On it: three helpers are checking the RFP, our certificates and possible partners.", JSONObject().put("replyTo", ask.id)),
            item("user", "Also remind me to call Lerato at 3"),
            item("agent", "Done, reminder set for 15:00.", JSONObject().put("replyTo", ask.id + 2)),
            item("report", "Not yet: two gaps, ISO 27001 and a 24/7 SOC. A partner closes both in about a month.",
                JSONObject().put("title", "Transnet cyber tender readiness").put("path", "/nope").put("words", 1400).put("replyTo", ask.id).put("late", true)),
            item("agent", "Short answer: not yet. Two gaps, both closable with a partner. The report has the plan.", JSONObject().put("replyTo", ask.id).put("late", true)))
        shot("15-chat-quote-report") { ChatScreen(items, AgentStatus(), "Nova", "Sam", false, "", {}, ChatActions()) }
    }
    @Test fun report() {
        val f = File.createTempFile("report", ".md").apply { writeText("# Transnet cyber tender readiness\n\n**Short answer:** not yet. Two gaps.\n\n## What they ask for\n\n- ISO 27001 certificate\n- A 24/7 security operations centre\n- B-BBEE level 1 or 2\n\n## What we have\n\n| Need | Us |\n|---|---|\n| ISO 27001 | No |\n| SOC | No |\n| B-BBEE | Level 1 |\n\n## The plan\n\n1. Partner with a SOC provider this month.\n2. Start ISO 27001 gap analysis.\n") }
        shot("17-report") { ReportScreen(f.path) {} }
    }

    @Test fun connectionsAppsOpen() {
        val st = ConnectionsState(hasKey = true, consumer = true, toolkits = kits + Toolkit("instagram", "Instagram", "Posts and messages", "", 20, false, true),
            connected = listOf(gmailWork, gmailHome, slackWork, Connection("ig1", "instagram", "ACTIVE", "instagram"), Connection("ig2", "instagram", "ACTIVE", "instagram")),
            profiles = listOf(workProfile, homeProfile))
        shot("59c-connections-apps-open") { ConnectionsScreen(st, ConnectionsActions(), initialAppsOpen = true) }
    }
}
