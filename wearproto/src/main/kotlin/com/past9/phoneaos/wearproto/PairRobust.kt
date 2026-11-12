package com.past9.phoneaos.wearproto

/**
 * Pieces that make pairing robust and observable (docs/wear.md, "Pairing: robust and observable"). All pure, so each is tested without a
 * watch: the log, the offer that survives the process, the retry schedule, the low-battery and power-saving rules.
 */

/**
 * One tag on both sides, so a single filter shows the whole handshake: `adb logcat -s AgentWatchPair` on the watch and on the phone.
 * Lines are plain facts (node ids cut to eight characters, ports, reasons). Never the code, the secret or the QR text.
 */
object PairLog {
    const val TAG = "AgentWatchPair"
    private const val KEEP = 200
    private val lines = ArrayDeque<String>()
    /** Where a line goes. The apps leave it alone (logcat); a test can read [recent]. */
    @Volatile var sink: (String) -> Unit = { runCatching { android.util.Log.i(TAG, it) } }

    fun i(message: String) {
        synchronized(lines) { lines.addLast(message); while (lines.size > KEEP) lines.removeFirst() }
        sink(message)
    }

    fun recent(): List<String> = synchronized(lines) { lines.toList() }
    fun clear() = synchronized(lines) { lines.clear() }

    /** A node id is long and not secret, but eight characters are enough to tell two apart in a log. */
    fun node(id: String?): String = if (id.isNullOrBlank()) "-" else id.take(8)
}

/** An offer and the count of wrong tries it has had, as kept on disk. */
data class StoredOffer(val offer: PairOffer, val wrong: Int)

/** Where the watch keeps its offer so a process that dies and comes back (or a service started cold by the system) still holds the same code. */
interface PairOfferStore {
    fun load(): StoredOffer?
    fun save(offer: PairOffer, wrong: Int)
    fun clear()
}

/** The phone's way of telling the watch "you are paired", as a data item the watch can read whenever it wakes (docs/wear.md). */
data class PairedNote(val watch: String, val phoneName: String, val at: Long)

object PairedWire {
    private fun obj(b: ByteArray?): org.json.JSONObject? = if (b == null) null else runCatching { org.json.JSONObject(String(b, Charsets.UTF_8)) }.getOrNull()
    fun encode(n: PairedNote): ByteArray = org.json.JSONObject().put("v", 1).put("watch", n.watch).put("name", n.phoneName.take(40)).put("at", n.at).toString().toByteArray(Charsets.UTF_8)
    fun parse(b: ByteArray?): PairedNote? {
        val o = obj(b) ?: return null
        if (!o.has("v")) return null
        val w = o.optString("watch")
        return if (w.isBlank()) null else PairedNote(w, o.optString("name"), o.optLong("at"))
    }
}

/**
 * Should the watch believe the phone's "paired" note? Only when the note names this very watch and was made after this watch last unpaired
 * (a stale note must never pair a watch he deliberately unpaired).
 */
object PairedRule {
    fun accepts(note: PairedNote?, myNode: String, unpairedAt: Long, alreadyPaired: Boolean): Boolean =
        note != null && !alreadyPaired && myNode.isNotBlank() && note.watch == myNode && note.at > unpairedAt
}

/** Retrying the Data Layer send over the 30 second window instead of once. */
object PairRetry {
    const val WINDOW_MS = 30_000L
    const val MAX_ATTEMPTS = 5
    /** After a send the Data Layer accepted: wait this long for the watch's answer before sending again. */
    const val WAIT_MS = 7_000L
    /** After a send that failed: try again sooner. */
    const val FAILED_GAP_MS = 1_500L

    data class Result(val attempts: Int, val accepted: Int, val failed: Int)

    /**
     * Calls [send] (it says whether the Data Layer took the message) until [answered] says the watch replied, the window is used up or
     * [MAX_ATTEMPTS] is reached. [onAttempt] sees every try (number, whether it was taken), for the log and the sheet.
     */
    suspend fun run(
        send: suspend (attempt: Int) -> Boolean, answered: () -> Boolean, now: () -> Long, sleep: suspend (Long) -> Unit,
        onAttempt: (Int, Boolean) -> Unit = { _, _ -> },
    ): Result {
        val start = now(); var tries = 0; var ok = 0; var bad = 0
        while (tries < MAX_ATTEMPTS && !answered() && now() - start < WINDOW_MS) {
            tries++
            val taken = send(tries)
            if (taken) ok++ else bad++
            onAttempt(tries, taken)
            val gap = if (taken) WAIT_MS else FAILED_GAP_MS
            if (now() - start + gap >= WINDOW_MS) break
            sleep(gap)
        }
        return Result(tries, ok, bad)
    }
}

/** What the sheet on the phone says it is doing now. */
enum class PairStep { BLUETOOTH, WIFI }

/** The low-battery line on the pairing screen. */
object PairBattery {
    const val LOW_PERCENT = 10
    /** [percent] is -1 when unknown. A watch on a charger is still slow at a few percent, so charging does not hide the line. */
    fun low(percent: Int): Boolean = percent in 0 until LOW_PERCENT
}

/** What may stop the app starting or running well, from facts the watch can read. */
enum class LaunchLimit { NONE, POWER_SAVE, VERY_LOW_BATTERY }

object LaunchHealth {
    const val VERY_LOW_PERCENT = 5
    fun of(percent: Int, powerSave: Boolean): LaunchLimit = when {
        powerSave -> LaunchLimit.POWER_SAVE
        percent in 0 until VERY_LOW_PERCENT -> LaunchLimit.VERY_LOW_BATTERY
        else -> LaunchLimit.NONE
    }
}
