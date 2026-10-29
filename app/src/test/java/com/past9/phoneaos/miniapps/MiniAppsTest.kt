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

    private val teams = "https://teams.microsoft.com/l/meetup-join/19%3ameeting_abc%40thread.v2/0?context=%7b%22Tid%22%3a%22x%22%7d"
    private val meet = "https://meet.google.com/abc-defg-hij"

    @Test fun onlyHttpsLinksOpen() {
        assertEquals(teams, MiniAppRules.openableUrl(teams))
        assertEquals(meet, MiniAppRules.openableUrl("  $meet "))
        for (bad in listOf("http://meet.google.com/x", "javascript:alert(1)", "intent://x#Intent;end", "msteams://l/x", "file:///sdcard/x", "https://user:pw@example.com/", "https://", "https:///x", "meet.google.com/x", "", null, "https://x.example/" + "a".repeat(5000))) {
            assertNull(bad, MiniAppRules.openableUrl(bad))
        }
    }

    @Test fun anOpenRequestOpensTheLinkTheComputerHandedBack() {
        val req = JSONObject().put("op", "open").put("id", 7).put("url", teams)
        val ok = JSONObject().put("op", "reply").put("id", 7).put("ok", true).put("result", JSONObject().put("open", teams))
        assertEquals(teams, MiniAppRules.linkToOpen(req, ok))
        // Refused on the computer (not https, too many): nothing opens and the app gets the computer's reason.
        val no = JSONObject().put("op", "reply").put("id", 7).put("ok", false).put("error", "Only https links open (not http).")
        assertNull(MiniAppRules.linkToOpen(req, no))
        // The computer answered with something that is not https: still nothing.
        assertNull(MiniAppRules.linkToOpen(req, JSONObject().put("ok", true).put("result", JSONObject().put("open", "http://x.example"))))
        // A computer from before openUrl: the phone's own check decides.
        val old = JSONObject().put("op", "reply").put("id", 7).put("ok", false).put("error", "Unknown request open.")
        assertEquals(teams, MiniAppRules.linkToOpen(req, old))
        assertNull(MiniAppRules.linkToOpen(JSONObject().put("op", "open").put("url", "http://x.example"), old))
        // Other requests are never links.
        assertNull(MiniAppRules.linkToOpen(JSONObject().put("op", "call").put("url", teams), ok))
        assertEquals("Opened.", MiniAppRules.openedReply(ok, null).optString("result"))
        val failed = MiniAppRules.openedReply(ok, "No app on this phone opens that link.")
        assertFalse(failed.optBoolean("ok"))
        assertEquals(7, failed.optInt("id"))
    }

    @Test fun linksAreRateLimited() {
        var t = 1_000L
        val limit = OpenLimiter({ t })
        repeat(4) { assertTrue(limit.take()) }
        assertFalse(limit.take())
        t += 30_000
        assertTrue(limit.take())
    }
}
