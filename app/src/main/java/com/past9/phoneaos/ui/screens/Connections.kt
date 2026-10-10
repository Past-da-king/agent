package com.past9.phoneaos.ui.screens

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.past9.phoneaos.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.toShape
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
    /** The user switched on All files access, so the agent can search the phone's storage. */
    val phoneFiles: Boolean = false,
    /** A consumer key (Composio Connect): the list is our catalogue plus search, statuses come from Composio Connect. */
    val consumer: Boolean = false,
    /** Searching all of Composio's apps for this query (consumer keys). */
    val searching: Boolean = false,
    /** The last query sent to the full search, so "Search all apps" isn't offered twice. */
    val searched: String = "",
    /** Apps the full search found for [searched]. */
    val hits: Set<String> = emptySet(),
    /** Servers and computers the agent can work on over SSH. */
    val machines: List<com.past9.phoneaos.machines.Machine> = emptyList(),
    /** Parts of the user's life, each a set of these accounts. */
    val profiles: List<com.past9.phoneaos.data.AgentProfile> = emptyList(),
    /** Per account: what the agent may do there. */
    val rules: Map<String, com.past9.phoneaos.data.AccountRules> = emptyMap(),
    val browserProfiles: List<String> = listOf("Personal"),
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
    val onAllowPhoneFiles: () -> Unit = {},
    /** Search every Composio app (not just the popular list) for this name. */
    val onSearchAll: (String) -> Unit = {},
    /** Machines: add, test, edit, remove. */
    val machine: MachineActions = MachineActions(),
    val onNotifications: () -> Unit = {},
    val profile: AccountProfileActions = AccountProfileActions(),
    /** The same sign-in is connected more than once: keep the first, remove the copies. */
    val onKeepOne: (keep: Connection, remove: List<Connection>) -> Unit = { _, _ -> },
)

/** Accounts of one app that are the same sign-in (same email or name, or none shared). The first is the keeper. */
fun duplicateGroups(connected: List<Connection>): List<List<Connection>> =
    connected.filter { it.status == "ACTIVE" }.groupBy { it.toolkit to identityOf(it).lowercase() }.values.filter { it.size > 1 }

/** Who the account is, if Composio said (an email, a username); blank when it only knows the app. */
fun identityOf(c: Connection) = c.label.takeIf { it.isNotBlank() && !it.equals(c.toolkit, true) }.orEmpty()

