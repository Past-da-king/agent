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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.assist.AskChoice
import com.past9.phoneaos.assist.AssistActions
import com.past9.phoneaos.assist.AssistContext
import com.past9.phoneaos.assist.AssistPhase
import com.past9.phoneaos.assist.AssistSheetBody
import com.past9.phoneaos.assist.AssistView
import com.past9.phoneaos.assist.CtxAgent
import com.past9.phoneaos.assist.CtxAsk
import com.past9.phoneaos.assist.LiveActions
import com.past9.phoneaos.assist.LivePhase
import com.past9.phoneaos.assist.LiveSheetBody
import com.past9.phoneaos.assist.LiveView
import com.past9.phoneaos.assist.ScreenLine
import com.past9.phoneaos.ui.LocalSheetPreview
import com.past9.phoneaos.ui.SheetShape
import com.past9.phoneaos.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The assistant's "what's going on" strip, the one-line confirmation of where an answer went, and the slide-up choice
 * sheet, in the typed sheet and in Live voice, at 390 wide (360 in [AssistContextShots360Test]), light and dark.
 * PNGs land in app/build/assist-context/<width>/.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
open class AssistContextShotsTest(private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
    @get:Rule val rule = createComposeRule()
    open val outDir: String get() = "build/assist-context/390"
    private fun file(name: String) = File("$outDir/$name-${if (dark) "dark" else "light"}.png").also { it.parentFile?.mkdirs() }.path

    private val pairing = CtxAsk(1, "A1", false, "What is the pairing code shown on the watch?", emptyList(), "Watch helper")
    private val bank = CtxAsk(2, "A2", false, "What is the verification code from the bank email?", emptyList(), "Mail helper")
    private val agents = listOf(CtxAgent("Watch helper", "helper", CtxAgent.WAITING, "Waiting for the pairing code"), CtxAgent("Report", "helper", CtxAgent.WORKING, "Writing the summary"))
    private val one = AssistContext(asks = listOf(pairing), agents = agents)
    private val several = AssistContext(asks = listOf(pairing, bank), agents = agents)
    private val busy = AssistContext(agents = listOf(CtxAgent("Report", "helper", CtxAgent.WORKING, "Writing the summary"), CtxAgent("project Payroll", "project", CtxAgent.WORKING, "Checking hours")))

    private fun typed(name: String, view: AssistView, ctx: AssistContext?, choice: AskChoice? = null, after: () -> Unit = {}) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AppTheme(dark = dark) {
                CompositionLocalProvider(LocalSheetPreview provides (choice != null)) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)))
                        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), shape = SheetShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            AssistSheetBody(view, "Nova", ScreenLine("Reading the screen: Notes", true), AssistActions(), context = ctx, choice = choice)
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(700)
        after()
        rule.onRoot().captureRoboImage(file(name))
    }

    private fun live(name: String, view: LiveView, actions: LiveActions = LiveActions(), after: () -> Unit = {}) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AppTheme(dark = dark) {
                CompositionLocalProvider(LocalSheetPreview provides (view.choice != null)) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)))
                        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), shape = SheetShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            LiveSheetBody(view, "Nova", ScreenLine("Reading the screen: Notes", true), actions)
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(700)
        after()
        rule.onRoot().captureRoboImage(file(name))
    }

    @Test fun typedOneQuestionWaiting() = typed("01-typed-one-waiting", AssistView(AssistPhase.LISTENING), one) {
        rule.onNodeWithText("Watch helper: What is the pairing code shown on the watch?").assertIsDisplayed()
    }

    @Test fun typedSeveralWaitingOpened() = typed("02-typed-several-waiting", AssistView(AssistPhase.TYPING), several) {
        rule.onNodeWithText("Waiting on you: 2").assertIsDisplayed()
        rule.onNodeWithText("Waiting on you: 2").performClick()
        rule.mainClock.advanceTimeBy(1500)
        rule.onNodeWithText("Mail helper: What is the verification code from the bank email?").assertIsDisplayed()
    }

    @Test fun typedOnlyWorking() = typed("03-typed-working-only", AssistView(AssistPhase.LISTENING), busy) {
        rule.onNodeWithText("At work: 2").assertIsDisplayed()
    }

    @Test fun typedNothingGoingOnShowsNoStrip() = typed("04-typed-nothing", AssistView(AssistPhase.LISTENING), AssistContext())

    @Test fun typedRoutedConfirmation() = typed("05-typed-routed", AssistView(AssistPhase.ANSWERING, heard = "482917", answer = "Sent to Watch helper: 482917"), one) {
        rule.onNodeWithText("Sent to Watch helper: 482917").assertIsDisplayed()
    }

    @Test fun typedChoiceSheet() = typed("06-typed-choice", AssistView(AssistPhase.SENDING, heard = "482917"), several, AskChoice("482917", several.asks)) {
        rule.onNodeWithText("Which one is it for?").assertIsDisplayed()
        rule.onNodeWithText("Mail helper").assertIsDisplayed()
    }

    @Test fun liveStartsKnowingWhatWaits() = live("07-live-listening", LiveView(LivePhase.LISTENING, context = one)) {
        rule.onNodeWithText("Watch helper: What is the pairing code shown on the watch?").assertIsDisplayed()
    }

    @Test fun liveRoutedConfirmation() = live("08-live-routed", LiveView(LivePhase.LISTENING, you = "It is four eight two nine one seven", agent = "Sent the code to the watch helper.", routed = "Sent to Watch helper: 482917", context = AssistContext(agents = agents))) {
        rule.onNodeWithText("Sent to Watch helper: 482917").assertIsDisplayed()
    }

    @Test fun liveChoiceSheet() {
        var picked: Long? = null
        live("09-live-choice", LiveView(LivePhase.LISTENING, you = "482917", context = several, choice = AskChoice("482917", several.asks)), LiveActions(onPickAsk = { k, _ -> picked = k.id })) {
            rule.onNodeWithText("Which one is it for?").assertIsDisplayed()
            rule.onNodeWithText("Watch helper").performClick()
        }
        assertEquals(1L, picked)
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class AssistContextShots360Test(dark: Boolean) : AssistContextShotsTest(dark) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
    override val outDir: String get() = "build/assist-context/360"
}
