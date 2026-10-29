package com.past9.phoneaos.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * @-mentions in the message box (29 Oct 2026), the phone half of desktop shared/mentions.ts: typing '@' offers the
 * computer's projects, and a message that names one goes to that project's chat (the computer routes it, so the phone
 * only helps type the name). The list is small, kept on the phone, and refreshed when the picker opens: the computer
 * answers "same" in a few bytes when nothing changed (RPC chat.mentions with the stamp the phone has).
 */
data class MentionProject(val id: String, val name: String, val aliases: List<String>, val glyph: String, val color: String, val image: String, val railAt: Long)

/** What the chat a mention was typed in shows: "Sent to X", which opens that project's chat. */
data class MentionNotice(val project: String, val name: String, val chat: String, val fresh: Boolean)

object Mentions {
    fun projectsOf(a: JSONArray?): List<MentionProject> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { o ->
            val name = o.optString("name").trim()
            if (o.optString("id").isBlank() || name.isEmpty()) null
            else MentionProject(o.optString("id"), name, o.optJSONArray("aliases")?.let { al -> (0 until al.length()).map { al.optString(it).trim() }.filter { it.isNotEmpty() } } ?: emptyList(),
                o.optString("glyph"), o.optString("color").ifBlank { "iris" }, o.optString("image"), o.optLong("railAt"))
        }
    }

    fun toJson(list: List<MentionProject>): JSONArray = JSONArray().apply {
        list.forEach { p -> put(JSONObject().put("id", p.id).put("name", p.name).put("aliases", JSONArray(p.aliases)).put("glyph", p.glyph).put("color", p.color).put("image", p.image).put("railAt", p.railAt)) }
    }

    fun noticeOf(meta: String): MentionNotice? {
        val m = runCatching { JSONObject(meta.ifBlank { "{}" }).optJSONObject("mention") }.getOrNull() ?: return null
        val chat = m.optString("chat"); val name = m.optString("name")
        if (chat.isBlank() || !m.has("name")) return null
        return MentionNotice(m.optString("project"), name, chat, m.optBoolean("fresh"))
    }

    private fun words(t: String) = t.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** How well what he typed fits a name; 0 for not at all. Same scale as the computer's. */
    fun fit(query: String, name: String): Double {
        val q = query.lowercase().replace(Regex("\\s+"), " ").trim()
        val n = name.lowercase()
        if (q.isEmpty()) return 1.0
        if (n == q) return 100.0
        if (n.startsWith(q)) return 90.0 - minOf(9, n.length - q.length) / 10.0
        val nw = words(n); val qw = words(q)
        if (qw.isNotEmpty() && qw.all { w -> nw.any { it.startsWith(w) } }) return 80.0
        if (n.contains(q)) return 70.0
        val bare = q.replace(Regex("[^\\p{L}\\p{N}]"), "")
        val initials = nw.joinToString("") { it.take(1) }
        if (bare.length >= 2 && initials.startsWith(bare)) return 65.0
        if (bare.length < 2) return 0.0
        var at = -1; var first = -1
        for (c in bare) { at = n.indexOf(c, at + 1); if (at < 0) return 0.0; if (first < 0) first = at }
        val spread = at - first + 1 - bare.length
        return maxOf(1.0, 40.0 - spread * 2)
    }

    /** The projects that fit, best first; an empty query lists them all in the computer's order. */
    fun rank(query: String, list: List<MentionProject>, limit: Int = 6): List<MentionProject> =
        list.mapIndexed { i, p -> Triple(p, i, maxOf(fit(query, p.name), p.aliases.maxOfOrNull { fit(query, it) * 0.95 } ?: 0.0)) }
            .filter { it.third > 0 }.sortedWith(compareByDescending<Triple<MentionProject, Int, Double>> { it.third }.thenBy { it.second })
            .take(limit).map { it.first }

    /** The '@...' word being typed at the caret: where it starts and what follows the '@'; null for none. */
    fun atCaret(text: String, caret: Int): Pair<Int, String>? {
        val before = text.substring(0, caret.coerceIn(0, text.length))
        val at = before.lastIndexOf('@')
        if (at < 0) return null
        if (at > 0 && !before[at - 1].isWhitespace()) return null
        val q = before.substring(at + 1)
        if (q.contains('\n') || q.contains('@') || q.length > 40) return null
        return at to q
    }

    /** The text with the picked name written in for the '@...' word at the caret, and where the caret goes. */
    fun insert(text: String, caret: Int, name: String): Pair<String, Int> {
        val found = atCaret(text, caret) ?: return text to caret
        val after = text.substring(caret.coerceIn(0, text.length)).replace(Regex("^\\S*"), "").trimStart(' ')
        val head = text.substring(0, found.first) + "@" + name + " "
        return (head + after) to head.length
    }
}

/**
 * The phone's copy of the list. `refresh` asks the computer with the stamp it has; an older computer without
 * chat.mentions leaves the list as it is (empty: no picker, nothing breaks).
 */
class MentionCache(private val read: (String) -> String?, private val write: (String, String?) -> Unit) {
    @Volatile var projects: List<MentionProject> = runCatching { Mentions.projectsOf(JSONArray(read(KEY_LIST) ?: "[]")) }.getOrDefault(emptyList())
        private set
    val stamp: String get() = read(KEY_STAMP).orEmpty()

    /** Takes the computer's answer to chat.mentions; true when the list changed. */
    fun take(answer: JSONObject?): Boolean {
        if (answer == null || answer.optBoolean("same")) return false
        val list = Mentions.projectsOf(answer.optJSONArray("projects") ?: return false)
        projects = list
        write(KEY_LIST, Mentions.toJson(list).toString())
        write(KEY_STAMP, answer.optString("stamp"))
        return true
    }

    companion object {
        const val KEY_LIST = "mentions_list"
        const val KEY_STAMP = "mentions_stamp"
    }
}
