package com.past9.phoneaos.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.data.NotificationRow
import com.past9.phoneaos.ui.AppCard
import com.past9.phoneaos.ui.SectionHeader
import com.past9.phoneaos.ui.SubScreen
import com.past9.phoneaos.ui.relativeTime

data class PhoneApp(val pkg: String, val label: String, val icon: ImageBitmap?)

data class NotificationsActions(
    val onBack: () -> Unit = {},
    val onToggle: (String, Boolean) -> Unit = { _, _ -> },
    val onGrantAccess: () -> Unit = {},
)

/** Which apps the agent may read notifications from, and what it has seen. */
@Composable
fun NotificationsScreen(access: Boolean, apps: List<PhoneApp>, allowed: Set<String>, recent: List<NotificationRow>, actions: NotificationsActions) {
    var query by remember { mutableStateOf("") }
    SubScreen("Notifications", "${allowed.size} app${if (allowed.size == 1) "" else "s"} your agent can read", actions.onBack) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Your agent learns a lot from your notifications: bank alerts, deliveries, messages. It only reads the apps you switch on here, and keeps a week of them on this phone.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
            }
            if (!access) item {
                AppCard(container = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text("NOTIFICATION ACCESS IS OFF", style = com.past9.phoneaos.ui.theme.Eyebrow, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.height(6.dp))
                    Text("Turn it on for this app in Android settings. If Android says it's a restricted setting, open this app's info, tap the ⋮ menu, then Allow restricted settings.", color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = actions.onGrantAccess, shapes = ButtonDefaults.shapes()) { Text("Open settings") }
                }
            }
            if (recent.isNotEmpty()) {
                item { SectionHeader("Lately", "What your agent has seen", Modifier.padding(start = 4.dp, top = 6.dp)) }
                item {
                    AppCard(padding = PaddingValues(vertical = 6.dp)) {
                        recent.take(6).forEach { n ->
                            Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                                Text("${n.app.uppercase()} · ${relativeTime(n.postedAt).uppercase()}", style = com.past9.phoneaos.ui.theme.Eyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(n.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(n.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            item { SectionHeader("Apps", "Switch on the ones that matter", Modifier.padding(start = 4.dp, top = 6.dp)) }
            item {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search apps") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge)
            }
            val shown = apps.filter { query.isBlank() || it.label.contains(query, true) }.sortedWith(compareBy({ it.pkg !in allowed }, { it.label.lowercase() }))
            items(shown, key = { it.pkg }) { a ->
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (a.icon != null) Image(a.icon, null, Modifier.size(36.dp).clip(MaterialTheme.shapes.small)) else Icon(Icons.Rounded.Apps, null, Modifier.size(36.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(a.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Switch(a.pkg in allowed, { actions.onToggle(a.pkg, it) })
                    }
                }
            }
        }
    }
}