@Composable
fun ConnectionsScreen(state: ConnectionsState, actions: ConnectionsActions, bottomPadding: androidx.compose.ui.unit.Dp = 48.dp, initialSheet: String? = null, initialAppsOpen: Boolean = false) {
    var query by remember { mutableStateOf("") }
    // Your apps start folded: a summary of what's connected; open it to manage or add.
    var appsOpen by remember { mutableStateOf(initialAppsOpen) }
    // Machines: "list", "new", or a machine id being edited.
    var sheet by remember { mutableStateOf(initialSheet) }
    val logos = state.toolkits.associate { it.slug to it.logo }
    when (val sh = sheet) {
        null -> {}
        "profile:new" -> ProfileSheet(null, state.connected.map { it.ref() }, logos, state.browserProfiles, actions.profile) { sheet = null }
        else -> if (sh.startsWith("profile:")) ProfileSheet(state.profiles.firstOrNull { it.id == sh.removePrefix("profile:") }, state.connected.map { it.ref() }, logos, state.browserProfiles, actions.profile) { sheet = null }
            else if (sh.startsWith("account:")) state.connected.firstOrNull { it.id == sh.removePrefix("account:") }?.let { c ->
                AccountSheet(c.ref(), logos[c.toolkit].orEmpty(), state.rules[c.id] ?: com.past9.phoneaos.data.AccountRules.DEFAULT, state.profiles, actions.profile,
                    onDisconnect = { actions.onDisconnect(c) }) { sheet = null }
            }
    }
    when (val sh = sheet) {
        null -> {}
        else -> if (sh.startsWith("profile:") || sh.startsWith("account:")) {} else when (sh) {
        "list" -> MachinesSheet(state.machines, actions.machine, onDismiss = { sheet = null }, onOpen = { sheet = it ?: "new" })
        else -> MachineSheet(state.machines.firstOrNull { it.id == sh }, state.machines, actions.machine.copy(onDelete = { actions.machine.onDelete(it); sheet = "list" }),
            onDismiss = { sheet = "list" }, onDone = { sheet = "list" })
    } }
    val activeCount = state.connected.count { it.status == "ACTIVE" }
    SubScreen(stringResource(R.string.conn_title), if (state.hasKey) pluralStringResource(R.plurals.conn_apps_connected, activeCount, activeCount) else stringResource(R.string.conn_connect_your_apps), actions.onBack,
        actions = { if (state.hasKey) IconButton(onClick = actions.onRefresh) { Icon(Icons.Rounded.Refresh, stringResource(R.string.conn_refresh)) } }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { SectionHeader(stringResource(R.string.conn_built_in), stringResource(R.string.conn_built_in_sub), Modifier.padding(start = 4.dp, top = 4.dp)) }
            item {
                AppCard(padding = PaddingValues(vertical = 4.dp)) {
                    PhoneLine(Icons.Rounded.Language, stringResource(R.string.conn_browser), stringResource(R.string.conn_browser_sub), true, null)
                    PhoneLine(Icons.Rounded.IosShare, stringResource(R.string.conn_share), stringResource(R.string.conn_share_sub), true, null)
                    PhoneLine(Icons.Rounded.Notifications, stringResource(R.string.conn_notifications), if (state.notificationsAllowed) stringResource(R.string.conn_notifications_on) else stringResource(R.string.conn_notifications_off),
                        state.notificationsAllowed, if (state.notificationsAllowed) null else actions.onAllowNotifications)
                    Surface(onClick = actions.onNotifications, color = androidx.compose.ui.graphics.Color.Transparent) {
                        PhoneLine(Icons.Rounded.MarkEmailUnread, stringResource(R.string.conn_read_notifications), stringResource(R.string.conn_read_notifications_sub), false, null)
                    }
                    PhoneLine(Icons.Rounded.FolderOpen, stringResource(R.string.conn_files), if (state.phoneFiles) stringResource(R.string.conn_files_on) else stringResource(R.string.conn_files_off),
                        state.phoneFiles, if (state.phoneFiles) null else actions.onAllowPhoneFiles)
                    MachinesLine(state.machines) { sheet = "list" }
                }
            }
            if (state.hasKey) item { ProfilesSection(state.profiles, logos) { id -> sheet = "profile:" + (id ?: "new") } }
            item { SectionHeader(stringResource(R.string.conn_your_apps), if (state.consumer) stringResource(R.string.conn_your_apps_consumer) else stringResource(R.string.conn_your_apps_key), Modifier.padding(start = 4.dp, top = 16.dp)) }
            if (!state.hasKey) item {
                AppCard { ComposioKeyForm(actions.onSaveKey, actions.onOpenUrl) {} }
            } else {
                if (state.error != null) item { Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(4.dp)) }
                val active = state.connected
                val dupes = duplicateGroups(active)
                item { AppsSummary(active, logos, appsOpen) { appsOpen = !appsOpen } }
                if (appsOpen && dupes.isNotEmpty()) items(dupes, key = { "dupe-" + it.first().id }) { g ->
                    val name = state.toolkits.firstOrNull { it.slug == g.first().toolkit }?.name ?: prettySlug(g.first().toolkit)
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.ContentCopy, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.conn_dupe_title, name, g.size), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                                Text(identityOf(g.first()).ifBlank { stringResource(R.string.conn_same_sign_in) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                            }
                            FilledTonalButton(onClick = { actions.onKeepOne(g.first(), g.drop(1)) }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.conn_keep_one)) }
                        }
                    }
                }
                if (appsOpen && active.isNotEmpty()) item {
                    AppCard(padding = PaddingValues(vertical = 4.dp)) {
                        // One row per account, copies folded into the first (the card above offers to remove them).
                        val copies = dupes.flatMap { it.drop(1) }.map { it.id }.toSet()
                        active.filter { it.id !in copies }.forEach { c -> val tk = state.toolkits.firstOrNull { it.slug == c.toolkit }
                            ConnectedLine(c, tk?.name ?: prettySlug(c.toolkit), actions, tk?.logo.orEmpty(), state.profiles.filter { it.has(c.id) }.map { it.name },
                                state.rules[c.id] ?: com.past9.phoneaos.data.AccountRules.DEFAULT) { sheet = "account:" + c.id } }
                    }
                }
                if (appsOpen) item {
                    OutlinedTextField(query, { query = it }, placeholder = { Text(if (state.consumer || state.toolkits.isEmpty()) stringResource(R.string.conn_search_apps) else stringResource(R.string.conn_search_n_apps, state.toolkits.size)) }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), shape = MaterialTheme.shapes.extraLarge)
                }
                if (state.loading && appsOpen) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() } }
                val list = if (!appsOpen) emptyList() else state.toolkits.filter { query.isBlank() || it.name.contains(query, true) || it.slug.contains(query, true) || (state.searched == query.trim() && it.slug in state.hits) }
                items(list.take(150), key = { it.slug }) { t ->
                    val mine = state.connected.filter { it.toolkit == t.slug && it.status == "ACTIVE" }
                    // Another account only makes sense where the app says who each one is (two Gmails); otherwise one is enough.
                    ToolkitLine(t, mine.isNotEmpty(), canAddAnother = mine.isNotEmpty() && mine.all { identityOf(it).isNotBlank() }) { actions.onConnect(t.slug) } }
                // Composio Connect keys browse the popular apps; anything else is one search away.
                if (appsOpen && state.consumer && query.isNotBlank() && state.searched != query.trim()) item {
                    Surface(onClick = { actions.onSearchAll(query.trim()) }, enabled = !state.searching, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (state.searching) LoadingIndicator(Modifier.size(24.dp)) else Icon(Icons.Rounded.TravelExplore, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (state.searching) stringResource(R.string.conn_searching) else stringResource(R.string.conn_search_all, query.trim()), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                if (list.isEmpty() && !state.searching) Text(stringResource(R.string.conn_not_popular), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }
                }
                if (appsOpen && state.consumer && query.isNotBlank() && state.searched == query.trim() && list.isEmpty()) item {
                    Text(stringResource(R.string.conn_no_app, query.trim()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                }
                if (appsOpen) item { TextButton(onClick = actions.onRemoveKey, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.conn_remove_key), color = MaterialTheme.colorScheme.error) } }
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
        if (fix != null) FilledTonalButton(onClick = fix) { Text(stringResource(R.string.conn_allow)) }
        else if (on) Icon(Icons.Rounded.CheckCircle, stringResource(R.string.conn_on), tint = LocalExtra.current.success)
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
private fun ConnectedLine(c: Connection, name: String, actions: ConnectionsActions, logo: String = "", profiles: List<String> = emptyList(),
                          rules: com.past9.phoneaos.data.AccountRules = com.past9.phoneaos.data.AccountRules.DEFAULT, onOpen: () -> Unit = {}) {
    Surface(onClick = onOpen, color = androidx.compose.ui.graphics.Color.Transparent) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Initial(name, logo)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(identityOf(c).ifBlank { if (c.status == "ACTIVE") stringResource(R.string.conn_connected) else stringResource(R.string.conn_sign_in_unfinished) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ProfileTags(profiles, rules)
        }
        val extra = LocalExtra.current
        when (c.status) {
            "ACTIVE" -> StatusPill(stringResource(R.string.conn_connected), extra.successContainer, extra.success)
            "INITIATED", "INITIALIZING" -> StatusPill(stringResource(R.string.conn_finish_sign_in), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
            else -> StatusPill(c.status.lowercase().replaceFirstChar { it.uppercase() }, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        }
        Icon(Icons.Rounded.ChevronRight, stringResource(R.string.conn_rules_for, c.toolkit), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
    }
    }
}

@Composable
private fun ToolkitLine(t: Toolkit, connected: Boolean, canAddAnother: Boolean = connected, onConnect: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Initial(t.name, t.logo)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.titleSmall)
                Text(t.description.ifBlank { pluralStringResource(R.plurals.conn_actions, t.toolsCount, t.toolsCount) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            // Connected apps can take more accounts (work and personal Gmail, say).
            if (connected && canAddAnother) OutlinedButton(onClick = onConnect, shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.PersonAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.conn_add)) }
            else if (connected) StatusPill(stringResource(R.string.conn_connected), LocalExtra.current.successContainer, LocalExtra.current.success)
            else FilledTonalButton(onClick = onConnect, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.conn_connect)) }
        }
    }
}

