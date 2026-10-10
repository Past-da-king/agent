package com.past9.phoneaos.triggers

import android.content.Context

/** One routine's record for one scheduled slot (the moment it was due). */
data class RunEntry(val slot: Long, val status: String, val claimedAt: Long, val attempts: Int)

interface RunStore {
    fun get(id: Long): RunEntry?
    fun put(id: Long, e: RunEntry)
}

/** Kept on disk so it outlives the process; written with commit() so a kill right after can't lose it. */
class PrefsRunStore(context: Context) : RunStore {
    private val prefs = context.applicationContext.getSharedPreferences("routine_ledger", Context.MODE_PRIVATE)
    override fun get(id: Long): RunEntry? = prefs.getString("r$id", null)?.split("|")?.takeIf { it.size == 4 }
        ?.let { p -> runCatching { RunEntry(p[0].toLong(), p[1], p[2].toLong(), p[3].toInt()) }.getOrNull() }
    override fun put(id: Long, e: RunEntry) { prefs.edit().putString("r$id", "${e.slot}|${e.status}|${e.claimedAt}|${e.attempts}").commit() }
}

/**
 * Makes a scheduled run happen exactly once per slot, however many things try to start it: the WorkManager job,
 * the catch-up after the app was dead, a WorkManager retry. A run the app died in the middle of may be started
 * once more (nothing else would ever finish it); a finished slot never runs again.
 */
object RunLedger {
    enum class Verdict { RUN, RERUN, SKIP }
    const val STALE_MS = 10 * 60_000L
    const val MAX_ATTEMPTS = 2

    /** @param retried WorkManager is re-running this job because its process died: the earlier claim is dead. */
    @Synchronized
    fun claim(store: RunStore, id: Long, slot: Long, now: Long = System.currentTimeMillis(), retried: Boolean = false): Verdict {
        val e = store.get(id)
        if (e == null || slot > e.slot) { store.put(id, RunEntry(slot, "running", now, 1)); return Verdict.RUN }
        if (slot == e.slot && e.status == "running" && e.attempts < MAX_ATTEMPTS && (retried || now - e.claimedAt > STALE_MS)) {
            store.put(id, RunEntry(slot, "running", now, e.attempts + 1)); return Verdict.RERUN
        }
        return Verdict.SKIP
    }

    @Synchronized
    fun done(store: RunStore, id: Long, slot: Long) {
        val e = store.get(id) ?: return
        if (e.slot == slot) store.put(id, e.copy(status = "done"))
    }

    /** Is there still something to do for a slot that was due? (Nothing recorded, or an old run that never finished.) */
    fun owed(store: RunStore, id: Long, due: Long, now: Long = System.currentTimeMillis()): Boolean {
        val e = store.get(id) ?: return true
        return due > e.slot || (due == e.slot && e.status == "running" && e.attempts < MAX_ATTEMPTS && now - e.claimedAt > STALE_MS)
    }
}
