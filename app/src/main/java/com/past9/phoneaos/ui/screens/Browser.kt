package com.past9.phoneaos.ui.screens

import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
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

/**
 * Watch the agent's browser live, or take over (sign in, solve a captcha) and hand it back.
 * The SAME WebView the agent drives is attached here while this screen is open.
 */
@Composable
fun BrowserScreen(engine: BrowserEngine?, page: PageState, onClose: () -> Unit, onHandBack: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close") }
                    Column(Modifier.weight(1f)) {
                        Text(page.title.ifBlank { "Your agent's browser" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (page.url.startsWith("https")) Icon(Icons.Rounded.Lock, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(" " + page.url.removePrefix("https://").removePrefix("http://").ifBlank { "Nothing open" }, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Button(onClick = onHandBack, shapes = ButtonDefaults.shapes()) { Text("Hand back") }
                }
            }
        },
    ) { pad ->
        if (page.loading) LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(pad))
        if (engine == null) Box(Modifier.padding(pad).fillMaxSize()) { EmptyState("Not browsing right now", "When your agent opens a page, you can watch it here and take over if a site needs you.") }
        else AndroidView(
            factory = { ctx -> FrameLayout(ctx).also { engine.attachTo(it) } },
            onRelease = { engine.detachFrom(it) },
            modifier = Modifier.padding(pad).fillMaxSize(),
        )
    }
}
