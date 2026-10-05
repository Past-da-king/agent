package com.past9.phoneaos.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.tools.Connection
import com.past9.phoneaos.tools.Toolkit
import com.past9.phoneaos.ui.AppCard
import com.past9.phoneaos.ui.SectionHeader
import com.past9.phoneaos.ui.StatusPill
import com.past9.phoneaos.ui.SubScreen
import com.past9.phoneaos.ui.theme.LocalExtra

data class ConnectionsState(
    val hasKey: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val connected: List<Connection> = emptyList(),
    val toolkits: List<Toolkit> = emptyList(),
    val notificationsAllowed: Boolean = true,
    /** A consumer key (Composio Connect): apps are connected by the agent on request, not from a list here. */
    val consumer: Boolean = false,
)

data class ConnectionsActions(
    val onBack: () -> Unit = {},
    val onSaveKey: suspend (String) -> String? = { null },
    val onRemoveKey: () -> Unit = {},
    val onConnect: (String) -> Unit = {},
    val onDisconnect: (Connection) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
    val onAllowNotifications: () -> Unit = {},
    val onNotifications: () -> Unit = {},
)

@Composable
fun ConnectionsScreen(state: ConnectionsState, actions: ConnectionsActions, bottomPadding: androidx.compose.ui.unit.Dp = 48.dp) {
    var query by remember { mutableStateOf("") }
    val activeCount = state.connected.count { it.status == "ACTIVE" }
    SubScreen("Connections", if (state.hasKey) (if (activeCount == 1) "1 app connected" else "$activeCount apps connected") else "Connect your apps", actions.onBack,
        actions = { if (state.hasKey) IconButton(onClick = actions.onRefresh) { Icon(Icons.Rounded.Refresh, "Refresh") } }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { SectionHeader("On this phone", "Built in, nothing to set up", Modifier.padding(start = 4.dp, top = 4.dp)) }
            item {
                AppCard(padding = PaddingValues(vertical = 4.dp)) {
                    PhoneLine(Icons.Rounded.Language, "Background browser", "Your agent's own browser keeps working while you use other apps", true, null)
                    PhoneLine(Icons.Rounded.IosShare, "Share to your agent", "From any app, tap Share and pick your agent", true, null)
                    PhoneLine(Icons.Rounded.Notifications, "Notifications", if (state.notificationsAllowed) "Results and questions reach you anywhere" else "Off: you'll only see results in the app",
                        state.notificationsAllowed, if (state.notificationsAllowed) null else actions.onAllowNotifications)
                    Surface(onClick = actions.onNotifications, color = androidx.compose.ui.graphics.Color.Transparent) {
                        PhoneLine(Icons.Rounded.MarkEmailUnread, "Read my notifications", "Pick the apps your agent may learn from", false, null)
                    }
                }
            }
            item { SectionHeader("Your apps", "Gmail, Calendar, Drive, Slack and 250+ more, through Composio with your own key", Modifier.padding(start = 4.dp, top = 16.dp)) }
            if (!state.hasKey) item {
                AppCard { ComposioKeyForm(actions.onSaveKey, actions.onOpenUrl) {} }
            } else {
                if (state.error != null) item { Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(4.dp)) }
                if (state.consumer) {
                    item {
                        AppCard {
                            Text("CONNECTED THROUGH COMPOSIO CONNECT", style = com.past9.phoneaos.ui.theme.Eyebrow, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(6.dp))
                            Text("Your agent can use every app on your Composio account. To add one, just ask it, for example \"connect my Gmail\". It sends you the sign-in link and carries on once you're done.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    item { TextButton(onClick = actions.onRemoveKey, modifier = Modifier.padding(top = 12.dp)) { Text("Remove Composio key", color = MaterialTheme.colorScheme.error) } }
                    return@LazyColumn
                }
                val active = state.connected
                if (active.isNotEmpty()) item {
                    AppCard(padding = PaddingValues(vertical = 4.dp)) {
                        active.forEach { c -> val tk = state.toolkits.firstOrNull { it.slug == c.toolkit }; ConnectedLine(c, tk?.name ?: prettySlug(c.toolkit), actions, tk?.logo.orEmpty()) }
                    }
                }
                item {
                    OutlinedTextField(query, { query = it }, placeholder = { Text("Search ${if (state.toolkits.isEmpty()) "" else "${state.toolkits.size} "}apps") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), shape = MaterialTheme.shapes.extraLarge)
                }
                if (state.loading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() } }
                val list = state.toolkits.filter { query.isBlank() || it.name.contains(query, true) || it.slug.contains(query, true) }
                items(list.take(150), key = { it.slug }) { t -> ToolkitLine(t, state.connected.any { it.toolkit == t.slug && it.status == "ACTIVE" }) { actions.onConnect(t.slug) } }
                item { TextButton(onClick = actions.onRemoveKey, modifier = Modifier.padding(top = 12.dp)) { Text("Remove Composio key", color = MaterialTheme.colorScheme.error) } }
            }
        }
    }
}

@Composable
private fun PhoneLine(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, line: String, on: Boolean, fix: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (fix != null) FilledTonalButton(onClick = fix) { Text("Allow") }
        else if (on) Icon(Icons.Rounded.CheckCircle, "On", tint = LocalExtra.current.success)
        else Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Initial(name: String, logo: String = "") {
    if (logo.isNotBlank()) {
        coil.compose.SubcomposeAsyncImage(model = logo, contentDescription = null, modifier = Modifier.size(40.dp).clip(MaterialTheme.shapes.small).background(androidx.compose.ui.graphics.Color.White).padding(5.dp),
            error = { InitialBox(name) }, loading = { InitialBox(name) })
        return
    }
    InitialBox(name)
}

@Composable
private fun InitialBox(name: String) {
    Box(Modifier.size(40.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun ConnectedLine(c: Connection, name: String, actions: ConnectionsActions, logo: String = "") {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Initial(name, logo)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(if (c.label == c.toolkit) "Signed in through Composio" else c.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val extra = LocalExtra.current
        when (c.status) {
            "ACTIVE" -> StatusPill("Connected", extra.successContainer, extra.success)
            "INITIATED", "INITIALIZING" -> StatusPill("Finish sign-in", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
            else -> StatusPill(c.status.lowercase().replaceFirstChar { it.uppercase() }, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        }
        IconButton(onClick = { actions.onDisconnect(c) }) { Icon(Icons.Rounded.LinkOff, "Disconnect ${c.toolkit}", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ToolkitLine(t: Toolkit, connected: Boolean, onConnect: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Initial(t.name, t.logo)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.titleSmall)
                Text(t.description.ifBlank { "${t.toolsCount} actions" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            if (connected) Icon(Icons.Rounded.CheckCircle, "Connected", tint = LocalExtra.current.success)
            else FilledTonalButton(onClick = onConnect, shapes = ButtonDefaults.shapes()) { Text("Connect") }
        }
    }
}

private val known = mapOf("googlecalendar" to "Google Calendar", "googledrive" to "Google Drive", "googledocs" to "Google Docs", "googlesheets" to "Google Sheets", "github" to "GitHub", "linkedin" to "LinkedIn", "whatsapp" to "WhatsApp")
fun prettySlug(slug: String) = known[slug] ?: slug.replace('_', ' ').replaceFirstChar { it.uppercase() }
