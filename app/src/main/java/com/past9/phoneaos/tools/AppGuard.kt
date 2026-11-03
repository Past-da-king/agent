package com.past9.phoneaos.tools

import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.data.AccountRef
import com.past9.phoneaos.data.AccountRules
import com.past9.phoneaos.data.AgentProfile
import com.past9.phoneaos.data.Rule

/**
 * The one gate every connected-app action goes through, enforced in code rather than asked of the model:
 * - which account it runs in (a helper handed a profile may only use that profile's accounts, and with one
 *   account of that app in the profile it is picked for it);
 * - what that account allows: reading and changing things are each Allowed, Ask me or Never, per account.
 */
class AppGuard(
    private val rulesFor: (String?) -> AccountRules,
    /** The user's accounts for these candidate app slugs (e.g. "microsoft", "microsoft_teams"). */
    private val accountsFor: suspend (List<String>) -> List<AccountRef>,
    private val profileOf: (ToolContext) -> AgentProfile?,
) {
    /** refuse: why it may not run (say this to the agent). account: the account picked for it, to pass on the call. */
    data class Verdict(val refuse: String? = null, val account: String? = null)

    suspend fun check(ctx: ToolContext, actions: List<String>, account: String?, detail: String): Verdict {
        if (actions.isEmpty()) return Verdict(account = account)
        val profile = profileOf(ctx)
        val asks = mutableListOf<String>()
        /** The account each action resolved to, when the profile picked it. */
        val resolved = mutableSetOf<String>()
        for (action in actions.map { it.uppercase() }) {
            val cands = candidates(action)
            val all = runCatching { accountsFor(cands) }.getOrElse {
                if (profile != null) return Verdict("Couldn't check which accounts this profile may use (${it.message}). Try again in a moment.")
                emptyList()
            }
            // The app is the longest slug the user actually has accounts for ("microsoft_teams" over "microsoft").
            val slug = cands.sortedByDescending { it.length }.firstOrNull { c -> all.any { it.slug.equals(c, true) } } ?: cands.first()
            val mine = all.filter { it.slug.equals(slug, true) }
            val app = AppCatalog.name(slug)
            val picked: AccountRef? = account?.let { w -> mine.firstOrNull { it.id == w } ?: mine.firstOrNull { it.label.equals(w, true) } }
            val use: List<AccountRef> = when {
                profile != null -> {
                    val allowed = mine.filter { profile.has(it.id) }
                    when {
                        picked != null && !profile.has(picked.id) -> return Verdict("${picked.label} isn't in your profile (${profile.name}). " +
                            if (allowed.isEmpty()) "This profile has no $app account. Tell the main agent; don't use another account." else "Use ${allowed.joinToString(" or ") { "${it.label} (account ${it.id})" }}.")
                        picked != null -> listOf(picked)
                        account != null && mine.isNotEmpty() -> return Verdict("Unknown $app account \"$account\". In your profile (${profile.name}): ${allowed.joinToString { "${it.label} (account ${it.id})" }.ifBlank { "none" }}.")
                        allowed.isEmpty() -> return Verdict("Your profile (${profile.name}) has no $app account, so you can't use $app. Tell the main agent what you needed it for.")
                        allowed.size > 1 -> return Verdict("Your profile (${profile.name}) has ${allowed.size} $app accounts: ${allowed.joinToString { "${it.label} (account ${it.id})" }}. Pass `account` to pick one.")
                        else -> allowed.also { resolved += it.single().id }
                    }
                }
                picked != null -> listOf(picked)
                // No account named: Composio uses the app's default one, so hold it to the strictest of that app's accounts.
                else -> mine
            }
            val write = isWrite(action, slug)
            val rules = use.map { rulesFor(it.id) }.ifEmpty { listOf(rulesFor(null)) }
            val rule = rules.map { if (write) it.change else it.read }.maxBy { it.ordinal }
            val where = use.singleOrNull()?.label?.takeIf { it.isNotBlank() && !it.equals(slug, true) }
            val what = action.lowercase().replace('_', ' ')
            when (rule) {
                Rule.NEVER -> return Verdict("Not allowed: the user set ${where ?: app} so the agent never ${if (write) "changes or sends anything" else "reads anything"} there. Don't try another way; tell them, and they can change it in Connections.")
                Rule.ASK -> asks += what + (where?.let { " · $it" } ?: "")
                Rule.ALLOW -> {}
            }
        }
        if (account == null && resolved.size > 1) return Verdict("These actions belong to different accounts in your profile. Run them one app at a time.")
        if (asks.isNotEmpty()) {
            val a = ctx.ask("APPROVAL|${asks.joinToString(", ")}|$detail", listOf("Approve", "Decline"))
            if (a != "Approve") return Verdict(if (a == null) "Needs the user's approval and nobody is around. Not done." else "The user declined. Not done.")
        }
        return Verdict(account = account ?: resolved.singleOrNull())
    }

    companion object {
        /** Before profiles and rules existed: reading runs, changing asks. Used where no store is wired (tests). */
        val DEFAULT = AppGuard({ AccountRules.DEFAULT }, { emptyList() }, { null })

        private val writeWords = Regex("(SEND|REPLY|FORWARD|CREATE|DELETE|REMOVE|TRASH|UPDATE|PATCH|POST|PUBLISH|PAY|PURCHASE|ARCHIVE|MOVE|INVITE|SHARE|INSERT|UPLOAD|ACCEPT|DECLINE|CANCEL)")
        private val writeTokens = setOf("ADD", "SET", "EDIT", "MODIFY", "WRITE", "MARK", "APPEND", "REPLACE", "RENAME", "COPY", "STAR", "UNSTAR", "LABEL", "APPROVE", "REJECT", "SUBMIT", "BOOK", "ORDER", "SCHEDULE")

        /** GMAIL_SEND_EMAIL -> send. Whatever follows the app's own prefix decides. */
        fun isWrite(action: String, slug: String): Boolean {
            val rest = action.uppercase().removePrefix(slug.uppercase() + "_").ifBlank { action.uppercase().substringAfter('_') }
            return writeWords.containsMatchIn(rest) || rest.split('_').any { it in writeTokens }
        }

        /** "MICROSOFT_TEAMS_SEND_MESSAGE" -> ["microsoft", "microsoft_teams"]: the app slug is one of these. */
        fun candidates(action: String): List<String> {
            val parts = action.lowercase().split('_').filter { it.isNotBlank() }
            return listOfNotNull(parts.firstOrNull(), parts.take(2).takeIf { it.size == 2 }?.joinToString("_")).distinct()
        }
    }
}
