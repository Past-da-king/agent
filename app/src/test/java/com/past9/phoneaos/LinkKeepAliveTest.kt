package com.past9.phoneaos

import com.past9.phoneaos.remote.CommandLedger
import com.past9.phoneaos.remote.NetReaction
import com.past9.phoneaos.remote.RemoteSession
import com.past9.phoneaos.remote.backoffMs
import com.past9.phoneaos.remote.mayPauseLink
import com.past9.phoneaos.remote.reactToNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 11 Nov 2026 (0.15.47): the phone stayed "offline" from the agent while on Wi-Fi and untouched. */
class LinkKeepAliveTest {
    // ---- the cause: the link paused 30 s after the app left the screen, whatever the computer was asking ----
    @Test fun onWifiAnIdlePhoneKeepsItsLinkSoTheComputerCanReachIt() {
        assertFalse(mayPauseLink(foreground = false, turnRunning = false, jobsRunning = 0, metered = false))
    }
    @Test fun onMobileDataAnIdleOffScreenPhoneStillSavesData() {
        assertTrue(mayPauseLink(foreground = false, turnRunning = false, jobsRunning = 0, metered = true))
    }
    @Test fun aJobOrATurnOrTheScreenKeepsTheLinkEvenOnMobileData() {
        assertFalse(mayPauseLink(false, false, 1, true))
        assertFalse(mayPauseLink(false, true, 0, true))
        assertFalse(mayPauseLink(true, false, 0, true))
    }

    // ---- reconnect within seconds: jittered, capped, reset on success ----
    @Test fun theWaitGrowsToThirtySecondsAndNeverMinutes() {
        var worst = 0L
        for (f in 1..40) for (r in listOf(0.0, 0.5, 1.0)) worst = maxOf(worst, backoffMs(f, r))
        assertTrue("cap is ${RemoteSession.MAX_BACKOFF_MS}", worst <= RemoteSession.MAX_BACKOFF_MS)
        assertTrue(RemoteSession.MAX_BACKOFF_MS <= 30_000L)
        assertTrue(backoffMs(1, 0.5) in 800L..1_200L)
        assertTrue(backoffMs(3, 0.5) in 3_200L..4_800L)
    }
    @Test fun aLinkThatCameBackWaitsNoTime() { assertEquals(0L, backoffMs(0)) }
    @Test fun phonesDoNotMarchInStep() { assertTrue(backoffMs(4, 0.0) < backoffMs(4, 1.0)) }

    // ---- the network callbacks ----
    @Test fun aNetworkThatAppearsWhileTheLinkIsDownConnectsAtOnce() {
        assertEquals(NetReaction.ConnectNow, reactToNetwork(linkOn = true, socketOpen = false, sameNetworkAsSocket = true))
    }
    @Test fun wifiLostAndRegainedReplacesTheSocketThatHasNotNoticed() {
        assertEquals(NetReaction.ReplaceSocket, reactToNetwork(linkOn = true, socketOpen = true, sameNetworkAsSocket = false))
    }
    @Test fun aSignalLevelChangeLeavesAHealthySocketAlone() {
        assertEquals(NetReaction.None, reactToNetwork(linkOn = true, socketOpen = true, sameNetworkAsSocket = true))
    }
    @Test fun aPausedLinkIsLeftPaused() {
        assertEquals(NetReaction.None, reactToNetwork(linkOn = false, socketOpen = false, sameNetworkAsSocket = true))
    }

    // ---- exactly once ----
    @Test fun aCommandSentAgainAfterADropRunsOnce() {
        val l = CommandLedger()
        assertEquals(CommandLedger.Verdict.RUN, l.begin("c1"))
        assertEquals("a repeat while it runs does nothing", CommandLedger.Verdict.RUNNING, l.begin("c1"))
        l.finish("c1", "tool", true, "done")
        assertEquals("a repeat after it ended gets the stored answer", CommandLedger.Verdict.DONE, l.begin("c1"))
        assertEquals("done", l.get("c1")!!.value)
    }
    @Test fun anAnswerTheDropKeptBackWaitsForTheNextLink() {
        val l = CommandLedger()
        l.begin("c2"); l.finish("c2", "tool", true, "x")
        assertEquals(listOf("c2"), l.undelivered().map { it.first })
        l.delivered("c2")
        assertTrue(l.undelivered().isEmpty())
    }
    @Test fun aCommandStillRunningHasNothingToResend() {
        val l = CommandLedger()
        l.begin("c3")
        assertTrue(l.undelivered().isEmpty())
        assertNotNull(l.get("c3"))
    }
    @Test fun theLedgerForgetsOldDeliveredCommandsButNeverOneStillOwed() {
        var t = 0L
        val l = CommandLedger(max = 2, ttlMs = 1_000, now = { t })
        for (id in listOf("a", "b", "c")) { l.begin(id); l.finish(id, "tool", true, id); if (id != "a") l.delivered(id) }
        t = 5_000
        l.begin("d")
        assertEquals("an undelivered answer is kept", CommandLedger.Verdict.DONE, l.begin("a"))
    }
}
