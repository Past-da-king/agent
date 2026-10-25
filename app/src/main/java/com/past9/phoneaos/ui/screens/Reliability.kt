package com.past9.phoneaos.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.past9.phoneaos.App
import com.past9.phoneaos.R
import com.past9.phoneaos.system.Background
import com.past9.phoneaos.ui.AppSheet
import kotlinx.coroutines.launch

/**
 * "Reminders on time": what on this phone holds the agent back, each with the one tap that fixes it.
 * Offered once by itself when something is wrong, and always from Settings > Background.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReliabilitySheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    // Coming back from a system settings page: look again.
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val h = remember(tick) { Background.check(context) }
    val scope = rememberCoroutineScope()
    val askCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> tick++; if (ok) scope.launch { runCatching { com.past9.phoneaos.triggers.Wakes.rearm(context) } } }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    val batteryOk = h.batteryUnrestricted && !h.restricted
    val firstFix: (() -> Unit)? = when {
        !h.notifications -> { { if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) else Background.openAppDetails(context) } }
        !batteryOk -> { { if (h.restricted) Background.openAppDetails(context) else Background.requestBatteryExemption(context) } }
        !h.exactAlarms -> { { Background.openExactAlarms(context) } }
        else -> null
    }
    AppSheet(Icons.Rounded.NotificationsActive, stringResource(R.string.settings_reminders_on_time), if (firstFix == null) stringResource(R.string.rel_all_set) else stringResource(R.string.rel_one_thing),
        onDismiss = onDismiss, primary = if (firstFix == null) stringResource(R.string.rel_done) else stringResource(R.string.rel_fix_it), primaryEnabled = true,
        onPrimary = { firstFix?.invoke() ?: onDismiss() },
        secondary = { TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(if (firstFix == null) stringResource(R.string.rel_close) else stringResource(R.string.onb_not_now)) } }) {
        Text(stringResource(R.string.rel_intro),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Row(Icons.Rounded.Notifications, stringResource(R.string.rel_notifications), h.notifications, if (h.notifications) stringResource(R.string.rel_on) else stringResource(R.string.rel_notifications_off)) {
            if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) else Background.openAppDetails(context)
        }
        Row(Icons.Rounded.BatteryChargingFull, stringResource(R.string.settings_battery), batteryOk,
            when { h.restricted -> stringResource(R.string.rel_battery_restricted); !h.batteryUnrestricted -> stringResource(R.string.rel_battery_optimised); else -> stringResource(R.string.rel_on) }) {
            if (h.restricted) Background.openAppDetails(context) else Background.requestBatteryExemption(context)
        }
        Row(Icons.Rounded.Alarm, stringResource(R.string.rel_exact_alarms), h.exactAlarms, if (h.exactAlarms) stringResource(R.string.rel_allowed) else stringResource(R.string.rel_exact_alarms_off)) { Background.openExactAlarms(context) }
        Row(Icons.Rounded.CalendarMonth, stringResource(R.string.rel_calendar), h.calendar, if (h.calendar) stringResource(R.string.rel_calendar_on) else stringResource(R.string.rel_calendar_off)) {
            askCalendar.launch(Manifest.permission.READ_CALENDAR)
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.rel_quiet_hours), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.rel_quiet_body), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
        var quiet by remember { mutableStateOf(runCatching { com.past9.phoneaos.triggers.Wakes.quiet(context).toString() }.getOrDefault("23:00-07:00")) }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("off" to stringResource(R.string.rel_off), "22:00-06:00" to stringResource(R.string.rel_quiet_range, "22:00", "06:00"), "23:00-07:00" to stringResource(R.string.rel_quiet_range, "23:00", "07:00"), "00:00-08:00" to stringResource(R.string.rel_quiet_range, "00:00", "08:00")).forEach { (v, label) ->
                FilterChip(selected = quiet == v, onClick = {
                    quiet = v
                    scope.launch { App.graph(context).settings.setExtra("quiet_hours", v); runCatching { com.past9.phoneaos.triggers.Wakes.rearm(context) } }
                }, label = { Text(label) })
            }
        }
        if (h.samsung) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.rel_samsung_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.rel_samsung_body),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            Text(stringResource(R.string.rel_samsung_steps),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
            OutlinedButton(onClick = { Background.openSamsungBattery(context) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.rel_samsung_open)) }
        }
    }
}

@Composable
private fun Row(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, ok: Boolean, detail: String, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = !ok, color = androidx.compose.ui.graphics.Color.Transparent) {
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null,
                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
    }
}
