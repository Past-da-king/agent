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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.past9.phoneaos.R

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
    SubScreen(stringResource(R.string.notif_title), pluralStringResource(R.plurals.notif_sub, allowed.size, allowed.size), actions.onBack) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(stringResource(R.string.notif_intro),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
            }
            if (!access) item {
                AppCard(container = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text(stringResource(R.string.notif_access_off), style = com.past9.phoneaos.ui.theme.Eyebrow, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.notif_access_body), color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = actions.onGrantAccess, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.notif_open_settings)) }
                }
            }
            if (recent.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.notif_lately), stringResource(R.string.notif_lately_sub), Modifier.padding(start = 4.dp, top = 6.dp)) }
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
            item { SectionHeader(stringResource(R.string.notif_apps), stringResource(R.string.notif_apps_sub), Modifier.padding(start = 4.dp, top = 6.dp)) }
            item {
                OutlinedTextField(query, { query = it }, placeholder = { Text(stringResource(R.string.notif_search)) }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true,
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
