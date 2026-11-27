package com.past9.phoneaos.tools

import com.past9.phoneaos.BuildConfig
import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.int
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * phone_logs: this app's own log lines, for the linked computer to diagnose a bad call or a failed feature without a
 * cable (27 Nov 2026: he could not plug the phone in, and the call that "acted stupid" left no trace anyone could read).
 *
 * Lent only in the "logs" group, which is OFF until he switches it on (Settings, Linked computer, What your computer may
 * use). Only this app's own process is read (Android lets an app read its own lines, never another app's), and lines
 * that look like keys or tokens are blanked before they leave the phone.
 */
class PhoneLogsTool(private val read: (Int) -> List<String> = ::ownLines) : Tool {
    override val spec = ToolSpec("phone_logs",
        "Read the Agent phone app's own recent log lines (its calls, Live voice, audio and music hold, voice notes, link and crashes) to diagnose a problem. " +
            "Only the app's own lines, never other apps'. Filter by tags (comma separated, e.g. CallAgent,AosLive,AosMediaHold,AosAudioRoute,LiveAssist,AndroidRuntime) or a word.",
        schema(emptyList(),
            "tags" to str("Only lines from these log tags, comma separated"),
            "query" to str("Only lines containing this text"),
            "lines" to int("How many of the newest lines to return (default 400, at most 3000)")))

    override suspend fun run(input: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val max = input.optInt("lines", 400).coerceIn(1, 3000)
        val tags = input.optString("tags").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val query = input.optString("query").trim()
        val raw = runCatching { read(20_000) }.getOrElse { return@withContext "failed: the log could not be read (${it.message})." }
        val picked = filter(raw, tags, query).takeLast(max).map(::redact)
        "Agent phone ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), ${picked.size} line(s)" +
            (if (tags.isNotEmpty()) " from ${tags.joinToString(", ")}" else "") + (if (query.isNotEmpty()) " containing \"$query\"" else "") + ":\n" +
            picked.joinToString("\n").takeLast(60_000)
    }

    companion object {
        /** Lines of `logcat -v time`: "11-27 13:22:01.123 I/CallAgent( 1234): text". */
        fun filter(lines: List<String>, tags: List<String>, query: String): List<String> = lines.filter { l ->
            (tags.isEmpty() || tags.any { t -> Regex("[VDIWEF]/" + Regex.escape(t) + "\\s*\\(").containsMatchIn(l) }) &&
                (query.isEmpty() || l.contains(query, ignoreCase = true))
        }

        private val SECRET = Regex("(?i)(key=|token=|bearer\\s+|authorization:\\s*(?:bearer\\s+)?|access_token=|\"(?:key|token|apiKey|api_key)\"\\s*:\\s*\")[^\\s&\"]+")
        private val LONG_TOKEN = Regex("\\b(?:AIza[0-9A-Za-z_\\-]{20,}|sk-[0-9A-Za-z_\\-]{16,}|ghp_[0-9A-Za-z]{20,}|[0-9A-Za-z_\\-]{40,})\\b")

        /** Keys and tokens never leave the phone, even inside a log line. */
        fun redact(line: String): String = LONG_TOKEN.replace(SECRET.replace(line) { it.groupValues[1] + "[hidden]" }, "[hidden]")

        /** The newest [n] lines of this app's own process. */
        fun ownLines(n: Int): List<String> {
            val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", n.toString(), "--pid", android.os.Process.myPid().toString())
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readLines()
            p.waitFor()
            return out
        }
    }
}
