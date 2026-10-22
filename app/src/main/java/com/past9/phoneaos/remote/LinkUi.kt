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
                mapOf(DecodeHintType.TRY_HARDER to true)).text
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
    /** Linked. [checkWords] is set on the typed-code path: the certificate was trusted on first use, so show it to compare. */
    data class Done(val name: String, val checkWords: String?) : LinkStep
}

/** Pair with a scanned offer or a typed address + code. Returns what to show next. */
private suspend fun pair(context: Context, offer: PairOffer?, typedUrl: String?, code: String): LinkStep = withContext(Dispatchers.IO) {
    val facts = phoneFacts(context)
    if (offer != null) {
        val trust = Pinning.PinnedTrust({ listOfNotNull(offer.fp) })
        val api = RemoteApi(Pinning.client(sharedHttp, trust))
        var last = "Couldn't reach the computer."
        for (u in offer.urls) {
            try {
                val body = api.pair(u, offer.code, facts)
                val paired = Pairing.parsePaired(body, BrainRef(offer.nodeId.ifBlank { "node" }, offer.name, offer.urls, offer.fp!!, 0))
                    ?: return@withContext LinkStep.Failed("The computer's answer didn't make sense. Is its Agent app up to date?")
                RemoteHub.linked(context, paired)
                return@withContext LinkStep.Done(paired.nodeName, null)
            } catch (e: PairException) {
                last = e.message ?: last
                if (!e.unreachable) return@withContext LinkStep.Failed(last)
            }
        }
        return@withContext LinkStep.Failed(last + if (offer.urls.size > 1) " (tried ${offer.urls.size} addresses)" else "")
    }
    val url = typedUrl ?: return@withContext LinkStep.Failed("Type the computer's address.")
    val trust = Pinning.PinnedTrust({ emptyList() }, trustOnFirstUse = true)
    val api = RemoteApi(Pinning.client(sharedHttp, trust))
    try {
        val h = api.health(url, 5_000)
        val body = api.pair(url, code, facts)
        val fp = trust.seen ?: return@withContext LinkStep.Failed("The computer sent no certificate.")
        val paired = Pairing.parsePaired(body, BrainRef(h?.id?.ifBlank { null } ?: "node", h?.name?.ifBlank { null } ?: url.removePrefix("https://"), listOf(url), fp, 0))
            ?: return@withContext LinkStep.Failed("The computer's answer didn't make sense. Is its Agent app up to date?")
        RemoteHub.linked(context, paired)
        LinkStep.Done(paired.nodeName, Pairing.fingerprintWords(fp))
    } catch (e: PairException) { LinkStep.Failed(e.message ?: "Linking failed.") }
}

