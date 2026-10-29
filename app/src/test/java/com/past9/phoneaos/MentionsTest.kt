package com.past9.phoneaos

import com.past9.phoneaos.remote.MentionCache
import com.past9.phoneaos.remote.MentionProject
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
}
