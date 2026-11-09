package com.past9.phoneaos

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.remote.HomeRunning
import com.past9.phoneaos.remote.LiveCard
import com.past9.phoneaos.remote.LiveCards
import com.past9.phoneaos.remote.LiveCardsUi
import com.past9.phoneaos.remote.LiveStep
import com.past9.phoneaos.remote.ProjectInfo
import com.past9.phoneaos.remote.RemoteIds
import com.past9.phoneaos.remote.RunningBlock
import com.past9.phoneaos.remote.RunningFold
import com.past9.phoneaos.remote.RunningList
import com.past9.phoneaos.ui.LocalSheetPreview
import com.past9.phoneaos.ui.ProjectSwitcherSheet
import com.past9.phoneaos.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The running list across all projects (open, and folded with one card that needs him still in view), its lines on
 * Home's main agent card, and the project switcher sheet (many projects with long names, and none). 390 wide, light
 * and dark. PNGs land in app/build/running/<name>-light|dark.png.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RunningShotsTest(private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
    @get:Rule val rule = createComposeRule()

    private fun shot(name: String, sheet: Boolean = false, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("w390dp-h844dp-xxhdpi")
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme(dark = dark) { CompositionLocalProvider(LocalSheetPreview provides sheet) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                if (sheet) content() else Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
            }
        } } }
        rule.mainClock.advanceTimeBy(900)
        File("build/running").mkdirs()
        rule.onRoot().captureRoboImage(File("build/running/$name-${if (dark) "dark" else "light"}.png").path)
    }

    private val now = System.currentTimeMillis()
    private val longName = "A project with a very long name that will never fit on one line of a phone"
    private fun card(id: String, label: String, state: String = "working", line: String = "Reading the code", project: String = "", pid: String = "", color: String = "iris",
                     isProject: Boolean = false, queued: Boolean = false, depth: Int = 0, waiting: Boolean = false, result: String = "") = LiveCard(
        id = id, row = id, chat = if (isProject) "pc-$id" else "main", label = label, state = state, queued = queued, line = line, done = result, depth = depth,
        projectChat = if (isProject) "pc-$id" else "", ownerKind = if (project.isBlank()) "main" else "project", ownerName = project, ownerGlyph = "folder", ownerColor = color, ownerImage = "",
        model = "model", startedAt = now - 4 * 60_000, endedAt = if (state == "working") 0L else now - 30_000, brief = "$label: do the whole job and report back.",
        result = result, steps = listOf(LiveStep("Read 14 files", "done", now - 200_000)), ownerId = pid, waiting = waiting, activityAt = now - 20_000)

    private val projects = listOf(
        ProjectInfo("p1", "Garden planner", "folder", "mint", chats = listOf("pc-a")),
        ProjectInfo("p2", "Shop site", "folder", "ocean", chats = listOf("pc-s")),
        ProjectInfo("p3", longName, "folder", "ember", chats = listOf("pc-d")),
    )
    private val cards = listOf(
        card("m", "Research the prices", line = "Reading three pages"),
        card("q", "Compare the quotes", queued = true, line = ""),
        card("a", "Garden planner", project = "Garden planner", pid = "p1", color = "mint", isProject = true, line = "Drawing the beds"),
        card("b", "Check the watering plan", project = "Garden planner", pid = "p1", color = "mint", depth = 1, line = "Running the tests"),
        card("s", "Shop site", project = "Shop site", pid = "p2", color = "ocean", isProject = true, line = "Fixing the checkout"),
        card("d", longName, project = longName, pid = "p3", color = "ember", isProject = true, state = "done", waiting = true, line = "", result = "Done. Which of the two layouts do you want?"),
        card("f", "Old lookup", state = "done", result = "Found the answer."),
    )
    private val ids = RemoteIds()
    private fun ui(list: List<LiveCard>) = LiveCardsUi(list, { c -> LiveCards.view(c, ids) }, {})
    private fun fold(collapsed: Boolean) = RunningFold(null).apply { set(collapsed) }

    @Test fun open() = shot("running-open") { RunningBlock(ui(cards), current = "pc-s", projects = projects, states = emptyMap(), fold = fold(false)) }
    @Test fun collapsed() = shot("running-collapsed") {
        RunningBlock(ui(cards), current = "pc-s", projects = projects, states = emptyMap(), fold = fold(true))
        // Home's main agent card carries the same lines on its own colour.
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeRunning(cards = cards, projects = projects, states = emptyMap(), fold = fold(false))
                HomeRunning(cards = cards, projects = projects, states = emptyMap(), fold = fold(true))
            }
        }
    }
    @Test fun nothingRunning() = shot("running-idle") {
        RunningBlock(ui(cards.filter { it.id == "d" || it.id == "f" }), projects = projects, states = emptyMap(), fold = fold(false))
    }

    private val many = projects + (4..30).map { ProjectInfo("p$it", if (it % 7 == 0) "$longName $it" else "Project $it", "folder", listOf("ocean", "mint", "ember", "iris")[it % 4], chats = listOf("c$it")) }
    private val lines = mapOf("p1" to "2 min ago · The beds are drawn, have a look", "p2" to "1 h ago · Checkout works again on the phone and on the computer, both checked", "p3" to "3 d ago")

    @Test fun switcher() = shot("switcher", sheet = true) { ProjectSwitcherSheet(RunningList.switcher(many, cards, "pc-s"), lines, {}, onNewProject = {}, onDismiss = {}) }
    @Test fun switcherNoProjects() = shot("switcher-empty", sheet = true) { ProjectSwitcherSheet(RunningList.switcher(emptyList(), emptyList()), emptyMap(), {}, onNewProject = {}, onDismiss = {}) }
    @Test fun switcherOneProject() = shot("switcher-one", sheet = true) { ProjectSwitcherSheet(RunningList.switcher(projects.take(1), cards.take(3), "main"), lines, {}, onNewProject = null, onDismiss = {}) }

    @Test fun theFoldIsKeptOnTheDeviceForEveryScreen() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("fold-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val n = RunningList.counts(cards)
        assertEquals("4 working, 1 waiting to start, 1 needs you", RunningList.header(n))
        val first = RunningFold(prefs)
        assertTrue(!first.collapsed(n))
        first.toggle(n)
        // Another screen, or the app opened again, reads the same choice.
        assertTrue(RunningFold(prefs).collapsed(n))
        first.toggle(n)
        assertTrue(!RunningFold(prefs).collapsed(n))
        // The groups he folded are kept the same way.
        first.toggleGroup("p2")
        assertEquals(setOf("p2"), RunningFold(prefs).groups)
    }
}
