package com.past9.phoneaos.remote

import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.data.GoalRow
import com.past9.phoneaos.data.MemoryRow
import com.past9.phoneaos.data.TaskRow
import com.past9.phoneaos.data.TriggerRow
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Row ids. The computer names rows by string ids; the phone's screens use number ids, and the chat is shown in id
 * order. So a row's number is its creation time in ms times 1000 plus a small tiebreak from its string id: the chat
 * keeps its order, the same row always gets the same number, and the map back to the string id is kept here.
 */
class RemoteIds {
    private val toLocal = ConcurrentHashMap<String, Long>()
    private val toRemote = ConcurrentHashMap<Long, String>()

    @Synchronized
    fun local(remoteId: String, createdAt: Long): Long {
        toLocal[remoteId]?.let { return it }
        var id = createdAt.coerceIn(1L, 9_000_000_000_000L) * 1000L + Math.floorMod(remoteId.hashCode(), 1000)
        while (toRemote.containsKey(id)) id++
        toLocal[remoteId] = id; toRemote[id] = remoteId
        return id
    }

    fun remote(localId: Long): String? = toRemote[localId]
    fun localOrNull(remoteId: String): Long? = toLocal[remoteId]
    fun forget(remoteId: String) { toLocal.remove(remoteId)?.let { toRemote.remove(it) } }
}

/** The computer's rows (packages/core/src/ipc.ts, field names are the phone's own column names) as the phone's rows, and back. */
object RemoteRows {
    /** A chat row belongs to the main chat when it names no other chat (desktop MAIN_CHAT = "main"). */
    fun inMainChat(o: JSONObject): Boolean {
        val chat = runCatching { JSONObject(o.optString("meta").ifBlank { "{}" }).optString("chat") }.getOrDefault("")
        return chat.isEmpty() || chat == "main"
    }

    /** The phone shows the person's general memory; a project's own pages stay on the computer. */
    fun isGeneralMemory(o: JSONObject): Boolean = o.optString("project").isBlank()

    fun chat(o: JSONObject, ids: RemoteIds): ChatItem {
        val created = o.optLong("createdAt", System.currentTimeMillis())
        return ChatItem(ids.local(o.getString("id"), created), o.optString("kind", "notice"), o.optString("text"), o.optString("meta").ifBlank { "{}" }, created)
    }

    fun memory(o: JSONObject, ids: RemoteIds): MemoryRow {
        val created = o.optLong("createdAt", System.currentTimeMillis())
        return MemoryRow(ids.local(o.getString("id"), created), o.optString("title"), o.optString("body"), o.optString("kind", "fact"),
            o.optString("topics"), o.optBoolean("pinned"), o.optString("source", "agent"), created, o.optLong("updatedAt", created))
    }

    fun goal(o: JSONObject, ids: RemoteIds): GoalRow {
        val created = o.optLong("createdAt", System.currentTimeMillis())
        return GoalRow(ids.local(o.getString("id"), created), o.optString("title"), o.optString("why"), o.optString("status", "open"), created)
    }

    /** [goalIds] maps the computer's goal ids to the phone's (the goals are mirrored first). */
    fun task(o: JSONObject, ids: RemoteIds): TaskRow {
        val created = o.optLong("createdAt", System.currentTimeMillis())
        val goal = o.optString("goalId").takeIf { it.isNotBlank() && it != "null" }?.let { ids.localOrNull(it) }
        val due = if (o.isNull("dueAt")) null else o.optLong("dueAt").takeIf { it > 0 }
        return TaskRow(ids.local(o.getString("id"), created), goal, o.optString("title"), o.optString("notes"), o.optString("status", "todo"),
            due, o.optString("owner", "user"), o.optString("blocker"), created, o.optLong("updatedAt", created))
    }

    fun trigger(o: JSONObject, ids: RemoteIds): TriggerRow {
        val created = o.optLong("createdAt", System.currentTimeMillis())
        val last = if (o.isNull("lastRunAt")) null else o.optLong("lastRunAt").takeIf { it > 0 }
        return TriggerRow(ids.local(o.getString("id"), created), o.optString("name"), o.optString("kind", "daily"), o.optString("spec"),
            o.optString("prompt"), o.optBoolean("enabled", true), last, o.optString("lastResult"), "", created)
    }

    /** The chat a row belongs to ("main" when it names none). */
    fun chatOf(o: JSONObject): String =
        runCatching { JSONObject(o.optString("meta").ifBlank { "{}" }).optString("chat") }.getOrDefault("").ifEmpty { "main" }

    /** How long a finished project chat keeps its row in the phone's chat. */
    const val PROJECT_ROW_DAYS = 3L

