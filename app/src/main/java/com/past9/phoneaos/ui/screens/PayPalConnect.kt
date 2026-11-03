package com.past9.phoneaos.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.ui.theme.Eyebrow
import com.past9.phoneaos.ui.theme.LocalExtra
import kotlinx.coroutines.launch

/**
 * PayPal in Connections: the user pastes their own PayPal app's client ID and secret (sandbox), checked with a
 * token call before it's saved. [connected] is a short label when set ("Sandbox · AbC1…"), null when not.
 */
@Composable
fun PayPalLine(connected: String?, save: suspend (String, String) -> String?, remove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Column {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Payments, null, tint = cs.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("PayPal", style = MaterialTheme.typography.titleSmall)
                Text(connected ?: "Invoices, pay links and refunds. Every payment waits for your yes", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            if (connected != null) Icon(Icons.Rounded.CheckCircle, "On", tint = LocalExtra.current.success)
            else Icon(Icons.Rounded.ChevronRight, null, tint = cs.onSurfaceVariant)
        }
        if (open) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            if (connected != null) {
                Text("Your agent can draft and send invoices, make pay links, check who paid, collect payments and refund. " +
                    "Sending, collecting and refunding always stop for your Approve first.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                TextButton(onClick = { remove(); open = false }) { Text("Remove PayPal", color = cs.error) }
            } else PayPalKeyForm(save) { open = false }
        }
    }
}

@Composable
fun PayPalKeyForm(save: suspend (String, String) -> String?, onSaved: () -> Unit) {
    var id by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column {
        Text("SANDBOX: TEST MONEY ONLY", style = Eyebrow, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Text("In developer.paypal.com open Apps & Credentials, Sandbox, your app. Copy its Client ID and Secret here. " +
            "Switch on Invoicing and Transaction search under the app's Features.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(id, { id = it.trim(); error = null }, label = { Text("Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(secret, { secret = it.trim(); error = null }, label = { Text("Secret") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, isError = error != null, supportingText = error?.let { { Text(it) } })
        Spacer(Modifier.height(8.dp))
        Button(onClick = { scope.launch { busy = true; error = save(id, secret); busy = false; if (error == null) onSaved() } },
            enabled = id.length > 10 && secret.length > 10 && !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Checking…" else "Connect PayPal") }
    }
}
