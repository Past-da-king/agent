package com.past9.phoneaos.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.wear.compose.material3.AppScaffold
import com.past9.phoneaos.wear.ui.AgentWatchTheme
import com.past9.phoneaos.wear.ui.RingScreen
import com.past9.phoneaos.wearproto.ApprovalRingMode
import com.past9.phoneaos.wearproto.RingAnswer
import com.past9.phoneaos.wearproto.RingBoard
import com.past9.phoneaos.wearproto.RingItem
import com.past9.phoneaos.wearproto.RingKind
import com.past9.phoneaos.wearproto.RingPattern
import com.past9.phoneaos.wearproto.RingPaths
import com.past9.phoneaos.wearproto.RingPolicy
import com.past9.phoneaos.wearproto.RingWire
import com.past9.phoneaos.wearproto.WatchState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * The agent rings the watch (docs/wear.md, "Ring"). The phone decides what rings and hears every answer; the watch follows its list
 * ([RingBoard], tested apart from Android): what starts gets a notification that carries a full-screen intent and a vibration the
 * system repeats until the notification is cancelled (so no service has to stay alive to keep it going), and what leaves the list
 * (answered on the phone, on the computer, or here) is cancelled at once. An answer here goes to the phone's own wake-up path.
 */
object WatchRing {
    private const val TAG = "WatchRing"
    private const val PREFS = "watch_ring"
    private val board = RingBoard()
    @Volatile private var wakes: List<RingItem> = emptyList()
    /** The approval card he is looking at right now (set by the screen): it does not ring again. */
    @Volatile var viewingCard: Long? = null

    private val _ringing = MutableStateFlow<List<RingItem>>(emptyList())
    val ringing: StateFlow<List<RingItem>> = _ringing

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun approvalMode(c: Context): String = prefs(c).getString("approval_mode", ApprovalRingMode.FULL)?.takeIf { it in ApprovalRingMode.ALL } ?: ApprovalRingMode.FULL
    fun setApprovalMode(c: Context, mode: String) { if (mode in ApprovalRingMode.ALL) { prefs(c).edit().putString("approval_mode", mode).apply(); recompute(c) } }

    private fun rung(c: Context): MutableSet<Long> = runCatching {
        val a = JSONArray(prefs(c).getString("rung", "[]")); (0 until a.length()).map { a.getLong(it) }.toMutableSet()
    }.getOrDefault(mutableSetOf())
    private fun saveRung(c: Context, s: Set<Long>) { prefs(c).edit().putString("rung", JSONArray(s.toList()).toString()).apply() }

    /** The phone's list of wakes that ring now (a data item, or a read of the last one). */
    fun onRing(c: Context, items: List<RingItem>) { wakes = items; recompute(c) }

    /** The picture changed: approvals that wait are the other half of what rings. */
    fun onState(c: Context, s: WatchState) = recompute(c, s)

    @Synchronized private fun recompute(c: Context, state: WatchState? = WatchStore.cached(c)) {
        val app = c.applicationContext
        val pending = state?.pending.orEmpty()
        val mode = approvalMode(app)
        val told = rung(app)
        // Approvals that came since: told once, never twice, and not while he is looking at that card.
        val fresh = RingPolicy.approvalsToRing(pending, mode, told, viewingCard)
        told += fresh.mapNotNull { it.cardId }
        // A card that is no longer waiting is forgotten, so the list does not grow for ever.
        if (state != null) told.retainAll(pending.map { it.id }.toSet())
        saveRung(app, told)
        val approvals = if (mode == ApprovalRingMode.OFF) emptyList() else pending.filter { it.id in told }.map(RingItem::approval)
        val u = board.apply(wakes, approvals)
        ensureChannels(app)
        u.started.forEach { post(app, it, mode) }
        u.changed.forEach { post(app, it, mode, quiet = true) }
        u.stopped.forEach { cancel(app, it) }
        _ringing.value = board.ringing()
    }

    /** He answered here (a button, a gesture): it stops now, and the phone, which stops it everywhere, is told. */
    fun answer(c: Context, id: String, what: String) {
        val app = c.applicationContext
        val item = board.ringing().firstOrNull { it.id == id }
        board.answerLocal(id)
        cancel(app, id)
        _ringing.value = board.ringing()
        if (item?.kind == RingKind.WAKE) {
            val w = app as WatchApp
            w.controller.scope.launch { if (!w.controller.link.send(RingPaths.ANSWER, RingWire.answer(id, what))) Log.w(TAG, "the phone was not reachable for the answer") }
        }
    }

    /** The gesture or key that dismisses whatever rings: a wake is snoozed (it comes back), an approval is put aside. */
    fun dismissTop(c: Context): Boolean {
        val top = board.ringing().firstOrNull() ?: return false
        answer(c, top.id, RingAnswer.SNOOZE)
        return true
    }

    fun isRinging() = board.ringing().isNotEmpty()

    // ---- notifications ----------------------------------------------------------------------------------------------

    private fun channelFor(item: RingItem, quiet: Boolean): String = when {
        quiet || !item.sound -> CH_SILENT
        item.kind == RingKind.APPROVAL -> CH_APPROVAL
        item.severity == "critical" -> CH_CRITICAL
        item.severity == "heads_up" -> CH_HEADS_UP
        else -> CH_URGENT
    }

