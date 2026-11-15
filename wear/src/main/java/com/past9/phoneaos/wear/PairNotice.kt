package com.past9.phoneaos.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.past9.phoneaos.wearproto.LaunchLimit
import com.past9.phoneaos.wearproto.PairLog

/**
 * The pairing state, one tap away: while the watch is waiting for a phone there is an ongoing notification that opens the code screen, so
 * backing out of the app does not lose it, and it says when something (power saving, a very low battery) may slow pairing. It goes when the
 * watch pairs, the code runs out or the watch is unpaired and shows a new one. It holds no code and no secret: only the words.
 * (A Wear OS "ongoing activity" would need one more library; an ongoing notification gives the same one-tap way back.)
 */
object PairNotice {
    private const val CHANNEL = "pairing"
    private const val ID = 4101
    private const val ID_DONE = 4102

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)

    private fun ensureChannel(c: Context) {
        val m = nm(c) ?: return
        if (m.getNotificationChannel(CHANNEL) == null) m.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.pair_channel), NotificationManager.IMPORTANCE_LOW))
    }

    private fun open(c: Context) = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Show it for the offer that is on screen now (timed out with the offer). Does nothing if notifications are off or the watch is paired. */
    fun show(c: Context) {
        val ctx = c.applicationContext
        val offer = WatchPairingState.currentOffer(ctx)
        if (offer == null || WatchPairingState.paired.value) { clear(ctx); return }
        val m = nm(ctx) ?: return
        if (!m.areNotificationsEnabled()) { PairLog.i("watch: pairing notice skipped, notifications are off"); return }
        runCatching {
            ensureChannel(ctx)
            val warn = WatchHealth.line(ctx, WatchHealth.limit(ctx))
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_agent).setContentTitle(ctx.getString(R.string.pair_notice_title)).setContentText(warn ?: ctx.getString(R.string.pair_notice_text))
                .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_STATUS)
                .setTimeoutAfter((offer.expiresAt - System.currentTimeMillis()).coerceAtLeast(1_000)).setContentIntent(open(ctx)).build()
            m.notify(ID, n)
            PairLog.i("watch: pairing notice shown limit=${if (warn == null) LaunchLimit.NONE else "warn"}")
        }.onFailure { PairLog.i("watch: pairing notice failed: ${it.message}") }
    }

    fun clear(c: Context) { runCatching { nm(c.applicationContext)?.cancel(ID) } }

    /** The watch paired while it was not in front: one quiet line that opens the app. */
    fun paired(c: Context, phoneName: String) {
        val ctx = c.applicationContext
        val m = nm(ctx) ?: return
        if (!m.areNotificationsEnabled()) return
        runCatching {
            ensureChannel(ctx)
            m.notify(ID_DONE, NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_agent)
                .setContentTitle(ctx.getString(R.string.pair_paired_with, phoneName.ifBlank { ctx.getString(R.string.pair_a_phone) }))
                .setAutoCancel(true).setSilent(true).setContentIntent(open(ctx)).setTimeoutAfter(60_000).build())
        }
    }
}
