package com.past9.phoneaos.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.past9.phoneaos.ui.AgentAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** The card's dark stage; the app's theme colours come in through the accents (send, caption). */
private val Stage = Color(0xFF111216)
private val OnStage = Color(0xFFF1F0F4)
private val Glass = Color(0x73000000)
private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val EmphasizedIn = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

private enum class Flash(val mode: Int, val label: String) {
    OFF(ImageCapture.FLASH_MODE_OFF, "Flash off"), ON(ImageCapture.FLASH_MODE_ON, "Flash on"), AUTO(ImageCapture.FLASH_MODE_AUTO, "Flash auto");
    fun next() = entries[(ordinal + 1) % entries.size]
}

/**
 * The camera, opened in place over the chat: a rounded card that grows out of [origin] (the camera button),
 * dims the chat behind it, and can be dragged down to close. [onSend] gets the compressed photo and a caption,
 * to be sent exactly like a photo attached from the gallery.
 */
@Composable
fun CameraCard(origin: Rect, onClosed: () -> Unit, onSend: (path: String, caption: String) -> Unit, onGallery: () -> Unit, binder: CameraBinder? = null, startWith: String? = null) {
    val ctx = LocalContext.current
    val cam = binder ?: remember { CameraXBinder(ctx) }
    val host = LocalLifecycleOwner.current
    val cardLife = remember { CardLifecycle(host.lifecycle) }
    // Synchronous and idempotent: the camera is released the moment the card is closed or leaves the screen, by whatever path.
    fun release() { cam.unbind(); cardLife.destroy() }
    DisposableEffect(Unit) { onDispose { release() } }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var asked by remember { mutableStateOf(false) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    // Asked here, on the first tap of the camera button, and not at app start.
    LaunchedEffect(Unit) { if (!granted) perm.launch(Manifest.permission.CAMERA) }
    val hasCamera = remember { ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }

    val progress = remember { Animatable(0f) }
    val dragY = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(340, easing = Emphasized)) }
    val done = remember { BooleanArray(1) }
    fun finish() { if (!done[0]) { done[0] = true; onClosed() } }
    fun close() {
        if (closing) return
        closing = true
        release()
        // Whatever happens to the animation, the card still goes away.
        scope.launch { try { progress.animateTo(0f, tween(280, easing = EmphasizedIn)) } finally { finish() } }
    }

    var photo by remember { mutableStateOf<String?>(startWith) }
    var frozen by remember { mutableStateOf<Bitmap?>(null) }
    val reviewing = photo != null || frozen != null
    fun retake() { photo?.let { File(it).delete() }; photo = null; frozen = null }
    BackHandler { if (reviewing) retake() else close() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sw = with(density) { maxWidth.toPx() }; val sh = with(density) { maxHeight.toPx() }
        val margin = with(density) { 12.dp.toPx() }
        val bottomInset = maxOf(WindowInsets.navigationBars.getBottom(density), WindowInsets.ime.getBottom(density)).toFloat()
        val topInset = WindowInsets.statusBars.getTop(density).toFloat()
        val tw = sw - 2 * margin
        val th = minOf(sh * 0.63f, sh - bottomInset - margin - topInset - margin * 2)
        val tx = margin; val ty = sh - bottomInset - margin - th
        val cornerFrom = minOf(origin.width, origin.height) / 2f; val cornerTo = with(density) { 30.dp.toPx() }

        Box(Modifier.fillMaxSize().graphicsLayer { alpha = (progress.value * (1f - (dragY.value / th).coerceIn(0f, 1f))) * 0.45f }.background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (!reviewing) close() })

        Box(
            Modifier
                .offset { IntOffset(lerp(origin.left, tx, progress.value).roundToInt(), (lerp(origin.top, ty, progress.value) + dragY.value).roundToInt()) }
                .layout { m, _ ->
                    val w = lerp(origin.width, tw, progress.value).roundToInt().coerceAtLeast(1); val h = lerp(origin.height, th, progress.value).roundToInt().coerceAtLeast(1)
                    val p = m.measure(Constraints.fixed(w, h)); layout(w, h) { p.place(0, 0) }
                }
                .graphicsLayer { shape = RoundedCornerShape(lerp(cornerFrom, cornerTo, progress.value)); clip = true }
                .background(Stage).testTag("camera-card")
                .draggable(rememberDraggableState { d -> scope.launch { dragY.snapTo(if (dragY.value + d < 0f) dragY.value + d * 0.25f else dragY.value + d) } }, Orientation.Vertical,
                    onDragStopped = { v ->
                        if (closing) return@draggable
                        if (dragY.value > th * 0.25f || v > 1400f) {
                            closing = true; release()
                            try { dragY.animateTo(th + margin * 3, tween(220, easing = EmphasizedIn)) } finally { finish() }
                        }
                        else dragY.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium))
                    })
        ) {
            // The content keeps its final size and the card's edge reveals it, so the preview never resizes mid-animation.
            Box(Modifier.layout { m, c ->
                val p = m.measure(Constraints.fixed(tw.roundToInt(), th.roundToInt()))
                layout(c.maxWidth, c.maxHeight) { p.place((c.maxWidth - p.width) / 2, (c.maxHeight - p.height) / 2) }
            }.graphicsLayer { alpha = ((progress.value - 0.25f) / 0.6f).coerceIn(0f, 1f) }) {
                when {
                    !hasCamera -> Calm("There is no camera on this device.", "You can still attach a photo from your gallery.", null, null, onGallery, ::close)
                    !granted -> Calm("The camera is off.", "Your agent only sees a photo when you take it and press Send.",
                        if (asked) "Open settings" else null, { runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                        onGallery, ::close, retry = if (asked) ({ perm.launch(Manifest.permission.CAMERA) }) else null)
                    else -> Live(cam, cardLife, progress, frozen, photo, onFrozen = { frozen = it }, onPhoto = { photo = it }, onRetake = ::retake, onClose = ::close, onGallery = onGallery, onSend = { cap -> photo?.let { onSend(it, cap); close() } })
                }
            }
        }
    }
}

