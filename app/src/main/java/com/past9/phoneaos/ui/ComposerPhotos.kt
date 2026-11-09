package com.past9.phoneaos.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.ui.theme.Radius

/**
 * A small picture of a photo kept on this phone, read straight from its file (no loader, nothing over the network).
 * Null when the file is gone or is not a picture: the tile then shows a plain picture mark, never a broken image.
 */
internal fun photoThumb(path: String, edgePx: Int): ImageBitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
        else {
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edgePx.coerceAtLeast(1)) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
    } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
}

/**
 * The photos waiting in a message box, above the words: one small rounded tile each, with an X to take it out. They
 * come from the camera or the gallery and go with the next send, with or without words. When the send did not get
 * through ([failed]) every tile says so and a tap on it tries again; the photos stay until they are sent or removed.
 */
@Composable
fun ComposerPhotos(photos: List<String>, onRemove: (String) -> Unit, failed: Boolean = false, onRetry: (() -> Unit)? = null) {
    if (photos.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Radius.sm)
    val edge = with(LocalDensity.current) { 72.dp.roundToPx() }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp).testTag("composer-photos"),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        photos.forEachIndexed { i, path ->
            val n = i + 1
            val thumb = remember(path, edge) { photoThumb(path, edge) }
            Box(Modifier.size(72.dp)) {
                Surface(shape = shape, color = cs.surfaceContainerHigh, border = BorderStroke(1.dp, cs.outlineVariant), modifier = Modifier.fillMaxSize()) {
                    if (thumb != null) Image(thumb, "Photo $n to send", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Image, "Photo $n to send", Modifier.size(28.dp), tint = cs.onSurfaceVariant) }
                }
                if (failed) Box(Modifier.fillMaxSize().clip(shape).background(cs.scrim.copy(alpha = 0.5f))
                    .then(if (onRetry != null) Modifier.clickable(onClickLabel = "Try again", role = Role.Button, onClick = onRetry) else Modifier), contentAlignment = Alignment.Center) {
                    Surface(shape = CircleShape, color = cs.errorContainer, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.Replay, "Photo $n did not send. Try again", Modifier.padding(6.dp), tint = cs.onErrorContainer)
                    }
                }
                // A 40 dp target around a 22 dp mark, in the tile's top corner.
                Box(Modifier.align(Alignment.TopEnd).size(40.dp).clip(CircleShape).clickable(onClickLabel = "Remove photo $n", role = Role.Button) { onRemove(path) }) {
                    Surface(shape = CircleShape, color = cs.inverseSurface, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp)) {
                        Icon(Icons.Rounded.Close, "Remove photo $n", Modifier.padding(4.dp), tint = cs.inverseOnSurface)
                    }
                }
            }
        }
    }
}
