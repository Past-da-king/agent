package com.past9.phoneaos

import com.past9.phoneaos.remote.Sender
import org.junit.Assert.*
import org.junit.Test

class AgentSenderTest {
    @Test fun legacyEnvelopesBelongToAgents() {
        for (text in listOf("[From project Example] report", "[Helper #h1 \"Phone player\" finished] report", "[Helpers finished: all ended] report", "[From Hina, the main agent] report")) {
            assertNotNull(text, Sender.of("{}", text))
            assertFalse(text, Sender.isHis("user", "{}", text))
            assertTrue(Sender.isHis("user", """{"own":true}""", text))
        }
    }
    @Test fun ordinaryMessagesAreNeverGuessed() {
        for (text in listOf("hello", "Buyer PDF branch now abc", "I saw [Helper #h1 finished] today")) {
            assertNull(Sender.of("{}", text))
            assertTrue(Sender.isHis("user", "{}", text))
        }
    }
    @Test fun malformedMetaAndNewSenderKindsRemainReadable() {
        assertNotNull(Sender.of("broken", "[From project Example] report"))
        for (kind in listOf("helper", "project", "routine", "device", "desk", "system")) {
            assertEquals(kind, Sender.of("""{"via":{"from":"$kind","name":"Source","chat":"main","id":"s1"}}""")?.from)
        }
        assertNull(Sender.of("""{"via":{"from":"future-kind"}}"""))
    }
}
