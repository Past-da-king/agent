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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.past9.phoneaos.App
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
    AppSheet(Icons.Rounded.NotificationsActive, "Reminders on time", if (firstFix == null) "All set" else "One thing to switch on",
        onDismiss = onDismiss, primary = if (firstFix == null) "Done" else "Fix it", primaryEnabled = true,
        onPrimary = { firstFix?.invoke() ?: onDismiss() },
        secondary = { TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(if (firstFix == null) "Close" else "Not now") } }) {
        Text("So your agent can speak up by itself: ring your reminders, tell you before a meeting, and bring back what was agreed after one.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Row(Icons.Rounded.Notifications, "Notifications", h.notifications, if (h.notifications) "On" else "Off: nothing can reach you") {
            if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) else Background.openAppDetails(context)
        }
        Row(Icons.Rounded.BatteryChargingFull, "Battery: unrestricted", batteryOk,
            when { h.restricted -> "Set to Restricted. Tap, then Battery > Unrestricted"; !h.batteryUnrestricted -> "Optimised. Tap and choose Allow"; else -> "On" }) {
            if (h.restricted) Background.openAppDetails(context) else Background.requestBatteryExemption(context)
        }
        Row(Icons.Rounded.Alarm, "Exact alarms", h.exactAlarms, if (h.exactAlarms) "Allowed" else "Tap and allow alarms and reminders") { Background.openExactAlarms(context) }
        Row(Icons.Rounded.CalendarMonth, "Your calendar", h.calendar, if (h.calendar) "A heads-up before meetings, action items after" else "Optional: tap for meeting heads-ups and action items") {
            askCalendar.launch(Manifest.permission.READ_CALENDAR)
        }
        Spacer(Modifier.height(12.dp))
        Text("Quiet hours", style = MaterialTheme.typography.titleSmall)
        Text("Your agent won't bring anything up by itself in these hours. Reminders you set for a time still ring.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
        var quiet by remember { mutableStateOf(runCatching { com.past9.phoneaos.triggers.Wakes.quiet(context).toString() }.getOrDefault("23:00-07:00")) }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("off" to "Off", "22:00-06:00" to "22:00 to 06:00", "23:00-07:00" to "23:00 to 07:00", "00:00-08:00" to "00:00 to 08:00").forEach { (v, label) ->
                FilterChip(selected = quiet == v, onClick = {
                    quiet = v
                    scope.launch { App.graph(context).settings.setExtra("quiet_hours", v); runCatching { com.past9.phoneaos.triggers.Wakes.rearm(context) } }
                }, label = { Text(label) })
            }
        }
        if (h.samsung) {
            Spacer(Modifier.height(12.dp))
            Text("On a Samsung, also:", style = MaterialTheme.typography.titleSmall)
            Text("Samsung puts apps you haven't opened for a few days on its \"sleeping apps\" list, and a sleeping app can't ring or run. Taking this app off that list is what keeps reminders on time.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            Text("1. Tap below to open Battery\n2. Background usage limits\n3. Never sleeping apps\n4. Tap +, pick this app, Add",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
            OutlinedButton(onClick = { Background.openSamsungBattery(context) }, modifier = Modifier.fillMaxWidth()) { Text("Open Samsung battery settings") }
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
