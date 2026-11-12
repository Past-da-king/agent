package com.past9.phoneaos.wear.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.past9.phoneaos.wear.R
import com.past9.phoneaos.wear.ui.ex.ExPill
import com.past9.phoneaos.wear.ui.ex.ExStage
import com.past9.phoneaos.wear.ui.ex.Ic
import com.past9.phoneaos.wear.ui.ex.LiveBlob
import com.past9.phoneaos.wear.ui.ex.LiveOrb
import com.past9.phoneaos.wear.ui.ex.LocalLook
import com.past9.phoneaos.wear.ui.ex.Mood
import com.past9.phoneaos.wear.ui.ex.TopLabel
import com.past9.phoneaos.wear.ui.ex.Txt
import com.past9.phoneaos.wearproto.PairOffer

/** The QR for a pairing offer, black on white (a phone camera reads it best that way), as a bitmap. */
object QrImage {
    fun make(text: String, px: Int = 240): ImageBitmap? = runCatching {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px, mapOf(EncodeHintType.MARGIN to 1))
        val pixels = IntArray(px * px) { i -> if (m.get(i % px, i / px)) AColor.BLACK else AColor.WHITE }
        Bitmap.createBitmap(pixels, px, px, Bitmap.Config.ARGB_8888).asImageBitmap()
    }.getOrNull()
}

/**
 * What the watch shows until a phone has paired: the QR (scan it with the Agent app on the phone), the short code under it (type it there
 * instead), and a new code when this one has expired. Nothing else on the watch works before this is done. [qr] is kept for callers that
 * draw their own; the screen draws the code from the offer itself, as the rounded tile of the expressive look.
 */
@Composable
fun PairingScreen(offer: PairOffer?, qr: ImageBitmap?, expired: Boolean, lanHost: String = "", onNewCode: () -> Unit) {
    if (offer != null && !expired) {
        PairFlowScreen(PairStage.SCAN, offer.qr(lanHost), offer.code, 0, "", com.past9.phoneaos.wear.ui.ex.MascotKind.Scout) {}
    } else {
        val pal = LocalLook.current.pal
        ExStage { g ->
            val cx = g.w / 2
            TopLabel(stringResource(R.string.pair_title).uppercase(), pal.primary, 10f, FontWeight.Bold, 2f)
            LiveBlob(MaterialShapes.Cookie12Sided, cx, g.h * 0.30f, g.d * 0.44f, pal.container)
            LiveOrb(cx, g.h * 0.30f, g.d * 0.13f, Mood.Asleep)
            Box(Modifier.offset(cx - g.dp(86f), g.h * 0.55f).width(g.dp(172f)), contentAlignment = Alignment.Center) { Txt(stringResource(if (offer == null) R.string.pair_making else R.string.pair_expired), 14f, Color.White, FontWeight.Bold, lh = 17f, maxLines = 2) }
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = g.dp(14f)).padding(horizontal = g.w * 0.17f)) { ExPill(stringResource(R.string.pair_new_code), onNewCode, container = pal.primary, content = pal.onPrimary, height = 38f) }
        }
    }
}

/** Who this watch is paired with, and Unpair. */
@Composable
fun PairedScreen(phoneName: String, onUnpair: () -> Unit) {
    val pal = LocalLook.current.pal
    ExStage { g ->
        val cx = g.w / 2
        TopLabel(stringResource(R.string.pair_paired).uppercase(), pal.primary, 10.5f, FontWeight.Bold, 2.4f)
        LiveBlob(MaterialShapes.Cookie12Sided, cx, g.h * 0.30f, g.d * 0.44f, pal.container)
        LiveOrb(cx, g.h * 0.30f, g.d * 0.13f, Mood.Idle)
        Box(Modifier.offset(cx - g.dp(86f), g.h * 0.55f).width(g.dp(172f)), contentAlignment = Alignment.Center) { Txt(stringResource(R.string.pair_paired_with, phoneName.ifBlank { stringResource(R.string.pair_a_phone) }), 14f, Color.White, FontWeight.Bold, lh = 17f, maxLines = 2) }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = g.dp(14f)).padding(horizontal = g.w * 0.17f)) { ExPill(stringResource(R.string.pair_unpair), onUnpair, height = 38f) }
    }
}
