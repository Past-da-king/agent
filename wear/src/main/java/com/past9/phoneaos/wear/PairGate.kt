package com.past9.phoneaos.wear

import android.app.Activity
import android.view.WindowManager
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.past9.phoneaos.wear.ui.PairStage
import com.past9.phoneaos.wear.ui.PairFlowScreen
import com.past9.phoneaos.wear.ui.PairingScreen
import com.past9.phoneaos.wear.ui.ex.MascotKind
import com.past9.phoneaos.wearproto.PairLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Starts and stops what listens for a pairing request, once each: added when the screen resumes, removed when it pauses, however often the
 * system calls either. (Whatever happens here, the manifest's [WearListener] still receives the request when the app is not in front.)
 */
class PairListening(private val add: () -> Unit, private val remove: () -> Unit) {
    var active = false; private set
    fun resume() { if (!active) { active = true; add() } }
    fun pause() { if (active) { active = false; remove() } }
}

/**
 * Until a phone has paired the watch shows only its pairing screen (the QR and the code); once paired, [content] is the app. Unpairing from
 * either side brings the pairing screen back by itself. When the phone has just paired in front of the user, the watch shows the agent
 * pulling its settings (name, mascot, accent), then the name and mascot it was given, and the user taps Start.
 *
 * The pairing screen keeps the screen on (a watch that dozes cannot be reached), puts its listeners back on every resume, reads the phone's
 * "paired" note on every resume (a watch that missed the answer learns it here), and shows the same code if he backs out and returns.
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
    // Keep the watch awake and this screen in front while it waits for the phone: a dozing watch is the one that cannot be paired.
    DisposableEffect(Unit) {
        val window = (ctx as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WatchPairingState.screenUp = true
        PairLog.i("watch: pairing screen up, screen kept on")
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); WatchPairingState.screenUp = false; PairLog.i("watch: pairing screen left") }
    }
    // While this screen is in front the watch also answers over the local network, and listens for the phone's request itself.
    var lanHost by remember { mutableStateOf("") }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val l = WatchPairingState.listener(ctx, scope)
        val listening = PairListening(
            add = {
                lanHost = WatchLanPair.start(ctx)
                runCatching { Wearable.getMessageClient(ctx).addListener(l) }.onFailure { PairLog.i("watch: listener add failed: ${it.message}") }
                PairLog.i("watch: listener added lan=${if (lanHost.isBlank()) "none" else "up"}")
            },
            remove = {
                runCatching { Wearable.getMessageClient(ctx).removeListener(l) }; WatchLanPair.stop()
                PairLog.i("watch: listener removed")
            },
        )
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    listening.resume()
                    // The code he saw before he backed out is still good: show it again, and learn whether the phone already paired this watch.
                    scope.launch { WatchPairingState.newOffer(ctx); WatchPairingState.reconcile(ctx) }
                }
                Lifecycle.Event.ON_PAUSE -> listening.pause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); listening.pause() }
    }
    val pct = rememberBatteryPercent()
    val powerSave = remember { WatchHealth.powerSave(ctx) }
    PairingScreen(offer, null, expired, lanHost, pairNotice(pct, powerSave)) { scope.launch { WatchPairingState.newOffer(ctx, force = true) } }
}
