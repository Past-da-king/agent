package com.past9.phoneaos.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.past9.phoneaos.R
import com.past9.phoneaos.ui.theme.Eyebrow
import kotlinx.coroutines.launch

data class NavCounts(val tasks: Int = 0, val memories: Int = 0, val routines: Int = 0, val apps: Int = 0)

/** The drawer: chat is home; everything else is a secondary view one tap away. */
@Composable
fun AppDrawer(agentName: String, counts: NavCounts, onGo: (String) -> Unit) {
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 20.dp)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AgentAvatar(working = false, size = 44.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(agentName, style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.ui_drawer_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.ui_drawer_your_agent), style = Eyebrow, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Item(Icons.Rounded.ChatBubble, stringResource(R.string.ui_nav_chat), null) { onGo("chat") }
            Item(Icons.Rounded.TaskAlt, stringResource(R.string.ui_nav_tasks), counts.tasks.takeIf { it > 0 }?.let { stringResource(R.string.ui_nav_tasks_open, it) }) { onGo("tasks") }
            Item(Icons.Rounded.Psychology, stringResource(R.string.ui_nav_memory), counts.memories.takeIf { it > 0 }?.toString()) { onGo("memory") }
            Item(Icons.Rounded.Schedule, stringResource(R.string.ui_nav_routines), counts.routines.takeIf { it > 0 }?.let { stringResource(R.string.ui_nav_routines_on, it) }) { onGo("routines") }
            Item(Icons.Rounded.Language, stringResource(R.string.ui_nav_browser), null) { onGo("browser") }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Item(Icons.Rounded.Apps, stringResource(R.string.ui_nav_connections), counts.apps.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.ui_nav_apps_count, it, it) }) { onGo("connections") }
            Item(Icons.Rounded.Settings, stringResource(R.string.ui_nav_settings), null) { onGo("settings") }
        }
    }
}

@Composable
private fun Item(icon: ImageVector, label: String, badge: String?, onClick: () -> Unit) {
    NavigationDrawerItem(
        icon = { Icon(icon, null) }, label = { Text(label, style = MaterialTheme.typography.titleSmall) },
        badge = badge?.let { { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        selected = false, onClick = onClick, modifier = Modifier.height(56.dp),
    )
}
