package com.past9.phoneaos.agent

import org.json.JSONObject
import java.io.File

/**
 * Helpers never reach the user directly. Anything a helper asks of the user (a question, an approval, a
 * browser handoff, connecting an app, a phone permission) goes to the main agent first, which:
 *  - forwards it when it truly needs the person (with a one-line reason the user sees),
 *  - answers it itself when it already knows (only plain questions; never a yes on the user's behalf),
 *  - or pushes it back with concrete things to try, at least twice before a "just stuck" request may go on.
 * This object holds the rules; AgentRuntime owns the waiting and the wiring.
 */
object EscalationGate {
    /** What kind of thing a helper wants from the user. Locked kinds are consents only the user can give. */
    enum class Kind(val label: String, val locked: Boolean) {
        QUESTION("question", false),
        HANDOFF("browser handoff", false),
        APPROVAL("approval", true),
        CONNECT("connect an app", true),
        PERMISSION("phone permission", true),
    }

    /** The main agent's decision on one request. */
    sealed interface Triage {
        data class Forward(val why: String, val needs: String) : Triage
        data class Answer(val text: String) : Triage
        data class PushBack(val message: String) : Triage
    }

    /**
     * Reasons that put the user in the loop at once. Anything else ("other") has to have been pushed back
     * [MIN_PUSHES] times first, so a helper that's merely stuck can't get to the user by asking twice.
     */
    val HUMAN_NEEDS = listOf("captcha", "passkey_or_2fa", "phone_number", "payment", "in_their_name", "their_sign_in", "their_choice")
    const val MIN_PUSHES = 2

    /** The option sets the phone's own permission prompts use (location, notifications, photos, files, card app access). */
    private val PERMISSION_OPTIONS = setOf(listOf("Allow this time", "No"), listOf("Allow", "No"), listOf("Open settings", "Not now"), listOf("Choose files", "Not now"), listOf("Allow", "Not now"))

    fun kind(question: String, options: List<String>): Kind = when {
        question.startsWith("APPROVAL|") -> Kind.APPROVAL
        com.past9.phoneaos.tools.ConnectRequest.parse(question) != null -> Kind.CONNECT
        options == listOf("Open browser", "Done", "Skip") -> Kind.HANDOFF
        options in PERMISSION_OPTIONS -> Kind.PERMISSION
        else -> Kind.QUESTION
    }

    /** The request in words a person reads ("APPROVAL|Send email|..." -> "Send email: ..."). */
    fun plain(question: String): String = when {
        question.startsWith("APPROVAL|") -> question.split("|", limit = 3).let { p -> p.getOrElse(1) { "" } + (p.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "") }
        else -> com.past9.phoneaos.tools.ConnectRequest.parse(question)?.let { "Connect ${it.name}" + (it.reason.takeIf { r -> r.isNotBlank() }?.let { r -> " ($r)" } ?: "") } ?: question
    }

    /** Null when the decision stands; otherwise why it was refused, for the main agent to act on. */
    fun check(kind: Kind, decision: String, message: String, needs: String, pushes: Int): String? = when (decision) {
        "forward" -> when {
            message.isBlank() -> "Forwarding needs a message: one line telling the user why they're needed."
            !kind.locked && needs !in HUMAN_NEEDS && pushes < MIN_PUSHES ->
                "Not forwarded. Nothing here says it needs the user (needs: ${needs.ifBlank { "none given" }}), and the helper has only been pushed back $pushes time${if (pushes == 1) "" else "s"}. " +
                    "push_back with concrete things to try first (at least $MIN_PUSHES times), or forward with needs set to one of: ${HUMAN_NEEDS.joinToString()}."
            else -> null
        }
        "answer" -> when {
            kind.locked -> "You can't answer this for the user (it's a request for their ${kind.label}): only they can say yes. forward it with the reason, or push_back if it shouldn't be asked at all."
            kind == Kind.HANDOFF -> "You can't take a browser handoff yourself. forward it, or push_back with what the helper should try on the page."
            message.isBlank() -> "Answering needs the answer in message."
            else -> null
        }
        "push_back" -> if (message.isBlank()) "Pushing back needs concrete things for the helper to try in message." else null
        else -> "decision must be forward, answer or push_back."
    }

