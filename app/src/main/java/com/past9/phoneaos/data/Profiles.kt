package com.past9.phoneaos.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One signed-in account of a connected app (work Gmail, personal Gmail...), by its Composio account id. */
data class AccountRef(val id: String, val slug: String, val label: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("slug", slug).put("label", label)
    companion object { fun from(o: JSONObject) = AccountRef(o.optString("id"), o.optString("slug"), o.optString("label")) }
}

/**
 * A part of the user's life ("Strat Edge work", "Personal"): the connected accounts that belong to it and,
 * optionally, which browser profile is signed in for it. A helper handed a profile can only use those
 * accounts. One account can sit in any number of profiles.
 */
data class AgentProfile(val id: String, val name: String, val accounts: List<AccountRef> = emptyList(), val browser: String = "") {
    fun has(accountId: String) = accounts.any { it.id == accountId }
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("browser", browser).put("accounts", JSONArray(accounts.map { it.toJson() }))
    companion object {
        fun from(o: JSONObject) = AgentProfile(o.optString("id"), o.optString("name"),
            o.optJSONArray("accounts")?.let { a -> (0 until a.length()).map { AccountRef.from(a.getJSONObject(it)) } }.orEmpty(), o.optString("browser"))
    }
}

/** What the agent may do in one account without the user. */
enum class Rule(val label: String) { ALLOW("Allowed"), ASK("Ask me"), NEVER("Never") }

/** Per account: reading (search, list, fetch) and changing things (send, post, create, delete...). */
data class AccountRules(val read: Rule = Rule.ALLOW, val change: Rule = Rule.ASK) {
    fun toJson(): JSONObject = JSONObject().put("read", read.name).put("change", change.name)
    companion object {
        val DEFAULT = AccountRules()
        fun from(o: JSONObject) = AccountRules(runCatching { Rule.valueOf(o.optString("read")) }.getOrDefault(Rule.ALLOW), runCatching { Rule.valueOf(o.optString("change")) }.getOrDefault(Rule.ASK))
    }
}

/** Profiles and per-account rules, kept on the phone in plain prefs (no secrets in here). */
class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    private val _profiles = MutableStateFlow(readProfiles())
    val profiles: StateFlow<List<AgentProfile>> = _profiles
    private val _rules = MutableStateFlow(readRules())
    val rules: StateFlow<Map<String, AccountRules>> = _rules

    private fun readProfiles(): List<AgentProfile> = runCatching {
        val a = JSONArray(prefs.getString("profiles", "[]")); (0 until a.length()).map { AgentProfile.from(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())
    private fun readRules(): Map<String, AccountRules> = runCatching {
        val o = JSONObject(prefs.getString("rules", "{}")!!); o.keys().asSequence().associateWith { AccountRules.from(o.getJSONObject(it)) }
    }.getOrDefault(emptyMap())

    private fun saveProfiles(list: List<AgentProfile>) { prefs.edit().putString("profiles", JSONArray(list.map { it.toJson() }).toString()).apply(); _profiles.value = list }

    fun find(nameOrId: String?): AgentProfile? = nameOrId?.trim()?.takeIf { it.isNotEmpty() }?.let { w ->
        _profiles.value.firstOrNull { it.id == w } ?: _profiles.value.firstOrNull { it.name.equals(w, true) }
    }

    fun create(name: String, accounts: List<AccountRef> = emptyList()): AgentProfile {
        val p = AgentProfile("p" + java.util.UUID.randomUUID().toString().take(8), name.trim(), accounts.distinctBy { it.id })
        saveProfiles(_profiles.value + p); return p
    }
    fun update(p: AgentProfile) = saveProfiles(_profiles.value.map { if (it.id == p.id) p.copy(accounts = p.accounts.distinctBy { a -> a.id }) else it })
    fun delete(id: String) = saveProfiles(_profiles.value.filter { it.id != id })
    fun setMember(profileId: String, account: AccountRef, member: Boolean) = _profiles.value.firstOrNull { it.id == profileId }?.let { p ->
        update(p.copy(accounts = if (member) p.accounts.filter { it.id != account.id } + account else p.accounts.filter { it.id != account.id }))
    }
    /** A disconnected account leaves every profile. */
    fun forgetAccount(accountId: String) {
        saveProfiles(_profiles.value.map { p -> p.copy(accounts = p.accounts.filter { it.id != accountId }) })
        val r = _rules.value - accountId; prefs.edit().putString("rules", JSONObject(r.mapValues { it.value.toJson() }).toString()).apply(); _rules.value = r
    }
    fun profilesWith(accountId: String) = _profiles.value.filter { it.has(accountId) }

    fun rules(accountId: String?): AccountRules = accountId?.let { _rules.value[it] } ?: AccountRules.DEFAULT
    fun setRules(accountId: String, r: AccountRules) {
        val all = _rules.value + (accountId to r)
        prefs.edit().putString("rules", JSONObject(all.mapValues { it.value.toJson() }).toString()).apply(); _rules.value = all
    }
}
