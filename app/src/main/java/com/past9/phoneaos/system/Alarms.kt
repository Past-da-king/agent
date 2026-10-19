package com.past9.phoneaos.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.past9.phoneaos.App
import com.past9.phoneaos.triggers.Wakes
import com.past9.phoneaos.triggers.Reminders
import com.past9.phoneaos.triggers.RoutineWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Exact alarms: the only thing Android fires on time when the phone is in Doze or the app is asleep.
 * WorkManager's delayed jobs are batched into maintenance windows (hours late on a Samsung that isn't charging),
 * so every clock moment the user cares about is also armed here. WorkManager stays as the backup; the run
 * ledger makes sure a moment that both start runs once.
 *
 * The app holds USE_EXACT_ALARM (API 33+) and SCHEDULE_EXACT_ALARM (API 31-32), so this needs no switch from the user.
 */
object ExactAlarms {
    const val ACTION_ROUTINE = "com.past9.phoneaos.alarm.ROUTINE"
    const val ACTION_REMINDER = "com.past9.phoneaos.alarm.REMINDER"
    const val ACTION_WAKE = "com.past9.phoneaos.alarm.WAKE"

    fun canExact(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    /** One PendingIntent per thing, told apart by its data uri (extras don't make intents distinct). */
    fun intent(context: Context, action: String, id: Long, at: Long): PendingIntent =
        PendingIntent.getBroadcast(context, 0, Intent(context, AlarmReceiver::class.java).setAction(action)
            .setData(Uri.parse("agent://alarm/$action/$id")).putExtra("id", id).putExtra("at", at),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /**
     * @param clock true: an alarm-clock alarm (what the user asked to be told at a time). Never deferred, not even in
     *   deep Doze, and Android shows it as the next alarm. false: exact and allowed while idle (routines, wakes).
     */
    fun set(context: Context, action: String, id: Long, at: Long, clock: Boolean = false) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = intent(context, action, id, at)
        runCatching {
            when {
                clock && canExact(context) -> am.setAlarmClock(AlarmManager.AlarmClockInfo(at, openApp(context)), pi)
                canExact(context) -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) // inexact, but still through Doze
            }
        }.onFailure { runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) } }
    }

    fun cancel(context: Context, action: String, id: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.cancel(intent(context, action, id, 0)) }
    }

    private fun openApp(context: Context) = PendingIntent.getActivity(context, 1,
        Intent(context, com.past9.phoneaos.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)

    /**
     * Keep the process up for the work an alarm starts. An exact alarm lets the app start a foreground service
     * from the background (Android 12+ forbids that otherwise), so it is started here, in the alarm's window,
     * not later from a worker where it would be refused and the work killed half way.
     */
    fun holdProcess(context: Context) {
        runCatching { context.startForegroundService(Intent(context, WorkService::class.java).putExtra(WorkService.EXTRA_KEEPALIVE, true)) }
    }
}

/** Every exact alarm lands here. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1); val at = intent.getLongExtra("at", 0)
        when (intent.action) {
            ExactAlarms.ACTION_ROUTINE -> {
                ExactAlarms.holdProcess(context)
                WorkManager.getInstance(context).enqueueUniqueWork("routine-alarm-$id-$at", ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<RoutineWorker>().setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .setInputData(workDataOf("id" to id, "slot" to at)).build())
            }
            ExactAlarms.ACTION_REMINDER, ExactAlarms.ACTION_WAKE -> {
                // A wake can run the agent for minutes. Holding the broadcast open until it finishes makes Android
                // time the broadcast out and show "Agent isn't responding" (four ANRs on 19 Oct 2026, all on
                // com.past9.phoneaos.alarm.WAKE). Start the keep-alive service, hand the work to the process, and
                // return from onReceive at once: the foreground service is what keeps it alive, not the broadcast.
                // workStarted() starts the foreground service and counts this job, so the process is kept for the
                // whole run (a wake's probes and model turn can take minutes) and the service stops itself after.
                val phone = App.graph(context).phone
                phone.workStarted()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (intent.action == ExactAlarms.ACTION_REMINDER) Reminders.fire(context, id, at)
                        else Wakes.fire(context)
                    } catch (t: Throwable) {
                        com.past9.phoneaos.agent.Crashes.record(context, "alarm ${intent.action}: ${t::class.java.simpleName} ${t.message}", t)
                    } finally { phone.workFinished() }
                }
            }
        }
    }
}

/** Re-arm every clock after a restart, an update, a clock or timezone change, or the exact-alarm permission changing. */
object Rearm {
    suspend fun all(context: Context) {
        runCatching { App.graph(context) }
        runCatching { com.past9.phoneaos.triggers.Routines.rescheduleAll(context) }
        runCatching { Reminders.syncAll(context, force = true) }
        runCatching { Wakes.rearm(context) }
        runCatching { com.past9.phoneaos.triggers.Routines.catchUp(context) }
    }
}
