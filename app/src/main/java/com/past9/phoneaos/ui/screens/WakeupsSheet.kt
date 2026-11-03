package com.past9.phoneaos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.past9.phoneaos.remote.RemoteHub
import com.past9.phoneaos.ui.AppSheet
import com.past9.phoneaos.wakeup.WakeChannel
import com.past9.phoneaos.wakeup.WakePermissions
import com.past9.phoneaos.wakeup.WakeRules
import kotlinx.coroutines.launch

/**
 * Settings > Wake-ups: what this phone needs so a wake-up rings through everything (each with the tap that fixes
 * it), whether the wake channel to the computer is open, and a test per severity that rings this phone for real.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WakeupsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; WakeChannel.ensure(context); WakeChannel.report(context); onPauseOrDispose { } }
    val checks = remember(tick) { WakePermissions.checks(context) }
    val linked = RemoteHub.link.collectAsState().value
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf("") }
    val firstBad = checks.firstOrNull { !it.ok }
    AppSheet(Icons.Rounded.Alarm, "Wake-ups", if (firstBad == null) "Ready to ring" else "One thing to switch on",
        onDismiss = onDismiss, primary = if (firstBad == null) "Done" else "Fix it", primaryEnabled = true,
        onPrimary = { if (firstBad == null) onDismiss() else WakePermissions.fix(context, firstBad) },
        secondary = { TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") } }) {
        Text("When something needs you now, your agent rings this phone through Do Not Disturb, full screen, until you answer. I'm on it here stops your computer too.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        val open = WakeChannel.isOpen
        val lacks = open && WakeChannel.lacksWakeups
        val pc = WakeChannel.connectedTo.ifBlank { "your computer" }
        CheckRow(Icons.Rounded.Devices, "Reaches this phone", open && !lacks,
            when { linked == null -> "Link this phone to your computer first (Settings > Linked devices)"; lacks -> "$pc can't ring this phone yet: update the Agent app there"; open -> "Connected to $pc"; else -> "Not connected right now. It keeps trying." }) { WakeChannel.ensure(context) }
        checks.forEach { c -> CheckRow(Icons.Rounded.Shield, c.label, c.ok, if (c.ok) "On" else c.help) { WakePermissions.fix(context, c); tick++ } }
        Spacer(Modifier.height(12.dp))
        Text("Test a wake-up", style = MaterialTheme.typography.titleSmall)
        Text("Rings this phone for real. Answer it to stop it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("critical", "urgent", "heads_up").forEach { sev ->
                OutlinedButton(onClick = {
                    scope.launch { said = runCatching { WakeChannel.test(sev); "${WakeRules.label(sev)} test sent." }.getOrElse { it.message ?: "It did not go." } }
                }) {
                    Box(Modifier.size(10.dp).padding(0.dp)) { Surface(color = Color(WakeRules.colour(sev)), shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxSize()) {} }
                    Spacer(Modifier.width(8.dp)); Text(WakeRules.label(sev))
                }
            }
        }
        if (said.isNotBlank()) Text(said, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(10.dp))
        Text("Install Agent normally (not inside Secure Folder): a locked Secure Folder cannot ring.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CheckRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, ok: Boolean, detail: String, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = !ok, color = Color.Transparent) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null, tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
    }
}
