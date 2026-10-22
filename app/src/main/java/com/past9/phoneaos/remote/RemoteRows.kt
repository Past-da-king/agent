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