    private fun ensureChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        fun make(id: String, name: String, importance: Int, pattern: LongArray?, bypass: Boolean) {
            if (nm.getNotificationChannel(id) != null) return
            nm.createNotificationChannel(NotificationChannel(id, name, importance).apply {
                setSound(null, null)
                if (pattern != null) { enableVibration(true); vibrationPattern = pattern } else enableVibration(false)
                if (bypass) setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
        make(CH_CRITICAL, c.getString(R.string.ring_ch_critical), NotificationManager.IMPORTANCE_HIGH, RingPattern.forItem(RingItem("", RingKind.WAKE, "critical", "", "")), true)
        make(CH_URGENT, c.getString(R.string.ring_ch_urgent), NotificationManager.IMPORTANCE_HIGH, RingPattern.forItem(RingItem("", RingKind.WAKE, "urgent", "", "")), true)
        make(CH_HEADS_UP, c.getString(R.string.ring_ch_heads_up), NotificationManager.IMPORTANCE_HIGH, RingPattern.forItem(RingItem("", RingKind.WAKE, "heads_up", "", "")), false)
        make(CH_APPROVAL, c.getString(R.string.ring_ch_approval), NotificationManager.IMPORTANCE_HIGH, RingPattern.forItem(RingItem("card:0", RingKind.APPROVAL, "urgent", "", "")), false)
        make(CH_SILENT, c.getString(R.string.ring_ch_silent), NotificationManager.IMPORTANCE_DEFAULT, null, false)
    }

    private fun post(c: Context, item: RingItem, approvalMode: String, quiet: Boolean = false) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val show = PendingIntent.getActivity(c, item.id.hashCode(), Intent(c, RingActivity::class.java).putExtra("id", item.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val fullScreen = item.kind == RingKind.WAKE || approvalMode == ApprovalRingMode.FULL
        val b = Notification.Builder(c, channelFor(item, quiet)).setSmallIcon(R.drawable.ic_agent).setContentTitle(item.title).setContentText(item.whatToDo)
            .setCategory(if (item.kind == RingKind.WAKE) Notification.CATEGORY_ALARM else Notification.CATEGORY_MESSAGE)
            .setOngoing(item.kind == RingKind.WAKE).setAutoCancel(false).setVisibility(Notification.VISIBILITY_PUBLIC).setContentIntent(show)
            .setOnlyAlertOnce(quiet)
        if (fullScreen) b.setFullScreenIntent(show, true)
        if (item.kind == RingKind.WAKE) {
            b.addAction(Notification.Action.Builder(null, c.getString(R.string.ring_on_it), action(c, RingAnswer.ACK, item.id)).build())
            b.addAction(Notification.Action.Builder(null, c.getString(R.string.ring_snooze), action(c, RingAnswer.SNOOZE, item.id)).build())
        }
        val n = b.build()
        // The system repeats the vibration of an alarm until the notification goes: no service has to stay alive to keep it ringing.
        if (RingPattern.repeats(item) && !quiet && item.sound) n.flags = n.flags or Notification.FLAG_INSISTENT
        runCatching { nm.notify(item.id.hashCode(), n) }.onFailure { Log.w(TAG, "notify: ${it.message}") }
    }

    private fun action(c: Context, what: String, id: String): PendingIntent =
        PendingIntent.getBroadcast(c, (what + id).hashCode(), Intent(c, RingActionReceiver::class.java).setAction("ring.$what").putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun cancel(c: Context, id: String) { runCatching { c.getSystemService(NotificationManager::class.java).cancel(id.hashCode()) } }

    private const val CH_CRITICAL = "ring2_critical"
    private const val CH_URGENT = "ring2_urgent"
    private const val CH_HEADS_UP = "ring2_heads_up"
    private const val CH_APPROVAL = "ring2_approval"
    private const val CH_SILENT = "ring2_silent"
}

/** I'm on it / Snooze from the notification itself. */
class RingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        WatchRing.answer(context, id, if (intent.action == "ring.${RingAnswer.SNOOZE}") RingAnswer.SNOOZE else RingAnswer.ACK)
    }
}

/** The ring's full screen: shows over the lock screen and turns the screen on; closes itself when nothing rings any more. */
class RingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(true)
        val id = intent.getStringExtra("id").orEmpty()
        setContent {
            AgentWatchTheme {
                AppScaffold {
                    val list by WatchRing.ringing.collectAsState()
                    val item = list.firstOrNull { it.id == id } ?: list.firstOrNull()
                    LaunchedEffect(item == null) { if (item == null) finish() }
                    // Back, and the wrist turn that is the system's back, silence the ring: a wake is snoozed (it comes back), an approval is put aside.
                    androidx.activity.compose.BackHandler { if (item != null) WatchRing.answer(this@RingActivity, item.id, RingAnswer.SNOOZE); finish() }
                    if (item != null) RingScreen(
                        item,
                        onAck = { WatchRing.answer(this@RingActivity, item.id, RingAnswer.ACK); finish() },
                        onSnooze = { WatchRing.answer(this@RingActivity, item.id, RingAnswer.SNOOZE); finish() },
                        onReview = { startActivity(Intent(this@RingActivity, MainActivity::class.java).putExtra("go", "approvals").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish() },
                    )
                }
            }
        }
    }
}