    /** What the main agent reads when a helper asks for the user. The helper waits until it decides. */
    fun note(helperId: Long, label: String, kind: Kind, question: String, options: List<String>, pushes: Int): String = buildString {
        appendLine("[Helper #$helperId \"$label\" wants the user: ${kind.label}]")
        appendLine("It asks: ${plain(question).take(1_500)}")
        if (options.isNotEmpty() && kind != Kind.APPROVAL) appendLine("Options: ${options.joinToString(" / ")}")
        appendLine("Pushed back so far: $pushes time${if (pushes == 1) "" else "s"}.")
        appendLine("It is waiting. Decide now with escalation_triage (helper $helperId); the user sees nothing unless you forward it.")
        appendLine("- It truly needs the user (${HUMAN_NEEDS.joinToString { it.replace('_', ' ') }}): decision forward, needs set to which, message = one short line telling the user why they're needed.")
        if (kind.locked) appendLine("- Only the user can say yes to this (${kind.label}), so forward it, or push_back if it shouldn't be asked at all (it then won't happen).")
        else if (kind == Kind.QUESTION) appendLine("- You already know the answer (memory, the brief, the conversation): decision answer, message = the answer. Search memory first if unsure.")
        appendLine("- It's asking for the sake of asking or giving up too early: decision push_back, message = concrete next things to try" +
            (if (kind == Kind.HANDOFF) " on the page (click each option, type into the focused field, use arrow keys or Enter, browser_look to see it, another route to the same result)" else "") +
            ". Push back at least $MIN_PUSHES times before you forward anything that isn't on the list above.")
        append("Then reply HOLD unless the user needs to hear something from you.")
    }

    /** The tool result a helper gets in place of the user's answer when the main agent sent it back. */
    fun pushedBack(agent: String, messages: List<String>): String =
        "NOT PASSED TO THE USER. $agent read your request and sent it back:\n" + messages.joinToString("\n") { "- $it" } +
            "\nDo that now. Ask again only if it truly needs the person: a captcha, a passkey or code on their phone, their phone number, a payment, or something sent in their name."
}

/** Every triage decision, one JSON line each, so the gate can be tuned later (what got forwarded, what didn't need to be). */
class EscalationLog(private val file: File) {
    @Synchronized fun add(entry: JSONObject) {
        runCatching { file.parentFile?.mkdirs(); file.appendText(entry.put("at", entry.optLong("at").takeIf { it > 0 } ?: System.currentTimeMillis()).toString() + "\n") }
        // Keep it bounded: the newest 2000 decisions.
        runCatching { if (file.length() > 1_500_000) file.writeText(file.readLines().takeLast(2_000).joinToString("\n", postfix = "\n")) }
    }

    fun recent(n: Int = 50): List<JSONObject> = runCatching { file.readLines().takeLast(n).mapNotNull { runCatching { JSONObject(it) }.getOrNull() } }.getOrDefault(emptyList())

    /** "forwarded 3, self-answered 5, pushed back 9" over the last [n] decisions. */
    fun tally(n: Int = 200): Map<String, Int> = recent(n).groupingBy { it.optString("decision") }.eachCount()

    companion object {
        fun entry(helperId: Long, label: String, kind: EscalationGate.Kind, question: String, decision: String, message: String, needs: String = "", pushes: Int = 0): JSONObject =
            JSONObject().put("helper", helperId).put("label", label).put("kind", kind.name.lowercase()).put("question", EscalationGate.plain(question).take(600))
                .put("decision", decision).put("message", message.take(600)).put("needs", needs).put("pushesBefore", pushes)
    }
}
