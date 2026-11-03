package com.past9.phoneaos.wakeup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.past9.phoneaos.remote.RemoteHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * The phone's wake-ups: the computer's latest snapshot (kept on disk, so a reboot or a dropped link still rings what
 * was booked), what rings here now, the alarm for the next change, and his answers (sent to the computer, kept and
 * retried until it has them). Logcat tag WakeUp.
 */
object WakeHub {
    const val TAG = "WakeUp"
    private const val PREFS = "wakeups"
    private const val K_SNAP = "snapshot"
    private const val K_ANSWERS = "answers"
    private const val K_ANSWERED = "answered"
    const val ACTION_ALARM = "com.past9.phoneaos.wakeup.ALARM"

    private val _ringing = MutableStateFlow<List<Ringing>>(emptyList())
    /** What rings on this phone now (the screen and the service follow it). */
    val ringing: StateFlow<List<Ringing>> = _ringing
    private val _snapshot = MutableStateFlow(WakeSnapshot.EMPTY)
    val snapshot: StateFlow<WakeSnapshot> = _snapshot
    @Volatile private var loaded = false

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        _snapshot.value = WakeSnapshot.fromLocal(prefs(context).getString(K_SNAP, null))
    }

    /** This phone's device id at the computer ('' when not linked). */
    fun selfId(): String = RemoteHub.link.value?.deviceId.orEmpty()

    /** A fresh snapshot from the computer. */
    fun apply(context: Context, snap: WakeSnapshot) {
        load(context)
        _snapshot.value = snap
        prefs(context).edit().putString(K_SNAP, snap.toJson().toString()).apply()
        // An answer the computer has confirmed (the wake is no longer live there) is forgotten.
        val live = snap.wakes.map { it.id }.toSet()
        val answered = answeredIds(context).filter { it in live }.toSet()
        prefs(context).edit().putString(K_ANSWERED, JSONArray(answered.toList()).toString()).apply()
        Log.i(TAG, "snapshot: ${snap.wakes.size} live from ${snap.from}")
        evaluate(context)
    }

    /** Decide what rings now, start or stop the ring, and arm the alarm for the next change. */
    fun evaluate(context: Context) {
        load(context)
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val quiet = WakeRules.inQuiet(_snapshot.value.quietHours, cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE))
        val answered = answeredIds(context)
        val list = WakeRules.ringing(_snapshot.value, selfId(), now, quiet, answered)
        val before = _ringing.value.map { it.wake.id to it.sound }
        _ringing.value = list
        if (list.map { it.wake.id to it.sound } != before) Log.i(TAG, "ringing here: ${list.map { it.wake.id.take(8) + ":" + it.wake.severity }}")
        if (list.isNotEmpty()) {
            WakeUpService.ring(context)
            // Tell the computer this phone has it (the 30 s delivery check).
            for (r in list) if (selfId() !in r.wake.seen) WakeChannel.seen(context, r.wake.id)
        } else WakeUpService.stop(context)
        arm(context, WakeRules.nextChange(_snapshot.value, now, answered))
    }

    // ---- his answers ----------------------------------------------------------------------------------------------

    fun ack(context: Context, id: String) = answer(context, id, "ack", 0)

    fun snooze(context: Context, id: String) = answer(context, id, "snooze", _snapshot.value.snoozeMinutes)

    private fun answer(context: Context, id: String, what: String, minutes: Int) {
        load(context)
        Log.i(TAG, "answered on the phone: $what ${id.take(8)}")
        val answered = answeredIds(context) + id
        prefs(context).edit().putString(K_ANSWERED, JSONArray(answered.toList()).toString()).apply()
        if (what == "snooze") {
            // Ring again here at the end of the snooze even if the computer cannot be reached meanwhile.
            val w = _snapshot.value.wakes.firstOrNull { it.id == id }
            if (w != null) {
                val again = w.copy(ringAt = System.currentTimeMillis() + minutes * 60_000L)
                val snap = _snapshot.value.copy(wakes = _snapshot.value.wakes.map { if (it.id == id) again else it })
                _snapshot.value = snap
                prefs(context).edit().putString(K_SNAP, snap.toJson().toString()).putString(K_ANSWERED, JSONArray((answered - id).toList()).toString()).apply()
            }
        }
        queue(context, JSONObject().put("what", what).put("id", id).put("minutes", minutes).put("at", System.currentTimeMillis()))
        evaluate(context)
        WakeChannel.flush(context)
    }

    private fun answeredIds(context: Context): Set<String> = runCatching {
        val a = JSONArray(prefs(context).getString(K_ANSWERED, "[]"))
        (0 until a.length()).map { a.getString(it) }.toSet()
    }.getOrDefault(emptySet())

    private fun queue(context: Context, o: JSONObject) {
        val a = runCatching { JSONArray(prefs(context).getString(K_ANSWERS, "[]")) }.getOrDefault(JSONArray())
        a.put(o)
        prefs(context).edit().putString(K_ANSWERS, a.toString()).apply()
    }

    /** Answers not yet confirmed by the computer, oldest first. */
    fun pendingAnswers(context: Context): List<JSONObject> = runCatching {
        val a = JSONArray(prefs(context).getString(K_ANSWERS, "[]"))
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }.getOrDefault(emptyList())

    fun answerDelivered(context: Context, o: JSONObject) {
        val left = pendingAnswers(context).filterNot { it.optString("id") == o.optString("id") && it.optString("what") == o.optString("what") && it.optLong("at") == o.optLong("at") }
        prefs(context).edit().putString(K_ANSWERS, JSONArray(left).toString()).apply()
    }

    // ---- the alarm for the next change (Doze-proof) ---------------------------------------------------------------

    private fun arm(context: Context, at: Long?) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(context, 7311, Intent(context, WakeAlarmReceiver::class.java).setAction(ACTION_ALARM).setData(Uri.parse("agent://wakeup/next")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (at == null) { runCatching { am.cancel(pi) }; return }
        val show = PendingIntent.getActivity(context, 7312, Intent(context, WakeUpActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            // An alarm clock: fires on time in deep Doze, locked, app swiped away.
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }.onFailure { runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) } }
    }
}

/** The next change is due (a booked wake, an escalation): decide again. Also after a reboot or an update. */
class WakeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(WakeHub.TAG, "alarm: ${intent.action}")
        runCatching { WakeHub.evaluate(context) }
        runCatching { WakeChannel.ensure(context) }
    }
}
