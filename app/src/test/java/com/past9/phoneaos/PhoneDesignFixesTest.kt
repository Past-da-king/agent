package com.past9.phoneaos

import com.past9.phoneaos.miniapps.MiniAppRules
import com.past9.phoneaos.remote.MeshDevice
import com.past9.phoneaos.remote.Mentions
import com.past9.phoneaos.tools.Connection
import com.past9.phoneaos.ui.screens.accountRows
import com.past9.phoneaos.ui.screens.profileChoices
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ayanda's phone feedback of 29 Oct 2026 (17:09 to 17:54): the parts that are logic, not looks. */
class PhoneDesignFixesTest {
    @Test
    fun anAccountListedOncePerProfileIsOneRow() {
        // Connections showed Fathom three times: the computer lists an account once per profile it is in.
        val f = Connection("ca_1", "fathom", "ACTIVE", "fathom")
        val rows = accountRows(listOf(f, f.copy(), f.copy(), Connection("ca_2", "gmail", "ACTIVE", "a@x.com"), Connection("ca_3", "gmail", "ACTIVE", "b@x.com")))
        assertEquals(listOf("fathom", "gmail", "gmail"), rows.map { it.first.toolkit })
        assertEquals(1, rows.first().second)
        // Two connections of the same sign-in are one row that says so.
        val twice = accountRows(listOf(f, f.copy(id = "ca_9")))
        assertEquals(1, twice.size); assertEquals(2, twice.first().second)
    }

    @Test
    fun generalAndNoProfileAreOneChoice() {
        val c = profileChoices(listOf("p_gen" to "General", "p_se" to "Acme Works", "p_g" to "Acme", "p_se" to "Acme Works"))
        assertEquals(listOf("General", "Acme Works", "Acme"), c.map { it.title })
        assertEquals("", c.first().id) // General = no profile
    }

    @Test
    fun aProjectReplyCarriesItsPictures() {
        // The answer card's reply (and every project send) used to go with an empty attachment list.
        val att = JSONArray().put(JSONObject().put("path", "C:\\x\\1.jpg").put("mime", "image/jpeg"))
        val args = Mentions.replyArgs("pc1", " look ", att)
        assertEquals("look", args[0]); assertEquals(1, (args[1] as JSONArray).length()); assertEquals("pc1", args[2])
        assertEquals("main", (args[3] as JSONObject).optString("replyIn"))
    }

    @Test
    fun aMiniAppLinkOpensThatApp() {
        assertEquals("man3rfqy4e5c92", MiniAppRules.idFromLink("agent://miniapp/man3rfqy4e5c92"))
        assertNull(MiniAppRules.idFromLink("agent://pair?x=1"))
        assertNull(MiniAppRules.idFromLink("https://example.com/agent://miniapp/x1"))
        assertEquals(listOf("man3rfqy4e5c92"), MiniAppRules.linksIn("Here it is.\n[Open Week calendar](agent://miniapp/man3rfqy4e5c92) and again agent://miniapp/man3rfqy4e5c92"))
    }

    @Test
    fun hisMachinesShowWithTheirState() {
        val now = 1_800_000_000_000L
        val raw = JSONArray()
            .put(JSONObject().put("id", "dev_pc").put("name", "DESKTOP-EXAMPLE").put("kind", "desktop").put("roles", JSONArray(listOf("brain", "hands"))).put("lastSeen", now))
            .put(JSONObject().put("id", "dev_srv").put("name", "home server").put("kind", "server").put("roles", JSONArray(listOf("brain"))).put("lastSeen", now - 60_000))
            .put(JSONObject().put("id", "dev_me").put("name", "Pixel 7").put("kind", "phone").put("roles", JSONArray(listOf("screen"))))
            .put(JSONObject().put("id", "dev_old").put("name", "Old").put("kind", "phone").put("revoked", true))
            .put(JSONObject().put("id", "dev_lap").put("name", "Laptop").put("kind", "desktop").put("lastSeen", now - 86_400_000L))
        val d = MeshDevice.parse(raw, self = "dev_me", answering = "dev_pc", ready = emptySet(), now = now)
        assertEquals(listOf("DESKTOP-EXAMPLE", "home server", "Pixel 7", "Laptop"), d.map { it.name })
        assertEquals(listOf(MeshDevice.State.ANSWERING, MeshDevice.State.ONLINE, MeshDevice.State.SELF, MeshDevice.State.AWAY), d.map { it.state })
        assertTrue(d.last().line.contains("last seen"))
    }

    @Test
    fun theComputersLearnedAppsAreRead() {
        val raw = JSONArray().put(JSONObject().put("id", "la_1").put("name", "Takealot").put("startUrl", "https://www.takealot.com").put("trusted", false)
            .put("tools", JSONArray().put(JSONObject().put("name", "search_products").put("readOnly", true)).put(JSONObject().put("name", "add_to_cart").put("readOnly", false))))
        val apps = MeshDevice.learned(raw)
        assertEquals(1, apps.size); assertEquals("Takealot", apps[0].name); assertEquals("takealot.com", apps[0].host); assertEquals(1, apps[0].writes)
    }
}
