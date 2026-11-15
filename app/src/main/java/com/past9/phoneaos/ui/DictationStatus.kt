package com.past9.phoneaos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.past9.phoneaos.R
import com.past9.phoneaos.voice.DictationHub
import com.past9.phoneaos.voice.DictationView

/**
 * One line above the message box while he dictates into it: listening, reconnecting (still recording), paused by
 * another app, writing down the last part, or what could not be written down with Retry. Never a silent end.
 */
@Composable
fun DictationStatus(owner: Any?) {
    if (owner == null) return
    val ui by DictationHub.ui.collectAsStateWithLifecycle()
    val u = ui ?: return
    if (u.owner != owner) return
    val v = u.view
    val line = DictationHub.line(v) ?: return
    val c = MaterialTheme.colorScheme
    val dot = when (v.phase) {
        DictationView.Phase.LISTENING -> c.primary
        DictationView.Phase.RECONNECTING, DictationView.Phase.PAUSED, DictationView.Phase.FINISHING -> c.tertiary
        else -> c.error
    }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Text(line, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (v.phase == DictationView.Phase.KEPT) {
            TextButton(onClick = { DictationHub.retry() }) { Text(stringResource(R.string.ui_retry), style = MaterialTheme.typography.labelLarge) }
            IconButton(onClick = { DictationHub.dismiss() }) { Icon(Icons.Rounded.Close, stringResource(R.string.camera_dismiss), Modifier.size(18.dp), tint = c.onSurfaceVariant) }
        }
    }
}
