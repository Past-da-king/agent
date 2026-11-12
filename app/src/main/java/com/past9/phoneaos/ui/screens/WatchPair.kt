package com.past9.phoneaos.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.past9.phoneaos.App
import com.past9.phoneaos.R
import com.past9.phoneaos.remote.QrScanner
import com.past9.phoneaos.ui.AppSheet
import com.past9.phoneaos.ui.theme.Media
import com.past9.phoneaos.wear.WatchPairing
import com.past9.phoneaos.wearproto.PairCode
import com.past9.phoneaos.wearproto.PairProblem
import com.past9.phoneaos.wearproto.PairReason
import com.past9.phoneaos.wearproto.PairReply
import kotlinx.coroutines.launch

/** Where the pairing sheet is. Every state has its own words; a failure says what is likely wrong, never just "no answer". */
sealed interface WatchPairState {
    data object Ready : WatchPairState
    data object Pairing : WatchPairState
    data class Paired(val name: String) : WatchPairState
    /** [cause] is what the phone worked out; [said] is the watch's own refusal (wrong code, expired, locked), shown as it is. */
    data class Problem(val cause: PairProblem?, val said: String = "") : WatchPairState
}

@StringRes private fun titleOf(p: PairProblem?): Int = when (p) {
    PairProblem.NOT_MAIN_PROFILE -> R.string.watch_problem_profile_title
    PairProblem.NO_PLAY_SERVICES -> R.string.watch_problem_play_title
    PairProblem.WATCH_NOT_CONNECTED -> R.string.watch_problem_link_title
    PairProblem.WIFI_UNREACHABLE -> R.string.watch_problem_wifi_title
    PairProblem.WATCH_DID_NOT_ANSWER -> R.string.watch_problem_silent_title
    PairProblem.TOLD_NO, null -> R.string.watch_problem_said_title
}

@StringRes private fun bodyOf(p: PairProblem?): Int = when (p) {
    PairProblem.NOT_MAIN_PROFILE -> R.string.watch_problem_profile
    PairProblem.NO_PLAY_SERVICES -> R.string.watch_problem_play
    PairProblem.WATCH_NOT_CONNECTED -> R.string.watch_problem_link
    PairProblem.WIFI_UNREACHABLE -> R.string.watch_problem_wifi
    PairProblem.WATCH_DID_NOT_ANSWER -> R.string.watch_problem_silent
    PairProblem.TOLD_NO, null -> R.string.watch_problem_said
}

/**
 * Pair a watch (a slide-up sheet): scan the QR the watch shows, or type the code under it (4 + 4, the dash is added for you). Once paired it
 * says which watch this phone serves, with Unpair. The scanner is the one the computer link uses.
 */
@Composable
fun WatchPairSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val g = remember { App.graph(context) }
    val scope = rememberCoroutineScope()
    val bound by WatchPairing.boundName.collectAsState()
    var code by remember { mutableStateOf("") }
    var torch by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf<WatchPairState?>(null) }
    var camOk by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val askCam = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camOk = it }
    // Reading the bound name once makes sure the binding is loaded before the first frame says "not paired".
    remember { WatchPairing.bound(g) }

    val state: WatchPairState = bound?.let { WatchPairState.Paired(it) } ?: attempt ?: WatchPairState.Ready

    fun finish(o: WatchPairing.Outcome) {
        attempt = when (val r = o.reply) {
            is PairReply.Accepted -> null
            is PairReply.Refused -> WatchPairState.Problem(o.problem, if (o.problem == PairProblem.TOLD_NO || o.problem == null) PairReason.text(r.reason) else "")
        }
    }
    fun start(run: suspend () -> WatchPairing.Outcome) {
        if (attempt == WatchPairState.Pairing) return
        attempt = WatchPairState.Pairing; torch = false
        scope.launch { finish(run()) }
    }

    WatchPairSheetView(
        state = state, code = code, onCode = { code = PairCode.clean(it) }, torch = torch, onTorch = { torch = it },
        cameraAllowed = camOk, onAllowCamera = { askCam.launch(Manifest.permission.CAMERA) },
        camera = { m -> QrScanner(m, torch = torch) { text -> start { WatchPairing.pairWithQr(g, text) } } },
        onPair = { start { WatchPairing.pairWithCode(g, code) } },
        onUnpair = { WatchPairing.unpair(g); attempt = null },
        onRetry = { attempt = null },
        onDismiss = onDismiss,
    )
}

