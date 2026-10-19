package com.past9.phoneaos

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.connections.LearnedApp
import com.past9.phoneaos.connections.LearnedTool
import com.past9.phoneaos.ui.LocalSheetPreview
import com.past9.phoneaos.ui.screens.ConnectionsActions
import com.past9.phoneaos.ui.screens.ConnectionsScreen
import com.past9.phoneaos.ui.screens.ConnectionsState
import com.past9.phoneaos.ui.screens.LearnedSheet
import com.past9.phoneaos.ui.theme.AppTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Learned apps are one line in the Built in card and a slide-up sheet, so ten of them don't push the profiles off the screen. PNGs in app/build/screens-learned/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class LearnedSheetScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun app(i: Int, name: String, host: String, writes: Boolean = false) = LearnedApp("app$i", name, "https://$host", "About $name: reads what the page shows, no sign-in needed.",
        tools = listOf(LearnedTool("get_price", "Price", js = "return 1")) + if (writes) listOf(LearnedTool("send_message", "Send", js = "return 1", readOnly = false)) else emptyList())
    private val names = listOf("Amazon.co.za" to "amazon.co.za", "WhatsApp Web" to "web.whatsapp.com", "City Library" to "library.example.org", "Takealot" to "takealot.com", "Checkers" to "checkers.co.za", "Meal Planner" to "mealplanner.example.org",
        "Discovery Bank" to "discovery.co.za", "Gumtree" to "gumtree.co.za", "Booking.com" to "booking.com", "FNB Online" to "fnb.co.za", "Fathom" to "fathom.video", "ClickUp" to "clickup.com")
    private val twelve = names.mapIndexed { i, (n, h) -> app(i, n, h, writes = i % 2 == 1) }

    private fun show(dark: Boolean, h: Int = 1500, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("w390dp-h${h}dp-port-${if (dark) "night" else "notnight"}-xxhdpi")
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme(dark = dark) { Surface(color = MaterialTheme.colorScheme.surface) { CompositionLocalProvider(LocalSheetPreview provides true) { content() } } } }
        rule.mainClock.advanceTimeBy(900)
    }
    private fun shot(name: String) { File("build/screens-learned").mkdirs(); rule.onRoot().captureRoboImage("build/screens-learned/$name.png") }

    @Test fun twelveAppsAreOneLineInTheBuiltInCard() {
        show(true, 1700) { ConnectionsScreen(ConnectionsState(learned = twelve), ConnectionsActions()) }
        rule.onNodeWithText("Learned apps").assertExists()
        rule.onAllNodesWithText("Teach your agent this app").assertCountEquals(0)   // the old full-size section is gone
        rule.onAllNodesWithText("get price").assertCountEquals(0)                   // and none of the tools are listed in the page
        rule.onNodeWithText("12").assertExists()                                    // the count badge
        assertTrue(rule.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
        shot("connections-twelve-dark")
    }

    @Test fun theLineOpensTheSheetWithEveryAppAndTheTeachBox() {
        show(true) { ConnectionsScreen(ConnectionsState(learned = twelve), ConnectionsActions(), initialSheet = "learned") }
        rule.onNodeWithText("Sites your agent knows").assertExists()
        for (n in listOf("Amazon.co.za", "WhatsApp Web", "City Library")) rule.onAllNodesWithText(n)[0].assertExists()
        rule.onNodeWithText("Teach your agent this app").assertExists()
        shot("sheet-twelve-dark")
    }

    @Test fun lightModeSheetWithOneOpen() {
        show(false) { LearnedSheet(twelve.take(4), ConnectionsActions(), {}, initialOpen = "app1") }
        rule.onNodeWithText("Change things without asking").assertExists()
        shot("sheet-open-light")
    }

    @Test fun trustAndRemoveWorkFromTheSheet() {
        val trusted = mutableListOf<Pair<String, Boolean>>(); val removed = mutableListOf<String>(); val taught = mutableListOf<String>()
        show(true) { LearnedSheet(twelve.take(3), ConnectionsActions(onTrustLearned = { id, on -> trusted += id to on }, onRemoveLearned = { removed += it }, onTeach = { taught += it }), {}, initialOpen = "app1") }
        rule.mainClock.autoAdvance = true   // taps and the recompositions after them run on their own
        rule.onNodeWithText("Remove").performClick(); rule.waitForIdle()
        assertTrue("two steps: nothing removed yet", removed.isEmpty())
        rule.onNodeWithText("Remove its tools").performClick(); rule.waitForIdle()
        assertEquals(listOf("app1"), removed)
    }

    @Test fun emptyExplainsItself() {
        show(true) { LearnedSheet(emptyList(), ConnectionsActions(), {}) }
        rule.onNodeWithText("Sites your agent knows").assertExists()
        shot("sheet-empty-dark")
    }
}
