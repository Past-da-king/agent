package com.past9.phoneaos

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.data.AccountRules
import com.past9.phoneaos.data.AgentProfile
import com.past9.phoneaos.tools.Connection
import com.past9.phoneaos.tools.Toolkit
import com.past9.phoneaos.ui.screens.*
import com.past9.phoneaos.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Connections is long: these shots use a tall screen so the profiles and apps cards are in frame. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1900dp-xxhdpi")
class ConnectionsTallTest(private val dark: Boolean) {
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
    private val gw = Connection("ca_w", "gmail", "ACTIVE", "sam@northwind.example")
    private val gh = Connection("ca_h", "gmail", "ACTIVE", "sam.dlamini@gmail.com")
    private val g3 = Connection("ca_3", "gmail", "ACTIVE", "sam.business@gmail.com")
    private val sl = Connection("ca_s", "slack", "ACTIVE", "Northwind")
    private val ol = Connection("ca_o", "outlook", "ACTIVE", "sam@northwind.example")
    private val tm = Connection("ca_t", "microsoft_teams", "ACTIVE", "microsoft_teams")
    private val gh1 = Connection("gh1", "github", "ACTIVE", "github"); private val gh2 = Connection("gh2", "github", "ACTIVE", "github")
    private val ig = Connection("ig1", "instagram", "ACTIVE", "instagram"); private val no = Connection("no1", "notion", "ACTIVE", "notion")
    private val kits = listOf("gmail" to "Gmail", "slack" to "Slack", "outlook" to "Outlook", "microsoft_teams" to "Microsoft Teams", "github" to "GitHub", "instagram" to "Instagram", "notion" to "Notion", "googlecalendar" to "Google Calendar")
        .map { (s, n) -> Toolkit(s, n, "", "", 30, false, true) }
    private val all = listOf(gw, gh, g3, sl, ol, tm, gh1, gh2, ig, no)
    private val profiles = listOf(AgentProfile("p1", "Northwind work", listOf(sl, ol, tm, gw).map { it.ref() }, color = "ocean"),
        AgentProfile("p2", "Personal", listOf(gh, ig).map { it.ref() }, color = "mint"), AgentProfile("p3", "General", color = "graphite"))
    private fun state() = ConnectionsState(hasKey = true, consumer = true, toolkits = kits, connected = all, profiles = profiles,
        rules = mapOf("ca_h" to AccountRules(change = com.past9.phoneaos.data.Rule.ALLOW)))
    @Test fun folded() { shot("61-connections-folded") { ConnectionsScreen(state(), ConnectionsActions()) } }
    @Test fun open() { shot("62-connections-open") { ConnectionsScreen(state(), ConnectionsActions(), initialAppsOpen = true) } }
}
