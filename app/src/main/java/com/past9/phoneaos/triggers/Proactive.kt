package com.past9.phoneaos.triggers

import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Proactive wakes. There is NO fixed heartbeat: the app only wakes for a reason it was given.
 *
 * A wake = a moment + a reason + a job + a cheap check. The agent schedules them itself (wake_schedule: "read the
 * inbox for the Brightwater reply on Monday", "follow up the application in 5 days"), and the app adds its own from
 * the calendar (a meeting ends: read the transcript). When a wake is due, its CHECK runs first in plain code
 * (a phone notification arrived, a task is still open, a watcher-style script says something changed, a
 * transcript showed up). Only checks that find something wake the AI, and every wake that is due within a few
 * minutes of each other goes to the AI as ONE run.
 *
 * This file is pure Kotlin (no Android) so every rule is unit-tested: WakeEngineTest.
 */

/** Quiet hours [start, end) in local time; may wrap midnight. start == end means never quiet. */
data class QuietHours(val start: LocalTime = LocalTime.of(23, 0), val end: LocalTime = LocalTime.of(7, 0)) {
    fun isQuiet(t: LocalTime): Boolean = when {
        start == end -> false
        start < end -> !t.isBefore(start) && t.isBefore(end)
        else -> !t.isBefore(start) || t.isBefore(end)
    }
    fun isQuiet(at: ZonedDateTime) = isQuiet(at.toLocalTime())

    /** When quiet hours are over: [at] itself when it isn't quiet. */
    fun nextLoud(at: ZonedDateTime): ZonedDateTime {
        if (!isQuiet(at)) return at
        var e = at.with(end).withSecond(0).withNano(0)
        if (!e.isAfter(at)) e = e.plusDays(1)
        return e
    }
    override fun toString() = if (start == end) "off" else "$start-$end"

    companion object {
        /** "23:00-07:00", or "off". */
        fun parse(s: String?): QuietHours? {
            if (s.isNullOrBlank()) return null
            if (s.trim().equals("off", true)) return QuietHours(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT)
            return runCatching { val (a, b) = s.split("-").map { LocalTime.parse(it.trim()) }; QuietHours(a, b) }.getOrNull()
        }
    }
}

/** How a wake decides, in code, whether the AI is needed. */
data class Check(
    /** none | notification | task_open | script | transcript | heads_up */
    val type: String = NONE,
    /** notification/transcript: app name (any app when blank). */
    val app: String = "",
    /** notification/transcript: any of these words, case-insensitive (all notifications when empty). */
    val words: List<String> = emptyList(),
    /** task_open: the task that must still be open. */
    val taskId: Long = 0,
    /** script: Node script with the watcher contract (last line {"fire":bool,"message":"..."}). */
    val script: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("type", type).put("app", app).put("words", org.json.JSONArray(words)).put("taskId", taskId).put("script", script)
    companion object {
        const val NONE = "none"; const val NOTIFICATION = "notification"; const val TASK_OPEN = "task_open"
        const val SCRIPT = "script"; const val TRANSCRIPT = "transcript"
        /** Code only: says something to the user and never wakes the AI (a meeting in 30 minutes). */
        const val HEADS_UP = "heads_up"
        val TYPES = listOf(NONE, NOTIFICATION, TASK_OPEN, SCRIPT, TRANSCRIPT, HEADS_UP)
        fun fromJson(o: JSONObject?): Check = if (o == null) Check() else Check(o.optString("type", NONE), o.optString("app"),
            o.optJSONArray("words")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList(),
            o.optLong("taskId"), o.optString("script"))
    }
}

data class Wake(
    val id: Long = 0,
    /** Why it wakes, said in a few words ("Brightwater reply", "Meeting with Dana ended"). Required: no reason, no wake. */
    val reason: String,
    /** What to do when the check finds something, as an instruction to the agent. */
    val job: String,
    /** When (epoch ms). */
    val at: Long,
    val check: Check = Check(),
    /** May break quiet hours. */
    val urgent: Boolean = false,
    /** When the check finds nothing: look again this many minutes later, up to [maxTries] looks in all. */
    val retryMinutes: Int = 0,
    val maxTries: Int = 1,
    val tries: Int = 0,
    /** agent | calendar */
    val source: String = "agent",
    /** Calendar wakes: "after:<event>:<begin>" / "prep:<event>:<begin>", so each is made once. */
    val key: String = "",
    /** Checks look for what arrived after this moment (a notification, a transcript). */
    val since: Long = 0,
    val createdAt: Long = 0,
)

/** What a check found. */
sealed class Probe {
    /** Something to act on: wake the AI with this. */
    data class Found(val detail: String) : Probe()
    /** Nothing yet: retry if allowed, else let it go. */
    object Nothing : Probe()
    /** The reason is gone (the task got done, the meeting was cancelled): drop it. */
    object Gone : Probe()
    /** Code-only: tell the user this, no AI. */
    data class Tell(val title: String, val body: String) : Probe()
}

// What the engine needs from the phone. Fakes stand in for them in the tests.
interface WakeStore {
    suspend fun all(): List<Wake>
    suspend fun save(w: Wake): Long
    suspend fun delete(id: Long)
    /** Calendar wakes already dealt with (keys), so a later calendar sync doesn't make them again. */
    suspend fun done(): Set<String>
    suspend fun markDone(key: String)
}
interface WakeProbes { suspend fun check(w: Wake, now: Long): Probe }
interface WakeAgent {
    /** One AI run for every wake that fired together. Returns what it said. */
    suspend fun run(title: String, prompt: String): String
}
interface WakeNotifier { fun tell(title: String, body: String) }
interface WakeAlarm {
    /** Arm the next wake-up, or clear it (null): with nothing to do, the phone is never woken. */
    fun arm(at: Long?)
}

data class EventInfo(
    val id: Long, val title: String, val begin: Long, val end: Long,
    val allDay: Boolean = false, val attendees: Int = 1, val online: Boolean = false, val declined: Boolean = false,
) {
    /** A real meeting (people or a call), not focus time or a birthday. */
    val isMeeting get() = !allDay && !declined && (attendees >= 2 || online)
}

/** [woke] = wakes that went to the AI. */
data class FireReport(val ran: List<Wake>, val agentCalls: Int, val told: Int, val retried: Int, val dropped: Int, val woke: Int, val nextAt: Long?)

object WakePlan {
    const val MIN = 60_000L
    /** Wakes this close together are one wake-up and one AI run. */
    const val MERGE = 10 * MIN
    const val PREP_BEFORE = 30 * MIN
    const val AFTER_DELAY = 5 * MIN
    /** After a meeting: look for the transcript 5 min after it ends, then 20, 40, 80, 160 min later. */
    const val AFTER_RETRY_MIN = 20
    const val AFTER_TRIES = 5
    /** Calendar wakes are planned this far ahead. */
    const val HORIZON = 36 * 60 * MIN

    /** The moment this wake may actually go off: quiet hours push a non-urgent one to their end. */
    fun effectiveAt(w: Wake, quiet: QuietHours, zone: java.time.ZoneId): Long {
        if (w.urgent) return w.at
        val z = ZonedDateTime.ofInstant(Instant.ofEpochMilli(w.at), zone)
        return quiet.nextLoud(z).toInstant().toEpochMilli()
    }

    /** Due now: everything whose moment has come, plus anything due within [MERGE] (merged into this run). */
    fun due(wakes: List<Wake>, now: ZonedDateTime, quiet: QuietHours): List<Wake> {
        val n = now.toInstant().toEpochMilli()
        if (wakes.none { effectiveAt(it, quiet, now.zone) <= n }) return emptyList()
        return wakes.filter { effectiveAt(it, quiet, now.zone) <= n + MERGE }
    }

    /**
     * When to wake the phone next: the first wake, moved later (up to [MERGE]) when that lets it share one wake-up
     * with the ones right after it. Null when nothing is scheduled: no reason, no wake.
     */
    fun nextAlarm(wakes: List<Wake>, now: ZonedDateTime, quiet: QuietHours): Long? {
        val n = now.toInstant().toEpochMilli()
        val times = wakes.map { effectiveAt(it, quiet, now.zone).coerceAtLeast(n + 1000) }.sorted()
        val first = times.firstOrNull() ?: return null
        // Slide to the last one within the window only if every one of them can still wait (none is urgent).
        val cluster = times.filter { it <= first + MERGE }
        // An urgent one in the group is never held back to wait for the others.
        val anyUrgent = wakes.any { it.urgent && effectiveAt(it, quiet, now.zone).coerceAtLeast(n + 1000) <= first + MERGE }
        return if (anyUrgent) first else cluster.last().coerceAtMost(first + MERGE)
    }

    /** Group wakes into the wake-ups they'll share (for display and tests). */
    fun clusters(wakes: List<Wake>, quiet: QuietHours, zone: java.time.ZoneId): List<List<Wake>> {
        val sorted = wakes.sortedBy { effectiveAt(it, quiet, zone) }
        val out = mutableListOf<MutableList<Wake>>()
        for (w in sorted) {
            val t = effectiveAt(w, quiet, zone)
            val last = out.lastOrNull()
            if (last != null && t - effectiveAt(last.first(), quiet, zone) <= MERGE) last += w else out += mutableListOf(w)
        }
        return out
    }

    /** A wake with no reason or no job is refused: the rule is no reason, no wake. */
    fun validate(w: Wake, now: Long): String? = when {
        w.reason.isBlank() -> "A wake needs a reason."
        w.job.isBlank() && w.check.type != Check.HEADS_UP -> "A wake needs a job: what to do when it fires."
        w.at < now - MIN -> "That time is in the past."
        w.check.type !in Check.TYPES -> "check must be one of ${Check.TYPES.joinToString()}."
        w.check.type == Check.SCRIPT && w.check.script.isBlank() -> "A script check needs the script."
        w.check.type == Check.TASK_OPEN && w.check.taskId <= 0 -> "A task_open check needs task_id."
        w.retryMinutes in 1 until 15 -> "Retry at most every 15 minutes."
        else -> null
    }

    /**
     * The calendar's wakes: a heads-up before each meeting (code only, no AI) and, after it ends, a transcript
     * check that wakes the AI only once a transcript or notes have arrived. Returns what to add and which
     * calendar wakes no longer match a meeting (cancelled or moved) to delete.
     */
    fun calendarWakes(events: List<EventInfo>, existing: List<Wake>, now: Long, afterMeeting: Boolean = true, done: Set<String> = emptySet()): Pair<List<Wake>, List<Wake>> {
        val want = mutableListOf<Wake>()
        for (e in events) {
            if (!e.isMeeting || e.begin > now + HORIZON) continue
            val name = e.title.ifBlank { "Meeting" }
            if (e.begin - PREP_BEFORE > now)
                want += Wake(reason = "$name starts soon", job = "", at = e.begin - PREP_BEFORE, check = Check(Check.HEADS_UP), source = "calendar",
                    key = "prep:${e.id}:${e.begin}", since = e.begin, urgent = true)
            if (afterMeeting && e.end + AFTER_DELAY > now - 4 * 60 * MIN)
                want += Wake(reason = "$name ended", source = "calendar", key = "after:${e.id}:${e.begin}", since = e.begin,
                    at = (e.end + AFTER_DELAY).coerceAtLeast(now), retryMinutes = AFTER_RETRY_MIN, maxTries = AFTER_TRIES,
                    check = Check(Check.TRANSCRIPT, words = titleWords(name)),
                    job = "The meeting \"$name\" (${hm(e.begin)}-${hm(e.end)}) has ended and its notes or transcript have arrived. Read the transcript " +
                        "(Fathom, Google Meet notes in Gmail, Teams, Otter, Fireflies, Zoom, or wherever the notification came from). Pull out the decisions and " +
                        "the action items that are the user's, add each to their to-do list (task_add owner user, with a due date when one was said), " +
                        "and schedule follow-ups with wake_schedule where something is owed to them. Tell the user in two or three short lines.")
        }
        val have = existing.filter { it.source == "calendar" }.associateBy { it.key }
        val add = want.filter { it.key !in have && it.key !in done }
        val wantKeys = want.map { it.key }.toSet()
        // Gone from the calendar: drop, except an after-meeting check already under way (the meeting happened).
        val stale = have.values.filter { it.key !in wantKeys && !(it.key.startsWith("after:") && it.tries > 0) && it.at > now }
        return add to stale
    }

    fun titleWords(title: String): List<String> = title.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 }
        .filter { it.lowercase() !in setOf("meeting", "call", "sync", "with", "weekly", "daily", "catch", "review") }.take(4)

    private fun hm(ms: Long) = Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0).toString()

    /** The one prompt for every wake that found something. */
    fun prompt(found: List<Pair<Wake, String>>): String = buildString {
        appendLine("You were woken because ${if (found.size == 1) "this needs" else "these need"} attention. Each has the reason it was scheduled, what the check found, and the job.")
        found.forEachIndexed { i, (w, detail) ->
            appendLine(); appendLine("${i + 1}. Reason: ${w.reason}")
            if (detail.isNotBlank()) appendLine("   Found: ${detail.take(1200)}")
            appendLine("   Job: ${w.job}")
        }
        appendLine()
        appendLine("Do the jobs. Then decide what needs following up and when, and schedule each with wake_schedule (a reason, a time, a job, and the cheapest check that tells whether it is worth waking you). Merge with wakes already scheduled (wake_list) instead of adding near-duplicates.")
        appendLine("Your reply becomes a notification. If something needs the user, say it in one or two short lines (what, and what they should do). If nothing needs them, reply with exactly QUIET and nothing else.")
    }

    /**
     * Follow-ups grow with use. A new user gets a few open follow-ups; every ten messages the user sends in the last
     * two weeks earns one more, up to a cap, so an agent that is being used a lot follows up on more things and one
     * that is ignored stays quiet. Past the budget, a new wake has to replace or merge into an existing one.
     */
    fun followUpBudget(userMessagesLast14Days: Int): Int = (BASE_FOLLOW_UPS + userMessagesLast14Days / 10).coerceAtMost(MAX_FOLLOW_UPS)
    const val BASE_FOLLOW_UPS = 3
    const val MAX_FOLLOW_UPS = 24

    const val QUIET = "QUIET"
    fun isQuiet(reply: String) = reply.trim().trim('.', '"', '*').equals(QUIET, true)
}

