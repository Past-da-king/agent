package com.past9.phoneaos.triggers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.past9.phoneaos.App
import com.past9.phoneaos.MainActivity
import com.past9.phoneaos.system.ExactAlarms
import com.past9.phoneaos.system.Identity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Task reminders on the phone: alarm-clock alarms at each due time, a plain notification with Done and Later. */
object Reminders {
    const val CHANNEL = "reminders"
    private val lock = Mutex()
    private fun prefs(context: Context) = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    private fun armed(context: Context): MutableMap<Long, Long> = runCatching {
        val o = JSONObject(prefs(context).getString("armed", "{}")!!); o.keys().asSequence().associate { it.toLong() to o.getLong(it) }.toMutableMap()
    }.getOrElse { mutableMapOf() }
    private fun said(context: Context): MutableSet<String> = prefs(context).getStringSet("said", emptySet())!!.toMutableSet()

    suspend fun syncAll(context: Context, force: Boolean = false) = lock.withLock {
        val g = App.graph(context)
        val now = System.currentTimeMillis()
        val open = g.db.tasks().openTasks().filter { it.owner == "user" && it.dueAt != null }.map { ReminderPlan.Due(it.id, it.title, it.dueAt!!) }
        val armed = armed(context); val said = said(context)
        val p = ReminderPlan.plan(open, armed, said, now, force)
        p.cancel.forEach { ExactAlarms.cancel(context, ExactAlarms.ACTION_REMINDER, it); armed.remove(it) }
        p.arm.forEach { (id, at) -> ExactAlarms.set(context, ExactAlarms.ACTION_REMINDER, id, at, clock = true); armed[id] = at }
        p.missed.forEach { show(context, it.id, it.title, "Was due ${WakeClock.label(it.dueAt)}. Missed while the phone was off."); said += ReminderPlan.key(it.id, it.dueAt) }
        // Keep the record small: only keys for tasks that are still open.
        val openIds = open.map { it.id.toString() }.toSet()
        prefs(context).edit().putString("armed", JSONObject(armed.mapKeys { it.key.toString() }).toString())
            .putStringSet("said", said.filter { it.substringBefore('@') in openIds }.toSet()).commit()
    }

    /** The alarm for task [id] went off. */
    suspend fun fire(context: Context, id: Long, at: Long) = lock.withLock {
        val t = App.graph(context).db.tasks().task(id) ?: return@withLock
        val armed = armed(context); armed.remove(id)
        val said = said(context)
        if (t.status != "done" && t.dueAt == at && ReminderPlan.key(id, at) !in said) {
            show(context, id, t.title, t.notes.take(200).ifBlank { "It's time." })
            said += ReminderPlan.key(id, at)
        }
        prefs(context).edit().putString("armed", JSONObject(armed.mapKeys { it.key.toString() }).toString()).putStringSet("said", said).commit()
    }

    fun show(context: Context, id: Long, title: String, body: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        val st = App.graph(context).settings.state.value
        val nid = 200_000 + id.toInt()
        fun action(what: String, code: Int) = PendingIntent.getBroadcast(context, nid * 4 + code,
            Intent(context, ReminderActionReceiver::class.java).setAction(what).putExtra("id", id).putExtra("nid", nid), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(context, nid, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(Identity.statIcon(context, st.mascot))
            .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(open)
            .addAction(0, "Done", action(ReminderActionReceiver.DONE, 0)).addAction(0, "In 1 hour", action(ReminderActionReceiver.LATER, 1)).build()
        runCatching { NotificationManagerCompat.from(context).notify(nid, n) }
    }
}

object WakeClock {
    fun label(ms: Long): String {
        val z = java.time.ZoneId.systemDefault(); val t = java.time.Instant.ofEpochMilli(ms).atZone(z); val today = java.time.LocalDate.now(z)
        val hm = "%02d:%02d".format(t.hour, t.minute)
        return when (t.toLocalDate()) { today -> "at $hm"; today.minusDays(1) -> "yesterday at $hm"; else -> "${t.toLocalDate()} at $hm" }
    }
}

/** Done / In 1 hour, straight from the reminder. */
class ReminderActionReceiver : BroadcastReceiver() {
    companion object { const val DONE = "com.past9.phoneaos.reminder.DONE"; const val LATER = "com.past9.phoneaos.reminder.LATER" }
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1)
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra("nid", 0))
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = App.graph(context).db.tasks()
                val t = dao.task(id) ?: return@launch
                val now = System.currentTimeMillis()
                when (intent.action) {
                    DONE -> dao.updateTask(t.copy(status = "done", updatedAt = now))
                    LATER -> dao.updateTask(t.copy(dueAt = now + 3_600_000L, updatedAt = now))
                }
                Reminders.syncAll(context)
            } finally { pending.finish() }
        }
    }
}
