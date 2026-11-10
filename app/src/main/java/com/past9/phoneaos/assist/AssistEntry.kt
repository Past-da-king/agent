package com.past9.phoneaos.assist

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The one way into the assist sheet: the compact slide-up voice sheet that opens over whatever is on screen, already
 * listening. Everything that opens it goes through [open]: the side key (the system calls the assistant service, which
 * ends up in the same sheet), a wake word, a launcher shortcut, a quick settings tile, a widget.
 *
 * From inside the app:       AssistEntry.open(context, AssistEntry.WAKE_WORD)
 * By intent (this app only): sendBroadcast(Intent(AssistEntry.ACTION).setPackage(packageName).putExtra("source", "wake_word"))
 *
 * It works from a foreground service while the app is in the background, with the screen on or locked:
 *  - the app is the phone's digital assistant: the system shows our voice session, over any app and the lock screen;
 *  - else, the app is on screen or may appear on top of other apps: the sheet's own activity is started;
 *  - else: a full-screen notification, which opens the sheet at once on a locked phone and is one tap on an unlocked one.
 * [open] says which of the three it took, so a caller can tell a sheet that opened from a notification that waits.
 */
object AssistEntry {
    /** The action of the internal intent. No other app can send it: the receiver that takes it is not exported. */
    const val ACTION = "com.past9.phoneaos.action.ASSIST"
    /** String extra: what opened the sheet. One of [SOURCES]; anything else counts as [SHORTCUT]. */
    const val EXTRA_SOURCE = "source"
    const val SIDE_KEY = "side_key"
    const val WAKE_WORD = "wake_word"
    const val SHORTCUT = "shortcut"
    const val WIDGET = "widget"
    val SOURCES = setOf(SIDE_KEY, WAKE_WORD, SHORTCUT, WIDGET)

    /** How the sheet was put on screen. */
    enum class Route { SESSION, ACTIVITY, NOTIFICATION }

    private const val TAG = "AssistEntry"
    private const val CHANNEL = "assist"
    private const val NOTE_ID = 0xA551

    private val _listening = MutableStateFlow(false)
    /**
     * True while the sheet holds the microphone. Anything else that listens (a wake word) lets go of the microphone
     * while this is true and takes it back when it turns false.
     */
    val listening: StateFlow<Boolean> = _listening
    internal fun setListening(on: Boolean) { _listening.value = on }

    private val _open = MutableStateFlow(false)
    /** True while the sheet is on screen. */
    val isOpen: StateFlow<Boolean> = _open
    internal fun setOpen(on: Boolean) { _open.value = on; if (!on) _listening.value = false }

    fun source(raw: String?): String = raw?.takeIf { it in SOURCES } ?: SHORTCUT

    /** The explicit intent that opens the sheet's activity, listening. */
    fun intent(context: Context, source: String): Intent =
        Intent(ACTION).setClass(context, AssistActivity::class.java).putExtra(EXTRA_SOURCE, source(source))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)

    /** Which way in, apart from Android so it can be tested. */
    fun route(sessionReady: Boolean, appOnScreen: Boolean, mayAppearOnTop: Boolean): Route = when {
        sessionReady -> Route.SESSION
        appOnScreen || mayAppearOnTop -> Route.ACTIVITY
        else -> Route.NOTIFICATION
    }

    /** Open the assist sheet, listening. Safe from any thread and any component. */
    @JvmStatic
    fun open(context: Context, source: String): Route {
        val app = context.applicationContext
        val src = source(source)
        val service = AssistService.live
        var wanted = route(service != null && AssistRole.held(app), onScreen(), Settings.canDrawOverlays(app))
        Log.i(TAG, "open source=$src route=$wanted")
        if (wanted == Route.SESSION) {
            val shown = runCatching { service!!.showSession(Bundle().apply { putString(EXTRA_SOURCE, src) }, AssistService.showFlags(app)); true }.getOrDefault(false)
            if (shown) return Route.SESSION
            wanted = route(false, onScreen(), Settings.canDrawOverlays(app))
        }
        if (wanted == Route.ACTIVITY && runCatching { app.startActivity(intent(app, src)); true }.getOrDefault(false)) return Route.ACTIVITY
        notify(app, src)
        return Route.NOTIFICATION
    }

    /** The sheet is up: a notification that offered it has done its job. */
    internal fun clearNotification(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTE_ID) }
    }

    private fun onScreen(): Boolean = runCatching {
        val s = ActivityManager.RunningAppProcessInfo(); ActivityManager.getMyMemoryState(s)
        s.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(false)

    private fun notify(app: Context, source: String) {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Assistant", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Opens the assistant when it is called while another app is on screen"
                setSound(null, null); enableVibration(false)
            })
        }
        val pi = PendingIntent.getActivity(app, 0, intent(app, source), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val name = app.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("agentName", null)?.takeIf { it.isNotBlank() && it != "Your agent" } ?: "your agent"
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(app, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(app)
        b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Talk to $name")
            .setContentText("Tap to open the assistant")
            .setContentIntent(pi).setFullScreenIntent(pi, true).setAutoCancel(true).setOngoing(false)
        if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(20_000)
        runCatching { nm.notify(NOTE_ID, b.build()) }.onFailure { Log.w(TAG, "notification refused: ${it.message}") }
    }
}

/**
 * The internal intent, as a broadcast: `Intent(AssistEntry.ACTION).setPackage(packageName).putExtra("source", ...)`.
 * Not exported, so only this app can send it.
 */
class AssistEntryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AssistEntry.ACTION) AssistEntry.open(context, AssistEntry.source(intent.getStringExtra(AssistEntry.EXTRA_SOURCE)))
    }
}
