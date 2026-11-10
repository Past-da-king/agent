package com.past9.phoneaos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.automation.MacroRules
import com.past9.phoneaos.ui.AppCard

/** The Settings line under Automation, in words. */
object MacroRulesUi {
    fun allowedLine(allowlistText: String, intentsText: String = ""): String {
        val macros = MacroRules.parseAllowlist(allowlistText).size
        val intents = com.past9.phoneaos.automation.IntentRules.parseAllowlist(intentsText).size
        return when {
            macros == 0 && intents == 0 -> "Intents and macros \u00b7 anything that changes something asks you first"
            else -> listOfNotNull(
                macros.takeIf { it > 0 }?.let { "$it macro${if (it == 1) "" else "s"}" },
                intents.takeIf { it > 0 }?.let { "$it app intent${if (it == 1) "" else "s"}" },
            ).joinToString(" and ") + " can run without asking; others ask"
        }
    }
}

/**
 * Settings > Automation > Agent talks to your apps: what may run without asking (app intents and macros), a test for
 * each, the MacroDroid recipe to copy and the optional MacroDroid webhook. A slide-up sheet in the app's own pieces, never a pop-up.
 */
@Composable
internal fun MacroSheet(actions: MacroActions, onDismiss: () -> Unit) {
    val clip = LocalClipboardManager.current
    var allow by remember { mutableStateOf(actions.allowlist()) }
    var webhookOn by remember { mutableStateOf(actions.webhookOn()) }
    var webhookId by remember { mutableStateOf(actions.webhookId()) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var intents by remember { mutableStateOf(actions.intents()) }
    var mapsResult by remember { mutableStateOf<String?>(null) }
    val recipe = remember { MacroRules.recipe(MacroRules.parseAllowlist(allow).firstOrNull() ?: "Wifi off") }
    // The test runs the user's own first macro when they have listed one, so they test the thing they made.
    val testMacro = MacroRules.parseAllowlist(allow).firstOrNull() ?: MacroRules.TEST_MACRO
    fun save() { actions.setAllowlist(allow); actions.setWebhookId(webhookId) }
    com.past9.phoneaos.ui.AgentSheet({ save(); onDismiss() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding().imePadding()) {
            com.past9.phoneaos.ui.SheetHead(Icons.Rounded.SmartToy, "Automation", "Agent talks to your apps",
                "Your agent can send any app on this phone an Android intent: open a place in Maps, share into an app, run a MacroDroid or Tasker macro. Opening a screen never asks. Anything else asks you first, unless it is listed here. Nothing leaves the phone.")
            Spacer(Modifier.height(20.dp))
            com.past9.phoneaos.ui.SheetField(intents, { intents = it; actions.setIntents(it) }, "Allowed app intents", "action | package, one per line",
                minLines = 2, mono = true, keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None))
            Text("An action and the app it goes to, like android.intent.action.SEND | com.whatsapp. Use * for any app. When you approve one, the card shows the line to add.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Place, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Test with Maps", style = MaterialTheme.typography.titleSmall)
                        Text(mapsResult?.let { if (it.startsWith("sent")) "Sent. Your maps app should open on Sandton City." else it.replaceFirstChar { c -> c.uppercase() } }
                            ?: "Opens Sandton City in your maps app, by intent.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (mapsResult?.startsWith("failed") == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { mapsResult = actions.testMaps() }) { Text("Open", style = MaterialTheme.typography.labelLarge) }
                }
            }
            Spacer(Modifier.height(20.dp))
            com.past9.phoneaos.ui.SheetField(allow, { allow = it; actions.setAllowlist(it) }, "Allowed macros", "One macro name per line, e.g. Wifi off",
                minLines = 3, keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            Text("These run without asking. Any other macro asks you first, and is not sent when nobody answers.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
            Spacer(Modifier.height(8.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Test a macro", style = MaterialTheme.typography.titleSmall)
                        Text(testResult?.let { if (it == "sent") "Sent \"$testMacro\". Nothing happened? In the trigger, the extra's name must be macro in small letters, with no space after the value." else it.replaceFirstChar { c -> c.uppercase() } }
                            ?: "Sends your macro \"$testMacro\" now.".takeIf { MacroRules.parseAllowlist(allow).isNotEmpty() } ?: "Sends a macro called \"${MacroRules.TEST_MACRO}\". Add yours above to test that one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (testResult?.startsWith("failed") == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { testResult = actions.test(testMacro) }) { Text("Send", style = MaterialTheme.typography.labelLarge) }
                }
            }
            Spacer(Modifier.height(16.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ContentCopy, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Text("Set up a macro", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { clip.setText(AnnotatedString(recipe)); com.past9.phoneaos.ui.AppNotice.say("Recipe copied") }) {
                        Text("Copy", style = MaterialTheme.typography.labelLarge)
                    }
                }
                SelectionContainer {
                    Text(recipe, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Cloud, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("MacroDroid webhook", style = MaterialTheme.typography.titleSmall)
                        Text(if (webhookOn) "On: macros can also go through MacroDroid's server, for an agent on another device."
                            else "Off. Goes through MacroDroid's server, not only this phone.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(webhookOn, { on -> webhookOn = on; actions.setWebhookOn(on) })
                }
                if (webhookOn) Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                    com.past9.phoneaos.ui.SheetField(webhookId, { webhookId = it; actions.setWebhookId(it) }, "Webhook device id",
                        "From https://trigger.macrodroid.com/<id>/...", singleLine = true, mono = true,
                        keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None))
                }
            }
        }
    }
}
