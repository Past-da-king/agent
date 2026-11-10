package com.past9.phoneaos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.assist.AssistActions
import com.past9.phoneaos.assist.AssistPhase
import com.past9.phoneaos.assist.AssistRole
import com.past9.phoneaos.assist.AssistSheetBody
import com.past9.phoneaos.assist.AssistView
import com.past9.phoneaos.assist.ScreenLine
import com.past9.phoneaos.data.AgentSettings
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.ui.LocalSheetPreview
import com.past9.phoneaos.ui.SheetShape
import com.past9.phoneaos.ui.screens.AssistantSheet
import com.past9.phoneaos.ui.screens.RuntimeInfo
import com.past9.phoneaos.ui.screens.SettingsActions
import com.past9.phoneaos.ui.screens.SettingsScreen
import com.past9.phoneaos.ui.theme.AppTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The assist sheet in every state, the Settings row and its guide sheet, at 390 wide (and again at 360 in
 * [AssistShots360Test]), light and dark. PNGs land in app/build/assist/<width>/.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
open class AssistShotsTest(private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
    @get:Rule val rule = createComposeRule()
    open val outDir: String get() = "build/assist/390"

    private fun file(name: String) = File("$outDir/$name-${if (dark) "dark" else "light"}.png").also { it.parentFile?.mkdirs() }.path

    /** The sheet as the activity draws it: at the bottom, over a scrim, with another app's screen behind. */
    private fun sheet(name: String, view: AssistView, screen: ScreenLine? = null, actions: AssistActions = AssistActions(), after: () -> Unit = {}) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AppTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)))
                    Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), shape = SheetShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        AssistSheetBody(view, "Nova", screen, actions)
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(700)
        after()
        rule.onRoot().captureRoboImage(file(name))
    }

    private fun row(id: Long, kind: String, text: String, meta: JSONObject = JSONObject()) = ChatItem(id = id, kind = kind, text = text, meta = meta.toString())

    @Test fun listeningEmpty() = sheet("01-listening", AssistView(AssistPhase.LISTENING), ScreenLine("Not reading the screen", false)) {
        rule.onNodeWithText("Go ahead, I'm listening").assertIsDisplayed()
        rule.onNodeWithContentDescription("Done speaking").assertIsDisplayed()
        rule.onNodeWithContentDescription("Type instead").assertIsDisplayed()
        rule.onNodeWithContentDescription("Open the app").assertIsDisplayed()
    }

    @Test fun listeningWithWords() = sheet("02-listening-words", AssistView(AssistPhase.LISTENING, heard = "Send this to Sam and tell him I'll be"), ScreenLine("Reading the screen: Notes", true)) {
        rule.onNodeWithText("Reading the screen: Notes").assertIsDisplayed()
    }

    @Test fun bankingAppIsNotRead() = sheet("03-listening-banking", AssistView(AssistPhase.LISTENING, heard = "What is this"), ScreenLine("I don't read banking apps", false)) {
        rule.onNodeWithText("I don't read banking apps").assertIsDisplayed()
    }

    @Test fun typing() = sheet("04-typing", AssistView(AssistPhase.TYPING, problem = "I didn't catch anything. Tap the mic to try again, or type."), ScreenLine("Not reading the screen", false)) {
        rule.onNodeWithText("Ask or tell Nova").assertIsDisplayed()
    }

    @Test fun thinking() = sheet("05-thinking", AssistView(AssistPhase.THINKING, heard = "What is on my list for today?")) {
        rule.onNodeWithContentDescription("Stop").assertIsDisplayed()
    }

    @Test fun acting() = sheet("06-acting", AssistView(AssistPhase.ACTING, heard = "Add milk to my list", label = "Adding a to-do"))

    @Test fun answering() = sheet("07-answer", AssistView(AssistPhase.ANSWERING, heard = "What is on my list for today?",
        answer = "Three things today:\n- Call the dentist\n- Send the invoice\n- Pick up the parcel before **17:00**", answerId = 9)) {
        rule.onNodeWithContentDescription("Speak").assertIsDisplayed()
    }

    @Test fun approvalInTheSheet() {
        var answered: Pair<Long, String>? = null
        val item = row(12, "question", "APPROVAL|Send a WhatsApp message to Sam|Running ten minutes late, start without me.", JSONObject().put("options", JSONArray(listOf("Approve", "Decline"))))
        sheet("08-approval", AssistView(AssistPhase.APPROVAL, heard = "Tell Sam I'm running ten minutes late", card = item), actions = AssistActions(onAnswer = { id, o -> answered = id to o })) {
            rule.onNodeWithText("Hold to approve").assertIsDisplayed()
            rule.onNodeWithText("Send a WhatsApp message to Sam").assertIsDisplayed()
            // Declining is one tap; approving is never one tap.
            rule.onNodeWithText("Decline").performClick()
        }
        assertEquals(12L to "Decline", answered)
    }

    @Test fun error() = sheet("09-error", AssistView(AssistPhase.ERROR, heard = "What's the weather?", problem = "Your AI key was refused. Open Settings and paste a new one."))

    @Test fun locked() = sheet("10-locked", AssistView(AssistPhase.LOCKED, heard = "Read my last message")) {
        rule.onNodeWithText("Unlock").assertIsDisplayed()
    }

    private fun guide(name: String, held: Boolean, readScreen: Boolean) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AppTheme(dark = dark) {
                CompositionLocalProvider(LocalSheetPreview provides true) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        AssistantSheet("Nova", onDismiss = {}, held = held, readScreen = readScreen, readPicture = false, steps = AssistRole.guide("Agent", manufacturer = "samsung", sdk = 36))
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1200)
        rule.onRoot().captureRoboImage(file(name))
    }

    @Test fun guideWhenOff() {
        guide("11-guide-off", held = false, readScreen = false)
        rule.onNodeWithText("Open Settings").assertIsDisplayed()
        rule.onNodeWithText("Make Nova this phone's assistant").assertIsDisplayed()
    }

    @Test fun guideWhenOn() {
        guide("12-guide-on", held = true, readScreen = true)
        rule.onNodeWithText("Try it now").assertIsDisplayed()
    }

    @Test fun settingsHasTheDefaultAssistantRow() {
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme(dark = dark) { SettingsScreen(AgentSettings(onboarded = true, agentName = "Nova"), RuntimeInfo(false, 0, false, ""), "0.0.0", SettingsActions()) } }
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithText("Default assistant").assertExists()
        // Not the phone's assistant in a test: the row says off and how to turn it on.
        rule.onNodeWithText(AssistRole.line(false)).assertExists()
        // Bring the Voice section up the screen for the picture.
        rule.onRoot().performTouchInput { swipeUp(startY = height * 0.7f, endY = height * 0.45f, durationMillis = 300) }
        rule.mainClock.advanceTimeBy(1500)
        rule.onNodeWithText("Default assistant").assertIsDisplayed()
        rule.onRoot().captureRoboImage(file("13-settings-row"))
    }
}

/** The same shots on a narrow phone (360dp wide). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class AssistShots360Test(dark: Boolean) : AssistShotsTest(dark) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
    override val outDir: String get() = "build/assist/360"
}