/** "Use the agent on my computer": scan the QR the computer shows, or type its address and the short code. */
@Composable
fun LinkScreen(onBack: () -> Unit, onLinked: () -> Unit, offerText: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // A link code that came with the screen goes straight to linking: the camera is never started for it.
    var step by remember { mutableStateOf<LinkStep>(if (offerText != null) LinkStep.Busy("your computer") else LinkStep.Ready) }
    var typing by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var camOk by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val askCam = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camOk = it }
    fun scanned(text: String) {
        if (step is LinkStep.Done || (step is LinkStep.Busy && text != offerText)) return
        val offer = Pairing.parseQr(text) ?: run { step = LinkStep.Failed("That QR code isn't an Agent link code."); return }
        step = LinkStep.Busy(offer.name)
        scope.launch { step = pair(context, offer, null, offer.code) }
    }
    LaunchedEffect(offerText) { offerText?.let { scanned(it) } }
    SubScreen("Use the agent on my computer", "Link this phone to a computer or server", onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (val s = step) {
                is LinkStep.Done -> {
                    AppCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text("Linked to ${s.name}", style = MaterialTheme.typography.titleLarge)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Your agent runs on ${s.name}. This phone shows its chat, memory, tasks and routines, and holds no AI keys for it.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (s.checkWords != null) {
                            Spacer(Modifier.height(12.dp))
                            Text("Check that ${s.name} shows this code next to this phone:", style = MaterialTheme.typography.bodyMedium)
                            Text(s.checkWords, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Button(onClick = onLinked, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Done") }
                    if (s.checkWords != null) TextButton(onClick = { RemoteHub.unlink(context); step = LinkStep.Failed("Unlinked. Make a new code on your computer and try again.") }, modifier = Modifier.fillMaxWidth()) {
                        Text("It shows something else: unlink")
                    }
                }
                is LinkStep.Busy -> AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(14.dp))
                        Text("Linking to ${s.to}...", style = MaterialTheme.typography.titleMedium)
                    }
                }
                else -> {
                    Text("On your computer, open the Agent app and go to Settings, Devices, Add a device. " +
                        (if (typing) "Type the address and the code it shows." else "Point this camera at the code it shows."),
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s is LinkStep.Failed) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large) {
                        Text(s.why, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!typing) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.extraLarge).background(Color.Black), contentAlignment = Alignment.Center) {
                            if (camOk) QrScanner(Modifier.fillMaxSize()) { scanned(it) }
                            else Button(onClick = { askCam.launch(Manifest.permission.CAMERA) }) { Icon(Icons.Rounded.PhotoCamera, null); Spacer(Modifier.width(8.dp)); Text("Allow the camera") }
                        }
                        TextButton(onClick = { typing = true; if (step is LinkStep.Failed) step = LinkStep.Ready }, modifier = Modifier.fillMaxWidth()) { Text("Type the address and code instead") }
                    } else {
                        val url = Pairing.address(address)
                        val sc = Pairing.shortCode(code)
                        fun link() { if (url != null && sc != null) { step = LinkStep.Busy(url.removePrefix("https://")); scope.launch { step = pair(context, null, url, sc) } } }
                        OutlinedTextField(address, { address = it }, label = { Text("Computer's address") }, placeholder = { Text("192.168.1.20 or my-pc.local") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
                        OutlinedTextField(code, { code = it.uppercase().take(9) }, label = { Text("Code") }, placeholder = { Text("ABCD-EFGH") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { link() }))
                        if (address.isNotBlank() && url == null) Text(if (address.trim().startsWith("http://", true)) "Linking only works over https." else "That doesn't look like an address.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { link() },
                            enabled = url != null && sc != null, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Link") }
                        TextButton(onClick = { typing = false; if (step is LinkStep.Failed) step = LinkStep.Ready }, modifier = Modifier.fillMaxWidth()) { Text("Scan the QR code instead") }
                    }
                }
            }
        }
    }
}

