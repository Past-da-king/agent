package com.past9.phoneaos

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.agent.AgentStatus
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.data.TaskRow
import com.past9.phoneaos.ui.LocalSheetPreview
import com.past9.phoneaos.ui.screens.HomeActions
import com.past9.phoneaos.ui.screens.HomeScreen
import com.past9.phoneaos.ui.screens.HomeState
import com.past9.phoneaos.ui.screens.NeedStore
import com.past9.phoneaos.ui.screens.NeedTarget
import com.past9.phoneaos.ui.screens.Needs
import com.past9.phoneaos.ui.theme.AppTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Home's Needs you cards and to-dos, 9 Nov 2026: three mixed cards, the card's slide-up sheet, the gone-target state
 * and the quiet empty line, at 390 wide in light and dark; plus what a tap, a hold and a swipe really do on screen.
 * PNGs land in app/build/audit/390/fxC-*.png.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class HomeNeedsShotsTest(private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
    @get:Rule val rule = createComposeRule()

    private val now = System.currentTimeMillis()
    private val items = listOf(
        ChatItem(1, "question", "APPROVAL|Send the quote to the client|To: client@example.com", JSONObject().put("remoteKind", "approval").toString(), now),
        ChatItem(2, "question", "Which colour for the cover?", JSONObject().put("options", JSONArray(listOf("Blue", "Green"))).put("helper", "h1").put("whyBy", "Design the cover").put("why", "a helper is waiting on this").toString(), now),
    )
    private val tasks = listOf(
        TaskRow(id = 10, title = "Book the venue", owner = "agent", status = "blocked", blocker = "The booking site wants your ID number", createdAt = now),
        TaskRow(id = 11, title = "Renew the car licence", dueAt = now + 3_600_000L, createdAt = now),
        TaskRow(id = 12, title = "Call the plumber about the geyser", createdAt = now + 1),
    )
    private fun state(i: List<ChatItem> = items, t: List<TaskRow> = tasks) = HomeState("Your agent", "Sam", AgentStatus(), i, emptyList(), t, emptyList(), now = now)

    private fun start(content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("w390dp-h844dp-xxhdpi")
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme(dark = dark) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { content() } } }
        rule.mainClock.advanceTimeBy(900)
    }
    private fun cap(name: String) {
        File("build/audit/390").mkdirs()
        rule.onRoot().captureRoboImage(File("build/audit/390/$name-${if (dark) "dark" else "light"}.png").path)
    }

    @Test fun threeMixedCards() { start { HomeScreen(state(), HomeActions()) }; cap("fxC-1-home-needs-you") }

    @Test fun cardSheet() {
        start { CompositionLocalProvider(LocalSheetPreview provides true) { HomeScreen(state(), HomeActions(), startSheet = "t11") } }
        cap("fxC-2-card-sheet")
    }

    @Test fun goneTarget() {
        start { HomeScreen(state(), HomeActions(), startGone = mapOf("q1" to Needs.HANDLED, "t12" to Needs.MISSING)) }
        cap("fxC-3-gone-target")
    }

    @Test fun nothingNeedsYou() { start { HomeScreen(state(emptyList(), emptyList()), HomeActions()) }; cap("fxC-4-nothing-needs-you") }

    @Test fun aTapOpensTheTaskAHoldOpensTheSheetAndASwipeClearsTheCard() {
        val opened = mutableListOf<NeedTarget>()
        val store = NeedStore()
        start { CompositionLocalProvider(LocalSheetPreview provides true) { HomeScreen(state(emptyList(), tasks.map { it.copy(dueAt = null) }), HomeActions(onNeed = { opened += it }, needs = store)) } }
        rule.onNodeWithText("Renew the car licence").performClick(); rule.mainClock.advanceTimeBy(500)
        assertEquals(listOf<NeedTarget>(NeedTarget.Task(11, agentTab = false)), opened)
        rule.onNodeWithText("Book the venue").performClick(); rule.mainClock.advanceTimeBy(500)
        assertEquals(NeedTarget.Task(10, agentTab = true), opened.last())
        // A hold opens the card's sheet, with the snooze choices.
        rule.onNodeWithText("Call the plumber about the geyser").performTouchInput { longClick() }; rule.mainClock.advanceTimeBy(900)
        rule.onNodeWithText("Not needed").assertExists()
        rule.onNodeWithText("Snooze for 1 hour").performClick(); rule.mainClock.advanceTimeBy(900)
        assertTrue(store.hidden.value.snoozed.containsKey("t12"))
        rule.onNodeWithText("Call the plumber about the geyser").assertDoesNotExist()
        // A swipe clears the card and remembers it.
        rule.onNodeWithText("Book the venue").performTouchInput { swipeLeft() }; rule.mainClock.advanceTimeBy(1500)
        assertEquals("The booking site wants your ID number", store.hidden.value.dismissed["b10"])
        rule.onNodeWithText("Book the venue").assertDoesNotExist()
    }
}
