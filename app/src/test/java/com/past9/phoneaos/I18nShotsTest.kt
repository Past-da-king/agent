package com.past9.phoneaos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.agent.AgentStatus
import com.past9.phoneaos.data.*
import com.past9.phoneaos.system.AppLocale
import com.past9.phoneaos.ui.ComposerActions
import com.past9.phoneaos.ui.LocalSheetPreview
import com.past9.phoneaos.ui.SheetChoice
import com.past9.phoneaos.ui.screens.*
import com.past9.phoneaos.ui.theme.AppTheme
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
 * Every language on the screens that matter, at 390dp, light and dark: Home, the chat with its composer, Settings
 * (top and scrolled) and the language picker sheet. Looked at by eye for tofu boxes, clipped text and right to left
 * mistakes. PNGs land in app/build/i18n/<language>/<name>-light|dark.png.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class I18nShotsTest(private val qualifier: String, private val dark: Boolean) {
    companion object {
        // Right to left first, then the scripts most likely to break (CJK, Indic, Thai, Khmer, Ethiopic), then the long Latin ones.
        private val locales = (System.getProperty("i18n.shots") ?: "ar,iw,fa,ur,ja,zh-rCN,ko,hi,ta,bn,th,km,am,de,ru").split(",")
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-dark={1}")
        fun params() = locales.flatMap { listOf(arrayOf<Any>(it, false), arrayOf<Any>(it, true)) }
    }
    @get:Rule val rule = createComposeRule()

    private val now = System.currentTimeMillis()
    private var nextId = 1L
    private fun item(kind: String, text: String, meta: JSONObject = JSONObject()) = ChatItem(id = nextId++, kind = kind, text = text, meta = meta.toString(), createdAt = now)

    private fun start(content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("$qualifier-w390dp-h844dp-xxhdpi")
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme(dark = dark) { Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) { content() } } }
        rule.mainClock.advanceTimeBy(900)
    }
    private fun cap(name: String) {
        val dir = File("build/i18n/$qualifier"); dir.mkdirs()
        rule.onRoot().captureRoboImage(File(dir, "$name-${if (dark) "dark" else "light"}.png").path)
    }

    private val homeState get() = HomeState("Nova", "Sam", AgentStatus(true, "Working", 2),
        listOf(item("helper", "Research", JSONObject().put("label", "Research").put("state", "working").put("startedAt", now - 120_000))),
        listOf(GoalRow(1, "Plan the trip", ""), GoalRow(2, "Launch the site", "")),
        listOf(TaskRow(1, 1L, "Book the flights", owner = "user", status = "todo"), TaskRow(2, 2L, "Check the copy", owner = "agent", status = "doing")),
        listOf(TriggerRow(1, "Morning brief", "daily", "07:00", "Do it")))

    @Test fun home() { start { HomeScreen(homeState, HomeActions()); Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { HomeBar("home", {}, {}) } }; cap("1-home") }

    @Test fun chat() {
        val items = listOf(item("user", "Hello"), item("agent", "Hi Sam. What should I do today?"),
            item("question", "Which one first?", JSONObject().put("options", org.json.JSONArray(listOf("The report", "The email", "Skip")))))
        start { ChatScreen(items, AgentStatus(), "Nova", "Sam", false, "", {}, ChatActions()) }
        cap("2-chat")
    }

    @Test fun composer() {
        start { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { MessageComposer("Hello", {}, false, ComposerActions(onSend = {}, onRecordings = {}), emptyList()) } }
        cap("3-composer")
    }

    @Test fun settings() {
        val s = AgentSettings(mode = PowerMode.API_KEY, provider = Provider.ANTHROPIC, model = "claude-sonnet-5-5", hasKey = true, userName = "Sam", agentName = "Nova", onboarded = true)
        start { SettingsScreen(s, RuntimeInfo(false, 0, false, ""), "0.15.54", SettingsActions()) }
        cap("4-settings-top")
        rule.onRoot().performTouchInput { swipeUp(startY = height * 0.85f, endY = height * 0.15f, durationMillis = 300) }
        rule.mainClock.advanceTimeBy(1200)
        cap("5-settings-scrolled")
    }

    @Test fun languageSheet() {
        start {
            CompositionLocalProvider(LocalSheetPreview provides true) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                com.past9.phoneaos.ui.ChoiceSheet(Icons.Rounded.Language, androidx.compose.ui.res.stringResource(R.string.settings_you), androidx.compose.ui.res.stringResource(R.string.settings_language),
                    AppLocale.choices.take(9).map { l -> SheetChoice(l.tag, l.name ?: androidx.compose.ui.res.stringResource(R.string.settings_language_phone),
                        line = if (l.machine) AppLocale.machineLine(ctx, l.tag) else "", selected = l.tag == "") }, onPick = {}, onDismiss = {})
            }
        }
        cap("6-language-sheet")
    }
}
