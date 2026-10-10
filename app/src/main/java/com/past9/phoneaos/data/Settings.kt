package com.past9.phoneaos.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How the agent is powered. */
enum class PowerMode { NONE, API_KEY, SUBSCRIPTION }

/** Subscriptions people already pay for that come with a coding-agent CLI we can run in-app. */
enum class SubKind(val label: String, val plan: String, val line: String) {
    CLAUDE("Claude", "Pro or Max", "Claude Code runs inside the app on your Claude plan."),
    CODEX("ChatGPT", "Plus or Pro", "OpenAI's Codex runs inside the app on your ChatGPT plan."),
    OPENCODE("OpenCode", "Zen or Go", "Paste your OpenCode key and your agent runs on your OpenCode plan."),
}

enum class Provider(val label: String, val defaultModel: String, val helperModel: String, val baseUrl: String, val keyHint: String) {
    ANTHROPIC("Anthropic", "claude-sonnet-5-5", "claude-haiku-4-5-20251001", "https://api.anthropic.com/v1", "Paste your key here"),
    OPENAI("OpenAI", "gpt-5", "gpt-5-mini", "https://api.openai.com/v1", "Paste your key here"),
    GEMINI("Google Gemini", "gemini-flash-latest", "gemini-flash-lite-latest", "https://generativelanguage.googleapis.com/v1beta/openai", "Paste your key here"),
    DEEPSEEK("DeepSeek", "deepseek-flash", "deepseek-flash", "https://api.deepseek.com/v1", "Paste your key here"),
    OPENCODE_GO("OpenCode Go", "deepseek-v4.1-flash", "deepseek-v4.1-flash", "https://opencode.ai/zen/go/v1", "Paste your key here"),
    OPENCODE_ZEN("OpenCode Zen", "claude-haiku-4-5", "claude-haiku-4-5", "https://opencode.ai/zen/v1", "Paste your key here"),
    OPENROUTER("OpenRouter", "openrouter/auto", "openrouter/auto", "https://openrouter.ai/api/v1", "Paste your key here"),
    CUSTOM("Other (OpenAI-compatible)", "", "", "", "Paste your key here"),
}

/** A model the main agent may hand work to, with what the user says it's good for. id "" = the same as the main agent's. */
data class HelperModel(val id: String, val name: String, val tags: List<String> = emptyList()) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject().put("id", id).put("name", name).put("tags", org.json.JSONArray(tags))
    companion object {
        fun listFrom(raw: String?): List<HelperModel> = runCatching {
            val a = org.json.JSONArray(raw ?: return emptyList())
            (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
                HelperModel(o.optString("id"), o.optString("name").ifBlank { o.optString("id") }, o.optJSONArray("tags")?.let { t -> (0 until t.length()).map { t.getString(it) } }.orEmpty())
            } }
        }.getOrDefault(emptyList())
        /** What helpers can be good for: offered as tags in the helper sheet. */
        val PresetTags = listOf("Quick lookups", "Web research", "Browsing sites", "Writing", "Planning", "Apps and email", "Files and data", "Coding", "Hard problems", "Cheap bulk work")
    }
}

