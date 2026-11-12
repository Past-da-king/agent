package com.past9.phoneaos.wear

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.wear.ui.AgentWatchTheme
import com.past9.phoneaos.wear.ui.PairFlowScreen
import com.past9.phoneaos.wear.ui.PairStage
import com.past9.phoneaos.wear.ui.ex.MascotKind
import com.past9.phoneaos.wearproto.PairLog
import com.past9.phoneaos.wearproto.PairProof
import com.past9.phoneaos.wearproto.PairReply
import com.past9.phoneaos.wearproto.PairWire
import com.past9.phoneaos.wearproto.PairPaths
import com.past9.phoneaos.wearproto.PairedNote
import com.past9.phoneaos.wearproto.PairedWire
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Pairing made robust: the offer on the watch survives the process (so a service the system starts cold can answer), the same code stays
 * valid if he backs out, a watch that missed the phone's reply learns it from the phone's note, the log tells the whole story without a
 * secret, and the screen says when the battery is low.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = WatchApp::class)
class PairHardeningTest {
    @get:Rule val rule = createComposeRule()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val nm: NotificationManager get() = ctx.getSystemService(NotificationManager::class.java)
    @Before fun fresh() { WatchPairingState.resetForTest(ctx); PairLog.clear() }

    private fun offer(force: Boolean = false) = runBlocking { WatchPairingState.newOffer(ctx, force) }!!
    private fun ask(from: String, proof: PairProof) = runBlocking { WatchPairingState.onRequest(ctx, from, PairWire.request(proof, "Pixel")) }
    private fun note(at: Long, watch: String = "watch-1") = PairedWire.encode(PairedNote(watch, "Galaxy A35", at))
    private fun learn(at: Long, watch: String = "watch-1") = runBlocking { WatchPairingState.onPairedNote(ctx, "phone-A", note(at, watch), me = "watch-1") }

    // ---- background answering -----------------------------------------------------------------------------------------------

    @Test fun aServiceStartedColdAnswersFromTheOfferKeptOnDisk() {
        val o = offer()
        WatchPairingState.dropMemoryForTest()      // the process died; the system starts WearListener in a new one
        assertNotNull("the offer is read back", WatchPairingState.currentOffer(ctx))
        ask("phone-A", PairProof(secret = o.secret))
        assertTrue(WatchPairingState.paired.value); assertEquals("phone-A", WatchPairingState.phone(ctx))
        assertTrue("the answer went back", WatchPairingState.sent.any { it.first == "phone-A" && it.second == PairPaths.RESULT && PairWire.parseResult(it.third) is PairReply.Accepted })
    }

    @Test fun aTypedCodeIsAnsweredTheSameWayFromTheKeptOffer() {
        val o = offer(); WatchPairingState.dropMemoryForTest()
        ask("phone-A", PairProof(code = o.code))
        assertTrue(WatchPairingState.paired.value)
    }

    @Test fun backingOutAndComingBackShowsTheSameCodeAndNewCodeMakesAnother() {
        val first = offer()
        assertEquals("opened again at once", first, offer())
        WatchPairingState.dropMemoryForTest()
        assertEquals("opened again after the process went", first, offer())
        assertNotEquals("the button asks for another", first.secret, offer(force = true).secret)
    }

    @Test fun anOfferThatRanOutIsNotKept() {
        val h = WatchPairingState.host(ctx); h.newOffer("watch-1", System.currentTimeMillis() - 11 * 60_000L)
        WatchPairingState.dropMemoryForTest()
        assertNull(WatchPairingState.currentOffer(ctx))
    }

    @Test fun anOngoingNoticeKeepsThePairingOneTapAwayAndGoesWhenPaired() {
        val o = offer()
        assertEquals(1, shadowOf(nm).allNotifications.size)
        val n = shadowOf(nm).allNotifications.single()
        assertTrue("ongoing", n.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        assertNotNull("opens the app", n.contentIntent)
        val text = n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString() + n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)
        assertFalse("no code or secret in a notification", text.contains(o.secret) || text.replace("-", "").contains(o.code.replace("-", "")))
        ask("phone-A", PairProof(secret = o.secret))
        assertTrue("gone when paired", shadowOf(nm).allNotifications.none { it.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0 })
    }

    // ---- the watch learns from the phone's note ------------------------------------------------------------------------------

    @Test fun phonePairedButTheWatchMissedTheReplyTheWatchLearnsItFromTheNote() {
        offer()                                    // the watch is on its pairing screen, never heard the answer
        assertFalse(WatchPairingState.paired.value)
        assertTrue(learn(at = 100L))
        assertTrue(WatchPairingState.paired.value); assertEquals("phone-A", WatchPairingState.phone(ctx)); assertEquals("Galaxy A35", WatchPairingState.phoneName.value)
        assertNull("the code is gone", WatchPairingState.currentOffer(ctx))
        WatchPairingState.dropMemoryForTest()
        assertEquals("and it is still paired after a restart", "phone-A", WatchPairingState.phone(ctx))
    }

