package com.past9.phoneaos

import com.past9.phoneaos.voice.CallController
import com.past9.phoneaos.voice.LiveAuth
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LiveTokenTest {
    private val base = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"

    @Test fun aTokenFromTheComputerOpensTheConstrainedEndpoint() {
        val t = LiveAuth.parse(JSONObject().put("ok", true).put("token", "auth_tokens/a b").put("url", base).put("model", "gemini-3.8-live").put("voice", "Puck"))
        t as LiveAuth.Token.Ok
        assertEquals("$base?access_token=auth_tokens%2Fa+b", t.url)
        assertEquals("gemini-3.8-live", t.model)
        assertEquals("Puck", t.voice)
        assertFalse(t.url.contains("key="))
    }

    @Test fun noKeyOnTheComputerSaysToAddItThere() {
        val t = LiveAuth.parse(JSONObject().put("ok", false).put("needsKey", true).put("error", "x")) as LiveAuth.Token.No
        assertTrue(t.needsKey)
        assertTrue(t.why.contains("on your computer"))
        val q = LiveAuth.parse(JSONObject().put("ok", false).put("needsKey", false).put("error", "Gemini says this key is out of quota for now.")) as LiveAuth.Token.No
        assertEquals("Gemini says this key is out of quota for now.", q.why)
    }

    @Test fun aTokenIsNeverSentAnywhereButGoogle() {
        assertNull(LiveAuth.tokenUrl("wss://evil.example/ws", "auth_tokens/x"))
        assertNull(LiveAuth.tokenUrl(base, ""))
        assertTrue(LiveAuth.parse(JSONObject().put("ok", true).put("token", "t").put("url", "wss://evil.example/")) is LiveAuth.Token.No)
        assertTrue(LiveAuth.parse(null) is LiveAuth.Token.No)
    }

    @Test fun anOlderComputerIsToldApart() {
        assertTrue(LiveAuth.isOldComputer("not available on this device"))
        assertFalse(LiveAuth.isOldComputer("Not connected."))
    }

    @Test fun linkedNeedsNoKeyOnThePhone() {
        assertNull(CallController.reason(hasGeminiKey = false, linked = true))
        assertTrue(CallController.reason(hasGeminiKey = false, linked = false)!!.contains("Settings, Voice"))
    }

    @Test fun theCallIsSavedOnTheComputerAsYouAndAgent() {
        val r = LiveAuth.record(1_000, 61_000, listOf(Triple("in", "Book it", 2_000L), Triple("out", "On it", 3_000L)))
        assertEquals(60_000, r.getLong("durationMs"))
        val lines = r.getJSONArray("lines")
        assertEquals("you", lines.getJSONObject(0).getString("who"))
        assertEquals("agent", lines.getJSONObject(1).getString("who"))
    }

    @Test fun linkedCallsAskTheComputerForAToken() {
        val src = File("src/main/java/com/past9/phoneaos/voice/CallController.kt").readText()
        assertTrue(src.contains("\"voice.liveToken\""))
        assertTrue(src.contains("\"voice.callSave\""))
    }
}
