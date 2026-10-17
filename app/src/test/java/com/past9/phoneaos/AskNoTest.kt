package com.past9.phoneaos

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.past9.phoneaos.agent.AskOptions
import com.past9.phoneaos.ui.openui.AskCard
import com.past9.phoneaos.ui.openui.AskProgram
import com.past9.phoneaos.ui.openui.LocalOpenUiSend
import com.past9.phoneaos.ui.openui.OpenUiLang
import com.past9.phoneaos.ui.theme.AppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Whatever an agent asks of the user has a No, last, drawn as an OpenUI card; a tap answers the question and sends no chat message. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2000dp-port-night-xxhdpi")
class AskNoTest {
    @get:Rule val rule = createComposeRule()

    private val picked = mutableListOf<String>(); private val sent = mutableListOf<String>()
    private fun show(question: String, options: List<String>, why: String? = null) {
        rule.setContent { AppTheme(dark = true) { Surface(color = MaterialTheme.colorScheme.surface) {
            CompositionLocalProvider(LocalOpenUiSend provides { sent += it }) { AskCard("Needs you", why, question, options, { picked += it }) }
        } } }
    }

    @Test fun noIsAddedLastWhenTheAgentOfferedNone() {
        assertEquals(listOf("Open browser", "Done", "Skip", "No"), AskOptions.withNo(listOf("Open browser", "Done", "Skip")))
        assertEquals(listOf("Red", "Blue", "No"), AskOptions.withNo(listOf("Red", "Blue")))
    }

    @Test fun anExistingWayToRefuseIsLeftAlone() {
        for (o in listOf(listOf("Allow", "No"), listOf("Open settings", "Not now"), listOf("Approve", "Decline"), listOf("Yes", "No thanks")))
            assertEquals(o, AskOptions.withNo(o))
        assertEquals(listOf("Open browser", "Done", "Skip", "No"), AskOptions.withNo(AskOptions.withNo(listOf("Open browser", "Done", "Skip"))))
    }

    @Test fun aNotificationKeepsNoAmongItsThreeButtons() {
        assertEquals(listOf("Open browser", "Done", "No"), AskOptions.fit(listOf("Open browser", "Done", "Skip", "No"), 3))
        assertEquals(listOf("Allow", "Not now"), AskOptions.fit(listOf("Allow", "Not now"), 3))
    }

    @Test fun theProgramParsesEvenWithQuotesAndNewlines() {
        val src = AskProgram.build("Needs you", "Nova: needs a \"code\"", "Can I read your \"Gmail\"?\nSecond line \\ here", listOf("Open browser", "Done"))
        val r = OpenUiLang.render(OpenUiLang.parse(src))
        assertTrue(r.errors.toString(), r.errors.isEmpty()); assertTrue(r.unresolved.isEmpty()); assertNotNull(r.root)
    }

    @Test fun everyButtonShowsAndNoAnswersTheQuestion() {
        show("Open the browser and sign in to Bank?", listOf("Open browser", "Done", "Skip"), "Nova: needs your sign-in")
        listOf("Open browser", "Done", "Skip", "No").forEach { rule.onAllNodesWithText(it)[0].assertExists() }
        rule.onNodeWithText("No").performClick()
        assertEquals(listOf("No"), picked)
        assertTrue("an answer is not a chat message: $sent", sent.isEmpty())
    }

    @Test fun aQuestionWithItsOwnNoDoesNotGetASecond() {
        show("Can I look at your latest photos?", listOf("Allow", "No"))
        assertEquals(1, rule.onAllNodesWithText("No").fetchSemanticsNodes().size)
        rule.onNodeWithText("Allow").performClick()
        assertEquals(listOf("Allow"), picked)
    }

    @Test fun aTappedButtonStaysTappable() {
        show("Open the browser and sign in?", listOf("Open browser", "Done"))
        rule.onNodeWithText("Open browser").performClick()
        rule.onNodeWithText("Done").performClick()
        assertEquals(listOf("Open browser", "Done"), picked)
    }
}
