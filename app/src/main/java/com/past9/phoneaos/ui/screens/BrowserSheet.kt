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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.R
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
    /** The numbers the user marked as test accounts, digits with the country code. */
    val testNumbers: () -> List<String> = { emptyList() },
    /** Adds what was typed. Returns why not, or null when it was added. */
    val addTestNumber: (String) -> String? = { null },
    val removeTestNumber: (String) -> Unit = {},
    val testCallingOn: () -> Boolean = { false },
    val setTestCallingOn: (Boolean) -> Unit = {},
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
            com.past9.phoneaos.ui.SheetHead(Icons.Rounded.Public, stringResource(R.string.settings_browser), stringResource(R.string.settings_browser_sites),
                stringResource(R.string.browser_sites_body))
            Spacer(Modifier.height(20.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                if (origins.isEmpty()) Text(stringResource(R.string.browser_no_sites), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp))
                origins.forEach { origin ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Language, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Text(origin.removePrefix("https://"), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        val removeDesc = stringResource(R.string.browser_remove_site, origin.removePrefix("https://"))
                        IconButton(onClick = { actions.removeOrigin(origin); origins = actions.origins() },
                            modifier = Modifier.semantics { contentDescription = removeDesc }) {
                            Icon(Icons.Rounded.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.past9.phoneaos.ui.SheetField(typed, { typed = it; error = null }, stringResource(R.string.browser_add_site), "https://example.com", singleLine = true, mono = true,
                    modifier = Modifier.weight(1f),
                    keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done))
                Button(onClick = { add() }, enabled = typed.isNotBlank(), modifier = Modifier.padding(bottom = 4.dp)) { Text(stringResource(R.string.conn_add), style = MaterialTheme.typography.labelLarge) }
            }
            Text(error ?: stringResource(R.string.browser_sites_hint),
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
            Spacer(Modifier.height(8.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Call, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.browser_call_voice), style = MaterialTheme.typography.titleSmall)
                        Text(if (callVoice) stringResource(R.string.browser_call_voice_on)
                            else stringResource(R.string.browser_call_voice_off),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(callVoice, { on -> callVoice = on; actions.setCallVoiceOn(on) }, modifier = Modifier.testTag("call-voice"))
                }
            }
            Spacer(Modifier.height(12.dp))
            TestCallingCard(actions)
        }
    }
}

/**
 * "Turn on calling for a test account": off by default, a plain warning that is always in view, the user's own list
 * of test numbers, and a switch that only turns on after the number is confirmed, inline in the sheet (never a
 * pop-up). The app forces calling only when the number signed in to WhatsApp Web is one listed here.
 */
@Composable
private fun TestCallingCard(actions: BrowserSettingsActions) {
    val cs = MaterialTheme.colorScheme
    var numbers by remember { mutableStateOf(actions.testNumbers()) }
    var on by remember { mutableStateOf(actions.testCallingOn()) }
    var typed by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    fun add() {
        if (typed.isBlank()) return
        error = actions.addTestNumber(typed)
        if (error == null) { typed = ""; numbers = actions.testNumbers() }
    }
    AppCard(padding = PaddingValues(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Science, null, tint = cs.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.browser_test_calling), style = MaterialTheme.typography.titleSmall)
                Text(if (on) stringResource(R.string.browser_test_calling_on)
                    else stringResource(R.string.browser_test_calling_off),
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Switch(on, { want ->
                error = null
                if (!want) { actions.setTestCallingOn(false); on = false; confirming = false }
                else if (numbers.isEmpty()) error = ctx.getString(R.string.browser_test_number_first)
                else confirming = true
            }, modifier = Modifier.testTag("test-calling"))
        }
        Surface(shape = MaterialTheme.shapes.medium, color = cs.errorContainer, contentColor = cs.onErrorContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Warning, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(com.past9.phoneaos.browser.CallTestAccounts.WARNING, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (confirming) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(pluralStringResource(R.plurals.browser_test_confirm, numbers.size, numbers.joinToString(", ") { com.past9.phoneaos.browser.CallTestAccounts.show(it) }),
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { actions.setTestCallingOn(true); on = actions.testCallingOn(); confirming = false }) { Text(stringResource(R.string.browser_yes_turn_on), style = MaterialTheme.typography.labelLarge) }
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.machines_cancel), style = MaterialTheme.typography.labelLarge) }
            }
        }
        numbers.forEach { number ->
            val shown = com.past9.phoneaos.browser.CallTestAccounts.show(number)
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Call, null, tint = cs.primary)
                Spacer(Modifier.width(16.dp))
                Text(shown, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = cs.onSurface, modifier = Modifier.weight(1f))
                val removeDesc = stringResource(R.string.browser_remove_test_number, shown)
                IconButton(onClick = { actions.removeTestNumber(number); numbers = actions.testNumbers(); on = actions.testCallingOn(); if (numbers.isEmpty()) confirming = false },
                    modifier = Modifier.semantics { contentDescription = removeDesc }) {
                    Icon(Icons.Rounded.Close, null, tint = cs.onSurfaceVariant)
                }
            }
        }
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.past9.phoneaos.ui.SheetField(typed, { typed = it; error = null }, stringResource(R.string.browser_test_number), stringResource(R.string.browser_test_number_hint), singleLine = true, mono = true,
                modifier = Modifier.weight(1f).testTag("test-number"),
                keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done))
            val addDesc = stringResource(R.string.browser_add_test_number)
            Button(onClick = { add() }, enabled = typed.isNotBlank(), modifier = Modifier.padding(bottom = 4.dp).semantics { contentDescription = addDesc }) { Text(stringResource(R.string.conn_add), style = MaterialTheme.typography.labelLarge) }
        }
        Text(error ?: stringResource(R.string.browser_test_number_body),
            style = MaterialTheme.typography.bodySmall, color = if (error != null) cs.error else cs.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}
