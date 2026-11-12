package com.past9.phoneaos.wear.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.past9.phoneaos.wear.R
import com.past9.phoneaos.wear.ui.ex.ExEdge
import com.past9.phoneaos.wear.ui.ex.ExStage
import com.past9.phoneaos.wear.ui.ex.Ic
import com.past9.phoneaos.wear.ui.ex.LiveBlob
import com.past9.phoneaos.wear.ui.ex.LiveOrb
import com.past9.phoneaos.wear.ui.ex.LiveWavy
import com.past9.phoneaos.wear.ui.ex.LocalLook
import com.past9.phoneaos.wear.ui.ex.MascotKind
import com.past9.phoneaos.wear.ui.ex.Mood
import com.past9.phoneaos.wear.ui.ex.TopLabel
import com.past9.phoneaos.wear.ui.ex.Txt
import com.past9.phoneaos.wear.ui.ex.icon
import com.past9.phoneaos.wear.ui.ex.qrTile
import com.past9.phoneaos.wear.ui.ex.shapeBlob
import com.past9.phoneaos.wear.ui.ex.rememberT

/** A QR code as rows of "1" and "0" (dark modules), for [qrTile]. */
object QrMatrix {
    fun encode(text: String): List<String> {
        val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L)
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        return (0 until m.height).map { y -> (0 until m.width).joinToString("") { x -> if (m.get(x, y)) "1" else "0" } }
    }
}

enum class PairStage { SCAN, PULLING, DONE }

/**
 * First open on the watch: a QR for the Agent phone app's camera and a short code; then the agent pulling its settings (name, mascot,
 * accent) with the steps ticking; then it shows the name and the mascot it was given. What pairs, and what the code means, is the
 * engine's; this draws it.
 */
@Composable
fun PairFlowScreen(stage: PairStage, qr: String, code: String, step: Int, name: String, kind: MascotKind, onStart: () -> Unit) {
    val look = LocalLook.current; val pal = look.pal
    val rows = remember(qr) { if (qr.isBlank()) emptyList() else QrMatrix.encode(qr) }
    ExStage(if (stage == PairStage.PULLING) pal.deep else Color.Black) { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        when (stage) {
            PairStage.SCAN -> {
                TopLabel(stringResource(R.string.pair_title).uppercase(), pal.primary, 10f, FontWeight.Bold, 2f)
                val side = g.d * 0.47f
                Canvas(Modifier.fillMaxSize()) {
                    val s = side.toPx(); val tl = Offset(cx.toPx() - s / 2, (H * 0.19f).toPx())
                    shapeBlob(MaterialShapes.Cookie12Sided, Offset(cx.toPx(), tl.y + s / 2), s * 1.22f, pal.container, 0f)
                    if (rows.isNotEmpty()) qrTile(rows, tl, s, 20.dp.toPx())
                }
                Box(Modifier.offset(cx - g.dp(80f), H * 0.19f + side + g.dp(10f)).width(g.dp(160f)), contentAlignment = Alignment.Center) { Txt(code, 24f, Color.White, FontWeight.Black, 2.5f, maxLines = 1) }
                Box(Modifier.offset(cx - g.dp(92f), H * 0.19f + side + g.dp(38f)).width(g.dp(184f)), contentAlignment = Alignment.Center) { Txt(stringResource(R.string.pair_hint), 10.5f, Color.White.copy(alpha = 0.6f), lh = 12.5f, maxLines = 2) }
            }
            PairStage.PULLING -> {
                val cy = H * 0.30f
                TopLabel(stringResource(R.string.pair_pairing).uppercase(), pal.onContainer, 10.5f, FontWeight.Bold, 2.4f)
                LiveWavy(cx, cy, g.d * 0.215f, g.d * 0.007f, 9, pal.primary, 3.dp * g.k, speed = -5f, sweep = 80f + 250f * ((step + 0.4f) / 3f).coerceIn(0f, 1f), startSpin = 100f)
                LiveOrb(cx, cy, g.d * 0.135f, Mood.Thinking)
                val steps = listOf(R.string.pair_step_paired, R.string.pair_step_look, R.string.pair_step_sync)
                val t = rememberT()
                steps.forEachIndexed { i, res ->
                    val y = H * 0.575f + g.dp(i * 21f)
                    val state = if (i < step) 2 else if (i == step) 1 else 0
                    Box(Modifier.offset(cx - g.dp(88f), y).size(g.dp(176f), g.dp(18f))) {
                        Canvas(Modifier.offset(0.dp, g.dp(1f)).size(g.dp(15f))) {
                            val c = Offset(size.width / 2, size.height / 2)
                            when (state) {
                                2 -> { drawCircle(pal.primary, size.width / 2); icon(Ic.check, c, size.width * 0.7f, pal.onPrimary) }
                                1 -> shapeBlob(MaterialShapes.Cookie6Sided, c, size.width, pal.primary.copy(alpha = 0.55f), t * 90f)
                                else -> drawCircle(pal.onContainer.copy(alpha = 0.3f), size.width / 2 - density, style = Stroke(1.4f * density))
                            }
                        }
                        Box(Modifier.offset(g.dp(23f), 0.dp)) { Txt(stringResource(res), 12f, if (state == 0) pal.onContainer.copy(alpha = 0.45f) else pal.onContainer, if (state == 1) FontWeight.Bold else FontWeight.Normal, align = androidx.compose.ui.text.style.TextAlign.Start, maxLines = 1) }
                    }
                }
            }
            PairStage.DONE -> {
                val cy = H * 0.30f
                LiveBlob(MaterialShapes.Cookie12Sided, cx, cy, g.d * 0.5f, pal.container)
                TopLabel(stringResource(R.string.pair_paired).uppercase(), pal.primary, 10.5f, FontWeight.Bold, 2.4f)
                LiveOrb(cx, cy, g.d * 0.17f, Mood.Idle, 0f, kind)
                Box(Modifier.offset(cx - g.dp(90f), H * 0.52f).width(g.dp(180f)), contentAlignment = Alignment.Center) { Txt(name.ifBlank { stringResource(R.string.agent_default) }, 30f, Color.White, FontWeight.Black, -0.5f, maxLines = 1) }
                Box(Modifier.offset(cx - g.dp(90f), H * 0.52f + g.dp(37f)).width(g.dp(180f)), contentAlignment = Alignment.Center) { Txt(stringResource(R.string.pair_ready), 12f, Color.White.copy(alpha = 0.62f)) }
                ExEdge(g, stringResource(R.string.pair_start), pal.primary, pal.onPrimary, null, 0.56f, 42f, onClick = onStart)
            }
        }
    }
}
