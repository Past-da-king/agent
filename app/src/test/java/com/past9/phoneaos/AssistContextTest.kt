package com.past9.phoneaos

import com.past9.phoneaos.assist.AskChoice
import com.past9.phoneaos.assist.AskRefs
import com.past9.phoneaos.assist.AssistContext
import com.past9.phoneaos.assist.AssistContextBuilder
import com.past9.phoneaos.assist.AssistCore
import com.past9.phoneaos.assist.AssistPhase
import com.past9.phoneaos.assist.AssistRouter
import com.past9.phoneaos.assist.CtxAgent
import com.past9.phoneaos.assist.CtxAsk
import com.past9.phoneaos.assist.CtxNote
import com.past9.phoneaos.assist.LiveAssistCore
import com.past9.phoneaos.assist.NotifyLog
import com.past9.phoneaos.assist.Routed
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.remote.LiveCards
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The assistant's starting context and the routing of what he says: built from the chat rows and cards the phone already
 * holds, in both modes, capped, and sent to whoever waits for it.
 */
class AssistContextTest {
    private val now = 1_800_000_000_000L
    private fun row(id: Long, kind: String, text: String, meta: JSONObject = JSONObject()) = ChatItem(id = id, kind = kind, text = text, meta = meta.toString(), createdAt = now - 1000 + id)
    private fun question(id: Long, text: String, by: String = "", options: List<String> = emptyList()) =
        row(id, "question", text, JSONObject().put("whyBy", by).put("options", JSONArray(options)))
    private fun approval(id: Long, what: String, by: String = "") = row(id, "approval", what, JSONObject().put("summary", what).put("by", by).put("remoteKind", "approval"))
    private fun helper(id: Long, label: String, doing: String) =
        row(id, "helper", label, JSONObject().put("label", label).put("state", "working").put("now", doing).put("startedAt", now - 60_000).put("beat", now))
    private fun card(id: String, label: String, state: String, line: String = "", waiting: Boolean = false, project: String = "") = LiveCards.card(JSONObject()
        .put("id", id).put("label", label).put("state", state).put("line", line).put("waiting", waiting).put("startedAt", now - 60_000).put("projectChat", project)
        .put("owner", JSONObject().put("kind", if (project.isEmpty()) "plain" else "project").put("name", if (project.isEmpty()) "" else "Payroll")))!!
    private fun build(rows: List<ChatItem>, cards: List<com.past9.phoneaos.remote.LiveCard>? = null, notes: List<CtxNote> = emptyList(), working: Boolean = false, refs: AskRefs = AskRefs()) =
        AssistContextBuilder.build(rows, cards, notes, working, "Looking at the invoice", now, refs, "Nova")

    private val pairing = question(30, "What is the pairing code shown on the watch?", by = "Watch helper")

    // ---- context, local mode --------------------------------------------------------------------

    @Test fun onThePhonesOwnAgentTheContextHasTheChatTailTheHelpersTheAsksAndTheNotes() {
        val rows = listOf(row(1, "user", "Pair my watch"), row(2, "agent", "Starting the pairing."), helper(3, "Watch helper", "Waiting for the pairing code"), pairing)
        val c = build(rows, notes = listOf(CtxNote("Reminder", "Pay the invoice", now - 60_000)), working = true)
        assertEquals(listOf("user" to "Pair my watch", "agent" to "Starting the pairing."), c.turns.map { it.who to it.text })
        assertEquals(listOf("Nova", "Watch helper"), c.agents.map { it.name })
        assertEquals(CtxAgent.WORKING, c.agents[1].state)
        assertEquals("Waiting for the pairing code", c.agents[1].doing)
        assertEquals(1, c.asks.size)
        assertEquals("Watch helper", c.asks[0].by)
        assertEquals("A1", c.asks[0].ref)
        assertEquals("Reminder", c.notes[0].title)
        val p = c.prompt()
        assertTrue(p, p.contains("[A1] Question, from Watch helper: What is the pairing code shown on the watch?"))
        assertTrue(p.contains("Watch helper (helper), working: Waiting for the pairing code"))
        assertTrue(p.contains("User: Pair my watch"))
    }

    @Test fun aHelperThatIsNotRunningAndAnOldNotificationAreLeftOut() {
        val dead = row(4, "helper", "Old", JSONObject().put("label", "Old").put("state", "working").put("startedAt", now - 9L * 86_400_000).put("beat", now - 9L * 86_400_000)).copy(createdAt = now - 9L * 86_400_000)
        val c = build(listOf(dead), notes = listOf(CtxNote("Old news", "x", now - 7 * 3_600_000L)))
        assertTrue(c.agents.isEmpty()); assertTrue(c.notes.isEmpty()); assertTrue(c.isEmpty)
        assertTrue(c.prompt(), c.prompt().contains("Agents: none are working right now."))
        assertTrue(LiveAssistCore.contextBlock(c, linked = true).contains("none are working right now"))
    }

