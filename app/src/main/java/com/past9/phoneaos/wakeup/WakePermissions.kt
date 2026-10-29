package com.past9.phoneaos.wakeup

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** What the phone needs so a wake-up can ring through everything, each with the settings page that fixes it. */
object WakePermissions {
    data class Check(val key: String, val label: String, val ok: Boolean, val help: String, val fix: Intent?)

    fun checks(context: Context): List<Check> {
        val pkg = context.packageName
        val nm = context.getSystemService(NotificationManager::class.java)
        val notes = NotificationManagerCompat.from(context).areNotificationsEnabled()
        WakeUpService.ensureChannel(context)
        val channelOn = Build.VERSION.SDK_INT < 26 || (nm?.getNotificationChannel(WakeUpService.CHANNEL)?.importance ?: 0) >= NotificationManager.IMPORTANCE_HIGH
        val fsi = Build.VERSION.SDK_INT < 34 || nm?.canUseFullScreenIntent() == true
        val dnd = nm?.isNotificationPolicyAccessGranted == true
        val overlay = Settings.canDrawOverlays(context)
        val battery = (context.getSystemService(PowerManager::class.java))?.isIgnoringBatteryOptimizations(pkg) == true
        val exact = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
        val appNotes = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
        return listOf(
            Check("notifications", "Notifications on", notes, "Tap: Notifications, allow", appNotes),
            Check("channel", "Wake-ups channel on, Urgent", channelOn, "Tap: Wake-ups, allow, pop on screen", if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg).putExtra(Settings.EXTRA_CHANNEL_ID, WakeUpService.CHANNEL) else appNotes),
            Check("fullscreen", "Full-screen notifications", fsi, "Android 14+: tap and allow full-screen notifications for Agent", if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$pkg")) else null),
            Check("overlay", "Appear on top", overlay, "Tap and allow, so it covers the app you are in", Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg"))),
            Check("dnd", "Do Not Disturb access", dnd, "Tap and allow Agent, so the alarm volume goes up in Do Not Disturb", Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)),
            Check("battery", "Battery: unrestricted", battery, "Tap and choose Allow (Samsung: also not in sleeping apps)", Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg"))),
            Check("exact", "Alarms and reminders", exact, "Tap and allow", if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$pkg")) else null),
        )
    }

    fun fix(context: Context, check: Check): Boolean = runCatching {
        val i = check.fix ?: return false
        context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrElse {
        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        false
    }
}
