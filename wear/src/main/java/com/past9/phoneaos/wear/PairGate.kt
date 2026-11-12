package com.past9.phoneaos.wear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.past9.phoneaos.wear.ui.PairingScreen
import com.past9.phoneaos.wear.ui.QrImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Until a phone has paired the watch shows only its pairing screen (the QR and the code); once paired, [content] is the app. Unpairing from
 * either side brings the pairing screen back by itself.
 */
@Composable
fun PairGate(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    remember { WatchPairingState.host(ctx) }
    val paired by WatchPairingState.paired.collectAsState()
    if (paired) { content(); return }
    val offer by WatchPairingState.offer.collectAsState()
    var expired by remember { mutableStateOf(false) }
    LaunchedEffect(offer) {
        expired = false
        if (offer == null) WatchPairingState.newOffer(ctx)
        else { delay((offer!!.expiresAt - System.currentTimeMillis()).coerceAtLeast(0)); expired = true }
    }
    val qr = remember(offer?.secret) { offer?.let { QrImage.make(it.qr()) } }
    PairingScreen(offer, qr, expired) { scope.launch { WatchPairingState.newOffer(ctx) } }
}
