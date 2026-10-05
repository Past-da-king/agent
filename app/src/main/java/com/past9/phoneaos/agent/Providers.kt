package com.past9.phoneaos.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private val JSON = "application/json".toMediaType()

/** Our own client name: some providers (OpenCode Go) refuse generic HTTP-library user agents. */
const val USER_AGENT = "phone-agent/0.3 (Android)"

val sharedHttp: OkHttpClient by lazy {
    OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()
}

/** POST JSON with retries on 429/5xx/network. */
internal suspend fun postJson(http: OkHttpClient, url: String, headers: Map<String, String>, body: JSONObject, attempts: Int = 3): JSONObject =
    withContext(Dispatchers.IO) {
        var last: Exception? = null
        repeat(attempts) { attempt ->
            try {
                val req = Request.Builder().url(url).post(body.toString().toRequestBody(JSON)).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
                http.newCall(req).execute().use { res ->
                    val text = res.body?.string().orEmpty()
                    if (res.isSuccessful) return@withContext JSONObject(text)
                    val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()?.takeIf { it.isNotBlank() } ?: text.take(300)
                    val retry = res.code == 429 || res.code >= 500
                    val e = ProviderException("HTTP ${res.code}: $msg", res.code, retry)
                    if (!retry) throw e
                    last = e
                }
            } catch (e: IOException) { last = ProviderException("Network: ${e.message}", 0, true) }
            if (attempt < attempts - 1) delay(1500L * (attempt + 1))
        }
        throw last ?: ProviderException("request failed")
    }

/** Anthropic Messages API. */
class AnthropicProvider(private val apiKey: String, private val baseUrl: String = "https://api.anthropic.com/v1", private val http: OkHttpClient = sharedHttp) : LlmProvider {
    override val name = "anthropic"