private val known = mapOf("googlecalendar" to "Google Calendar", "googledrive" to "Google Drive", "googledocs" to "Google Docs", "googlesheets" to "Google Sheets", "github" to "GitHub", "linkedin" to "LinkedIn", "whatsapp" to "WhatsApp", "microsoft_teams" to "Microsoft Teams", "outlook" to "Outlook", "one_drive" to "OneDrive", "sharepoint" to "SharePoint")
fun prettySlug(slug: String) = known[slug] ?: slug.replace('_', ' ').replaceFirstChar { it.uppercase() }


/** Your apps, folded: how many are connected, a few of their logos (each app once), open to manage or add. */
@Composable
private fun AppsSummary(connected: List<Connection>, logos: Map<String, String>, open: Boolean, onToggle: () -> Unit) {
    val active = connected.filter { it.status == "ACTIVE" }
    val apps = active.map { it.toolkit }.distinct()
    val cs = MaterialTheme.colorScheme
    val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "fold")
    AppCard(onClick = onToggle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(androidx.compose.material3.MaterialShapes.Cookie9Sided.toShape()).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Apps, null, tint = cs.onPrimaryContainer)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (apps.isEmpty()) stringResource(R.string.conn_no_apps) else pluralStringResource(R.plurals.conn_apps_connected, apps.size, apps.size), style = MaterialTheme.typography.titleMedium)
                Text(if (apps.isEmpty()) stringResource(R.string.conn_summary_empty) else if (active.size > apps.size) stringResource(R.string.conn_summary_accounts, active.size) else stringResource(R.string.conn_summary_manage),
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ExpandMore, if (open) stringResource(R.string.conn_fold) else stringResource(R.string.conn_open), tint = cs.onSurfaceVariant, modifier = Modifier.graphicsLayer { rotationZ = turn })
        }
        if (apps.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                apps.take(6).forEach { slug ->
                    val n = active.count { it.toolkit == slug }
                    Box {
                        AppLogo(prettySlug(slug), logos[slug].orEmpty(), 40.dp)
                        if (n > 1) Surface(shape = androidx.compose.foundation.shape.CircleShape, color = cs.primary, modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp)) {
                            Text("$n", style = MaterialTheme.typography.labelSmall, color = cs.onPrimary, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                        }
                    }
                }
                if (apps.size > 6) Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(11.dp)).background(cs.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                    Text("+${apps.size - 6}", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                }
            }
        } else if (!open) {
            Spacer(Modifier.height(14.dp))
            FilledTonalButton(onClick = onToggle, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.conn_connect_an_app))
            }
        }
    }
}
