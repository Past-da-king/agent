package com.past9.phoneaos.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.past9.phoneaos.R
import com.past9.phoneaos.agent.sharedHttp
import com.past9.phoneaos.ui.AppCard
import com.past9.phoneaos.ui.SectionHeader
import com.past9.phoneaos.ui.SubScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** Reads a QR code out of a camera frame's brightness plane. */
object QrDecode {
    fun decodeLuminance(y: ByteArray, width: Int, height: Int, rowStride: Int): String? {
        val data = if (rowStride == width) y else ByteArray(width * height).also { out ->
            for (r in 0 until height) { val from = r * rowStride; if (from + width <= y.size) System.arraycopy(y, from, out, r * width, width) }
        }
        return try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false))),
                mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE))).text
        } catch (_: Exception) { null }
    }

    fun decode(img: ImageProxy): String? {
        val plane = img.planes[0]
        val buf = plane.buffer
        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
        return decodeLuminance(bytes, img.width, img.height, plane.rowStride)
    }
}

/** What linking needs to say to the person. */
private sealed interface LinkStep {
    data object Ready : LinkStep
    data class Busy(val to: String) : LinkStep
    data class Failed(val why: String) : LinkStep
    /** A link code came from outside while this phone is linked to another computer: ask before replacing that link. */
    data class Confirm(val offer: PairOffer, val current: String) : LinkStep
    /** Linked. [checkWords] is set on the typed-code path: the certificate was trusted on first use, so show it to compare. */
    data class Done(val name: String, val checkWords: String?) : LinkStep
}

/** Pair with a scanned offer or a typed address + code. Returns what to show next. */
private suspend fun pair(context: Context, offer: PairOffer?, typedUrl: String?, code: String): LinkStep = withContext(Dispatchers.IO) {
    val facts = phoneFacts(context)
    if (offer != null) {
        val trust = Pinning.PinnedTrust({ listOfNotNull(offer.fp) })
        val api = RemoteApi(Pinning.client(sharedHttp, trust))
        var last = context.getString(R.string.link_cant_reach)
        for (u in offer.urls) {
            try {
                val body = api.pair(u, offer.code, facts)
                val paired = Pairing.parsePaired(body, BrainRef(offer.nodeId.ifBlank { "node" }, offer.name, offer.urls, offer.fp!!, 0))
                    ?: return@withContext LinkStep.Failed(context.getString(R.string.link_bad_answer))
                RemoteHub.linked(context, paired)
                return@withContext LinkStep.Done(paired.nodeName, null)
            } catch (e: PairException) {
                last = e.message ?: last
                if (!e.unreachable) return@withContext LinkStep.Failed(last)
            }
        }
        return@withContext LinkStep.Failed(last + if (offer.urls.size > 1) " " + context.getString(R.string.link_tried_addresses, offer.urls.size) else "")
    }
    val url = typedUrl ?: return@withContext LinkStep.Failed(context.getString(R.string.link_type_address))
    val trust = Pinning.PinnedTrust({ emptyList() }, trustOnFirstUse = true)
    val api = RemoteApi(Pinning.client(sharedHttp, trust))
    try {
        val h = api.health(url, 5_000)
        val body = api.pair(url, code, facts)
        val fp = trust.seen ?: return@withContext LinkStep.Failed(context.getString(R.string.link_no_certificate))
        val paired = Pairing.parsePaired(body, BrainRef(h?.id?.ifBlank { null } ?: "node", h?.name?.ifBlank { null } ?: url.removePrefix("https://"), listOf(url), fp, 0))
            ?: return@withContext LinkStep.Failed(context.getString(R.string.link_bad_answer))
        RemoteHub.linked(context, paired)
        LinkStep.Done(paired.nodeName, Pairing.fingerprintWords(fp))
    } catch (e: PairException) { LinkStep.Failed(e.message ?: context.getString(R.string.link_failed)) }
}

