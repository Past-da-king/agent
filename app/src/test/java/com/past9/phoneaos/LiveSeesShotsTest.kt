package com.past9.phoneaos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.assist.LiveActions
import com.past9.phoneaos.assist.LiveBubbleContent
import com.past9.phoneaos.assist.LivePhase
import com.past9.phoneaos.assist.LiveSheetBody
import com.past9.phoneaos.assist.LiveView
import com.past9.phoneaos.assist.ScreenLine
import com.past9.phoneaos.assist.Seeing
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.ui.SheetShape
import com.past9.phoneaos.ui.screens.CallLogRow
import com.past9.phoneaos.ui.screens.CallRow
import com.past9.phoneaos.ui.theme.AppTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Live seeing the screen (the honest line in the sheet and the bubble) and a Live session as ONE folded call with its hand-offs inside, at 390 and 360, light and dark. PNGs in app/build/live-sees/<width>/. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
open class LiveSeesShotsTest(private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
    @get:Rule val rule = createComposeRule()
    open val outDir: String get() = "build/live-sees/390"
    private fun file(name: String) = File("$outDir/$name-${if (dark) "dark" else "light"}.png").also { it.parentFile?.mkdirs() }.path

    private fun sheet(name: String, view: LiveView, after: () -> Unit = {}) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AppTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), shape = SheetShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        LiveSheetBody(view, "Nova", ScreenLine("Reading the screen: Notes", true), LiveActions())
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(700)
        after()
        rule.onRoot().captureRoboImage(file(name))
    }

    @Test fun sheetSeeing() = sheet("01-sheet-seeing", LiveView(LivePhase.LISTENING, you = "What is on my screen?", seeing = Seeing.SEEING)) { rule.onNodeWithText("Live can see your screen").assertIsDisplayed() }
    @Test fun sheetPrivate() = sheet("02-sheet-private", LiveView(LivePhase.LISTENING, you = "What is on my screen?", agent = "That one is private, so I can't see it.", seeing = Seeing.PRIVATE)) { rule.onNodeWithText("Private screen: Live can't see it").assertIsDisplayed() }

    private fun bubble(name: String, view: LiveView) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AppTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    Box(Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 120.dp)) { LiveBubbleContent(view, onMute = {}, onClose = {}, animate = false) }
                }
            }
        }
        rule.mainClock.advanceTimeBy(700)
        rule.onRoot().captureRoboImage(file(name))
    }

    @Test fun bubbleSeeing() = bubble("03-bubble-seeing", LiveView(LivePhase.LISTENING, seeing = Seeing.SEEING))
    @Test fun bubblePrivate() = bubble("04-bubble-private", LiveView(LivePhase.LISTENING, seeing = Seeing.PRIVATE))

    // ---- a Live session as one call in the chat ----------------------------------------------------------

    private fun row(id: Long, kind: String, text: String, meta: JSONObject, at: Long) = ChatItem(id = id, kind = kind, text = text, meta = meta.toString(), createdAt = at)

    private val liveItems = listOf(
        row(1, "user", "can you put today's numbers in the end of day report", JSONObject().put("call", true).put("live", true), 1_000),
        row(2, "agent", "Update the EOD report with today's numbers", JSONObject().put("call", true).put("live", true).put("handoff", true).put("spoken", "can you put today's numbers in the end of day report"), 2_000),
        row(3, "agent", "On it. I'll tell you when it's done.", JSONObject().put("call", true).put("live", true), 3_000),
        row(4, "user", "thanks, that's all", JSONObject().put("call", true).put("live", true), 125_000),
    )

    private fun call(name: String, open: Boolean) {
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme(dark = dark) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { Column(Modifier.padding(16.dp)) { CallRow(liveItems, "Nova") } } } }
        rule.mainClock.advanceTimeBy(500)
        if (open) { rule.onNodeWithText("Handed to your agent: Update the EOD report with today's numbers").performClick(); rule.mainClock.advanceTimeBy(1500); rule.onNodeWithText("Sent to your agent").assertExists() }
        else rule.onNodeWithText("Handed to your agent: Update the EOD report with today's numbers").assertIsDisplayed()
        rule.onRoot().captureRoboImage(file(name))
    }

    @Test fun liveCallFolded() = call("05-live-call-folded", open = false)
    @Test fun liveCallOpened() = call("06-live-call-opened", open = true)

    @Test fun liveCallFromTheComputer() {
        val meta = JSONObject().put("kind", "live_assistant").put("durationMs", 124_000L)
            .put("lines", JSONArray().put(JSONObject().put("who", "you").put("text", "update my end of day report").put("at", 1L)).put(JSONObject().put("who", "agent").put("text", "On it.").put("at", 2L)))
            .put("handoffs", JSONArray().put(JSONObject().put("at", 2L).put("request", "Update the EOD report").put("spoken", "update my end of day report").put("include_screen", false)))
        val item = ChatItem(id = 9, kind = "call", text = "Live voice", meta = meta.toString())
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme(dark = dark) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { Column(Modifier.padding(16.dp)) { CallLogRow(item, "Nova") } } } }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Handed to your agent: Update the EOD report").performClick()
        rule.mainClock.advanceTimeBy(1500)
        rule.onNodeWithText("Sent to your agent").assertExists()
        rule.onRoot().captureRoboImage(file("07-live-call-from-computer-opened"))
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class LiveSeesShots360Test(dark: Boolean) : LiveSeesShotsTest(dark) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
    override val outDir: String get() = "build/live-sees/360"
}
