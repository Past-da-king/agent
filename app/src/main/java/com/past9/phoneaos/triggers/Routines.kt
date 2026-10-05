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
        "interval", Watchers.KIND -> t.lastRunAt?.let { (it + (t.spec.toLongOrNull() ?: 60) * 60_000 - now.toInstant().toEpochMilli()).coerceAtLeast(0) } ?: ((t.spec.toLongOrNull() ?: 60) * 60_000)
        "email" -> 15 * 60_000L
        else -> null // "notification" fires on arrival, not on a clock
    }

    fun schedule(context: Context, t: TriggerRow) {
        val wm = WorkManager.getInstance(context)
        if (!t.enabled) { wm.cancelUniqueWork(name(t.id)); return }
        val data = workDataOf("id" to t.id)
        when (t.kind) {
            "interval", "email", Watchers.KIND -> {
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
                val delay = nextRunIn(t) ?: run { wm.cancelUniqueWork(name(t.id)); return }
                wm.enqueueUniqueWork(name(t.id), ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<RoutineWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).setInputData(data).setConstraints(net).build())
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
        var prompt = inputData.getString("context").orEmpty() + t.prompt
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
        g.db.triggers().upsert(t.copy(lastRunAt = System.currentTimeMillis(), lastResult = out.take(500), cursor = cursor,
            enabled = if (t.kind == "at" && !manual) false else t.enabled))
        if (t.kind == "daily" && !manual) Routines.schedule(applicationContext, g.db.triggers().get(id)!!)
        return Result.success()
    }
}
