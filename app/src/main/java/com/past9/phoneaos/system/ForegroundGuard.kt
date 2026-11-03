package com.past9.phoneaos.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Every foreground service in the app starts and stops through here.
 *
 * Android kills the whole app (RemoteServiceException$ForegroundServiceDidNotStartInTimeException, "startForegroundService()
 * did not then call Service.startForeground()") when a service started with startForegroundService is stopped, or ends,
 * before it has called startForeground. On 22 Oct 2026 that crashed the app twice when a call was refused by a computer
 * still on 0.2.47: CallService was started, the token request failed, and stopService arrived before startForeground.
 * The same race was open for WorkService (a quick chat turn: workStarted then workFinished) and none of the services
 * had a way through if building their notification threw.
 *
 * The rules, for every service:
 * - [start] records that a start is pending, then calls startForegroundService.
 * - The service calls [enter] first thing in onCreate. It always reaches startForeground: the proper notification and
 *   type, else no type, else a plain notification. Only then may the service stop itself.
 * - [stop] before the service is in the foreground does not call stopService (that is the crash); it asks the service
 *   to stop as soon as it is.
 * - The service calls [left] in onDestroy.
 */
object ForegroundGuard {
    private const val TAG = "ForegroundGuard"
    private const val PLAIN_CHANNEL = "background"
    private val up: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pending: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val stopAsked: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Start [cls] as a foreground service. False when Android refused the start (nothing is left pending then). */
    fun start(context: Context, cls: Class<out Service>, intent: Intent = Intent(context, cls)): Boolean {
        val k = cls.name
        synchronized(this) {
            stopAsked.remove(k)
            if (!up.contains(k)) pending.add(k)
        }
        return try {
            context.startForegroundService(intent); true
        } catch (e: Exception) {
            synchronized(this) { pending.remove(k) }
            Log.w(TAG, "could not start ${cls.simpleName}: ${e.message}"); false
        }
    }

    /** Stop [cls]. Safe at any moment, including before the service has reached the foreground. */
    fun stop(context: Context, cls: Class<out Service>) {
        val k = cls.name
        synchronized(this) {
            if (pending.contains(k) && !up.contains(k)) { stopAsked.add(k); return }
        }
        runCatching { context.stopService(Intent(context, cls)) }
    }

    /**
     * First thing in the service's onCreate: put it in the foreground, whatever it takes. [type] is the
     * foregroundServiceType for API 29+, or 0 for none. True when it is in the foreground and staying.
     */
    fun enter(service: Service, id: Int, type: Int, build: () -> Notification): Boolean {
        val k = service.javaClass.name
        val ok = attempt(service, "proper") { val n = build(); if (Build.VERSION.SDK_INT >= 29 && type != 0) service.startForeground(id, n, type) else service.startForeground(id, n) }
            || attempt(service, "no type") { service.startForeground(id, build()) }
            || attempt(service, "plain") { service.startForeground(id, plain(service)) }
        val stopNow = synchronized(this) {
            pending.remove(k)
            val asked = stopAsked.remove(k)
            // A service on its way out is not "up": a later stop() goes straight to stopService, which is safe now.
            if (ok && !asked) up.add(k)
            asked
        }
        if (!ok || stopNow) { service.stopSelf(); return false }
        return true
    }

    /** In the service's onDestroy. */
    fun left(service: Service) {
        val k = service.javaClass.name
        synchronized(this) { up.remove(k); pending.remove(k); stopAsked.remove(k) }
    }

    /** For tests and the service itself: in the foreground right now. */
    fun isUp(cls: Class<out Service>): Boolean = up.contains(cls.name)

    private fun attempt(service: Service, what: String, block: () -> Unit): Boolean = try {
        block(); true
    } catch (e: Exception) {
        Log.w(TAG, "${service.javaClass.simpleName}: startForeground ($what) failed: ${e.message}"); false
    }

    private fun plain(context: Context): Notification {
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(NotificationChannel(PLAIN_CHANNEL, "Working in the background", NotificationManager.IMPORTANCE_MIN))
        return NotificationCompat.Builder(context, PLAIN_CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Agent").setContentText("Working").setOngoing(true).build()
    }
}
