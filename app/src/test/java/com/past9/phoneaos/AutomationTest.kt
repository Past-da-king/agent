package com.past9.phoneaos

import com.past9.phoneaos.automation.AutomationApi
import com.past9.phoneaos.automation.AutomationRoutine
import com.past9.phoneaos.automation.AutomationRuns
import com.past9.phoneaos.automation.AutomationServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * The outside entry point (GitHub issue #18), tested without Android: the token rule and the JSON shapes we promised,
 * then the real server on a loopback socket.
 *
 * The approval story is not re-tested here. A run fired from outside is non-interactive, so the existing gate
 * (ChatContext("routine", interactive = false) and AppGuard) fails closed and refuses anything that needs the user's
 * yes; that gate has its own tests. What these pin is that nothing gets past the token and the switch, and that a macro
 * can fire a routine and read its state.
 */
class AutomationTest {
    private val token = "abc123def456"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: AutomationServer? = null
    private var fired = 0
    private val routines = listOf(
        AutomationRoutine(1, "Morning brief", true, "Every day at 07:00", "Yesterday: all clear"),
        AutomationRoutine(2, "Night shift", false, "Every day at 23:00", ""),
    )

    @Before fun reset() { AutomationRuns.clear(); fired = 0 }
    @After fun stopIt() { server?.stop(); scope.cancel() }

    // ---- 1. the token rule and the JSON we promised -------------------------------------------------------------

    @Test fun theTokenRule() {
        assertTrue(AutomationApi.authorised("Bearer $token", token))
        assertFalse("no header", AutomationApi.authorised(null, token))
        assertFalse("blank header", AutomationApi.authorised("", token))
        assertFalse("wrong token", AutomationApi.authorised("Bearer nope", token))
        assertFalse("no Bearer", AutomationApi.authorised(token, token))
        assertFalse("no token set", AutomationApi.authorised("Bearer ", ""))
    }

    @Test fun theJsonShapes() {
        val list = JSONObject(AutomationApi.routines(routines)).getJSONArray("routines")
        assertEquals(2, list.length())
        assertEquals("Morning brief", list.getJSONObject(0).getString("name"))
        assertEquals("Every day at 07:00", list.getJSONObject(0).getString("schedule"))
        assertTrue(list.getJSONObject(1).has("lastResult"))

        val ack = JSONObject(AutomationApi.runAck("run-7"))
        assertEquals("run-7", ack.getString("runId"))
        assertEquals("running", ack.getString("status"))

        AutomationRuns.start("run").also { id ->
            AutomationRuns.finish(id, "All done.")
            val state = JSONObject(AutomationApi.runState(AutomationRuns.get(id)))
            assertEquals("done", state.getString("status"))
            assertEquals("All done.", state.getString("result"))
            assertTrue(state.has("durationMs"))
            assertTrue(AutomationApi.needsApproval("Needs the user's approval and nobody is around. Not done."))
            assertFalse(AutomationApi.needsApproval("All done."))
        }

        val err = JSONObject(AutomationApi.error(401, "unauthorized"))
        assertEquals(401, err.getInt("code"))
        assertEquals("unauthorized", err.getString("error"))
    }

    // ---- 2. the real server on a loopback socket -----------------------------------------------------------------

    private fun start(allowed: Boolean = true, served: String = token): String {
        val s = AutomationServer(0)
        s.start(scope, { served }, {
            AutomationServer.Handlers(
                allowed = { allowed },
                routines = { routines },
                fire = { r, _ -> fired++; AutomationRuns.start("run").also { AutomationRuns.finish(it, "Fired ${r.name}.") } },
                instruct = { AutomationRuns.start("instruction").also { AutomationRuns.finish(it, "Answered.") } },
                run = { id -> AutomationRuns.get(id) },
            )
        })
        server = s
        assertEquals("it must bind a real port", true, s.running)
        return s.url
    }

    private fun call(base: String, method: String, path: String, bearer: String? = token, body: String? = null): Pair<Int, String> {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 5000
        c.readTimeout = 5000
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        return code to text
    }

    @Test fun noTokenGets401AndNothingRuns() {
        val base = start()
        val (code, body) = call(base, "GET", "/v1/routines", bearer = null)
        assertEquals(401, code)
        assertTrue(body, body.contains("unauthorized"))
        assertEquals("nothing may run", 0, fired)
    }

    @Test fun aWrongTokenGets401() {
        val base = start()
        val (code, body) = call(base, "GET", "/v1/routines", bearer = "not-the-token")
        assertEquals(401, code)
        assertTrue(body, body.contains("4401") || body.contains("\"code\":401"))
        assertEquals("nothing may run", 0, fired)
    }

    @Test fun theSwitchOffGets403WithAReason() {
        val base = start(allowed = false)
        val (code, body) = call(base, "GET", "/v1/routines")
        assertEquals(403, code)
        assertTrue(body, body.contains("switched off"))
        val (health, _) = call(base, "GET", "/v1/health")
        assertEquals("health still answers, so a macro can see why", 200, health)
    }

    @Test fun theRoutineListIsJson() {
        val base = start()
        val (code, body) = call(base, "GET", "/v1/routines")
        assertEquals(200, code)
        val list = JSONObject(body).getJSONArray("routines")
        assertEquals(2, list.length())
        assertEquals(1L, list.getJSONObject(0).getLong("id"))
        assertTrue(list.getJSONObject(0).getBoolean("enabled"))
    }

    @Test fun anUnknownRoutineGets404AndNothingRuns() {
        val base = start()
        val (code, body) = call(base, "POST", "/v1/routines/run", body = """{"name":"Does not exist"}""")
        assertEquals(404, code)
        assertTrue(body, body.contains("no routine called"))
        assertEquals("nothing may run", 0, fired)
    }

    @Test fun aKnownRoutineReturnsARunIdAndItsState() {
        val base = start()
        val (code, body) = call(base, "POST", "/v1/routines/run", body = """{"name":"Morning brief"}""")
        assertEquals(200, code)
        val id = JSONObject(body).getString("runId")
        assertEquals("running", JSONObject(body).getString("status"))
        assertEquals("the routine ran once", 1, fired)

        val (stateCode, stateBody) = call(base, "GET", "/v1/runs/$id")
        assertEquals(200, stateCode)
        val state = JSONObject(stateBody)
        assertEquals("done", state.getString("status"))
        assertEquals("Fired Morning brief.", state.getString("result"))

        val (missing, _) = call(base, "GET", "/v1/runs/run-999")
        assertEquals(404, missing)
    }

    @Test fun stoppingTwiceIsSafe() {
        val base = start()
        assertEquals(200, call(base, "GET", "/v1/health").first)
        server?.stop()
        server?.stop()
        val refused = runCatching { call(base, "GET", "/v1/health").first }.getOrNull()
        assertTrue("a stopped server does not answer", refused == null || refused != 200)
    }
}
