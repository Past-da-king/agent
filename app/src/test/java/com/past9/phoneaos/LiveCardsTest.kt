package com.past9.phoneaos

import com.past9.phoneaos.remote.LiveCards
import com.past9.phoneaos.remote.RemoteIds
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The computer's helper cards (desktop shared/helper-cards.ts HelperCardInfo) as the phone reads and draws them. */
class LiveCardsTest {
    private val snapshot = JSONObject("""
        {"seq":7,"at":1,"cards":[
          {"id":"pa-pc1","row":"pa-pc1","chat":"main","label":"Garden","state":"working","queued":false,"line":"Running the tests","done":"",
           "depth":0,"projectChat":"pc1","owner":{"kind":"project","id":"p1","name":"Garden","icon":{"glyph":"heart","color":"teal"}},
           "model":"","startedAt":1000,"endedAt":null,"brief":"Plan the beds","result":"","steps":[{"text":"Read the repo","status":"done","at":1001}]},
          {"id":"h2","row":"h2","chat":"pc1","label":"Seeds","state":"done","queued":false,"line":"x","done":"Found three shops",
           "depth":1,"projectChat":"","owner":{"kind":"project","id":"p1","name":"Garden","icon":{"glyph":"heart","color":"teal"}},
           "model":"","startedAt":1002,"endedAt":2000,"brief":"Find seeds","result":"Found three shops\nmore","steps":[]},
          {"id":"h3","row":"h3","chat":"main","label":"Weather","state":"working","queued":false,"line":"Checking","done":"",
           "depth":0,"projectChat":"","owner":{"kind":"main"},"model":"","startedAt":1003,"endedAt":null,"brief":"","result":"","steps":[]}
        ]}""")

    @Test fun parsesInOrderWithDepthAndOwner() {
        val (seq, cards) = LiveCards.parse(snapshot)!!
        assertEquals(7L, seq)
        assertEquals(listOf("pa-pc1", "h2", "h3"), cards.map { it.id })
        assertEquals(listOf(0, 1, 0), cards.map { it.depth })
        assertTrue(cards[0].isProject && cards[0].working)
        assertEquals("teal", cards[0].ownerColor)
        assertEquals(0L, cards[0].endedAt)
        assertEquals("Found three shops", LiveCards.line(cards[1]))
        assertNull(LiveCards.parse(JSONObject("{}")))
    }

    @Test fun groupsKeepHelpersWithTheirProject() {
        val (_, cards) = LiveCards.parse(snapshot)!!
        assertEquals(listOf(listOf("pa-pc1", "h2"), listOf("h3")), com.past9.phoneaos.remote.groupsOf(cards).map { g -> g.map { it.id } })
    }

    @Test fun drawsAsThePhonesHelperCard() {
        val ids = RemoteIds()
        val (_, cards) = LiveCards.parse(snapshot)!!
        val project = LiveCards.view(cards[0], ids)
        assertEquals("Garden", project.label)
        assertEquals("Garden", project.project)
        assertEquals("Running the tests", project.now)
        assertEquals(listOf("Read the repo"), project.steps.map { it.text })
        val done = LiveCards.view(cards[1], ids)
        assertEquals("done", done.state)
        assertTrue(done.result.startsWith("Found three shops"))
        // Under its project agent's card it needs no "which project" tag; the main agent's helper has no project tile.
        assertEquals("", done.profile)
        assertEquals("", LiveCards.view(cards[2], ids).project)
        // The same card keeps the same id while it is redrawn.
        assertEquals(project.id, LiveCards.view(cards[0], ids).id)
    }

    private fun card(id: String, project: String?, working: Boolean, depth: Int = 0) = LiveCards.card(JSONObject()
        .put("id", id).put("state", if (working) "working" else "done").put("depth", depth)
        .put("owner", if (project == null) JSONObject().put("kind", "main") else JSONObject().put("kind", "project").put("name", project).put("icon", JSONObject().put("glyph", "heart").put("color", "teal"))))!!

    @Test fun pillCountsWorkingAgentsPerPlace() {
        val cards = listOf(card("a", "Garden", true), card("a1", "Garden", true, 1), card("a2", "Garden", false, 1),
            card("m", null, true), card("b", "Shop", true), card("b1", "Shop", true, 1), card("b2", "Shop", true, 1), card("c", "Old", false))
        val g = com.past9.phoneaos.remote.pillGroups(cards)
        assertEquals(listOf("p:Garden" to 2, "main" to 1, "p:Shop" to 3), g.map { it.key to it.count })
        assertEquals("a", g[0].card.id)
        assertTrue(g[0].isProject && !g[1].isProject)
        // Nothing working: no pill.
        assertTrue(com.past9.phoneaos.remote.pillGroups(listOf(card("x", "Garden", false))).isEmpty())
    }

    @Test fun foldsPastTwoUntilHeChooses() {
        val f: (String, Int) -> Boolean = { c, n -> com.past9.phoneaos.remote.foldedFor(c, n) }
        assertEquals(listOf(false, false, false, true, true), listOf(0, 1, 2, 3, 8).map { f("", it) })
        assertEquals(listOf(false, false, false), listOf(0, 3, 8).map { f("open", it) })
        assertEquals(listOf(false, true, true), listOf(0, 1, 8).map { f("closed", it) })
        val fold = com.past9.phoneaos.remote.AgentsFold(null)
        assertTrue(fold.folded(3)); fold.set(true); assertTrue(!fold.folded(8)); fold.set(false); assertTrue(fold.folded(1))
    }
}
