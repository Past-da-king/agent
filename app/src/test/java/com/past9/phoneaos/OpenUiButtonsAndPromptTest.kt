package com.past9.phoneaos

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.agent.AgentRuntime
import com.past9.phoneaos.agent.PromptRole
import com.past9.phoneaos.data.AppDb
import com.past9.phoneaos.data.SettingsStore
import com.past9.phoneaos.ui.MdPart
import com.past9.phoneaos.ui.MdTable
import com.past9.phoneaos.ui.openui.LocalOpenUiSend
import com.past9.phoneaos.ui.openui.OpenUiBlock
import com.past9.phoneaos.ui.openui.OpenUiLang
import com.past9.phoneaos.ui.openui.OpenUiPrompt
import com.past9.phoneaos.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A tap on a button the agent made comes back into the chat as the user's message, naming the item; links open; the agent is taught to use it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2000dp-port-night-xxhdpi")
class OpenUiButtonsAndPromptTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun res(name: String) = javaClass.classLoader!!.getResource("openui/$name")!!.readText()
    private fun block(name: String) = MdTable.split(res("$name.md")).filterIsInstance<MdPart.OpenUi>().first().source

    private val sent = mutableListOf<String>(); private val opened = mutableListOf<String>()
    private fun show(name: String) {
        rule.setContent { AppTheme(dark = true) { Surface(color = MaterialTheme.colorScheme.surface) {
            CompositionLocalProvider(LocalOpenUiSend provides { sent += it }, LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { opened += uri } }) {
                OpenUiBlock(block(name))
            } } } }
    }

    @Test fun bookViewingAsksTheAgentAboutThatFlat() {
        show("flats")
        rule.onAllNodesWithText("Book viewing")[0].performClick()
        val msg = sent.single()
        assertTrue(msg, msg.startsWith("Book a viewing"))
        assertTrue(msg, msg.contains("RR4794507") && msg.contains("R9,500"))
        assertFalse("not the other flats", msg.contains("RR4787859") || msg.contains("RR4762518"))
    }

    @Test fun openListingOpensTheLinkAndSendsNothing() {
        show("flats")
        rule.onAllNodesWithText("Open listing")[0].performClick()
        assertEquals(listOf("https://www.privateproperty.co.za/to-rent/kwazulu-natal/durban/umhlanga/umhlanga-ridge/RR4794507"), opened)
        assertTrue(sent.isEmpty())
    }

    @Test fun detailsNamesTheJob() {
        show("helpers")
        rule.onAllNodesWithText("Details")[1].performClick()
        assertTrue(sent.single(), sent.single().contains("Sne"))
    }

    @Test fun sendOnlyAsksTheAgentItNeverSendsByItself() {
        show("approval")
        rule.onAllNodesWithText("Send")[0].performClick()
        val msg = sent.single()
        assertTrue(msg, msg.contains("Send the invoice email to Shey") && msg.contains("Invoice Submission"))
        assertTrue(opened.isEmpty())
    }

    @Test fun messageNamesCardItemAndContextOnce() {
        val a = OpenUiLang.Action.ToAssistant("Research more", mapOf("listing" to "RR4794507", "item" to "R9,500 1-bed"))
        val m = OpenUiLang.messageFor(a, OpenUiLang.Scope(card = "Flats in Umhlanga Ridge", item = "R9,500 1-bed"))
        assertEquals("Research more\n↳ R9,500 1-bed · Flats in Umhlanga Ridge · listing: RR4794507", m)
        assertEquals("Do it", OpenUiLang.messageFor(OpenUiLang.Action.ToAssistant("Do it", null), OpenUiLang.Scope()))
    }

    @Test fun placesWithoutCardsGetTitlesNotCode() {
        val t = OpenUiLang.plainText(res("flats.md"))
        assertFalse(t, t.contains("openui") || t.contains("root ="))
        assertTrue(t, t.contains("R9,500 a month") && t.startsWith("Here are three"))
    }

    @Test fun thePromptSectionTeachesOnlyWhatTheAppDraws() {
        val p = OpenUiPrompt.section(ctx)
        assertTrue(p.startsWith("GENERATIVE UI"))
        assertTrue(p.contains("UI BY DEFAULT") && p.contains("One Card per item") && p.contains("approval flow"))
        assertFalse("no em dashes", p.contains('—') || p.contains('–'))
        val signatures = Regex("""(?m)^([A-Z][A-Za-z]+)\(""").findAll(p).map { it.groupValues[1] }.toSet()
        assertTrue(signatures.size >= 20)
        assertEquals("every component in the prompt is drawn", emptySet<String>(), signatures - OpenUiLang.LIBRARY.keys)
        assertFalse("charts are not pushed", signatures.any { it.endsWith("Chart") })
    }

    @Test fun theMainAgentAndRoutinesGetItHelpersDoNot() = runBlocking {
        val db = AppDb.inMemory(ctx); val settings = SettingsStore(ctx)
        val rt = AgentRuntime(ctx, db, settings, CoroutineScope(SupervisorJob() + Dispatchers.IO), FakePhone(), { null })
        assertTrue(rt.systemPrompt("find me a flat").contains("GENERATIVE UI"))
        assertTrue(rt.systemPrompt("brief", role = PromptRole.ROUTINE).contains("GENERATIVE UI"))
        assertFalse(rt.systemPrompt("check prices", role = PromptRole.HELPER).contains("GENERATIVE UI"))
    }
}
