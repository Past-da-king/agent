package com.past9.phoneaos

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.connections.LearnedApp
import com.past9.phoneaos.connections.LearnedTool
import com.past9.phoneaos.screen.RunInfo
import com.past9.phoneaos.screen.RunStep
import com.past9.phoneaos.screen.ScreenState
import com.past9.phoneaos.ui.LocalSheetPreview
import com.past9.phoneaos.ui.screens.LearnedEdit
import com.past9.phoneaos.ui.screens.LearnedToolSheet
import com.past9.phoneaos.ui.screens.ScreenBlockedSheet
import com.past9.phoneaos.ui.screens.ScreenPeekActions
import com.past9.phoneaos.ui.screens.ScreenPeekSheet
import com.past9.phoneaos.ui.screens.ScreenRunPlayerSheet
import com.past9.phoneaos.ui.screens.ScreenRunsSheet
import com.past9.phoneaos.ui.screens.ScreenSaveSkillSheet
import com.past9.phoneaos.ui.screens.ScreenSetupActions
import com.past9.phoneaos.ui.screens.ScreenSetupInfo
import com.past9.phoneaos.ui.screens.ScreenSetupSheet
import com.past9.phoneaos.ui.screens.ScreenTypeSheet
import com.past9.phoneaos.ui.theme.AppTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Every background screen sheet at two phone widths (390 and 360), light and dark. PNGs land in app/build/design/
 * as screen-<sheet>-<width>-<light|dark>.png, to be looked at by eye.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ScreenSheetsShotTest(private val dark: Boolean, private val width: Int) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0} width={1}")
        fun params() = listOf(arrayOf<Any>(false, 390), arrayOf<Any>(true, 390), arrayOf<Any>(false, 360), arrayOf<Any>(true, 360))
    }
    @get:Rule val rule = createComposeRule()

    /** A stand-in for the hidden screen's picture: bands, so the frame and its fit can be judged. */
    private fun picture(): ImageBitmap {
        val b = android.graphics.Bitmap.createBitmap(270, 600, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b); val p = android.graphics.Paint()
        for (i in 0 until 12) { p.color = android.graphics.Color.rgb(235 - i * 6, 238 - i * 4, 245 - i * 2); c.drawRect(0f, i * 50f, 270f, i * 50f + 46f, p) }
        p.color = android.graphics.Color.rgb(40, 110, 90); c.drawRect(0f, 0f, 270f, 46f, p)
        return b.asImageBitmap()
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("w${width}dp-h${if (width == 390) 844 else 780}dp-xxhdpi")
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AppTheme(dark = dark) {
                CompositionLocalProvider(LocalSheetPreview provides true) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { content() } }
            }
        }
        rule.mainClock.advanceTimeBy(1200)
        rule.onRoot().captureRoboImage(File("build/design/screen-$name-$width-${if (dark) "dark" else "light"}.png").path)
    }

    private val open = ScreenState(open = true, pkg = "com.example.chat", app = "Messages", owner = "main", step = "Pressed \"New message\"", width = 720, height = 1600, steps = 4)

    @Test fun peekWhileTheAgentWorks() = shot("peek") { ScreenPeekSheet(open, picture(), "Hina", ScreenPeekActions()) {} }
    @Test fun peekTakenOver() = shot("peek-yours") { ScreenPeekSheet(open.copy(takenOver = true, step = "You have the screen", shown = 2), picture(), "Hina", ScreenPeekActions()) {} }
    @Test fun peekStopped() = shot("peek-stopped") { ScreenPeekSheet(open.copy(stopped = true), picture(), "Hina", ScreenPeekActions()) {} }

    @Test fun setupNotReady() = shot("setup") {
        ScreenSetupSheet(ScreenSetupInfo(shizukuInstalled = true, shizukuRunning = false, adbCommand = "adb shell sh /sdcard/Android/data/com.past9.phoneaos/files/screen-helper.sh",
            reason = "it has not been set up on this phone yet"), ScreenSetupActions()) {}
    }
    @Test fun setupReady() = shot("setup-ready") { ScreenSetupSheet(ScreenSetupInfo(ready = true, shizukuInstalled = true, shizukuRunning = true, shizukuAllowed = true, rules = "com.example.chat | allow"), ScreenSetupActions()) {} }

    private val runs = listOf(RunInfo("r1", "Messages", "com.example.chat", "main", 1794300000000, 1794300060000, 9, 9, 412_000),
        RunInfo("r2", "Notes", "com.example.notes", "you", 1794290000000, 1794290040000, 5, 5, 198_000), RunInfo("r3", "Maps", "com.example.maps", "Research helper", 1794200000000, 1794200090000, 14, 14, 1_640_000))
    private val learned = listOf(LearnedApp("notes", "Notes", "app://com.example.notes", kind = LearnedApp.SCREEN, pkg = "com.example.notes", tools = listOf(tool())))

    private fun tool() = LearnedTool("add_note", "Adds a note with a title and returns the list of notes.", js = "",
        steps = JSONArray().put(JSONObject().put("do", "open")).put(JSONObject().put("do", "tap").put("sel", JSONObject().put("desc", "New note")))
            .put(JSONObject().put("do", "type").put("text", "{title}").put("sel", JSONObject().put("hint", "Title"))).put(JSONObject().put("do", "tap").put("sel", JSONObject().put("text", "Save"))),
        versions = JSONArray().put(JSONObject()), stats = JSONObject().put("runs", 6).put("lastMs", 3400).put("liveMs", 41000))

    @Test fun recordingsList() = shot("runs") { ScreenRunsSheet(runs, learned, true, {}, {}, {}, {}, {}) {} }
    @Test fun recordingsEmpty() = shot("runs-empty") { ScreenRunsSheet(emptyList(), emptyList(), true, {}, {}, {}, {}, {}) {} }
    @Test fun recordingPlayer() = shot("player") {
        val pic = picture()
        ScreenRunPlayerSheet(runs[0], listOf(RunStep(1, "open", "Opened Messages", frame = 0), RunStep(2, "tap", "Pressed \"New message\"", frame = 1), RunStep(3, "type", "Typed \"Running late, there in ten\"", frame = 2),
            RunStep(4, "approval", "Press \"Send\" in Messages: you approved"), RunStep(5, "tap", "Pressed \"Send\"", frame = 3)), { pic }, {}) {}
    }
    @Test fun blockedApps() = shot("blocked") { ScreenBlockedSheet(listOf("Capitec", "Samsung Wallet", "PayPal"), listOf("com.example.diary" to "My Diary"), {}, {}) {} }
    @Test fun blockedAppsNoneFound() = shot("blocked-none") { ScreenBlockedSheet(emptyList(), emptyList(), {}, {}) {} }
    @Test fun saveAsLearnedApp() = shot("save") {
        ScreenSaveSkillSheet("Notes", listOf("Opened Notes", "You pressed \"New note\"", "Typed \"Milk\"", "You pressed \"Save\""), listOf("Milk"), "", { _, _, _ -> }) {}
    }
    @Test fun typeIntoTheApp() = shot("type") { ScreenTypeSheet({ _, _ -> }) {} }
    @Test fun learnedTool() = shot("learned-tool") { LearnedToolSheet(learned[0], tool(), LearnedEdit()) {} }
}