/** The sheet without the phone behind it, so every state can be drawn and checked on its own. [camera] fills the scan window. */
@Composable
fun WatchPairSheetView(
    state: WatchPairState, code: String, onCode: (String) -> Unit, torch: Boolean, onTorch: (Boolean) -> Unit,
    cameraAllowed: Boolean, onAllowCamera: () -> Unit, camera: @Composable (Modifier) -> Unit,
    onPair: () -> Unit, onUnpair: () -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit,
) {
    val title = when (state) {
        is WatchPairState.Ready -> stringResource(R.string.watch_sheet_title)
        is WatchPairState.Pairing -> stringResource(R.string.watch_sheet_pairing)
        is WatchPairState.Paired -> stringResource(R.string.watch_pair_paired_with, state.name.ifBlank { stringResource(R.string.watch_pair_title) })
        is WatchPairState.Problem -> stringResource(R.string.watch_sheet_failed)
    }
    val primary = when (state) {
        is WatchPairState.Ready -> stringResource(R.string.watch_pair_pair)
        is WatchPairState.Pairing -> stringResource(R.string.watch_sheet_pairing)
        is WatchPairState.Paired -> stringResource(R.string.watch_pair_unpair)
        is WatchPairState.Problem -> stringResource(R.string.watch_sheet_retry)
    }
    AppSheet(Icons.Rounded.Watch, stringResource(R.string.watch_pair_title), title,
        onDismiss = onDismiss, primary = primary,
        primaryEnabled = when (state) { is WatchPairState.Ready -> PairCode.complete(code); is WatchPairState.Pairing -> false; else -> true },
        onPrimary = { when (state) { is WatchPairState.Ready -> onPair(); is WatchPairState.Paired -> onUnpair(); is WatchPairState.Problem -> onRetry(); else -> Unit } },
        secondary = { TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_close)) } }) {
        val cs = MaterialTheme.colorScheme
        when (state) {
            is WatchPairState.Ready -> {
                Text(stringResource(R.string.watch_pair_intro), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                ScanWindow(cameraAllowed, torch, onTorch, onAllowCamera, camera)
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.watch_sheet_or_type), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                PairCodeField(code, onCode, enabled = true, error = false, onDone = { if (PairCode.complete(code)) onPair() })
            }
            is WatchPairState.Pairing -> {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    LoadingIndicator(Modifier.size(56.dp))
                    Text(stringResource(R.string.watch_sheet_pairing_body), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
            is WatchPairState.Paired -> {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(color = cs.primaryContainer, shape = MaterialTheme.shapes.large) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = cs.onPrimaryContainer)
                            Text(stringResource(R.string.watch_sheet_paired_body), style = MaterialTheme.typography.bodyLarge, color = cs.onPrimaryContainer)
                        }
                    }
                    Text(stringResource(R.string.watch_pair_synced), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }
            is WatchPairState.Problem -> {
                Surface(color = cs.errorContainer, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.WarningAmber, null, tint = cs.onErrorContainer)
                            Text(stringResource(titleOf(state.cause)), style = MaterialTheme.typography.titleMedium, color = cs.onErrorContainer)
                        }
                        Text(if (state.said.isNotBlank()) state.said else stringResource(bodyOf(state.cause)), style = MaterialTheme.typography.bodyMedium, color = cs.onErrorContainer)
                    }
                }
                Spacer(Modifier.height(16.dp))
                PairCodeField(code, onCode, enabled = true, error = state.cause == PairProblem.TOLD_NO, onDone = { if (PairCode.complete(code)) onPair() })
            }
        }
    }
}

