package com.past9.phoneaos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.agent.AgentStatus
import com.past9.phoneaos.data.*
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import androidx.compose.ui.unit.dp

/**
 * The main screens at phone (390dp), tablet portrait (800dp) and tablet landscape (1280dp) widths.
 * PNGs land in app/build/screens-tablet/<size>-<screen>-<mode>.png.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class TabletScreensTest(private val size: String, private val qualifiers: String, private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-dark={2}")
        fun params() = listOf(
            arrayOf<Any>("phone", "w390dp-h844dp-port|xhdpi", true),
            arrayOf<Any>("tablet-port", "w800dp-h1280dp-port|mdpi", true),
            arrayOf<Any>("tablet-land", "w1280dp-h800dp-land|mdpi", true),
            arrayOf<Any>("tablet-land", "w1280dp-h800dp-land|mdpi", false),
        )
    }
    @get:Rule val rule = createComposeRule()

    private fun shot(name: String, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers(qualifiers.replace("|", if (dark) "-night-" else "-notnight-"))
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme(dark = dark) { androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.surface) { content() } } }
        rule.mainClock.advanceTimeBy(900)
        rule.onRoot().captureRoboImage(File("build/screens-tablet/$size-$name-${if (dark) "dark" else "light"}.png").path)
    }

    private val now = System.currentTimeMillis()
    private var nextId = 1L
    private fun item(kind: String, text: String, meta: JSONObject = JSONObject()) = ChatItem(id = nextId++, kind = kind, text = text, meta = meta.toString(), createdAt = now)
    private val conversation get() = listOf(
        item("user", "Find me the three cheapest flights from Joburg to Durban on Friday the 9th, morning only"),
        item("activity", "Searched memory for \"flights preferences\" · 2 found", JSONObject().put("tool", "memory")),
        item("helper", "Check FlySafair for Friday 9 Oct JNB to DUR departures before 12:00, return prices and times", JSONObject().put("label", "Helper 1").put("state", "done").put("result", "- 06:05 R 899\n- 09:40 R 1,049")),
        item("activity", "Opened FlySafair · Book flights", JSONObject().put("tool", "browser")),
        item("agent", "Here's what I found for **Friday 9 October**, before noon:\n\n1. **FlySafair 06:05**, R 899 (window seats left)\n2. **Lift 07:30**, R 1,020\n3. **FlySafair 09:40**, R 1,049\n\nSources: flysafair.co.za and lift.co.za, checked just now. Want me to hold the 06:05?"),
        item("user", "Yes, hold the 06:05"),
        item("question", "APPROVAL|Book FlySafair FA 151, Fri 9 Oct 06:05|Passenger: Sam Dlamini\nSeat: 14A (window)\nFare: R 899 incl. taxes\nPaid with: card ending 4421"),
    )
    private val goals = listOf(GoalRow(1, "Plan the Durban trip", "Long weekend, 9 to 12 Oct"))
    private val tasks get() = listOf(
        TaskRow(1, 1, "Book flights", status = "done", owner = "agent"), TaskRow(2, 1, "Shortlist places near uMhlanga", status = "doing", owner = "agent"),
        TaskRow(3, 1, "Car hire for 3 days", owner = "agent"), TaskRow(4, null, "Renew driver's licence", notes = "Bring 2 photos", dueAt = now + 3 * 86_400_000L, owner = "user"),
        TaskRow(5, null, "Send Lerato the invoice", dueAt = now - 3_600_000L, owner = "user"),
    )
    private val mem get() = listOf(
        MemoryRow(1, "Sam", "Sam Dlamini runs product at [[Acme]] and lives in Johannesburg. Prefers short answers and window seats.", "person", "self", pinned = true, updatedAt = now - 86_400_000L * 3),
        MemoryRow(2, "Lerato", "Lerato is [[Sam]]'s sister. She runs the books at [[Acme]]; invoices go to her on the 25th.", "person", "family", updatedAt = now - 3_600_000),
        MemoryRow(3, "Acme", "A fintech company where [[Sam]] works. Office in Rosebank.", "company", "work", updatedAt = now - 86_400_000L),
        MemoryRow(4, "Seat preference", "Window seats, no red-eye flights.", "preference", updatedAt = now - 600_000),
    )
    private val home get() = HomeState("Nova", "Sam", AgentStatus(true, "Browsing", 1), conversation.take(5) +
        item("question", "Which guesthouse should I book?", JSONObject().put("options", JSONArray(listOf("Ocean Breeze", "The Palms", "Show me more")))), goals, tasks)

    @Test fun chat() { shot("chat") { ChatScreen(conversation, AgentStatus(true, "Waiting for you"), "Nova", "Sam", true, "", {}, ChatActions(), openCount = 1) } }
    @Test fun chatEmpty() { shot("chat-empty") { ChatScreen(emptyList(), AgentStatus(), "Nova", "Sam", false, "", {}, ChatActions()) } }
    @Test fun homeScreen() { shot("home") { HomeScreen(home, HomeActions()); Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { HomeBar("home", {}, {}) } } }
    @Test fun settings() {
        val s = AgentSettings(mode = PowerMode.API_KEY, provider = Provider.ANTHROPIC, model = "claude-sonnet-5-5", hasKey = true, userName = "Sam", agentName = "Nova", onboarded = true)
        shot("settings") { SettingsScreen(s, RuntimeInfo(false, 0, false, "Runtime pack not in this build"), "0.11.14", SettingsActions()) }
    }
    @Test fun connections() {
        val st = ConnectionsState(hasKey = true, connected = listOf(Connection("1", "gmail", "ACTIVE", "sam@example.com"), Connection("2", "notion", "ACTIVE", "notion")),
            toolkits = listOf(Toolkit("gmail", "Gmail", "Read, search, draft and send email", "", 40, false, true), Toolkit("googlecalendar", "Google Calendar", "Events, invites and free time", "", 30, false, true),
                Toolkit("slack", "Slack", "Messages, channels and threads", "", 60, false, true), Toolkit("notion", "Notion", "Pages and databases", "", 25, false, true)))
        shot("connections") { ConnectionsScreen(st, ConnectionsActions()) }
    }
    @Test fun memory() { shot("memory") { MemoryScreen(mem, MemoryActions()) } }
    /** What the memory route shows: list and page side by side on an expanded width, the list alone otherwise. */
    @Test fun memoryOpen() { shot("memory-open") {
        if (com.past9.phoneaos.ui.widthClass() == com.past9.phoneaos.ui.WidthClass.EXPANDED) MemoryTwoPane(mem, 2L, {}, MemoryActions(), 120.dp, {}, {})
        else WikiPageScreen(mem[1], mem, {}, {}, {}, {}, {})
    } }
    @Test fun tasksScreen() { shot("tasks") { TasksScreen(goals, tasks, TaskActions()) } }
    @Test fun onboarding() { shot("onboarding") { OnboardingScreen(OnboardingActions(), start = OnbStep.KEY) } }
}
