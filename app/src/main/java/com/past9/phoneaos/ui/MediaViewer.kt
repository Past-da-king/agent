package com.past9.phoneaos.ui

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.SubcomposeAsyncImage
import java.io.File

/**
 * The picture or video a person opened from a card in the chat, drawn at the top of the app's own window.
 *
 * It used to be a Dialog with `usePlatformDefaultWidth = false`, which is a window of its own: on the A35 (3 Nov 2026) it
 * came up as a small floating photo in the top right, with the app's icon in its title bar, on top of the chat and hard to
 * get rid of. A window that is not part of the app cannot be swiped away with the chat, so the whole class is gone: this
 * overlay lives inside the app, above everything, and the back gesture, a tap outside the picture and the close button all
 * dismiss it. It can never cover only part of the chat, and it can never outlive the screen it was opened from.
 */
object MediaViewer {
    var shown by mutableStateOf<Target?>(null)
        private set

    /** Show a picture (from a file on the phone) or a video (from a local url). */
    fun show(target: Target) {
        shown = target
    }

    fun close() {
        shown = null
    }
}

/** What is open: a picture, or a video, and the name to show under it. */
data class Target(val name: String, val picture: File? = null, val video: String? = null)

/** Where the overlay is drawn. One of these at the root of the app, above whatever screen is showing. */
@Composable
fun MediaViewerHost() {
    val target = MediaViewer.shown ?: return
    BackHandler { MediaViewer.close() }
    var zoom by remember(target) { mutableFloatStateOf(1f) }
    var pan by remember(target) { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .background(com.past9.phoneaos.ui.theme.Media.stage)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { MediaViewer.close() },
        contentAlignment = Alignment.Center,
    ) {
        // Everything inside this box eats its own taps, so only a tap outside the picture closes it.
        Box(
            Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {},
            contentAlignment = Alignment.Center,
        ) {
            if (target.picture != null) {
                SubcomposeAsyncImage(
                    model = target.picture,
                    contentDescription = target.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(target) {
                            detectTransformGestures { _, by, factor, _ ->
                                zoom = (zoom * factor).coerceIn(1f, 5f)
                                pan = if (zoom == 1f) Offset.Zero else pan + by
                            }
                        }
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = pan.x
                            translationY = pan.y
                        },
                )
            } else if (target.video != null) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    factory = { context ->
                        VideoView(context).apply {
                            setVideoURI(Uri.parse(target.video))
                            val controller = MediaController(context)
                            controller.setAnchorView(this)
                            setMediaController(controller)
                            setOnPreparedListener {
                                controller.show()
                                start()
                            }
                        }
                    },
                )
            }
        }
        FilledTonalIconButton(
            onClick = { MediaViewer.close() },
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
        ) { Icon(Icons.Rounded.Close, "Close") }
        Text(
            target.name,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.72f),
            maxLines = 1,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
        )
    }
}
