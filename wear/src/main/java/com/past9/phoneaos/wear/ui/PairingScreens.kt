package com.past9.phoneaos.wear.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.past9.phoneaos.wear.R
import com.past9.phoneaos.wearproto.PairOffer

/** The QR for a pairing offer, black on white (a phone camera reads it best that way), as a bitmap the screen can draw. */
object QrImage {
    fun make(text: String, px: Int = 240): ImageBitmap? = runCatching {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px, mapOf(EncodeHintType.MARGIN to 1))
        val pixels = IntArray(px * px) { i -> if (m.get(i % px, i / px)) AColor.BLACK else AColor.WHITE }
        Bitmap.createBitmap(pixels, px, px, Bitmap.Config.ARGB_8888).asImageBitmap()
    }.getOrNull()
}

/**
 * What the watch shows until a phone has paired: the QR (scan it with the Agent app on the phone), the short code under it (type it there
 * instead), and a new code when this one has expired. Nothing else on the watch works before this is done.
 */
@Composable
fun PairingScreen(offer: PairOffer?, qr: ImageBitmap?, expired: Boolean, onNewCode: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    WatchList {
        item { Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.titleSmall, color = cs.onSurface, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) }
        if (offer != null && !expired) {
            if (qr != null) item { Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { Image(qr, stringResource(R.string.pair_qr_description), Modifier.size(132.dp)) } }
            item { Text(offer.code, style = MaterialTheme.typography.titleMedium, color = cs.primary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            item { Text(stringResource(R.string.pair_how), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) }
        } else {
            item { Text(stringResource(if (offer == null) R.string.pair_making else R.string.pair_expired), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) }
        }
        item { FilledTonalButton(onClick = onNewCode, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.pair_new_code)) }) }
    }
}

/** Who this watch is paired with, and Unpair. */
@Composable
fun PairedScreen(phoneName: String, onUnpair: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    WatchList {
        item { Text(stringResource(R.string.pair_paired_with, phoneName.ifBlank { stringResource(R.string.pair_a_phone) }), style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) }
        item { FilledTonalButton(onClick = onUnpair, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.pair_unpair)) }) }
    }
}
