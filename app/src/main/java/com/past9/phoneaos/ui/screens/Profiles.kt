package com.past9.phoneaos.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material3.toShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.data.AccountRef
import com.past9.phoneaos.data.AccountRules
import com.past9.phoneaos.data.AgentProfile
import com.past9.phoneaos.data.Rule
import com.past9.phoneaos.tools.Connection
import com.past9.phoneaos.ui.AppCard
import com.past9.phoneaos.ui.AppSheet
import com.past9.phoneaos.ui.SectionHeader
import com.past9.phoneaos.ui.SheetField
import com.past9.phoneaos.ui.theme.Eyebrow

/** What the profile and rules UI needs to do things. */
data class AccountProfileActions(
    val onCreate: (name: String, accounts: List<AccountRef>, browser: String, color: String?) -> AgentProfile? = { _, _, _, _ -> null },
    val onSave: (AgentProfile) -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onMember: (profileId: String, account: AccountRef, member: Boolean) -> Unit = { _, _, _ -> },
    val onRules: (accountId: String, rules: AccountRules) -> Unit = { _, _ -> },
)

fun Connection.ref() = AccountRef(id, toolkit, label)

/** "work@northwind.example" for an account, or the app's name when Composio has no email for it. */
private fun AccountRef.line(appName: String) = label.takeIf { it.isNotBlank() && !it.equals(slug, true) } ?: appName

// ---- the Profiles section on Connections ---------------------------------------------------------