/** "Use the agent on my computer": scan the QR the computer shows, or type its address and the short code. */
@Composable
fun LinkScreen(onBack: () -> Unit, onLinked: () -> Unit, offerText: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // A link code that came with the screen goes straight to linking: the camera is never started for it.
    // ...unless it would replace the link to another computer: then it asks first (a link anyone sent could do that).
    val confirmFirst = remember(offerText) {
        offerText?.let { Pairing.parseQr(it) }?.let { o ->
            RemoteHub.link.value?.takeIf { it.meshId.isNotBlank() && it.meshId != o.meshId }?.let { LinkStep.Confirm(o, it.nodeName.ifBlank { context.getString(R.string.link_your_computer) }) }
        }
    }
    var step by remember { mutableStateOf<LinkStep>(confirmFirst ?: if (offerText != null) LinkStep.Busy(context.getString(R.string.link_your_computer)) else LinkStep.Ready) }
    var typing by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var camOk by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val askCam = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camOk = it }
    fun scanned(text: String) {
        if (step is LinkStep.Done || (step is LinkStep.Busy && text != offerText)) return
        val offer = Pairing.parseQr(text) ?: run { step = LinkStep.Failed(context.getString(R.string.link_not_a_code)); return }
        step = LinkStep.Busy(offer.name)
        scope.launch { step = pair(context, offer, null, offer.code) }
    }
    LaunchedEffect(offerText) { if (confirmFirst == null) offerText?.let { scanned(it) } }
    // System back is the arrow, for every caller: onboarding, a link that opened the app, and the add form in Linked devices.
    androidx.activity.compose.BackHandler { onBack() }
    SubScreen(stringResource(R.string.onb_use_on_computer), stringResource(R.string.link_subtitle), onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (val s = step) {
                is LinkStep.Done -> {
                    AppCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.link_linked_to, s.name), style = MaterialTheme.typography.titleLarge)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.link_runs_on, s.name),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (s.checkWords != null) {
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.link_check_code, s.name), style = MaterialTheme.typography.bodyMedium)
                            Text(s.checkWords, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Button(onClick = onLinked, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.rel_done)) }
                    if (s.checkWords != null) TextButton(onClick = { RemoteHub.unlink(context); step = LinkStep.Failed(context.getString(R.string.link_unlinked_retry)) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.link_shows_else))
                    }
                }
                is LinkStep.Confirm -> {
                    AppCard {
                        Text(stringResource(R.string.link_confirm_title, s.offer.name), style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.link_confirm_body, s.current, s.offer.name),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = { val o = s.offer; step = LinkStep.Busy(o.name); scope.launch { step = pair(context, o, null, o.code) } },
                        modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.link_link_to, s.offer.name)) }
                    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.link_keep, s.current)) }
                }
                is LinkStep.Busy -> AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(stringResource(R.string.link_linking_to, s.to), style = MaterialTheme.typography.titleMedium)
                    }
                }
                else -> {
                    Text(stringResource(R.string.link_how_to) + " " +
                        (if (typing) stringResource(R.string.link_how_type) else stringResource(R.string.link_how_scan)),
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s is LinkStep.Failed) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large) {
                        Text(s.why, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!typing) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.extraLarge).background(com.past9.phoneaos.ui.theme.Media.qr), contentAlignment = Alignment.Center) {
                            if (camOk) QrScanner(Modifier.fillMaxSize()) { scanned(it) }
                            else Button(onClick = { askCam.launch(Manifest.permission.CAMERA) }) { Icon(Icons.Rounded.PhotoCamera, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.link_allow_camera)) }
                        }
                        TextButton(onClick = { typing = true; if (step is LinkStep.Failed) step = LinkStep.Ready }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.link_type_instead)) }
                    } else {
                        val url = Pairing.address(address)
                        val sc = Pairing.shortCode(code)
                        fun link() { if (url != null && sc != null) { step = LinkStep.Busy(url.removePrefix("https://")); scope.launch { step = pair(context, null, url, sc) } } }
                        com.past9.phoneaos.ui.AppTextField(address, { address = it }, label = { Text(stringResource(R.string.link_address_label)) }, placeholder = { Text("192.168.1.20 or my-pc.local") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
                        com.past9.phoneaos.ui.AppTextField(code, { code = it.uppercase().take(9) }, label = { Text(stringResource(R.string.link_code_label)) }, placeholder = { Text("ABCD-EFGH") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { link() }))
                        if (address.isNotBlank() && url == null) Text(if (address.trim().startsWith("http://", true)) stringResource(R.string.link_https_only) else stringResource(R.string.link_not_address),
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { link() },
                            enabled = url != null && sc != null, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.link_link)) }
                        TextButton(onClick = { typing = false; if (step is LinkStep.Failed) step = LinkStep.Ready }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.link_scan_instead)) }
                    }
                }
            }
        }
    }
}

