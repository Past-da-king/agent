package com.past9.phoneaos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.assist.AssistRole
import com.past9.phoneaos.assist.LiveActions
import com.past9.phoneaos.assist.LivePhase
import com.past9.phoneaos.assist.LiveSheetBody
import com.past9.phoneaos.assist.LiveView
import com.past9.phoneaos.assist.ScreenLine
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.ui.LocalSheetPreview
import com.past9.phoneaos.ui.SheetShape
import com.past9.phoneaos.ui.screens.AssistantSheet
import com.past9.phoneaos.ui.theme.AppTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The live voice sheet in every state, and the switch that turns it on, at 390 wide (360 below), light and dark. PNGs in app/build/live/<width>/. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
open class LiveShotsTest(private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
    @get:Rule val rule = createComposeRule()
    open val outDir: String get() = "build/live/390"
    private fun file(name: String) = File("$outDir/$name-${if (dark) "dark" else "light"}.png").also { it.parentFile?.mkdirs() }.path

    private fun sheet(name: String, view: LiveView, screen: ScreenLine? = ScreenLine("Reading the screen: Notes", true), actions: LiveActions = LiveActions(), after: () -> Unit = {}) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AppTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)))
                    Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), shape = SheetShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        LiveSheetBody(view, "Nova", screen, actions)
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(700)
        after()
        rule.onRoot().captureRoboImage(file(name))
    }

    private fun row(id: Long, kind: String, text: String, meta: JSONObject = JSONObject()) = ChatItem(id = id, kind = kind, text = text, meta = meta.toString())

    @Test fun connecting() = sheet("01-connecting", LiveView(LivePhase.CONNECTING)) { rule.onNodeWithText("Connecting").assertIsDisplayed() }

    @Test fun listening() = sheet("02-listening", LiveView(LivePhase.LISTENING)) {
        rule.onNodeWithText("Go ahead, just talk").assertIsDisplayed()
        rule.onNodeWithText("LIVE").assertIsDisplayed()
        rule.onNodeWithContentDescription("End").assertIsDisplayed()
        rule.onNodeWithContentDescription("Mute").assertIsDisplayed()
    }

    @Test fun speakingWithCaption() = sheet("03-speaking", LiveView(LivePhase.SPEAKING, you = "What is the capital of Australia?", agent = "Canberra. Not Sydney, which most people guess.")) {
        rule.onNodeWithText("Canberra. Not Sydney, which most people guess.").assertIsDisplayed()
    }

    @Test fun workingOnAHandOff() = sheet("04-working", LiveView(LivePhase.WORKING, you = "Send this to Sam and say I'm late", agent = "On it.", label = "Sending a message")) {
        rule.onNodeWithText("Sending a message").assertIsDisplayed()
    }

    @Test fun approvalCardInTheSheet() {
        var answered: Pair<Long, String>? = null
        val item = row(12, "question", "APPROVAL|Send a WhatsApp message to Sam|Running ten minutes late, start without me.", JSONObject().put("options", JSONArray(listOf("Approve", "Decline"))))
        sheet("05-approval", LiveView(LivePhase.NEEDS_YOU, you = "Tell Sam I'm late", agent = "I need your OK to message Sam. Hold the button to approve.", card = item), actions = LiveActions(onAnswer = { id, o -> answered = id to o })) {
            rule.onNodeWithText("Hold to approve").assertIsDisplayed()
            rule.onNodeWithText("Decline").performClick()
        }
        assertEquals(12L to "Decline", answered)
    }

    @Test fun muted() = sheet("06-muted", LiveView(LivePhase.LISTENING, muted = true)) { rule.onNodeWithContentDescription("Unmute").assertIsDisplayed() }

    @Test fun stopped() = sheet("07-error", LiveView(LivePhase.ERROR, problem = "The Google key was refused. Check it in Settings, Voice, then try again.")) {
        rule.onNodeWithText("The Google key was refused. Check it in Settings, Voice, then try again.").assertIsDisplayed()
    }

    @Test fun endAndMuteAreTapTargets() {
        var ended = 0; var muteTo: Boolean? = null
        sheet("08-listening-actions", LiveView(LivePhase.LISTENING), actions = LiveActions(onEnd = { ended++ }, onMute = { muteTo = it })) {
            rule.onNodeWithContentDescription("Mute").performClick()
            rule.onNodeWithContentDescription("End").performClick()
        }
        assertEquals(1, ended); assertEquals(true, muteTo)
    }

    private fun guide(name: String, live: Boolean) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AppTheme(dark = dark) {
                CompositionLocalProvider(LocalSheetPreview provides true) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        AssistantSheet("Nova", onDismiss = {}, held = true, readScreen = true, readPicture = false, steps = AssistRole.guide("Agent", manufacturer = "samsung", sdk = 36), liveVoice = live)
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1200)
        rule.onRoot().captureRoboImage(file(name))
    }

    @Test fun theSwitchIsInTheAssistantSheetAndOffByDefault() {
        guide("09-switch-off", live = false)
        rule.onNodeWithText("Live voice in the assistant").assertExists()
        assertTrue(!com.past9.phoneaos.assist.AssistPrefs.liveVoice(androidx.test.core.app.ApplicationProvider.getApplicationContext()))
    }

    @Test fun theSwitchOn() { guide("10-switch-on", live = true); rule.onNodeWithText("Live voice in the assistant").assertExists() }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class LiveShots360Test(dark: Boolean) : LiveShotsTest(dark) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
    override val outDir: String get() = "build/live/360"
}
