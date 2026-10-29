package com.past9.phoneaos.miniapps

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's side of mini-apps: the slim list, the pinned order, what the WebView may load, and session routing. */
class MiniAppsTest {
    private fun row(id: String, name: String, pinned: Int) = JSONObject().put("id", id).put("name", name).put("description", "d").put("icon", "cal").put("pinned", pinned).put("version", 2).put("checked", true)

    @Test fun readsTheSlimListAndKeepsTheSidebarOrder() {
        val list = MiniAppRules.parseList(JSONArray().put(row("mazzzzzzzz1", "Water", 2)).put(row("maaaaaaaaa1", "Calendar", 1)).put(row("mabbbbbbbb1", "Notes", 0)).put(row("bad id", "X", 1)))
        assertEquals(listOf("Calendar", "Notes", "Water"), list.map { it.name })
        assertEquals(listOf("Calendar", "Water"), MiniAppRules.pinned(list).map { it.name })
        assertTrue(MiniAppRules.parseList("nope").isEmpty())
    }

    @Test fun theFrameLoadsOnlyItsOwnAddress() {
        val id = "maaaaaaaaa1"
        assertTrue(MiniAppRules.mayLoad(id, "https", "$id.miniapp.invalid"))
        assertFalse(MiniAppRules.mayLoad(id, "http", "$id.miniapp.invalid"))
        assertFalse(MiniAppRules.mayLoad(id, "https", "mabbbbbbbb1.miniapp.invalid"))
        assertFalse(MiniAppRules.mayLoad(id, "https", "example.com"))
        assertFalse(MiniAppRules.mayLoad(id, "file", null))
    }

    @Test fun pushesGoOnlyToTheirSession() {
        val p = JSONObject().put("session", "s1").put("message", JSONObject().put("op", "event").put("topic", "data.task"))
        assertEquals("data.task", MiniAppRules.pushFor("s1", p)?.optString("topic"))
        assertNull(MiniAppRules.pushFor("s2", p))
        assertEquals("theme", MiniAppRules.themeMessage(true, "teal").optString("op"))
    }
}