    @Test fun aNoteForAnotherWatchOrAnOldNoteDoesNothing() {
        offer()
        assertFalse(learn(at = 100L, watch = "watch-9")); assertFalse(WatchPairingState.paired.value)
        // He paired, then unpaired on the watch: the phone's older note must not pair it again.
        val o = WatchPairingState.currentOffer(ctx)!!; ask("phone-A", PairProof(secret = o.secret)); runBlocking { WatchPairingState.unpair(ctx) }
        assertFalse(WatchPairingState.paired.value)
        assertFalse("note made before the unpairing", learn(at = System.currentTimeMillis() - 60_000L)); assertFalse(WatchPairingState.paired.value)
        assertTrue("a newer note (paired again on the phone)", learn(at = System.currentTimeMillis() + 60_000L)); assertTrue(WatchPairingState.paired.value)
    }

    @Test fun aWatchThatIsAlreadyPairedIgnoresNotes() {
        val o = offer(); ask("phone-A", PairProof(secret = o.secret))
        assertFalse(runBlocking { WatchPairingState.onPairedNote(ctx, "phone-B", note(1L), me = "watch-1") })
        assertEquals("phone-A", WatchPairingState.phone(ctx))
    }

    // ---- the log -------------------------------------------------------------------------------------------------------------

    @Test fun theLogTellsTheHandshakeWithoutTheCodeOrTheSecret() {
        val o = offer()
        ask("phone-A", PairProof(code = "ZZZZ-ZZZZ")); ask("phone-A", PairProof(secret = o.secret))
        val log = PairLog.recent().joinToString("\n")
        for (needle in listOf("offer created", "onRequest road=datalayer", "answer sent road=datalayer refused(wrong_code)", "paired with phone-A", "answer sent road=datalayer accepted"))
            assertTrue("missing: $needle in\n$log", log.contains(needle))
        assertFalse("the secret never reaches the log", log.contains(o.secret))
        assertFalse("nor the code", log.replace("-", "").contains(o.code.replace("-", "")))
        assertFalse("nor the typed one", log.contains("ZZZZ"))
    }

    @Test fun theLanRoadIsLoggedToo() {
        val o = offer()
        runBlocking { WatchPairingState.onLanRequest(ctx, PairWire.request(PairProof(code = o.code), "Pixel", "phone-A")) }
        val log = PairLog.recent().joinToString("\n")
        assertTrue(log, log.contains("onLanRequest")); assertTrue(log, log.contains("lan answer accepted"))
    }

    // ---- listeners and the low-battery state ----------------------------------------------------------------------------------

    @Test fun listenersAreAddedOnResumeAndRemovedOnPauseOnceEach() {
        val calls = ArrayList<String>()
        val l = PairListening({ calls += "add" }, { calls += "remove" })
        l.pause(); l.resume(); l.resume(); l.pause(); l.pause(); l.resume()
        assertEquals(listOf("add", "remove", "add"), calls); assertTrue(l.active)
    }

    @Test fun theBatteryLevelIsReadFromTheSystemBroadcast() {
        assertEquals(4, WatchHealth.percentOf(Intent().putExtra(BatteryManager.EXTRA_LEVEL, 4).putExtra(BatteryManager.EXTRA_SCALE, 100)))
        assertEquals(50, WatchHealth.percentOf(Intent().putExtra(BatteryManager.EXTRA_LEVEL, 50).putExtra(BatteryManager.EXTRA_SCALE, 100)))
        assertEquals(-1, WatchHealth.percentOf(null))
    }

    @Test fun theScreenSaysWhenTheBatteryIsLow() {
        val line = "Battery low, pairing may be slow. Keep the watch awake on this screen"
        rule.setContent { AgentWatchTheme { PairFlowScreen(PairStage.SCAN, "agentwatch://pair?n=w&s=0123456789ABCDEFGHJK", "0123-4567", 0, "", MascotKind.Scout, notice = line, onStart = {}) } }
        rule.onNodeWithText(line).assertIsDisplayed()
    }

    @Test fun withNothingToWarnAboutTheUsualHintShows() {
        rule.setContent { AgentWatchTheme { PairFlowScreen(PairStage.SCAN, "agentwatch://pair?n=w&s=0123456789ABCDEFGHJK", "0123-4567", 0, "", MascotKind.Scout, onStart = {}) } }
        rule.onNodeWithText("Phone: Agent, Watch, Scan").assertIsDisplayed()
    }
}
