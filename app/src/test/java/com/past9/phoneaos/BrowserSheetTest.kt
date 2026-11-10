package com.past9.phoneaos

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.browser.CallTestAccounts
import com.past9.phoneaos.browser.IsolatedOrigins
import com.past9.phoneaos.ui.LocalSheetPreview
import com.past9.phoneaos.ui.screens.BrowserSettingsActions
import com.past9.phoneaos.ui.screens.BrowserSheet
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

/**
 * Settings > Browser > Sites that need advanced browser features, at the A35's size, light and dark: adding, a
 * refused address, removing, the call voice switch, and calling for a test account (off, its warning, a number
 * marked, the confirmation in the sheet). PNGs land in app/build/design/.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class BrowserSheetTest(private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
    @get:Rule val rule = createComposeRule()

    /** The settings store's rules without the store: the sheet only ever talks to these five functions. */
    private class Fake(var list: List<String> = IsolatedOrigins.DEFAULT, var voice: Boolean = false, var numbers: List<String> = emptyList(), var testOn: Boolean = false) {
        val actions = BrowserSettingsActions(
            origins = { list },
            addOrigin = { typed -> IsolatedOrigins.add(list, typed).let { (l, why) -> list = l; why } },
            removeOrigin = { o -> list = IsolatedOrigins.remove(list, o) },
            callVoiceOn = { voice }, setCallVoiceOn = { voice = it },
            testNumbers = { numbers },
            addTestNumber = { typed -> CallTestAccounts.add(numbers, typed).let { (l, why) -> numbers = l; why } },
            removeTestNumber = { n -> numbers = CallTestAccounts.remove(numbers, n); if (numbers.isEmpty()) testOn = false },
            testCallingOn = { testOn && numbers.isNotEmpty() }, setTestCallingOn = { on -> if (!on || numbers.isNotEmpty()) testOn = on },
        )
    }

    private fun show(f: Fake) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AppTheme(dark = dark) {
                CompositionLocalProvider(LocalSheetPreview provides true) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { BrowserSheet(f.actions) {} }
                }
            }
        }
        tick()
    }
    /** A press is only over, and what it changed only drawn, once the looper has run and frames have passed. */
    private fun tick() { rule.mainClock.advanceTimeBy(1200); rule.waitForIdle(); rule.mainClock.advanceTimeBy(600); rule.waitForIdle() }
    private fun cap(name: String) = rule.onRoot().captureRoboImage(File("build/design/browser-sheet-$name-${if (dark) "dark" else "light"}.png").path)

    @Test fun aFreshInstallShowsWhatsAppWebAndTheVoiceOff() {
        show(Fake())
        rule.onNodeWithText("Sites that need advanced browser features").assertExists()
        rule.onNodeWithText("web.whatsapp.com").assertExists()
        rule.onNodeWithText("Changes apply to new tabs.", substring = true).assertExists()
        rule.onNodeWithText("Let the agent speak in WhatsApp calls").assertExists()
        rule.onNodeWithTag("call-voice").assertIsOff()
        cap("default")
    }

    @Test fun addingASiteListsItAndClearsTheField() {
        val f = Fake(); show(f)
        site().performTextInput("Stackblitz.com/"); tick()
        addSite().performClick(); tick()
        assertEquals(listOf("https://web.whatsapp.com", "https://stackblitz.com"), f.list)
        rule.onNodeWithText("stackblitz.com").assertExists()
        rule.onNodeWithText("Stackblitz.com/").assertDoesNotExist()
        cap("added")
    }

    @Test fun aRefusedAddressSaysWhyAndIsNotListed() {
        val f = Fake(); show(f)
        site().performTextInput("http://example.com"); tick()
        addSite().performClick(); tick()
        rule.onNodeWithText("Only https sites can be added.").assertExists()
        assertEquals(IsolatedOrigins.DEFAULT, f.list)
        cap("refused")
        // Typing again takes the message away.
        site().performTextInput("s"); tick()
        rule.onNodeWithText("Only https sites can be added.").assertDoesNotExist()
    }

    @Test fun aWildcardIsRefused() {
        val f = Fake(); show(f)
        site().performTextInput("https://*.example.com"); tick()
        addSite().performClick(); tick()
        rule.onNodeWithText("No wildcards", substring = true).assertExists()
        assertEquals(IsolatedOrigins.DEFAULT, f.list)
    }

    @Test fun removingTheLastSiteLeavesAnEmptyListThatSaysSo() {
        val f = Fake(); show(f)
        rule.onNodeWithContentDescription("Remove web.whatsapp.com").performClick(); tick()
        assertTrue(f.list.isEmpty())
        rule.onNodeWithText("No sites yet. Add one below.").assertExists()
        cap("empty")
    }

    @Test fun theVoiceSwitchTurnsOnAndSaysWhatItDoes() {
        val f = Fake(list = listOf("https://web.whatsapp.com", "https://stackblitz.com", "https://chat.webllm.ai")); show(f)
        assertFalse(f.voice)
        rule.onNodeWithTag("call-voice").performScrollTo().performClick(); tick()
        assertTrue(f.voice)
        rule.onNodeWithTag("call-voice").assertIsOn()
        rule.onNodeWithText("its voice goes into the call", substring = true).assertExists()
        // The voice switch does not turn calling for a test account on with it.
        rule.onNodeWithTag("test-calling").assertIsOff(); assertFalse(f.testOn)
        cap("voice-on")
    }

    // ---- calling for a test account ----

    private fun site() = rule.onAllNodes(hasSetTextAction())[0]
    private fun addSite() = rule.onAllNodesWithText("Add")[0]
    private fun number() = rule.onAllNodes(hasSetTextAction())[1]
    private fun addNumber() = rule.onNodeWithContentDescription("Add test number")
    private fun testSwitch() = rule.onNodeWithTag("test-calling")
    /**
     * To the bottom of the sheet, where the test account card is. The clock here only moves when the test moves it,
     * and performScrollTo waits for a scroll that needs the clock: so the scroll is asked for, then time is passed.
     */
    private fun down() { rule.onAllNodes(hasScrollAction())[0].performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 6000f) }; tick(); tick() }

    @Test fun callingForATestAccountIsOffAndItsWarningIsAlwaysInView() {
        show(Fake()); down()
        rule.onNodeWithText("Turn on calling for a test account").assertIsDisplayed()
        testSwitch().assertIsOff()
        rule.onNodeWithText(CallTestAccounts.WARNING).assertIsDisplayed()
        rule.onNodeWithText("could get the number banned", substring = true).assertExists()
        rule.onNodeWithText("never your main number", substring = true).assertExists()
        cap("test-off")
    }

    @Test fun theSwitchWillNotTurnOnBeforeANumberIsMarked() {
        val f = Fake(); show(f); down()
        testSwitch().performClick(); tick()
        assertFalse(f.testOn); testSwitch().assertIsOff()
        rule.onNodeWithText("Add the test account's number first.").assertExists()
        rule.onNodeWithText("Yes, turn it on").assertDoesNotExist()
    }

    @Test fun aNumberIsMarkedThenConfirmedInTheSheetBeforeTheSwitchTurnsOn() {
        val f = Fake(); show(f); down()
        number().performTextInput("+1 555 0100"); tick()
        addNumber().performClick(); tick(); down()
        assertEquals(listOf("15550100"), f.numbers); assertFalse("marking a number turns nothing on", f.testOn)
        rule.onNodeWithText("+15550100").assertExists()
        testSwitch().performClick(); tick(); down()
        // Not on yet: the number is read back and has to be confirmed, in the sheet itself.
        assertFalse(f.testOn); testSwitch().assertIsOff()
        rule.onNodeWithText("Turn it on for +15550100? Confirm that this is a test number, not your main number.").assertExists()
        rule.onNodeWithText("Yes, turn it on").assertIsDisplayed()
        cap("test-confirm")
        rule.onNodeWithText("Cancel").performClick(); tick()
        assertFalse(f.testOn); rule.onNodeWithText("Yes, turn it on").assertDoesNotExist()
        testSwitch().performClick(); tick(); down()
        rule.onNodeWithText("Yes, turn it on").performClick(); tick(); down()
        assertTrue(f.testOn); testSwitch().assertIsOn()
        rule.onNodeWithText("Never for any other number.", substring = true).assertExists()
        rule.onNodeWithText(CallTestAccounts.WARNING).assertIsDisplayed()
        cap("test-on")
        // Off again is one tap, no questions.
        testSwitch().performClick(); tick()
        assertFalse(f.testOn); testSwitch().assertIsOff()
    }

    @Test fun aNumberWithoutItsCountryCodeIsRefusedAndRemovingTheLastNumberTurnsTheSwitchOff() {
        val f = Fake(numbers = listOf("15550100"), testOn = true); show(f); down()
        testSwitch().assertIsOn()
        number().performTextInput("0555 010 0100"); tick()
        addNumber().performClick(); tick()
        rule.onNodeWithText("country code", substring = true).assertExists()
        assertEquals(listOf("15550100"), f.numbers)
        rule.onNodeWithContentDescription("Remove test number +15550100").performClick(); tick()
        assertTrue(f.numbers.isEmpty()); assertFalse(f.testOn); testSwitch().assertIsOff()
    }
}
