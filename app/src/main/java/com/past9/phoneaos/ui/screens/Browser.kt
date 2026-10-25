package com.past9.phoneaos.ui.screens

import android.widget.FrameLayout
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.past9.phoneaos.browser.BrowserEngine
import com.past9.phoneaos.browser.PageState
import com.past9.phoneaos.ui.EmptyState
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.res.stringResource
import com.past9.phoneaos.R

data class BrowserActions(
    val onClose: () -> Unit = {},
    val onHandBack: () -> Unit = {},
    /** Open (or switch to) the main agent's browser in this profile. */
    val onProfile: (String) -> Unit = {},
    val onAddProfile: (String) -> Unit = {},
)

/**
 * Watch any of the agent's browsers live, or take over (sign in, solve a captcha) and hand it
 * back. Each profile is its own set of signed-in accounts; helpers get their own browsers too.
 */
@Composable
fun BrowserScreen(engines: Map<String, BrowserEngine>, profiles: List<String>, actions: BrowserActions) {
    var selected by remember(engines.keys) { mutableStateOf(engines.keys.firstOrNull { it.startsWith("main/") } ?: engines.keys.firstOrNull()) }
    val engine = selected?.let { engines[it] }
    val page by (engine?.page ?: remember { MutableStateFlow(PageState()) }).collectAsState()
    var adding by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = actions.onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.browser_close)) }
                        Column(Modifier.weight(1f)) {
                            Text(page.title.ifBlank { stringResource(R.string.browser_title_default) }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (page.url.startsWith("https")) Icon(Icons.Rounded.Lock, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(" " + page.url.removePrefix("https://").removePrefix("http://").ifBlank { stringResource(R.string.browser_nothing_open) }, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Button(onClick = actions.onHandBack, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.browser_hand_back)) }
                    }
                    // Profiles (accounts) and any helper browsers that are open.
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        profiles.forEach { p ->
                            val key = "main/$p"
                            FilterChip(selected == key, { if (engines.containsKey(key)) selected = key else actions.onProfile(p) }, label = { Text(p) },
                                leadingIcon = { Icon(Icons.Rounded.Person, null, Modifier.size(18.dp)) }, shape = RoundedCornerShape(50))
                        }
                        engines.keys.filter { !it.startsWith("main/") }.forEach { k ->
                            FilterChip(selected == k, { selected = k }, label = { Text(k.substringBefore('/')) }, shape = RoundedCornerShape(50))
                        }
                        AssistChip({ adding = true }, label = { Text(stringResource(R.string.browser_profile)) }, leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)) }, shape = RoundedCornerShape(50))
                    }
                }
            }
        },
    ) { pad ->
        if (page.loading) LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(pad))
        if (engine == null) Box(Modifier.padding(pad).fillMaxSize()) { EmptyState(stringResource(R.string.browser_empty), stringResource(R.string.browser_empty_body)) }
        else key(selected) {
            AndroidView(factory = { ctx -> FrameLayout(ctx).also { engine.attachTo(it) } }, onRelease = { engine.detachFrom(it) }, modifier = Modifier.padding(pad).fillMaxSize())
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        ModalBottomSheet(onDismissRequest = { adding = false }) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text(stringResource(R.string.browser_new_profile), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.browser_new_profile_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.browser_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { actions.onAddProfile(name.trim()); adding = false }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth().height(56.dp), shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.browser_add_profile)) }
            }
        }
    }
}
