package com.past9.phoneaos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.machines.Machine
import com.past9.phoneaos.machines.MachineAuth
import com.past9.phoneaos.ui.AppCard
import com.past9.phoneaos.ui.SectionHeader
import com.past9.phoneaos.ui.StatusPill
import com.past9.phoneaos.ui.SubScreen
import com.past9.phoneaos.ui.theme.Eyebrow
import com.past9.phoneaos.ui.theme.LocalExtra
import kotlinx.coroutines.launch

/** The Machines block on the Connections screen. */
fun androidx.compose.foundation.lazy.LazyListScope.machinesSection(machines: List<Machine>, onOpen: (String?) -> Unit) {
    item { SectionHeader("Machines", "Servers and computers your agent can work on. It sends them the heavy jobs.", Modifier.padding(start = 4.dp, top = 16.dp)) }
    if (machines.isNotEmpty()) item {
        AppCard(padding = PaddingValues(vertical = 4.dp)) { machines.forEach { m -> MachineLine(m) { onOpen(m.id) } } }
    }
    item {
        Surface(onClick = { onOpen(null) }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Add a machine", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(if (machines.isEmpty()) "Any server or computer you can SSH into: a VPS, a uni server, your PC" else "Another server or computer", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    }
}

@Composable
private fun MachineLine(m: Machine, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Dns, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(m.name, style = MaterialTheme.typography.titleSmall)
            Text(listOf(m.address, m.about).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (m.trusted) StatusPill("Trusted", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

data class MachineActions(
    val onBack: () -> Unit = {},
    /** Save; returns the stored machine. Blank secrets keep the saved ones. [newKey] makes a fresh key. */
    val onSave: suspend (m: Machine, password: String, privateKey: String, passphrase: String, newKey: Boolean) -> Machine = { m, _, _, _, _ -> m },
    /** Connect and sign in. Null when it worked, otherwise why not. */
    val onTest: suspend (Machine) -> String? = { null },
    val onDelete: (String) -> Unit = {},
    val onCopy: (String) -> Unit = {},
    val publicKey: (String) -> String? = { null },
    val reload: (String) -> Machine? = { null },
)

/** Add or edit one machine: address, how to sign in, an optional gateway, and whether it may act without asking. */
@Composable
fun MachineScreen(existing: Machine?, others: List<Machine>, actions: MachineActions, initialResult: String? = null) {
    val scope = rememberCoroutineScope()
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
    val cs = MaterialTheme.colorScheme

    SubScreen(if (editing) existing!!.name else "Add a machine", if (editing) existing!!.address else "Any computer or server you can SSH into", actions.onBack,
        actions = { if (editing) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.DeleteOutline, "Remove machine") } }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, placeholder = { Text("e.g. Build server") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(host, { host = it.trim(); result = null }, label = { Text("Address") }, placeholder = { Text("server.example.com or 10.0.0.5") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium)
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5); result = null }, label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(96.dp), shape = MaterialTheme.shapes.medium)
            }
            OutlinedTextField(user, { user = it.trim(); result = null }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)

            Text("SIGN IN WITH", style = Eyebrow, color = cs.primary, modifier = Modifier.padding(top = 8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                MachineAuth.entries.forEachIndexed { i, a ->
                    SegmentedButton(selected = auth == a, onClick = { auth = a; result = null }, shape = SegmentedButtonDefaults.itemShape(i, MachineAuth.entries.size)) { Text(a.label) }
                }
            }
            when (auth) {
                MachineAuth.PASSWORD -> OutlinedTextField(password, { password = it; result = null }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    placeholder = { if (editing) Text("Saved. Leave empty to keep it") }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
                MachineAuth.KEY -> {
                    OutlinedTextField(key, { key = it; result = null }, label = { Text("Private key") }, placeholder = { Text(if (editing) "Saved. Leave empty to keep it" else "-----BEGIN OPENSSH PRIVATE KEY-----") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
                    OutlinedTextField(passphrase, { passphrase = it; result = null }, label = { Text("Key passphrase (if it has one)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
                }
                MachineAuth.NEW_KEY -> Text(if (pub == null) "The app makes a key just for this machine. After saving, you'll add its public half to the machine once." else "This machine uses a key made on this phone.",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            if (pub != null && auth == MachineAuth.NEW_KEY) PublicKeyCard(pub, actions.onCopy)

            val gateways = others.filter { it.id != existing?.id }
            if (gateways.isNotEmpty()) {
                Text("CONNECT THROUGH", style = Eyebrow, color = cs.primary, modifier = Modifier.padding(top = 8.dp))
                Text("For machines only reachable from inside a network, like a uni lab behind a login server.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = via == null, onClick = { via = null }, label = { Text("Direct") })
                    gateways.forEach { g -> FilterChip(selected = via == g.id, onClick = { via = g.id }, label = { Text(g.name) }) }
                }
            }

            Surface(shape = MaterialTheme.shapes.large, color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Row(Modifier.clickable { trusted = !trusted }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Run commands without asking", style = MaterialTheme.typography.titleSmall)
                        Text(if (trusted) "Your agent can change anything on this machine on its own." else "Anything that changes this machine waits for your yes. Looking around never asks.",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(trusted, { trusted = it })
                }
            }

            when {
                result == "" -> ResultCard(true, "Connected", saved?.let { listOf(it.about, it.hostKey?.let { k -> "Identity $k" }).filterNotNull().filter { s -> s.isNotBlank() }.joinToString("\n") } ?: "")
                result != null -> ResultCard(false, "Couldn't connect", result!!)
            }

            Button(onClick = {
                scope.launch {
                    busy = true; result = null
                    val m = Machine(saved?.id ?: java.util.UUID.randomUUID().toString().take(8), name.trim().ifBlank { host.substringBefore('.') }, host.trim(), port.toIntOrNull() ?: 22, user.trim(), auth, via, trusted,
                        saved?.hostKey?.takeIf { saved?.host == host.trim() && saved?.port == (port.toIntOrNull() ?: 22) }, saved?.about ?: "")
                    val makeKey = auth == MachineAuth.NEW_KEY && actions.publicKey(m.id) == null
                    val stored = actions.onSave(m, password, key, passphrase, makeKey)
                    saved = stored; password = ""; key = ""; passphrase = ""
                    if (!makeKey) { result = actions.onTest(stored) ?: ""; saved = actions.reload(stored.id) ?: stored }
                    busy = false
                }
            }, enabled = ready, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 4.dp), shapes = ButtonDefaults.shapes()) {
                if (busy) { LoadingIndicator(Modifier.size(24.dp), color = cs.onPrimary); Spacer(Modifier.width(10.dp)); Text("Connecting…") }
                else Text(if (auth == MachineAuth.NEW_KEY && pub == null) "Save and make a key" else "Save and test")
            }
        }
    }
    if (confirmDelete && existing != null) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Remove ${existing.name}?") }, text = { Text("Your agent loses access, and its saved password or key is deleted from this phone.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; actions.onDelete(existing.id) }) { Text("Remove", color = cs.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

@Composable
private fun PublicKeyCard(pub: String, onCopy: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, color = cs.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("ONE STEP ON THE MACHINE", style = Eyebrow, color = cs.onPrimaryContainer)
            Spacer(Modifier.height(6.dp))
            Text("Add this line to ~/.ssh/authorized_keys on the machine (or send it to whoever runs it). Then tap Save and test.", style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer)
            Spacer(Modifier.height(10.dp))
            Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerLowest, modifier = Modifier.fillMaxWidth()) {
                Text(pub, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = cs.onSurface)
            }
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(onClick = { onCopy(pub) }, shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Copy public key") }
        }
    }
}

@Composable
private fun ResultCard(ok: Boolean, title: String, body: String) {
    val cs = MaterialTheme.colorScheme; val extra = LocalExtra.current
    Surface(shape = MaterialTheme.shapes.large, color = if (ok) extra.successContainer else cs.errorContainer, modifier = Modifier.fillMaxWidth()) {
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
