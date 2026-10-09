package com.past9.phoneaos.agent

import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Double texting. When the user sends a message while the agent is still working, the app forks a new agent
 * from the working one's CURRENT state (a branch), decides how the two relate, and merges the chat back into
 * one history when the branches finish. See agent-branching-design.md.
 */

/** How a message sent mid-work relates to the work already running. */
enum class Relation {
    /** "ok thanks": nothing to do, so no agent is forked (cost). */
    ACK,
    /** "wait, I meant...": replaces the running work. The old line is closed, the branch carries on with its context. */
    SUPERSEDE,
    /** "also...": adds to the same work. The branch builds on the running line instead of redoing it. */
    RELATED,
    /** A separate request: both carry on and each answers. */
    PARALLEL;

    val word get() = name.lowercase()
}

/** Decides what a mid-work message means. Injectable so a model-based classifier can replace the default. */
fun interface BranchPolicy {
    fun classify(text: String, runningText: String): Relation
}

object HeuristicPolicy : BranchPolicy {
    private val ackWords = setOf("ok", "okay", "k", "kk", "thanks", "thank", "you", "thx", "ty", "cheers", "cool", "great", "nice", "perfect", "got", "it", "sure",
        "yes", "yep", "yeah", "yup", "no", "nope", "good", "awesome", "sweet", "lovely", "fine", "alright", "right", "noted", "np", "ta", "lol", "haha", "so", "much", "a", "lot", "thanks!")
    private val supersedeStart = Regex("""^(wait|hold on|hang on|actually|no wait|no,|sorry|scrap that|forget (that|it)|never ?mind|ignore (that|the last|what)|disregard|cancel (that|it)|i meant|i mean|correction|instead|change of plan|on second thought|stop that|not that)\b""", RegexOption.IGNORE_CASE)
    private val supersedeAnywhere = Regex("""\b(i meant|i actually meant|scrap that|forget (that|it)|never ?mind|ignore (that|what i (said|asked))|disregard (that|what)|change of plan|instead of that)\b""", RegexOption.IGNORE_CASE)
    private val relatedStart = Regex("""^(also|and also|and |plus|one more thing|oh and|while you'?re at it|additionally|btw|by the way|can you also|and then|on top of that|as well)\b""", RegexOption.IGNORE_CASE)

    override fun classify(text: String, runningText: String): Relation {
        val t = text.trim()
        val words = t.lowercase().split(Regex("[\\s,.!?;:]+")).filter { it.isNotEmpty() }
        val noLetters = t.none { it.isLetterOrDigit() }
        if (t.isEmpty() || (noLetters && t.length <= 8) || (words.size in 1..4 && words.all { it in ackWords })) return Relation.ACK
        if (supersedeStart.containsMatchIn(t) || supersedeAnywhere.containsMatchIn(t)) return Relation.SUPERSEDE
        if (relatedStart.containsMatchIn(t)) return Relation.RELATED
        return Relation.PARALLEL
    }
}

/** One line of work in the chat: the main agent, or a branch forked from it. */
class Line(val id: String, val seq: Long) {
    val budget = AgentRuntime.StepBudget()
    /** API mode: the line's transcript so far, replaced after every step so a fork can read it without racing the loop. */
    @Volatile var snapshot: List<Msg> = emptyList()
    /** Subscription mode: the harness session this line is running in. */
    @Volatile var session: String? = null
    /** What the user last asked this line to do. */
    @Volatile var userText: String = ""
    @Volatile var job: Job? = null
    /** Another line closed this one (a SUPERSEDE): its cancellation is not "Stopped", and its work is kept. */
    @Volatile var supersededBy: Long? = null
    /** Heads-ups from the user or a sibling, read by the loop before its next step. */
    val notes = ConcurrentLinkedQueue<String>()
}

/** What a turn needs to know about who it is for: replies quote `origin`, helpers it starts attach to `branchItem`. */
class Turn(val line: Line, val branchItem: Long? = null) {
    @Volatile var origin: Long? = null
    @Volatile var late: Boolean = false
}

