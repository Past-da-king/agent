package com.past9.phoneaos.triggers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.past9.phoneaos.App
import com.past9.phoneaos.data.TriggerRow
import com.past9.phoneaos.system.ExactAlarms
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.ZonedDateTime

/** The phone side of [WakeEngine]: wakes live in the routines table (kind "wake"), the clock is an exact alarm. */
object Wakes {
    const val KIND = "wake"
    private val lock = Mutex()

    fun quiet(context: Context): QuietHours = QuietHours.parse(App.graph(context).settings.extra("quiet_hours")) ?: QuietHours()

    fun toRow(w: Wake): TriggerRow = TriggerRow(id = w.id, name = w.reason, kind = KIND, spec = w.at.toString(), prompt = w.job,
        cursor = JSONObject().put("check", w.check.toJson()).put("urgent", w.urgent).put("retry", w.retryMinutes).put("max", w.maxTries)
            .put("tries", w.tries).put("source", w.source).put("key", w.key).put("since", w.since).toString(),
        createdAt = if (w.createdAt > 0) w.createdAt else System.currentTimeMillis())

    fun fromRow(t: TriggerRow): Wake? {
        val at = t.spec.toLongOrNull() ?: return null
        val c = runCatching { JSONObject(t.cursor) }.getOrElse { JSONObject() }
        return Wake(t.id, t.name, t.prompt, at, Check.fromJson(c.optJSONObject("check")), c.optBoolean("urgent"), c.optInt("retry"),
            c.optInt("max", 1), c.optInt("tries"), c.optString("source", "agent"), c.optString("key"), c.optLong("since"), t.createdAt)
    }

    class Store(private val context: Context) : WakeStore {
        private val dao get() = App.graph(context).db.triggers()
        override suspend fun all() = dao.list().filter { it.kind == KIND && it.enabled }.mapNotNull { fromRow(it) }
        override suspend fun save(w: Wake): Long = dao.upsert(toRow(w)).let { if (w.id > 0) w.id else it }
        override suspend fun delete(id: Long) = dao.delete(id)
        private val prefs get() = context.getSharedPreferences("wakes", Context.MODE_PRIVATE)
        override suspend fun done(): Set<String> = prefs.getStringSet("done", emptySet())!!
        override suspend fun markDone(key: String) {
            // Newest 300 are plenty: calendar wakes only look 36 hours ahead and 4 back.
            val list = (prefs.getString("doneList", "")!!.split('\n').filter { it.isNotBlank() } + key).takeLast(300)
            prefs.edit().putString("doneList", list.joinToString("\n")).putStringSet("done", list.toSet()).commit()
        }
    }

    /** Apps that bring meeting notes or transcripts, and the words their notifications use. */
    private val transcriptApps = listOf("fathom", "gmail", "outlook", "teams", "otter", "fireflies", "zoom", "wispr", "meet", "read.ai", "tl;dv", "granola")
    private val transcriptWords = listOf("transcript", "notes", "recap", "summary", "action items", "recording")

    class Probes(private val context: Context) : WakeProbes {
        override suspend fun check(w: Wake, now: Long): Probe {
            val g = App.graph(context)
            val c = w.check
            return when (c.type) {
                Check.NONE -> Probe.Found("")
                Check.HEADS_UP -> {
                    val inMin = ((w.since - now) / 60_000).coerceAtLeast(0)
                    if (w.since in 1 until now) Probe.Gone else Probe.Tell(w.reason.removeSuffix(" starts soon"),
                        "Starts in $inMin min, at ${java.time.Instant.ofEpochMilli(w.since).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0)}.")
                }
                Check.TASK_OPEN -> {
                    val t = g.db.tasks().task(c.taskId)
                    if (t == null || t.status == "done") Probe.Gone else Probe.Found("Task #${t.id} \"${t.title}\" is still ${t.status}.")
                }
                Check.NOTIFICATION -> {
                    val hits = g.db.notifications().since(w.since).filter { n ->
                        (c.app.isBlank() || n.app.contains(c.app, true) || n.pkg.contains(c.app, true)) &&
                            (c.words.isEmpty() || c.words.any { word -> n.title.contains(word, true) || n.text.contains(word, true) })
                    }
                    if (hits.isEmpty()) Probe.Nothing else Probe.Found(hits.take(5).joinToString("\n") { "${it.app}: ${it.title}: ${it.text.take(200)}" })
                }
                Check.TRANSCRIPT -> {
                    val notes = g.db.notifications().since(w.since)
                    val hits = notes.filter { n ->
                        val app = (n.app + " " + n.pkg).lowercase(); val body = (n.title + " " + n.text).lowercase()
                        transcriptApps.any { it in app } && (transcriptWords.any { it in body } || c.words.any { it.lowercase() in body })
                    }
                    when {
                        hits.isNotEmpty() -> Probe.Found(hits.take(4).joinToString("\n") { "${it.app}: ${it.title}: ${it.text.take(200)}" })
                        // No way to see a transcript arrive (no notification access to any notes app): let the agent look once,
                        // on the second try (about 25 min after the end), rather than never.
                        !canSeeTranscripts(context) && w.tries >= 1 ->
                            Probe.Found("(The phone can't see notes apps' notifications, so look for the transcript yourself: Fathom, Gmail, Teams. If there is none yet, reply QUIET.)")
                        else -> Probe.Nothing
                    }
                }
                Check.SCRIPT -> {
                    val o = Watchers.execute(context, c.script, "{}")
                    when {
                        !o.ok -> Probe.Found("The check script failed, so check by hand and fix it if you schedule it again:\n${o.raw.take(800)}")
                        o.fire -> Probe.Found(o.message)
                        else -> Probe.Nothing
                    }
                }
                else -> Probe.Found("")
            }
        }
    }

