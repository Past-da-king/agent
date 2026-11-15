package com.past9.phoneaos.people

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.past9.phoneaos.App
import com.past9.phoneaos.MainActivity
import com.past9.phoneaos.system.ExactAlarms
import com.past9.phoneaos.system.Identity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The pure part of an important-date reminder (GitHub issue #13): which dates are still to come, which alarm is
 * already armed, which moment has gone by while the phone was off. No clock and no Android in here, so the tests can
 * prove a date arms exactly one reminder and a second run adds nothing.
 */
object PersonReminderPlan {
    const val MISSED_WINDOW = 24 * 3_600_000L

    /** One date that wants a reminder. [key] is the same every time for the same date and moment. */
    data class Due(val key: String, val personId: Long, val dateKey: String, val label: String, val day: Long, val at: Long)

    data class Plan(val arm: List<Due>, val cancel: Set<String>, val missed: List<Due>)

    fun plan(due: List<Due>, armed: Map<String, Long>, said: Set<String>, now: Long): Plan {
        val upcoming = due.filter { it.at > now }
        val arm = upcoming.filter { armed[it.key] != it.at }
        val cancel = armed.keys.filter { k -> upcoming.none { it.key == k } }.toSet()
        val missed = due.filter { it.at <= now && now - it.at < MISSED_WINDOW && it.key !in said }
        return Plan(arm, cancel, missed)
    }
}

/**
 * Important dates on the phone. Scheduling is the app's own: one exact alarm per date through [ExactAlarms], the same
 * clock tasks and routines ring on, so Doze and a sleeping Samsung do not lose it. Nothing new is invented: the alarm
 * shows a plain notification (no AI call), and the notification brings the person's gift ideas up with it.
 */
object PersonReminders {
    const val CHANNEL = "people"

    /** One armed reminder: which alarm, when, and whose date it is. */
    data class Arm(val id: Long, val at: Long, val personId: Long, val dateKey: String)

    private val lock = Mutex()
    private fun prefs(context: Context) = context.getSharedPreferences("people_reminders", Context.MODE_PRIVATE)

    /** The repository to plan from: the app's own, or one a test hands in (so the test never needs the real database). */
    private fun repo(context: Context, repo: PeopleRepository? = null) = repo ?: App.graph(context).people

    private fun armed(context: Context): MutableMap<String, Arm> = runCatching {
        val o = JSONObject(prefs(context).getString("armed", "{}")!!)
        o.keys().asSequence().associate { k ->
            val v = o.getJSONObject(k)
            k to Arm(v.getLong("id"), v.getLong("at"), v.optLong("person"), v.optString("date"))
        }.toMutableMap()
    }.getOrElse { mutableMapOf() }

    private fun said(context: Context): MutableSet<String> = prefs(context).getStringSet("said", emptySet())!!.toMutableSet()

    private fun save(context: Context, arms: Map<String, Arm>, said: Set<String>, nextId: Long) {
        val o = JSONObject()
        arms.forEach { (k, a) -> o.put(k, JSONObject().put("id", a.id).put("at", a.at).put("person", a.personId).put("date", a.dateKey)) }
        prefs(context).edit().putString("armed", o.toString())
            .putStringSet("said", said.toMutableSet().apply { retainAll(arms.keys) })
            .putLong("nextId", nextId).commit()
    }

    /** Re-plan every date: arm what is new, drop what is gone, say once what the phone missed. */
    suspend fun syncAll(context: Context, now: Long = System.currentTimeMillis(), repo: PeopleRepository? = null) = lock.withLock {
        plan(context, now, repo)
    }

    private suspend fun plan(context: Context, now: Long, repo: PeopleRepository?) {
        val due = runCatching { repo(context, repo).dueReminders(now) }.getOrDefault(emptyList())
        val arms = armed(context); val said = said(context)
        var nextId = prefs(context).getLong("nextId", 1L)
        val p = PersonReminderPlan.plan(due, arms.mapValues { it.value.at }, said, now)
        p.cancel.forEach { key -> arms.remove(key)?.let { ExactAlarms.cancel(context, ExactAlarms.ACTION_PERSON, it.id) } }
        p.arm.forEach { d ->
            if (arms[d.key] == null) {
                val id = nextId++
                ExactAlarms.set(context, ExactAlarms.ACTION_PERSON, id, d.at, clock = true)
                arms[d.key] = Arm(id, d.at, d.personId, d.dateKey)
            }
        }
        p.missed.forEach { d ->
            runCatching { repo(context, repo).person(d.personId) }.getOrNull()?.let { person ->
                show(context, person, person.dates.firstOrNull { it.key == d.dateKey }, missed = true)
            }
            said += d.key
        }
        save(context, arms, said, nextId)
    }

    /** The alarm for one date went off: say it, bring the gift ideas up with it, then plan again (next year's comes round). */
    suspend fun fire(context: Context, alarmId: Long, at: Long, repo: PeopleRepository? = null) = lock.withLock {
        val arms = armed(context)
        val entry = arms.entries.firstOrNull { it.value.id == alarmId && it.value.at == at }
        val said = said(context)
        if (entry != null) {
            val arm = entry.value
            runCatching { repo(context, repo).person(arm.personId) }.getOrNull()?.let { person ->
                show(context, person, person.dates.firstOrNull { it.key == arm.dateKey }, missed = false)
            }
            said += entry.key
            arms.remove(entry.key)
        }
        save(context, arms, said, prefs(context).getLong("nextId", 1L))
    }

    fun show(context: Context, person: Person, date: ImportantDate?, missed: Boolean) {
        val today = LocalDate.now()
        val day = date?.occurrenceOnOrAfter(today)
        val days = if (day == null) null else ChronoUnit.DAYS.between(today, day)
        val title = when {
            date == null || day == null -> person.name
            days == 0L -> "${person.name}: ${date.label} today"
            else -> "${person.name}: ${date.label} on ${day.format(DateTimeFormatter.ofPattern("EEE d MMM"))}"
        }
        val gifts = person.gifts.filterNot { it.given }
        val body = buildString {
            if (missed) append("While the phone was off. ")
            when {
                days == null -> append("An important date is coming up.")
                days <= 0L -> append("It's today.")
                days == 1L -> append("It's tomorrow.")
                else -> append("In $days days.")
            }
            if (gifts.isNotEmpty()) append("\nGift ideas: " + gifts.take(6).joinToString(", ") { it.text })
            else append("\nNo gift ideas yet. Tap to add one.")
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "People", NotificationManager.IMPORTANCE_HIGH))
        val st = App.graph(context).settings.state.value
        val nid = 400_000 + person.id.toInt()
        val open = PendingIntent.getActivity(context, nid,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("person", person.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(Identity.statIcon(context, st.mascot))
            .setContentTitle(title).setContentText(body.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true).setContentIntent(open).build()
        runCatching { NotificationManagerCompat.from(context).notify(nid, n) }
    }
}