/** Runs wakes. Everything that touches the phone comes in through the interfaces. */
class WakeEngine(
    private val store: WakeStore,
    private val probes: WakeProbes,
    private val agent: WakeAgent,
    private val notifier: WakeNotifier,
    private val alarm: WakeAlarm,
    private val quiet: () -> QuietHours,
) {
    /** Schedule one (from the agent). Refused without a reason. */
    suspend fun schedule(w: Wake, now: Long): Pair<Long?, String?> {
        WakePlan.validate(w, now)?.let { return null to it }
        val id = store.save(w.copy(createdAt = now, since = if (w.since > 0) w.since else now))
        return id to null
    }

    /** Bring calendar wakes up to date (no AI). */
    suspend fun syncCalendar(events: List<EventInfo>, now: Long, afterMeeting: Boolean = true) {
        val (add, stale) = WakePlan.calendarWakes(events, store.all(), now, afterMeeting, store.done())
        stale.forEach { store.delete(it.id) }
        add.forEach { store.save(it.copy(createdAt = now)) }
    }

    suspend fun rearm(now: ZonedDateTime): Long? = WakePlan.nextAlarm(store.all(), now, quiet()).also { alarm.arm(it) }

    /** The alarm went off: run the checks, wake the AI once if any found something, re-arm. */
    suspend fun fire(now: ZonedDateTime): FireReport {
        val n = now.toInstant().toEpochMilli()
        val q = quiet()
        val due = WakePlan.due(store.all(), now, q)
        val found = mutableListOf<Pair<Wake, String>>()
        var told = 0; var retried = 0; var dropped = 0
        suspend fun finish(w: Wake) { store.delete(w.id); if (w.key.isNotBlank()) store.markDone(w.key) }
        for (w in due) {
            when (val p = runCatching { probes.check(w, n) }.getOrElse { Probe.Nothing }) {
                // Kept until the AI has really been woken: a failed run must not lose the reason.
                is Probe.Found -> found += w to p.detail
                is Probe.Tell -> { notifier.tell(p.title, p.body); told++; finish(w) }
                Probe.Gone -> { finish(w); dropped++ }
                Probe.Nothing -> {
                    val tries = w.tries + 1
                    if (tries < w.maxTries && w.retryMinutes > 0) {
                        // Back off: 20, 40, 80... minutes. Same reason, still one wake each time.
                        store.save(w.copy(tries = tries, at = n + w.retryMinutes * WakePlan.MIN * (1L shl (tries - 1).coerceAtMost(4))))
                        retried++
                    } else { finish(w); dropped++ }
                }
            }
        }
        var calls = 0
        if (found.isNotEmpty()) {
            val title = found.joinToString(", ") { it.first.reason }.take(80)
            calls++
            // The agent's reply is posted by the runtime (or not, when it says QUIET).
            val ok = runCatching { agent.run(title, WakePlan.prompt(found)) }.isSuccess
            if (ok) found.forEach { finish(it.first) }
            else found.forEach { (w, _) ->
                // The AI couldn't be reached (no network, no key): ask again in 15 minutes, three times at most, then let it go.
                if (w.tries < RETRY_AI) store.save(w.copy(tries = w.tries + 1, maxTries = maxOf(w.maxTries, w.tries + 2), at = n + 15 * WakePlan.MIN)) else finish(w)
            }
        }
        val next = rearm(now)
        return FireReport(due, calls, told, retried, dropped, found.size, next)
    }

    companion object { const val RETRY_AI = 3 }
}
