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
    /** A consumer key (Composio Connect): the list is our catalogue plus search, statuses come from Composio Connect. */
    val consumer: Boolean = false,
    /** Searching all of Composio's apps for this query (consumer keys). */
    val searching: Boolean = false,
    /** The last query sent to the full search, so "Search all apps" isn't offered twice. */
    val searched: String = "",
    /** Apps the full search found for [searched]. */
    val hits: Set<String> = emptySet(),
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
    /** Search every Composio app (not just the popular list) for this name. */
    val onSearchAll: (String) -> Unit = {},
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
            item { SectionHeader("Your apps", if (state.consumer) "Tap Connect and sign in. Your agent can use it straight away." else "Gmail, Calendar, Drive, Slack and 250+ more, through Composio with your own key", Modifier.padding(start = 4.dp, top = 16.dp)) }
            if (!state.hasKey) item {
                AppCard { ComposioKeyForm(actions.onSaveKey, actions.onOpenUrl) {} }
            } else {
                if (state.error != null) item { Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(4.dp)) }
                val active = state.connected
                if (active.isNotEmpty()) item {
                    AppCard(padding = PaddingValues(vertical = 4.dp)) {
                        active.forEach { c -> val tk = state.toolkits.firstOrNull { it.slug == c.toolkit }; ConnectedLine(c, tk?.name ?: prettySlug(c.toolkit), actions, tk?.logo.orEmpty()) }
                    }
                }
                item {
                    OutlinedTextField(query, { query = it }, placeholder = { Text(if (state.consumer) "Search apps" else "Search ${if (state.toolkits.isEmpty()) "" else "${state.toolkits.size} "}apps") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), shape = MaterialTheme.shapes.extraLarge)
                }
                if (state.loading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() } }
                val list = state.toolkits.filter { query.isBlank() || it.name.contains(query, true) || it.slug.contains(query, true) || (state.searched == query.trim() && it.slug in state.hits) }
                items(list.take(150), key = { it.slug }) { t -> ToolkitLine(t, state.connected.any { it.toolkit == t.slug && it.status == "ACTIVE" }) { actions.onConnect(t.slug) } }
                // Composio Connect keys browse the popular apps; anything else is one search away.
                if (state.consumer && query.isNotBlank() && state.searched != query.trim()) item {
                    Surface(onClick = { actions.onSearchAll(query.trim()) }, enabled = !state.searching, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (state.searching) LoadingIndicator(Modifier.size(24.dp)) else Icon(Icons.Rounded.TravelExplore, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (state.searching) "Searching Composio…" else "Search all 500+ apps for \u201c${query.trim()}\u201d", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                if (list.isEmpty() && !state.searching) Text("Not in the popular list", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }
                }
                if (state.consumer && query.isNotBlank() && state.searched == query.trim() && list.isEmpty()) item {
                    Text("No app called \u201c${query.trim()}\u201d on Composio.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                }
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
private fun Initial(name: String, logo: String = "") = AppLogo(name, logo)

/** An app's logo on a white tile (logos are drawn for light backgrounds), or its initial while it loads. */
@Composable
fun AppLogo(name: String, logo: String, size: androidx.compose.ui.unit.Dp = 40.dp) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(size * 0.28f)
    if (logo.isNotBlank()) {
        coil.compose.SubcomposeAsyncImage(model = logo, contentDescription = null, modifier = Modifier.size(size).clip(shape).background(androidx.compose.ui.graphics.Color(0xFFF7F7F8)).padding(size * 0.16f),
            error = { InitialBox(name, size, shape) }, loading = { InitialBox(name, size, shape) })
        return
    }
    InitialBox(name, size, shape)
}

@Composable
private fun InitialBox(name: String, size: androidx.compose.ui.unit.Dp = 40.dp, shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.small) {
    Box(Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), style = if (size > 44.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun ConnectedLine(c: Connection, name: String, actions: ConnectionsActions, logo: String = "") {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Initial(name, logo)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(if (c.label == c.toolkit) (if (c.status == "ACTIVE") "Through Composio" else "Sign-in not finished") else c.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
