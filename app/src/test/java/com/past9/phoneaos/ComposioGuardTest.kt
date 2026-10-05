package com.past9.phoneaos

import com.past9.phoneaos.agent.GuardedAppsRunTool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.tools.AppsRunTool
import com.past9.phoneaos.tools.ComposioClient
import com.past9.phoneaos.triggers.Routines
import com.past9.phoneaos.data.TriggerRow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ComposioGuardTest {
    class Ctx(private val answer: String?) : ToolContext {
        val asked = mutableListOf<String>()
        override val agentLabel = "main"
        override suspend fun activity(text: String, meta: JSONObject) = 1L
        override suspend fun updateActivity(id: Long, text: String, meta: JSONObject) {}
        override suspend fun ask(question: String, options: List<String>): String? { asked += question; return answer }
        override suspend fun notify(title: String, body: String) {}
    }

    private fun server() = MockWebServer().apply { start() }

    @Test fun readActionsRunWithoutAsking() = runBlocking {
        val s = server(); s.enqueue(MockResponse().setBody("""{"data":{"messages":[]},"successful":true,"error":null}"""))
        val tool = GuardedAppsRunTool(AppsRunTool(ComposioClient({ "ck" }, "phone-1", s.url("/api/v3.1").toString().trimEnd('/'))))
        val ctx = Ctx("Decline")
        tool.run(JSONObject().put("slug", "GMAIL_FETCH_EMAILS").put("arguments", JSONObject().put("query", "is:unread")), ctx)
        assertTrue(ctx.asked.isEmpty())
        val req = s.takeRequest()
        assertEquals("/api/v3.1/tools/execute/GMAIL_FETCH_EMAILS", req.path)
        assertEquals("ck", req.getHeader("x-api-key"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("phone-1", body.getString("user_id"))
        s.shutdown()
    }

    @Test fun sendingIsBlockedWithoutApproval() = runBlocking {
        val s = server()
        val tool = GuardedAppsRunTool(AppsRunTool(ComposioClient({ "ck" }, "phone-1", s.url("/api/v3.1").toString().trimEnd('/'))))
        val ctx = Ctx("Decline")
        val out = tool.run(JSONObject().put("slug", "GMAIL_SEND_EMAIL").put("arguments", JSONObject().put("to", "a@b.c")), ctx)
        assertTrue(out.contains("declined")); assertEquals(0, s.requestCount)
        assertTrue(ctx.asked.single().startsWith("APPROVAL|gmail send email|"))
        val bg = tool.run(JSONObject().put("slug", "GMAIL_SEND_EMAIL").put("arguments", JSONObject()), Ctx(null))
        assertTrue(bg.contains("nobody is around")); assertEquals(0, s.requestCount)
        s.shutdown()
    }

    @Test fun connectCreatesManagedAuthAndReturnsLink() = runBlocking {
        val s = server()
        s.enqueue(MockResponse().setBody("""{"items":[]}"""))
        s.enqueue(MockResponse().setBody("""{"auth_config":{"id":"ac_1"}}"""))
        s.enqueue(MockResponse().setBody("""{"redirect_url":"https://connect.composio.dev/x","connected_account_id":"ca_1","expires_at":"","link_token":""}"""))
        val c = ComposioClient({ "ck" }, "phone-1", s.url("/api/v3.1").toString().trimEnd('/'))
        assertEquals("https://connect.composio.dev/x", c.connect("gmail"))
        assertTrue(s.takeRequest().path!!.startsWith("/api/v3.1/auth_configs?toolkit_slug=gmail"))
        assertEquals("use_composio_managed_auth", JSONObject(s.takeRequest().body.readUtf8()).getJSONObject("auth_config").getString("type"))
        val link = JSONObject(s.takeRequest().body.readUtf8())
        assertEquals("ac_1", link.getString("auth_config_id")); assertEquals("phone-1", link.getString("user_id"))
        s.shutdown()
    }

    @Test fun dailyRoutineNextRun() {
        val now = ZonedDateTime.of(2026, 10, 5, 8, 0, 0, 0, ZoneId.of("Africa/Johannesburg"))
        val r = TriggerRow(name = "x", kind = "daily", spec = "07:00", prompt = "p")
        assertEquals(23 * 3_600_000L, Routines.nextRunIn(r, now))
        assertEquals(3_600_000L, Routines.nextRunIn(r.copy(spec = "09:00"), now))
        assertEquals("abc", Routines.newestMessageId(JSONObject("""{"messages":[{"messageId":"abc"},{"messageId":"old"}]}""")))
    }
}
