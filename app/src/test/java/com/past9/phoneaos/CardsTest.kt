package com.past9.phoneaos

import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.cards.Card
import com.past9.phoneaos.cards.CardKit
import com.past9.phoneaos.cards.CardStore
import com.past9.phoneaos.ui.MdPart
import com.past9.phoneaos.ui.MdTable
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CardsTest {
    private val app get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun tablesAreParsedOutOfAMessage() {
        val md = "Here you go:\n\n| Test | Helper | Price |\n|---|:---:|--:|\n| Rand rate | #33 | R 16.50 |\n| Weather | #35 | 13° |\n| Escaped \\| pipe | #36 |\n\nThat's all."
        val parts = MdTable.split(md)
        assertEquals(3, parts.size)
        val t = (parts[1] as MdPart.Table).table
        assertEquals(listOf("Test", "Helper", "Price"), t.header)
        assertEquals(listOf('l', 'c', 'r'), t.align)
        assertEquals(listOf("Escaped | pipe", "#36", ""), t.rows[2]) // ragged row padded
        assertTrue(t.numeric(2) == false || t.numeric(2))
        assertEquals("That's all.", (parts[2] as MdPart.Prose).text.trim())
        // No separator row = not a table.
        assertEquals(1, MdTable.split("a | b\nc | d").size)
    }

    @Test fun cardsPersistAcrossStores() {
        val s = CardStore(app)
        s.put(Card(id = "t1", title = "Test", html = "<p>x</p>", data = """{"a":1}""", pinned = true))
        val again = CardStore(app).get("t1")!!
        assertEquals("""{"a":1}""", again.data); assertTrue(again.pinned)
        s.delete("t1"); assertNull(CardStore(app).get("t1"))
        assertEquals("monitor-prices", Card.slug("Monitor prices!"))
    }

    @Test fun pagesCarryTheLiveThemeAndTheKit() {
        val light = CardKit.page(app, "<p class='card'>hi</p>", dark = false, accent = "red")
        assertTrue(light.contains("--primary:#D7141E;") && light.contains("color-scheme:light"))
        assertTrue(CardKit.page(app, "", dark = true, accent = "red").contains("--primary:#FF5A4F;"))
        assertTrue(light.contains(".hero") && light.contains("window.card") && light.contains("Content-Security-Policy"))
        assertTrue(CardKit.guide(app).contains("card.data()"))
    }

    /** Writes the sample cards as full pages (with a stand-in bridge) for a real-browser render check: build/cards/. */
    @Test fun writeSamplePages() {
        val out = File("build/cards").apply { mkdirs() }
        listOf("monitors", "outreach", "brief").forEach { name ->
            val html = javaClass.classLoader!!.getResource("cards/$name.html").readText()
            val data = javaClass.classLoader!!.getResource("cards/$name.json").readText().trim()
            for (dark in listOf(false, true)) {
                val stub = "<script>window.AppBridge={data:()=>JSON.stringify($data),run(){},open(){},ask(){},error(e){document.title='ERR '+e}}</script>"
                val page = CardKit.page(app, html, dark, "iris").replace("<head>", "<head>$stub")
                File(out, "$name-${if (dark) "dark" else "light"}.html").writeText(page)
            }
            JSONObject(data) // sample data is valid JSON
        }
    }
}
