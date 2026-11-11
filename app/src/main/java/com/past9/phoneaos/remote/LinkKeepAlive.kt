package com.past9.phoneaos.remote

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * Link keep-alive rules (11 Nov 2026, 0.15.47).
 *
 * The cause of "the phone keeps going offline": the app's own link paused 30 s after it left the screen, whatever the
 * computer was asking of it; the always-on wake channel is `wakeOnly` and the computer does not count it as a phone it
 * can give jobs to. So an untouched phone read offline until something woke the app. These are the decisions, kept
 * pure so they are tested without a device.
 */

/** The app's own link may be let go only off screen, on mobile data (where every byte costs), with nothing being asked of the phone. */
internal fun mayPauseLink(foreground: Boolean, turnRunning: Boolean, jobsRunning: Int, metered: Boolean): Boolean =
    !foreground && !turnRunning && jobsRunning <= 0 && metered

internal enum class NetReaction { None, ConnectNow, ReplaceSocket }

/**
 * What a change of network does to the link. No socket: connect now, whatever the backoff says. A socket open on a
 * network that is not the one in use any more (Wi-Fi lost and regained, Wi-Fi to mobile data): it is a dead socket
 * that has not noticed yet, so it is replaced at once instead of waiting for a ping to fail.
 */
internal fun reactToNetwork(linkOn: Boolean, socketOpen: Boolean, sameNetworkAsSocket: Boolean): NetReaction = when {
    !linkOn -> NetReaction.None
    !socketOpen -> NetReaction.ConnectNow
    !sameNetworkAsSocket -> NetReaction.ReplaceSocket
    else -> NetReaction.None
}

/**
 * Device commands run exactly once. The computer may send a command again after a drop (it cannot tell "never got it"
 * from "got it, answer lost"), so each id is remembered: a repeat while it runs does nothing, a repeat after it ended
 * gets the stored answer again, and an answer that could not be delivered waits for the next link.
 */
internal class CommandLedger(private val max: Int = 256, private val ttlMs: Long = 3_600_000L, private val now: () -> Long = System::currentTimeMillis) {
    enum class Verdict { RUN, RUNNING, DONE }
    class Rec(val startedAt: Long) { @Volatile var done = false; @Volatile var kind = ""; @Volatile var ok = false; @Volatile var value: Any? = null; @Volatile var delivered = false }

    private val recs = LinkedHashMap<String, Rec>()

    @Synchronized fun begin(id: String): Verdict {
        prune()
        val r = recs[id]
        if (r == null) { recs[id] = Rec(now()); return Verdict.RUN }
        return if (r.done) Verdict.DONE else Verdict.RUNNING
    }

    @Synchronized fun finish(id: String, kind: String, ok: Boolean, value: Any?) {
        val r = recs.getOrPut(id) { Rec(now()) }
        r.kind = kind; r.ok = ok; r.value = value; r.done = true
    }

    @Synchronized fun delivered(id: String) { recs[id]?.delivered = true }
    @Synchronized fun get(id: String): Rec? = recs[id]
    /** Finished commands whose answer the computer has not had. */
    @Synchronized fun undelivered(): List<Pair<String, Rec>> = recs.entries.filter { it.value.done && !it.value.delivered }.map { it.key to it.value }

    private fun prune() {
        val cut = now() - ttlMs
        val it = recs.entries.iterator()
        while (it.hasNext()) { val e = it.next(); if (e.value.startedAt < cut && e.value.done && e.value.delivered) it.remove() }
        while (recs.size > max) { val k = recs.entries.firstOrNull { it.value.done && it.value.delivered }?.key ?: break; recs.remove(k) }
    }
}

/**
 * Held while the computer's jobs run on this phone: a Wi-Fi lock (so the radio does not nap between packets) and a
 * partial wake lock, released when the last job ends, and anyway after [CEILING_MS] so a stuck job cannot drain the battery.
 */
internal class JobLocks(private val context: Context) {
    private var count = 0
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ceiling = Runnable { synchronized(this) { count = 0; releaseLocks() } }

    @Synchronized fun acquire() {
        if (count++ > 0) return
        runCatching {
            wake = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "agent:device-job").apply { setReferenceCounted(false); acquire(CEILING_MS) }
        }
        runCatching {
            @Suppress("DEPRECATION")
            wifi = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "agent:device-job").apply { setReferenceCounted(false); acquire() }
        }
        handler.removeCallbacks(ceiling); handler.postDelayed(ceiling, CEILING_MS)
    }

    @Synchronized fun release() {
        if (count <= 0) return
        if (--count == 0) { handler.removeCallbacks(ceiling); releaseLocks() }
    }

    val held: Boolean @Synchronized get() = count > 0

    private fun releaseLocks() {
        runCatching { wake?.takeIf { it.isHeld }?.release() }; wake = null
        runCatching { wifi?.takeIf { it.isHeld }?.release() }; wifi = null
    }

    companion object { const val CEILING_MS = 12 * 60_000L }
}
