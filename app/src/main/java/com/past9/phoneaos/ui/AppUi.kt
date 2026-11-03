package com.past9.phoneaos.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
                    Text("Everything it keeps for you", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("YOUR AGENT", style = Eyebrow, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Item(Icons.Rounded.ChatBubble, "Chat", null) { onGo("chat") }
            Item(Icons.Rounded.TaskAlt, "Tasks", counts.tasks.takeIf { it > 0 }?.let { "$it open" }) { onGo("tasks") }
            Item(Icons.Rounded.Psychology, "Memory", counts.memories.takeIf { it > 0 }?.toString()) { onGo("memory") }
            Item(Icons.Rounded.Schedule, "Routines", counts.routines.takeIf { it > 0 }?.let { "$it on" }) { onGo("routines") }
            Item(Icons.Rounded.Language, "Browser", null) { onGo("browser") }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Item(Icons.Rounded.Apps, "Connections", counts.apps.takeIf { it > 0 }?.let { "$it apps" }) { onGo("connections") }
            Item(Icons.Rounded.Settings, "Settings", null) { onGo("settings") }
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
