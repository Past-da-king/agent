package com.past9.phoneaos

import com.past9.phoneaos.data.AgentProfile
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Linked to a computer, Connections > Profiles shows the computer's profiles, not the phone's own (empty) list. */
@RunWith(RobolectricTestRunner::class)
class ComputerProfilesTest {
    private val raw = JSONArray("""[
      {"id":"general","name":"General","color":"graphite","accounts":[],"general":true},
      {"id":"p1","name":"Acme Works","color":"ocean","accounts":[{"id":"slack_a","app":"slack","label":"Acme Inc"},{"id":"github_b","app":"github","label":"Past-da-king"}],"general":false},
      {"id":"p2","name":"Personal","color":"rose","accounts":[{"id":"gmail_c","app":"gmail","label":"me@gmail.com"}]}
    ]""")

    @Test fun generalIsLeftOutAndAccountsKeepTheirApp() {
        val ps = AgentProfile.fromComputer(raw)
        assertEquals(listOf("Acme Works", "Personal"), ps.map { it.name })
        assertEquals(listOf("slack", "github"), ps[0].accounts.map { it.slug })
        assertEquals("ocean", ps[0].color)
        assertTrue(ps[1].has("gmail_c"))
    }

    @Test fun nothingUsableIsEmpty() {
        assertEquals(emptyList<AgentProfile>(), AgentProfile.fromComputer(null))
        assertEquals(emptyList<AgentProfile>(), AgentProfile.fromComputer("nope"))
    }
}
