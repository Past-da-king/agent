package com.past9.phoneaos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.ui.screens.VoiceNoteControls
import com.past9.phoneaos.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
open class NotePlayerShotsTest(private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
    @get:Rule val rule = createComposeRule()
    open val width = 390
    private fun shot(name: String) {
        val root = System.getenv("VOICE_PLAYER_PROOF_DIR") ?: File(System.getProperty("java.io.tmpdir"), "proof-voice-note-player").path
        val file = File(root, "shots/${width}/${name}-${if (dark) "dark" else "light"}.png")
        file.parentFile.mkdirs()
        rule.onRoot().captureRoboImage(file.path)
    }
    private fun bubble(playing: Boolean, position: Int, started: Boolean, onSeek: (Int) -> Unit = {}) {
        rule.setContent {
            AppTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
                    Box(Modifier.fillMaxWidth().padding(end = 48.dp)) {
                        VoiceNoteControls(playing, 118000, { position }, started, false, {}, onSeek, {})
                    }
                }
            }
        }
    }
    @Test fun idleDuration() {
        bubble(false, 0, false)
        rule.onNodeWithText("1:58").assertIsDisplayed()
        rule.onNodeWithContentDescription("Play voice note").assertIsDisplayed()
        shot("idle")
    }
    @Test fun playingElapsedTotal() {
        bubble(true, 42000, true)
        rule.onNodeWithText("0:42 / 1:58").assertIsDisplayed()
        rule.onNodeWithContentDescription("Pause voice note").assertIsDisplayed()
        shot("playing")
    }
    @Test fun pausedKeepsElapsed() {
        bubble(false, 42000, true)
        rule.onNodeWithText("0:42 / 1:58").assertIsDisplayed()
        rule.onNodeWithContentDescription("Play voice note").assertIsDisplayed()
        shot("paused")
    }
    @Test fun draggingPreviewsAndCommitsOnRelease() {
        var seek = -1
        bubble(true, 42000, true) { seek = it }
        val waveform = rule.onNodeWithContentDescription("Voice note position")
        waveform.assertHeightIsAtLeast(48.dp)
        // At 360 dp the waveform is narrow: moving from its centre to 75% can be
        // inside Android's touch slop. Start at 5% so this is a real drag at both widths.
        waveform.performTouchInput {
            down(androidx.compose.ui.geometry.Offset(this.width * 0.05f, centerY))
            moveTo(androidx.compose.ui.geometry.Offset(this.width * 0.75f, centerY))
        }
        assertEquals(-1, seek)
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        val preview = waveform.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current
        org.junit.Assert.assertTrue("Preview should be near 88500 ms, got $preview. " + rule.onRoot().printToString(), preview in 88000f..89000f)
        rule.onNodeWithText("1:28 / 1:58").assertIsDisplayed()
        shot("dragging")
        waveform.performTouchInput { up() }
        rule.runOnIdle { org.junit.Assert.assertTrue("Expected three-quarter seek, got $seek", seek in 88000..89000) }
    }
    @Test fun tappingWaveformSeeksImmediately() {
        var seek = -1
        bubble(false, 42000, true) { seek = it }
        rule.onNodeWithContentDescription("Voice note position").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(this.width * 0.75f, centerY))
        }
        rule.runOnIdle { org.junit.Assert.assertTrue("Tap target $seek", seek in 88000..89000) }
    }
    @Test fun rtlWaveformMirrorsTapTarget() {
        var seek = -1
        rule.setContent {
            AppTheme(dark = dark) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
                    VoiceNoteControls(false, 118000, { 42000 }, true, false, {}, { seek = it }, {})
                }
            }
        }
        rule.onNodeWithContentDescription("Voice note position").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(this.width * 0.25f, centerY))
        }
        rule.runOnIdle { org.junit.Assert.assertTrue("RTL tap target $seek", seek in 88000..89000) }
    }
    @Test fun verticalSwipeScrollsChatWithoutSeeking() {
        var seek = -1
        lateinit var state: androidx.compose.foundation.lazy.LazyListState
        rule.setContent {
            AppTheme(dark = dark) {
                state = androidx.compose.foundation.lazy.rememberLazyListState()
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), state = state) {
                    item { VoiceNoteControls(false, 118000, { 42000 }, true, false, {}, { seek = it }, {}) }
                    items(30) { androidx.compose.material3.Text("Message $it", Modifier.height(72.dp)) }
                }
            }
        }
        rule.onNodeWithContentDescription("Voice note position").performTouchInput {
            down(center)
            moveTo(androidx.compose.ui.geometry.Offset(centerX, centerY - 300f))
            up()
        }
        rule.runOnIdle {
            assertEquals(-1, seek)
            org.junit.Assert.assertTrue("Chat should scroll", state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0)
        }
    }
    @Test fun durationChangeClearsDragPreviewWithoutSeeking() {
        var seek = -1
        val duration = androidx.compose.runtime.mutableIntStateOf(118000)
        rule.setContent {
            AppTheme(dark = dark) {
                VoiceNoteControls(true, duration.intValue, { 42000 }, true, false, {}, { seek = it }, {})
            }
        }
        val waveform = rule.onNodeWithContentDescription("Voice note position")
        waveform.assertHeightIsAtLeast(48.dp)
        waveform.performTouchInput {
            down(androidx.compose.ui.geometry.Offset(this.width * 0.05f, centerY))
            moveTo(androidx.compose.ui.geometry.Offset(this.width * 0.75f, centerY))
        }
        rule.mainClock.advanceTimeByFrame(); rule.waitForIdle()
        rule.runOnIdle { duration.intValue = 120000 }
        rule.mainClock.advanceTimeByFrame(); rule.waitForIdle()
        val shown = waveform.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current
        assertEquals(42000f, shown, 0f)
        waveform.performTouchInput { up() }
        rule.runOnIdle { assertEquals(-1, seek) }
    }
    @Test fun talkbackCanSeek() {
        var seek = -1
        bubble(false, 42000, true) { seek = it }
        rule.onNodeWithContentDescription("Voice note position").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(59000f) }
        rule.runOnIdle { assertEquals(59000, seek) }
    }
}

@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class NotePlayer360ShotsTest(dark: Boolean) : NotePlayerShotsTest(dark) {
    override val width = 360
}