    override suspend fun complete(system: String, messages: List<Msg>, tools: List<ToolSpec>, model: String, maxTokens: Int): Completion {
        val body = JSONObject().put("model", model).put("max_tokens", maxTokens).put("system", system)
            .put("messages", JSONArray().apply { messages.forEach { put(toWire(it)) } })
        if (tools.isNotEmpty()) body.put("tools", JSONArray().apply {
            tools.forEach { put(JSONObject().put("name", it.name).put("description", it.description).put("input_schema", it.schema)) }
        })
        val res = postJson(http, "$baseUrl/messages", mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01", "User-Agent" to USER_AGENT), body)
        val content = res.optJSONArray("content") ?: JSONArray()
        val blocks = (0 until content.length()).mapNotNull { i ->
            val b = content.getJSONObject(i)
            when (b.optString("type")) {
                "text" -> Block.Text(b.optString("text"))
                "tool_use" -> Block.ToolCall(b.getString("id"), b.getString("name"), b.optJSONObject("input") ?: JSONObject())
                else -> null
            }
        }
        val u = res.optJSONObject("usage")
        return Completion(Msg(Role.ASSISTANT, blocks), res.optString("stop_reason"), Usage(u?.optInt("input_tokens") ?: 0, u?.optInt("output_tokens") ?: 0))
    }

    private fun toWire(m: Msg): JSONObject = JSONObject().put("role", if (m.role == Role.USER) "user" else "assistant")
        .put("content", JSONArray().apply {
            m.blocks.forEach { b ->
                when (b) {
                    is Block.Text -> if (b.text.isNotEmpty()) put(JSONObject().put("type", "text").put("text", b.text))
                    is Block.ToolCall -> put(JSONObject().put("type", "tool_use").put("id", b.id).put("name", b.name).put("input", b.input))
                    is Block.ToolResult -> put(JSONObject().put("type", "tool_result").put("tool_use_id", b.callId).put("content", b.content).put("is_error", b.isError))
                    is Block.Reasoning -> {}
                    is Block.Image -> if (java.io.File(b.path).exists()) put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", b.mime).put("data", b.base64())))
                }
            }
        })
}

/** OpenAI Chat Completions shape: OpenAI, OpenRouter and Gemini's OpenAI-compatible endpoint. */
class OpenAiCompatProvider(override val name: String, private val apiKey: String, private val baseUrl: String, private val http: OkHttpClient = sharedHttp,
                           /** Provider-specific body fields, e.g. DeepSeek's thinking switch. */
                           private val extra: JSONObject = JSONObject(),
                           /** Provider-specific headers, e.g. OpenCode Go's per-conversation session id. */
                           private val extraHeaders: () -> Map<String, String> = { emptyMap() }) : LlmProvider {

    override suspend fun complete(system: String, messages: List<Msg>, tools: List<ToolSpec>, model: String, maxTokens: Int): Completion {
        val wire = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        messages.forEach { m -> toWire(m).forEach { wire.put(it) } }
        val body = JSONObject().put("model", model).put("messages", wire).put("max_tokens", maxTokens)
        extra.keys().forEach { body.put(it, extra.get(it)) }
        if (tools.isNotEmpty()) body.put("tools", JSONArray().apply {
            tools.forEach { put(JSONObject().put("type", "function").put("function", JSONObject().put("name", it.name).put("description", it.description).put("parameters", it.schema))) }
        })
        val headers = mutableMapOf("Authorization" to "Bearer $apiKey", "User-Agent" to USER_AGENT)
        headers.putAll(extraHeaders())
        if (name == "openrouter") headers["X-Title"] = "Phone agent"
        val res = postJson(http, "${baseUrl.trimEnd('/')}/chat/completions", headers, body)
        val choice = res.optJSONArray("choices")?.optJSONObject(0) ?: throw ProviderException("No choices in response")
        val msg = choice.getJSONObject("message")
        val blocks = mutableListOf<Block>()
        msg.optString("reasoning_content").takeIf { it.isNotBlank() && it != "null" }?.let { blocks += Block.Reasoning(it) }
        msg.optString("content").takeIf { it.isNotBlank() && it != "null" }?.let { blocks += Block.Text(it) }
        msg.optJSONArray("tool_calls")?.let { calls ->
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i); val f = c.getJSONObject("function")
                val args = runCatching { JSONObject(f.optString("arguments").ifBlank { "{}" }) }.getOrDefault(JSONObject())
                blocks += Block.ToolCall(c.optString("id").ifBlank { "call_$i" }, f.getString("name"), args)
            }
        }
        val u = res.optJSONObject("usage")
        return Completion(Msg(Role.ASSISTANT, blocks), choice.optString("finish_reason"), Usage(u?.optInt("prompt_tokens") ?: 0, u?.optInt("completion_tokens") ?: 0))
    }

    private fun toWire(m: Msg): List<JSONObject> {
        if (m.role == Role.ASSISTANT) {
            val o = JSONObject().put("role", "assistant").put("content", m.text.ifEmpty { JSONObject.NULL })
            // Reasoning models behind OpenAI-style APIs (DeepSeek, OpenCode) want their reasoning back on tool turns.
            m.blocks.filterIsInstance<Block.Reasoning>().joinToString("\n") { it.text }.takeIf { it.isNotBlank() }?.let { o.put("reasoning_content", it) }
            if (m.toolCalls.isNotEmpty()) o.put("tool_calls", JSONArray().apply {
                m.toolCalls.forEach { put(JSONObject().put("id", it.id).put("type", "function").put("function", JSONObject().put("name", it.name).put("arguments", it.input.toString()))) }
            })
            return listOf(o)
        }
        val out = mutableListOf<JSONObject>()
        m.blocks.filterIsInstance<Block.ToolResult>().forEach { out += JSONObject().put("role", "tool").put("tool_call_id", it.callId).put("content", it.content) }
        val text = m.text
        val images = m.blocks.filterIsInstance<Block.Image>().filter { java.io.File(it.path).exists() }
        if (images.isNotEmpty()) out += JSONObject().put("role", "user").put("content", JSONArray().apply {
            images.forEach { put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:${it.mime};base64,${it.base64()}"))) }
            if (text.isNotEmpty()) put(JSONObject().put("type", "text").put("text", text))
        })
        else if (text.isNotEmpty()) out += JSONObject().put("role", "user").put("content", text)
        return out
    }
}
