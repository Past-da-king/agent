package com.past9.phoneaos.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.MaterialTheme
import com.past9.phoneaos.ui.AppTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.past9.phoneaos.App
import com.past9.phoneaos.R
import com.past9.phoneaos.remote.QrScanner
import com.past9.phoneaos.ui.AppSheet
import com.past9.phoneaos.wear.WatchPairing
import com.past9.phoneaos.wearproto.PairReason
import com.past9.phoneaos.wearproto.PairReply
import kotlinx.coroutines.launch

/**
 * Pair a watch (a slide-up sheet): scan the QR the watch shows with the camera, or type the short code under it. Once paired it shows
 * which watch this phone serves, and Unpair. The scanner is the one the computer link uses.
 */
@Composable
fun WatchPairSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val g = remember { App.graph(context) }
    val scope = rememberCoroutineScope()
    val bound by WatchPairing.boundName.collectAsState()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var camOk by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val askCam = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camOk = it }
    val cs = MaterialTheme.colorScheme
    // Reading the bound name once makes sure the binding is loaded before the first frame says "not paired".
    remember { WatchPairing.bound(g) }

    fun finish(r: PairReply) {
        busy = false
        message = when (r) {
            is PairReply.Accepted -> ""
            is PairReply.Refused -> if (r.reason == "none") context.getString(R.string.watch_pair_not_found) else PairReason.text(r.reason)
        }
    }

    AppSheet(Icons.Rounded.Watch, stringResource(R.string.watch_pair_title), if (bound != null) stringResource(R.string.watch_pair_paired_with, bound ?: "") else stringResource(R.string.watch_pair_intro),
        onDismiss = onDismiss, primary = if (bound != null) stringResource(R.string.watch_pair_unpair) else stringResource(R.string.watch_pair_pair), primaryEnabled = bound != null || (code.isNotBlank() && !busy),
        onPrimary = {
            if (bound != null) { WatchPairing.unpair(g); message = "" }
            else { busy = true; scope.launch { finish(WatchPairing.pairWithCode(g, code)) } }
        },
        secondary = { TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_close)) } }) {
        if (bound == null) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.extraLarge).background(com.past9.phoneaos.ui.theme.Media.qr), contentAlignment = Alignment.Center) {
                if (camOk) QrScanner(Modifier.fillMaxSize()) { text -> if (!busy) { busy = true; scope.launch { finish(WatchPairing.pairWithQr(g, text)) } } }
                else TextButton(onClick = { askCam.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.watch_pair_allow_camera)) }
            }
            Spacer(Modifier.height(12.dp))
            AppTextField(value = code, onValueChange = { code = it.take(12) }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.watch_pair_code)) })
        } else {
            Text(stringResource(R.string.watch_pair_synced), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
        if (message.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(message, style = MaterialTheme.typography.bodyMedium, color = cs.error) }
    }
}
