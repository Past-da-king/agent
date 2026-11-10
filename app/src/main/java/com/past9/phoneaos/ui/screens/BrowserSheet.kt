package com.past9.phoneaos.ui.screens

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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.browser.IsolatedOrigins
import com.past9.phoneaos.ui.AppCard

/** Settings > Browser: what the sheet reads and writes. */
data class BrowserSettingsActions(
    val origins: () -> List<String> = { IsolatedOrigins.DEFAULT },
    /** Adds what was typed. Returns why not, or null when it was added. */
    val addOrigin: (String) -> String? = { null },
    val removeOrigin: (String) -> Unit = {},
    val callVoiceOn: () -> Boolean = { false },
    val setCallVoiceOn: (Boolean) -> Unit = {},
)

/**
 * Settings > Browser > Sites that need advanced browser features: the user's own list of sites that may use shared
 * memory in the agent's browser, a field to add one, and the switch for the agent's voice in WhatsApp calls.
 * A slide-up sheet in the app's own pieces, never a pop-up.
 */
@Composable
internal fun BrowserSheet(actions: BrowserSettingsActions, onDismiss: () -> Unit) {
    var origins by remember { mutableStateOf(actions.origins()) }
    var typed by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var callVoice by remember { mutableStateOf(actions.callVoiceOn()) }
    fun add() {
        if (typed.isBlank()) return
        error = actions.addOrigin(typed)
        if (error == null) { typed = ""; origins = actions.origins() }
    }
    com.past9.phoneaos.ui.AgentSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding().imePadding()) {
            com.past9.phoneaos.ui.SheetHead(Icons.Rounded.Public, "Browser", "Sites that need advanced browser features",
                "Some sites do heavy work inside the page and need shared memory for it: WhatsApp Web calls, in-browser editors, Ai models that run in the page. Your agent's browser allows it only for the sites listed here. Add only sites you trust.")
            Spacer(Modifier.height(20.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                if (origins.isEmpty()) Text("No sites yet. Add one below.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp))
                origins.forEach { origin ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Language, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Text(origin.removePrefix("https://"), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        IconButton(onClick = { actions.removeOrigin(origin); origins = actions.origins() },
                            modifier = Modifier.semantics { contentDescription = "Remove ${origin.removePrefix("https://")}" }) {
                            Icon(Icons.Rounded.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.past9.phoneaos.ui.SheetField(typed, { typed = it; error = null }, "Add a site", "https://example.com", singleLine = true, mono = true,
                    modifier = Modifier.weight(1f),
                    keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done))
                Button(onClick = { add() }, enabled = typed.isNotBlank(), modifier = Modifier.padding(bottom = 4.dp)) { Text("Add", style = MaterialTheme.typography.labelLarge) }
            }
            Text(error ?: "Secure sites only (https), by their exact address. Changes apply to new tabs.",
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
            Spacer(Modifier.height(8.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Call, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Let the agent speak in WhatsApp calls", style = MaterialTheme.typography.titleSmall)
                        Text(if (callVoice) "On: in a WhatsApp Web call your agent places here, its voice goes into the call and it hears the other person. Applies to new tabs."
                            else "Off. Your agent can't speak in a WhatsApp Web call.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(callVoice, { on -> callVoice = on; actions.setCallVoiceOn(on) })
                }
            }
        }
    }
}
