package com.past9.phoneaos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.assist.LiveActions
import com.past9.phoneaos.assist.LiveBubbleContent
import com.past9.phoneaos.assist.LiveDismissTarget
import com.past9.phoneaos.assist.LivePhase
import com.past9.phoneaos.assist.LiveSheetBody
import com.past9.phoneaos.assist.LiveView
import com.past9.phoneaos.assist.OverlayPermissionSheet
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

/** The minimise button, the floating bubble in each state, the dismiss circle and the overlay permission sheet, at 390 and 360 wide, light and dark. PNGs in app/build/live-bubble/<width>/. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
open class LiveBubbleShotsTest(private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
    @get:Rule val rule = createComposeRule()
    open val outDir: String get() = "build/live-bubble/390"
    private fun file(name: String) = File("$outDir/$name-${if (dark) "dark" else "light"}.png").also { it.parentFile?.mkdirs() }.path

    /** Another app's screen behind, with the bubble floating on it. */
    private fun floating(name: String, view: LiveView, content: @androidx.compose.runtime.Composable () -> Unit = {}, after: () -> Unit = {}) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AppTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(24.dp)) {
                        Spacer(Modifier.height(40.dp))
                        androidx.compose.material3.Text("Another app", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.Text("The live bubble floats over whatever you are doing.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 120.dp)) { LiveBubbleContent(view, onMute = {}, onClose = {}) }
                    content()
                }
            }
        }
        rule.mainClock.advanceTimeBy(700)
        after()
        rule.onRoot().captureRoboImage(file(name))
    }

    @Test fun bubbleListening() = floating("01-bubble-listening", LiveView(LivePhase.LISTENING)) { rule.onNodeWithText("LIVE").assertIsDisplayed(); rule.onNodeWithContentDescription("Mute").assertIsDisplayed() }
    @Test fun bubbleSpeaking() = floating("02-bubble-speaking", LiveView(LivePhase.SPEAKING))
    @Test fun bubbleWorking() = floating("03-bubble-working", LiveView(LivePhase.WORKING, label = "Sending a message"))
    @Test fun bubbleNeedsYou() = floating("04-bubble-needs-you", LiveView(LivePhase.NEEDS_YOU)) { rule.onNodeWithText("Needs you").assertIsDisplayed() }
    @Test fun bubbleMuted() = floating("05-bubble-muted", LiveView(LivePhase.LISTENING, muted = true)) { rule.onNodeWithContentDescription("Unmute").assertIsDisplayed() }

    @Test fun draggingShowsTheDismissCircle() {
        floating("06-dismiss-target", LiveView(LivePhase.LISTENING), content = {
            Box(Modifier.fillMaxSize()) { LiveDismissTarget(false, Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)) }
        })
    }

    @Test fun dismissCircleWhenOver() {
        floating("07-dismiss-target-over", LiveView(LivePhase.LISTENING), content = {
            Box(Modifier.fillMaxSize()) { LiveDismissTarget(true, Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)) }
        })
    }

    @Test fun theSheetHasAMinimiseButton() {
        var minimised = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AppTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), shape = SheetShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        LiveSheetBody(LiveView(LivePhase.LISTENING, you = "What is on my screen?"), "Nova", ScreenLine("Reading the screen: Notes", true), LiveActions(onMinimise = { minimised++ }))
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(700)
        rule.onNodeWithContentDescription("Minimise").assertIsDisplayed()
        rule.onNodeWithContentDescription("Minimise").performClick()
        assertEquals(1, minimised)
        rule.onRoot().captureRoboImage(file("08-sheet-with-minimise"))
    }

    @Test fun overlayPermissionSheet() {
        var opened = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AppTheme(dark = dark) {
                CompositionLocalProvider(LocalSheetPreview provides true) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        OverlayPermissionSheet("Nova", onOpenSettings = { opened++ }, onDismiss = {})
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1200)
        rule.onNodeWithText("Let Nova float over other apps").assertIsDisplayed()
        rule.onNodeWithText("Open Settings").assertIsDisplayed()
        rule.onNodeWithText("Not now").assertIsDisplayed()
        rule.onRoot().captureRoboImage(file("09-overlay-permission"))
        rule.onNodeWithText("Open Settings").performClick()
        assertEquals(1, opened)
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class LiveBubbleShots360Test(dark: Boolean) : LiveBubbleShotsTest(dark) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
    override val outDir: String get() = "build/live-bubble/360"
}
