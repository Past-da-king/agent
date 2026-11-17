package com.past9.phoneaos.assist

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The last live call's words, kept so the next call opens knowing what was said (bounded; see [LiveTruth.previousCall]). */
object LiveHistory {
    private const val PREFS = "live_history"
    private const val KEEP = 60

    fun save(context: Context, lines: List<LiveLine>) {
        if (lines.isEmpty()) return
        val a = JSONArray()
        lines.takeLast(KEEP).forEach { a.put(JSONObject().put("who", it.who).put("text", it.text.take(600)).put("at", it.at)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("lines", a.toString()).apply()
    }

    fun load(context: Context): List<LiveLine> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lines", null) ?: return emptyList()
        return runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> LiveLine(o.optString("who"), o.optString("text"), o.optLong("at")) } } } }.getOrDefault(emptyList())
    }
}