data class AgentSettings(
    val mode: PowerMode = PowerMode.NONE,
    val provider: Provider = Provider.ANTHROPIC,
    val model: String = Provider.ANTHROPIC.defaultModel,
    val helperModel: String = Provider.ANTHROPIC.helperModel,
    val hasKey: Boolean = false,
    val userName: String = "",
    val agentName: String = "Your agent",
    val speakReplies: Boolean = false,
    val composioEnabled: Boolean = false,
    val onboarded: Boolean = false,
    val baseUrl: String = "",
    /** The user opted in to plain http:// for their custom (self-hosted) server. Off by default. */
    val allowHttp: Boolean = false,
    val accent: String = "iris",
    val mascot: String = "scout",
    val subKind: SubKind = SubKind.CLAUDE,
    /** Apps whose notifications the agent may read (package names). Empty = none. */
    val notifApps: Set<String> = emptySet(),
    /** Browser profiles: separate cookie jars so work and personal accounts both stay signed in. */
    val browserProfiles: List<String> = listOf("Personal"),
    /** Model for the subscription path ("" = the CLI's default). */
    val subModel: String = "",
    /** Model the subscription's helpers run on ("" = the same as subModel). */
    val subHelperModel: String = "",
    /** The models helpers can run on for the current power (API provider or subscription), each tagged with what it's good for. Empty = just the one helper model. */
    val helperRoster: List<HelperModel> = emptyList(),
    /** Voice: who speaks the agent's replies and voice notes (system | gemini | openai | elevenlabs). */
    val ttsProvider: String = "system",
    val ttsVoice: String = "",
    /** Live voice calls with the agent (needs a provider with a realtime model, e.g. Gemini Live). */
    val liveEnabled: Boolean = false,
    /** Play voice notes (and read replies) automatically while earphones are in. */
    val autoPlayEarphones: Boolean = true,
) {
    val ready: Boolean get() = when (mode) {
        PowerMode.API_KEY -> hasKey
        PowerMode.SUBSCRIPTION -> true
        PowerMode.NONE -> false
    }
}

