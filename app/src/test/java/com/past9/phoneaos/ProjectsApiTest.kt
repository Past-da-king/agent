package com.past9.phoneaos

import com.past9.phoneaos.data.GoalRow
import com.past9.phoneaos.data.TaskRow
import com.past9.phoneaos.remote.ProjectDraft
import com.past9.phoneaos.remote.ProjectRow
import com.past9.phoneaos.remote.ProjectsApi
import com.past9.phoneaos.ui.screens.profileGoals
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Projects from the phone (9 Nov 2026): what goes to the computer when one is made, and how a chat is started in one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectsApiTest {
    private class Computer(val said: Set<String> = emptySet(), val projects: JSONArray = JSONArray()) {
        val calls = ArrayList<Pair<String, List<Any?>>>()
        val api = ProjectsApi { method, args ->
            calls += method to args
            when (method) {
                "data.list" -> projects
                "data.upsert" -> JSONObject((args[1] as JSONObject).toString()).apply { if (!has("id")) put("id", "p_new") }
                "data.where" -> { val chat = (args[1] as JSONObject).getString("chat")
                    if (chat in said) JSONArray().put(JSONObject().put("id", "r1").put("kind", "user").put("text", "hello").put("meta", JSONObject().put("chat", chat).toString())) else JSONArray() }
                "profiles.list" -> JSONArray().put(JSONObject().put("id", "g").put("name", "General").put("general", true)).put(JSONObject().put("id", "pr1").put("name", "Work"))
                else -> null
            }
        }
    }

    @Test fun `the create payload is exactly what the computer's own form sends`() {
        val p = ProjectsApi.createPayload(ProjectDraft(name = "  Garden   plan  ", description = " beds and paths ", profile = "pr1", glyph = "globe", color = "teal", path = " /home/me/garden "))
        assertEquals(setOf("chats", "name", "description", "aliases", "icon", "path", "profile", "hidden", "ai"), p.keys().asSequence().toSet())
        assertEquals(0, p.getJSONArray("chats").length())
        assertEquals("Garden plan", p.getString("name"))
        assertEquals("beds and paths", p.getString("description"))
        assertEquals(0, p.getJSONArray("aliases").length())
        assertEquals("globe", p.getJSONObject("icon").getString("glyph"))
        assertEquals("teal", p.getJSONObject("icon").getString("color"))
        assertEquals(setOf("glyph", "color"), p.getJSONObject("icon").keys().asSequence().toSet())
        assertEquals("/home/me/garden", p.getString("path"))
        assertEquals("pr1", p.getString("profile"))
        assertFalse(p.getBoolean("hidden"))
        assertEquals("""{"main":{"account":"","model":""},"helpers":{"account":"","model":""}}""", p.getJSONObject("ai").toString())
    }

    @Test fun `defaults are General, no folder, the folder icon in iris`() {
        val p = ProjectsApi.createPayload(ProjectDraft(name = "A"))
        assertEquals("", p.getString("profile")); assertEquals("", p.getString("path")); assertEquals("", p.getString("description"))
        assertEquals("folder", p.getJSONObject("icon").getString("glyph")); assertEquals("iris", p.getJSONObject("icon").getString("color"))
    }

    @Test fun `the name is trimmed and capped at 60, the description at 600`() {
        val p = ProjectsApi.createPayload(ProjectDraft(name = "  " + "n".repeat(80) + "  ", description = "d".repeat(700)))
        assertEquals(60, p.getString("name").length)
        assertEquals(600, p.getString("description").length)
        assertFalse(ProjectsApi.canCreate(ProjectDraft(name = "   ")))
        assertTrue(ProjectsApi.canCreate(ProjectDraft(name = " x ")))
    }

    @Test fun `an icon is a known glyph or one emoji, a colour one of the nine`() {
        assertEquals("folder", ProjectsApi.cleanGlyph("nonsense"))
        assertEquals("server", ProjectsApi.cleanGlyph("server"))
        assertEquals("🌱", ProjectsApi.cleanGlyph("🌱🌱"))
        assertEquals("iris", ProjectsApi.createPayload(ProjectDraft(name = "A", color = "sunflower")).getJSONObject("icon").getString("color"))
        assertEquals(20, ProjectsApi.GLYPHS.size); assertEquals(9, ProjectsApi.COLORS.size)
    }

    @Test fun `create sends one upsert and answers with the new id`() = runBlocking {
        val c = Computer()
        val row = c.api.create(ProjectDraft(name = "Garden"))
        assertEquals("p_new", row.id)
        assertEquals(listOf("data.upsert"), c.calls.map { it.first })
        assertEquals("project", c.calls[0].second[0])
        assertFalse((c.calls[0].second[1] as JSONObject).has("id"))
        assertTrue(runCatching { c.api.create(ProjectDraft(name = "  ")) }.isFailure)
    }

    @Test fun `a new chat reuses the first chat with nothing said in it`() = runBlocking {
        val c = Computer(said = setOf("pcaaaaaaaaaaaaaaaa"))
        val ref = c.api.startChat(ProjectRow("p1", "Garden", chats = listOf("pcaaaaaaaaaaaaaaaa", "pcbbbbbbbbbbbbbbbb", "pccccccccccccccccc")))
        assertEquals("pcbbbbbbbbbbbbbbbb", ref.chat); assertFalse(ref.fresh)
        assertTrue(c.calls.none { it.first == "data.upsert" || it.first == "memory.newChat" })
        val where = c.calls.first { it.first == "data.where" }.second
        assertEquals("chat_item", where[0])
        assertEquals("""{"chat":"pcaaaaaaaaaaaaaaaa","exceptKinds":["branch"],"limit":20}""", where[1].toString())
    }

    @Test fun `when every chat has something said a new pc id is appended`() = runBlocking {
        val c = Computer(said = setOf("pcaaaaaaaaaaaaaaaa"))
        val ref = c.api.startChat(ProjectRow("p1", "Garden", chats = listOf("pcaaaaaaaaaaaaaaaa")), from = "pcaaaaaaaaaaaaaaaa")
        assertTrue(ref.fresh); assertTrue(ref.chat, ProjectsApi.isChatId(ref.chat)); assertTrue(Regex("pc[a-z0-9]{16}").matches(ref.chat))
        val up = c.calls.single { it.first == "data.upsert" }.second
        assertEquals("project", up[0])
        assertEquals(JSONObject().put("id", "p1").put("chats", JSONArray(listOf("pcaaaaaaaaaaaaaaaa", ref.chat))).toString(), up[1].toString())
        assertEquals(JSONObject().put("chat", ref.chat).put("from", "pcaaaaaaaaaaaaaaaa").toString(), c.calls.single { it.first == "memory.newChat" }.second[0].toString())
        assertEquals(listOf("pcaaaaaaaaaaaaaaaa", ref.chat), ref.project.chats)
    }

    @Test fun `a project with no chats gets its first one, and nothing is filed when he came from elsewhere`() = runBlocking {
        val c = Computer()
        val ref = c.api.startChat(ProjectRow("p1", "Garden"), from = "main")
        assertTrue(ProjectsApi.isChatId(ref.chat))
        assertTrue(c.calls.none { it.first == "memory.newChat" })
        assertNotEquals(ProjectsApi.newChatId(), ProjectsApi.newChatId())
    }

    @Test fun `hidden projects are skipped and the archive comes last`() = runBlocking {
        fun row(id: String, name: String, hidden: Boolean = false, at: Long = 0) = JSONObject().put("id", id).put("name", name).put("hidden", hidden).put("updatedAt", at)
            .put("icon", JSONObject().put("glyph", "code").put("color", "teal")).put("chats", JSONArray(listOf("pc1", "pc2")))
        val c = Computer(projects = JSONArray(listOf(row("earlier-chats", "Earlier chats", at = 900), row("a", "Older", at = 100), row("h", "Secret", hidden = true, at = 500), row("b", "Newer", at = 300))))
        val list = c.api.list()
        assertEquals(listOf("b", "a", "earlier-chats"), list.map { it.id })
        assertEquals("pc2", list[0].latestChat); assertEquals("code", list[0].glyph); assertEquals("teal", list[0].color)
        assertTrue(list.last().isArchive)
        assertEquals(listOf("" to "General", "pr1" to "Work"), c.api.profiles())
    }

    @Test fun `the profile shows at most three goals, open only, blocked first`() {
        val goals = (1L..6L).map { GoalRow(it, "Goal $it", status = if (it == 6L) "achieved" else "open") }
        val tasks = listOf(TaskRow(id = 1, title = "Stuck", goalId = 4, status = "blocked"))
        val shown = profileGoals(goals, tasks)
        assertEquals(3, shown.size)
        assertEquals(4L, shown.first().id)
        assertTrue(shown.all { it.status == "open" })
        assertEquals(2, profileGoals(goals.take(2), emptyList()).size)
    }
}
