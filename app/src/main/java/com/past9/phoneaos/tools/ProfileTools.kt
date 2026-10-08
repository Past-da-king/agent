package com.past9.phoneaos.tools

import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.agent.strList
import com.past9.phoneaos.data.AccountRef
import com.past9.phoneaos.data.AgentProfile
import com.past9.phoneaos.data.ProfileStore
import org.json.JSONObject

/**
 * The main agent makes a profile when none fits a job (say "Job hunt": Gmail and LinkedIn), so every helper still
 * works inside one. It names apps, optionally narrowed to one account ("gmail:me@x.com"); it can't invent accounts.
 */
class ProfileCreateTool(private val store: ProfileStore, private val accountsFor: suspend (List<String>) -> List<AccountRef>) : Tool {
    override val spec = ToolSpec("profile_create", "Make a new profile when none of the user's fits a job: a name and the connected apps its helpers may use. " +
        "It shows in the user's Connections in its own colour. Then delegate with that profile.",
        schema(listOf("name"), "name" to str("Short name for this part of their life, e.g. 'Job hunt'"),
            "apps" to strList("Connected apps it may use, by slug, e.g. 'gmail' or 'gmail:me@example.com' for one account. Leave out for none."),
            "color" to str("Optional colour: ${AgentProfile.COLORS.joinToString()}")))

    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val name = input.optString("name").trim().take(40)
        if (name.isBlank()) return "Give the profile a name."
        store.find(name)?.let { return "There is already a profile called ${it.name}. Use it." }
        val wanted = input.optJSONArray("apps")?.let { a -> (0 until a.length()).map { a.getString(it).trim() } }.orEmpty().filter { it.isNotBlank() }
        val picked = mutableListOf<AccountRef>(); val missing = mutableListOf<String>()
        for (w in wanted) {
            val slug = w.substringBefore(':').lowercase(); val who = w.substringAfter(':', "")
            val found = runCatching { accountsFor(listOf(slug)) }.getOrDefault(emptyList()).filter { who.isBlank() || it.label.equals(who, true) }
            if (found.isEmpty()) missing += w else picked += found
        }
        val p = store.create(name, picked, input.optString("color").takeIf { it.isNotBlank() })
        return "Made the profile ${p.name} with ${if (p.accounts.isEmpty()) "no accounts" else p.accounts.joinToString { AppCatalog.name(it.slug) + " " + it.label }}." +
            (if (missing.isNotEmpty()) " Not connected, so left out: ${missing.joinToString()}." else "") + " Delegate with profiles: [\"${p.name}\"]."
    }
}