    @Test fun anAnsweredCardIsNotWaitingAndOnlyTheMainChatMakesTheTail() {
        val answered = question(5, "Which colour?", options = listOf("Red", "Blue")).let { it.copy(meta = JSONObject(it.meta).put("answer", "Red").toString()) }
        val other = row(6, "user", "in a project chat", JSONObject().put("chat", "p1"))
        val c = build(listOf(answered, other, row(7, "agent", "Done.")))
        assertTrue(c.asks.isEmpty())
        assertEquals(listOf("Done."), c.turns.map { it.text })
    }

    // ---- context, remote mode -------------------------------------------------------------------

    @Test fun linkedToAComputerTheCardsGiveWhoIsAtWorkAndWhoWaits() {
        val cards = listOf(card("h1", "Watch helper", "working", "Waiting for the code", waiting = true), card("h2", "Report", "working", "Writing the summary"), card("pc", "Payroll chat", "working", "Checking hours", project = "chat-1"))
        val c = build(listOf(row(1, "user", "Pair my watch"), pairing), cards)
        assertEquals(listOf("Watch helper", "Report", "project Payroll"), c.agents.map { it.name })
        assertEquals(CtxAgent.WAITING, c.agents[0].state)
        assertEquals("What is the pairing code shown on the watch?", c.agents[0].waitingOn)
        assertEquals("project", c.agents[2].kind)
        assertEquals(2, c.agents.count { it.state == CtxAgent.WORKING })
    }

    @Test fun aCardThatFinishedLongAgoIsLeftOutAndOneThatJustFinishedIsKept() {
        val old = LiveCards.card(JSONObject().put("id", "x").put("label", "Old").put("state", "done").put("endedAt", now - 3_600_000).put("done", "ok"))!!
        val fresh = LiveCards.card(JSONObject().put("id", "y").put("label", "Fresh").put("state", "done").put("endedAt", now - 60_000).put("done", "Sent the invoice"))!!
        val c = build(emptyList(), listOf(old, fresh))
        assertEquals(listOf("Fresh"), c.agents.map { it.name })
        assertEquals(CtxAgent.DONE, c.agents[0].state)
    }

    // ---- size -----------------------------------------------------------------------------------

    @Test fun theContextIsCappedAndTheAsksSurviveTheTrim() {
        val long = "word ".repeat(120)
        val rows = (1L..40L).flatMap { listOf(row(it * 2, "user", long), row(it * 2 + 1, "agent", long)) } + pairing + (50L..70L).map { helper(it, "Helper $it $long", long) }
        val notes = (1..12).map { CtxNote("T$it", long, now - it * 1000L) }
        val c = build(rows, notes = notes)
        assertTrue(c.turns.size <= AssistContext.TURNS && c.agents.size <= AssistContext.AGENTS && c.notes.size <= AssistContext.NOTES)
        val p = c.prompt()
        assertTrue("${p.length}", p.length <= AssistContext.MAX_CHARS)
        assertTrue(p.contains("pairing code"))
        assertTrue(c.prompt(cap = 600).length <= 600)
        assertTrue(c.prompt(cap = 600).contains("pairing code"))
    }

    // ---- routing --------------------------------------------------------------------------------

    private fun ask(id: Long, text: String, by: String, options: List<String> = emptyList(), approval: Boolean = false) = CtxAsk(id, "A$id", approval, text, options, by)

    @Test fun aPairingCodeGoesToTheAgentThatIsWaitingForIt() {
        val asks = listOf(ask(1, "What is the pairing code shown on the watch?", "Watch helper"), ask(2, "Which colour should the header be?", "Design helper"))
        val r = AssistRouter.fromWords("it's 482917", asks) as Routed.Answer
        assertEquals(1L, r.ask.id); assertEquals("482917", r.answer)
        assertEquals("482917", (AssistRouter.fromWords("the code is four eight two nine one seven", asks) as Routed.Answer).answer)
        assertEquals("482917", (AssistRouter.fromWords("48 29 17", asks) as Routed.Answer).answer)
        assertEquals("a8f3k2", (AssistRouter.fromWords("a8f3k2", asks) as Routed.Answer).answer)
    }

    @Test fun twoAgentsAskingForACodeIsAChoiceNotAGuess() {
        val asks = listOf(ask(1, "Enter the pairing code", "Watch helper"), ask(2, "What is the verification code from the bank email?", "Mail helper"))
        val r = AssistRouter.fromWords("482917", asks) as Routed.Which
        assertEquals("482917", r.answer)
        assertEquals(listOf(1L, 2L), r.candidates.map { it.id })
    }

