package com.past9.phoneaos.ui.screens

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.past9.phoneaos.R
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
    SectionHeader(stringResource(R.string.profiles_title), stringResource(R.string.profiles_sub), Modifier.padding(start = 4.dp, top = 16.dp, bottom = 2.dp))
    if (profiles.isEmpty()) {
        AppCard(onClick = { onOpen(null) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(MaterialShapes.Clover4Leaf.toShape()).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Workspaces, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.profiles_first), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.profiles_first_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(14.dp))
            FilledTonalButton(onClick = { onOpen(null) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.profiles_new))
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
                Text(stringResource(R.string.profiles_new), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
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
                Text(if (p.accounts.isEmpty()) stringResource(R.string.profiles_no_accounts) else pluralStringResource(R.plurals.profiles_accounts, p.accounts.size, p.accounts.size),
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
                if (on) Icon(Icons.Rounded.Check, stringResource(R.string.profiles_color_chosen, a.label), tint = fg, modifier = Modifier.size(20.dp))
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
    AppSheet(Icons.Rounded.Workspaces, if (profile == null) stringResource(R.string.profiles_new) else stringResource(R.string.profiles_profile), if (profile == null) (name.ifBlank { stringResource(R.string.profiles_name_it) }) else name.ifBlank { profile.name }, onDismiss,
        primary = if (profile == null) stringResource(R.string.profiles_create) else stringResource(R.string.profiles_save), primaryEnabled = name.isNotBlank(),
        onPrimary = {
            val picked = all.filter { it.id in chosen }
            if (profile == null) actions.onCreate(name.trim(), picked, browser, color) else actions.onSave(profile.copy(name = name.trim(), accounts = picked, browser = browser, color = color))
            onDismiss()
        },
        secondary = if (profile != null) ({ TextButton(onClick = { actions.onDelete(profile.id); onDismiss() }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text(stringResource(R.string.profiles_delete), color = MaterialTheme.colorScheme.error) } }) else null,
    ) {
        SheetField(name, { name = it }, stringResource(R.string.profiles_name), stringResource(R.string.profiles_name_hint), big = true, singleLine = true)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.profiles_colour), style = Eyebrow, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.profiles_colour_sub), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        ColorSwatches(color) { color = it }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.profiles_accounts_eyebrow), style = Eyebrow, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.profiles_accounts_sub), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        if (all.isEmpty()) Text(stringResource(R.string.profiles_connect_first), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
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
                                Text(a.line(prettySlug(a.slug)).takeIf { it != prettySlug(a.slug) } ?: stringResource(R.string.profiles_connected), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Checkbox(checked = on, onCheckedChange = { chosen = if (it) chosen + a.id else chosen - a.id })
                        }
                    }
                }
            }
        }
        if (browserProfiles.size > 1 || browser.isNotBlank()) {
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.profiles_browser), style = Eyebrow, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.profiles_browser_sub), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf("") + browserProfiles).forEach { b ->
                    FilterChip(selected = browser == b, onClick = { browser = b }, label = { Text(b.ifBlank { stringResource(R.string.profiles_any) }, style = MaterialTheme.typography.labelLarge) },
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
                    Text(when (r) { Rule.ALLOW -> stringResource(R.string.profiles_rule_allow); Rule.ASK -> stringResource(R.string.profiles_rule_ask); Rule.NEVER -> stringResource(R.string.profiles_rule_never) }, maxLines = 1)
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
        if (!adding) AssistChip(onClick = { adding = true }, label = { Text(stringResource(R.string.profiles_new), style = MaterialTheme.typography.labelLarge) },
            leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)) }, shape = RoundedCornerShape(50), modifier = Modifier.height(40.dp))
    }
    if (adding) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) { SheetField(newName, { newName = it }, stringResource(R.string.profiles_new), stringResource(R.string.profiles_new_profile_hint), singleLine = true) }
            Spacer(Modifier.width(10.dp))
            FilledIconButton(onClick = { if (newName.isNotBlank()) { actions.onCreate(newName.trim(), listOf(account), "", null); newName = ""; adding = false } },
                enabled = newName.isNotBlank(), modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.Check, stringResource(R.string.profiles_add_profile)) }
        }
    }
}

@Composable
fun AccountSheet(account: AccountRef, logo: String, rules: AccountRules, profiles: List<AgentProfile>, actions: AccountProfileActions, onDisconnect: () -> Unit, onDismiss: () -> Unit) {
    val app = prettySlug(account.slug)
    AppSheet(Icons.Rounded.Shield, stringResource(R.string.profiles_account_rules), app, onDismiss, primary = stringResource(R.string.profiles_done), primaryEnabled = true, onPrimary = onDismiss,
        secondary = { TextButton(onClick = { onDisconnect(); onDismiss() }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text(stringResource(R.string.profiles_disconnect), color = MaterialTheme.colorScheme.error) } }) {
        AccountHeader(account, app, logo, stringResource(R.string.profiles_rules_note))
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.profiles_may_do), style = Eyebrow, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        RuleRow(stringResource(R.string.profiles_read), stringResource(R.string.profiles_read_sub), rules.read) { actions.onRules(account.id, rules.copy(read = it)) }
        Spacer(Modifier.height(20.dp))
        RuleRow(stringResource(R.string.profiles_change), stringResource(R.string.profiles_change_sub), rules.change) { actions.onRules(account.id, rules.copy(change = it)) }
        Spacer(Modifier.height(28.dp))
        Text(stringResource(R.string.profiles_profiles_eyebrow), style = Eyebrow, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.profiles_profiles_sub), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    AppSheet(Icons.Rounded.Link, stringResource(R.string.profiles_just_connected), app, onDismiss, primary = stringResource(R.string.profiles_done), primaryEnabled = true, onPrimary = onDismiss,
        secondary = { TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text(stringResource(R.string.profiles_not_now)) } }) {
        AccountHeader(account, app, logo, null)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.profiles_which_part), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        ProfileChips(account, profiles, actions)
        Spacer(Modifier.height(28.dp))
        RuleRow(stringResource(R.string.profiles_change), stringResource(R.string.profiles_change_ask), rules.change) { actions.onRules(account.id, rules.copy(change = it)) }
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
            rules.read == Rule.NEVER && rules.change == Rule.NEVER -> stringResource(R.string.profiles_off_limits)
            rules.change == Rule.ALLOW -> stringResource(R.string.profiles_sends_without_asking)
            rules.change == Rule.NEVER -> stringResource(R.string.profiles_never_sends)
            rules.read == Rule.ASK -> stringResource(R.string.profiles_asks_to_read)
            else -> null
        }
        if (note != null) Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Text(note, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp), maxLines = 1)
        }
    }
}
