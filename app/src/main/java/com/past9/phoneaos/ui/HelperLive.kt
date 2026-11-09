package com.past9.phoneaos.ui

import com.past9.phoneaos.data.ChatItem
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * When a helper row is genuinely at work (9 Nov 2026).
 *
 * His Profile page said "12 working for you right now" and listed helpers that had died days before ("Rebuild:
 * payroll 260 h", "SEI broken links, OneDrive 174 h"): every one of them was started on his Windows PC, which then
 * went away mid-run. The phone mirrors the chat rows of the computer it talks to, and a mirrored row counted as
 * working for ever on meta.state alone, because nothing on the phone ever corrected it.
 *
 * One rule now, used by every count, every list, the helper card and the sheet, and by the reaper that writes the
 * phone's own copy back as ended:
 *  - a row works only while its state is "working" and it shows a sign of life (it moved, or logged a step) inside
 *    [QUIET_MS], which is the desktop's helperQuietMinutes (10 by default) times three;
 *  - a queued row older than [QUEUED_MS] has lost its place in the line, whatever it says;
 *  - and the device that owns it must not be known to be gone: the phone remembers the devices it hears from (a
 *    brain that answers an RPC or a health check, a device in devices.list with its lastSeen). A device the phone
 *    has no word on at all does not hide a row: the heartbeat decides, so a helper on a computer this phone never
 *    talks to directly still shows while it is really moving.
 */
object HelperLive {
    /** The desktop's helperQuietMinutes (10 by default) times three: a row that has not moved for this long is not at work. */
    const val QUIET_MS = 30 * 60_000L
    /** Never shorter than this, whatever the setting: one slow tool call (a build, a render) is not a dead helper. */
    const val MIN_QUIET_MS = 15 * 60_000L
    /** A queued row waiting longer than this lost its place: the computer that held the line went away with it. */
    const val QUEUED_MS = 60 * 60_000L
    /** How recently the phone must have heard from the device that owns a row for that device to count as live. */
    const val DEVICE_MS = 10 * 60_000L
    /** How long the computer's card list (which helpers are working there right now) stays good enough to go by. */
    const val CARDS_MS = 15 * 60_000L

    /** The clock the rule reads, so a test can set the moment it means. */
    @Volatile
    var clock: () -> Long = { System.currentTimeMillis() }

    fun now(): Long = clock()

    /** What the phone has heard from each device: its id, and its name (lowercased is enough: the desktop writes both). */
    private val heard = ConcurrentHashMap<String, Long>()

    /** The phone heard from this device (a brain answered, a device was seen on the link) at [at]. */
    fun heardFrom(device: String, name: String = "", at: Long = now()) {
        if (device.isNotBlank()) heard[device] = at
        if (name.isNotBlank()) heard["name:" + name.trim().lowercase()] = at
    }

    /** Forget every device and the card list (a test, or a link that was taken out). */
    fun forgetDevices() {
        heard.clear()
        liveRows.clear()
        cardsAt = 0L
    }

    /**
     * True when the phone heard from [device] inside [DEVICE_MS]; false when what it heard is older than that (the
     * device is gone as far as the phone can tell); null when it has no word on the device at all.
     */
    fun deviceLive(device: String, at: Long = now()): Boolean? {
        if (device.isBlank()) return null
        val t = heard[device] ?: heard["name:" + device.trim().lowercase()] ?: return null
        return at - t <= DEVICE_MS
    }

    // ---- the computer's own list of what is working there (helpers.cards) ----

    /** The rows the computer's newest card list names, and when that list arrived. */
    private val liveRows = ConcurrentHashMap<String, Long>()

    @Volatile private var cardsAt = 0L

    /**
     * The computer published its cards: every helper row id it says is working there right now. That list is the
     * truth about what is running, and a row in the phone's own copy that it does not name is not working (9 Nov 2026:
     * nine of the twelve ghosts lived in a project chat, whose helper rows this phone is never sent again, so nothing
     * else could ever correct them).
     */
    fun cardsSeen(rows: Collection<String>, at: Long = now()) {
        liveRows.clear()
        rows.forEach { if (it.isNotBlank()) liveRows[it] = at }
        cardsAt = at
    }

    /** No card list to go by (not linked, or a computer too old to publish one): the heartbeat rule decides. */
    fun cardsUnknown() {
        liveRows.clear()
        cardsAt = 0L
    }

    /** Whether the phone has a card list recent enough to go by. */
    fun cardsKnown(at: Long = now()): Boolean = cardsAt > 0L && at - cardsAt <= CARDS_MS

    /**
     * Whether the computer's live list names this row: true (it is working there), false (it is not), null when there
     * is no list to go by, when the row was made on this phone, or when it started after the list was read (so the
     * list cannot know about it yet).
     */
    fun listed(h: HelperView, at: Long = now()): Boolean? {
        if (!cardsKnown(at)) return null
        if (h.rid.isBlank()) return null
        if (h.startedAt > cardsAt) return null
        return liveRows.containsKey(h.rid)
    }

    /** The newest sign of life a row carries: when it was written, when it started, or the last step it logged. */
    fun lastMove(h: HelperView): Long {
        var at = h.item.createdAt
        val m = runCatching { JSONObject(h.item.meta) }.getOrDefault(JSONObject())
        for (key in listOf("startedAt", "updatedAt", "beat", "lastStepAt", "lastMove", "queuedAt")) {
            val v = m.optLong(key, 0L)
            if (v > at) at = v
        }
        val lastStep = h.steps.maxOfOrNull { it.createdAt } ?: 0L
        if (lastStep > at) at = lastStep
        return at
    }

    /**
     * When a queued row started waiting. A queued card has its "waiting its turn" line rewritten now and then, so its
     * last move stays fresh while it never starts: the waiting clock runs from here, not from the last move.
     */
    fun queuedSince(h: HelperView): Long {
        val m = runCatching { JSONObject(h.item.meta) }.getOrDefault(JSONObject())
        return m.optLong("queuedAt", 0L).takeIf { it > 0 } ?: h.startedAt
    }

    /** Is this row genuinely at work at [at]? The one rule (see the class note). */
    fun isLive(h: HelperView, at: Long = now()): Boolean {
        if (h.state != "working") return false
        if (h.endedAt > 0) return false
        if (h.queued && at - queuedSince(h) > QUEUED_MS) return false
        if (at - lastMove(h) > maxOf(QUIET_MS, MIN_QUIET_MS)) return false
        if (deviceLive(h.runOn, at) == false) return false
        // The computer's own list of what is working there: a row it does not name is not running.
        if (listed(h, at) == false) return false
        return true
    }

    /** Every row that is really at work, newest first as the chat has them. */
    fun running(rows: List<HelperView>, at: Long = now()): List<HelperView> = rows.filter { isLive(it, at) }

    /** How many are at work: the same rule as [running], so a count can never disagree with the list under it. */
    fun count(rows: List<HelperView>, at: Long = now()): Int = rows.count { isLive(it, at) }

    /** The state the screens draw: a working row that is not live is drawn as lost, with the reason. */
    fun shownState(h: HelperView, at: Long = now()): String = when {
        h.state != "working" -> h.state
        isLive(h, at) -> "working"
        else -> "lost"
    }

    /** Why a row that says working is not at work, in words he can act on. */
    fun lostBecause(h: HelperView, at: Long = now()): String {
        val said = runCatching { JSONObject(h.item.meta).optString("endReason") }.getOrDefault("")
        if (said.isNotBlank()) return said
        val beat = lastMove(h)
        val device = h.runsOn.ifBlank { h.runOn }
        return when {
            h.queued && at - queuedSince(h) > QUEUED_MS -> "Lost its place in the line ${spanLabel(at - queuedSince(h))} ago"
            listed(h, at) == false -> "Lost: not on the list of what is working there, ${spanLabel(at - beat)} after it last moved"
            deviceLive(h.runOn, at) == false && device.isNotBlank() -> "Lost: no word from $device for ${spanLabel(at - beat)}"
            else -> "Lost: nothing from it for ${spanLabel(at - beat)}"
        }
    }

    /**
     * The phone's own copy of a row, brought up to date with what the computer that owns it says now (that computer's
     * chat item for the same row). Null: nothing to change.
     *
     * This is the other half of the 9 Nov 2026 bug: the computer had written "done" and "stopped" days before, and the
     * phone's mirrored row was still "working" because nothing ever sent it the end (a linked device is only sent the
     * recent rows, the helpers working now and the open cards, so an old helper that ended is never in a list again).
     * A computer that no longer has the row at all ([deviceMeta] null) means the run is lost here.
     */
    fun reconciled(h: HelperView, deviceMeta: JSONObject?, at: Long = now()): ChatItem? {
        val mine = runCatching { JSONObject(h.item.meta) }.getOrDefault(JSONObject())
        if (deviceMeta == null) {
            if (h.state != "working") return null
            val m = JSONObject(mine.toString())
            m.put("state", "lost").put("endReason", "Lost: the computer that ran it no longer has it")
                .put("endedAt", lastMove(h).coerceAtLeast(1L).coerceAtMost(at)).put("reaped", at)
            m.remove("now")
            return h.item.copy(meta = m.toString())
        }
        val state = deviceMeta.optString("state").ifBlank { "working" }
        val ended = deviceMeta.optLong("endedAt", 0L)
        // It says working there: whether that is true is the rule above, not this.
        if (state == "working" && ended <= 0) return null
        // Already the same: no write, so a live computer's own history is never rewritten over and over.
        if (mine.optString("state") == state && ended > 0 && mine.optLong("endedAt", 0L) == ended) return null
        val m = JSONObject(mine.toString())
        m.put("state", state)
        if (ended > 0) m.put("endedAt", ended) else m.remove("endedAt")
        deviceMeta.optString("result").takeIf { it.isNotBlank() }?.let { m.put("result", it) }
        deviceMeta.optString("endReason").takeIf { it.isNotBlank() }?.let { m.put("endReason", it) }
        m.remove("now")
        return h.item.copy(meta = m.toString())
    }

    /**
     * A helper row he swiped away, as the phone's own copy keeps it: stopped, with when and why, so no count and no
     * list shows it again. Null when the row is not a helper or has already ended. A live owner that later sends a
     * fresher row replaces this, like any other write-back.
     */
    fun dismissed(row: ChatItem, at: Long = now()): ChatItem? {
        if (row.kind != "helper") return null
        val m = runCatching { JSONObject(row.meta) }.getOrDefault(JSONObject())
        val state = m.optString("state")
        if (state != "working" && state != "interrupted" && state.isNotBlank()) return null
        m.put("state", "stopped").put("endReason", "Dismissed on this phone").put("endedAt", at).put("dismissed", at)
        m.remove("now")
        return row.copy(meta = m.toString())
    }

    /** A row the phone writes back as ended in its own copy. */
    data class Reap(val row: ChatItem, val meta: String)

    /**
     * The rows that must be written back as ended: still saying working, but not live any more. The phone writes its
     * own copy only; a live owner that later sends a fresher row replaces this (RemoteSession.putChat), so
     * replication is never fought. [at] is the moment of the check.
     */
    fun reapPlan(rows: List<HelperView>, at: Long = now()): List<Reap> = rows
        .filter { it.state == "working" && it.endedAt <= 0 && !isLive(it, at) }
        .map { h ->
            val m = runCatching { JSONObject(h.item.meta) }.getOrDefault(JSONObject())
            val why = lostBecause(h, at)
            m.put("state", if (h.queued) "stopped" else "lost")
                .put("endReason", why)
                .put("endedAt", lastMove(h).coerceAtLeast(1L).coerceAtMost(at))
                .put("reaped", at)
            m.remove("now")
            Reap(h.item, m.toString())
        }
}
