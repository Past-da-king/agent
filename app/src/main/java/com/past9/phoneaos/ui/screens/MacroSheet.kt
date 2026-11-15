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
import androidx.compose.ui.res.stringResource
import com.past9.phoneaos.R
import com.past9.phoneaos.automation.MacroRules
import com.past9.phoneaos.ui.AppCard

/** The Settings line under Automation, in words. */
object MacroRulesUi {
    /** Without a Context (tests) it stays English. */
    fun allowedLine(allowlistText: String, intentsText: String = "", ctx: android.content.Context? = null): String {
        val macros = MacroRules.parseAllowlist(allowlistText).size
        val intents = com.past9.phoneaos.automation.IntentRules.parseAllowlist(intentsText).size
        fun qty(id: Int, n: Int, en: String) = ctx?.resources?.getQuantityString(id, n, n) ?: en
        return when {
            macros == 0 && intents == 0 -> ctx?.getString(R.string.macro_line_none) ?: "Intents and macros \u00b7 anything that changes something asks you first"
            else -> {
                val parts = listOfNotNull(
                    macros.takeIf { it > 0 }?.let { qty(R.plurals.macro_count, it, "$it macro${if (it == 1) "" else "s"}") },
                    intents.takeIf { it > 0 }?.let { qty(R.plurals.macro_intent_count, it, "$it app intent${if (it == 1) "" else "s"}") },
                )
                val joined = if (parts.size == 2) ctx?.getString(R.string.macro_and, parts[0], parts[1]) ?: parts.joinToString(" and ") else parts[0]
                ctx?.getString(R.string.macro_line_some, joined) ?: "$joined can run without asking; others ask"
            }
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
    val ctx = androidx.compose.ui.platform.LocalContext.current
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
            com.past9.phoneaos.ui.SheetHead(Icons.Rounded.SmartToy, stringResource(R.string.macro_eyebrow), stringResource(R.string.macro_title),
                stringResource(R.string.macro_intro))
            Spacer(Modifier.height(20.dp))
            com.past9.phoneaos.ui.SheetField(intents, { intents = it; actions.setIntents(it) }, stringResource(R.string.macro_intents), stringResource(R.string.macro_intents_hint),
                minLines = 2, mono = true, keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None))
            Text(stringResource(R.string.macro_intents_body),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Place, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.macro_test_maps), style = MaterialTheme.typography.titleSmall)
                        Text(mapsResult?.let { if (it.startsWith("sent")) stringResource(R.string.macro_maps_sent) else it.replaceFirstChar { c -> c.uppercase() } }
                            ?: stringResource(R.string.macro_maps_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (mapsResult?.startsWith("failed") == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { mapsResult = actions.testMaps() }) { Text(stringResource(R.string.chat_open), style = MaterialTheme.typography.labelLarge) }
                }
            }
            Spacer(Modifier.height(20.dp))
            com.past9.phoneaos.ui.SheetField(allow, { allow = it; actions.setAllowlist(it) }, stringResource(R.string.macro_allowed), stringResource(R.string.macro_allowed_hint),
                minLines = 3, keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            Text(stringResource(R.string.macro_allowed_body),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
            Spacer(Modifier.height(8.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.macro_test), style = MaterialTheme.typography.titleSmall)
                        Text(testResult?.let { if (it == "sent") stringResource(R.string.macro_test_sent, testMacro) else it.replaceFirstChar { c -> c.uppercase() } }
                            ?: stringResource(R.string.macro_test_yours, testMacro).takeIf { MacroRules.parseAllowlist(allow).isNotEmpty() } ?: stringResource(R.string.macro_test_default, MacroRules.TEST_MACRO),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (testResult?.startsWith("failed") == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { testResult = actions.test(testMacro) }) { Text(stringResource(R.string.camera_send), style = MaterialTheme.typography.labelLarge) }
                }
            }
            Spacer(Modifier.height(16.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ContentCopy, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.macro_setup), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { clip.setText(AnnotatedString(recipe)); com.past9.phoneaos.ui.AppNotice.say(ctx.getString(R.string.macro_recipe_copied)) }) {
                        Text(stringResource(R.string.rec_copy), style = MaterialTheme.typography.labelLarge)
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
                        Text(stringResource(R.string.macro_webhook), style = MaterialTheme.typography.titleSmall)
                        Text(if (webhookOn) stringResource(R.string.macro_webhook_on)
                            else stringResource(R.string.macro_webhook_off),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(webhookOn, { on -> webhookOn = on; actions.setWebhookOn(on) })
                }
                if (webhookOn) Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                    com.past9.phoneaos.ui.SheetField(webhookId, { webhookId = it; actions.setWebhookId(it) }, stringResource(R.string.macro_webhook_id),
                        stringResource(R.string.macro_webhook_hint), singleLine = true, mono = true,
                        keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None))
                }
            }
        }
    }
}
