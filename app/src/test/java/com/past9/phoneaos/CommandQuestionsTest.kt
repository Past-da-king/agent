package com.past9.phoneaos

import com.past9.phoneaos.remote.CommandQuestions
import com.past9.phoneaos.remote.RpcException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CommandQuestionsTest {
    private val origin get() = JSONObject().put("tool", "webapp_run").put("input", JSONObject().put("tool", "get_drive_access"))

    @Test fun sendsQuestionToOriginatingBrainInsteadOfNativeCard() = runBlocking {
        var native = 0
        val calls = mutableListOf<List<Any?>>()
        val relay = CommandQuestions("cmd-real", origin,
            call = { method, args, timeout -> assertEquals("device.ask", method); assertEquals(600_000L, timeout); calls += args; JSONObject().put("handled", true).put("answer", "Approve") },
            fallback = { _, _ -> native++; "Approve" })
        assertEquals("Approve", relay.ask("APPROVAL|Get access|Details", listOf("Approve", "Decline")))
        assertEquals("cmd-real", calls.single()[0]); assertEquals(1, calls.single()[1]); assertEquals(0, native)
    }
    @Test fun distinctQuestionsHaveDistinctSequenceNumbers() = runBlocking {
        val seen = mutableListOf<Any?>()
        val relay = CommandQuestions("cmd", origin, { _, args, _ -> seen += args[1]; JSONObject().put("handled", true).put("answer", "Decline") }, { _, _ -> fail("Unexpected native card"); null })
        relay.ask("One", emptyList()); relay.ask("Two", emptyList()); assertEquals(listOf(1, 2), seen)
    }
    @Test fun stoppedOriginalRunReturnsNoAnswerWithoutNativeFallback() = runBlocking {
        val relay = CommandQuestions("cmd", origin, { _, _, _ -> JSONObject().put("handled", true).put("answer", JSONObject.NULL) }, { _, _ -> fail("Unexpected native card"); null })
        assertNull(relay.ask("Question", emptyList()))
    }
    @Test fun cancellationNeverProducesANativeCard() = runBlocking {
        val relay = CommandQuestions("cmd", origin, { _, _, _ -> throw CancellationException("stopped") }, { _, _ -> fail("Unexpected native card"); null })
        try { relay.ask("Question", emptyList()); fail("Expected cancellation") } catch (_: CancellationException) {}
    }
    @Test fun ambiguousConnectionFailureDoesNotDuplicateAnAlreadyRaisedCard() = runBlocking {
        val relay = CommandQuestions("cmd", origin, { _, _, _ -> throw RpcException("Connection closed while waiting") }, { _, _ -> fail("Unexpected duplicate native card"); null })
        try { relay.ask("Question", emptyList()); fail("Expected failure") } catch (e: RpcException) { assertTrue(e.message.orEmpty().contains("Connection closed")) }
    }
    @Test fun definitelyAbsentBrainWaitsBeforeNativeFallback() = runBlocking {
        var waited = false
        val relay = CommandQuestions("cmd", origin, { _, _, _ -> throw RpcException("Not connected to a computer right now.") }, { _, _ -> assertTrue(waited); "Decline" }, { waited = true })
        assertEquals("Decline", relay.ask("Question", emptyList()))
    }
    @Test fun olderBrainUsesCompatiblePersonFallback() = runBlocking {
        val relay = CommandQuestions("cmd", origin, { _, _, _ -> throw RpcException("Unknown RPC device.ask") }, { _, _ -> "Decline" })
        assertEquals("Decline", relay.ask("Question", emptyList()))
    }
    @Test fun deployedOlderBrainUnavailableErrorUsesPersonFallback() = runBlocking {
        val relay = CommandQuestions("cmd", origin, { _, _, _ -> throw RpcException("not available on this device") }, { _, _ -> "Decline" })
        assertEquals("Decline", relay.ask("Question", emptyList()))
    }
    @Test fun lostOriginContextFallsBackToPerson() = runBlocking {
        var native = 0
        val relay = CommandQuestions("cmd", origin, { _, _, _ -> JSONObject().put("handled", false).put("answer", JSONObject.NULL) }, { _, _ -> native++; "Decline" })
        assertEquals("Decline", relay.ask("Question", emptyList())); assertEquals(1, native)
    }
    @Test fun invalidResponseNeverCreatesAnotherNativeCard() = runBlocking {
        val relay = CommandQuestions("cmd", origin, { _, _, _ -> "bad response" }, { _, _ -> fail("Unexpected native card"); null })
        try { relay.ask("Question", emptyList()); fail("Expected failure") } catch (_: RpcException) {}
    }
}
