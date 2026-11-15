package com.past9.phoneaos.wearproto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RingTest {
    private fun wake(id: String, sev: String = "urgent", sound: Boolean = true) = RingItem(id, RingKind.WAKE, sev, "Standup", "Join the call", sound)
    private fun card(id: Long) = Pending(id, Pending.KIND_APPROVAL, "Send the invoice", "To Shey", Pending.APPROVAL_OPTIONS, "Hina")

    @Test fun ringItemsRoundTripAndOverlongTextIsCut() {
        val list = listOf(wake("w1", "critical"), RingItem.approval(card(7)))
        assertEquals(list, RingWire.parseItems(RingWire.items(list)))
        val long = RingWire.parseItems(RingWire.items(listOf(wake("w2").copy(title = "t".repeat(500)))))!!
        assertTrue(long.single().title.length <= 120)
        assertEquals("an empty list is a real answer: nothing rings", emptyList<RingItem>(), RingWire.parseItems(RingWire.items(emptyList())))
        assertNull(RingWire.parseItems("garbage".toByteArray())); assertNull(RingWire.parseItems(null))
    }

    @Test fun anApprovalRingKnowsItsCard() {
        assertEquals(7L, RingItem.approval(card(7)).cardId)
        assertNull(wake("w1").cardId)
    }

    @Test fun theWatchStartsWhatThePhoneSaysRingsAndStopsWhatLeavesTheList() {
        val b = RingBoard()
        val u1 = b.apply(listOf(wake("w1")))
        assertEquals(listOf("w1"), u1.started.map { it.id })
        // The same list again starts nothing new.
        assertTrue(b.apply(listOf(wake("w1"))).started.isEmpty())
        val u3 = b.apply(emptyList())
        assertEquals(listOf("w1"), u3.stopped); assertTrue(b.ringing().isEmpty())
    }

    @Test fun anAnswerOnAnotherDeviceStopsItHereWhenThePhoneListDropsIt() {
        val b = RingBoard(); b.apply(listOf(wake("w1"), wake("w2")))
        val u = b.apply(listOf(wake("w2")))
        assertEquals(listOf("w1"), u.stopped); assertEquals(listOf("w2"), b.ringing().map { it.id })
    }

    @Test fun anAnswerOnTheWatchStopsItAtOnceAndALateListThatStillHasItDoesNotStartItAgain() {
        val b = RingBoard(); b.apply(listOf(wake("w1")))
        assertTrue(b.answerLocal("w1")); assertFalse(b.isRinging("w1"))
        val late = b.apply(listOf(wake("w1")))
        assertTrue("phone has not caught up yet", late.started.isEmpty()); assertFalse(b.isRinging("w1"))
        // The phone has caught up: it is gone from the list and the note is forgotten, so a snooze that comes back rings again.
        b.apply(emptyList())
        assertEquals(listOf("w1"), b.apply(listOf(wake("w1"))).started.map { it.id })
    }

    @Test fun answeringSomethingThatIsNotRingingChangesNothing() {
        val b = RingBoard(); assertFalse(b.answerLocal("nope"))
        assertTrue(b.apply(listOf(wake("nope"))).started.isNotEmpty())
    }

    @Test fun theMostSevereRingsFirstAndAnItemThatChangesIsReportedNotRestarted() {
        val b = RingBoard()
        b.apply(listOf(wake("a", "heads_up"), wake("b", "critical"), wake("c", "urgent")))
        assertEquals(listOf("b", "c", "a"), b.ringing().map { it.id })
        val u = b.apply(listOf(wake("a", "heads_up", sound = false), wake("b", "critical"), wake("c", "urgent")))
        assertEquals(listOf("a"), u.changed.map { it.id }); assertTrue(u.started.isEmpty() && u.stopped.isEmpty())
    }

    @Test fun wakesAndApprovalsRingTogetherAndStopIndependently() {
        val b = RingBoard()
        b.apply(listOf(wake("w1")), listOf(RingItem.approval(card(5))))
        assertEquals(setOf("w1", "card:5"), b.ringing().map { it.id }.toSet())
        // The approval was answered on the phone: the pending list no longer has it.
        val u = b.apply(listOf(wake("w1")), emptyList())
        assertEquals(listOf("card:5"), u.stopped); assertTrue(b.isRinging("w1"))
    }

    @Test fun anApprovalRingsOnceAndOnlyInTheModeHeChose() {
        val p = listOf(card(1), card(2))
        assertEquals(2, RingPolicy.approvalsToRing(p, ApprovalRingMode.FULL, emptySet(), null).size)
        assertTrue(RingPolicy.approvalsToRing(p, ApprovalRingMode.OFF, emptySet(), null).isEmpty())
        assertEquals(listOf("card:2"), RingPolicy.approvalsToRing(p, ApprovalRingMode.BUZZ, told = setOf(1L), viewing = null).map { it.id })
        assertEquals("not the one he is already looking at", listOf("card:1"), RingPolicy.approvalsToRing(p, ApprovalRingMode.FULL, emptySet(), viewing = 2L).map { it.id })
    }

    @Test fun aWakeBuzzsLoopsAndAnApprovalBuzzesOnce() {
        assertTrue(RingPattern.repeats(wake("w", "critical")))
        assertTrue(RingPattern.repeats(wake("w", "urgent")))
        assertFalse(RingPattern.repeats(wake("w", "heads_up")))
        assertFalse(RingPattern.repeats(RingItem.approval(card(1))))
        assertTrue(RingPattern.forItem(wake("w", "critical")).sum() > RingPattern.forItem(RingItem.approval(card(1))).sum())
        assertFalse("a silent heads-up does not buzz", RingPolicy.vibrates(wake("w", "heads_up", sound = false)))
    }

    @Test fun thePhoneOnlyAcceptsAckOrSnoozeForSomethingThatRingsNow() {
        val ringing = setOf("w1")
        assertTrue(RingRules.check(ringing, "w1", RingAnswer.ACK).ok)
        assertTrue(RingRules.check(ringing, "w1", RingAnswer.SNOOZE).ok)
        assertFalse(RingRules.check(ringing, "w2", RingAnswer.ACK).ok)
        assertFalse(RingRules.check(ringing, "w1", "delete").ok)
        assertEquals(Pair("w1", "ack"), RingWire.parseAnswer(RingWire.answer("w1", "ack")))
        assertNull(RingWire.parseAnswer("""{"id":"w1","what":"delete"}""".toByteArray()))
    }
}