    /**
     * Work the agent handed to a project runs in that project's own chat on the computer. The phone shows it the way
     * it shows a helper: one row in the chat, with the project's icon instead of the helper orb, its live step as the
     * status line and its last answer as the result. Null when the chat has nothing to show or is long finished.
     */
    fun projectHelper(project: JSONObject, chatId: String, rows: List<JSONObject>, running: Boolean, ids: RemoteIds, now: Long = System.currentTimeMillis()): ChatItem? {
        val sorted = rows.sortedBy { it.optLong("createdAt") }
        if (sorted.isEmpty()) return null
        val first = sorted.first().optLong("createdAt", now)
        val last = sorted.last().optLong("createdAt", first)
        if (!running && now - last > PROJECT_ROW_DAYS * 86_400_000L) return null
        fun text(o: JSONObject) = o.optString("text").trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val brief = sorted.firstOrNull { it.optString("kind") == "user" }?.optString("text")?.trim().orEmpty()
        val step = sorted.lastOrNull { it.optString("kind") in setOf("activity", "agent", "helper") && text(it).isNotBlank() }?.let(::text).orEmpty()
        val answer = sorted.lastOrNull { it.optString("kind") == "agent" }?.optString("text")?.trim().orEmpty()
        val icon = project.optJSONObject("icon") ?: JSONObject()
        val id = ids.local("project-chat:$chatId", first)
        val meta = JSONObject()
            .put("id", id).put("label", project.optString("name").ifBlank { "Project" }).put("state", if (running) "working" else "done")
            .put("now", if (running) step else "").put("result", if (running) "" else answer.ifBlank { step })
            .put("startedAt", first).put("endedAt", if (running) 0 else last)
            .put("projectChat", chatId).put("projectName", project.optString("name"))
            .put("projectGlyph", icon.optString("glyph")).put("projectColor", icon.optString("color").ifBlank { "iris" })
            .put("projectImage", icon.optString("image"))
        return ChatItem(id, "helper", brief.ifBlank { "Work for ${project.optString("name").ifBlank { "a project" }}" }, meta.toString(), first)
    }

    /**
     * Pictures a chat row points at on the computer: "image" (a step's screenshot), "images" (a message's photos) and
     * image attachments of a message sent there. They are paths on that computer, fetched through /v1/file.
     */
    fun mediaPaths(meta: JSONObject): List<String> {
        val out = ArrayList<String>()
        meta.optString("image").takeIf { it.isNotBlank() }?.let { out += it }
        meta.optJSONArray("images")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { out += it } }
        meta.optJSONArray("attachments")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optString("mime").startsWith("image/") }?.optString("path")?.takeIf { it.isNotBlank() }?.let { out += it }
        }
        return out.distinct()
    }

    /** The same meta with each fetched picture pointing at its copy on the phone; image attachments become "images", which the phone draws. */
    fun withLocalMedia(meta: JSONObject, local: Map<String, String>): JSONObject {
        val m = JSONObject(meta.toString())
        m.optString("image").takeIf { it in local }?.let { m.put("image", local.getValue(it)) }
        val images = ArrayList<String>()
        m.optJSONArray("images")?.let { a -> for (i in 0 until a.length()) a.optString(i).let { p -> images += local[p] ?: p } }
        m.optJSONArray("attachments")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optString("mime").startsWith("image/") }?.optString("path")?.let { p -> local[p]?.let { images += it } }
        }
        if (images.isNotEmpty()) m.put("images", org.json.JSONArray(images.distinct()))
        return m
    }

    // ---- back to the computer: data.upsert rows. An id means update; none means create. ----

    fun taskInput(t: TaskRow, ids: RemoteIds): JSONObject = JSONObject().apply {
        ids.remote(t.id)?.let { put("id", it) }
        put("title", t.title); put("notes", t.notes); put("status", t.status); put("owner", t.owner); put("blocker", t.blocker)
        put("dueAt", t.dueAt ?: JSONObject.NULL)
        put("goalId", t.goalId?.let { ids.remote(it) } ?: JSONObject.NULL)
        put("updatedAt", System.currentTimeMillis())
    }

    fun memoryInput(m: MemoryRow, ids: RemoteIds): JSONObject = JSONObject().apply {
        ids.remote(m.id)?.let { put("id", it) }
        put("title", m.title); put("body", m.body); put("kind", m.kind); put("topics", m.topics); put("pinned", m.pinned)
        put("source", "user"); put("updatedAt", System.currentTimeMillis())
    }

    fun triggerInput(r: TriggerRow, ids: RemoteIds): JSONObject = JSONObject().apply {
        ids.remote(r.id)?.let { put("id", it) }
        put("name", r.name); put("kind", r.kind); put("spec", r.spec); put("prompt", r.prompt); put("enabled", r.enabled)
    }
}