/** The camera window: a scan box so he knows where to aim, a torch, and the hint that matters (10 to 20 cm, tap to focus). */
@Composable
private fun ScanWindow(allowed: Boolean, torch: Boolean, onTorch: (Boolean) -> Unit, onAllow: () -> Unit, camera: @Composable (Modifier) -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(1.3f).clip(MaterialTheme.shapes.extraLarge).background(Media.stage), contentAlignment = Alignment.Center) {
        if (allowed) {
            camera(Modifier.fillMaxSize())
            ScanBrackets(Modifier.fillMaxSize())
            IconButton(onClick = { onTorch(!torch) }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(44.dp).clip(CircleShape).background(Media.glass)) {
                Icon(if (torch) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff, stringResource(R.string.watch_sheet_torch), tint = Media.onStage)
            }
            Text(stringResource(R.string.watch_sheet_hint), style = MaterialTheme.typography.labelLarge, color = Media.onStage, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).clip(CircleShape).background(Media.glass).padding(horizontal = 14.dp, vertical = 6.dp))
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(24.dp)) {
                Icon(Icons.Rounded.PhotoCamera, null, tint = Media.onStage)
                Text(stringResource(R.string.watch_sheet_camera_why), style = MaterialTheme.typography.bodyMedium, color = Media.onStage, textAlign = TextAlign.Center)
                TextButton(onClick = onAllow) { Text(stringResource(R.string.watch_pair_allow_camera)) }
            }
        }
    }
}

/** Four corners around the middle of the window: aim the watch's code inside them. */
@Composable
private fun ScanBrackets(modifier: Modifier) {
    val color = Media.onStage
    Canvas(modifier) {
        val side = size.minDimension * 0.62f
        val left = (size.width - side) / 2f; val top = (size.height - side) / 2f
        val arm = side * 0.2f; val w = 4.dp.toPx()
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(color, Offset(x, y), Offset(x + dx * arm, y), w, StrokeCap.Round)
            drawLine(color, Offset(x, y), Offset(x, y + dy * arm), w, StrokeCap.Round)
        }
        corner(left, top, 1f, 1f); corner(left + side, top, -1f, 1f); corner(left, top + side, 1f, -1f); corner(left + side, top + side, -1f, -1f)
    }
}

/**
 * The code, as the watch shows it: two groups of four, the dash drawn for you. One hidden field holds the text, so typing, pasting a whole
 * "9H4T-5PBS" or "9H4T5PBS", and the keyboard's own suggestions all go through [PairCode.clean] (upper case, no dash, no letters the code never uses).
 */
@Composable
fun PairCodeField(value: String, onValue: (String) -> Unit, enabled: Boolean, error: Boolean, onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val clip = LocalClipboardManager.current
    var focused by remember { mutableStateOf(false) }
    val text = PairCode.clean(value)
    Column(Modifier.fillMaxWidth()) {
        BasicTextField(
            value = text, onValueChange = { onValue(PairCode.clean(it)) }, enabled = enabled, singleLine = true,
            textStyle = TextStyle(color = Color.Transparent), cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            decorationBox = { _ ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (i in 0 until PairCode.LENGTH) {
                        if (i == 4) Text("-", style = MaterialTheme.typography.titleLarge, color = cs.onSurfaceVariant, modifier = Modifier.width(12.dp), textAlign = TextAlign.Center)
                        val active = focused && i == text.length.coerceAtMost(PairCode.LENGTH - 1)
                        val edge = when { error -> cs.error; active -> cs.primary; else -> cs.surfaceContainerHighest }
                        Box(
                            Modifier.weight(1f).height(56.dp).clip(MaterialTheme.shapes.medium).background(cs.surfaceContainerHighest.copy(alpha = 0.55f))
                                .border(BorderStroke(if (active || error) 2.dp else 1.dp, edge), MaterialTheme.shapes.medium),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text.getOrNull(i)?.toString().orEmpty(), style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace), color = if (error) cs.error else cs.onSurface)
                        }
                    }
                }
            },
        )
        TextButton(onClick = { clip.getText()?.text?.let { onValue(PairCode.clean(it)) } }, enabled = enabled, modifier = Modifier.align(Alignment.End)) {
            Icon(Icons.Rounded.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.watch_sheet_paste))
        }
    }
}
