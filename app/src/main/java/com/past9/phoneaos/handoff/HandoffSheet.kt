package com.past9.phoneaos.handoff

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sign in to the sync service the user runs, so jobs handed to their computers can report back. Slide-up, never a dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandoffSheet(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var signedIn by remember { mutableStateOf(HandoffStore.peek(ctx)) }
    var url by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun go(create: Boolean) {
        busy = true; error = null
        scope.launch {
            try {
                val token = withContext(Dispatchers.IO) { HandoffClient().signIn(url, email, password, create) }
                HandoffStore(App.graph(ctx).settings).save(url.trim().trimEnd('/'), email.trim(), token)
                signedIn = email.trim(); password = ""; Handoffs.onScreen(ctx)
            } catch (e: HandoffException) { error = e.message } catch (e: Exception) { error = e.message ?: "That didn't work." }
            busy = false
        }
    }
    ModalBottomSheet(onDismissRequest = onDone) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Hand-offs", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("Jobs you hand to your own computers report back through a sync service that you run. Nothing goes through anyone else, and nothing runs on your phone while the app is closed: results wait there and show up when you open the app.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            if (signedIn != null) {
                Text("Signed in as $signedIn", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { Handoffs.offScreen(); HandoffStore(App.graph(ctx).settings).signOut(); signedIn = null }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Sign out") }
            } else {
                OutlinedTextField(url, { url = it }, label = { Text("Your sync service address (https://…)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { go(false) }, enabled = !busy && url.isNotBlank() && email.isNotBlank() && password.length >= 6, modifier = Modifier.fillMaxWidth().height(56.dp), shapes = ButtonDefaults.shapes()) { Text(if (busy) "Signing in…" else "Sign in") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { go(true) }, enabled = !busy && url.isNotBlank() && email.isNotBlank() && password.length >= 6, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Create the account on my service") }
            }
        }
    }
}
