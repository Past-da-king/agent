package com.past9.phoneaos.agent

import android.content.Context
import com.past9.phoneaos.data.Provider
import com.past9.phoneaos.data.SubKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

data class ModelInfo(val id: String, val name: String, val vision: Boolean, val released: String, val note: String = "")

/**
 * Which models the user can pick, always current: we ask the provider itself what the key can use
 * (its /models list), and enrich that from models.dev (names, image support, release dates) so the
 * newest sit on top. Nothing is hard-coded except the Claude subscription aliases, which Claude
 * Code always maps to the newest Opus/Sonnet/Haiku.
 */
object ModelCatalog {
    private val devIds = mapOf(
        Provider.ANTHROPIC to "anthropic", Provider.OPENAI to "openai", Provider.GEMINI to "google", Provider.DEEPSEEK to "deepseek",
        Provider.OPENROUTER to "openrouter", Provider.OPENCODE_GO to "opencode-go", Provider.OPENCODE_ZEN to "opencode",
    )
    private val notChat = Regex("(?i)embed|tts|whisper|transcri|dall-e|image-gen|imagen|moderation|realtime|audio|veo|aqa|search-preview|babbage|davinci|computer-use")

    /** models.dev, cached for a day: the public, community-kept catalogue of every provider's models. */
    private suspend fun catalogue(context: Context): JSONObject? = withContext(Dispatchers.IO) {
        val f = File(context.cacheDir, "models-dev.json")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 86_400_000L) return@withContext runCatching { JSONObject(f.readText()) }.getOrNull()
        runCatching {
            sharedHttp.newCall(Request.Builder().url("https://models.dev/api.json").build()).execute().use { r ->
                val t = r.body!!.string(); if (r.isSuccessful) { f.writeText(t); JSONObject(t) } else null
            }
        }.getOrNull() ?: runCatching { JSONObject(f.readText()) }.getOrNull()
    }

    private fun info(id: String, meta: JSONObject?): ModelInfo {
        val inputs = meta?.optJSONObject("modalities")?.optJSONArray("input")
        val vision = meta?.optBoolean("attachment") == true || (inputs != null && (0 until inputs.length()).any { inputs.getString(it) == "image" })
        return ModelInfo(id, meta?.optString("name")?.takeIf { it.isNotBlank() } ?: id, vision, meta?.optString("release_date").orEmpty())
    }

    /** Models this key can actually use, newest first. */
    suspend fun forKey(context: Context, p: Provider, key: String, baseUrl: String): List<ModelInfo> = withContext(Dispatchers.IO) {
        val dev = catalogue(context)?.optJSONObject(devIds[p] ?: "")?.optJSONObject("models")
        val base = baseUrl.ifBlank { p.baseUrl }.trimEnd('/')
        val req = Request.Builder().url("$base/models").apply {
            if (p == Provider.ANTHROPIC) { header("x-api-key", key); header("anthropic-version", "2023-06-01") } else header("Authorization", "Bearer $key")
        }.build()
        val ids = runCatching {
            sharedHttp.newCall(req).execute().use { r ->
                val arr = JSONObject(r.body!!.string()).optJSONArray("data") ?: org.json.JSONArray()
                (0 until arr.length()).map { arr.getJSONObject(it).optString("id").removePrefix("models/") }
            }
        }.getOrDefault(emptyList()).ifEmpty { dev?.keys()?.asSequence()?.toList().orEmpty() }
        ids.filter { it.isNotBlank() && !notChat.containsMatchIn(it) }.distinct()
            .map { info(it, dev?.optJSONObject(it)) }
            .sortedWith(compareByDescending<ModelInfo> { it.released }.thenBy { it.id })
    }

    /** Subscriptions: Claude Code takes aliases that always point at the newest model of each tier. */
    suspend fun forSubscription(context: Context, kind: SubKind): List<ModelInfo> = when (kind) {
        SubKind.CLAUDE -> listOf(
            ModelInfo("opus", "Opus", true, "", "Most capable. Uses your limits fastest"),
            ModelInfo("sonnet", "Sonnet", true, "", "The everyday choice: smart and quick"),
            ModelInfo("haiku", "Haiku", true, "", "Fastest and lightest on your limits"),
        )
        SubKind.CODEX -> {
            val dev = catalogue(context)?.optJSONObject("openai")?.optJSONObject("models")
            val ids = dev?.keys()?.asSequence()?.filter { it.startsWith("gpt-5") && !notChat.containsMatchIn(it) }?.toList().orEmpty()
            listOf(ModelInfo("", "Codex default", true, "", "Whatever Codex recommends for your plan")) +
                ids.map { info(it, dev?.optJSONObject(it)) }.sortedByDescending { it.released }.take(8)
        }
        SubKind.OPENCODE -> emptyList()
    }
}
