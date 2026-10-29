package com.past9.phoneaos

import com.past9.phoneaos.remote.MentionCache
import com.past9.phoneaos.remote.MentionProject
import com.past9.phoneaos.remote.MentionReplies
import com.past9.phoneaos.remote.RemoteIds
import com.past9.phoneaos.remote.RemoteRows
import com.past9.phoneaos.remote.Sender
import com.past9.phoneaos.remote.Mentions
import com.past9.phoneaos.remote.RemoteSession
import com.past9.phoneaos.ui.screens.concerns
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 29 Oct 2026: '@' in the message box offers the computer's projects; the computer sends a message that names one to
 * that project's chat and leaves "Sent to X" in the main chat, which opens it here. The list is kept on the phone and
 * costs a few bytes to check on mobile data.
 */
class MentionsTest {
    private fun p(id: String, name: String, vararg aliases: String) = MentionProject(id, name, aliases.toList(), "bolt", "teal", "", 0)
    private val projects = listOf(p("a", "Acme"), p("b", "Agent Desktop App"), p("c", "Nova Print", "Nova"), p("d", "Blue Ridge Platform"), p("e", "Acorn Notes"))

    @Test
    fun ranksTheWayTheComputerDoes() {
        assertEquals(listOf("Acme", "Acorn Notes"), Mentions.rank("ac", projects).map { it.name })
        assertEquals("Agent Desktop App", Mentions.rank("desk", projects).first().name)
        assertEquals("Agent Desktop App", Mentions.rank("ada", projects).first().name)
        assertEquals("Blue Ridge Platform", Mentions.rank("blridge", projects).first().name)
        assertEquals("Nova Print", Mentions.rank("nova", projects).first().name)
        assertTrue(Mentions.rank("zzz", projects).isEmpty())
        assertEquals(5, Mentions.rank("", projects, limit = 10).size)
    }

    @Test
    fun findsTheAtWordAtTheCaretButNotInAnEmail() {
        assertEquals(0 to "ac", Mentions.atCaret("@ac", 3))
        assertEquals(4 to "agent desk", Mentions.atCaret("ask @agent desk", 15))
        assertNull(Mentions.atCaret("me@mail.com", 11))
        assertNull(Mentions.atCaret("nothing", 7))
    }

    @Test
    fun writesThePickedNameIn() {
        assertEquals("@Acme " to 6, Mentions.insert("@ac", 3, "Acme"))
        assertEquals("@Acme fix it" to 6, Mentions.insert("@ac fix it", 3, "Acme"))
        assertEquals("please @Agent Desktop App " to 26, Mentions.insert("please @agent de", 16, "Agent Desktop App"))
    }

    @Test
    fun closesOnceTheWordIsAWholeNameAndASpace() {
        assertTrue(Mentions.done("Acme ", projects))
        assertTrue(Mentions.done("nova ", projects))
        assertFalse(Mentions.done("Acme", projects))
        assertFalse(Mentions.done("Agent ", projects))
    }

    @Test
    fun readsTheSentLineTheComputerWrites() {
        val meta = JSONObject().put("mention", JSONObject().put("project", "a").put("name", "Acme").put("chat", "pc123456").put("fresh", true)).toString()
        val n = Mentions.noticeOf(meta)!!
        assertEquals("pc123456", n.chat); assertEquals("Acme", n.name); assertTrue(n.fresh)
        assertNull(Mentions.noticeOf("{}"))
        assertNull(Mentions.noticeOf(JSONObject().put("divider", true).toString()))
    }

    @Test
    fun keepsTheListAndTakesSameAsNoChange() {
        val store = HashMap<String, String>()
        val cache = MentionCache({ store[it] }, { k, v -> if (v == null) store.remove(k) else store[k] = v })
        assertTrue(cache.projects.isEmpty()); assertEquals("", cache.stamp)
        val answer = JSONObject().put("stamp", "s1").put("projects", Mentions.toJson(projects.take(2)))
        assertTrue(cache.take(answer))
        assertEquals(listOf("Acme", "Agent Desktop App"), cache.projects.map { it.name })
        assertEquals("s1", cache.stamp)
        assertFalse(cache.take(JSONObject().put("stamp", "s1").put("same", true)))
        assertEquals(2, cache.projects.size)
        // A restart reads the kept copy: no call needed to show the picker.
        val again = MentionCache({ store[it] }, { k, v -> if (v == null) store.remove(k) else store[k] = v })
        assertEquals(listOf("Acme", "Agent Desktop App"), again.projects.map { it.name })
        assertEquals("s1", again.stamp)
        // An older computer without chat.mentions answers nothing: the list stays.
        assertFalse(cache.take(null))
        assertEquals(2, cache.projects.size)
    }

