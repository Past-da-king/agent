package com.past9.phoneaos.ui.screens

import androidx.compose.ui.res.stringResource
import com.past9.phoneaos.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.machines.Machine
import com.past9.phoneaos.machines.MachineAuth
import com.past9.phoneaos.ui.AppSheet
import com.past9.phoneaos.ui.ChoiceCard
import com.past9.phoneaos.ui.ChoiceChips
import com.past9.phoneaos.ui.SheetField
import com.past9.phoneaos.ui.StatusPill
import com.past9.phoneaos.ui.theme.Eyebrow
import com.past9.phoneaos.ui.theme.LocalExtra
import kotlinx.coroutines.launch

data class MachineActions(
    /** Save; returns the stored machine. Blank secrets keep the saved ones. [newKey] makes a fresh key. */
    val onSave: suspend (m: Machine, password: String, privateKey: String, passphrase: String, newKey: Boolean) -> Machine = { m, _, _, _, _ -> m },
    /** Connect and sign in. Null when it worked, otherwise why not. */
    val onTest: suspend (Machine) -> String? = { null },
    val onDelete: (String) -> Unit = {},
    val onCopy: (String) -> Unit = {},
    val publicKey: (String) -> String? = { null },
    val reload: (String) -> Machine? = { null },
)

/** A machine's icon from what it told us it is. */
fun machineIcon(m: Machine): ImageVector = when {
    m.about.contains("Windows", true) || m.about.startsWith("Microsoft", true) -> Icons.Rounded.Computer
    m.about.contains("Darwin", true) -> Icons.Rounded.LaptopMac
    else -> Icons.Rounded.Dns
}

/** The Machines line inside the "built in" card on Connections. Opens the machines sheet. */
@Composable
fun MachinesLine(machines: List<Machine>, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Dns, null, tint = cs.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.machines_title), style = MaterialTheme.typography.titleSmall)
            Text(if (machines.isEmpty()) stringResource(R.string.machines_line_empty) else machines.joinToString(" · ") { it.name },
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (machines.isNotEmpty()) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                Text("${machines.size}", style = MaterialTheme.typography.labelLarge, color = cs.onPrimaryContainer)
            }
            Spacer(Modifier.width(4.dp))
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = cs.onSurfaceVariant)
    }
}