/**
 * Plain settings in SharedPreferences; secrets (API keys, OAuth tokens) in an
 * EncryptedSharedPreferences file keyed by the Android Keystore. Nothing leaves the phone.
 */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secrets: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context, "secrets",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Throwable) {
        // Robolectric and a few broken keystores: fall back to private prefs rather than crash.
        context.getSharedPreferences("secrets_fallback", Context.MODE_PRIVATE)
    }

    private val _state = MutableStateFlow(read())
    val state: StateFlow<AgentSettings> = _state

    private fun read(): AgentSettings {
        val provider = runCatching { Provider.valueOf(prefs.getString("provider", Provider.ANTHROPIC.name)!!) }.getOrDefault(Provider.ANTHROPIC)
        return AgentSettings(
            mode = runCatching { PowerMode.valueOf(prefs.getString("mode", PowerMode.NONE.name)!!) }.getOrDefault(PowerMode.NONE),
            provider = provider,
            model = prefs.getString("model", null) ?: provider.defaultModel,
            helperModel = prefs.getString("helperModel", null) ?: provider.helperModel,
            hasKey = !secrets.getString("key_${provider.name}", null).isNullOrBlank(),
            userName = prefs.getString("userName", "") ?: "",
            agentName = prefs.getString("agentName", null)?.takeIf { it.isNotBlank() } ?: "Your agent",
            speakReplies = prefs.getBoolean("speakReplies", false),
            composioEnabled = !secrets.getString("composio", null).isNullOrBlank(),
            onboarded = prefs.getBoolean("onboarded", false),
            baseUrl = prefs.getString("baseUrl", null) ?: provider.baseUrl,
            allowHttp = prefs.getBoolean("allowHttp", false),
            accent = prefs.getString("accent", "iris") ?: "iris",
            mascot = prefs.getString("mascot", "scout") ?: "scout",
            subModel = prefs.getString("subModel_" + (prefs.getString("subKind", "CLAUDE") ?: "CLAUDE"), null) ?: "",
            subHelperModel = prefs.getString("subHelperModel_" + (prefs.getString("subKind", "CLAUDE") ?: "CLAUDE"), null) ?: "",
            ttsProvider = prefs.getString("ttsProvider", "system") ?: "system",
            ttsVoice = prefs.getString("ttsVoice", "") ?: "",
            liveEnabled = prefs.getBoolean("liveEnabled", false),
            autoPlayEarphones = prefs.getBoolean("autoPlayEarphones", true),
            notifApps = prefs.getStringSet("notifApps", emptySet()) ?: emptySet(),
            browserProfiles = (prefs.getString("browserProfiles", null) ?: "Personal").split("\n").filter { it.isNotBlank() }.ifEmpty { listOf("Personal") },
            subKind = runCatching { SubKind.valueOf(prefs.getString("subKind", "CLAUDE")!!) }.getOrDefault(SubKind.CLAUDE),
            helperRoster = HelperModel.listFrom(prefs.getString(rosterKey(), null)),
        )
    }

    /** One roster per API provider and per subscription, so switching back keeps what the user set up. */
    private fun rosterKey(): String = if (prefs.getString("mode", null) == PowerMode.SUBSCRIPTION.name) "roster_sub_" + (prefs.getString("subKind", "CLAUDE") ?: "CLAUDE")
        else "roster_api_" + (prefs.getString("provider", Provider.ANTHROPIC.name) ?: Provider.ANTHROPIC.name)

    fun setHelperRoster(list: List<HelperModel>) = edit {
        putString(rosterKey(), org.json.JSONArray(list.map { it.toJson() }).toString())
        // The first one is also the default helper model (older paths read this).
        // Cleared: helpers run on the agent's own model.
        val first = list.firstOrNull()?.id ?: if (prefs.getString("mode", null) == PowerMode.SUBSCRIPTION.name) "" else _state.value.model
        if (prefs.getString("mode", null) == PowerMode.SUBSCRIPTION.name) putString("subHelperModel_" + (prefs.getString("subKind", "CLAUDE") ?: "CLAUDE"), first) else putString("helperModel", first)
    }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) { prefs.edit().apply(block).apply(); _state.value = read() }

    fun setMode(mode: PowerMode) = edit { putString("mode", mode.name) }
    fun setProvider(p: Provider) = edit { putString("provider", p.name); remove("model"); remove("helperModel"); remove("baseUrl"); remove("allowHttp") }
    fun setBaseUrl(url: String) = edit { putString("baseUrl", url.trim().trimEnd('/')) }
    fun setAllowHttp(on: Boolean) = edit { putBoolean("allowHttp", on) }
    fun setNotifApp(pkg: String, on: Boolean) = edit { putStringSet("notifApps", (_state.value.notifApps.toMutableSet().apply { if (on) add(pkg) else remove(pkg) })) }
    fun addBrowserProfile(name: String) = edit { putString("browserProfiles", (_state.value.browserProfiles + name.trim()).distinct().joinToString("\n")) }
    fun removeBrowserProfile(name: String) = edit { putString("browserProfiles", _state.value.browserProfiles.filter { it != name }.ifEmpty { listOf("Personal") }.joinToString("\n")) }
    fun setSubModel(m: String) = edit { putString("subModel_" + _state.value.subKind.name, m) }
    fun setSubHelperModel(m: String) = edit { putString("subHelperModel_" + _state.value.subKind.name, m) }
    fun setTts(provider: String, voice: String) = edit { putString("ttsProvider", provider); putString("ttsVoice", voice) }
    fun setAutoPlayEarphones(on: Boolean) = edit { putBoolean("autoPlayEarphones", on) }
    fun setLive(on: Boolean) = edit { putBoolean("liveEnabled", on) }
    /** A voice key: its own, or the user's main API key when it's the same provider. */
    fun voiceKey(provider: String): String? = secrets.getString("voice_$provider", null)?.takeIf { it.isNotBlank() } ?: when (provider) {
        "gemini" -> apiKey(Provider.GEMINI); "openai" -> apiKey(Provider.OPENAI); else -> null
    }
    fun setVoiceKey(provider: String, key: String?) { secrets.edit().apply { if (key.isNullOrBlank()) remove("voice_$provider") else putString("voice_$provider", key.trim()) }.apply(); _state.value = read() }
    fun setSubKind(k: SubKind) = edit { putString("subKind", k.name) }
    fun setAccent(a: String) = edit { putString("accent", a) }
    fun setMascot(m: String) = edit { putString("mascot", m) }
    fun setModel(model: String) = edit { putString("model", model.trim()) }
    fun setHelperModel(model: String) = edit { putString("helperModel", model.trim()) }
    fun setUserName(name: String) = edit { putString("userName", name.trim()) }
    fun setAgentName(name: String) = edit { putString("agentName", name.trim()) }
    fun setSpeakReplies(on: Boolean) = edit { putBoolean("speakReplies", on) }

    fun apiKey(p: Provider = _state.value.provider): String? = secrets.getString("key_${p.name}", null)
    fun setApiKey(p: Provider, key: String) { secrets.edit().putString("key_${p.name}", key.trim()).apply(); _state.value = read() }
    fun clearApiKey(p: Provider) { secrets.edit().remove("key_${p.name}").apply(); _state.value = read() }

    fun composioKey(): String? = secrets.getString("composio", null)
    fun setComposioKey(key: String?) {
        // Another key is another account: the apps learned for the old one aren't its apps.
        if (key?.trim() != composioKey()) prefs.edit().remove("composio_apps").remove("composio_app_tools").apply()
        secrets.edit().apply { if (key.isNullOrBlank()) remove("composio") else putString("composio", key.trim()) }.apply(); _state.value = read()
    }

    /** Apps this Composio account has connected that aren't in the popular list (slug -> description), learned as they turn up. */
    fun composioApps(): Map<String, String> = runCatching {
        org.json.JSONObject(prefs.getString("composio_apps", null) ?: "{}").let { o -> o.keys().asSequence().associateWith { o.optString(it) } }
    }.getOrDefault(emptyMap())
    /** The tools seen for the account's own apps (slug -> tool slugs, at most 15 each): they say what each app is for. */
    fun composioAppTools(): Map<String, List<String>> = runCatching {
        org.json.JSONObject(prefs.getString("composio_app_tools", null) ?: "{}").let { o ->
            o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } } }
        }
    }.getOrDefault(emptyMap())
    @Synchronized fun rememberComposioAppTools(tools: Map<String, List<String>>) {
        val all = composioAppTools().toMutableMap()
        tools.forEach { (slug, t) -> all[slug] = (all[slug].orEmpty() + t).distinct().take(15) }
        prefs.edit().putString("composio_app_tools", org.json.JSONObject(all.mapValues { org.json.JSONArray(it.value) } as Map<*, *>).toString()).apply()
    }

    @Synchronized fun rememberComposioApps(apps: Map<String, String>) {
        val all = composioApps().toMutableMap()
        apps.forEach { (slug, about) -> if (about.isNotBlank() || slug !in all) all[slug] = about }
        prefs.edit().putString("composio_apps", org.json.JSONObject(all as Map<*, *>).toString()).apply()
    }

    /** Stable per-install id: our Composio user id, so connections survive restarts. */
    fun installId(): String = prefs.getString("install_id", null) ?: ("phone-" + java.util.UUID.randomUUID().toString().take(12)).also {
        prefs.edit().putString("install_id", it).apply()
    }

    fun onboarded(): Boolean = prefs.getBoolean("onboarded", false)
    fun setOnboarded(v: Boolean) = edit { putBoolean("onboarded", v) }

    /** A per-install secret the localhost APIs require, so other apps on the phone cannot drive us. */
    fun localToken(): String = secrets.getString("local_token", null) ?: java.util.UUID.randomUUID().toString().also {
        secrets.edit().putString("local_token", it).apply()
    }

    /** Encrypted storage for other secrets (machine passwords and keys). */
    fun secret(key: String): String? = secrets.getString("s_$key", null)
    fun setSecret(key: String, value: String?) { secrets.edit().apply { if (value.isNullOrEmpty()) remove("s_$key") else putString("s_$key", value) }.apply() }

    fun extra(key: String): String? = prefs.getString("x_$key", null)
    fun setExtra(key: String, value: String?) { prefs.edit().apply { if (value == null) remove("x_$key") else putString("x_$key", value) }.apply() }

    fun resetAll() { prefs.edit().clear().apply(); secrets.edit().clear().apply(); _state.value = read() }
}
