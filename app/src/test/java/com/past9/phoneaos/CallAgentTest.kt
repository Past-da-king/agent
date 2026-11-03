package com.past9.phoneaos

import com.past9.phoneaos.remote.RemoteIds
import com.past9.phoneaos.remote.RemoteRows
import com.past9.phoneaos.voice.CallController
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 22 Oct 2026: on a linked call the voice said it couldn't delegate, talk to anyone or send anything, and the
 * computer's approvals never showed on the call screen. The voice must hand everything to the agent, say approvals out
 * loud, and take a yes by voice only when the user really said it.
 */
class CallAgentTest {
    private fun approvalRow(id: String) = JSONObject().put("id", id).put("kind", "approval").put("text", "Send the invoice to Shey")
        .put("meta", JSONObject().put("summary", "Send the invoice to Shey").put("details", "To: shey@example.com").put("decision", "").toString())
        .put("createdAt", 1_792_676_929_746L)

    @Test
    fun theVoiceIsToldItCanDoAnythingAndNeverToRefuse() {
        val p = CallController.systemPrompt("Hina", "Ayanda", linked = true)
        assertTrue(p.contains("NEVER say you can't"))
        assertTrue(p.contains("start_work"))
        assertTrue(p.contains("computer"))
        assertTrue(p.contains("Approve?"))
        assertFalse(CallController.systemPrompt("Hina", "Ayanda", linked = false).contains("their computer"))
    }

    @Test
    fun theCallDeclaresTheHandOffTheAnswerAndTheStatusTools() {
        val decls = CallController.callTools(true).getJSONObject(0).getJSONArray("functionDeclarations")
        val names = (0 until decls.length()).map { decls.getJSONObject(it).getString("name") }
        assertEquals(listOf("start_work", "answer_question", "work_status"), names)
        assertTrue(decls.getJSONObject(0).getString("description").contains("on their computer"))
        assertTrue(decls.getJSONObject(1).getJSONObject("parameters").getJSONObject("properties").has("user_said"))
    }

    @Test
    fun aComputerApprovalIsTheCallsOpenCardAndIsSaidOutLoud() {
        val item = RemoteRows.chat(approvalRow("a1"), RemoteIds())
        val open = CallController.openQuestion(listOf(item))
        assertNotNull(open)
        assertTrue(CallController.isApproval(open!!))
        assertEquals("Send the invoice to Shey", CallController.approvalWhat(open))
        val said = CallController.cardPrompt(open)
        assertTrue(said, said.contains("I need your OK to Send the invoice to Shey. Approve?"))
        // Answered: no longer the open card.
        val answered = item.copy(meta = JSONObject(item.meta).put("answer", "Approve").toString())
        assertEquals(null, CallController.openQuestion(listOf(answered)))
    }

    @Test
    fun linkedTheVoiceKnowsWhoIsOnTheComputerAndCanNameATarget() {
        val decls = CallController.callTools(true).getJSONObject(0).getJSONArray("functionDeclarations")
        val props = decls.getJSONObject(0).getJSONObject("parameters").getJSONObject("properties")
        assertTrue(props.has("target")); assertTrue(props.has("new_chat"))
        val alone = CallController.callTools(false).getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0).getJSONObject("parameters").getJSONObject("properties")
        assertFalse(alone.has("target"))
        assertTrue(CallController.systemPrompt("Hina", "Ayanda", true).contains("I'm on it in the Acme Website project"))
        assertTrue(CallController.systemPrompt("Hina", "Ayanda", true).contains("don't guess and don't send it to main"))
        val targets = org.json.JSONArray()
            .put(JSONObject().put("kind", "main").put("name", "Hina").put("running", false).put("doing", ""))
            .put(JSONObject().put("kind", "project").put("name", "Agent Desktop App").put("running", true).put("doing", "Wiring the RPC")
                .put("chats", org.json.JSONArray().put(JSONObject().put("title", "Phone call routing").put("running", true))))
            .put(JSONObject().put("kind", "helper").put("name", "Desktop dictation fix").put("running", true).put("doing", "Building the installer"))
        val r = CallController.roster(targets)
        assertTrue(r, r.contains("- Your main chat (target main), idle"))
        assertTrue(r, r.contains("- Your project Agent Desktop App, working: Wiring the RPC. Chats: \"Phone call routing\" (working)"))
        assertTrue(r, r.contains("- Your helper \"Desktop dictation fix\", working: Building the installer"))
        assertEquals("", CallController.roster(null))
    }

    @Test
    fun aYesByVoiceOnlyCountsWhenTheUserSaidIt() {
        assertTrue(CallController.saidYes("Yeah, go ahead"))
        assertTrue(CallController.saidYes("yes please send it"))
        assertFalse(CallController.saidYes("no don't send it"))
        assertFalse(CallController.saidYes("wait, what is it?"))
        assertFalse(CallController.saidYes("what's the weather"))
        assertFalse(CallController.saidYes(""))
        assertTrue(CallController.isNo("Decline"))
        assertFalse(CallController.isNo("Approve"))
    }
}
