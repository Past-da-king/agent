package com.past9.phoneaos

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
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
 * refused address, removing, and the call voice switch. PNGs land in app/build/design/.
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
    private class Fake(var list: List<String> = IsolatedOrigins.DEFAULT, var voice: Boolean = false) {
        val actions = BrowserSettingsActions(
            origins = { list },
            addOrigin = { typed -> IsolatedOrigins.add(list, typed).let { (l, why) -> list = l; why } },
            removeOrigin = { o -> list = IsolatedOrigins.remove(list, o) },
            callVoiceOn = { voice }, setCallVoiceOn = { voice = it },
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
        rule.onNode(isToggleable()).assertIsOff()
        cap("default")
    }

    @Test fun addingASiteListsItAndClearsTheField() {
        val f = Fake(); show(f)
        rule.onNode(hasSetTextAction()).performTextInput("Stackblitz.com/"); tick()
        rule.onNodeWithText("Add").performClick(); tick()
        assertEquals(listOf("https://web.whatsapp.com", "https://stackblitz.com"), f.list)
        rule.onNodeWithText("stackblitz.com").assertExists()
        rule.onNodeWithText("Stackblitz.com/").assertDoesNotExist()
        cap("added")
    }

    @Test fun aRefusedAddressSaysWhyAndIsNotListed() {
        val f = Fake(); show(f)
        rule.onNode(hasSetTextAction()).performTextInput("http://example.com"); tick()
        rule.onNodeWithText("Add").performClick(); tick()
        rule.onNodeWithText("Only https sites can be added.").assertExists()
        assertEquals(IsolatedOrigins.DEFAULT, f.list)
        cap("refused")
        // Typing again takes the message away.
        rule.onNode(hasSetTextAction()).performTextInput("s"); tick()
        rule.onNodeWithText("Only https sites can be added.").assertDoesNotExist()
    }

    @Test fun aWildcardIsRefused() {
        val f = Fake(); show(f)
        rule.onNode(hasSetTextAction()).performTextInput("https://*.example.com"); tick()
        rule.onNodeWithText("Add").performClick(); tick()
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
        rule.onNode(isToggleable()).performScrollTo().performClick(); tick()
        assertTrue(f.voice)
        rule.onNode(isToggleable()).assertIsOn()
        rule.onNodeWithText("its voice goes into the call", substring = true).assertExists()
        cap("voice-on")
    }
}
