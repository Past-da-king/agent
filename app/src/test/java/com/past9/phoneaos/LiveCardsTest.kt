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
}
