package com.past9.phoneaos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.R
import com.past9.phoneaos.agent.ModelInfo
import com.past9.phoneaos.data.ModelRoute
import com.past9.phoneaos.data.Provider
import com.past9.phoneaos.data.RouteChoice

/** Settings > Model routing (issue #64): what the sheet reads and writes. Nothing is chosen until the user chooses. */
data class RoutingActions(
    /** The providers the user has saved a key for. */
    val accounts: () -> List<Provider> = { emptyList() },
    /** The models one of those accounts can use. */
    val models: suspend (Provider) -> List<ModelInfo> = { emptyList() },
    val set: (ModelRoute, Provider, String) -> Unit = { _, _, _ -> },
    val clear: (ModelRoute) -> Unit = {},
)

/** The words and icon for a route, as the Settings row and the sheet show them. */
@Composable
internal fun routeName(r: ModelRoute): String = stringResource(when (r) {
    ModelRoute.CHAT -> R.string.ui_nav_chat
    ModelRoute.ROUTINE -> R.string.ui_nav_routines
    ModelRoute.WATCHER -> R.string.routing_watchers
})

internal fun routeIcon(r: ModelRoute) = when (r) {
    ModelRoute.CHAT -> Icons.Rounded.Forum
    ModelRoute.ROUTINE -> Icons.Rounded.Repeat
    ModelRoute.WATCHER -> Icons.Rounded.Visibility
}

/** "OpenAI · gpt-5", or null when the route is not set. The model's own name without the vendor prefix a router adds. */
internal fun routeSummary(c: RouteChoice?): String? = c?.let { "${it.provider.label} · ${it.model.substringAfterLast('/')}" }

/**
 * Pick the account and the model one kind of turn runs on. A slide-up sheet in the app's own pieces, never a pop-up.
 * Tapping a model saves it and closes; "Use the default" clears the route.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelRoutingSheet(route: ModelRoute, current: RouteChoice?, actions: RoutingActions, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val accounts = remember { actions.accounts() }
    var provider by remember { mutableStateOf(current?.provider?.takeIf { it in accounts } ?: accounts.firstOrNull()) }
    var models by remember { mutableStateOf<List<ModelInfo>?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    LaunchedEffect(provider) {
        val p = provider ?: return@LaunchedEffect
        models = null; failed = null; q = ""
        runCatching { actions.models(p) }.onSuccess { models = it }.onFailure { failed = it.message ?: it::class.simpleName }
    }
    com.past9.phoneaos.ui.AgentSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding().testTag("routing-sheet")) {
            com.past9.phoneaos.ui.SheetHead(routeIcon(route), stringResource(R.string.routing_title), routeName(route), stringResource(R.string.routing_sheet_line))
            Spacer(Modifier.height(16.dp))
            if (accounts.isEmpty()) {
                Text(stringResource(R.string.routing_no_accounts), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            } else {
                Text(stringResource(R.string.settings_provider), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    accounts.forEach { p ->
                        FilterChip(selected = p == provider, onClick = { provider = p }, label = { Text(p.label, style = MaterialTheme.typography.labelLarge) },
                            shape = RoundedCornerShape(50), modifier = Modifier.height(40.dp).testTag("routing-provider-${p.name}"))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.settings_model), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                val list = models
                if ((list?.size ?: 0) > 10) {
                    com.past9.phoneaos.ui.AppTextField(q, { q = it }, placeholder = { Text(stringResource(R.string.models_search)) }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge)
                    Spacer(Modifier.height(4.dp))
                }
                when {
                    failed != null -> Text(failed.orEmpty(), color = cs.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                    list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                    list.isEmpty() -> Text(stringResource(R.string.models_no_models), color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                        items(list.filter { q.isBlank() || it.name.contains(q, true) || it.id.contains(q, true) }, key = { it.id }) { m ->
                            val sel = current?.provider == provider && current?.model == m.id
                            Surface(onClick = { actions.set(route, provider!!, m.id); onDismiss() }, shape = MaterialTheme.shapes.large,
                                color = if (sel) cs.primaryContainer else androidx.compose.ui.graphics.Color.Transparent, modifier = Modifier.fillMaxWidth().testTag("routing-model-${m.id}")) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(m.name, style = MaterialTheme.typography.titleMedium, color = if (sel) cs.onPrimaryContainer else cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (m.id != m.name) Text(m.id, style = MaterialTheme.typography.bodySmall, color = if (sel) cs.onPrimaryContainer else cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (sel) Icon(Icons.Rounded.Check, stringResource(R.string.models_selected), Modifier.padding(start = 8.dp), tint = cs.onPrimaryContainer)
                                }
                            }
                        }
                    }
                }
            }
            if (current != null) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { actions.clear(route); onDismiss() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("routing-reset")) {
                    Text(stringResource(R.string.routing_reset), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
