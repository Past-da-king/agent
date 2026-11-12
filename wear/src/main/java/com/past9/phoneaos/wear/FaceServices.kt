package com.past9.phoneaos.wear

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.past9.phoneaos.wearproto.AgentStatus
import com.past9.phoneaos.wearproto.FaceData

/**
 * Data sources a watch face (any face, any maker's) can show, besides the combined one in [AgentComplication]: how many approvals wait,
 * the next task, what the agent is doing, and a tap-to-talk. The values come from [FaceData] (tested on the JVM); this only puts them
 * in the watch's own language and gives each a tap. They are pushed when the state changes (see [WatchStore]), never polled.
 * The watch face's own look is not decided here.
 */
abstract class FaceSource : SuspendingComplicationDataSourceService() {
    protected fun tap(go: String): PendingIntent = PendingIntent.getActivity(
        this, go.hashCode(), Intent(this, MainActivity::class.java).putExtra("go", go).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    protected fun icon(res: Int) = MonochromaticImage.Builder(Icon.createWithResource(this, res)).build()
    protected fun text(s: String) = PlainComplicationText.Builder(s).build()
    protected fun state() = WatchStore.cached(this)

    protected fun short(value: String, description: String, go: String, res: Int = R.drawable.ic_agent): ComplicationData =
        ShortTextComplicationData.Builder(text(value), text(description)).setMonochromaticImage(icon(res)).setTapAction(tap(go)).build()

    protected fun long(value: String, title: String?, description: String, go: String, res: Int = R.drawable.ic_agent): ComplicationData =
        LongTextComplicationData.Builder(text(value), text(description)).apply { if (title != null) setTitle(text(title)) }.setMonochromaticImage(icon(res)).setTapAction(tap(go)).build()

    protected fun image(description: String, go: String, res: Int): ComplicationData =
        MonochromaticImageComplicationData.Builder(icon(res), text(description)).setTapAction(tap(go)).build()
}

/** How many approvals and questions wait for him. Tap: the first card, or the talk screen when none waits. */
class PendingComplication : FaceSource() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? = render(type, 2)
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? = render(request.complicationType, FaceData.waiting(state()), FaceData.approvalsGo(state()))

    private fun render(type: ComplicationType, n: Int, go: String = "approvals"): ComplicationData? = when (type) {
        ComplicationType.SHORT_TEXT -> short(FaceData.count(n), getString(R.string.cd_waiting), go)
        ComplicationType.LONG_TEXT -> long(if (n == 0) getString(R.string.face_nothing_waits) else resources.getQuantityString(R.plurals.face_waiting, n, n), getString(R.string.face_title_approvals), getString(R.string.cd_waiting), go)
        ComplicationType.MONOCHROMATIC_IMAGE -> image(getString(R.string.cd_waiting), go, R.drawable.ic_agent)
        else -> null
    }
}

/** The next task on his list. Tap: asks the agent about it (the talk screen). */
class NextTaskComplication : FaceSource() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? = when (type) {
        ComplicationType.SHORT_TEXT -> short("Send", getString(R.string.face_title_task), "talk")
        ComplicationType.LONG_TEXT -> long("Send the invoice", getString(R.string.face_title_task), getString(R.string.face_title_task), "talk")
        else -> null
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val f = FaceData.nextTask(state())
        return when (request.complicationType) {
            ComplicationType.SHORT_TEXT -> short(if (f.hasTask) f.short else getString(R.string.face_none), getString(R.string.face_title_task), "talk")
            ComplicationType.LONG_TEXT -> long(if (f.hasTask) f.long else getString(R.string.face_no_task), getString(R.string.face_title_task), getString(R.string.face_title_task), "talk")
            else -> null
        }
    }
}

/** What the agent is doing: idle, working, needs him, in a call, or its computer is offline. */
class StatusComplication : FaceSource() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? = render(type, AgentStatus.WORKING)
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? = render(request.complicationType, FaceData.status(state()))

    private fun render(type: ComplicationType, s: AgentStatus): ComplicationData? {
        val (sh, lg, go) = when (s) {
            AgentStatus.IDLE -> Triple(R.string.face_idle_short, R.string.face_idle, "talk")
            AgentStatus.WORKING -> Triple(R.string.face_working_short, R.string.face_working, "talk")
            AgentStatus.NEEDS_YOU -> Triple(R.string.face_needs_short, R.string.face_needs, "approvals")
            AgentStatus.IN_CALL -> Triple(R.string.face_call_short, R.string.face_call, "call")
            AgentStatus.OFFLINE -> Triple(R.string.face_offline_short, R.string.face_offline, "talk")
        }
        return when (type) {
            ComplicationType.SHORT_TEXT -> short(getString(sh), getString(R.string.face_title_status), go)
            ComplicationType.LONG_TEXT -> long(getString(lg), getString(R.string.face_title_status), getString(R.string.face_title_status), go)
            else -> null
        }
    }
}

/** Tap to talk: opens the talk screen already listening (or on the phone's mic when this watch has none). */
class TalkComplication : FaceSource() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? = render(type)
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? = render(request.complicationType)

    private fun render(type: ComplicationType): ComplicationData? = when (type) {
        ComplicationType.SHORT_TEXT -> short(getString(R.string.complication_ask), getString(R.string.cd_talk), "talk", R.drawable.ic_mic)
        ComplicationType.MONOCHROMATIC_IMAGE -> image(getString(R.string.cd_talk), "talk", R.drawable.ic_mic)
        else -> null
    }
}

object FaceSurfaces {
    private val all = listOf(AgentComplication::class.java, PendingComplication::class.java, NextTaskComplication::class.java, StatusComplication::class.java, TalkComplication::class.java)

    /** Ask the system to redraw every face source of this app: the state changed, a face on the wrist may show it. */
    fun refresh(context: Context) {
        all.forEach { runCatching { ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, it)).requestUpdateAll() } }
    }
}