    @Test
    fun theProjectChatFollowsOnlyItsOwnEvents() {
        val mine = JSONObject().put("entity", "chat_item").put("row", JSONObject().put("id", "r1").put("meta", JSONObject().put("chat", "pc1").toString()))
        val other = JSONObject().put("entity", "chat_item").put("row", JSONObject().put("id", "r2").put("meta", "{}"))
        assertTrue(concerns(RemoteSession.EVT_DATA, mine, "pc1"))
        assertFalse(concerns(RemoteSession.EVT_DATA, other, "pc1"))
        assertTrue(concerns(RemoteSession.EVT_CHAT, JSONObject().put("chat", "pc1").put("type", "run_finished"), "pc1"))
        assertFalse(concerns(RemoteSession.EVT_CHAT, JSONObject().put("chat", "pc2").put("type", "run_finished"), "pc1"))
        assertFalse(concerns("other", JSONArray(), "pc1"))
    }

    private fun replyRow(id: String, text: String, at: Long) = JSONObject().put("id", id).put("kind", "notice").put("text", text).put("createdAt", at)
        .put("meta", JSONObject().put("mentionReply", JSONObject().put("project", "a").put("name", "Acme").put("chat", "pc1").put("glyph", "bolt").put("color", "teal")).toString())

    @Test
    fun aProjectsAnswerIsACardAndToldOnce() {
        val now = 1_800_000_000_000L
        val r = Mentions.replyOf(replyRow("x", "Fixed 3 follow-ups.", now).optString("meta"))!!
        assertEquals("Acme", r.name); assertEquals("pc1", r.chat); assertEquals("teal", r.color)
        assertNull(Mentions.replyOf("{}"))
        val change = JSONObject().put("entity", "chat_item").put("row", replyRow("r-told-1", "Fixed 3 follow-ups.", now))
        assertEquals("Acme" to "Fixed 3 follow-ups.", MentionReplies.toTell(change, now))
        assertNull("the same answer is told once", MentionReplies.toTell(change, now + 1000))
        assertNull("an old answer a fresh read brings back is not told", MentionReplies.toTell(JSONObject().put("entity", "chat_item").put("row", replyRow("r-old", "old", now - 3_600_000)), now))
        assertNull("a plain notice is not an answer", MentionReplies.toTell(JSONObject().put("entity", "chat_item").put("row", JSONObject().put("id", "n1").put("kind", "notice").put("text", "hi").put("meta", "{}")), now))
        assertNull("a deletion is not an answer", MentionReplies.toTell(JSONObject().put("entity", "chat_item").put("deleted", true).put("id", "r2"), now))
    }

    @Test
    fun aReplyOnTheCardGoesToTheSameProjectChat() {
        val args = Mentions.replyArgs("pc1", "  yes, send them ")
        assertEquals("yes, send them", args[0]); assertEquals("pc1", args[2])
        assertEquals("main", (args[3] as JSONObject).optString("replyIn"))
    }

    @Test
    fun aProjectChatIsRead20AtATime() {
        val first = Mentions.pageWhere("pc1", 20)
        assertEquals(20, first.optInt("limit")); assertTrue(first.optBoolean("newestFirst")); assertFalse(first.has("before")); assertEquals("pc1", first.optString("chat"))
        assertEquals(123L, Mentions.pageWhere("pc1", 20, 123L).optLong("before"))
        val a = (1..3).map { JSONObject().put("id", "m$it").put("createdAt", it * 10L).put("text", "v1") }
        val newer = listOf(JSONObject().put("id", "m3").put("createdAt", 30L).put("text", "v2"), JSONObject().put("id", "m4").put("createdAt", 40L).put("text", "v1"))
        val older = listOf(JSONObject().put("id", "m0").put("createdAt", 0L).put("text", "v1"))
        val merged = Mentions.merge(Mentions.merge(a, newer), older)
        assertEquals(listOf("m0", "m1", "m2", "m3", "m4"), merged.map { it.optString("id") })
        assertEquals("v2", merged.first { it.optString("id") == "m3" }.optString("text"))
    }

    @Test
    fun aProjectWritingToTheMainChatReadsCleanly() {
        // message_main on the computer: the row's text is the message itself, meta.via says which project and chat.
        val row = JSONObject().put("id", "pm1").put("kind", "user").put("text", "The follow-ups are drafted, 4 wait for a yes.").put("createdAt", 1_800_000_000_000L)
            .put("meta", JSONObject().put("via", JSONObject().put("from", "project").put("name", "Acme").put("chat", "pc1").put("title", "Follow-ups")).toString())
        val item = RemoteRows.chat(row, RemoteIds())
        assertEquals("The follow-ups are drafted, 4 wait for a yes.", item.text)
        assertFalse(item.text.contains("[From"))
        assertEquals("From Acme · Follow-ups", Sender.of(item.meta)?.line)
        assertFalse(Sender.isHis(item.kind, item.meta))
        // "Sent to" keeps what he sent for the line
        val sent = Mentions.noticeOf(JSONObject().put("mention", JSONObject().put("project", "a").put("name", "Acme").put("chat", "pc1").put("said", "fix the follow-ups")).toString())!!
        assertEquals("fix the follow-ups", sent.said)
    }
}
