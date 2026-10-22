package com.past9.phoneaos

import com.past9.phoneaos.remote.RemoteIds
import com.past9.phoneaos.remote.RemoteRows
import com.past9.phoneaos.remote.Sender
import com.past9.phoneaos.voice.CallController
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 22 Oct 2026 (Ayanda on a call): the voice spoke of "the agent" as someone else, and messages other agents sent
 * (the call's hand-off, a project writing to the main thread) showed on the phone as his own bubble. Each row says
 * who sent it (meta.via, as the computer writes it), the phone draws it that way, and the voice is the agent itself.
 */
class SenderTest {
    private fun computerRow(id: String, meta: JSONObject, text: String = "hi") = JSONObject().put("id", id).put("kind", "user").put("text", text)
        .put("meta", meta.toString()).put("createdAt", 1_792_676_929_746L)

    /** Talk about the agent as a separate being. */
    // "agent" in lower case: a project called "Agent Desktop App" is a name, not someone else.
    private val thirdPerson = Regex("\\b([Tt]he|[Yy]our|[Mm]ain|user's) (main )?agent\\b|\\bmain agent\\b|\\bhand(ed)? (it )?(over )?to\\b|\\bthe voice of\\b")

    @Test
    fun theComputersSenderSurvivesTheLinkAndIsNotHis() {
        val call = RemoteRows.chat(computerRow("c1", JSONObject().put("via", JSONObject().put("from", "call").put("name", "Pixel").put("chat", ""))), RemoteIds())
        assertEquals("From your call on Pixel", Sender.of(call.meta)?.line)
        assertFalse(Sender.isHis(call.kind, call.meta))
        val project = RemoteRows.chat(computerRow("c2", JSONObject().put("via", JSONObject().put("from", "project").put("name", "Agent Desktop App").put("chat", "pc1"))), RemoteIds())
        assertEquals("From Agent Desktop App", Sender.of(project.meta)?.line)
        val main = RemoteRows.chat(computerRow("c3", JSONObject().put("chat", "pc1").put("via", JSONObject().put("from", "main").put("name", "Hina").put("chat", "main"))), RemoteIds())
        assertEquals("From Hina, the main agent", Sender.of(main.meta)?.line)
        val helper = Sender.of(JSONObject().put("via", JSONObject().put("from", "helper").put("name", "Dictation fix")).toString())
        assertEquals("From the helper Dictation fix", helper?.line)
    }

    @Test
    fun hisOwnWordsStayHisAndJunkIsNeverAMadeUpSender() {
        val mine = RemoteRows.chat(computerRow("m1", JSONObject().put("origin", JSONObject().put("name", "Phone"))), RemoteIds())
        assertTrue(Sender.isHis(mine.kind, mine.meta))
        assertNull(Sender.of("{}"))
        assertNull(Sender.of(""))
        assertNull(Sender.of("not json"))
        assertNull(Sender.of(JSONObject().put("via", JSONObject().put("from", "alien").put("name", "x")).toString()))
        assertNull(Sender.of(JSONObject().put("via", "call").toString()))
        assertFalse(Sender.isHis("agent", "{}"))
    }

    @Test
    fun theVoiceIsTheAgentItselfInTheFirstPerson() {
        for (linked in listOf(true, false)) {
            val p = CallController.systemPrompt("Hina", "Ayanda", linked)
            assertTrue(p, p.startsWith("You are Hina, Ayanda's personal agent"))
            assertTrue(p, p.contains("I'm on it"))
            assertFalse(p, thirdPerson.containsMatchIn(p))
            val decls = CallController.callTools(linked).getJSONObject(0).getJSONArray("functionDeclarations")
            for (i in 0 until decls.length()) {
                val d = decls.getJSONObject(i)
                assertFalse(d.getString("name"), d.getString("name").contains("agent"))
                assertFalse(d.getString("description"), thirdPerson.containsMatchIn(d.getString("description")))
            }
        }
        val roster = CallController.roster(org.json.JSONArray().put(JSONObject().put("kind", "main").put("name", "Hina").put("running", false)))
        assertFalse(roster, thirdPerson.containsMatchIn(roster))
        val card = RemoteRows.chat(JSONObject().put("id", "a1").put("kind", "approval").put("text", "Send it")
            .put("meta", JSONObject().put("summary", "Send it").put("decision", "").toString()).put("createdAt", 1L), RemoteIds())
        val said = CallController.cardPrompt(card)
        assertTrue(said, said.contains("I need your OK to Send it. Approve?"))
        assertFalse(said, thirdPerson.containsMatchIn(said) || said.contains("The agent"))
    }
}
