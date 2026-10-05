package com.past9.phoneaos

import com.past9.phoneaos.agent.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Wire-format tests: each provider adapter speaks its API and maps back to the neutral model. */
class ProvidersTest {
    private val tool = ToolSpec("memory_save", "save", schema(listOf("title"), "title" to str("t")))

    @Test fun anthropicToolCallRoundTrip() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"On it."},{"type":"tool_use","id":"tu_1","name":"memory_save","input":{"title":"Seats"}}],"stop_reason":"tool_use","usage":{"input_tokens":10,"output_tokens":5}}"""))
        server.start()
        val p = AnthropicProvider("sk-test", server.url("/v1").toString().trimEnd('/'))
        val history = listOf(Msg.user("hi"), Msg(Role.ASSISTANT, listOf(Block.ToolCall("tu_0", "now", JSONObject()))), Msg(Role.USER, listOf(Block.ToolResult("tu_0", "Monday"))))
        val c = p.complete("sys", history, listOf(tool), "claude-sonnet-5-5")
        val req = server.takeRequest()
        assertEquals("/v1/messages", req.path)
        assertEquals("sk-test", req.getHeader("x-api-key"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("sys", body.getString("system"))
        assertEquals("memory_save", body.getJSONArray("tools").getJSONObject(0).getString("name"))
        assertEquals("tool_result", body.getJSONArray("messages").getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("type"))
        assertEquals("On it.", c.message.text)
        assertEquals("Seats", c.message.toolCalls.single().input.getString("title"))
        assertEquals(5, c.usage.outputTokens)
        server.shutdown()
    }

    @Test fun openAiCompatToolCallRoundTrip() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"memory_save","arguments":"{\"title\":\"Seats\"}"}}]},"finish_reason":"tool_calls"}]}"""))
        server.start()
        val p = OpenAiCompatProvider("openai", "sk-test", server.url("/v1").toString())
        val history = listOf(Msg.user("hi"), Msg(Role.ASSISTANT, listOf(Block.ToolCall("c0", "now", JSONObject()))), Msg(Role.USER, listOf(Block.ToolResult("c0", "Monday"))))
        val c = p.complete("sys", history, listOf(tool), "gpt-5")
        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
        val msgs = JSONObject(req.body.readUtf8()).getJSONArray("messages")
        assertEquals("system", msgs.getJSONObject(0).getString("role"))
        assertEquals("tool", msgs.getJSONObject(3).getString("role"))
        assertEquals("c0", msgs.getJSONObject(3).getString("tool_call_id"))
        assertEquals("Seats", c.message.toolCalls.single().input.getString("title"))
        server.shutdown()
    }

    @Test fun retriesOn429ThenSucceeds() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"slow down"}}"""))
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}"""))
        server.start()
        val c = AnthropicProvider("k", server.url("/v1").toString().trimEnd('/')).complete("s", listOf(Msg.user("x")), emptyList(), "m")
        assertEquals("ok", c.message.text); assertEquals(2, server.requestCount)
        server.shutdown()
    }

    @Test fun badKeyIsNotRetried() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"invalid x-api-key"}}"""))
        server.start()
        val e = runCatching { AnthropicProvider("k", server.url("/v1").toString().trimEnd('/')).complete("s", listOf(Msg.user("x")), emptyList(), "m") }.exceptionOrNull()
        assertTrue(e is ProviderException && e.status == 401); assertEquals(1, server.requestCount)
        server.shutdown()
    }

    @Test fun msgJsonRoundTrip() {
        val m = Msg(Role.ASSISTANT, listOf(Block.Text("a"), Block.ToolCall("1", "x", JSONObject().put("k", 1)), Block.ToolResult("1", "r", true)))
        assertEquals(m.toString(), Msg.fromJson(JSONObject(m.toJson().toString())).toString().let { it })
    }
}