@Composable
fun ProfilesSection(profiles: List<AgentProfile>, logos: Map<String, String>, onOpen: (String?) -> Unit) {
    SectionHeader("Profiles", "Group accounts by part of your life. A helper given a profile can only use its accounts.", Modifier.padding(start = 4.dp, top = 16.dp, bottom = 2.dp))
    if (profiles.isEmpty()) {
        AppCard(onClick = { onOpen(null) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(MaterialShapes.Clover4Leaf.toShape()).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Workspaces, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Make your first profile", style = MaterialTheme.typography.titleMedium)
                    Text("Say “Work” with Slack, Outlook and Teams, or “Personal” with your own Gmail.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(14.dp))
            FilledTonalButton(onClick = { onOpen(null) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("New profile")
            }
        }
        return
    }
    AppCard(padding = PaddingValues(vertical = 4.dp)) {
        profiles.forEach { p -> ProfileLine(p, logos) { onOpen(p.id) } }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Surface(onClick = { onOpen(null) }, color = androidx.compose.ui.graphics.Color.Transparent) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Text("New profile", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ProfileLine(p: AgentProfile, logos: Map<String, String>, onClick: () -> Unit) {
    Surface(onClick = onClick, color = androidx.compose.ui.graphics.Color.Transparent) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            ProfileBadge(p.name, p.color)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(when (p.accounts.size) { 0 -> "No accounts"; 1 -> "1 account"; else -> "${p.accounts.size} accounts" },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            LogoStack(p.accounts.map { it.slug }.distinct(), logos)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A profile's colour as a container and the colour on it, tuned for this mode (light or dark). */
@Composable
fun profileTones(color: String): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
    val a = com.past9.phoneaos.ui.theme.accentOf(color)
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) a.dPC to a.dOnPC else a.lPC to a.lOnPC
}

/** A profile's mark: its initial in its own colour and one of the app's expressive shapes (always the same one for a profile). */
@Composable
fun ProfileBadge(name: String, color: String, size: Dp = 44.dp) {
    val shapes = listOf(MaterialShapes.Cookie9Sided, MaterialShapes.Clover4Leaf, MaterialShapes.Sunny, MaterialShapes.Puffy, MaterialShapes.Gem, MaterialShapes.SoftBurst, MaterialShapes.Flower)
    val shape = shapes[Math.floorMod(name.trim().lowercase().hashCode(), shapes.size)].toShape()
    val (bg, fg) = profileTones(color)
    Box(Modifier.size(size).clip(shape).background(bg), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase().ifBlank { "?" }, style = if (size < 32.dp) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleMedium, color = fg)
    }
}

/** Pick a profile's colour: the app's palette as big round swatches, the chosen one ringed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorSwatches(selected: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AgentProfile.COLORS.forEach { c ->
            val (bg, fg) = profileTones(c)
            val a = com.past9.phoneaos.ui.theme.accentOf(c)
            val on = c == selected
            Box(Modifier.size(44.dp).clip(CircleShape).background(if (on) MaterialTheme.colorScheme.onSurface else androidx.compose.ui.graphics.Color.Transparent).padding(3.dp)
                .clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(3.dp).clip(CircleShape).background(bg)
                .clickable { onPick(c) }, contentAlignment = Alignment.Center) {
                if (on) Icon(Icons.Rounded.Check, "${a.label}, chosen", tint = fg, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Up to three of the profile's app logos, the same tiles as everywhere else, plus how many more. */
@Composable
private fun LogoStack(slugs: List<String>, logos: Map<String, String>) {
    if (slugs.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        slugs.take(3).forEach { s -> AppLogo(prettySlug(s), logos[s].orEmpty(), 28.dp) }
        if (slugs.size > 3) Text("+${slugs.size - 3}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- one profile: name, its accounts, its browser ------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileSheet(profile: AgentProfile?, accounts: List<AccountRef>, logos: Map<String, String>, browserProfiles: List<String>, actions: AccountProfileActions, onDismiss: () -> Unit) {
    var name by remember(profile?.id) { mutableStateOf(profile?.name.orEmpty()) }
    var chosen by remember(profile?.id) { mutableStateOf(profile?.accounts?.map { it.id }?.toSet().orEmpty()) }
    var browser by remember(profile?.id) { mutableStateOf(profile?.browser.orEmpty()) }
    var color by remember(profile?.id) { mutableStateOf(profile?.color ?: AgentProfile.COLORS.first()) }
    // Accounts that left Composio still show (so they can be taken out), after the live ones.
    val all = (accounts + profile?.accounts.orEmpty()).distinctBy { it.id }
    AppSheet(Icons.Rounded.Workspaces, if (profile == null) "New profile" else "Profile", if (profile == null) (name.ifBlank { "Name it" }) else name.ifBlank { profile.name }, onDismiss,
        primary = if (profile == null) "Create profile" else "Save", primaryEnabled = name.isNotBlank(),
        onPrimary = {
            val picked = all.filter { it.id in chosen }
            if (profile == null) actions.onCreate(name.trim(), picked, browser, color) else actions.onSave(profile.copy(name = name.trim(), accounts = picked, browser = browser, color = color))
            onDismiss()
        },
        secondary = if (profile != null) ({ TextButton(onClick = { actions.onDelete(profile.id); onDismiss() }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("Delete profile", color = MaterialTheme.colorScheme.error) } }) else null,
    ) {
        SheetField(name, { name = it }, "Name", "Northwind work", big = true, singleLine = true)
        Spacer(Modifier.height(24.dp))
        Text("COLOUR", style = Eyebrow, color = MaterialTheme.colorScheme.primary)
        Text("Helpers working in this profile show in it, so you can tell them apart at a glance.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        ColorSwatches(color) { color = it }
        Spacer(Modifier.height(24.dp))
        Text("ACCOUNTS", style = Eyebrow, color = MaterialTheme.colorScheme.primary)
        Text("Helpers given this profile can use these and nothing else.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        if (all.isEmpty()) Text("Connect an app below first, then add it here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                all.forEach { a ->
                    val on = a.id in chosen
                    Surface(onClick = { chosen = if (on) chosen - a.id else chosen + a.id }, color = androidx.compose.ui.graphics.Color.Transparent) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppLogo(prettySlug(a.slug), logos[a.slug].orEmpty(), 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(prettySlug(a.slug), style = MaterialTheme.typography.titleSmall)
                                Text(a.line(prettySlug(a.slug)).takeIf { it != prettySlug(a.slug) } ?: "Connected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Checkbox(checked = on, onCheckedChange = { chosen = if (it) chosen + a.id else chosen - a.id })
                        }
                    }
                }
            }
        }
        if (browserProfiles.size > 1 || browser.isNotBlank()) {
            Spacer(Modifier.height(24.dp))
            Text("BROWSER SIGN-INS", style = Eyebrow, color = MaterialTheme.colorScheme.primary)
            Text("Which of your browser profiles its helpers browse as.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf("") + browserProfiles).forEach { b ->
                    FilterChip(selected = browser == b, onClick = { browser = b }, label = { Text(b.ifBlank { "Any" }, style = MaterialTheme.typography.labelLarge) },
                        leadingIcon = if (browser == b) ({ Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }) else null, shape = RoundedCornerShape(50), modifier = Modifier.height(40.dp))
                }
            }
        }
    }
}

// ---- one account: what the agent may do there, and which profiles it is in -------------------------

/** Allowed / Ask me / Never, as one segmented control. */
@Composable
fun RuleRow(title: String, line: String, rule: Rule, onRule: (Rule) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Rule.entries.forEachIndexed { i, r ->
                SegmentedButton(selected = rule == r, onClick = { onRule(r) }, shape = SegmentedButtonDefaults.itemShape(i, Rule.entries.size), modifier = Modifier.heightIn(min = 48.dp),
                    colors = if (r == Rule.NEVER) SegmentedButtonDefaults.colors(activeContainerColor = MaterialTheme.colorScheme.errorContainer, activeContentColor = MaterialTheme.colorScheme.onErrorContainer) else SegmentedButtonDefaults.colors()) {
                    Text(r.label, maxLines = 1)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileChips(account: AccountRef, profiles: List<AgentProfile>, actions: AccountProfileActions) {
    var adding by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        profiles.forEach { p ->
            val on = p.has(account.id)
            FilterChip(selected = on, onClick = { actions.onMember(p.id, account, !on) }, label = { Text(p.name, style = MaterialTheme.typography.labelLarge) },
                leadingIcon = if (on) ({ Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }) else null, shape = RoundedCornerShape(50), modifier = Modifier.height(40.dp))
        }
        if (!adding) AssistChip(onClick = { adding = true }, label = { Text("New profile", style = MaterialTheme.typography.labelLarge) },
            leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)) }, shape = RoundedCornerShape(50), modifier = Modifier.height(40.dp))
    }
    if (adding) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) { SheetField(newName, { newName = it }, "New profile", "Personal", singleLine = true) }
            Spacer(Modifier.width(10.dp))
            FilledIconButton(onClick = { if (newName.isNotBlank()) { actions.onCreate(newName.trim(), listOf(account), "", null); newName = ""; adding = false } },
                enabled = newName.isNotBlank(), modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.Check, "Add profile") }
        }
    }
}

@Composable
fun AccountSheet(account: AccountRef, logo: String, rules: AccountRules, profiles: List<AgentProfile>, actions: AccountProfileActions, onDisconnect: () -> Unit, onDismiss: () -> Unit) {
    val app = prettySlug(account.slug)
    AppSheet(Icons.Rounded.Shield, "Account rules", app, onDismiss, primary = "Done", primaryEnabled = true, onPrimary = onDismiss,
        secondary = { TextButton(onClick = { onDisconnect(); onDismiss() }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("Disconnect this account", color = MaterialTheme.colorScheme.error) } }) {
        AccountHeader(account, app, logo, "Your agent and its helpers follow these rules here, whoever is asking.")
        Spacer(Modifier.height(24.dp))
        Text("WHAT IT MAY DO", style = Eyebrow, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        RuleRow("Read", "Search, open and list things", rules.read) { actions.onRules(account.id, rules.copy(read = it)) }
        Spacer(Modifier.height(20.dp))
        RuleRow("Send and change", "Send, post, reply, create, move or delete", rules.change) { actions.onRules(account.id, rules.copy(change = it)) }
        Spacer(Modifier.height(28.dp))
        Text("PROFILES", style = Eyebrow, color = MaterialTheme.colorScheme.primary)
        Text("The parts of your life this account belongs to. It can be in several.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        ProfileChips(account, profiles, actions)
    }
}

/** The account in one line: its logo, the email it signed in as (wrapping by character only if it must), and a note. */
@Composable
private fun AccountHeader(account: AccountRef, app: String, logo: String, note: String?) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AppLogo(app, logo, 40.dp); Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(account.line(app), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Right after an app is connected: which profiles is it for, and may the agent send from it without asking? */
@Composable
fun AddToProfileSheet(account: AccountRef, logo: String, rules: AccountRules, profiles: List<AgentProfile>, actions: AccountProfileActions, onDismiss: () -> Unit) {
    val app = prettySlug(account.slug)
    AppSheet(Icons.Rounded.Link, "Just connected", app, onDismiss, primary = "Done", primaryEnabled = true, onPrimary = onDismiss,
        secondary = { TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("Not now") } }) {
        AccountHeader(account, app, logo, null)
        Spacer(Modifier.height(24.dp))
        Text("Which part of your life is it for?", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        ProfileChips(account, profiles, actions)
        Spacer(Modifier.height(28.dp))
        RuleRow("Send and change", "Can your agent send, post or delete here without asking you?", rules.change) { actions.onRules(account.id, rules.copy(change = it)) }
    }
}

/** On an account row: the profiles it's in, as small tags. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileTags(names: List<String>, rules: AccountRules) {
    if (names.isEmpty() && rules == AccountRules.DEFAULT) return
    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        names.forEach { n ->
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.tertiaryContainer) {
                Text(n, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp), maxLines = 1)
            }
        }
        val note = when {
            rules.read == Rule.NEVER && rules.change == Rule.NEVER -> "Off limits"
            rules.change == Rule.ALLOW -> "Sends without asking"
            rules.change == Rule.NEVER -> "Never sends"
            rules.read == Rule.ASK -> "Asks to read"
            else -> null
        }
        if (note != null) Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Text(note, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp), maxLines = 1)
        }
    }
}
