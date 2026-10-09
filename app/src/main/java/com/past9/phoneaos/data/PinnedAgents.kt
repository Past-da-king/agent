package com.past9.phoneaos.data

import com.past9.phoneaos.agent.Block
import com.past9.phoneaos.agent.Msg
import com.past9.phoneaos.agent.Role
import com.past9.phoneaos.agent.Trim
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A helper the main agent kept because it earned it (it cracked something non-obvious, or owns an
 * ongoing area). It keeps its identity, model, profile and whole conversation, so the main agent can
 * hand it a new job instead of briefing a fresh helper from zero. The user never manages these.
 */
data class PinnedAgent(
    val id: String,
    val name: String,
    /** What it knows and what it's good at, in the main agent's words: how it decides to reuse it. */
    val summary: String,
    val goodFor: List<String> = emptyList(),
    /** Why it was kept. Pins are for good reason only. */
    val why: String = "",
    val model: String = "",
    val profileId: String = "",
    /** The helper (chat item) it was first pinned from, and the one that last worked as it. */
    val fromHelper: Long = 0,
    val lastHelper: Long = 0,
    /** Its harness session (subscription helpers carry their context there instead of in our history). */
    val session: String? = null,
    val pinnedAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = pinnedAt,
    /** The jobs it has done, newest last ("8 Oct: X signup"). */
    val jobs: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("summary", summary).put("goodFor", JSONArray(goodFor)).put("why", why)
        .put("model", model).put("profileId", profileId).put("fromHelper", fromHelper).put("lastHelper", lastHelper).put("session", session ?: JSONObject.NULL)
        .put("pinnedAt", pinnedAt).put("lastUsedAt", lastUsedAt).put("jobs", JSONArray(jobs))

    companion object {
        fun from(o: JSONObject) = PinnedAgent(o.optString("id"), o.optString("name"), o.optString("summary"),
            o.optJSONArray("goodFor")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(), o.optString("why"),
            o.optString("model"), o.optString("profileId"), o.optLong("fromHelper"), o.optLong("lastHelper"),
            o.optString("session").takeIf { !o.isNull("session") && it.isNotBlank() }, o.optLong("pinnedAt"), o.optLong("lastUsedAt"),
            o.optJSONArray("jobs")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty())
    }
}

/**
 * The pinned agents and their conversations, in the app's own files (a list plus one history file each).
 * Plain files rather than the database so a pin survives the overnight compaction of the main chat.
 */
class PinnedAgentStore(private val dir: File) {
    private val index = File(dir, "agents.json")
    private val _agents = MutableStateFlow(read())
    val agents: StateFlow<List<PinnedAgent>> = _agents

    private fun read(): List<PinnedAgent> = runCatching {
        val a = JSONArray(index.readText()); (0 until a.length()).map { PinnedAgent.from(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    @Synchronized private fun save(list: List<PinnedAgent>) {
        dir.mkdirs(); index.writeText(JSONArray(list.map { it.toJson() }).toString()); _agents.value = list
    }

    fun find(nameOrId: String?): PinnedAgent? = nameOrId?.trim()?.removePrefix("#")?.takeIf { it.isNotEmpty() }?.let { w ->
        _agents.value.firstOrNull { it.id == w } ?: _agents.value.firstOrNull { it.name.equals(w, true) }
    }
    fun forHelper(helperId: Long): PinnedAgent? = _agents.value.firstOrNull { it.lastHelper == helperId || it.fromHelper == helperId }

    @Synchronized fun put(a: PinnedAgent) = save(_agents.value.filter { it.id != a.id } + a)

    @Synchronized fun remove(id: String): PinnedAgent? {
        val a = _agents.value.firstOrNull { it.id == id } ?: return null
        save(_agents.value.filter { it.id != id }); historyFile(id).delete(); return a
    }

    /** Pins nobody has used for [EXPIRE_DAYS] go: their know-how was either reused or is stale. */
    @Synchronized fun prune(now: Long = System.currentTimeMillis()): List<PinnedAgent> {
        val stale = _agents.value.filter { now - it.lastUsedAt > EXPIRE_DAYS * 86_400_000L }
        stale.forEach { remove(it.id) }; return stale
    }

    private fun historyFile(id: String) = File(dir, "$id.history.json")

    fun loadHistory(id: String): MutableList<Msg> = runCatching {
        val a = JSONArray(historyFile(id).readText()); (0 until a.length()).map { Msg.fromJson(a.getJSONObject(it)) }.toMutableList()
    }.getOrDefault(mutableListOf())

    fun saveHistory(id: String, history: List<Msg>) {
        dir.mkdirs(); historyFile(id).writeText(JSONArray(compact(history).map { it.toJson() }).toString())
    }

    companion object {
        /** At most this many: pinning is selective. */
        const val MAX = 12
        const val EXPIRE_DAYS = 45
        /** Characters of history kept whole; older work is folded into one note. */
        const val BUDGET = 120_000
        private const val KEEP_RECENT = 16

        fun newId() = "a" + java.util.UUID.randomUUID().toString().take(8)

        /**
         * Keep a long-lived agent's conversation small enough to carry: the recent work stays whole, the
         * older work (its own words and the briefs it got; tool output and pictures dropped) becomes one
         * note at the top. The result always starts with a user message and keeps tool calls paired.
         */
        fun compact(history: List<Msg>): List<Msg> {
            // Pictures point at files that may be gone by the next job.
            val msgs = com.past9.phoneaos.agent.AgentRuntime.Repair.repair(history.map { m -> Msg(m.role, m.blocks.filter { it !is Block.Image }) }.filter { it.blocks.isNotEmpty() })
            if (msgs.sumOf { Trim.size(it) } <= BUDGET) return msgs
            // Never start the recent part on tool results: their calls would be left behind.
            var cut = (msgs.size - KEEP_RECENT).coerceAtLeast(1)
            while (cut < msgs.size - 1 && msgs[cut].role == Role.USER && msgs[cut].blocks.any { it is Block.ToolResult }) cut++
            val older = msgs.take(cut).mapNotNull { m ->
                m.blocks.filterIsInstance<Block.Text>().joinToString("\n") { it.text }.trim().takeIf { it.isNotEmpty() }
                    ?.let { (if (m.role == Role.USER) "Brief/note: " else "You: ") + it.take(1_500) }
            }
            var notes = older.joinToString("\n\n")
            if (notes.length > BUDGET / 3) notes = "…\n" + notes.takeLast(BUDGET / 3)
            // The recent part stays whole except bulky tool output, which a new job rarely needs verbatim.
            val recent = msgs.drop(cut).map { m -> Msg(m.role, m.blocks.map { b -> if (b is Block.ToolResult && b.content.length > 4_000) b.copy(content = b.content.take(3_500) + "\n[...trimmed when this agent was kept]") else b }) }
            val head = Msg.user("[Your earlier work, condensed to save room: tool output dropped, your own words kept]\n\n$notes")
            // Two user messages in a row merge into one, so the turn order stays valid for every provider.
            return if (recent.firstOrNull()?.role == Role.USER) listOf(Msg(Role.USER, head.blocks + recent.first().blocks)) + recent.drop(1) else listOf(head) + recent
        }

        /** Add a message from the main agent at the end, merging with a trailing user message. */
        fun appendUser(history: MutableList<Msg>, text: String) {
            val last = history.lastOrNull()
            if (last != null && last.role == Role.USER) history[history.lastIndex] = Msg(Role.USER, last.blocks + Block.Text(text)) else history += Msg.user(text)
        }
    }
}
