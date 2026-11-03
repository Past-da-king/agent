package com.past9.phoneaos

import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.remote.LinkState
import com.past9.phoneaos.remote.RemoteTrouble
import com.past9.phoneaos.voice.CallController
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Phone UI bugs 3 and 4 (22 Oct): no machine name in the chat, and the call button is always there. */
@RunWith(RobolectricTestRunner::class)
class PhoneUi34Test {
    private val src = File("src/main/java/com/past9/phoneaos")

    @Test fun troubleBarOnlyWhenNothingCanAnswer() {
        assertFalse(RemoteTrouble.shows(LinkState.Connecting))
        assertFalse(RemoteTrouble.shows(LinkState.Paused))
        assertTrue(RemoteTrouble.shows(LinkState.NoAgents("None of your computers answered.")))
        assertFalse(RemoteTrouble.TEXT.contains("DESKTOP"))
    }

    @Test fun noMachineNameInTheConversationUi() {
        val main = File(src, "MainActivity.kt").readText()
        assertFalse("strip gone", main.contains("RemoteStrip"))
        assertFalse("header pill has no machine name", Regex("modelLabel = if \\(remote != null\\) \"On ").containsMatchIn(main))
        val link = File(src, "remote/LinkUi.kt").readText()
        assertFalse(link.contains("Answered by"))
        val session = File(src, "remote/RemoteSession.kt").readText()
        assertTrue(session.contains("private fun brainName(): String = \"your computer\""))
        assertFalse(session.contains("\"Working on \${brainName()}\""))
    }

    @Test fun callButtonNeverGatedOnTheOldSwitch() {
        val main = File(src, "MainActivity.kt").readText()
        assertFalse(main.contains("onCall = if (settings.liveEnabled)"))
        assertEquals(2, Regex("onCall = \\{ startCall\\(\\) \\}").findAll(main).count())
    }

    @Test fun callReasonOnlyWithoutAKey() {
        assertNull(CallController.reason(hasGeminiKey = true, linked = false))
        assertNull(CallController.reason(hasGeminiKey = true, linked = true))
        val alone = CallController.reason(hasGeminiKey = false, linked = false)
        val linked = CallController.reason(hasGeminiKey = false, linked = true)
        assertNotNull(alone); assertNull(linked)
        assertTrue(alone!!.contains("Settings, Voice"))
    }

    @Test fun linkedCallFindsTheComputersOpenQuestion() {
        val answered = ChatItem(id = 1, kind = "question", text = "Which one?", meta = JSONObject().put("answer", "A").toString())
        val open = ChatItem(id = 2, kind = "question", text = "Send it?", meta = JSONObject().put("options", org.json.JSONArray().put("Yes").put("No")).toString())
        val agent = ChatItem(id = 3, kind = "agent", text = "Working on it")
        assertEquals(2L, CallController.openQuestion(listOf(answered, open, agent))?.id)
        val approval = ChatItem(id = 4, kind = "approval", text = "Pay R100")
        assertEquals(4L, CallController.openQuestion(listOf(answered, open, approval))?.id)
        assertTrue(CallController.isApproval(approval))
        assertTrue(CallController.isApproval(ChatItem(kind = "question", text = "APPROVAL|Send mail|x")))
        assertFalse(CallController.isApproval(open))
        assertNull(CallController.openQuestion(listOf(answered, agent)))
    }
}
