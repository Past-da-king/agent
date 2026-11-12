package com.past9.phoneaos.wear

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/**
 * The complication: how many things need him (amber on most faces, a number), or "Ask" with a mic when nothing does.
 * A tap opens the talk screen, or the card when one waits.
 */
class AgentComplication : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? = when (type) {
        ComplicationType.SHORT_TEXT -> short("2", getString(R.string.cd_waiting), "approvals")
        ComplicationType.MONOCHROMATIC_IMAGE -> image("talk")
        else -> null
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val waiting = WatchStore.cached(this)?.pending?.size ?: 0
        return when (request.complicationType) {
            ComplicationType.SHORT_TEXT ->
                if (waiting > 0) short(waiting.toString(), getString(R.string.cd_waiting), "approvals")
                else short(getString(R.string.complication_ask), getString(R.string.cd_talk), "talk")
            ComplicationType.MONOCHROMATIC_IMAGE -> image(if (waiting > 0) "approvals" else "talk")
            else -> null
        }
    }

    private fun tap(go: String): PendingIntent = PendingIntent.getActivity(
        this, go.hashCode(), Intent(this, MainActivity::class.java).putExtra("go", go).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun icon() = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_mic)).build()

    private fun short(text: String, description: String, go: String) =
        ShortTextComplicationData.Builder(PlainComplicationText.Builder(text).build(), PlainComplicationText.Builder(description).build())
            .setMonochromaticImage(icon()).setTapAction(tap(go)).build()

    private fun image(go: String) =
        MonochromaticImageComplicationData.Builder(icon(), PlainComplicationText.Builder(getString(R.string.cd_talk)).build()).setTapAction(tap(go)).build()
}
