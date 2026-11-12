package com.past9.phoneaos.wear

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.past9.phoneaos.wear.ui.ex.MascotKind
import com.past9.phoneaos.wear.ui.ex.Pal
import com.past9.phoneaos.wear.ui.ex.V2
import com.past9.phoneaos.wear.ui.ex.mascotPath

/** The agent's mascot as a picture: the phone's own shapes, flat fill, two pill eyes (docs/wear.md, "Look"). */
object MascotBitmap {
    /** [outline] draws the line version for ambient mode (few lit pixels, no fill). */
    fun make(kind: MascotKind, fill: Int, ink: Int, px: Int, outline: Boolean = false, line: Int = 0xFFB9B5C6.toInt()): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val size = px * 0.92f
        val path = mascotPath(kind, 0f, px / 2f, px / 2f, size).asAndroidPath()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (outline) { p.style = Paint.Style.STROKE; p.strokeWidth = px * 0.012f; p.color = line } else { p.style = Paint.Style.FILL; p.color = fill }
        c.drawPath(path, p)
        // the phone's eyes: two pills 0.09 x 0.15 of the body, 0.14 apart
        val ew = size * 0.09f; val eh = size * 0.15f; val gap = size * 0.14f
        p.style = Paint.Style.FILL; p.color = if (outline) (line and 0x00FFFFFF) or (0xD9 shl 24) else ink
        for (s in listOf(-1f, 1f)) {
            val ex = px / 2f + s * (gap + ew) / 2f
            c.drawRoundRect(RectF(ex - ew / 2f, px / 2f - eh / 2f, ex + ew / 2f, px / 2f + eh / 2f), ew / 2f, ew / 2f, p)
        }
        return bmp
    }
}

/**
 * The agent itself for a watch face: a picture complication, so the face shows the mascot and the accent the user picked on the phone (not
 * a drawing baked into the face), amber when something needs them, a line drawing in ambient mode. Tap: talk. A face with an image slot
 * takes it; the three Agent faces use it by default.
 */
class MascotComplication : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? = render(type, false)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        render(request.complicationType, WatchStore.cached(this)?.pending?.isNotEmpty() == true)

    private fun render(type: ComplicationType, needs: Boolean): ComplicationData? {
        if (type != ComplicationType.SMALL_IMAGE && type != ComplicationType.PHOTO_IMAGE) return null
        WatchCustomisationState.load(this)
        val cust = WatchCustomisationState.state.value
        val kind = MascotKind.of(cust.mascot)
        val pal = Pal.ofAccent(cust.accent)
        val fill = if (needs) V2.amber.toArgb() else pal.primary.toArgb()
        val ink = if (needs) V2.inkAmber.toArgb() else pal.onPrimary.toArgb()
        val icon = Icon.createWithBitmap(MascotBitmap.make(kind, fill, ink, 360))
        val ambient = Icon.createWithBitmap(MascotBitmap.make(kind, fill, ink, 360, outline = true))
        val label = PlainComplicationText.Builder(getString(R.string.cd_talk)).build()
        val tap = PendingIntent.getActivity(this, "mascot".hashCode(), Intent(this, MainActivity::class.java).putExtra("go", "talk").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return when (type) {
            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(SmallImage.Builder(icon, SmallImageType.PHOTO).setAmbientImage(ambient).build(), label).setTapAction(tap).build()
            else -> PhotoImageComplicationData.Builder(icon, label).setTapAction(tap).build()
        }
    }
}
