package com.past9.phoneaos

import com.past9.phoneaos.screen.ScreenElement
import com.past9.phoneaos.screen.ScreenFuzzy
import com.past9.phoneaos.screen.ScreenSkills
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Picking by words with no model, and filling a job's slots. */
@RunWith(RobolectricTestRunner::class)
class ScreenFuzzyTest {
    private fun el(i: Int, text: String) = ScreenElement(i, "TextView", text, "", "", "row", 0, i * 100, 700, i * 100 + 90, "c", 0)

    @Test fun theClearMatchWinsAndAWeakOrTiedOneIsNotAnAnswer() {
        val files = listOf(el(0, "Lease-2026.pdf"), el(1, "Invoice March.pdf"), el(2, "Holiday photos"), el(3, "Report (final).pdf"), el(4, "Report (draft).pdf"))
        assertEquals("Lease-2026.pdf", ScreenFuzzy.best(files, "lease 2026")!!.text)
        assertEquals("Invoice March.pdf", ScreenFuzzy.best(files, "Invoice  march.PDF")!!.text)
        assertEquals("Invoice March.pdf", ScreenFuzzy.best(files, "Invoce March.pdf")!!.text)
        assertNull("two reports are as close as each other", ScreenFuzzy.best(files, "Report.pdf"))
        assertNull(ScreenFuzzy.best(files, "Tax return"))
        assertEquals(3, ScreenFuzzy.closest(files, "Tax return").size)
        assertTrue(ScreenFuzzy.score("Thabo Nkosi", "Thábo  NKOSI") == 1.0)
    }

    @Test fun slotsAreFilledAndListed() {
        val step = JSONObject().put("do", "share").put("extra", JSONObject().put("files", "{file}").put("text", "{question}"))
        val steps = org.json.JSONArray().put(step).put(JSONObject().put("do", "tap").put("sel", JSONObject().put("text", "{contact}")))
        assertEquals(listOf("file", "question", "contact"), ScreenSkills.inputs(steps))
        assertEquals("/sdcard/a.pdf", ScreenSkills.fill("{file}", JSONObject().put("file", "/sdcard/a.pdf")))
        assertEquals("{question} about {file}", ScreenSkills.templatize("What is this about /sdcard/a.pdf", mapOf("file" to "/sdcard/a.pdf", "question" to "What is this")))
    }
}
