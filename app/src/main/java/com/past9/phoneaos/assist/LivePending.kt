package com.past9.phoneaos.assist

import android.content.Context

/**
 * What a closed call was still waiting for: the chat row it followed from and the rows it had already read out. The agent's
 * later replies (a helper that finished minutes after the call ended) are notified, and read at the start of the next call.
 */
object LivePending {
    private const val PREFS = "live_pending"
    /** A reply older than this is stale news. */
    const val KEEP_MS = 12 * 3_600_000L

    data class State(val base: Long, val told: Set<Long>, val at: Long)

    fun save(context: Context, base: Long, told: Set<Long>, now: Long) {
        if (base == Long.MIN_VALUE) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("base", base).putString("told", told.joinToString(",")).putLong("at", now).apply()
    }

    /** Reads without clearing (the notifier reads it too). Null when nothing is pending or it has gone stale. */
    fun peek(context: Context, now: Long): State? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains("base")) return null
        val at = p.getLong("at", 0L)
        if (now - at > KEEP_MS) { clear(context); return null }
        return State(p.getLong("base", Long.MIN_VALUE), p.getString("told", "").orEmpty().split(",").mapNotNull { it.toLongOrNull() }.toSet(), at)
    }

    fun clear(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply() }
}