class SurfacesTest {
    private fun st(pending: Int = 0, working: Boolean = false, link: String = "local", task: String? = null, call: Boolean = false) = WatchState(
        link = link, working = working, nextTask = task?.let { TaskView(1, it) }, callLive = call,
        pending = List(pending) { Pending(it.toLong(), Pending.KIND_APPROVAL, "x", "", Pending.APPROVAL_OPTIONS, "") })

    @Test fun theStatusFaceSaysTheMostImportantThing() {
        assertEquals(AgentStatus.IDLE, FaceData.status(null))
        assertEquals(AgentStatus.IN_CALL, FaceData.status(st(pending = 2, working = true, call = true)))
        assertEquals(AgentStatus.OFFLINE, FaceData.status(st(link = "offline", pending = 1)))
        assertEquals(AgentStatus.NEEDS_YOU, FaceData.status(st(pending = 1, working = true)))
        assertEquals(AgentStatus.WORKING, FaceData.status(st(working = true)))
        assertEquals(AgentStatus.IDLE, FaceData.status(st()))
    }

    @Test fun theApprovalsFaceCountsAndSendsATapToTheCardOrTheTalkScreen() {
        assertEquals(3, FaceData.waiting(st(pending = 3)))
        assertEquals("approvals", FaceData.approvalsGo(st(pending = 1)))
        assertEquals("talk", FaceData.approvalsGo(st()))
        assertEquals("99+", FaceData.count(250)); assertEquals("4", FaceData.count(4))
    }

    @Test fun theNextTaskFitsASmallAndAWideSlot() {
        val f = FaceData.nextTask(st(task = "Send the invoice to Shey this afternoon before the office closes tonight"))
        assertTrue(f.hasTask); assertEquals("Send", f.short); assertTrue(f.long.length <= 40)
        assertEquals("Reconci…", FaceData.shortWord("Reconciliation due", 8))
        assertFalse(FaceData.nextTask(st()).hasTask)
        assertFalse(FaceData.nextTask(st(task = "   ")).hasTask)
        assertFalse(FaceData.nextTask(null).hasTask)
    }

    private fun app(pkg: String, label: String) = AppEntry(pkg, label, "$pkg.Main")

    @Test fun theAppGridIsAlphabeticalWithTheAgentFirstAndHiddenAppsLeftOut() {
        val apps = listOf(app("c.z", "zebra"), app("c.b", "Banana"), app("self", "Agent"), app("c.a", "apple"), app("c.b", "Banana again"), app("c.h", "Hidden"))
        assertEquals(listOf("self", "c.a", "c.b", "c.z"), AppGrid.order(apps, "self", hidden = setOf("c.h")).map { it.pkg })
    }

    @Test fun recentsAreWhatWasOpenedFromHereNewestFirstAndSkipAppsThatAreGone() {
        val apps = listOf(app("a", "A"), app("b", "B"), app("c", "C"))
        val log = LaunchLog()
        log.record("a"); log.record("b"); log.record("gone"); log.record("a")
        assertEquals(listOf("a", "gone", "b"), log.recent(3))
        assertEquals(listOf("a", "b"), AppGrid.recents(apps, log, 4).map { it.pkg })
        assertEquals(log.recent(3), LaunchLog.fromJson(log.toJson()).recent(3))
        assertEquals(12, LaunchLog(12).also { l -> repeat(30) { l.record("p$it") } }.recent(99).size)
        log.forget("a"); assertFalse("a" in log.recent(9))
    }

    @Test fun usingTheAgentAsTheHomeScreenIsOffUntilAskedAndAlwaysHasAWayBack() {
        assertEquals(HomeGuide.State.OFF, HomeGuide.stateOf(false, true, false, "stock", "self"))
        assertEquals(HomeGuide.State.HELD_BY_THIS_APP, HomeGuide.stateOf(true, true, true, "self", "self"))
        assertEquals(HomeGuide.State.HELD_BY_OTHER, HomeGuide.stateOf(true, true, false, "stock", "self"))
        assertEquals(listOf(HomeGuide.Step.ENABLE_COMPONENT, HomeGuide.Step.REQUEST_ROLE), HomeGuide.turnOn(true, true, true))
        assertEquals(listOf(HomeGuide.Step.ENABLE_COMPONENT, HomeGuide.Step.OPEN_HOME_SETTINGS), HomeGuide.turnOn(false, true, true))
        assertEquals(listOf(HomeGuide.Step.ENABLE_COMPONENT, HomeGuide.Step.OPEN_DEFAULT_APPS), HomeGuide.turnOn(false, false, true))
        assertEquals("a watch with no picker at all is told how", HomeGuide.Step.SAY_HOW_TO_PICK, HomeGuide.turnOn(false, false, false).last())
        // Going back never depends on a system screen existing.
        for (h in listOf(true, false)) for (d in listOf(true, false)) assertEquals(HomeGuide.Step.DISABLE_COMPONENT, HomeGuide.turnOff(h, d).first())
    }
}
