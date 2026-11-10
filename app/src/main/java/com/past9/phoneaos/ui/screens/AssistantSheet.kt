package com.past9.phoneaos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Assistant
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.past9.phoneaos.assist.AssistEntry
import com.past9.phoneaos.assist.AssistPrefs
import com.past9.phoneaos.assist.AssistRole
import com.past9.phoneaos.ui.AppSheet

/**
 * Settings > Default assistant: whether the app is the phone's assistant, the two taps that make it so (Android has
 * no dialog for it, so this opens the phone's own page), how the assistant is then called on this phone, and the two
 * switches for what it may know about the screen. A slide-up sheet, never a pop-up.
 *
 * [held], [readScreen] and [readPicture] are only passed by screenshot tests; the app reads the real state.
 */
@Composable
fun AssistantSheet(agentName: String, onDismiss: () -> Unit, held: Boolean? = null, readScreen: Boolean? = null, readPicture: Boolean? = null,
                   steps: AssistRole.Guide? = null) {
    val context = LocalContext.current
    // The phone's picker lists the app under its own label, which is not always the agent's name.
    val guide = steps ?: remember { AssistRole.guide(runCatching { context.applicationInfo.loadLabel(context.packageManager).toString() }.getOrDefault("").ifBlank { "this app" }) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val isHeld = held ?: remember(tick) { AssistRole.held(context) }
    var screenOn by remember(tick) { mutableStateOf(readScreen ?: AssistPrefs.readScreen(context)) }
    var pictureOn by remember(tick) { mutableStateOf(readPicture ?: AssistPrefs.readScreenshot(context)) }
    val cs = MaterialTheme.colorScheme
    AppSheet(Icons.Rounded.Assistant, "Default assistant", if (isHeld) "$agentName is this phone's assistant" else "Make $agentName this phone's assistant",
        onDismiss = onDismiss, primary = if (isHeld) "Try it now" else "Open Settings", primaryEnabled = true,
        onPrimary = { if (isHeld) { AssistEntry.open(context, AssistEntry.SHORTCUT); onDismiss() } else AssistRole.openSettings(context) },
        secondary = { TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") } }) {
        Text("A compact sheet slides up over whatever app you are in, listening straight away. Say what you want and $agentName answers or gets it done.",
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Assistant, null, tint = cs.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Phone's assistant", style = MaterialTheme.typography.titleSmall)
                Text(if (isHeld) "On" else "Off. Your phone only lets you pick it in its own Settings: two taps.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Icon(if (isHeld) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null, tint = if (isHeld) cs.primary else cs.error)
        }
        if (!isHeld) {
            Spacer(Modifier.height(4.dp))
            guide.steps.forEachIndexed { i, s -> Step(i + 1, s) }
        }
        Spacer(Modifier.height(12.dp))
        Text(if (isHeld) "How to call it" else "Then, to call it", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        guide.triggers.forEach { t -> Text(t, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 3.dp)) }
        Spacer(Modifier.height(16.dp))
        Text("What it may know about the screen", style = MaterialTheme.typography.titleSmall)
        SwitchRow(Icons.Rounded.Visibility, "Let the assistant read the screen",
            "The words on the screen you call it over go to $agentName with that one request, so \"send this to...\" or \"what is this?\" works. Off: it only hears you.",
            screenOn) { on -> screenOn = on; if (!on) pictureOn = false; AssistPrefs.setReadScreen(context, on) }
        SwitchRow(Icons.Rounded.Image, "Also send a picture of the screen", "For screens that are mostly pictures. Only with the switch above on.",
            pictureOn, enabled = screenOn) { on -> pictureOn = on; AssistPrefs.setReadScreenshot(context, on) }
        Spacer(Modifier.height(8.dp))
        Text("It never reads banking, payment or money apps, with either switch on. What it reads is used for that request only.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun Step(n: Int, text: String) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.labelMedium, color = cs.onPrimaryContainer)
        }
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, line: String, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (enabled) cs.primary else cs.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) cs.onSurface else cs.onSurfaceVariant)
            Text(line, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(on, onChange, enabled = enabled)
    }
}