/** A branch while this process runs it. The persisted record is the chat_items row `item` (kind "branch"). */
class Branch(val item: Long, val line: Line, val parent: Line, val relation: Relation, val originId: Long, val userText: String) {
    val turn = Turn(line, item)
    /** Turns running on this branch: its first, plus one for each helper result handed back to it. */
    val turns = AtomicInteger(0)
    @Volatile var outcome = "done"
    @Volatile var error: String? = null
    val lock = Mutex()
    /** Subscription mode: the SUPERSEDE branch's session becomes the main conversation when it works. */
    @Volatile var adopt = false
    /** API mode: this branch's transcript (the fork of the running line plus everything it has done since). */
    @Volatile var history: MutableList<Msg>? = null
}

object Branching {
    const val MAX_BRANCHES = 3
    const val STILL_RUNNING = "(Still running in the other branch when this branch was forked off. Its result is merged back later. Don't wait for it.)"

    /** What the new branch starts from: the running line's transcript so far, with unfinished tool calls closed off. */
    fun fork(live: List<Msg>): MutableList<Msg> {
        val out = live.toMutableList()
        val last = out.lastOrNull()
        if (last != null && last.role == Role.ASSISTANT && last.toolCalls.isNotEmpty())
            out += Msg(Role.USER, last.toolCalls.map { Block.ToolResult(it.id, STILL_RUNNING, true) })
        return AgentRuntime.Repair.repair(out)
    }

    /** The first message of a branch: who it is, how it relates, and exactly what to answer. */
    fun framed(text: String, rel: Relation, parentText: String): String = buildString {
        append("[You were forked off the line of conversation that is still working on: \"${parentText.take(200).ifBlank { "an earlier request" }}\". You have everything it has seen and done so far. ")
        append(when (rel) {
            Relation.SUPERSEDE -> "The user's new message REPLACES or corrects that request: the other line has been closed. Carry on from what it already found and do what the user now wants.]"
            Relation.RELATED -> "The new message ADDS to that same work, which is still running in the other line. Build on what it has found, don't redo it, and answer only the new part.]"
            else -> "The new message is a SEPARATE request. The other line carries on with its own and answers it itself. Answer only the message below and don't repeat its work.]"
        })
        append("\n\n").append(text)
    }

    /** For engines that cannot fork a session: a branch starts fresh, so hand it what the running line has said. */
    fun handoff(parentText: String, recent: List<String>): String =
        "[Context handed over, because this is a fresh session: the line of conversation you were forked from is working on \"${parentText.take(200)}\". Recent chat:\n" +
            recent.takeLast(14).joinToString("\n") { "- $it" } + "]\n\n"

    private fun clip(s: String, n: Int) = s.replace(Regex("\\s+"), " ").trim().let { if (it.length > n) it.take(n) + "..." else it }

    /** Steps and spoken lines of a branch's own messages (everything after the fork point). */
    fun digest(delta: List<Msg>): Pair<List<String>, List<String>> {
        val steps = mutableListOf<String>(); val said = mutableListOf<String>()
        delta.forEach { m ->
            m.blocks.forEach { b ->
                when (b) {
                    is Block.Text -> if (m.role == Role.ASSISTANT && b.text.isNotBlank()) said += b.text.trim()
                    is Block.ToolCall -> steps += "${b.name}(${clip(b.input.toString(), 160)})"
                    is Block.ToolResult -> steps += "  -> ${if (b.isError) "ERROR " else ""}${clip(b.content, 400)}"
                    else -> {}
                }
            }
        }
        return steps to said
    }

    /** The block a finished branch leaves in the main line, labelled so the next turn sees what happened and nothing is redone. */
    fun mergeNote(n: Int, relation: Relation, state: String, userText: String, steps: List<String>, said: List<String>, error: String?): String = buildString {
        appendLine("[Branch merge #$n: the user sent a message while you were working; a branch of you handled it. ${relation.word}, $state. Its work is below and is DONE: don't redo it.]")
        appendLine("user: ${clip(userText, 600)}")
        if (steps.isNotEmpty()) { appendLine("its steps:"); steps.take(40).forEach { appendLine("  $it") }; if (steps.size > 40) appendLine("  ...(${steps.size - 40} more)") }
        said.forEach { appendLine("you said: ${clip(it, 1200)}") }
        if (error != null) appendLine("it ${if (state == "failed") "failed" else "was cut short"}: ${clip(error, 300)}")
        else if (said.isEmpty() && state != "superseded") appendLine("(it said nothing to the user)")
    }.trim()
}
