package com.past9.phoneaos

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.agent.AgentStatus
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.ui.MdPart
import com.past9.phoneaos.ui.MdTable
import com.past9.phoneaos.ui.openui.OpenUiLang
import com.past9.phoneaos.ui.screens.ChatActions
import com.past9.phoneaos.ui.screens.ChatScreen
import com.past9.phoneaos.ui.theme.AppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp

/** SPIKE: OpenUI Lang 1.0 drawn natively in the chat. PNGs land in app/build/screens-openui/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-port-night-xxhdpi")
class OpenUiSpikeTest {
    @get:Rule val rule = createComposeRule()

    companion object {
        /** An answer in OpenUI's inline mode: prose, then a fenced program. */
        val ANSWER = """
Here's where your money went in **September**. Groceries and transport are most of it.

```openui-lang
root = Card([header, chart, split, table, tip, actions], sources)
header = CardHeader("September spending", "R 7 640 across 41 transactions")
chart = BarChart(["Jul", "Aug", "Sep"], [spend, budget])
spend = Series("Spent", [6900, 8120, 7640])
budget = Series("Budget", [7500, 7500, 7500])
split = PieChart(["Groceries", "Transport", "Data", "Eating out"], [2950, 2210, 1180, 1300], "donut")
table = Table([Col("Merchant", ["Checkers", "Bolt", "Vodacom", "Nando's"]), Col("Visits", [9, 14, 2, 4]), Col("Total (R)", [2950, 2210, 1180, 860])])
tip = Callout("warning", "Bolt is up 38% on August", "14 trips, mostly weekday mornings. A monthly MyCiTi pass would cost R 1 400.")
actions = Buttons([setBudget, details])
setBudget = Button("Set a R 7 000 budget", @ToAssistant("Set my monthly budget to R 7 000 and warn me at 80%"))
details = Button("Show every Bolt trip", @ToAssistant("List every Bolt trip in September"), "secondary")
sources = [{ title: "MyBank statement", sourceName: "MyBank", url: "https://mybank.example" }]
```
        """.trimIndent()
    }

    @Test fun parsesTheSampleIntoATree() {
        val r = OpenUiLang.render(OpenUiLang.parse(ANSWER.substringAfter("```openui-lang").substringBefore("```")))
        assertEquals(emptyList<String>(), r.errors)
        val root = r.root!!
        assertEquals("Card", root.type)
        assertEquals(listOf("CardHeader", "BarChart", "PieChart", "Table", "Callout", "Buttons"), (root.props["children"] as List<*>).map { (it as OpenUiLang.Node).type })
        val btn = ((root.props["children"] as List<*>).last() as OpenUiLang.Node).props["buttons"] as List<*>
        assertEquals(OpenUiLang.Action.ToAssistant("Set my monthly budget to R 7 000 and warn me at 80%", null), (btn[0] as OpenUiLang.Node).props["action"])
    }

    @Test fun aHalfStreamedProgramStillRenders() {
        // Cut mid-string and mid-list: the open string and brackets close themselves, a pending name is just left out.
        val p = OpenUiLang.parse("root = Card([header, chart])\nheader = CardHeader(\"Monthly Rev")
        assertTrue(p.incomplete)
        val r = OpenUiLang.render(p)
        val kids = r.root!!.props["children"] as List<*>
        assertEquals(1, kids.size)
        assertEquals("Monthly Rev", (kids[0] as OpenUiLang.Node).props["title"])
        assertEquals(setOf("chart"), r.unresolved)
    }

    @Test fun aBrokenLineDropsOnlyItself() {
        val r = OpenUiLang.render(OpenUiLang.parse("root = Card([a, b, c])\na = TextContent(\"ok\")\nb = TextContent(\"bad\" ))) oops\nc = Wobble(1)"))
        assertEquals(listOf("TextContent"), (r.root!!.props["children"] as List<*>).map { (it as OpenUiLang.Node).type })
        assertTrue(r.errors.any { it.contains("unknown-component Wobble") })
        assertTrue(r.errors.any { it.startsWith("line 3") })
    }

    @Test fun namesCanBeUsedBeforeTheyAreDefinedAndLaterOnesWin() {
        val r = OpenUiLang.render(OpenUiLang.parse("x = TextContent(\"first\")\nroot = Card([x])\nx = TextContent(\"second\")"))
        assertEquals("second", ((r.root!!.props["children"] as List<*>)[0] as OpenUiLang.Node).props["text"])
    }

    @Test fun theFenceSplitsOutOfTheMarkdown() {
        val parts = MdTable.split(ANSWER)
        assertEquals(2, parts.size)
        assertTrue(parts[0] is MdPart.Prose); assertTrue(parts[1] is MdPart.OpenUi)
        // While streaming the closing fence hasn't arrived yet: still a generated UI, not prose.
        assertTrue(MdTable.split(ANSWER.substringBeforeLast("```")).last() is MdPart.OpenUi)
    }

    private fun shot(name: String, items: List<ChatItem>) {
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme(dark = true) { Surface(color = MaterialTheme.colorScheme.surface) {
            ChatScreen(items, AgentStatus(), "Nova", "Sam", false, "", {}, ChatActions()) } } }
        rule.mainClock.advanceTimeBy(900)
        rule.onRoot().captureRoboImage(File("build/screens-openui/$name.png").path)
    }

    private val now = System.currentTimeMillis()
    /** The whole answer at once (a tall phone-width canvas), to see every block. */
    @Test @Config(qualifiers = "w390dp-h1900dp-port-night-xxhdpi") fun wholeAnswerTall() {
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme(dark = true) { Surface(color = MaterialTheme.colorScheme.surface) {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(16.dp)) { com.past9.phoneaos.ui.Markdown(ANSWER) } } } }
        rule.mainClock.advanceTimeBy(900)
        rule.onRoot().captureRoboImage(File("build/screens-openui/openui-answer-full-phone-dark.png").path)
    }
    @Test fun chatWithGeneratedUi() = shot("openui-chat-phone-dark", listOf(
        ChatItem(id = 1, kind = "user", text = "Where did my money go last month?", meta = "{}", createdAt = now),
        ChatItem(id = 2, kind = "agent", text = ANSWER, meta = "{}", createdAt = now),
    ))
    @Test fun chatWithGeneratedUiStreaming() = shot("openui-chat-phone-dark-streaming", listOf(
        ChatItem(id = 1, kind = "user", text = "Where did my money go last month?", meta = "{}", createdAt = now),
        ChatItem(id = 2, kind = "agent", text = ANSWER.substringBefore("split = PieChart"), meta = "{}", createdAt = now),
    ))
}