    fun canSeeTranscripts(context: Context): Boolean {
        val apps = App.graph(context).settings.state.value.notifApps.map { it.lowercase() }
        return apps.any { a -> transcriptApps.any { it in a } }
    }

    private class Agent(private val context: Context) : WakeAgent {
        override suspend fun run(title: String, prompt: String): String {
            ExactAlarms.holdProcess(context)
            val g = App.graph(context)
            // No AI to run it with: fail, so the engine keeps the wake and asks again later instead of dropping it with a notice.
            check(g.runtime.canRunBackground()) { "no AI available" }
            return g.runtime.runBackground(title, prompt, silentIf = WakePlan::isQuiet)
        }
    }
    private class Notifier(private val context: Context) : WakeNotifier {
        override fun tell(title: String, body: String) = App.graph(context).phone.notify(title, body)
    }
    private class Alarm(private val context: Context) : WakeAlarm {
        override fun arm(at: Long?) {
            if (at == null) ExactAlarms.cancel(context, ExactAlarms.ACTION_WAKE, 0) else ExactAlarms.set(context, ExactAlarms.ACTION_WAKE, 0, at)
        }
    }

    fun engine(context: Context) = WakeEngine(Store(context), Probes(context), Agent(context), Notifier(context), Alarm(context)) { quiet(context) }

    /** The alarm went off. */
    suspend fun fire(context: Context) = lock.withLock {
        val e = engine(context)
        runCatching { e.syncCalendar(Calendar.events(context), System.currentTimeMillis(), afterMeeting(context)) }
        e.fire(ZonedDateTime.now())
    }

    /** Something changed (a wake added, the calendar changed, a reboot): plan again, no AI. */
    suspend fun rearm(context: Context) = lock.withLock {
        val e = engine(context)
        runCatching { e.syncCalendar(Calendar.events(context), System.currentTimeMillis(), afterMeeting(context)) }
        e.rearm(ZonedDateTime.now())
        Calendar.watch(context)
    }

    fun afterMeeting(context: Context) = App.graph(context).settings.extra("after_meeting") != "off"
}

/** Reads the phone's own calendar (Google, Outlook, Samsung: whatever syncs to it). No AI, no network. */
object Calendar {
    fun allowed(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private val online = listOf("meet.google.com", "zoom.us", "teams.microsoft.com", "teams.live.com", "webex.com", "whereby.com", "gotomeeting", "fathom.video")

    fun events(context: Context, now: Long = System.currentTimeMillis()): List<EventInfo> {
        if (!allowed(context)) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, now - 5 * 3_600_000L); android.content.ContentUris.appendId(it, now + WakePlan.HORIZON)
        }.build()
        val cols = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS, CalendarContract.Instances.STATUS)
        val out = mutableListOf<EventInfo>()
        runCatching {
            context.contentResolver.query(uri, cols, null, null, CalendarContract.Instances.BEGIN)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(8) == CalendarContract.Events.STATUS_CANCELED) continue
                    val id = c.getLong(0)
                    val text = (c.getString(5).orEmpty() + " " + c.getString(6).orEmpty()).lowercase()
                    out += EventInfo(id, c.getString(1).orEmpty(), c.getLong(2), c.getLong(3), c.getInt(4) == 1, attendees(context, id),
                        online.any { it in text }, c.getInt(7) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED)
                }
            }
        }
        return out
    }

    private fun attendees(context: Context, eventId: Long): Int = runCatching {
        context.contentResolver.query(CalendarContract.Attendees.CONTENT_URI, arrayOf(CalendarContract.Attendees._ID),
            "${CalendarContract.Attendees.EVENT_ID} = ?", arrayOf(eventId.toString()), null)?.use { it.count } ?: 0
    }.getOrDefault(0)

    /**
     * Re-plan when the calendar changes, without polling: a content-uri trigger, so Android starts the worker only
     * when an event is added, moved or cancelled. It re-registers itself each time.
     */
    fun watch(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        if (!allowed(context)) return
        WorkManager.getInstance(context).enqueueUniqueWork("calendar-changed", policy,
            OneTimeWorkRequestBuilder<CalendarChangedWorker>().setConstraints(Constraints.Builder()
                .addContentUriTrigger(CalendarContract.Events.CONTENT_URI, true)
                .setTriggerContentUpdateDelay(java.time.Duration.ofSeconds(20)).setTriggerContentMaxDelay(java.time.Duration.ofMinutes(2)).build()).build())
    }
}

class CalendarChangedWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Wakes.rearm(applicationContext)
        // This job is still running, so rearm's own watch() (KEEP) skipped: queue the next look behind it.
        Calendar.watch(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }
}
