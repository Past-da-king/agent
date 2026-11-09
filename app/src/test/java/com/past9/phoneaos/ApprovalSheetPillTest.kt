package com.past9.phoneaos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.agent.AgentStatus
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.ui.LocalSheetPreview
import com.past9.phoneaos.ui.NewBelowPill
import com.past9.phoneaos.ui.screens.Approval
import com.past9.phoneaos.ui.screens.ApprovalCard
import com.past9.phoneaos.ui.screens.ApprovalSheet
import com.past9.phoneaos.ui.screens.ChatActions
import com.past9.phoneaos.ui.screens.ChatScreen
import com.past9.phoneaos.ui.theme.AppTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * The approval card says WHAT is being approved and opens a slide-up sheet with all of it and Approve and Decline
 * pinned at the bottom; the pill over the chat counts real messages only. 390 wide, light and dark.
 * PNGs land in app/build/audit/fxA/<name>-light|dark.png.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ApprovalSheetPillTest(private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
    @get:Rule val rule = createComposeRule()

    private fun start(content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("w390dp-h844dp-xxhdpi")
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme(dark = dark) { content() } }
        rule.mainClock.advanceTimeBy(900)
    }
    private fun tick(ms: Long = 900) = rule.mainClock.advanceTimeBy(ms)
    private fun cap(name: String) {
        File("build/audit/fxA").mkdirs()
        rule.onRoot().captureRoboImage(File("build/audit/fxA/$name-${if (dark) "dark" else "light"}.png").path)
    }
    @Composable private fun Parts(content: @Composable () -> Unit) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }

    private var nextId = 1L
    private fun item(kind: String, text: String, meta: JSONObject = JSONObject()) = ChatItem(id = nextId++, kind = kind, text = text, meta = meta.toString(), createdAt = 1_000L + nextId)

    private val longDetails = buildString {
        append("**To:** thabo@example.com, lindiwe@example.com\n\n**Subject:** Venue confirmed for the year-end function\n\n")
        append("Hi both,\n\nThe venue is confirmed for Friday 20 November. Here is what was agreed:\n\n")
        (1..24).forEach { append("- Point $it: the agreed detail for item $it, written out in full so nothing is cut.\n") }
        append("\nPlease reply by Wednesday if anything is wrong.\n\nLAST LINE OF THE EMAIL")
    }
    /** The computer's approval as the phone holds it (remote/RemoteRows.card): summary and details whole in meta. */
    private fun remoteApproval(summary: String, details: String, extra: JSONObject.() -> Unit = {}) =
        item("question", "APPROVAL|${summary.replace('|', '/')}|$details", JSONObject().put("remoteKind", "approval").put("summary", summary).put("details", details).put("scope", "computer").apply(extra))

    @Test fun whatAnApprovalSays() {
        val a = Approval.of(remoteApproval("send the email | to the venue team", longDetails) { put("by", "Venue research") })
        assertEquals("Send the email | to the venue team", a.summary)
        assertEquals("the details are whole", longDetails, a.details)
        assertEquals("Venue research", a.askedBy)
        assertEquals("your computer", a.target)
        assertTrue(a.pending); assertFalse(a.failed)
        // The phone's own agent asks in the text form, with no meta.
        val local = Approval.of(item("question", "APPROVAL|Run a command on Studio|$ rm -rf build | tee log.txt"))
        assertEquals("Run a command on Studio", local.summary)
        assertEquals("$ rm -rf build | tee log.txt", local.details)
        assertTrue(local.isCommand)
        assertEquals("Your agent", local.askedBy)
        val done = Approval.of(remoteApproval("Send it", "x") { put("answer", "Decline") })
        assertFalse(done.pending); assertEquals("Declined", done.decided)
    }

    @Test fun cardsInTheChat() {
        val items = listOf(
            item("user", "Tell the venue team it is confirmed"),
            item("agent", "I have the email ready. It needs your yes before it goes."),
            remoteApproval("Send the email to the venue team", longDetails) { put("by", "Venue research") },
            remoteApproval("Run the clean-up on the build server", "$ ssh build 'rm -rf /srv/builds/old && docker system prune --all --force --volumes'") { put("decideFailed", true) },
            remoteApproval("Post the update in the team channel", "Short update") { put("answer", "Approve") },
            remoteApproval("Delete the draft folder", "12 files") { put("answer", "Decline") },
        )
        start { ChatScreen(items, AgentStatus(true, "Waiting for you"), "Nova", "Sam", false, "", {}, ChatActions()) }
        tick()
        cap("approval-cards-chat")
    }

    @Test fun theCardSaysWhatAndOpensTheSheet() {
        val answers = mutableListOf<Pair<Long, String>>()
        val pending = remoteApproval("Send the email to the venue team", longDetails) { put("by", "Venue research") }
        start { CompositionLocalProvider(LocalSheetPreview provides true) { Parts { ApprovalCard(pending) { id, o -> answers += id to o } } } }
        rule.onNodeWithText("Send the email to the venue team").assertIsDisplayed()
        rule.onNodeWithText("Asked by Venue research", substring = true).assertIsDisplayed()
        rule.onNodeWithText("To: thabo@example.com", substring = true).assertExists()
        cap("approval-card")
        rule.onNodeWithText("Review").performClick(); tick()
        rule.onNodeWithText("Approve").assertIsDisplayed()
        rule.onNodeWithText("Decline").assertIsDisplayed()
        rule.onNodeWithText("Approve").performClick(); tick()
        assertEquals(listOf(pending.id to "Approve"), answers)
    }

    @Test fun theSheetShowsAllOfItWithTheButtonsPinned() {
        val a = Approval.of(remoteApproval("Send the email to the venue team", longDetails) { put("by", "Venue research") })
        val picked = mutableListOf<String>()
        start { CompositionLocalProvider(LocalSheetPreview provides true) { ApprovalSheet(a, onDecide = { picked += it }, onDismiss = {}) } }
        // Every word is in the sheet (it scrolls), and the buttons are on screen without scrolling.
        rule.onNodeWithText("LAST LINE OF THE EMAIL", substring = true).assertExists()
        rule.onNodeWithText("Point 24", substring = true).assertExists()
        rule.onNodeWithText("Approve").assertIsDisplayed()
        rule.onNodeWithText("Decline").assertIsDisplayed()
        cap("approval-sheet-long")
        rule.onNodeWithText("Decline").performClick(); tick()
        assertEquals(listOf("Decline"), picked)
    }

    @Test fun aCommandInTheSheet() {
        val a = Approval.of(item("question", "APPROVAL|Run a command on the build server|$ ssh build 'rm -rf /srv/builds/old && docker system prune --all --force --volumes' && echo cleaned | tee -a /var/log/cleanup.log"))
        start { CompositionLocalProvider(LocalSheetPreview provides true) { ApprovalSheet(a, onDecide = {}, onDismiss = {}) } }
        rule.onNodeWithText("/var/log/cleanup.log", substring = true).assertIsDisplayed()
        cap("approval-sheet-command")
    }

    @Test fun aFailedDecisionSaysSoAndKeepsTheButtons() {
        val failed = remoteApproval("Send the email to the venue team", "To: thabo@example.com") { put(Approval.FAILED_KEY, true) }
        start { CompositionLocalProvider(LocalSheetPreview provides true) { Parts { ApprovalCard(failed) { _, _ -> } } } }
        rule.onNodeWithText("That did not go through. Try again.").assertIsDisplayed()
        rule.onNodeWithText("Review").performClick(); tick()
        assertTrue(rule.onAllNodesWithText("That did not go through. Try again.").fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("Approve").assertIsDisplayed()
        rule.onNodeWithText("Decline").assertIsDisplayed()
        cap("approval-sheet-failed")
    }

    // Not proven here: under Robolectric the tap on the one-line decided row does not open the sheet (the pending card's
    // Review tap does, and theSheetAfterADecision draws the decided sheet). Check the tap on a real phone, then un-ignore.
    @org.junit.Ignore("the tap on the decided row is not delivered under Robolectric; check on a phone")
    @Test fun aDecidedCardSaysWhatWasDecidedAndStillOpens() {
        val done = remoteApproval("Post the update in the team channel", "The whole update, as it was posted.") { put("answer", "Approve") }
        start { CompositionLocalProvider(LocalSheetPreview provides true) { Parts { androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 120.dp)); ApprovalCard(done) { _, _ -> } } } }
        rule.onNodeWithText("Approved").assertIsDisplayed()
        rule.onNodeWithText("Approved").performClick(); tick()
        cap("approval-decided-opened")
        rule.onNodeWithText("You approved this", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("The whole update, as it was posted.", substring = true).assertExists()
        assertTrue(rule.onAllNodesWithText("Approve").fetchSemanticsNodes().isEmpty())
        cap("approval-sheet-decided")
    }

    @Test fun theSheetAfterADecision() {
        val a = Approval.of(remoteApproval("Delete the draft folder", "12 files in Drafts will be deleted.") { put("answer", "Decline") })
        start { CompositionLocalProvider(LocalSheetPreview provides true) { ApprovalSheet(a, onDecide = {}, onDismiss = {}) } }
        cap("approval-sheet-declined")
        rule.onNodeWithText("You declined this", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("12 files in Drafts will be deleted.", substring = true, useUnmergedTree = true).assertExists()
        assertTrue(rule.onAllNodesWithText("Approve").fetchSemanticsNodes().isEmpty())
    }

    @Test fun thePillLooks() {
        start { Parts { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) {
            NewBelowPill(true, 3) {}; NewBelowPill(true, 1) {}; NewBelowPill(true, 0) {} } } } }
        tick()
        rule.onNodeWithText("3 new messages", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("1 new message", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Jump to latest", useUnmergedTree = true).assertExists()
        cap("pill-states")
    }

    @Test fun thePillInTheChatSaysThreeNewMessages() {
        var items by mutableStateOf((1..30).map { item(if (it % 2 == 0) "agent" else "user", "Message $it of the earlier conversation, long enough to fill a line or two on the phone.") })
        start { ChatScreen(items, AgentStatus(true, "Working"), "Nova", "Sam", false, "", {}, ChatActions()) }
        tick(1500)
        // He scrolls up to read.
        rule.onRoot().performTouchInput { swipeDown(startY = height * 0.3f, endY = height * 0.8f, durationMillis = 300) }
        tick(2500)
        rule.onNodeWithText("Jump to latest", useUnmergedTree = true).assertExists()
        // Work goes on below: steps written twice, a helper, notices, cards, an empty row, and three real messages.
        val steps = (1..40).map { item("activity", "Read file $it", JSONObject().put("status", "running")) }
        items = items + item("notice", "New chat", JSONObject().put("divider", true)) + steps; tick(300)
        items = items.map { if (it.kind == "activity") it.copy(meta = JSONObject().put("status", "done").toString()) else it }; tick(300)
        val helper = item("helper", "Research the venue", JSONObject().put("state", "working"))
        items = items + helper + (1..60).map { item("activity", "Step $it", JSONObject().put("helper", helper.id)) }; tick(300)
        items = items.map { if (it.id == helper.id) it.copy(meta = JSONObject().put("state", "working").put("steps", 60).toString()) else it }; tick(300)
        items = items + item("notice", "Switched model") + item("question", "Which venue?", JSONObject().put("answer", "A")) +
            remoteApproval("Book venue A", "Fri 20 Nov") { put("answer", "Approve") } + item("agent", "") +
            item("agent", "Venue A is booked.") + item("report", "Venue comparison", JSONObject().put("path", "/reports/venues.md")) + item("agent", "Anything else for the function?")
        tick(1500)
        rule.onNodeWithText("3 new messages", useUnmergedTree = true).assertExists()
        cap("pill-3-new-messages")
        // He taps it: down to the latest, and nothing is new any more.
        rule.onNodeWithText("3 new messages", useUnmergedTree = true).performClick(); tick(2500)
        assertTrue(rule.onAllNodesWithText("3 new messages", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }
}
