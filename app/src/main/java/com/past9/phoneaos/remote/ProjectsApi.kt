package com.past9.phoneaos.remote

import org.json.JSONArray
import org.json.JSONObject

/** One of the computer's projects, as the phone lists it. */
data class ProjectRow(
    val id: String, val name: String, val description: String = "", val glyph: String = "folder", val color: String = "iris", val image: String = "",
    val chats: List<String> = emptyList(), val hidden: Boolean = false, val profile: String = "", val activeAt: Long = 0L,
) {
    /** The chat a tap opens: the newest one. */
    val latestChat: String get() = chats.lastOrNull().orEmpty()
    val isArchive: Boolean get() = id == ProjectsApi.ARCHIVE_ID
}

/** What he typed into the New project sheet. */
data class ProjectDraft(val name: String = "", val description: String = "", val profile: String = "", val glyph: String = "folder", val color: String = "iris", val path: String = "")

/** A chat to open: its id and the project it belongs to. */
data class ProjectChatRef(val chat: String, val project: ProjectRow, val fresh: Boolean)

/**
 * Projects on the linked computer, from the phone (9 Nov 2026): list them, make one, start a chat in one. Everything
 * goes through the computer's own data calls (data.list, data.upsert, data.where, memory.newChat), the same ones its
 * own window uses, so the phone needs nothing new from it. [call] is the link's RPC (RemoteSession.call); tests pass
 * their own.
 */
class ProjectsApi(private val call: suspend (method: String, args: List<Any?>) -> Any?) {

    /** The projects to show: hidden ones left out, the built-in archive last. */
    suspend fun list(): List<ProjectRow> = listed(call("data.list", listOf("project")) as? JSONArray)

    /** Make a project; the computer answers with the row and its id. */
    suspend fun create(draft: ProjectDraft): ProjectRow {
        require(canCreate(draft)) { "A project needs a name." }
        val row = call("data.upsert", listOf("project", createPayload(draft))) as? JSONObject
        return row?.let(::rowOf) ?: throw IllegalStateException("The computer did not answer with the new project.")
    }

    /** The computer's profiles for the picker: General (no profile, id '') first. */
    suspend fun profiles(): List<Pair<String, String>> {
        val a = call("profiles.list", listOf()) as? JSONArray
        val own = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .filter { it.optString("id").isNotBlank() && !it.optBoolean("general") }.map { it.optString("id") to it.optString("name").ifBlank { "Profile" } }
        return listOf("" to "General") + own
    }

    /** Whether anything was said in a chat yet (a few rows by index, never the whole chat table). */
    private suspend fun said(chat: String): Boolean = runCatching {
        val where = JSONObject().put("chat", chat).put("exceptKinds", JSONArray(listOf("branch"))).put("limit", 20)
        val a = call("data.where", listOf("chat_item", where)) as? JSONArray ?: return@runCatching false
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.any { it.optString("kind") != "branch" && !RemoteRows.isDivider(it) && RemoteRows.chatOf(it) == chat }
    }.getOrDefault(false)

    /**
     * A new chat in a project, the way the computer's own window starts one: the first of its chats with nothing said
     * in it is reused; else a new chat id is added to the project. [from] is the chat of that project he came from, if
     * any: what was said there is filed into memory (best effort).
     */
    suspend fun startChat(project: ProjectRow, from: String = "", newId: () -> String = ::newChatId): ProjectChatRef {
        for (chat in project.chats) if (!said(chat)) return ProjectChatRef(chat, project, fresh = false)
        val next = newId()
        val chats = project.chats + next
        call("data.upsert", listOf("project", JSONObject().put("id", project.id).put("chats", JSONArray(chats))))
        if (from.isNotBlank() && from in project.chats) runCatching { call("memory.newChat", listOf(JSONObject().put("chat", next).put("from", from))) }
        return ProjectChatRef(next, project.copy(chats = chats), fresh = true)
    }

