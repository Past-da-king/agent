package com.past9.phoneaos.wakeword

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.ui.LocalSheetPreview
import com.past9.phoneaos.ui.screens.WakeWordActions
import com.past9.phoneaos.ui.screens.WakeWordSheetContent
import com.past9.phoneaos.ui.screens.WakeWordView
import com.past9.phoneaos.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Settings > Wake word at 390 and 360 wide, light and dark: switched off as a new person finds it, and switched on
 * with a taught name and a test hit showing. PNGs land in app/build/design/wake-word-*.png. Tall on purpose, so the
 * whole sheet is in one picture.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class WakeWordSheetShotTest(private val width: Int, private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "w={0} dark={1}")
        fun params() = listOf(arrayOf<Any>(390, false), arrayOf<Any>(390, true), arrayOf<Any>(360, false), arrayOf<Any>(360, true))
    }
    @get:Rule val rule = createComposeRule()

    private fun show(v: WakeWordView, a: WakeWordActions = WakeWordActions()) {
        RuntimeEnvironment.setQualifiers("w${width}dp-h1500dp-xxhdpi")
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AppTheme(dark = dark) {
                CompositionLocalProvider(LocalSheetPreview provides true) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { WakeWordSheetContent(v, a) {} }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1200)
    }
    private fun shot(name: String) = rule.onRoot().captureRoboImage(File("build/design/wake-word-$name-$width-${if (dark) "dark" else "light"}.png").path)

    @Test fun offAsANewPersonFindsIt() {
        show(WakeWordView(defaultPhrase = "Hey Nova", latin = "hey nova", micAllowed = false, onTop = false))
        rule.onNodeWithText("Wake word").assertIsDisplayed()
        rule.onNodeWithText("Off").assertIsDisplayed()
        // The phrase is the agent's name, and the sheet says it follows it.
        rule.onNodeWithText("Say \"Hey Nova\" and your agent opens", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Follows your agent's name", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Sound never leaves it", substring = true).assertExists()
        shot("off")
    }

    @Test fun onWithATaughtNameAndATestHit() {
        val learned = listOf(listOf("▁HE", "Y", "▁TA", "ND", "Y"))
        show(WakeWordView(
            settings = WakeWordSettings(enabled = true, sensitivity = Sensitivity.MEDIUM, learned = learned, learnedFor = WakeWordSettings.key("Hey Thandi")),
            defaultPhrase = "Hey Thandi", latin = "hey thandi", live = WakeLive(WakeState.Listening), trying = true, tryHits = 1, hearing = true))
        rule.onNodeWithText("Listening now").assertIsDisplayed()
        rule.onNodeWithText("Heard it").assertIsDisplayed()
        rule.onNodeWithText("Learned from your voice: \"hey tandy\"").assertExists()
        shot("on")
    }

    @Test fun aShortPhraseIsCalledOutAndAPauseIsExplained() {
        show(WakeWordView(settings = WakeWordSettings(enabled = true, customPhrase = "Nova", sensitivity = Sensitivity.LOW, pauseLowBattery = true),
            defaultPhrase = "Hey Agent", latin = "nova", live = WakeLive(WakeState.Paused(WakePause.BATTERY_SAVER)), teachStep = 2, teachSaid = "Heard \"nova\""))
        rule.onNodeWithText(WakePause.BATTERY_SAVER.line).assertIsDisplayed()
        rule.onNodeWithText("Three or more beats", substring = true).assertExists()
        rule.onNodeWithText("Say it now: 2 of 3").assertExists()
        shot("paused")
    }

    @Test fun everyControlReportsWhatWasTapped() {
        val got = ArrayList<String>()
        show(WakeWordView(defaultPhrase = "Hey Nova", latin = "hey nova"), WakeWordActions(
            onEnabled = { got += "enabled=$it" }, onSensitivity = { got += "sens=$it" }, onTry = { got += "try=$it" }, onTeach = { got += "teach" },
            onPauseLowBattery = { got += "low=$it" }, onOnlyCharging = { got += "charging=$it" }, onPauseOnSaver = { got += "saver=$it" }))
        rule.onNodeWithText("Easy").performScrollTo().performClick()
        rule.onNodeWithText("Say it three times").performScrollTo().performClick()
        rule.mainClock.advanceTimeBy(300)
        assertEquals(listOf("sens=HIGH", "teach"), got)
    }
}
