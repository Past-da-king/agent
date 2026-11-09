package com.past9.phoneaos

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.ui.screens.CallScreen
import com.past9.phoneaos.ui.theme.AppTheme
import com.past9.phoneaos.voice.CallCore
import com.past9.phoneaos.voice.CallState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The call screen in every state, at 390 and 360 wide, light and dark: build/callshots/<width>/<name>-light|dark.png. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CallShotsTest(private val width: Int, private val height: Int, private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "w{0}-dark={2}")
        fun params() = listOf(arrayOf<Any>(390, 844, false), arrayOf<Any>(390, 844, true), arrayOf<Any>(360, 780, false), arrayOf<Any>(360, 780, true))
    }
    @get:Rule val rule = createComposeRule()

    private fun shot(name: String, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("w${width}dp-h${height}dp-xxhdpi")
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme(dark = dark) { content() } }
        rule.mainClock.advanceTimeBy(900)
        File("build/callshots/$width").mkdirs()
        rule.onRoot().captureRoboImage(File("build/callshots/$width/$name-${if (dark) "dark" else "light"}.png").path)
    }

    private val talk = listOf(
        "in" to "Can you find the three cheapest flights from Joburg to Durban on Friday morning and hold the earliest one for me",
        "out" to "I'm on it. Give me a moment while I check the airlines.",
        "in" to "Window seat if there is one",
        "out" to "FlySafair at five past six is the cheapest at eight hundred and ninety nine rand, and it still has window seats. Lift at half past seven is a little more. Want me to hold the early one?",
        "in" to "Yes, the early one",
    )

    @Test fun connecting() = shot("c1-connecting") { CallScreen("Hina", CallState(active = true, phase = "connecting"), {}, {}, {}) }
    @Test fun listeningWithCaptions() = shot("c2-listening-captions") { CallScreen("Hina", CallState(active = true, phase = "listening", mine = 0.4f, lines = talk, route = "SPEAKER"), {}, {}, {}) }
    @Test fun speaking() = shot("c3-speaking") { CallScreen("Hina", CallState(active = true, phase = "speaking", theirs = 0.7f, lines = talk.take(4), route = "BLUETOOTH",
        delegated = "Find the three cheapest flights from Joburg to Durban", reply = "FlySafair 06:05 is the cheapest at R 899. Lift 07:30 is R 1,020 and FlySafair 09:40 is R 1,049. All three still have window seats."),
        {}, {}, {}, actions = listOf("Searched memory", "Opened FlySafair")) }
    @Test fun thinkingMuted() = shot("c4-thinking-muted") { CallScreen("Hina the Extraordinarily Long Named Agent", CallState(active = true, phase = "thinking", muted = true, lines = talk.take(2), route = "EARPIECE", delegated = "Hold the 06:05"), {}, {}, {}) }
    @Test fun reconnecting() = shot("c5-reconnecting") { CallScreen("Hina", CallState(active = true, phase = "reconnecting", lines = talk.take(3), route = "SPEAKER"), {}, {}, {}) }
    @Test fun errorNoNetwork() = shot("c6-error-no-network") { CallScreen("Hina", CallState(phase = "failed", error = CallCore.NO_NETWORK), {}, {}, {}) }
    @Test fun errorTimedOut() = shot("c7-error-timed-out") { CallScreen("Hina", CallState(phase = "failed", error = CallCore.TIMED_OUT), {}, {}, {}) }
    @Test fun errorMic() = shot("c8-error-microphone") { CallScreen("Hina", CallState(phase = "failed", error = CallCore.NO_MIC, needsMic = true), {}, {}, {}) }
    @Test fun cutOff() = shot("c9-cut-off") { CallScreen("Hina", CallState(phase = "ended", error = CallCore.CUT_OFF, lines = talk), {}, {}, {}) }
    @Test fun ended() = shot("c10-ended") { CallScreen("Hina", CallState(phase = "ended", lines = talk.take(2)), {}, {}, {}) }
}
