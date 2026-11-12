package com.past9.phoneaos.wear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.google.android.gms.wearable.Wearable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.past9.phoneaos.wear.ui.PairStage
import com.past9.phoneaos.wear.ui.PairFlowScreen
import com.past9.phoneaos.wear.ui.PairingScreen
import com.past9.phoneaos.wear.ui.QrImage
import com.past9.phoneaos.wear.ui.ex.MascotKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Until a phone has paired the watch shows only its pairing screen (the QR and the code); once paired, [content] is the app. Unpairing from
 * either side brings the pairing screen back by itself. When the phone has just paired in front of the user, the watch shows the agent
 * pulling its settings (name, mascot, accent), then the name and mascot it was given, and the user taps Start.
 */
@Composable
fun PairGate(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    remember { WatchPairingState.host(ctx) }
    val paired by WatchPairingState.paired.collectAsState()
    val cust by WatchCustomisationState.state.collectAsState()
    // paired when this screen opened: no welcome, the app. Paired while the screen was up: the welcome.
    val startedPaired = remember { WatchPairingState.paired.value }
    var welcomed by remember { mutableStateOf(startedPaired) }
    var timedOut by remember { mutableStateOf(false) }
    if (paired && !welcomed) {
        LaunchedEffect(Unit) { delay(6_000); timedOut = true }
        val have = cust.rev > 0 || timedOut
        PairFlowScreen(if (have) PairStage.DONE else PairStage.PULLING, "", "", if (have) 3 else if (cust.rev > 0) 2 else 1, cust.agentName, MascotKind.of(cust.mascot)) { welcomed = true }
        return
    }
    if (paired) { content(); return }
    LaunchedEffect(Unit) { welcomed = false }
    val offer by WatchPairingState.offer.collectAsState()
    var expired by remember { mutableStateOf(false) }
    LaunchedEffect(offer) {
        expired = false
        if (offer == null) WatchPairingState.newOffer(ctx)
        else { delay((offer!!.expiresAt - System.currentTimeMillis()).coerceAtLeast(0)); expired = true }
    }
    // While this screen is up the watch also answers over the local network, and listens for the phone's request itself (the system may not start the service).
    var lanHost by remember { mutableStateOf("") }
    DisposableEffect(Unit) {
        lanHost = WatchLanPair.start(ctx)
        val l = WatchPairingState.listener(ctx, scope)
        runCatching { Wearable.getMessageClient(ctx).addListener(l) }
        onDispose { runCatching { Wearable.getMessageClient(ctx).removeListener(l) }; WatchLanPair.stop() }
    }
    val qr = remember(offer?.secret, lanHost) { offer?.let { QrImage.make(it.qr(lanHost)) } }
    PairingScreen(offer, qr, expired, lanHost) { scope.launch { WatchPairingState.newOffer(ctx) } }
}
