package com.past9.phoneaos.wear

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import androidx.test.core.app.ApplicationProvider
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.past9.phoneaos.wearproto.AgentStatus
import com.past9.phoneaos.wearproto.ApprovalRingMode
import com.past9.phoneaos.wearproto.Pending
import com.past9.phoneaos.wearproto.RingItem
import com.past9.phoneaos.wearproto.RingKind
import com.past9.phoneaos.wearproto.TaskView
import com.past9.phoneaos.wearproto.WatchState
import com.past9.phoneaos.wearproto.gesture.Gesture
import com.past9.phoneaos.wearproto.gesture.GestureAction
import com.past9.phoneaos.wearproto.gesture.GestureEffect
import com.past9.phoneaos.wearproto.gesture.GestureMap
import com.past9.phoneaos.wearproto.gesture.SensorPlan
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import java.time.Instant
import kotlin.math.PI
import kotlin.math.sin

class AudioProbeTest {
    @Test fun aWatchWithItsOwnSpeakerCarriesTheSoundItself() {
        val c = AudioProbe.caps(micUsable = true, out = AudioProbe.outputs(true, listOf(AudioProbe.TYPE_BUILTIN_SPEAKER)))
        assertTrue(c.mic); assertTrue(c.speaker); assertFalse(c.earbuds)
    }

    @Test fun aWatchWithNoSpeakerButEarbudsConnectedPlaysThroughTheEarbuds() {
        val c = AudioProbe.caps(true, AudioProbe.outputs(false, listOf(AudioProbe.TYPE_BLUETOOTH_A2DP)))
        assertTrue(c.speaker); assertTrue(c.earbuds)
        val ble = AudioProbe.caps(true, AudioProbe.outputs(true, listOf(AudioProbe.TYPE_BLE_HEADSET)))
        assertTrue(ble.speaker)
    }

    @Test fun aWatchWithNeitherHasNoSpeakerAndTheCallUsesThePhone() {
        val c = AudioProbe.caps(true, AudioProbe.outputs(false, emptyList()))
        assertFalse(c.speaker)
        // A speaker the watch lists without claiming audio output does not count.
        assertFalse(AudioProbe.outputs(false, listOf(AudioProbe.TYPE_BUILTIN_SPEAKER)).builtIn)
    }

    @Test fun aMicThatReportsItselfButRecordsSilenceIsJudgedDeadMidCallAndAMutedStretchSaysNothing() {
        val h = CallMicHealth(windowsToDecide = 5)
        repeat(4) { assertFalse(h.feed(0, muted = false)) }
        assertTrue(h.feed(2, muted = false)); assertTrue(h.dead)
        val ok = CallMicHealth(windowsToDecide = 5)
        repeat(4) { ok.feed(1, false) }
        ok.feed(900, false)
        repeat(4) { ok.feed(1, false) }
        assertFalse("a real voice in between resets the count", ok.dead)
        val muted = CallMicHealth(windowsToDecide = 5)
        repeat(50) { assertFalse(muted.feed(0, muted = true)) }
        assertFalse(muted.dead)
    }
}