/**
 * The back camera, reading QR codes until one is found. One scanner for the computer link and for pairing a watch.
 *
 * Built to read a small, dense code on another screen held 10 to 20 cm away: a high-resolution analysis stream (the default 640x480 is too
 * coarse for a code on a watch face), continuous autofocus plus a refocus on the centre every couple of seconds (phones that hunt at close
 * range settle), tap to focus anywhere, an optional torch, and a TextureView preview so it draws inside a bottom sheet.
 */
@Composable
fun QrScanner(modifier: Modifier, torch: Boolean = false, onText: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val latest by rememberUpdatedState(onText)
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(context)
        val exec = Executors.newSingleThreadExecutor()
        val selector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
            .setAspectRatioStrategy(androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(androidx.camera.core.resolutionselector.ResolutionStrategy(android.util.Size(QrScan.ANALYSIS_W, QrScan.ANALYSIS_H),
                androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()
        val preview = Preview.Builder().setResolutionSelector(selector).build()
        val analysis = ImageAnalysis.Builder().setResolutionSelector(selector).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(exec) { img ->
            val text = try { QrDecode.decode(img) } catch (_: Exception) { null } finally { img.close() }
            if (text != null) view.post { latest(text) }
        }
        var gone = false
        val main = ContextCompat.getMainExecutor(context)
        future.addListener({
            if (!gone) runCatching {
                preview.setSurfaceProvider(view.surfaceProvider)
                camera = future.get().bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, main)
        onDispose {
            gone = true; camera = null
            // Never wait for the camera here: it may still be starting (seconds on some phones). Unbind once it is ready.
            future.addListener({ runCatching { future.get().unbind(preview, analysis) } }, main)
            exec.shutdown()
        }
    }
    val cam = camera
    LaunchedEffect(cam, torch) { if (cam != null && cam.cameraInfo.hasFlashUnit()) runCatching { cam.cameraControl.enableTorch(torch) } }
    // Refocus on the centre every couple of seconds, then hand back to continuous autofocus.
    LaunchedEffect(cam) {
        while (cam != null) {
            kotlinx.coroutines.delay(QrScan.REFOCUS_MS)
            runCatching { focusAt(cam, view, view.width / 2f, view.height / 2f) }
        }
    }
    AndroidView({ view }, modifier.pointerInput(cam) { detectTapGestures { p -> if (cam != null) runCatching { focusAt(cam, view, p.x, p.y) } } })
}

private fun focusAt(cam: androidx.camera.core.Camera, view: PreviewView, x: Float, y: Float) {
    if (view.width == 0 || view.height == 0) return
    val point = view.meteringPointFactory.createPoint(x, y)
    cam.cameraControl.startFocusAndMetering(androidx.camera.core.FocusMeteringAction.Builder(point,
        androidx.camera.core.FocusMeteringAction.FLAG_AF or androidx.camera.core.FocusMeteringAction.FLAG_AE)
        .setAutoCancelDuration(QrScan.FOCUS_HOLD_S, java.util.concurrent.TimeUnit.SECONDS).build())
}

/** What the scanner asks of the camera, kept in one place so a test can pin it. */
object QrScan {
    const val ANALYSIS_W = 1600
    const val ANALYSIS_H = 1200
    const val REFOCUS_MS = 2_500L
    const val FOCUS_HOLD_S = 2L
}

/**
 * Shown at the top ONLY when nothing can answer. While linked and working there is no bar and no machine name
 * anywhere in the conversation: failover between computers is silent. Which computer answers lives in
 * Settings > Linked devices.
 */
@Composable
fun RemoteTroubleBar(session: RemoteSession, onDevices: () -> Unit) {
    val state by session.state.collectAsStateWithLifecycle()
    if (!RemoteTrouble.shows(state)) return
    val cs = MaterialTheme.colorScheme
    val retrying = state as? LinkState.Retrying
    // The countdown on "Trying again in N s" ticks once a second while it shows.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    if (retrying != null) androidx.compose.runtime.LaunchedEffect(retrying) {
        while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1_000) }
    }
    val text = when (val s = state) {
        is LinkState.NoAgents -> s.why.ifBlank { stringResource(R.string.link_trouble) }
        is LinkState.Retrying -> s.why + ((s.atMs - now + 999) / 1000).let { " " + if (it > 0) stringResource(R.string.link_trying_in, it.toInt()) else stringResource(R.string.link_trying) }
        else -> stringResource(R.string.link_trouble)
    }
    Surface(color = cs.errorContainer) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 36.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(cs.error))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = cs.onErrorContainer, modifier = Modifier.weight(1f))
            TextButton(onClick = onDevices, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(stringResource(R.string.link_devices)) }
            TextButton(onClick = { session.retry() }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(stringResource(R.string.link_retry)) }
        }
    }
}

