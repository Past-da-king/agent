package com.past9.phoneaos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.ui.ComposerBox
import com.past9.phoneaos.ui.LocalSheetPreview
import com.past9.phoneaos.ui.composerActionsFor
import com.past9.phoneaos.ui.screens.MessageComposer
import com.past9.phoneaos.ui.screens.RecordingsScreen
import com.past9.phoneaos.ui.theme.AppTheme
import com.past9.phoneaos.voice.DictationTape
import com.past9.phoneaos.voice.Dictations
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The message box on screen: Send leaves the words until they are accepted. And the Recordings screen, light and dark. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class DraftScreensTest(private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val send = "Send (swipe up for a voice reply)"

    @Test fun tappingSendLeavesTheWordsThereUntilTheyAreAccepted() {
        val box = ComposerBox(mutableStateOf("everything I just said"))
        val gate = CompletableDeferred<Unit>()
        val acts = composerActionsFor(box, null, false, scope, send = { _, _, _ -> gate.await() })
        rule.setContent { AppTheme(dark = dark) { MessageComposer(box.draft, { box.draft = it }, false, acts, box.attachments, emptyList(), sending = box.sending) } }
        rule.onNodeWithContentDescription(send, useUnmergedTree = true).performTouchInput { click() }
        rule.waitForIdle()
        assertEquals("everything I just said", box.draft)
        rule.onNodeWithText("everything I just said").assertIsDisplayed()
        gate.complete(Unit)
        rule.waitForIdle()
        assertEquals("", box.draft)
    }

    @Test fun aFailedSendAndAScreenThatLeavesLoseNothing() {
        val box = ComposerBox(mutableStateOf("everything I just said"))
        val acts = composerActionsFor(box, null, false, scope, send = { _, _, _ -> throw java.io.IOException("Not connected") })
        var shown by mutableStateOf(true)
        rule.setContent { AppTheme(dark = dark) { if (shown) MessageComposer(box.draft, { box.draft = it }, false, acts, box.attachments, emptyList(), sending = box.sending) } }
        rule.onNodeWithContentDescription(send, useUnmergedTree = true).performTouchInput { click() }
        rule.waitForIdle()
        assertEquals("everything I just said", box.draft)
        // A reload or a reconnect takes the box off the screen and brings it back.
        shown = false; rule.waitForIdle()
        shown = true; rule.waitForIdle()
        rule.onNodeWithText("everything I just said").assertIsDisplayed()
    }

    private fun seed() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val f = Dictations.of(ctx)
        f.list().forEach { f.forget(it.id) }
        File(f.dir, "20261030-105630.wav").writeBytes(DictationTape.header(0) + ByteArray(150 * DictationTape.BYTES_PER_SECOND))
        File(f.dir, "20261030-093000.wav").writeBytes(DictationTape.header(0) + ByteArray(64 * DictationTape.BYTES_PER_SECOND))
        f.note("20261030-093000", 1793352600000, 64_000, "So what I was saying is I want the whole thing finished on Sonnet and then deployed, so I can test it fully on my phone, and everyone can see it before the weekend.", "done", "main", flush = true)
        File(f.dir, "20261029-204113.wav").writeBytes(DictationTape.header(0) + ByteArray(10 * DictationTape.BYTES_PER_SECOND))
        f.note("20261029-204113", 1793299273000, 10_000, "The first half came through.", "kept", "main", flush = true)
    }

    private fun content() = @androidx.compose.runtime.Composable {
        AppTheme(dark = dark) { CompositionLocalProvider(LocalSheetPreview provides true) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { Box(Modifier.fillMaxSize()) { RecordingsScreen("main", onBack = {}) } } } }
    }

    @Test fun theList() {
        seed()
        rule.mainClock.autoAdvance = false
        rule.setContent(content())
        rule.mainClock.advanceTimeBy(800)
        rule.onRoot().captureRoboImage(File("build/design/10-recordings-${if (dark) "dark" else "light"}.png").path)
    }

    @Test fun oneSavedDictation() {
        seed()
        rule.mainClock.autoAdvance = false
        rule.setContent(content())
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithText("So what I was saying", substring = true).performClick()
        rule.mainClock.advanceTimeBy(1200)
        rule.onRoot().captureRoboImage(File("build/design/11-recording-sheet-${if (dark) "dark" else "light"}.png").path)
        rule.onNodeWithText("Insert into message").assertIsDisplayed()
        rule.onNodeWithText("Copy").assertIsDisplayed()
        rule.onNodeWithText("Transcribe again").assertIsDisplayed()
    }
}
