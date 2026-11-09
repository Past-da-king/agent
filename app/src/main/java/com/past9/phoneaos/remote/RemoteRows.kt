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

    /** A mapping read back from a stored row (its meta.rid) after a restart. */
    @Synchronized fun learn(remoteId: String, localId: Long) { toLocal.putIfAbsent(remoteId, localId); toRemote.putIfAbsent(localId, remoteId) }
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

    /** A "New chat" divider: a notice the computer writes instead of deleting anything (memory/service.ts compressChat). */
    fun isDivider(o: JSONObject): Boolean = runCatching { JSONObject(o.optString("meta").ifBlank { "{}" }).optBoolean("divider") }.getOrDefault(false)

    /**
     * When the newest "New chat" divider in [chat] was written, or 0 when that chat has none. Starting a new chat
     * does not delete a single row: everything at or before the divider is a conversation the person closed, and
     * the desktop draws only from the divider on (renderer/lib/chat-memory.ts). The phone does the same, or the
     * chat it just cleared comes back on the next read (issue #14).
     */
    fun dividerAt(rows: List<JSONObject>, chat: String = "main"): Long = rows.fold(0L) { best, o ->
        if (!isDivider(o)) best else {
            val own = runCatching { JSONObject(o.optString("meta").ifBlank { "{}" }).optString("chat").ifBlank { "main" } }.getOrDefault("main")
            if (own == chat) maxOf(best, o.optLong("createdAt")) else best
        }
    }

    /** Is this row closed by the chat's newest New chat? The divider itself stays: it is the line the chat opens at. */
    fun closed(o: JSONObject, divider: Long, chat: String = "main"): Boolean {
        if (divider <= 0L) return false
        val inChat = if (chat == "main") inMainChat(o) else runCatching { JSONObject(o.optString("meta").ifBlank { "{}" }).optString("chat") }.getOrDefault("") == chat
        return inChat && o.optLong("createdAt") < divider
    }

    /**
     * One of the computer's chats as the phone should show it: its newest New chat onward, plus any card still waiting
     * on the person, however old (a question must never be cleared out from under them). A chat on the computer is not
     * deleted by a clear, only closed with a divider, so a window that reads the rows themselves (a project chat) has
     * to hide the closed ones too, or the chat it just cleared comes back (issue #14).
     */
    fun openIn(rows: List<JSONObject>, chat: String = "main"): List<JSONObject> {
        val divider = dividerAt(rows, chat)
        return rows.filter { inChat(it, chat) && (!closed(it, divider, chat) || isOpenCard(it)) }
    }

    /** The main chat as the phone should show it. */
    fun openMain(rows: List<JSONObject>): List<JSONObject> = openIn(rows)

    /** Does this row belong to that chat? A row that names no chat is the main one (desktop MAIN_CHAT = "main"). */
    fun inChat(o: JSONObject, chat: String): Boolean = if (chat == "main") inMainChat(o) else chatOf(o) == chat

    /** The phone shows the person's general memory; a project's own pages stay on the computer. */
    fun isGeneralMemory(o: JSONObject): Boolean = o.optString("project").isBlank()

    /** The computer's id rides in the row, so a restart can map the phone's number back to it (answering a card). */
    fun withRid(meta: String, rid: String): String = runCatching { JSONObject(meta).put("rid", rid).toString() }.getOrDefault(meta)

    fun chat(o: JSONObject, ids: RemoteIds, notes: NoteAudio? = null): ChatItem {
        val created = o.optLong("createdAt", System.currentTimeMillis())
        val id = ids.local(o.getString("id"), created)
        val meta = o.optString("meta").ifBlank { "{}" }
        if (o.optString("kind") == "voice_note") return ChatItem(id, "voice", o.optString("text"), voiceMeta(meta, notes).put("rid", o.getString("id")).toString(), created)
        if (o.optString("kind") == "question" || o.optString("kind") == "approval") return card(o, id, meta, created)
        return ChatItem(id, o.optString("kind", "notice"), o.optString("text"), withRid(meta, o.getString("id")), created)
    }

    /**
     * A question or approval on the computer that nobody has answered (desktop runtime/runner.ts isOpenCardRow), from
     * any of its chats: the computer counts it as waiting for you, so the phone shows it too.
     */
    fun isOpenCard(o: JSONObject): Boolean {
        val kind = o.optString("kind")
        if (kind != "question" && kind != "approval") return false
        val m = runCatching { JSONObject(o.optString("meta").ifBlank { "{}" }) }.getOrDefault(JSONObject())
        return if (kind == "question") m.optString("answer").isEmpty() else m.optString("decision").let { it != "approve" && it != "decline" }
    }

    /**
     * The computer's card in the phone's own shape, so Home's Needs you, the chat and calls all see it: the phone's
     * rule is "no answer key = still waiting" (the computer writes answer "" instead), and an approval is a question
     * whose text is "APPROVAL|<what>|<details>" with Approve and Decline. remoteKind keeps what the computer calls it,
     * so the answer goes back as chat.decide or chat.answer. A helper's card says which helper asks (22 Oct 2026).
     */
    private fun card(o: JSONObject, id: Long, rawMeta: String, created: Long): ChatItem {
        val m = runCatching { JSONObject(rawMeta) }.getOrDefault(JSONObject())
        val kind = o.optString("kind")
        m.put("remoteKind", kind)
        m.put("rid", o.optString("id"))
        val by = m.optString("by")
        if (by.isNotBlank() && m.optString("why").isBlank()) m.put("whyBy", by).put("why", "a helper is waiting on this")
        if (kind == "question") {
            if (m.optString("answer").isEmpty()) m.remove("answer")
            return ChatItem(id, "question", o.optString("text"), m.toString(), created)
        }
        val summary = m.optString("summary").ifBlank { o.optString("text") }.replace('|', '/')
        when (m.optString("decision")) {
            "approve" -> m.put("answer", "Approve")
            "decline" -> m.put("answer", "Decline")
            else -> m.remove("answer")
        }
        m.put("options", org.json.JSONArray(listOf("Approve", "Decline")))
        return ChatItem(id, "question", "APPROVAL|$summary|${m.optString("details")}", m.toString(), created)
    }

    /** Where the phone keeps the computer's voice notes, and how fetching each one went. */
    interface NoteAudio {
        /** The phone's copy of a note (it may not exist yet). */
        fun local(name: String): java.io.File
        /** Why a note could not be fetched, or null when it has not failed. */
        fun failed(name: String): String?
        /** A fetch of this note is on its way now. */
        fun fetching(name: String): Boolean = false
        /** Notes come only when he taps them (mobile data): until then a note waits on the computer, ready to tap. */
        val onTap: Boolean get() = false
    }

    /**
     * What a voice note from the computer can do right now, read live (never from the row's stored "audio", which can
     * be stale: 29 Oct 2026, a note stored "loading" on mobile data spun forever and could not be tapped).
     */
    sealed class NoteState {
        data class Ready(val file: java.io.File) : NoteState()
        object Fetching : NoteState()
        /** On the computer: one tap gets it and plays it. */
        object OnComputer : NoteState()
        /** The last try failed: say why, with Try again (a tap tries again too). */
        data class Failed(val why: String) : NoteState()
        /** There is nothing to get. */
        data class NoAudio(val why: String) : NoteState()
    }

    fun noteState(meta: JSONObject, notes: NoteAudio?): NoteState {
        val name = meta.optString("remoteNote").ifBlank { noteName(meta) }
        if (name.isEmpty()) return NoteState.NoAudio(meta.optString("audioError").ifBlank { "This note has no audio file on the computer." })
        val local = notes?.local(name)
        if (local != null && local.isFile && local.length() > 44) return NoteState.Ready(local)
        val stored = meta.optString("path")
        if (stored.isNotBlank() && java.io.File(stored).let { it.isFile && it.length() > 44 }) return NoteState.Ready(java.io.File(stored))
        if (notes == null) return NoteState.NoAudio("Link this phone to your computer to hear this note.")
        if (notes.fetching(name)) return NoteState.Fetching
        notes.failed(name)?.let { return NoteState.Failed(it) }
        return NoteState.OnComputer
    }

    /** About how big a computer note is to fetch (16-bit mono 24 kHz WAV, base64 on the link), for the tap label. */
    fun noteSizeLabel(meta: JSONObject): String {
        val ms = meta.optLong("durationMs", 0L)
        if (ms <= 0L) return ""
        val mb = ms / 1000.0 * 48_000 * 4 / 3 / 1_000_000
        return if (mb < 0.1) "under 0.1 MB" else String.format(java.util.Locale.US, "%.1f MB", mb)
    }

    /** A note name on the computer (desktop voice service: only plain names in its voice-notes folder), or "". */
    fun noteName(meta: JSONObject): String = meta.optString("file").takeIf { NOTE_NAME.matches(it) }.orEmpty()
    private val NOTE_NAME = Regex("^[\\w-]{1,100}\\.wav$")

    /**
     * The computer's voice note (kind voice_note, meta.file = a note name on that computer) as the phone's voice row:
     * "path" is the phone's copy once it is here, and "audio" says ready, loading or unavailable (with "audioError").
     * The script stays in the row's text, shown only when he asks to read it.
     */
    fun voiceMeta(rawMeta: String, notes: NoteAudio?): JSONObject {
        val m = runCatching { JSONObject(rawMeta) }.getOrDefault(JSONObject())
        val name = noteName(m)
        m.put("remoteNote", name)
        val local = if (name.isEmpty() || notes == null) null else notes.local(name)
        val failed = if (name.isEmpty() || notes == null) null else notes.failed(name)
        when {
            local != null && local.isFile && local.length() > 44 -> m.put("path", local.absolutePath).put("audio", "ready")
            name.isEmpty() -> m.put("path", "").put("audio", "unavailable").put("audioError", "This note has no audio file on the computer.")
            failed != null -> m.put("path", "").put("audio", "unavailable").put("audioError", failed)
            notes != null && notes.onTap && !notes.fetching(name) -> m.put("path", "").put("audio", "remote")
            else -> m.put("path", "").put("audio", "loading")
        }
        return m
    }

    /** The bytes of a voice.noteAudio answer ({"$b64": ...} on the wire), or null when the computer has no such note. */
    fun noteBytes(value: Any?): ByteArray? {
        val b64 = (value as? JSONObject)?.optString("\$b64").orEmpty()
        if (b64.isEmpty()) return null
        return java.util.Base64.getDecoder().decode(b64)
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
