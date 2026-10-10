package com.past9.phoneaos.triggers

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.past9.phoneaos.App
import com.past9.phoneaos.data.TriggerRow
import com.past9.phoneaos.tools.parseWhen
import org.json.JSONObject
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * One workflow system: WHEN (a time, a daily slot, an interval, or an event such as a new email
 * matching a search) + WHAT (a prompt the agent runs on its own). Backed by WorkManager, so
 * routines survive the app being closed and the phone restarting.
 */
object Routines {
    private val dayNames = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

    /** Weekly spec: "SUN 18:00" or "MON,WED,FRI 07:30" -> (days 1..7, hour, minute), or null if it doesn't parse. */
    fun parseWeekly(spec: String): Triple<Set<Int>, Int, Int>? = runCatching {
        val (d, t) = spec.trim().split(Regex("\\s+"), limit = 2)
        val days = d.split(",").map { x -> dayNames.indexOfFirst { x.trim().uppercase().startsWith(it) } + 1 }.toSet()
        val (h, m) = t.split(":").map { it.toInt() }
        if (days.any { it < 1 } || days.isEmpty() || h !in 0..23 || m !in 0..59) null else Triple(days, h, m)
    }.getOrNull()

    fun weeklyLabel(spec: String): String = parseWeekly(spec)?.let { (days, h, m) ->
        val long = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        val d = when {
            days == setOf(1, 2, 3, 4, 5) -> "weekday"; days == setOf(6, 7) -> "weekend day"; days.size == 1 -> long[days.first() - 1]
            else -> days.sorted().joinToString(", ") { long[it - 1].take(3) }
        }
        "Every $d at %02d:%02d".format(h, m)
    } ?: "Weekly"

    private fun name(id: Long) = "routine-$id"
    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Milliseconds until the next run, or null if it never runs again. */
    fun nextRunIn(t: TriggerRow, now: ZonedDateTime = ZonedDateTime.now()): Long? = when (t.kind) {
        "at" -> parseWhen(t.spec)?.let { it - now.toInstant().toEpochMilli() }?.takeIf { it > 0 }
        "daily", Overnight.KIND -> runCatching {
            val (h, m) = t.spec.split(":").map { it.toInt() }
            var next = now.with(LocalTime.of(h, m)).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            Duration.between(now, next).toMillis()
        }.getOrNull()
        "weekly" -> parseWeekly(t.spec)?.let { (days, h, m) ->
            (0..7).asSequence().map { now.toLocalDate().plusDays(it.toLong()).atTime(h, m).atZone(now.zone) }
                .firstOrNull { it.isAfter(now) && it.dayOfWeek.value in days }?.let { Duration.between(now, it).toMillis() }
        }
        "interval", Watchers.KIND, com.past9.phoneaos.cards.CardScripts.KIND -> t.lastRunAt?.let { (it + (t.spec.toLongOrNull() ?: 60) * 60_000 - now.toInstant().toEpochMilli()).coerceAtLeast(0) } ?: ((t.spec.toLongOrNull() ?: 60) * 60_000)
        "email" -> 15 * 60_000L
        else -> null // "notification" fires on arrival, not on a clock
    }

    /** The scheduled moment (epoch ms) of the next run of a clock-based routine, or null. */
    fun nextSlot(t: TriggerRow, now: ZonedDateTime = ZonedDateTime.now()): Long? = when (t.kind) {
        "at" -> parseWhen(t.spec)?.takeIf { it > now.toInstant().toEpochMilli() }
        else -> nextRunIn(t, now)?.let { now.toInstant().toEpochMilli() + it }
    }

    /** Kinds that fire at a set clock time: the ones that can be missed while the app is dead. */
    private val timed = setOf("at", "daily", "weekly")

    /** The latest moment this routine was due at or before [now] (epoch ms), or null if it has none. */
    fun lastDue(t: TriggerRow, now: ZonedDateTime = ZonedDateTime.now()): Long? = when (t.kind) {
        "at" -> parseWhen(t.spec)?.takeIf { it <= now.toInstant().toEpochMilli() }
        "daily" -> runCatching {
            val (h, m) = t.spec.split(":").map { it.toInt() }
            var d = now.with(LocalTime.of(h, m)).withSecond(0).withNano(0)
            if (d.isAfter(now)) d = d.minusDays(1)
            d.toInstant().toEpochMilli()
        }.getOrNull()
        "weekly" -> parseWeekly(t.spec)?.let { (days, h, m) ->
            (0..7).asSequence().map { now.toLocalDate().minusDays(it.toLong()).atTime(h, m).atZone(now.zone) }
                .firstOrNull { !it.isAfter(now) && it.dayOfWeek.value in days }?.toInstant()?.toEpochMilli()
        }
        else -> null
    }