object RemoteTrouble {
    const val TEXT = "Nothing can answer right now. Your computers are off or out of reach."
    /** Only a real outage shows (nothing answers, or the last try failed and the next is waiting); connecting, paused and a silent switch to another computer do not. */
    fun shows(state: LinkState): Boolean = state is LinkState.NoAgents || state is LinkState.Retrying
}

/** Settings > Linked devices. */
@Composable
fun LinkedDevicesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val link by RemoteHub.link.collectAsStateWithLifecycle()
    val session by RemoteHub.session.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    val l = link
    if (l == null || adding) { LinkScreen(onBack = { if (adding) adding = false else onBack() }, onLinked = { adding = false }); return }
    val health by (session?.health ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, Health?>()) }).collectAsStateWithLifecycle()
    val state by (session?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow<LinkState>(LinkState.Paused) }).collectAsStateWithLifecycle()
    var confirmUnlink by remember { mutableStateOf(false) }
    SubScreen(stringResource(R.string.settings_linked), l.nodeName.ifBlank { null }, onBack) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.link_use_on, l.nodeName.ifBlank { stringResource(R.string.link_my_computer) }), style = MaterialTheme.typography.titleSmall)
                        Text(if (l.on) stringResource(R.string.link_on_body)
                            else stringResource(R.string.link_off_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(l.on, { RemoteHub.setOn(context, it) })
                }
            }
            SectionHeader(stringResource(R.string.link_computers), stringResource(R.string.link_computers_sub), Modifier.padding(start = 4.dp, top = 8.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                BrainChooser.ordered(l.brains).forEachIndexed { i, b ->
                    val h = health[b.id]
                    val answering = (state as? LinkState.Connected)?.brain?.id == b.id
                    val line = when {
                        !l.on -> stringResource(R.string.settings_not_in_use)
                        answering -> stringResource(R.string.link_answering)
                        !health.containsKey(b.id) -> stringResource(R.string.link_checking)
                        h == null -> stringResource(R.string.link_not_answering)
                        !h.ready -> stringResource(R.string.link_not_ready)
                        h.busy -> stringResource(R.string.link_ready_busy)
                        else -> stringResource(R.string.link_ready)
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.name, style = MaterialTheme.typography.titleSmall)
                            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box(Modifier.size(10.dp).clip(CircleShape).background(when { answering -> com.past9.phoneaos.ui.theme.Lights.live; h?.ready == true -> com.past9.phoneaos.ui.theme.Lights.ready; else -> MaterialTheme.colorScheme.outlineVariant }))
                    }
                }
                val trouble = (state as? LinkState.NoAgents)?.why ?: (state as? LinkState.Retrying)?.why
                if (l.on && trouble != null) Text(trouble, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                if (l.on && (state as? LinkState.NoAgents)?.rescan == true) TextButton(onClick = { adding = true }, modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.link_scan_again)) }
                if (l.on) TextButton(onClick = { session?.retry() }, modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.link_check_again)) }
            }
            SectionHeader(stringResource(R.string.link_lend), stringResource(R.string.link_lend_sub), Modifier.padding(start = 4.dp, top = 8.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                DeviceTools.GROUPS.keys.forEach { group ->
                    var on by remember(group) { mutableStateOf(DeviceTools.allowed(context, group)) }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(DeviceTools.LABELS[group]?.let { stringResource(it) } ?: group, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Switch(on, { on = it; DeviceTools.setAllowed(context, group, it); session?.factsChangedForce() })
                    }
                }
            }
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                TextButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.link_different)) }
                TextButton(onClick = { confirmUnlink = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.link_unlink_phone)) }
            }
        }
    }
    if (confirmUnlink) com.past9.phoneaos.ui.ConfirmSheet(androidx.compose.material.icons.Icons.Rounded.LinkOff, stringResource(R.string.link_linked_computer), stringResource(R.string.link_unlink_confirm),
        stringResource(R.string.link_unlink_body, l.nodeName.ifBlank { stringResource(R.string.link_the_computer) }),
        confirm = stringResource(R.string.link_unlink), onConfirm = { confirmUnlink = false; RemoteHub.unlink(context); onBack() }, onDismiss = { confirmUnlink = false }, cancel = stringResource(R.string.link_keep_linked), destructive = true)
}