    @Test fun anOptionSaidInFewWordsAnswersTheQuestionThatOffersIt() {
        val asks = listOf(ask(1, "Which colour?", "Design helper", listOf("Red", "Blue")), ask(2, "Pairing code?", "Watch helper"))
        val r = AssistRouter.fromWords("blue please", asks) as Routed.Answer
        assertEquals(1L, r.ask.id); assertEquals("Blue", r.answer)
        // The same option offered by two questions: he picks.
        val twice = listOf(ask(1, "Send it now?", "A", listOf("Yes", "No")), ask(2, "Delete it?", "B", listOf("Yes", "No")))
        assertTrue(AssistRouter.fromWords("yes", twice) is Routed.Which)
    }

    @Test fun saidAsTheAnswerGoesToTheFreeTextQuestionNarrowedByTheNameHeUsed() {
        val asks = listOf(ask(1, "What should the file be called?", "Report helper"), ask(2, "What is the project name?", "Setup helper"))
        val r = AssistRouter.fromWords("tell the report helper the answer is quarterly figures", asks)
        // The name he used picks the one that waits.
        assertTrue(r.toString(), r is Routed.Answer && r.ask.id == 1L)
        assertEquals("quarterly figures", (r as Routed.Answer).answer)
    }

    @Test fun nothingWaitingOrNothingThatFitsMeansTheWordsGoToTheAgentAsBefore() {
        assertEquals(Routed.None, AssistRouter.fromWords("482917", emptyList()))
        assertEquals(Routed.None, AssistRouter.fromWords("what's the weather in Durban", listOf(ask(1, "Pairing code?", "Watch helper"))))
        assertEquals(Routed.None, AssistRouter.fromWords("remind me to pay rent on the 25th", listOf(ask(1, "Pairing code?", "Watch helper"))))
        assertEquals(Routed.None, AssistRouter.fromWords("", listOf(ask(1, "Pairing code?", "Watch helper"))))
    }

    @Test fun anApprovalIsNeverAnsweredByWords() {
        val asks = listOf(ask(1, "Send the invoice to Shey", "Mail helper", approval = true))
        assertEquals(Routed.None, AssistRouter.fromWords("yes", asks))
        assertEquals(Routed.None, AssistRouter.fromWords("approve it", asks))
        val t = AssistRouter.forTool("A1", "Approve", "yes approve it", asks)
        assertTrue(t is Routed.Refused)
    }

    @Test fun theVoiceAnswersByRefAndACodeMustBeWhatHeActuallySaid() {
        val asks = listOf(ask(1, "Pairing code?", "Watch helper"), ask(2, "Which colour?", "Design helper", listOf("Red", "Blue")))
        val ok = AssistRouter.forTool("A1", "482917", "it is four eight two nine one seven", asks) as Routed.Answer
        assertEquals(1L, ok.ask.id)
        // The voice made a code up: refused.
        assertTrue(AssistRouter.forTool("A1", "123456", "I do not know it yet", asks) is Routed.Refused)
        // An option said loosely is matched to the option.
        assertEquals("Blue", (AssistRouter.forTool("A2", "the blue one", "the blue one", asks) as Routed.Answer).answer)
        // No ref and one question only: it is that one.
        assertEquals(1L, (AssistRouter.forTool("", "482917", "482917", listOf(asks[0])) as Routed.Answer).ask.id)
        // No ref, two questions, nothing separates them: he chooses.
        val two = listOf(ask(1, "Pairing code?", "Watch helper"), ask(2, "Bank verification code?", "Mail helper"))
        assertTrue(AssistRouter.forTool("", "482917", "482917", two) is Routed.Which)
        assertTrue(AssistRouter.forTool("", "x", "x", emptyList()) is Routed.Refused)
    }

    // ---- the voice's brain ----------------------------------------------------------------------

    @Test fun theVoiceStartsWithTheContextAndHowToAnswerAndKnowsNothingOfOnePerson() {
        val c = build(listOf(pairing))
        val p = LiveAssistCore.systemPrompt("Nova", "Sam", linked = true, context = c)
        assertTrue(p.contains("Waiting on the user:"))
        assertTrue(p.contains("answer_ask"))
        assertTrue(p.contains("pass its name as target in hand_to_agent"))
        // Nothing waiting: no routing instructions, but the voice is still told that none of its agents are working (never silent).
        val quiet = LiveAssistCore.systemPrompt("Nova", "Sam", linked = false, context = AssistContext())
        assertFalse(quiet.contains("answer_ask")); assertTrue(quiet.contains("none are working right now"))
        assertFalse(LiveAssistCore.systemPrompt("Nova", "Sam", linked = false, context = build(listOf(approval(9, "Send the invoice", "Mail helper")))).contains("call answer_ask"))
        val decls = LiveAssistCore.tools().getJSONObject(0).getJSONArray("functionDeclarations")
        val names = (0 until decls.length()).map { decls.getJSONObject(it).getString("name") }
        assertTrue("answer_ask" in names)
        assertTrue(decls.getJSONObject(0).getJSONObject("parameters").getJSONObject("properties").has("target"))
    }

