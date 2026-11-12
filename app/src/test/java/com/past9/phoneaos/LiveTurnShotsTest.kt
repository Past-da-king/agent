package com.past9.phoneaos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.assist.AssistPrefs
import com.past9.phoneaos.assist.LiveActions
import com.past9.phoneaos.assist.LivePhase
import com.past9.phoneaos.assist.LiveSheetBody
import com.past9.phoneaos.assist.LiveView
import com.past9.phoneaos.ui.SheetShape
import com.past9.phoneaos.ui.screens.LiveTurnTaking
import com.past9.phoneaos.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Wait-for-me on the live sheet (Tap to talk, I'm done) and the turn-taking settings, light and dark at 390. PNGs in app/build/live-v2/. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class LiveTurnShotsTest(private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
    @get:Rule val rule = createComposeRule()
    private fun file(name: String) = File("build/live-v2/$name-${if (dark) "dark" else "light"}.png").also { it.parentFile?.mkdirs() }.path

    private fun show(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AppTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), shape = SheetShape, color = MaterialTheme.colorScheme.surfaceContainerLow) { content() }
                }
            }
        }
        rule.mainClock.advanceTimeBy(700)
    }

    @Test fun waitForMeShowsTapToTalkThenImDone() {
        var taps = 0
        show("x") { LiveSheetBody(LiveView(LivePhase.LISTENING, manual = true), "Nova", null, LiveActions(onTurn = { taps++ })) }
        rule.onNodeWithText("Tap to talk").assertIsDisplayed().performClick()
        assertEquals(1, taps)
        rule.onRoot().captureRoboImage(file("01-tap-to-talk"))
    }

    @Test fun whileHisTurnIsOpenTheButtonSaysImDone() {
        show("x") { LiveSheetBody(LiveView(LivePhase.LISTENING, manual = true, talking = true, you = "I want to book a table for"), "Nova", null, LiveActions()) }
        rule.onNodeWithText("I'm done").assertIsDisplayed()
        rule.onRoot().captureRoboImage(file("02-im-done"))
    }

    @Test fun withoutWaitForMeThereIsNoTurnButton() {
        show("x") { LiveSheetBody(LiveView(LivePhase.LISTENING), "Nova", null, LiveActions()) }
        rule.onAllNodesWithTextCount("Tap to talk", 0)
    }

    @Test fun theSettingsSavePatienceWaitForMeAndLanguage() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        AssistPrefs.setLivePatience(context, "patient"); AssistPrefs.setLiveWaitForMe(context, false); AssistPrefs.setLiveAutoLanguage(context, false)
        assertEquals("patient is the default", "patient", AssistPrefs.livePatience(context))
        show("x") { Column(Modifier.padding(20.dp)) { LiveTurnTaking(enabled = true) } }
        rule.onNodeWithText("How Live takes turns").assertIsDisplayed()
        rule.onNodeWithText("Very patient").performClick()
        assertEquals("very", AssistPrefs.livePatience(context))
        rule.onNodeWithText("Quick").performClick()
        assertEquals("quick", AssistPrefs.livePatience(context))
        // The two switches are the last two toggleable nodes (the chips are selectable, not toggleable).
        val switches = rule.onAllNodes(androidx.compose.ui.test.isToggleable()).fetchSemanticsNodes().size
        assertTrue("two switches", switches >= 2)
        rule.onAllNodes(androidx.compose.ui.test.isToggleable())[switches - 2].performClick()
        assertTrue(AssistPrefs.liveWaitForMe(context))
        rule.onAllNodes(androidx.compose.ui.test.isToggleable())[switches - 1].performClick()
        assertTrue(AssistPrefs.liveAutoLanguage(context))
        val t = AssistPrefs.liveTuning(context, listOf("Nova"))
        assertTrue(t.waitForMe && t.autoLanguage && t.vocabulary == listOf("Nova"))
        rule.onRoot().captureRoboImage(file("03-settings"))
        AssistPrefs.setLivePatience(context, "patient"); AssistPrefs.setLiveWaitForMe(context, false); AssistPrefs.setLiveAutoLanguage(context, false)
        assertFalse(AssistPrefs.liveWaitForMe(context))
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String, expected: Int) {
        assertEquals(expected, onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size)
    }
}
