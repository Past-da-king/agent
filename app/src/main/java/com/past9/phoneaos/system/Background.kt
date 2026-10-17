package com.past9.phoneaos.system

import android.Manifest
import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * What on this phone stops the agent from speaking up on time, and where to switch it off.
 * Samsung puts apps to sleep ("Sleeping" / "Deep sleeping") after a few days unused in the background; a sleeping
 * app's alarms and jobs are held back for hours, which is exactly "it never reminds me unless I open it".
 */
object Background {
    data class Health(
        /** Not under battery optimisation (Settings > Apps > Agent > Battery > Unrestricted). */
        val batteryUnrestricted: Boolean,
        /** The user set battery to Restricted: almost nothing runs in the background. */
        val restricted: Boolean,
        val exactAlarms: Boolean,
        val notifications: Boolean,
        val calendar: Boolean,
        /** Android's standby bucket (10 active ... 40 rare, 45 restricted), -1 unknown. */
        val bucket: Int,
        val samsung: Boolean,
    )

    fun check(context: Context): Health {
        val pm = context.getSystemService(PowerManager::class.java)
        val am = context.getSystemService(ActivityManager::class.java)
        val usm = context.getSystemService(UsageStatsManager::class.java)
        return Health(
            batteryUnrestricted = pm?.isIgnoringBatteryOptimizations(context.packageName) == true,
            restricted = am?.isBackgroundRestricted == true,
            exactAlarms = ExactAlarms.canExact(context),
            notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            calendar = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED,
            bucket = runCatching { usm?.appStandbyBucket ?: -1 }.getOrDefault(-1),
            samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true),
        )
    }

    /** Plain lines for what is wrong, worst first. Pure, for the tests. Calendar is optional, so it is not a problem. */
    fun problems(h: Health): List<String> = buildList {
        if (!h.notifications) add("notifications are off for the app, so nothing can reach you")
        if (h.restricted) add("battery is set to Restricted, so it can't run in the background")
        else if (!h.batteryUnrestricted) add("battery optimisation is on, so ${if (h.samsung) "Samsung puts it to sleep and " else ""}reminders arrive late")
        if (!h.exactAlarms) add("exact alarms aren't allowed, so reminders can be late")
        if (h.bucket >= 45) add("Android has put the app in its most restricted group")
    }

    fun requestBatteryExemption(context: Context) = start(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
        || openAppDetails(context)

    fun openAppDetails(context: Context) = start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))

    fun openExactAlarms(context: Context) = Build.VERSION.SDK_INT >= 31 &&
        start(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))

    /** Samsung's battery page, where "Background usage limits > Never sleeping apps" lives. Falls back to the app's page. */
    fun openSamsungBattery(context: Context): Boolean {
        val tries = listOf(
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )
        return tries.any { start(context, Intent().setComponent(it)) } || start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) || openAppDetails(context)
    }

    private fun start(context: Context, i: Intent): Boolean = runCatching {
        context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)
}