    @Test fun aCardThatChangesWhileTheSessionIsOpenReachesTheVoiceOnceAndNeverTwice() {
        val core = LiveAssistCore { now }
        val refs = core.refs
        val first = build(listOf(pairing), refs = refs)
        core.primed(first)
        // Already in the instructions: not told again.
        assertTrue(core.contextChanged(first).isEmpty())
        // A new question arrives from another agent.
        val second = build(listOf(pairing, question(31, "Which account should I use?", by = "Mail helper", options = listOf("Work", "Home"))), refs = refs)
        val told = core.contextChanged(second)
        assertEquals(1, told.size)
        assertTrue(told[0], told[0].contains("Mail helper") && told[0].contains("[A2]") && told[0].contains("Work, Home"))
        assertTrue(core.contextChanged(second).isEmpty())
        // The first one is answered elsewhere (the chat, the computer): the voice is told not to ask again.
        val third = build(listOf(question(31, "Which account should I use?", by = "Mail helper", options = listOf("Work", "Home"))), refs = refs)
        val gone = core.contextChanged(third)
        assertEquals(1, gone.size); assertTrue(gone[0].contains("answered elsewhere"))
        // One the voice answered itself is not announced as answered elsewhere.
        core.answeredHere(31)
        assertTrue(core.contextChanged(build(emptyList(), refs = refs)).isEmpty())
    }

    @Test fun anAgentThatFinishesIsMentionedOnlyOnItsChangeOfState() {
        val core = LiveAssistCore { now }
        val working = build(emptyList(), listOf(card("h1", "Report", "working", "Writing")))
        core.primed(working)
        val done = LiveCards.card(JSONObject().put("id", "h1").put("label", "Report").put("state", "done").put("endedAt", now - 1000).put("done", "Report is ready"))!!
        val told = core.contextChanged(build(emptyList(), listOf(done)))
        assertEquals(1, told.size); assertTrue(told[0].contains("Report finished: Report is ready"))
        assertTrue(core.contextChanged(build(emptyList(), listOf(done))).isEmpty())
    }

    @Test fun aHandOffAimedAtAHelperIsRecordedButNotTrackedThroughTheMainChat() {
        val core = LiveAssistCore { now }
        core.handOff("tell the report helper to stop", 0L, track = false)
        assertFalse(core.handOffOpen)
        assertEquals(1, core.handOffs.size)
        core.handOff("do the thing", 0L)
        assertTrue(core.handOffOpen)
    }

    // ---- the sheet ------------------------------------------------------------------------------

    @Test fun anAnswerRoutedToAnAgentShowsOneLineAndNothingIsReadOut() {
        val core = AssistCore(micAvailable = true)
        core.finished("482917")
        core.routed("Sent to Watch helper: 482917")
        val v = core.view(emptyList(), working = false)
        assertEquals(AssistPhase.ANSWERING, v.phase)
        assertEquals("Sent to Watch helper: 482917", v.answer)
        assertNull(v.answerId)
        core.listen()
        assertEquals(AssistPhase.LISTENING, core.view(emptyList(), false).phase)
    }

    @Test fun theChoiceKeepsHisWordsAndTheCandidates() {
        val c = AskChoice("482917", listOf(ask(1, "Pairing code?", "Watch helper"), ask(2, "Bank code?", "Mail helper")))
        assertEquals("482917", c.answer); assertEquals(2, c.asks.size)
    }

    // ---- notifications --------------------------------------------------------------------------

    @Test fun notificationsAreKeptShortAndRoundTrip() {
        val items = listOf(CtxNote("Invoice", "Paid by Shey", now), CtxNote("Reminder", "Call mum", now - 1000))
        assertEquals(items, NotifyLog.read(NotifyLog.write(items)))
        assertTrue(NotifyLog.read("not json").isEmpty())
        assertTrue(NotifyLog.read(null).isEmpty())
    }

    @Test fun refsStayTheSameForTheSameQuestion() {
        val refs = AskRefs()
        assertEquals("A1", refs.of(900)); assertEquals("A2", refs.of(901)); assertEquals("A1", refs.of(900))
        assertNotNull(refs)
    }
}
