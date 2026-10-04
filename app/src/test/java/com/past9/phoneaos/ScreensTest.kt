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
import org.json.JSONObject
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
        item("question", "APPROVAL|Book FlySafair FA 151, Fri 9 Oct 06:05|Passenger: Ayanda Mokoena\nSeat: 14A (window)\nFare: R 899 incl. taxes\nPaid with: card ending 4421"),
    )

    @Test fun onboarding() { shot("01-onboarding-hello") { OnboardingScreen(OnboardingActions()) } }
    @Test fun onboardingPower() { shot("02-onboarding-power") { OnboardingScreen(OnboardingActions(), start = OnbStep.POWER) } }
    @Test fun onboardingKey() { shot("03-onboarding-key") { OnboardingScreen(OnboardingActions(), start = OnbStep.KEY) } }
    @Test fun onboardingName() { shot("06-onboarding-name") { OnboardingScreen(OnboardingActions(), start = OnbStep.NAME, initialUser = "Ayanda", initialAgent = "Nova") } }
    @Test fun onboardingStyle() { shot("07-onboarding-style") { OnboardingScreen(OnboardingActions(), start = OnbStep.STYLE, initialAccent = "teal", initialMascot = "ghost", initialUser = "Ayanda", initialAgent = "Nova") } }
    @Test fun onboardingSub() { shot("08-onboarding-sub") { OnboardingScreen(OnboardingActions(), start = OnbStep.SUB, initialSub = SubKind.CODEX) } }
    @Test fun onboardingReady() { shot("09-onboarding-ready") { OnboardingScreen(OnboardingActions(), start = OnbStep.READY, initialAccent = "ember", initialUser = "Ayanda") } }
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
        shot("14-chat-images") { ChatScreen(items, AgentStatus(), "Nova", "Ayanda", false, "", {}, ChatActions()) }
    }
    @Test fun onboardingApps() { shot("04-onboarding-apps") { OnboardingScreen(OnboardingActions(), start = OnbStep.APPS) } }

    @Test fun chatEmpty() { shot("10-chat-empty") { ChatScreen(emptyList(), AgentStatus(), "Your agent", "Ayanda", false, "", {}, ChatActions()) } }
    @Test fun chatBusy() { shot("11-chat-working") { ChatScreen(conversation.dropLast(1), AgentStatus(true, "Browsing", 1), "Nova", "Ayanda", true, "", {}, ChatActions(), openCount = 3) } }
    @Test fun chatApproval() { shot("12-chat-approval") { ChatScreen(conversation, AgentStatus(true, "Waiting for you"), "Nova", "Ayanda", true, "Also check", {}, ChatActions(), openCount = 3) } }
    @Test fun chatQuestion() {
        val items = listOf(
            item("user", "Every morning at 7, give me the news that matters to me"),
            item("activity", "New routine: Morning brief", JSONObject().put("tool", "routines")),
            item("question", "Which topics should the brief cover?", JSONObject().put("options", JSONArray(listOf("SA business + tech", "Just tech", "Let me type it")))),
        )
        shot("13-chat-question") { ChatScreen(items, AgentStatus(true, "Waiting for you"), "Your agent", "Ayanda", false, "", {}, ChatActions()) }
    }

    @Test fun tasks() {
        val goals = listOf(GoalRow(1, "Plan the Durban trip", "Long weekend, 9 to 12 Oct"))
        val tasks = listOf(
            TaskRow(1, 1, "Book flights", status = "done"), TaskRow(2, 1, "Book a place near uMhlanga", status = "doing"), TaskRow(3, 1, "Car hire for 3 days"),
            TaskRow(4, null, "Renew driver's licence", notes = "Bring 2 photos", dueAt = now + 3 * 86_400_000L, status = "blocked"),
            TaskRow(5, null, "Send Thandi the invoice", dueAt = now + 86_400_000L), TaskRow(6, null, "Cancel the old gym debit order", status = "done"),
        )
        shot("20-tasks") { TasksScreen(goals, tasks, TaskActions()) }
    }
    @Test fun tasksEmpty() { shot("21-tasks-empty") { TasksScreen(emptyList(), emptyList(), TaskActions()) } }

    @Test fun memory() {
        val m = listOf(
            MemoryRow(1, "Name", "His name is Ayanda; he goes by Ayanda.", "fact", pinned = true, updatedAt = now - 86_400_000L * 3),
            MemoryRow(2, "Seat preference", "Prefers window seats and no red-eye flights.", "preference", updatedAt = now - 3_600_000),
            MemoryRow(3, "Thandi", "Thandi is his business partner at Guavas; invoices go to her on the 25th.", "person", source = "user", updatedAt = now - 86_400_000L),
            MemoryRow(4, "Gym", "Cancelled Virgin Active on 2 Oct; watch for a final debit.", "event", updatedAt = now - 600_000),
        )
        shot("30-memory") { MemoryScreen(m, MemoryActions()) }
    }

    @Test fun routines() {
        val r = listOf(
            TriggerRow(1, "Morning brief", "daily", "07:00", "Brief me on SA business and tech news, my calendar and anything due today.", lastRunAt = now - 3_600_000 * 5, lastResult = "3 stories, 2 meetings, 1 task due (licence renewal)."),
            TriggerRow(2, "Bank alerts", "email", "from:noreply@capitecbank.co.za", "Tell me what changed and flag anything over R 1,000."),
            TriggerRow(3, "Check flight price", "interval", "180", "Check FlySafair JNB-DUR Fri 9 Oct 06:05 and tell me if it drops below R 800.", enabled = false),
        )
        shot("40-routines") { RoutinesScreen(r, RoutineActions()) }
    }

    @Test fun connectionsNoKey() { shot("50-connections-nokey") { ConnectionsScreen(ConnectionsState(hasKey = false), ConnectionsActions()) } }
    @Test fun connections() {
        val st = ConnectionsState(hasKey = true, connected = listOf(Connection("1", "gmail", "ACTIVE", "ayanda@example.com"), Connection("2", "googlecalendar", "INITIATED", "googlecalendar")),
            toolkits = listOf(Toolkit("gmail", "Gmail", "Read, search, draft and send email", "", 40, false, true), Toolkit("googlecalendar", "Google Calendar", "Events, invites and free time", "", 30, false, true),
                Toolkit("googledrive", "Google Drive", "Find, read and share files", "", 35, false, true), Toolkit("slack", "Slack", "Messages, channels and threads", "", 60, false, true), Toolkit("notion", "Notion", "Pages and databases", "", 25, false, true)),
            notificationsAllowed = false)
        shot("51-connections") { ConnectionsScreen(st, ConnectionsActions()) }
    }

    @Test fun settings() {
        val s = AgentSettings(mode = PowerMode.API_KEY, provider = Provider.ANTHROPIC, model = "claude-sonnet-5-5", helperModel = "claude-haiku-4-5-20251001", hasKey = true, userName = "Ayanda", agentName = "Nova", onboarded = true)
        shot("60-settings") { SettingsScreen(s, RuntimeInfo(false, 0, false, "Runtime pack not in this build"), "0.1.0", SettingsActions()) }
    }

    @Test fun browserEmpty() { shot("70-browser-empty") { BrowserScreen(null, com.past9.phoneaos.browser.PageState(), {}, {}) } }
}
