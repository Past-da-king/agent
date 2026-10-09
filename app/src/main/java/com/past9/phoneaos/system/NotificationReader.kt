package com.past9.phoneaos.system

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.past9.phoneaos.App
import com.past9.phoneaos.data.NotificationRow
import com.past9.phoneaos.triggers.Routines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Reads notifications ONLY from the apps the user switched on, keeps a week of them on the
 * phone, and fires any "when a notification from X mentions Y" routine.
 */
class NotificationReader : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        // Anything posted while the listener was unbound (first grant, a reinstall, the system killing it) is
        // still in the shade: pick it up, but never fire routines for it.
        runCatching { activeNotifications?.forEach { handle(it, live = false) } }
    }

    override fun onListenerDisconnected() {
        runCatching { requestRebind(ComponentName(this, NotificationReader::class.java)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn, live = true)

    private fun handle(sbn: StatusBarNotification, live: Boolean) {
        val g = App.graph(this)
        if (sbn.packageName == packageName || sbn.packageName !in g.settings.state.value.notifApps) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        scope.launch {
            val dao = g.db.notifications()
            // Apps re-post the same notification as it updates; keep one copy per 10 minutes.
            if (dao.dupes(sbn.packageName, title, text, sbn.postTime - 600_000) > 0) return@launch
            dao.insert(NotificationRow(pkg = sbn.packageName, app = app, title = title, text = text, postedAt = sbn.postTime))
            dao.prune(System.currentTimeMillis() - 7 * 86_400_000L)
            if (!live) return@launch
            g.db.triggers().list().filter { it.enabled && it.kind == "notification" }.forEach { t ->
                val (pkg, word) = t.spec.split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                if ((pkg == sbn.packageName || app.equals(pkg, true)) && (word.isBlank() || title.contains(word, true) || text.contains(word, true)))
                    Routines.runNow(this@NotificationReader, t.id, "A notification just arrived from $app: \"$title: ${text.take(400)}\". ")
            }
        }
    }
}
