package com.past9.phoneaos

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.agent.AgentStatus
import com.past9.phoneaos.data.GoalRow
import com.past9.phoneaos.data.MemoryRow
import com.past9.phoneaos.data.TaskRow
import com.past9.phoneaos.remote.ProjectDraft
import com.past9.phoneaos.remote.ProjectRow
import com.past9.phoneaos.remote.ProjectsApi
import com.past9.phoneaos.ui.LocalSheetPreview
import com.past9.phoneaos.ui.NewProjectSheet
import com.past9.phoneaos.ui.screens.*
import com.past9.phoneaos.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Projects on the phone (9 Nov 2026): the New project sheet, the profile's Projects section and goals, the New chat choice. 390 wide, light and dark. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ProjectsShotsTest(private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
    @get:Rule val rule = createComposeRule()

    private fun start(content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("w390dp-h844dp-xxhdpi")
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme(dark = dark) { Surface(color = MaterialTheme.colorScheme.surface) { content() } } }
        rule.mainClock.advanceTimeBy(900)
    }
    private fun cap(name: String) {
        File("build/audit/fxE").mkdirs()
        rule.onRoot().captureRoboImage(File("build/audit/fxE/$name-${if (dark) "dark" else "light"}.png").path)
    }
    private fun sheet(content: @Composable () -> Unit) = start { CompositionLocalProvider(LocalSheetPreview provides true) { content() } }
    private fun scrollDown(dp: Int = 540) { rule.onRoot().performTouchInput { swipeUp(startY = 2000f, endY = 2000f - dp * 3, durationMillis = 300) }; rule.mainClock.advanceTimeBy(1500) }

    private val now = System.currentTimeMillis()
    private val api = ProjectsApi { method, _ -> if (method == "profiles.list") org.json.JSONArray().put(org.json.JSONObject().put("id", "p1").put("name", "Work")).put(org.json.JSONObject().put("id", "p2").put("name", "Personal")) else null }
    private val profiles = listOf("" to "General", "p1" to "Work", "p2" to "Personal")
    private val rows = listOf(
        ProjectRow("a", "Garden plan", glyph = "globe", color = "forest", chats = listOf("pc1", "pc2"), activeAt = now - 4 * 60_000),
        ProjectRow("b", "Website rebuild for the bakery on the corner of Main Street", glyph = "code", color = "ocean", chats = listOf("pc3"), activeAt = now - 3 * 3_600_000),
        ProjectRow("c", "Tax return", glyph = "doc", color = "ember", chats = emptyList(), activeAt = now - 2 * 86_400_000),
        ProjectRow("d", "Reading list", glyph = "📚", color = "violet", chats = listOf("pc4"), activeAt = now - 20 * 86_400_000),
        ProjectRow("earlier-chats", "Earlier chats", glyph = "chat", color = "graphite", chats = listOf("pc5")),
    )
    private val goals = (1L..5L).map { GoalRow(it, listOf("Plan the Durban trip", "Renew the car licence", "Sort out the garden", "Find a new dentist", "Learn to bake bread")[(it - 1).toInt()], if (it == 1L) "Long weekend in October" else "") }
    private val tasks = listOf(TaskRow(1, 1, "Book the flights", status = "done", owner = "agent"), TaskRow(2, 1, "Hold the guesthouse", status = "doing", owner = "agent"),
        TaskRow(3, 2, "Get the form signed", status = "blocked", owner = "user", blocker = "Needs your signature"), TaskRow(4, 3, "Measure the beds", owner = "user"))
    private val home get() = HomeState("Nova", "Sam", AgentStatus(false, "", 2), emptyList(), goals, tasks, emptyList(), now = now)
    private val mem = listOf(MemoryRow(id = 1, title = "Prefers short answers", body = "Keep it brief.", pinned = true))
    private val linked = ProfileProjects(api, rows, loaded = true)

    @Test fun newProjectEmpty() {
        sheet { NewProjectSheet({}, {}, api, initialProfiles = profiles) }
        rule.onNodeWithText("Create project").assertIsNotEnabled()
        cap("new-project-empty")
    }
    @Test fun newProjectFilled() {
        sheet { NewProjectSheet({}, {}, api, ProjectDraft("Garden plan", "Beds, paths and what to plant where this spring.", "p1", "globe", "forest", "/home/sam/garden"), profiles) }
        rule.onNodeWithText("Create project").assertIsEnabled()
        cap("new-project-filled")
        scrollDown(); cap("new-project-filled-lower")
    }
    @Test fun newProjectFailed() {
        sheet { NewProjectSheet({}, {}, api, ProjectDraft("Garden plan", glyph = "🌱", color = "teal"), profiles, initialError = "Couldn't make the project: Not connected to a computer right now. Nothing you typed is lost.") }
        cap("new-project-failed")
    }
    @Test fun newProjectNotLinked() { sheet { NewProjectSheet({}, {}, null) }; cap("new-project-not-linked") }

    @Test fun profileWithProjects() {
        start { ProfileScreen(home, mem, ProfileActions(), linked) }
        cap("profile-top")
        scrollDown(330); cap("profile-projects")
        scrollDown(); cap("profile-goals")
        rule.onNodeWithText("See all").assertExists()
        rule.onNodeWithText("Find a new dentist").assertDoesNotExist()
    }
    @Test fun profileNotLinked() { start { ProfileScreen(home, mem, ProfileActions(), ProfileProjects()) }; scrollDown(330); cap("profile-not-linked") }
    @Test fun newChatChoice() { sheet { NewChatChoice(linked, ProfileActions(), {}) }; cap("new-chat-choice") }
}