/** Slides up from Connections: every machine, whether it's reachable right now, and Add. */
@Composable
fun MachinesSheet(machines: List<Machine>, actions: MachineActions, onDismiss: () -> Unit, onOpen: (String?) -> Unit, checkOnOpen: Boolean = true,
                  initialStatus: Map<String, String?> = emptyMap()) {
    // id -> null while checking, "" reachable, otherwise why not.
    val status = remember { mutableStateMapOf<String, String?>().apply { putAll(initialStatus) } }
    val checked = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(machines.map { it.id }) {
        if (!checkOnOpen) return@LaunchedEffect
        machines.filter { checked[it.id] != true }.forEach { m -> checked[m.id] = true; status[m.id] = null; launch { status[m.id] = actions.onTest(m) ?: "" } }
    }
    AppSheet(Icons.Rounded.Dns, stringResource(R.string.machines_eyebrow), stringResource(R.string.machines_your), onDismiss, primary = stringResource(R.string.machines_add), primaryEnabled = true, onPrimary = { onOpen(null) },
        shape = androidx.compose.material3.MaterialShapes.Cookie7Sided) {
        if (machines.isEmpty()) {
            Text(stringResource(R.string.machines_empty),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Hint(Icons.Rounded.Cloud, stringResource(R.string.machines_hint_vps), stringResource(R.string.machines_hint_vps_sub))
            Hint(Icons.Rounded.School, stringResource(R.string.machines_hint_uni), stringResource(R.string.machines_hint_uni_sub))
            Hint(Icons.Rounded.Computer, stringResource(R.string.machines_hint_own), stringResource(R.string.machines_hint_own_sub))
        } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            machines.forEach { m -> MachineTile(m, status[m.id], checked[m.id] == true || m.id in initialStatus) { onOpen(m.id) } }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.machines_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Hint(icon: ImageVector, title: String, line: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MachineTile(m: Machine, status: String?, checked: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme; val extra = LocalExtra.current
    Surface(onClick = onClick, shape = RoundedCornerShape(24.dp), color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(machineIcon(m), null, tint = cs.onPrimaryContainer)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (m.trusted) { Spacer(Modifier.width(6.dp)); Icon(Icons.Rounded.Bolt, stringResource(R.string.machines_runs_without_asking), Modifier.size(16.dp), tint = cs.tertiary) }
                }
                Text(m.about.ifBlank { stringResource(R.string.machines_not_checked) }, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            when {
                !checked -> {}
                status == null -> LoadingIndicator(Modifier.size(28.dp))
                status.isEmpty() -> StatusPill(stringResource(R.string.machines_online), extra.successContainer, extra.success)
                else -> StatusPill(stringResource(R.string.machines_cant_reach), cs.errorContainer, cs.onErrorContainer)
            }
        }
    }
}

/** Add or edit one machine, in the same slide-up style as every other sheet in the app. */
@Composable
fun MachineSheet(existing: Machine?, others: List<Machine>, actions: MachineActions, onDismiss: () -> Unit, onDone: () -> Unit,
                 initialResult: String? = null) {
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var saved by remember { mutableStateOf(existing) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var port by remember { mutableStateOf((existing?.port ?: 22).toString()) }
    var user by remember { mutableStateOf(existing?.user ?: "") }
    var auth by remember { mutableStateOf(existing?.auth ?: MachineAuth.PASSWORD) }
    var password by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var via by remember { mutableStateOf(existing?.via) }
    var trusted by remember { mutableStateOf(existing?.trusted ?: false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf(initialResult) } // "" = connected
    var confirmDelete by remember { mutableStateOf(false) }
    val pub = saved?.let { actions.publicKey(it.id) }
    val editing = existing != null
    val needsSecret = when (auth) { MachineAuth.PASSWORD -> !editing && password.isEmpty(); MachineAuth.KEY -> !editing && key.isBlank(); MachineAuth.NEW_KEY -> false }
    val ready = host.isNotBlank() && user.isNotBlank() && (port.toIntOrNull() ?: 0) in 1..65535 && !needsSecret && !busy
    val waitingForKey = auth == MachineAuth.NEW_KEY && pub == null
    // Already connected once: the key is in place, so keep its card out of the way unless asked for.
    val proven = existing?.hostKey != null
    var showKey by remember { mutableStateOf(false) }
    val plain = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)

    fun connect() = scope.launch {
        busy = true; result = null
        val p = port.toIntOrNull() ?: 22
        val m = Machine(saved?.id ?: java.util.UUID.randomUUID().toString().take(8), name.trim().ifBlank { host.trim().substringBefore('.') }, host.trim(), p, user.trim(), auth, via, trusted,
            saved?.hostKey?.takeIf { saved?.host == host.trim() && saved?.port == p }, saved?.about ?: "")
        val makeKey = auth == MachineAuth.NEW_KEY && actions.publicKey(m.id) == null
        val stored = actions.onSave(m, password, key, passphrase, makeKey)
        saved = stored; password = ""; key = ""; passphrase = ""
        if (!makeKey) { result = actions.onTest(stored) ?: ""; saved = actions.reload(stored.id) ?: stored }
        busy = false
    }

    AppSheet(if (saved != null) machineIcon(saved!!) else Icons.Rounded.Dns, if (editing) stringResource(R.string.machines_machine) else stringResource(R.string.machines_eyebrow), if (editing) existing!!.name else stringResource(R.string.machines_new), onDismiss,
        primary = when { busy -> stringResource(R.string.machines_connecting); result == "" -> stringResource(R.string.machines_done); waitingForKey -> stringResource(R.string.machines_make_key); proven -> stringResource(R.string.machines_save); auth == MachineAuth.NEW_KEY && result == null -> stringResource(R.string.machines_added_connect); else -> stringResource(R.string.machines_connect) },
        primaryEnabled = ready || result == "", onPrimary = { if (result == "") onDone() else connect() },
        shape = androidx.compose.material3.MaterialShapes.Cookie7Sided,
        secondary = if (editing) ({
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 4.dp)) { Text(stringResource(R.string.machines_remove_this), color = cs.error) }
        }) else null) {
        Column(Modifier.animateContentSize()) {

            SheetField(name, { name = it }, stringResource(R.string.machines_name), stringResource(R.string.machines_name_hint), big = true, singleLine = true)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetField(host, { host = it.trim(); result = null }, stringResource(R.string.machines_address), "server.example.com", singleLine = true, modifier = Modifier.weight(1f),
                    keyboard = plain.copy(keyboardType = KeyboardType.Uri))
                SheetField(port, { port = it.filter(Char::isDigit).take(5); result = null }, stringResource(R.string.machines_port), "22", singleLine = true, modifier = Modifier.width(92.dp),
                    keyboard = plain.copy(keyboardType = KeyboardType.Number))
            }
            Spacer(Modifier.height(16.dp))
            SheetField(user, { user = it.trim(); result = null }, stringResource(R.string.machines_username), stringResource(R.string.machines_username_hint), singleLine = true, keyboard = plain)

            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.machines_sign_in_with), style = Eyebrow, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceCard(Icons.Rounded.Password, stringResource(R.string.machines_password), stringResource(R.string.machines_password_sub), auth == MachineAuth.PASSWORD, { auth = MachineAuth.PASSWORD; result = null }, Modifier.weight(1f))
                ChoiceCard(Icons.Rounded.Key, stringResource(R.string.machines_your_key), stringResource(R.string.machines_your_key_sub), auth == MachineAuth.KEY, { auth = MachineAuth.KEY; result = null }, Modifier.weight(1f))
                ChoiceCard(Icons.Rounded.AutoAwesome, stringResource(R.string.machines_new_key), stringResource(R.string.machines_new_key_sub), auth == MachineAuth.NEW_KEY, { auth = MachineAuth.NEW_KEY; result = null }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            when (auth) {
                MachineAuth.PASSWORD -> SheetField(password, { password = it; result = null }, stringResource(R.string.machines_password), if (editing) stringResource(R.string.machines_saved_keep) else "••••••••", singleLine = true, secret = true, keyboard = plain.copy(keyboardType = KeyboardType.Password))
                MachineAuth.KEY -> {
                    SheetField(key, { key = it; result = null }, stringResource(R.string.machines_private_key), if (editing) stringResource(R.string.machines_saved_keep) else "-----BEGIN OPENSSH PRIVATE KEY-----", minLines = 3, maxLines = 6, mono = true, keyboard = plain)
                    Spacer(Modifier.height(12.dp))
                    SheetField(passphrase, { passphrase = it; result = null }, stringResource(R.string.machines_passphrase), "", singleLine = true, secret = true, keyboard = plain.copy(keyboardType = KeyboardType.Password))
                }
                MachineAuth.NEW_KEY -> if (pub == null) Text(stringResource(R.string.machines_new_key_explain),
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
                    else if (proven && !showKey) TextButton(onClick = { showKey = true }) { Icon(Icons.Rounded.Key, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.machines_show_public_key)) }
                    else if (result != "") PublicKeyCard(pub, actions.onCopy)
            }

            val gateways = others.filter { it.id != existing?.id }
            if (gateways.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                ChoiceChips(listOf(Triple("", stringResource(R.string.machines_direct), Icons.Rounded.ArrowOutward)) + gateways.map { Triple(it.id, stringResource(R.string.machines_through, it.name), Icons.Rounded.AltRoute) }, via ?: "", { via = it.ifBlank { null } }, label = stringResource(R.string.machines_reach_it))
            }

            Spacer(Modifier.height(24.dp))
            Surface(onClick = { trusted = !trusted }, shape = RoundedCornerShape(24.dp), color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (trusted) Icons.Rounded.Bolt else Icons.Rounded.VerifiedUser, null, tint = if (trusted) cs.tertiary else cs.primary)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.machines_run_without_asking), style = MaterialTheme.typography.titleSmall)
                        Text(if (trusted) stringResource(R.string.machines_trusted_on) else stringResource(R.string.machines_trusted_off), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(trusted, { trusted = it })
                }
            }
            // The outcome sits right above the button that caused it.
            when {
                result == "" -> { Spacer(Modifier.height(16.dp)); ResultCard(true, stringResource(R.string.machines_connected), saved?.let { s -> listOfNotNull(s.about.takeIf { it.isNotBlank() }, s.hostKey?.let { stringResource(R.string.machines_identity, it.take(22)) }).joinToString(" · ") } ?: "") }
                result != null -> { Spacer(Modifier.height(16.dp)); ResultCard(false, stringResource(R.string.machines_couldnt_connect), result!!) }
            }
        }
    }
    if (confirmDelete && existing != null) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.machines_remove_confirm, existing.name)) }, text = { Text(stringResource(R.string.machines_remove_confirm_body)) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; actions.onDelete(existing.id) }) { Text(stringResource(R.string.machines_remove), color = cs.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.machines_cancel)) } })
}

@Composable
private fun PublicKeyCard(pub: String, onCopy: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var copied by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(24.dp), color = cs.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(stringResource(R.string.machines_one_step), style = Eyebrow, color = cs.onPrimaryContainer)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.machines_one_step_body), style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer)
            Spacer(Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceContainerLowest, modifier = Modifier.fillMaxWidth()) {
                Text(pub, Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = cs.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = { onCopy(pub); copied = true }, shapes = ButtonDefaults.shapes()) {
                Icon(if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (copied) stringResource(R.string.machines_copied) else stringResource(R.string.machines_copy_public_key))
            }
        }
    }
}

@Composable
private fun ResultCard(ok: Boolean, title: String, body: String) {
    val cs = MaterialTheme.colorScheme; val extra = LocalExtra.current
    Surface(shape = RoundedCornerShape(24.dp), color = if (ok) extra.successContainer else cs.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null, tint = if (ok) extra.success else cs.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, color = if (ok) extra.success else cs.onErrorContainer)
                if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.bodySmall, color = if (ok) cs.onSurface else cs.onErrorContainer)
            }
        }
    }
}
