package com.past9.phoneaos.wakeup

import org.json.JSONArray
import org.json.JSONObject

/**
 * Wake-ups on the phone (desktop docs/plans/wake-ups.md): what the computer sends (event wakeups.changed, RPC
 * wakeups.list) and the same ring rules as the desktop's shared/wakeups.ts, so the phone decides for itself whether
 * it rings, even when the link has dropped since. Pure: no Android, tested on the JVM.
 */
data class Wake(
    val id: String,
    val severity: String,
    val title: String,
    val whatToDo: String,
    val link: String,
    val sourceKind: String,
    /** When it rings, on THIS phone's clock (the sender's time moved by the clock difference). */
    val ringAt: Long,
    val cancelled: Boolean,
    val targets: List<String>,
    val seen: Set<String>,
    val firedBy: String,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("severity", severity).put("title", title).put("whatToDo", whatToDo)
        .put("link", link).put("sourceKind", sourceKind).put("ringAt", ringAt).put("cancelled", cancelled)
        .put("targets", JSONArray(targets)).put("seen", JSONArray(seen.toList())).put("firedBy", firedBy)

    companion object {
        fun fromLocal(o: JSONObject): Wake? = runCatching {
            Wake(o.getString("id"), o.optString("severity", "urgent"), o.optString("title"), o.optString("whatToDo"), o.optString("link"),
                o.optString("sourceKind"), o.getLong("ringAt"), o.optBoolean("cancelled"), o.optJSONArray("targets").strings(),
                o.optJSONArray("seen").strings().toSet(), o.optString("firedBy"))
        }.getOrNull()
    }
}

data class WakeSnapshot(val wakes: List<Wake>, val snoozeMinutes: Int, val quietHours: String, val from: String) {
    fun toJson(): JSONObject = JSONObject().put("wakes", JSONArray(wakes.map { it.toJson() })).put("snoozeMinutes", snoozeMinutes)
        .put("quietHours", quietHours).put("from", from)

    companion object {
        val EMPTY = WakeSnapshot(emptyList(), 5, "", "")

        /** What the computer sent, with its clock: every time moved onto the phone's clock. */
        fun fromWire(o: JSONObject?, phoneNow: Long): WakeSnapshot? {
            if (o == null) return null
            val senderNow = o.optLong("now", phoneNow)
            val shift = phoneNow - senderNow
            val list = o.optJSONArray("wakes") ?: JSONArray()
            val wakes = (0 until list.length()).mapNotNull { i ->
                val w = list.optJSONObject(i) ?: return@mapNotNull null
                val id = w.optString("id")
                if (id.isBlank()) return@mapNotNull null
                val seen = w.optJSONObject("seen")?.keys()?.asSequence()?.toSet().orEmpty()
                Wake(id, w.optString("severity", "urgent"), w.optString("title"), w.optString("whatToDo"), w.optString("link"), w.optString("sourceKind"),
                    w.optLong("ringAt", senderNow) + shift, w.optBoolean("cancelled"), w.optJSONArray("targets").strings(), seen, w.optString("firedBy"))
            }
            return WakeSnapshot(wakes, o.optInt("snoozeMinutes", 5).coerceIn(1, 120), o.optString("quietHours"), o.optString("from"))
        }

        fun fromLocal(text: String?): WakeSnapshot = runCatching {
            val o = JSONObject(text ?: return EMPTY)
            val list = o.optJSONArray("wakes") ?: JSONArray()
            WakeSnapshot((0 until list.length()).mapNotNull { Wake.fromLocal(list.optJSONObject(it) ?: return@mapNotNull null) },
                o.optInt("snoozeMinutes", 5), o.optString("quietHours"), o.optString("from"))
        }.getOrDefault(EMPTY)
    }
}

/** One ringing wake, as the screen shows it. */
data class Ringing(val wake: Wake, val sound: Boolean)

object WakeRules {
    const val ESCALATE_AFTER_MS = 60_000L
    const val DELIVERY_WAIT_MS = 30_000L
    const val HEADS_UP_CYCLE_MS = 60_000L

    /** The app's light-theme tokens (--err, --tert, --ok), edge to edge with white text. */
    fun colour(severity: String): Long = when (severity) {
        "critical" -> 0xFFB3261EL
        "heads_up" -> 0xFF1E6B3AL
        else -> 0xFF8A5100L
    }

    fun label(severity: String): String = when (severity) {
        "critical" -> "Critical"
        "heads_up" -> "Heads-up"
        else -> "Urgent"
    }

    fun rank(severity: String): Int = when (severity) { "critical" -> 3; "urgent" -> 2; else -> 1 }

    /**
     * The same rules as the desktop's ringsHere: critical rings everywhere; urgent where it was sent, everywhere
     * after 60 s; heads-up only where it was sent, silent in quiet hours; anything sent to a device that has not
     * said it has it after 30 s rings everywhere. [answered]: wakes he answered here that the computer has not
     * confirmed yet (they stop at once, on this phone).
     */
    fun ringing(snap: WakeSnapshot, self: String, now: Long, quiet: Boolean, answered: Set<String>): List<Ringing> =
        snap.wakes.filter { it.id !in answered }.mapNotNull { w -> decide(w, self, now, quiet)?.let { Ringing(w, it) } }
            .sortedWith(compareByDescending<Ringing> { rank(it.wake.severity) }.thenBy { it.wake.ringAt })

    /** null: not ringing here now; true/false: ringing, with or without sound. */
    fun decide(w: Wake, self: String, now: Long, quiet: Boolean): Boolean? {
        if (now < w.ringAt) return null
        val sound = !(w.severity == "heads_up" && quiet)
        if (w.severity == "critical") return true
        val targeted = w.targets.isEmpty() || self in w.targets
        if (targeted) return sound
        val since = now - w.ringAt
        if (since >= DELIVERY_WAIT_MS && w.targets.any { it !in w.seen }) return sound
        if (w.severity == "urgent" && since >= ESCALATE_AFTER_MS) return true
        return null
    }

    /** The next moment anything can change for this phone (an alarm for it), or null. */
    fun nextChange(snap: WakeSnapshot, now: Long, answered: Set<String>): Long? = snap.wakes.filter { it.id !in answered }.flatMap { w ->
        if (now < w.ringAt) listOf(w.ringAt) else listOf(w.ringAt + DELIVERY_WAIT_MS, w.ringAt + ESCALATE_AFTER_MS).filter { it > now }
    }.minOrNull()

    /** "22:00-07:00" quiet hours ('' none); [minute] is the local minute of the day. */
    fun inQuiet(spec: String, minute: Int): Boolean {
        val m = Regex("""^(\d{1,2}):(\d{2})-(\d{1,2}):(\d{2})$""").find(spec.trim()) ?: return false
        val (h1, m1, h2, m2) = m.destructured
        val start = h1.toInt() * 60 + m1.toInt()
        val end = h2.toInt() * 60 + m2.toInt()
        if (start == end) return false
        return if (start < end) minute in start until end else minute >= start || minute < end
    }

    fun shownTitle(w: Wake): String = if (w.cancelled && !w.title.startsWith("Cancelled: ")) "Cancelled: ${w.title}" else w.title
}

internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