/** The back camera, reading QR codes until one is found. */
@Composable
fun QrScanner(modifier: Modifier, onText: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val latest by rememberUpdatedState(onText)
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(context)
        val exec = Executors.newSingleThreadExecutor()
        val preview = Preview.Builder().build()
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(exec) { img ->
            val text = try { QrDecode.decode(img) } catch (_: Exception) { null } finally { img.close() }
            if (text != null) view.post { latest(text) }
        }
        var gone = false
        val main = ContextCompat.getMainExecutor(context)
        future.addListener({
            if (!gone) runCatching {
                preview.setSurfaceProvider(view.surfaceProvider)
                future.get().bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, main)
        onDispose {
            gone = true
            // Never wait for the camera here: it may still be starting (seconds on some phones). Unbind once it is ready.
            future.addListener({ runCatching { future.get().unbind(preview, analysis) } }, main)
            exec.shutdown()
        }
    }
    AndroidView({ view }, modifier)
}

/** The slim line at the top while linked: who is answering, or that nobody is. */
@Composable
fun RemoteStrip(session: RemoteSession) {
    val state by session.state.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    val none = state is LinkState.NoAgents
    val retrying = state as? LinkState.Retrying
    // The countdown on "Trying again in N s" ticks once a second while it shows.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    if (retrying != null) androidx.compose.runtime.LaunchedEffect(retrying) {
        while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1_000) }
    }
    Surface(color = if (none) cs.errorContainer else cs.surfaceContainer) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 30.dp).padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val (dot, text) = when (val s = state) {
                is LinkState.Connected -> Color(0xFF2E9E5B) to "Answered by ${s.brain.name}"
                LinkState.Connecting -> Color(0xFFE0A100) to "Connecting to ${session.link.nodeName.ifBlank { "your computer" }}..."
                LinkState.Paused -> Color(0xFFE0A100) to "Linked to ${session.link.nodeName.ifBlank { "your computer" }}"
                is LinkState.Retrying -> Color(0xFFE0A100) to (s.why + ((s.atMs - now + 999) / 1000).let { if (it > 0) " Trying again in $it s." else " Trying again..." })
                is LinkState.NoAgents -> cs.error to s.why
            }
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = if (none) cs.onErrorContainer else cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (none || retrying != null) TextButton(onClick = { session.retry() }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Retry") }
        }
    }
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
    SubScreen("Linked devices", l.nodeName.ifBlank { null }, onBack) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Use the agent on ${l.nodeName.ifBlank { "my computer" }}", style = MaterialTheme.typography.titleSmall)
                        Text(if (l.on) "The chat, memory, tasks and routines here are your computer's. Your phone's own stay as they were."
                            else "Off: this phone uses its own agent and data.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(l.on, { RemoteHub.setOn(context, it) })
                }
            }
            SectionHeader("Computers that can answer", "Tried in this order. Change the order on a computer: Settings, Devices.", Modifier.padding(start = 4.dp, top = 8.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                BrainChooser.ordered(l.brains).forEachIndexed { i, b ->
                    val h = health[b.id]
                    val answering = (state as? LinkState.Connected)?.brain?.id == b.id
                    val line = when {
                        !l.on -> "Not in use"
                        answering -> "Answering now"
                        !health.containsKey(b.id) -> "Checking..."
                        h == null -> "Not answering"
                        !h.ready -> "On, but can't run the agent now"
                        h.busy -> "Ready (busy)"
                        else -> "Ready"
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.name, style = MaterialTheme.typography.titleSmall)
                            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box(Modifier.size(10.dp).clip(CircleShape).background(when { answering -> Color(0xFF2E9E5B); h?.ready == true -> Color(0xFF8BC34A); else -> MaterialTheme.colorScheme.outlineVariant }))
                    }
                }
                val trouble = (state as? LinkState.NoAgents)?.why ?: (state as? LinkState.Retrying)?.why
                if (l.on && trouble != null) Text(trouble, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                if (l.on && (state as? LinkState.NoAgents)?.rescan == true) TextButton(onClick = { adding = true }, modifier = Modifier.padding(start = 8.dp)) { Text("Scan the QR code again") }
                if (l.on) TextButton(onClick = { session?.retry() }, modifier = Modifier.padding(start = 8.dp)) { Text("Check again") }
            }
            SectionHeader("Let your computers use this phone's", "So the agent there can browse from here, read notifications you allowed, and ask you.", Modifier.padding(start = 4.dp, top = 8.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                DeviceTools.GROUPS.keys.forEach { group ->
                    var on by remember(group) { mutableStateOf(DeviceTools.allowed(context, group)) }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(DeviceTools.LABELS[group] ?: group, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Switch(on, { on = it; DeviceTools.setAllowed(context, group, it); session?.factsChangedForce() })
                    }
                }
            }
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                TextButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("Link to a different computer") }
                TextButton(onClick = { confirmUnlink = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Unlink this phone") }
            }
        }
    }
    if (confirmUnlink) ModalBottomSheet(onDismissRequest = { confirmUnlink = false }) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Unlink this phone?", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("This phone forgets ${l.nodeName.ifBlank { "the computer" }} and the copy of its data. Nothing on the computer changes, and this phone's own chat, memory and keys stay as they were.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Button(onClick = { confirmUnlink = false; RemoteHub.unlink(context); onBack() }, modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) { Text("Unlink") }
            TextButton(onClick = { confirmUnlink = false }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Keep it linked") }
        }
    }
}
