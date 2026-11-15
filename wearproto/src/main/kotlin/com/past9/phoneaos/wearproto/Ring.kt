package com.past9.phoneaos.wearproto

import org.json.JSONArray
import org.json.JSONObject

/**
 * The agent rings the watch (docs/wear.md, "Ring"). Two things ring it: a wake-up (the phone's own, see wakeup/WakeHub: critical,
 * urgent, heads-up) and an approval that waits for him. The phone stays the one place that decides and the one place that hears
 * an answer, so "an answer on one device stops all" holds: the watch only shows what the phone says rings, and what he answers
 * on the watch goes to the phone's own WakeHub / runtime.answer paths.
 *
 *  phone -> watch  DataClient   RING   the wakes that ring now (one item, replaced; empty list = nothing rings)
 *  phone -> watch  DataClient   STATE  the approvals that wait (existing)
 *  watch -> phone  MessageClient RING_ANSWER {id, what: ack|snooze}   (phone answers ACK)
 */
object RingPaths {
    const val RING = "/agent/ring"
    const val ANSWER = "/agent/ring/answer"
}

object RingKind { const val WAKE = "wake"; const val APPROVAL = "approval" }
object RingAnswer { const val ACK = "ack"; const val SNOOZE = "snooze"; val ALL = setOf(ACK, SNOOZE) }

/** One thing that rings. [id] is the wake's id, or "card:<n>" for an approval. [sound] false = silent (heads-up in quiet hours). */
data class RingItem(
    val id: String, val kind: String, val severity: String, val title: String, val whatToDo: String, val sound: Boolean = true, val link: String = "",
) {
    val cardId: Long? get() = if (kind == RingKind.APPROVAL) id.removePrefix("card:").toLongOrNull() else null
    companion object {
        fun approval(p: Pending) = RingItem("card:${p.id}", RingKind.APPROVAL, "urgent", p.title, p.details.ifBlank { p.askedBy.ifBlank { "Needs your yes" } })
    }
}

object RingWire {
    private fun bytes(o: JSONObject) = o.toString().toByteArray(Charsets.UTF_8)

    fun items(list: List<RingItem>): ByteArray = bytes(JSONObject().put("v", 1).put("ring", JSONArray().also { a ->
        list.forEach { r -> a.put(JSONObject().put("id", r.id).put("kind", r.kind).put("severity", r.severity).put("title", Limits.clip(r.title, 120))
            .put("what", Limits.clip(r.whatToDo, 300)).put("sound", r.sound).put("link", r.link)) }
    }))

    fun parseItems(b: ByteArray?): List<RingItem>? {
        val o = if (b == null) null else runCatching { JSONObject(String(b, Charsets.UTF_8)) }.getOrNull() ?: return null
        val a = o?.optJSONArray("ring") ?: return null
        return (0 until a.length()).mapNotNull { i ->
            val r = a.optJSONObject(i) ?: return@mapNotNull null
            val id = r.optString("id"); if (id.isBlank()) return@mapNotNull null
            RingItem(id, r.optString("kind", RingKind.WAKE), r.optString("severity", "urgent"), r.optString("title"), r.optString("what"), r.optBoolean("sound", true), r.optString("link"))
        }
    }

    fun answer(id: String, what: String): ByteArray = bytes(JSONObject().put("id", id).put("what", what))
    fun parseAnswer(b: ByteArray?): Pair<String, String>? {
        val o = if (b == null) null else runCatching { JSONObject(String(b, Charsets.UTF_8)) }.getOrNull() ?: return null
        val id = o?.optString("id").orEmpty(); val what = o?.optString("what").orEmpty()
        return if (id.isBlank() || what !in RingAnswer.ALL) null else id to what
    }
}