@Composable
private fun Calm(title: String, line: String, action: String?, onAction: (() -> Unit)?, onGallery: () -> Unit, onClose: () -> Unit, retry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize()) {
        CircleTool(Icons.Rounded.KeyboardArrowDown, "Close camera", onClose, Modifier.align(Alignment.TopStart).padding(14.dp))
        Column(Modifier.align(Alignment.Center).padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AgentAvatar(working = false, size = 64.dp)
            Spacer(Modifier.height(18.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = OnStage, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(line, style = MaterialTheme.typography.bodyMedium, color = OnStage.copy(alpha = 0.72f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            if (action != null && onAction != null) Button(onClick = onAction) { Text(action) }
            if (retry != null) TextButton(onClick = retry) { Text("Try again") }
            FilledTonalButton(onClick = onGallery) { Icon(Icons.Rounded.Image, null); Spacer(Modifier.width(8.dp)); Text("Choose from gallery") }
        }
    }
}

@Composable
private fun Live(
    binder: CameraBinder, owner: LifecycleOwner, progress: Animatable<Float, AnimationVector1D>, frozen: Bitmap?, photo: String?,
    onFrozen: (Bitmap?) -> Unit, onPhoto: (String) -> Unit, onRetake: () -> Unit, onClose: () -> Unit, onGallery: () -> Unit, onSend: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var front by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(Flash.OFF) }
    var failed by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var hasFlash by remember { mutableStateOf(false) }
    var streaming by remember { mutableStateOf(false) }
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    LaunchedEffect(flash) { capture.flashMode = flash.mode }
    DisposableEffect(previewView) {
        val obs = androidx.lifecycle.Observer<PreviewView.StreamState> { streaming = it == PreviewView.StreamState.STREAMING }
        previewView.previewStreamState.observe(owner, obs)
        onDispose { previewView.previewStreamState.removeObserver(obs) }
    }

    // The provider is fetched off the main thread; binding waits until the card has mostly grown so the animation stays smooth.
    LaunchedEffect(front) {
        runCatching {
            snapshotFlow { progress.value }.first { it >= 0.6f }
            val cam = binder.bind(owner, front, previewView.surfaceProvider, capture)
            camera = cam; hasFlash = cam?.cameraInfo?.hasFlashUnit() == true; failed = null
        }.onFailure { if (it !is kotlinx.coroutines.CancellationException) failed = "The camera would not start." }
    }

    fun shoot() {
        if (busy || !streaming) return
        busy = true
        onFrozen(previewView.bitmap)
        val raw = File(ctx.cacheDir, "shot-${System.currentTimeMillis()}.jpg")
        val opts = ImageCapture.OutputFileOptions.Builder(raw).setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = front }).build()
        capture.takePicture(opts, ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                scope.launch {
                    val path = withContext(Dispatchers.IO) { CameraPhoto.compress(ctx, raw) }
                    busy = false
                    if (path != null) onPhoto(path) else { onFrozen(null); failed = "That photo could not be saved. Try again." }
                }
            }
            override fun onError(e: ImageCaptureException) { busy = false; raw.delete(); onFrozen(null); failed = "That photo could not be taken. Try again." }
        })
    }

    val reviewing = frozen != null || photo != null
    Box(Modifier.fillMaxSize()) {
        // Live preview, faded in only once the first frame is streaming.
        val previewAlpha by animateFloatAsState(if (streaming) 1f else 0f, tween(220), label = "preview")
        val shrink by animateFloatAsState(if (reviewing) 0.96f else 1f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium), label = "shrink")
        AndroidView({ previewView }, Modifier.fillMaxSize().testTag("camera-preview").graphicsLayer { alpha = previewAlpha; scaleX = shrink; scaleY = shrink })
        // Touches never reach the preview view itself: this layer sits above it, zooms with two fingers, and leaves one finger
        // alone so the card can be dragged down from anywhere on the picture.
        val live by rememberUpdatedState(camera)
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val e = awaitPointerEvent()
                    if (e.changes.size >= 2 && e.changes.all { it.pressed }) {
                        val z = e.calculateZoom(); val c = live; val s = c?.cameraInfo?.zoomState?.value
                        if (c != null && s != null && z != 1f) c.cameraControl.setZoomRatio((s.zoomRatio * z).coerceIn(s.minZoomRatio, s.maxZoomRatio))
                        e.changes.forEach { it.consume() }
                    }
                } while (e.changes.any { it.pressed })
            }
        })
        // Freeze-frame, then the real photo.
        if (reviewing) Box(Modifier.fillMaxSize().graphicsLayer { scaleX = shrink; scaleY = shrink }.clip(RoundedCornerShape(24.dp)).background(Stage)) {
            if (photo != null) coil.compose.AsyncImage(File(photo), "The photo you took", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else frozen?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }

        if (!reviewing) {
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { CircleTool(Icons.Rounded.KeyboardArrowDown, "Close camera", onClose) }
                Shutter(enabled = !busy && failed == null && streaming, onClick = ::shoot)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { CircleTool(Icons.Rounded.MoreHoriz, "More camera options", { menu = !menu }) }
            }
            AnimatedVisibility(menu, Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 88.dp), enter = fadeIn(tween(140)) + scaleIn(tween(180), 0.9f, androidx.compose.ui.graphics.TransformOrigin(1f, 1f)), exit = fadeOut(tween(100)) + scaleOut(tween(120), 0.9f, androidx.compose.ui.graphics.TransformOrigin(1f, 1f))) {
                Surface(shape = RoundedCornerShape(20.dp), color = Color(0xE61B1C22), contentColor = OnStage) {
                    Column(Modifier.padding(vertical = 6.dp).width(220.dp)) {
                        MenuRow(Icons.Rounded.FlipCameraAndroid, "Flip camera") { menu = false; front = !front }
                        if (hasFlash && !front) MenuRow(when (flash) { Flash.OFF -> Icons.Rounded.FlashOff; Flash.ON -> Icons.Rounded.FlashOn; Flash.AUTO -> Icons.Rounded.FlashAuto }, flash.label) { flash = flash.next() }
                        MenuRow(Icons.Rounded.Image, "Choose from gallery") { menu = false; onGallery() }
                    }
                }
            }
        } else Review(photo != null, onRetake, onSend)

        failed?.let { msg ->
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.align(Alignment.Center).padding(28.dp)) {
                Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    AgentAvatar(working = false, size = 48.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(msg, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { failed = null }) { Text("Dismiss") }
                        FilledTonalButton(onClick = onClose) { Text("Close") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Shutter(enabled: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.92f else 1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "shutter")
    Box(
        Modifier.size(72.dp).graphicsLayer { scaleX = s; scaleY = s; alpha = if (enabled) 1f else 0.6f }.clip(CircleShape).background(Color.White)
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick).semantics { contentDescription = "Take photo" },
        contentAlignment = Alignment.Center
    ) { Box(Modifier.size(62.dp).border(2.dp, Color(0x40000000), CircleShape)) }
}

@Composable
private fun CircleTool(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, shape = CircleShape, color = Glass, modifier = modifier.size(48.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, label, tint = Color.White) }
    }
}

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp)); Spacer(Modifier.width(14.dp)); Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Retake / Send with an optional caption, inside the card; the card rises with the keyboard. */
@Composable
private fun BoxScope.Review(ready: Boolean, onRetake: () -> Unit, onSend: (String) -> Unit) {
    var caption by remember { mutableStateOf("") }
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = RoundedCornerShape(26.dp), color = Color(0xCC1B1C22), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
            Box(Modifier.padding(horizontal = 18.dp, vertical = 13.dp)) {
                if (caption.isEmpty()) Text("Add a caption (optional)", style = MaterialTheme.typography.bodyLarge, color = OnStage.copy(alpha = 0.6f))
                BasicTextField(caption, { caption = it }, textStyle = MaterialTheme.typography.bodyLarge.copy(color = OnStage), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), maxLines = 3, modifier = Modifier.fillMaxWidth())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onRetake, Modifier.weight(1f).heightIn(min = 52.dp)) { Icon(Icons.Rounded.Replay, null); Spacer(Modifier.width(8.dp)); Text("Retake") }
            Button(onClick = { onSend(caption.trim()) }, enabled = ready, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Icon(Icons.AutoMirrored.Rounded.Send, null); Spacer(Modifier.width(8.dp)); Text("Send") }
        }
    }
}