/** The ring on the wrist: what the phone says rings is notified (full screen, repeating vibration) and what leaves its list is cancelled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = WatchApp::class)
class WatchRingTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val nm: NotificationManager get() = ctx.getSystemService(NotificationManager::class.java)
    private fun wake(id: String, sev: String = "urgent", sound: Boolean = true) = RingItem(id, RingKind.WAKE, sev, "Standup", "Join the call", sound)
    private fun card(id: Long) = Pending(id, Pending.KIND_APPROVAL, "Send the invoice", "To Shey", Pending.APPROVAL_OPTIONS, "Hina")
    private fun posted(id: String): Notification? = shadowOf(nm).getNotification(id.hashCode())

    @Before fun clean() { WatchRing.onRing(ctx, emptyList()); WatchRing.onState(ctx, WatchState()); shadowOf(nm).allNotifications.size }
    @After fun after() { WatchRing.onRing(ctx, emptyList()); WatchRing.onState(ctx, WatchState()) }

    @Test fun aWakeThePhoneSaysRingsStartsAFullScreenNotificationThatRepeatsItsVibration() {
        WatchRing.onRing(ctx, listOf(wake("w1", "critical")))
        val n = posted("w1")!!
        assertNotNull("full-screen intent", n.fullScreenIntent)
        assertTrue("the system repeats the vibration until it is cancelled", n.flags and Notification.FLAG_INSISTENT != 0)
        assertEquals(Notification.CATEGORY_ALARM, n.category)
        assertEquals(2, n.actions.size)
        assertEquals(listOf("w1"), WatchRing.ringing.value.map { it.id })
    }

    @Test fun whenThePhoneDropsItBecauseItWasAnsweredElsewhereItStopsHereAtOnce() {
        WatchRing.onRing(ctx, listOf(wake("w1")))
        assertNotNull(posted("w1"))
        WatchRing.onRing(ctx, emptyList())
        assertNull(posted("w1")); assertTrue(WatchRing.ringing.value.isEmpty())
    }

    @Test fun anAnswerHereStopsItNowAndALateListFromThePhoneDoesNotStartItAgain() {
        WatchRing.onRing(ctx, listOf(wake("w1")))
        WatchRing.answer(ctx, "w1", "ack")
        assertNull(posted("w1")); assertTrue(WatchRing.ringing.value.isEmpty())
        WatchRing.onRing(ctx, listOf(wake("w1")))
        assertNull("the phone has not caught up yet: it must not ring again", posted("w1"))
        WatchRing.onRing(ctx, emptyList())
        WatchRing.onRing(ctx, listOf(wake("w1")))
        assertNotNull("a snooze that comes back later rings again", posted("w1"))
    }

    @Test fun aSilentHeadsUpInQuietHoursShowsButDoesNotRepeatOrBuzz() {
        WatchRing.onRing(ctx, listOf(wake("w2", "heads_up", sound = false)))
        val n = posted("w2")!!
        assertEquals(0, n.flags and Notification.FLAG_INSISTENT)
        assertEquals("ring2_silent", n.channelId)
    }

    @Test fun theDefaultForApprovalsIsJustBuzzAndNotAFullScreenRing() {
        assertEquals(ApprovalRingMode.BUZZ, WatchRing.approvalMode(ctx))
        WatchRing.onState(ctx, WatchState(pending = listOf(card(21))))
        assertNull("one buzz and a card, no full screen", posted("card:21")!!.fullScreenIntent)
        WatchRing.onState(ctx, WatchState())
    }

    @Test fun anApprovalRingsOnceInTheModeHeChoseAndStopsWhenItIsNoLongerWaiting() {
        WatchRing.setApprovalMode(ctx, ApprovalRingMode.FULL)
        WatchRing.onState(ctx, WatchState(pending = listOf(card(7))))
        val n = posted("card:7")!!
        assertNotNull(n.fullScreenIntent); assertEquals(0, n.flags and Notification.FLAG_INSISTENT)
        // Answered on the phone: it leaves the list and the notification goes.
        WatchRing.onState(ctx, WatchState(pending = emptyList()))
        assertNull(posted("card:7"))
    }

    @Test fun anApprovalInBuzzModeHasNoFullScreenAndInOffModeNothingAtAll() {
        WatchRing.setApprovalMode(ctx, ApprovalRingMode.BUZZ)
        WatchRing.onState(ctx, WatchState(pending = listOf(card(8))))
        assertNull(posted("card:8")!!.fullScreenIntent)
        WatchRing.onState(ctx, WatchState())
        WatchRing.setApprovalMode(ctx, ApprovalRingMode.OFF)
        WatchRing.onState(ctx, WatchState(pending = listOf(card(9))))
        assertNull(posted("card:9"))
        WatchRing.setApprovalMode(ctx, ApprovalRingMode.FULL)
    }

    @Test fun anApprovalHeIsAlreadyLookingAtDoesNotRing() {
        WatchRing.viewingCard = 11
        WatchRing.onState(ctx, WatchState(pending = listOf(card(11))))
        assertNull(posted("card:11"))
        WatchRing.viewingCard = null
    }

    @Test fun theDismissGestureSnoozesAWakeAndReportsWhetherAnythingRang() {
        assertFalse(WatchRing.dismissTop(ctx))
        WatchRing.onRing(ctx, listOf(wake("w3")))
        assertTrue(WatchRing.dismissTop(ctx))
        assertNull(posted("w3"))
    }
}

/** The gesture wiring: what is mapped is saved, sensors are registered only for it, and a detected gesture reaches the screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = WatchApp::class)
class WatchGesturesTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun gyroEvent(sensor: Sensor, tMs: Long, x: Float): SensorEvent {
        val e = org.robolectric.shadows.ShadowSensorManager.createSensorEvent(3)
        e.sensor = sensor; e.values[0] = x; e.values[1] = 0f; e.values[2] = 0f; e.timestamp = tMs * 1_000_000L
        return e
    }

    @After fun after() { WatchGestures.uiHandler = null; WatchGestures.save(ctx, GestureMap()) }

    @Test fun theMapIsSavedAndEveryGestureStartsAsNothing() {
        assertEquals(GestureAction.NONE, WatchGestures.map(ctx).actionFor(Gesture.FLICK))
        WatchGestures.save(ctx, GestureMap().with(Gesture.SHAKE, GestureAction.CALL))
        assertEquals(GestureAction.CALL, WatchGestures.map(ctx).actionFor(Gesture.SHAKE))
    }

    @Test fun theProfileReportsWhatTheWatchHas() {
        val sm = ctx.getSystemService(android.hardware.SensorManager::class.java)
        shadowOf(sm).addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
        val p = WatchGestures.profile(ctx)
        assertTrue(p.hasAccel); assertFalse(p.hasGyro); assertTrue(p.api > 0)
    }

    @Test fun aDetectedFlickReachesTheScreenAsWhatHeMappedItTo() {
        val sm = ctx.getSystemService(android.hardware.SensorManager::class.java)
        shadowOf(sm).addSensor(ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE))
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)!!
        WatchGestures.save(ctx, GestureMap().with(Gesture.FLICK, GestureAction.TALK))
        val got = ArrayList<GestureEffect>()
        WatchGestures.uiHandler = { got += it; true }
        val host = GestureHost(ctx)
        host.start(WatchGestures.map(ctx), SensorPlan.Mode.FOREGROUND)
        assertTrue("registered for the gyroscope it needs", shadowOf(sm).hasListener(host))
        for (t in 0L..500L step 20L) host.onSensorChanged(gyroEvent(gyro, t, if (t < 360) (6f * sin(2 * PI * t / 360)).toFloat() else 0f))
        assertEquals(listOf<GestureEffect>(GestureEffect.StartTalk), got)
        host.stop()
        assertFalse(shadowOf(sm).hasListener(host))
    }

    @Test fun withNothingMappedNoSensorIsRegisteredAtAll() {
        val sm = ctx.getSystemService(android.hardware.SensorManager::class.java)
        shadowOf(sm).addSensor(ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE)); shadowOf(sm).addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
        val host = GestureHost(ctx)
        host.start(GestureMap(), SensorPlan.Mode.FOREGROUND)
        assertFalse(shadowOf(sm).hasListener(host))
    }

    @Test fun aGestureThatMeansNothingHereIsDroppedBeforeTheScreen() {
        WatchGestures.save(ctx, GestureMap().with(Gesture.SHAKE, GestureAction.DISMISS))
        val got = ArrayList<GestureEffect>()
        WatchGestures.uiHandler = { got += it; true }
        // Nothing rings and no approval is on screen: dismiss has nothing to do.
        WatchGestures.onGesture(ctx, Gesture.SHAKE)
        assertTrue(got.isEmpty())
    }
}

/** The face data sources and the optional home screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = WatchApp::class)
class FaceAndHomeTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun text(d: Any?): String = when (d) {
        is ShortTextComplicationData -> d.text.getTextAt(ctx.resources, Instant.EPOCH).toString()
        is LongTextComplicationData -> d.text.getTextAt(ctx.resources, Instant.EPOCH).toString()
        else -> ""
    }
    private fun req(t: ComplicationType) = ComplicationRequest(1, t, false)
    private fun put(s: WatchState) = WatchStore.update(ctx, s)
    private fun pend(n: Int) = List(n) { Pending(it.toLong() + 1, Pending.KIND_APPROVAL, "x", "", Pending.APPROVAL_OPTIONS, "") }

    @Test fun theApprovalsFaceShowsHowManyWaitAndTapsToTheCard() = runBlocking {
        put(WatchState(pending = pend(3)))
        val s = Robolectric.setupService(PendingComplication::class.java)
        val d = s.onComplicationRequest(req(ComplicationType.SHORT_TEXT))
        assertEquals("3", text(d))
        put(WatchState())
        assertEquals("0", text(s.onComplicationRequest(req(ComplicationType.SHORT_TEXT))))
        assertEquals("All clear", text(s.onComplicationRequest(req(ComplicationType.LONG_TEXT))))
    }

    @Test fun theNextTaskFaceShowsTheTaskInASmallAndAWideSlot() = runBlocking {
        put(WatchState(nextTask = TaskView(1, "Send the invoice to Shey")))
        val s = Robolectric.setupService(NextTaskComplication::class.java)
        assertEquals("Send", text(s.onComplicationRequest(req(ComplicationType.SHORT_TEXT))))
        assertEquals("Send the invoice to Shey", text(s.onComplicationRequest(req(ComplicationType.LONG_TEXT))))
        put(WatchState())
        assertEquals("No task", text(s.onComplicationRequest(req(ComplicationType.LONG_TEXT))))
    }

    @Test fun theStatusFaceFollowsWhatTheAgentIsDoing() = runBlocking {
        val s = Robolectric.setupService(StatusComplication::class.java)
        put(WatchState(working = true)); assertEquals("Busy", text(s.onComplicationRequest(req(ComplicationType.SHORT_TEXT))))
        put(WatchState(pending = pend(1))); assertEquals("Needs", text(s.onComplicationRequest(req(ComplicationType.SHORT_TEXT))))
        put(WatchState(callLive = true)); assertEquals("In a call", text(s.onComplicationRequest(req(ComplicationType.LONG_TEXT))))
        put(WatchState(link = "offline")); assertEquals("Offline", text(s.onComplicationRequest(req(ComplicationType.SHORT_TEXT))))
        assertEquals(AgentStatus.OFFLINE, com.past9.phoneaos.wearproto.FaceData.status(WatchStore.cached(ctx)))
    }

    @Test fun theTalkFaceIsATapToTalk() = runBlocking {
        val s = Robolectric.setupService(TalkComplication::class.java)
        val d = s.onComplicationRequest(req(ComplicationType.SHORT_TEXT)) as ShortTextComplicationData
        assertEquals("Ask", text(d)); assertNotNull(d.tapAction)
        assertNull("a type it does not offer is not answered", s.onComplicationRequest(req(ComplicationType.LONG_TEXT)))
    }

    @Test fun installingTheAppChangesNothingAboutHowTheWatchStartsUntilHeAsks() {
        assertFalse("the home component is off", HomeLauncher.componentOn(ctx))
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        assertTrue(ctx.packageManager.queryIntentActivities(home, 0).none { it.activityInfo.packageName == ctx.packageName })
        assertEquals(HomeGuideState.OFF, HomeLauncher.state(ctx))
    }

    @Test fun turningItOnAnsweringHomeAndOffAgainHandsHomeBack() {
        HomeLauncher.setComponent(ctx, true)
        assertTrue(HomeLauncher.componentOn(ctx))
        assertTrue(ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).any { it.activityInfo.packageName == ctx.packageName })
        HomeLauncher.setComponent(ctx, false)
        assertFalse(HomeLauncher.componentOn(ctx))
        assertTrue(ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).none { it.activityInfo.packageName == ctx.packageName })
    }

    @Test fun theAppListIsWhatCanBeOpenedAndTheAgentFirst() {
        val pm = shadowOf(ctx.packageManager)
        fun install(pkg: String, label: String) {
            val info = android.content.pm.PackageInfo().apply { packageName = pkg; applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = pkg; name = label; nonLocalizedLabel = label } }
            pm.installPackage(info)
            val ri = android.content.pm.ResolveInfo().apply { activityInfo = android.content.pm.ActivityInfo().apply { packageName = pkg; name = "$pkg.Main"; applicationInfo = info.applicationInfo; nonLocalizedLabel = label }; nonLocalizedLabel = label }
            pm.addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), ri)
        }
        install("z.last", "Zulu"); install("a.first", "alpha")
        val apps = HomeLauncher.apps(ctx)
        val names = apps.map { it.pkg }
        assertTrue(names.indexOf("a.first") < names.indexOf("z.last"))
        assertTrue(HomeLauncher.launch(ctx, apps.first { it.pkg == "a.first" }).let { true })
    }
}

private typealias HomeGuideState = com.past9.phoneaos.wearproto.HomeGuide.State