    /** Should a run that was due at [due] still be made up? Not if it ran since, or the routine did not exist yet. */
    fun missed(t: TriggerRow, due: Long, store: RunStore, now: Long = System.currentTimeMillis()): Boolean =
        t.enabled && t.kind in timed && due > t.createdAt && (t.lastRunAt ?: 0L) < due && RunLedger.owed(store, t.id, due, now)

    /**
     * After the app (or the phone) was off: every clock routine whose time passed without a run gets ONE run now, however
     * many slots it missed. Also makes sure each routine has its next run queued. The ledger stops any double run.
     */
    suspend fun catchUp(context: Context, now: ZonedDateTime = ZonedDateTime.now()) {
        val g = App.graph(context)
        val store = PrefsRunStore(context)
        val wm = WorkManager.getInstance(context)
        for (t in g.db.triggers().list().filter { it.enabled }) {
            ensureScheduled(context, t)
            val due = lastDue(t, now) ?: continue
            if (!missed(t, due, store, now.toInstant().toEpochMilli())) continue
            wm.enqueueUniqueWork("routine-catchup-${t.id}-$due", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RoutineWorker>().setInputData(workDataOf("id" to t.id, "slot" to due, "late" to true)).setConstraints(net).build())
        }
    }

    /** Queue the next run if none is waiting. Leaves a pending or running job alone (unlike [schedule], which replaces it). */
    fun ensureScheduled(context: Context, t: TriggerRow) {
        if (!t.enabled || t.kind !in timed) return
        val wm = WorkManager.getInstance(context)
        val busy = runCatching { wm.getWorkInfosForUniqueWork(name(t.id)).get().any { !it.state.isFinished } }.getOrDefault(false)
        if (!busy) schedule(context, t)
    }

    fun schedule(context: Context, t: TriggerRow) {
        val wm = WorkManager.getInstance(context)
        if (!t.enabled) { wm.cancelUniqueWork(name(t.id)); return }
        val data = workDataOf("id" to t.id)
        when (t.kind) {
            "interval", "email", Watchers.KIND, com.past9.phoneaos.cards.CardScripts.KIND -> {
                val minutes = if (t.kind == "email") 15L else (t.spec.toLongOrNull() ?: 60L).coerceAtLeast(15)
                wm.enqueueUniquePeriodicWork(name(t.id), ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<RoutineWorker>(minutes, TimeUnit.MINUTES).setInputData(data).setConstraints(net).build())
            }
            "notification" -> wm.cancelUniqueWork(name(t.id))
            Overnight.KIND -> {
                // The night shift waits for the charger as well as the clock.
                val delay = nextRunIn(t) ?: return
                wm.enqueueUniqueWork(name(t.id), ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<RoutineWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setInputData(data).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresCharging(true).build()).build())
            }
            else -> {
                val now = ZonedDateTime.now()
                val slot = nextSlot(t, now) ?: run { wm.cancelUniqueWork(name(t.id)); return }
                val delay = slot - now.toInstant().toEpochMilli()
                wm.enqueueUniqueWork(name(t.id), ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<RoutineWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS)
                        .setInputData(workDataOf("id" to t.id, "slot" to slot)).setConstraints(net).build())
            }
        }
    }

    fun runNow(context: Context, id: Long, context_: String = "") {
        WorkManager.getInstance(context).enqueueUniqueWork("$id-now-${System.currentTimeMillis() / 60_000}", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RoutineWorker>().setInputData(workDataOf("id" to id, "manual" to true, "context" to context_)).build())
    }

    fun cancel(context: Context, id: Long) = WorkManager.getInstance(context).cancelUniqueWork(name(id))

    suspend fun rescheduleAll(context: Context) = App.graph(context).db.triggers().list().forEach { schedule(context, it) }

    /** Pull the newest message id out of a Composio GMAIL_FETCH_EMAILS result, whatever its shape. */
    fun newestMessageId(data: JSONObject): String? {
        val arr = data.optJSONArray("messages") ?: data.optJSONObject("data")?.optJSONArray("messages") ?: return null
        val first = arr.optJSONObject(0) ?: return null
        return first.optString("messageId").ifBlank { first.optString("id") }.ifBlank { null }
    }
}

class RoutineWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = App.graph(applicationContext)
        val id = inputData.getLong("id", -1)
        val t = g.db.triggers().get(id) ?: return Result.success()
        val manual = inputData.getBoolean("manual", false) && inputData.getString("context").isNullOrEmpty()
        if (!t.enabled && !inputData.getBoolean("manual", false)) return Result.success()

        if (t.kind == com.past9.phoneaos.cards.CardScripts.KIND) {
            val out = runCatching { com.past9.phoneaos.cards.CardScripts.tick(applicationContext, t) }.getOrElse { "Refresh failed: ${it.message}" }
            g.db.triggers().get(id)?.let { g.db.triggers().upsert(it.copy(lastRunAt = System.currentTimeMillis(), lastResult = out.take(300))) }
            return Result.success()
        }
        if (t.kind == Watchers.KIND) {
            val out = runCatching { Watchers.tick(applicationContext, t) }.getOrElse { "Check failed: ${it.message}" }
            g.db.triggers().get(id)?.let { g.db.triggers().upsert(it.copy(lastRunAt = System.currentTimeMillis(), lastResult = out.take(300))) }
            return Result.success()
        }
        if (t.kind == Overnight.KIND) {
            val out = runCatching { Overnight.run(g, full = true) }.getOrElse { "Overnight run failed: ${it.message}" }
            g.db.triggers().upsert(t.copy(lastRunAt = System.currentTimeMillis(), lastResult = out.take(500)))
            if (!manual) Routines.schedule(applicationContext, g.db.triggers().get(id)!!)
            return Result.success()
        }
        // Once per scheduled slot, however many things start it (the queued job, a catch-up, a WorkManager retry).
        val slot = inputData.getLong("slot", 0L)
        val store = PrefsRunStore(applicationContext)
        val timedRun = slot > 0 && !inputData.getBoolean("manual", false) && t.kind in setOf("at", "daily", "weekly")
        var lateNote = ""
        if (timedRun) {
            val verdict = RunLedger.claim(store, id, slot, retried = runAttemptCount > 0)
            if (verdict == RunLedger.Verdict.SKIP) return Result.success()
            if (verdict == RunLedger.Verdict.RERUN) lateNote = "(The app was closed partway through the last attempt at this, so some of it may already be done. Check before you redo anything.)\n"
            if (System.currentTimeMillis() - slot > 10 * 60_000) lateNote += "(This was due at ${java.time.Instant.ofEpochMilli(slot).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0)} and runs late because the phone or app was off then. Run it once; don't repeat missed days.)\n"
        }
        var prompt = inputData.getString("context").orEmpty() + lateNote + t.prompt
        var cursor = t.cursor
        if (t.kind == "email" && !manual) {
            // Event trigger: only run when a NEW email matches the search.
            val res = runCatching { g.runtime.composio.execute("GMAIL_FETCH_EMAILS", JSONObject().put("query", t.spec).put("max_results", 3)) }.getOrNull()
                ?: return Result.retry()
            val newest = Routines.newestMessageId(res.optJSONObject("data") ?: res) ?: return Result.success()
            if (cursor.isBlank()) { g.db.triggers().upsert(t.copy(cursor = newest)); return Result.success() } // first look: just remember
            if (newest == cursor) return Result.success()
            cursor = newest
            prompt = "A new email matched \"${t.spec}\" (message id $newest). Read it with your Gmail tools, then: ${t.prompt}"
        }

        val out = g.runtime.runBackground(t.name, prompt)
        // Re-read: the row may have been edited while this ran.
        val now = g.db.triggers().get(id) ?: return Result.success()
        g.db.triggers().upsert(now.copy(lastRunAt = System.currentTimeMillis(), lastResult = out.take(500), cursor = cursor,
            enabled = if (t.kind == "at" && !manual) false else now.enabled))
        if (timedRun) RunLedger.done(store, id, slot)
        if ((t.kind == "daily" || t.kind == "weekly") && !manual) g.db.triggers().get(id)?.let { Routines.schedule(applicationContext, it) }
        return Result.success()
    }
}
