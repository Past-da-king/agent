package com.past9.phoneaos

import com.past9.phoneaos.remote.BrainChooser
import com.past9.phoneaos.remote.BrainRef
import com.past9.phoneaos.remote.RpcException
import com.past9.phoneaos.remote.SendFlow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SendFlowTest {
    /** A pretend link: the brains in order, the one in use, what each does with a message. */
    private inner class Rig(vararg brains: Pair<String, suspend (Boolean) -> JSONObject?>) {
        val order = brains.map { it.first }.toMutableList()
        val behave = brains.toMap()
        val asked = mutableListOf<String>()
        var left = 0
        var legacy = 0
        var open = true
        fun flow(ack: Long = 50, giveUp: Long = 2_000) = SendFlow(
            linkUp = { order.isNotEmpty() && open },
            prepare = {},
            attempt = { fresh -> val b = order.first(); asked += b + (if (fresh) "+fresh" else ""); behave.getValue(b)(fresh) },
            socketOpen = { open },
            leave = { left++; if (order.isNotEmpty()) order.removeAt(0) },
            legacy = { legacy++ },
            ackWaitMs = ack,
            prepareWaitMs = 50,
            linkWaitMs = 10,
            giveUpMs = giveUp,
            pauseMs = 5,
        )
    }

    private val ok: suspend (Boolean) -> JSONObject? = { JSONObject().put("accepted", true) }
    private val frozen: suspend (Boolean) -> JSONObject? = { awaitCancellation() }

    @Test fun aFrozenBrainIsLeftAfterTheWaitAndTheNextOneTakesIt() = runBlocking {
        val rig = Rig("pc" to frozen, "server" to ok)
        rig.flow().run()
        assertEquals(listOf("pc", "server"), rig.asked)
        assertEquals(1, rig.left)
    }

    @Test fun aBrainThatAnswersIsNeverLeft() = runBlocking {
        val rig = Rig("pc" to ok, "server" to ok)
        rig.flow().run()
        assertEquals(listOf("pc"), rig.asked)
        assertEquals(0, rig.left)
    }

    @Test fun aLinkThatDropsMidCallTriesAgainWithTheSameMessage() = runBlocking {
        var first = true
        lateinit var rig: Rig
        rig = Rig("pc" to { if (first) { first = false; rig.open = false; rig.order.removeAt(0); rig.open = true; throw RpcException("sent ping but didn't receive pong") } else ok(it) }, "server" to ok)
        rig.open = true
        // The socket is seen closed the moment the call fails.
        val flow = SendFlow(
            linkUp = { rig.order.isNotEmpty() }, prepare = {},
            attempt = { fresh -> val b = rig.order.first(); rig.asked += b; rig.behave.getValue(b)(fresh) },
            socketOpen = { false }, leave = {}, legacy = {}, ackWaitMs = 50, prepareWaitMs = 50, linkWaitMs = 10, giveUpMs = 2_000, pauseMs = 5,
        )
        flow.run()
        assertEquals(listOf("pc", "server"), rig.asked)
    }

    @Test fun aComputerTooOldForItGetsTheOldCallOnce() = runBlocking {
        val rig = Rig("pc" to { throw RpcException("That is not available here.") }, "server" to ok)
        rig.flow().run()
        assertEquals(1, rig.legacy)
        assertEquals(listOf("pc"), rig.asked)
    }

    @Test fun aRefusalFromAComputerThatAnswersIsNotRetriedElsewhere() = runBlocking {
        val rig = Rig("pc" to { throw RpcException("A bad send id.") }, "server" to ok)
        assertThrows(RpcException::class.java) { runBlocking { rig.flow().run() } }
        assertEquals(listOf("pc"), rig.asked)
    }

    @Test fun staleIsRepeatedAsFreshOnTheSameBrain() = runBlocking {
        val rig = Rig("pc" to { fresh -> if (fresh) JSONObject().put("accepted", true) else JSONObject().put("accepted", false).put("stale", true) })
        rig.flow().run()
        assertEquals(listOf("pc", "pc+fresh"), rig.asked)
    }

    @Test fun withEveryBrainGoneItSaysSoInTheEndAndKeepsTrying() = runBlocking {
        val rig = Rig("pc" to frozen, "server" to frozen)
        val e = assertThrows(RpcException::class.java) { runBlocking { rig.flow(giveUp = 200).run() } }
        assertEquals(SendFlow.NO_BRAIN, e.message)
        assertEquals(listOf("pc", "server"), rig.asked)
    }

    private val desk = BrainRef("desk", "Desk", listOf("https://desk-lan:7787", "https://desk-vpn:7787"), "aa", 0)
    private val server = BrainRef("srv", "Server", listOf("https://srv:7787"), "bb", 1)

    @Test fun aBrainThatDidNotAnswerIsSkippedForNow() = runBlocking {
        val asked = mutableListOf<String>()
        val pick = BrainChooser.firstReady(listOf(desk, server), setOf("desk")) { _, u -> asked += u; true }
        assertEquals("srv", pick!!.brain.id)
        assertEquals(listOf("https://srv:7787"), asked)
    }

    @Test fun aFrozenAddressDoesNotHoldUpTheOthers() = runBlocking {
        val t0 = System.currentTimeMillis()
        val pick = BrainChooser.firstReady(listOf(desk, server)) { _, u ->
            if (u.contains("lan")) { Thread.sleep(2_000); false } else true
        }
        assertEquals("https://desk-vpn:7787", pick!!.url)
        assertTrue("took ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 1_500)
    }
}
