package com.past9.phoneaos.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Provider-neutral conversation model. Every provider adapter converts to and from this,
 * so the loop, the tools and the stored transcript never know which AI is behind them.
 */
enum class Role { USER, ASSISTANT }

sealed interface Block {
    data class Text(val text: String) : Block
    /** `extra`: provider data that must travel back with the call untouched (Gemini's thought_signature). */
    data class ToolCall(val id: String, val name: String, val input: JSONObject, val extra: JSONObject? = null) : Block
    data class ToolResult(val callId: String, val content: String, val isError: Boolean = false) : Block
    /** The model's private reasoning (DeepSeek-style). Never shown; echoed back where the provider requires it. */
    data class Reasoning(val text: String) : Block
    /** A picture the user sent: a file on the phone, read and encoded only when sent to the provider. */
    data class Image(val path: String, val mime: String = "image/jpeg") : Block {
        fun base64(): String = android.util.Base64.encodeToString(java.io.File(path).readBytes(), android.util.Base64.NO_WRAP)
    }
}

data class Msg(val role: Role, val blocks: List<Block>) {
    val text: String get() = blocks.filterIsInstance<Block.Text>().joinToString("\n") { it.text }.trim()
    val toolCalls: List<Block.ToolCall> get() = blocks.filterIsInstance<Block.ToolCall>()

    fun toJson(): JSONObject = JSONObject().put("role", role.name).put("blocks", JSONArray().apply {
        blocks.forEach { b ->
            put(when (b) {
                is Block.Text -> JSONObject().put("t", "text").put("text", b.text)
                is Block.ToolCall -> JSONObject().put("t", "call").put("id", b.id).put("name", b.name).put("input", b.input).apply { b.extra?.let { put("extra", it) } }
                is Block.ToolResult -> JSONObject().put("t", "result").put("id", b.callId).put("content", b.content).put("err", b.isError)
                is Block.Image -> JSONObject().put("t", "image").put("path", b.path).put("mime", b.mime)
                is Block.Reasoning -> JSONObject().put("t", "reasoning").put("text", b.text)
            })
        }
    })

    companion object {
        fun user(text: String) = Msg(Role.USER, listOf(Block.Text(text)))
        fun user(text: String, images: List<String>) = Msg(Role.USER, images.filter { java.io.File(it).exists() }.map { Block.Image(it) } + Block.Text(text))
        fun fromJson(o: JSONObject): Msg {
            val arr = o.getJSONArray("blocks")
            val blocks = (0 until arr.length()).map { i ->
                val b = arr.getJSONObject(i)
                when (b.getString("t")) {
                    "call" -> Block.ToolCall(b.getString("id"), b.getString("name"), b.optJSONObject("input") ?: JSONObject(), b.optJSONObject("extra"))
                    "result" -> Block.ToolResult(b.getString("id"), b.optString("content"), b.optBoolean("err"))
                    "image" -> Block.Image(b.getString("path"), b.optString("mime", "image/jpeg"))
                    "reasoning" -> Block.Reasoning(b.optString("text"))
                    else -> Block.Text(b.optString("text"))
                }
            }
            return Msg(Role.valueOf(o.getString("role")), blocks)
        }
    }
}

/** What a tool looks like to the model. `schema` is a JSON Schema object. */
data class ToolSpec(val name: String, val description: String, val schema: JSONObject)

data class Usage(val inputTokens: Int = 0, val outputTokens: Int = 0)

data class Completion(val message: Msg, val stopReason: String, val usage: Usage = Usage())

class ProviderException(message: String, val status: Int = 0, val retryable: Boolean = false) : Exception(message)

interface LlmProvider {
    val name: String
    suspend fun complete(system: String, messages: List<Msg>, tools: List<ToolSpec>, model: String, maxTokens: Int = 8192): Completion
}

/** Set on a tool call whose arguments could not be read as JSON; the value is how many characters arrived. */
const val BAD_ARGUMENTS = "__bad_arguments"

/**
 * Models often write code inside JSON with a single backslash (a regex like \d+ or \w, a selector like [data-x=\"y\"]).
 * That is not valid JSON, and Android's JSON reader does not complain: it drops the backslash, so \w becomes w and
 * /\/dp\/(\w+)/ becomes //dp/(w+)/, which is a comment. A backslash before a character JSON has no escape for is
 * meant literally, so it is doubled before reading. (\/ and \b are valid JSON escapes with other meanings; those stay.)
 */
object ToolArgs {
    private const val VALID = "\"\\/bfnrtu"
    fun fixEscapes(raw: String): String {
        if (!raw.contains('\\')) return raw
        val sb = StringBuilder(raw.length + 16); var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                val n = raw[i + 1]
                if (n in VALID) { sb.append(c).append(n); i += 2; continue }
                sb.append("\\\\"); i++; continue
            }
            sb.append(c); i++
        }
        return sb.toString()
    }
}

/** Tiny builder so tool schemas read like documentation. */
fun schema(required: List<String> = emptyList(), vararg props: Pair<String, JSONObject>): JSONObject =
    JSONObject().put("type", "object").put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
        .put("required", JSONArray(required))

fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
fun int(desc: String) = JSONObject().put("type", "integer").put("description", desc)
fun bool(desc: String) = JSONObject().put("type", "boolean").put("description", desc)
fun enumOf(desc: String, vararg values: String) = JSONObject().put("type", "string").put("description", desc).put("enum", JSONArray(values.toList()))
fun strList(desc: String) = JSONObject().put("type", "array").put("description", desc).put("items", JSONObject().put("type", "string"))
