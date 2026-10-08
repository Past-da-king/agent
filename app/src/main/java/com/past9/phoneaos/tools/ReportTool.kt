package com.past9.phoneaos.tools

import android.content.Context
import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.data.ChatItem
import org.json.JSONObject
import java.io.File

/**
 * The chat stays TLDR. Anything long (research, a comparison, a plan) becomes a report: a card in the chat
 * that opens a full-screen reader in the app, where the user can scroll, share or save it.
 */
class ReportTool(private val context: Context, private val post: suspend (ChatItem) -> Long) : Tool {
    override val spec = ToolSpec("report", "Put a long answer in a REPORT instead of the chat: research, comparisons, plans, write-ups. It shows as a card the user taps to read full screen, share or save. " +
        "In the chat you then say only the one-line takeaway.",
        schema(listOf("title", "summary", "markdown"), "title" to str("Short title, e.g. 'Cyber tender readiness'"),
            "summary" to str("The takeaway in one or two short sentences: shown on the card"),
            "markdown" to str("The full report in markdown: headings, bullets, tables, links to sources")))

    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val title = input.optString("title").trim().ifBlank { "Report" }.take(80)
        val body = input.optString("markdown").trim()
        if (body.isBlank()) return "The report is empty. Pass the full text in markdown."
        val dir = File(context.filesDir, "reports").apply { mkdirs() }
        val slug = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "report" }
        val file = File(dir, "${System.currentTimeMillis()}-$slug.md")
        file.writeText("# $title\n\n$body\n")
        val words = body.split(Regex("\\s+")).count { it.isNotBlank() }
        post(ChatItem(kind = "report", text = input.optString("summary").trim().take(400),
            meta = JSONObject().put("title", title).put("path", file.path).put("words", words).toString()))
        return "The report card is in the chat. Now say only the one-line takeaway; don't repeat the report."
    }
}