/** The phone's check on a ring answer from the watch: only something that rings now, and only ack or snooze. */
object RingRules {
    fun check(ringingIds: Set<String>, id: String, what: String): RelayResult = when {
        what !in RingAnswer.ALL -> RelayResult(false, "That is not an answer.")
        id !in ringingIds -> RelayResult(false, "That is not ringing any more.")
        else -> RelayResult(true)
    }
}

/**
 * The watch's view of what rings. It follows the phone's list and nothing else, and remembers what he answered on the watch
 * until the phone has caught up, so a late list that still holds an answered wake does not start it ringing again.
 * [apply] says what to start and what to stop; the Android side turns that into a notification, a full screen and a vibration.
 */
class RingBoard {
    data class Update(val started: List<RingItem>, val stopped: List<String>, val changed: List<RingItem>)

    private val live = LinkedHashMap<String, RingItem>()
    private val answeredHere = HashSet<String>()

    /** Wakes the phone says ring, and approvals that wait: the whole current picture each time. */
    @Synchronized fun apply(wakes: List<RingItem>, approvals: List<RingItem> = emptyList()): Update {
        val now = (wakes + approvals).distinctBy { it.id }
        val ids = now.map { it.id }.toSet()
        // Gone from the phone's list: whatever he answered here has been seen by the phone, forget it (a snooze comes back later as new).
        answeredHere.retainAll(ids)
        val wanted = now.filter { it.id !in answeredHere }
        val wantedIds = wanted.map { it.id }.toSet()
        val stopped = live.keys.filter { it !in wantedIds }
        val started = wanted.filter { it.id !in live }
        val changed = wanted.filter { w -> live[w.id]?.let { it != w } == true }
        stopped.forEach { live.remove(it) }
        wanted.forEach { live[it.id] = it }
        return Update(started, stopped, changed)
    }

    /** He answered [id] on the watch: it stops ringing here at once, before the phone has even heard. */
    @Synchronized fun answerLocal(id: String): Boolean {
        val had = live.remove(id) != null
        if (had) answeredHere += id
        return had
    }

    @Synchronized fun ringing(): List<RingItem> = live.values.sortedWith(compareByDescending<RingItem> { rank(it.severity) }.thenBy { it.id })
    @Synchronized fun isRinging(id: String) = id in live

    companion object { fun rank(severity: String) = when (severity) { "critical" -> 3; "urgent" -> 2; else -> 1 } }
}

/** What an approval does on the watch, chosen by him in the Gestures and rings settings. */
object ApprovalRingMode { const val OFF = "off"; const val BUZZ = "buzz"; const val FULL = "full"; val ALL = setOf(OFF, BUZZ, FULL) }

object RingPolicy {
    /**
     * The approvals that should ring now: ones he has not been told about yet, in the mode he chose. A wake always rings (that is
     * what a wake is for); an approval never rings twice for the same card, and never while he is looking at that very card.
     */
    fun approvalsToRing(pending: List<Pending>, mode: String, told: Set<Long>, viewing: Long?): List<RingItem> =
        if (mode == ApprovalRingMode.OFF) emptyList() else pending.filter { it.id !in told && it.id != viewing }.map(RingItem::approval)

    /** A wake in quiet hours with no sound still shows, and does not buzz him. */
    fun vibrates(item: RingItem): Boolean = item.sound
}

/** Vibration patterns (off, on, off, on...) in ms, one per severity, and the short one for an approval. Repeats until answered. */
object RingPattern {
    fun forItem(item: RingItem): LongArray = when {
        item.kind == RingKind.APPROVAL -> longArrayOf(0, 120, 120, 120)
        item.severity == "critical" -> longArrayOf(0, 900, 250, 900, 250, 1400, 500)
        item.severity == "heads_up" -> longArrayOf(0, 250, 400, 250)
        else -> longArrayOf(0, 600, 300, 600, 300, 900, 600)
    }
    /** True when the pattern should loop until he answers (a wake), false for a one-off buzz (an approval). */
    fun repeats(item: RingItem) = item.kind == RingKind.WAKE && item.severity != "heads_up"
}
