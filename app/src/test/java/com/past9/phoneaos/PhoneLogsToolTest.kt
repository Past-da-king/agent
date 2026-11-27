package com.past9.phoneaos

import com.past9.phoneaos.remote.DeviceTools
import com.past9.phoneaos.tools.PhoneLogsTool
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Send logs to my computer": off by default, the app's own lines only, never a key (27 Nov 2026). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneLogsToolTest {
    /** Shaped like a Google key, built at run time so no key-like text sits in the source. */
    private val FAKE_KEY = "AIza" + "Sy" + "x".repeat(33)
    private val lines = listOf(
        "11-27 13:22:01.100 I/CallAgent( 4242): tool call start_work: {}",
        "11-27 13:22:01.200 I/AosMediaHold( 4242): enforce: music was still playing, paused [com.example.music]",
        "11-27 13:22:01.300 D/OkHttp( 4242): GET https://example.com/live?key=" + FAKE_KEY,
        "11-27 13:22:01.400 I/CallAgent( 4242): Authorization: Bearer abcdef123456",
    )

    @Test fun theLogsGroupIsOffUntilHeSwitchesItOn() {
        assertEquals(false, DeviceTools.GROUPS["logs"])
        assertEquals("logs", DeviceTools.groupOf("phone_logs"))
        assertTrue("phone_logs" in DeviceTools.LENT)
    }

    @Test fun filtersByTagAndWord() {
        assertEquals(2, PhoneLogsTool.filter(lines, listOf("CallAgent"), "").size)
        assertEquals(1, PhoneLogsTool.filter(lines, listOf("CallAgent", "AosMediaHold"), "paused").size)
    }

    @Test fun keysAndTokensNeverLeaveThePhone() {
        val out = runBlocking { PhoneLogsTool { lines }.run(JSONObject(), FakeCtx) }
        assertFalse(out.contains("AIzaSy"))
        assertFalse(out.contains("abcdef123456"))
        assertTrue(out.contains("[hidden]"))
        assertTrue(out.contains("paused [com.example.music]"))
    }

    private object FakeCtx : com.past9.phoneaos.agent.ToolContext {
        override val agentLabel = "test"
        override suspend fun activity(text: String, meta: JSONObject): Long = 0
        override suspend fun updateActivity(id: Long, text: String, meta: JSONObject) {}
        override suspend fun ask(question: String, options: List<String>): String? = null
        override suspend fun notify(title: String, body: String) {}
    }
}
