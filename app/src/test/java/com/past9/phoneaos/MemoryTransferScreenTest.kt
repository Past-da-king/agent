package com.past9.phoneaos

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.data.MemoryRow
import com.past9.phoneaos.data.MemoryTransfer
import com.past9.phoneaos.ui.screens.MemoryActions
import com.past9.phoneaos.ui.screens.MemoryScreen
import com.past9.phoneaos.ui.screens.TransferNote
import com.past9.phoneaos.ui.screens.exported
import com.past9.phoneaos.ui.screens.imported
import com.past9.phoneaos.ui.theme.AppTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertCountEquals
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** What an import does is a card in the Memory list, never a pop-up. PNGs in app/build/screens-memory/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MemoryTransferScreenTest {
    @get:Rule val rule = createComposeRule()

    private val pages = listOf(
        MemoryRow(id = 1, title = "Sam Dlamini", body = "Founder of Northwind. Plain words, no pop-ups.", kind = "person", topics = "sam,owner", pinned = true),
        MemoryRow(id = 2, title = "Agent app", body = "The phone agent. 0.11.17 installed.", kind = "fact", topics = "agent,phone"),
        MemoryRow(id = 3, title = "Car insurance", body = "Renews every March.", kind = "fact", topics = "car,vehicle,imported,unsorted"),
    )
    private val bigImport = TransferNote.imported(MemoryTransfer.Summary(MemoryTransfer.Result(added = 992, unsorted = 41, pinnedDropped = 2), 0, true))

    private fun show(dark: Boolean, memories: List<MemoryRow>, note: TransferNote?) {
        RuntimeEnvironment.setQualifiers("w390dp-h1000dp-port-${if (dark) "night" else "notnight"}-xxhdpi")
        rule.setContent { AppTheme(dark = dark) { Surface(color = MaterialTheme.colorScheme.surface) { MemoryScreen(memories, MemoryActions(), 24.dp, initialNote = note) } } }
    }
    private fun shot(name: String) { File("build/screens-memory").mkdirs(); rule.onRoot().captureRoboImage("build/screens-memory/$name.png") }

    @Test fun theResultIsACardInTheListNotADialog() {
        show(true, pages, bigImport)
        rule.onNodeWithText("992 pages added").assertExists()
        rule.onNodeWithText("Your agent can search them now.", substring = true).assertExists()
        for (t in listOf("New", "992", "Unsorted", "41", "Show unsorted", "Done")) rule.onAllNodesWithText(t)[0].assertExists()
        assertTrue("no dialog window", rule.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
        shot("import-dark")
    }

    @Test fun lightModeToo() { show(false, pages, bigImport); rule.onNodeWithText("992 pages added").assertExists(); shot("import-light") }

    @Test fun showUnsortedFiltersTheListAndClearsTheCard() {
        show(true, pages, bigImport)
        rule.onNodeWithText("Show unsorted").performClick()
        rule.onAllNodesWithText("992 pages added").assertCountEquals(0)
        rule.onAllNodesWithText("Car insurance").assertCountEquals(1)
        rule.onAllNodesWithText("Agent app").assertCountEquals(0)   // filtered out: it isn't unsorted
        shot("unsorted-filter")
    }

    @Test fun doneClearsTheCard() {
        show(true, pages, bigImport)
        rule.onNodeWithText("Done").performClick()
        rule.onAllNodesWithText("992 pages added").assertCountEquals(0)
        rule.onAllNodesWithText("Agent app").assertCountEquals(1)
    }

    @Test fun backupSectionIsInTheListAndNothingPopsUp() {
        show(true, pages, null)
        rule.onNodeWithText("Back up and restore", ignoreCase = true).assertExists()
        rule.onNodeWithText("Import").assertExists(); rule.onNodeWithText("Export").assertExists()
        assertTrue(rule.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
        shot("backup-section")
    }

    @Test fun afterAWipeRestoreIsTheFirstThingOffered() {
        show(true, emptyList(), null)
        rule.onNodeWithText("Back up and restore", ignoreCase = true).assertExists(); rule.onNodeWithText("Import").assertExists()
        rule.onAllNodesWithText("Export").assertCountEquals(0)
        shot("empty-restore")
    }

    @Test fun exportedAndFailedNotes() {
        assertEquals("812 pages saved", TransferNote.exported(812).headline)
        show(false, pages, TransferNote.exported(3)); rule.onNodeWithText("3 pages saved").assertExists(); shot("exported-light")
    }
}
