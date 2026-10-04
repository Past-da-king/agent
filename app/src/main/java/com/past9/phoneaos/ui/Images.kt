package com.past9.phoneaos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage

data class ChatImage(val url: String, val alt: String)

private val imageToken = Regex("""!\[([^\]]*)]\(([^)\s]+)(?:\s+"[^"]*")?\)""")

/** Pull markdown images out of a line: the text without them, and the images in order. */
fun splitImages(line: String): Pair<String, List<ChatImage>> {
    val imgs = imageToken.findAll(line).map { ChatImage(it.groupValues[2], it.groupValues[1]) }.toList()
    return imageToken.replace(line, "").replace(Regex("\\s{2,}"), " ").trim() to imgs
}

/**
 * Images inside a reply. One image: full width, height capped so a huge image never takes over
 * the screen. Several: a horizontal strip you swipe through. Tap any image to see it full screen.
 */
@Composable
fun ImageStrip(images: List<ChatImage>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf<ChatImage?>(null) }
    if (images.size == 1) {
        // No fixed box: the image sizes to its own shape, scaled down to fit the width and 340dp tall.
        ChatImageView(images[0], Modifier.heightIn(max = 340.dp), ContentScale.Fit) { open = images[0] }
    } else {
        LazyRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 24.dp)) {
            itemsIndexed(images) { i, img ->
                Box {
                    ChatImageView(img, Modifier.height(200.dp).widthIn(min = 120.dp, max = 300.dp), ContentScale.Crop) { open = img }
                    Surface(Modifier.align(Alignment.TopEnd).padding(8.dp), shape = MaterialTheme.shapes.small, color = Color.Black.copy(alpha = 0.55f)) {
                        Text("${i + 1}/${images.size}", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium, color = Color.White)
                    }
                }
            }
        }
    }
    open?.let { FullImage(it) { open = null } }
}

@Composable
private fun ChatImageView(img: ChatImage, modifier: Modifier, scale: ContentScale, onClick: () -> Unit) {
    SubcomposeAsyncImage(
        model = img.url, contentDescription = img.alt.ifBlank { "Image" }, contentScale = scale,
        modifier = modifier.clip(MaterialTheme.shapes.large).clickable(onClick = onClick),
        loading = { Box(Modifier.size(220.dp, 160.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(36.dp)) } },
        error = {
            Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh).defaultMinSize(200.dp, 80.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.BrokenImage, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text(img.alt.ifBlank { "Image didn't load" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
    )
}

/** Full-screen viewer: pinch to zoom, drag to pan, tap ✕ to close. */
@Composable
private fun FullImage(img: ChatImage, onClose: () -> Unit) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0E0D12))) {
            SubcomposeAsyncImage(model = img.url, contentDescription = img.alt, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTransformGestures { _, p, z, _ -> zoom = (zoom * z).coerceIn(1f, 5f); pan = if (zoom == 1f) androidx.compose.ui.geometry.Offset.Zero else pan + p }
                }.graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y })
            FilledTonalIconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)) { Icon(Icons.Rounded.Close, "Close") }
            if (img.alt.isNotBlank()) Text(img.alt, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp), color = Color(0xFFE9E7EE), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