    companion object {
        const val ARCHIVE_ID = "earlier-chats"
        const val NAME_MAX = 60
        const val DESCRIPTION_MAX = 600
        /** The icons a project can take: the computer's glyph names (ui.ProjectIconTile draws each). */
        val GLYPHS = listOf("folder", "building", "code", "term", "globe", "doc", "mail", "cal", "chat", "tasks", "heart", "bolt", "shield", "key", "palette", "monitor", "phone", "person", "server", "shot")
        /** The colours a project can take: accent ids both the computer and the phone's theme know. */
        val COLORS = listOf("ocean", "ember", "violet", "rose", "teal", "forest", "magenta", "iris", "graphite")

        fun cleanName(name: String): String = name.trim().replace(Regex("\\s+"), " ").take(NAME_MAX).trim()
        fun cleanDescription(text: String): String = text.trim().take(DESCRIPTION_MAX).trim()
        fun canCreate(draft: ProjectDraft): Boolean = cleanName(draft.name).isNotEmpty()

        /** A glyph name from the list, or ONE emoji he typed (anything that is not letters or digits); else the folder. */
        fun cleanGlyph(glyph: String): String {
            val g = glyph.trim()
            if (g in GLYPHS) return g
            if (g.isEmpty() || g.any { it.isLetterOrDigit() || it.isWhitespace() || it == '-' }) return "folder"
            val it = java.text.BreakIterator.getCharacterInstance().apply { setText(g) }
            return g.substring(0, it.next().takeIf { e -> e > 0 } ?: g.length)
        }

        /** The row data.upsert('project', ...) takes for a new project: every field the computer's own form sends. */
        fun createPayload(draft: ProjectDraft): JSONObject = JSONObject()
            .put("chats", JSONArray())
            .put("name", cleanName(draft.name))
            .put("description", cleanDescription(draft.description))
            .put("aliases", JSONArray())
            .put("icon", JSONObject().put("glyph", cleanGlyph(draft.glyph)).put("color", draft.color.takeIf { it in COLORS } ?: "iris"))
            .put("path", draft.path.trim())
            .put("profile", draft.profile.trim())
            .put("hidden", false)
            .put("ai", JSONObject().put("main", JSONObject().put("account", "").put("model", "")).put("helpers", JSONObject().put("account", "").put("model", "")))

        /** 'pc' and 16 letters and digits from a UUID, the same form the computer gives a project chat. */
        fun newChatId(): String = "pc" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        fun isChatId(id: String): Boolean = Regex("pc[a-z0-9]{16}").matches(id)

        fun rowOf(o: JSONObject): ProjectRow? {
            val id = o.optString("id")
            if (id.isBlank()) return null
            val icon = o.optJSONObject("icon") ?: JSONObject()
            val chats = o.optJSONArray("chats")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
            return ProjectRow(id, o.optString("name").trim().ifEmpty { "Project" }, o.optString("description"), icon.optString("glyph"), icon.optString("color").ifBlank { "iris" },
                icon.optString("image"), chats, o.optBoolean("hidden"), o.optString("profile"),
                maxOf(o.optLong("railAt"), o.optLong("updatedAt"), o.optLong("createdAt")))
        }

        /** The rows a list shows: hidden ones left out, most recently active first, the built-in archive last. */
        fun listed(rows: JSONArray?): List<ProjectRow> {
            if (rows == null) return emptyList()
            return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.mapNotNull(::rowOf).filter { !it.hidden }
                .sortedWith(compareBy<ProjectRow> { it.isArchive }.thenByDescending { it.activeAt }.thenBy { it.name.lowercase() })
        }

        /** How long ago, in a few letters: "now", "5 min", "3 h", "2 d", "4 wk". */
        fun ago(at: Long, now: Long): String {
            if (at <= 0) return ""
            val min = ((now - at) / 60_000).coerceAtLeast(0)
            return when { min < 1 -> "Active now"; min < 60 -> "$min min ago"; min < 24 * 60 -> "${min / 60} h ago"; min < 14 * 24 * 60 -> "${min / (24 * 60)} d ago"; else -> "${min / (7 * 24 * 60)} wk ago" }
        }
    }
}
