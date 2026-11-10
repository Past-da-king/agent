package com.past9.phoneaos

import com.past9.phoneaos.assist.AssistCore
import com.past9.phoneaos.assist.AssistEntry
import com.past9.phoneaos.assist.AssistHub
import com.past9.phoneaos.assist.AssistMessage
import com.past9.phoneaos.assist.AssistPhase
import com.past9.phoneaos.assist.AssistRole
import com.past9.phoneaos.assist.AssistWords
import com.past9.phoneaos.assist.ScreenContext
import com.past9.phoneaos.assist.ScreenGuard
import com.past9.phoneaos.assist.ScreenNode
import com.past9.phoneaos.data.ChatItem
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The assist sheet's rules, free of Android: the state machine, what a turn may know about the screen, and the way in. */
class AssistTest {
    private fun row(id: Long, kind: String, text: String, meta: JSONObject = JSONObject()) = ChatItem(id = id, kind = kind, text = text, meta = meta.toString())

    // ---- the state machine ----------------------------------------------------------------------

    @Test fun itListensTheMomentItOpens() {
        val c = AssistCore()
        assertTrue(c.listening)
        assertEquals(AssistPhase.LISTENING, c.view(emptyList(), false).phase)
        c.partial("what is on")
        assertEquals("what is on", c.view(emptyList(), false).heard)
    }

    @Test fun withNoMicrophoneItOpensOnTheKeyboard() {
        val c = AssistCore(micAvailable = false)
        assertFalse(c.listening)
        assertEquals(AssistPhase.TYPING, c.view(emptyList(), false).phase)
    }

    @Test fun finishedWordsAreSentWholeAndTheTurnIsFollowedThroughTheChat() {
        val c = AssistCore()
        c.partial("add milk to my")
        assertEquals("add milk to my list", c.finished("  add milk to my list "))
        assertEquals(AssistPhase.SENDING, c.view(emptyList(), false).phase)
        val before = listOf(row(1, "user", "earlier"), row(2, "agent", "An older answer"))
        c.sent(lastIdBefore = 2)
        // Nothing of this turn yet: thinking, and the older answer is not shown as this one's.
        assertEquals(AssistPhase.THINKING, c.view(before, false).phase)
        assertEquals(AssistPhase.THINKING, c.view(before + row(3, "user", "add milk to my list"), true).phase)
        val acting = c.view(before + row(3, "user", "add milk to my list") + row(4, "activity", "Adding a to-do"), true)
        assertEquals(AssistPhase.ACTING, acting.phase); assertEquals("Adding a to-do", acting.label)
        val done = c.view(before + row(3, "user", "add milk to my list") + row(4, "activity", "Adding a to-do") + row(5, "agent", "Added milk."), false)
        assertEquals(AssistPhase.ANSWERING, done.phase); assertEquals("Added milk.", done.answer); assertEquals(5L, done.answerId)
        assertEquals("add milk to my list", done.heard)
    }

    @Test fun silenceSendsNothingAndOffersTheKeyboard() {
        val c = AssistCore()
        assertNull(c.finished("   "))
        val v = c.view(emptyList(), false)
        assertEquals(AssistPhase.TYPING, v.phase)
        assertTrue(v.problem.contains("didn't catch"))
    }

    @Test fun anApprovalWaitsInTheSheetAndNothingElseHidesIt() {
        val c = AssistCore()
        c.finished("send this to Sam"); c.sent(10)
        val approval = row(12, "question", "APPROVAL|Send a WhatsApp message to Sam|Hello Sam", JSONObject().put("options", org.json.JSONArray(listOf("Approve", "Decline"))))
        val v = c.view(listOf(row(11, "user", "send this to Sam"), approval, row(13, "agent", "I need your yes first.")), true)
        assertEquals(AssistPhase.APPROVAL, v.phase)
        assertEquals(12L, v.card?.id)
        // The computer's approval rows are their own kind.
        val remote = row(14, "approval", "Send the email", JSONObject().put("summary", "Send the email"))
        assertEquals(AssistPhase.APPROVAL, c.view(listOf(remote), false).phase)
        // Once answered it no longer holds the sheet: the turn carries on.
        val answered = approval.copy(meta = JSONObject(approval.meta).put("answer", "Approve").toString())
        assertEquals(AssistPhase.ANSWERING, c.view(listOf(answered, row(15, "agent", "Sent.")), false).phase)
    }

    @Test fun aQuestionWithButtonsIsAskedInTheSheet() {
        val c = AssistCore()
        c.finished("book a table"); c.sent(0)
        val q = row(2, "question", "For how many people?", JSONObject().put("options", org.json.JSONArray(listOf("2", "4"))))
        assertEquals(AssistPhase.ASKING, c.view(listOf(q), true).phase)
    }

    @Test fun aFailureIsSaidInPlainWords() {
        val c = AssistCore()
        c.finished("what's the weather"); c.sent(0)
        val v = c.view(listOf(row(1, "user", "what's the weather"), row(2, "notice", "Your key has no credit left.", JSONObject().put("error", true))), false)
        assertEquals(AssistPhase.ERROR, v.phase)
        assertEquals("Your key has no credit left.", v.problem)
        // A later answer wins over an earlier failure.
        assertEquals(AssistPhase.ANSWERING, c.view(listOf(row(2, "notice", "x", JSONObject().put("error", true)), row(3, "agent", "Sunny.")), false).phase)
    }

    @Test fun aMessageThatDidNotSendKeepsTheWords() {
        val c = AssistCore()
        c.finished("remind me at six")
        c.sendFailed("That did not send.")
        val v = c.view(emptyList(), false)
        assertEquals(AssistPhase.TYPING, v.phase); assertEquals("remind me at six", v.heard); assertEquals("That did not send.", v.problem)
        assertEquals("remind me at six", c.finished(v.heard, byKeyboard = true))
    }

    @Test fun onALockedPhoneTheWordsWaitForTheUnlock() {
        val c = AssistCore()
        assertNull(c.finished("read my last message", locked = true))
        assertEquals(AssistPhase.LOCKED, c.view(emptyList(), false).phase)
        assertTrue(c.waitingForUnlock)
        assertEquals("read my last message", c.unlocked())
        assertEquals(AssistPhase.SENDING, c.view(emptyList(), false).phase)
    }

    @Test fun aMicrophoneThatFailsHandsOverToTheKeyboardWithTheReason() {
        val c = AssistCore()
        c.micFailed("The microphone is busy or not working.")
        val v = c.view(emptyList(), false)
        assertEquals(AssistPhase.TYPING, v.phase); assertEquals("The microphone is busy or not working.", v.problem)
    }

    @Test fun anAgentThatStopsWithoutAWordDoesNotSpinForever() {
        val c = AssistCore()
        c.finished("do the thing"); c.sent(0)
        assertEquals(AssistPhase.THINKING, c.view(emptyList(), true).phase)
        val v = c.view(listOf(row(1, "user", "do the thing"), row(2, "notice", "Stopped.")), false)
        assertEquals(AssistPhase.ANSWERING, v.phase); assertEquals("Stopped.", v.answer)
    }

    @Test fun speakingAgainStartsANewTurn() {
        val c = AssistCore()
        c.finished("one"); c.sent(0)
        c.listen()
        assertTrue(c.listening)
        assertEquals("", c.view(listOf(row(1, "agent", "old")), false).heard)
        assertEquals("two", c.finished("two")); c.sent(1)
        assertEquals(AssistPhase.THINKING, c.view(listOf(row(1, "agent", "old")), false).phase)
    }

    // ---- what a turn may know about the screen --------------------------------------------------

    @Test fun bankingAndMoneyAppsGiveNothing() {
        listOf("za.co.fnb.connect.itt", "com.paypal.android.p2pmobile", "com.google.android.apps.walletnfcrel", "capitecbank.remote.prd", "com.samsung.android.spay",
            "com.revolut.revolut", "com.chase.sig.android", "com.somebank.mobile", "org.example.mybanking", "com.acme.wallet", "io.new.crypto.app", "com.binance.dev")
            .forEach { assertTrue(it, ScreenGuard.blocked(it)) }
        // A label gives a money app away even when its package does not.
        assertTrue(ScreenGuard.blocked("com.xyz.app123", "First Mobile Banking"))
        listOf("com.whatsapp", "com.android.chrome", "com.google.android.youtube", "com.example.notes", "com.spotify.music", "com.display.settings", "com.android.settings")
            .forEach { assertFalse(it, ScreenGuard.blocked(it)) }
        val gated = ScreenContext.gate(readScreen = true, readScreenshot = true, pkg = "za.co.fnb.connect.itt", label = "FNB", text = "Balance R 12 345.67", link = "", screenshot = "/x/shot.jpg", secureWindow = false)
        assertNotNull(gated)
        assertTrue(gated!!.blocked); assertEquals("", gated.text); assertEquals("", gated.screenshot); assertFalse(gated.hasAnything)
        val msg = AssistMessage.compose("what is my balance", AssistEntry.SIDE_KEY, gated)
        assertFalse(msg.contains("12 345")); assertTrue(msg.contains("never reads"))
    }

    @Test fun nothingIsReadUntilTheSwitchIsOnAndThePictureNeedsItsOwn() {
        assertNull(ScreenContext.gate(false, false, "com.example.notes", "Notes", "Milk", "", "/x/s.jpg", false))
        // The picture switch alone does nothing.
        assertNull(ScreenContext.gate(false, true, "com.example.notes", "Notes", "Milk", "", "/x/s.jpg", false))
        val words = ScreenContext.gate(true, false, "com.example.notes", "Notes", "Milk", "", "/x/s.jpg", false)!!
        assertEquals("Milk", words.text); assertEquals("", words.screenshot)
        val both = ScreenContext.gate(true, true, "com.example.notes", "Notes", "Milk", "", "/x/s.jpg", false)!!
        assertEquals("/x/s.jpg", both.screenshot)
        // A protected window hands over nothing, and says so.
        val secure = ScreenContext.gate(true, true, "com.example.vault", "Vault", "", "", "", true)!!
        assertTrue(secure.protectedWindow); assertFalse(secure.hasAnything)
    }

    @Test fun passwordsAndHiddenThingsAreNeverRead() {
        val tree = ScreenNode(children = listOf(
            ScreenNode(text = "Sign in"),
            ScreenNode(text = "hunter2", secret = true),
            ScreenNode(text = "Behind a tab", visible = false, children = listOf(ScreenNode(text = "Also hidden"))),
            ScreenNode(description = "Send button"),
            ScreenNode(text = "Sign in"),
        ))
        val words = ScreenContext.words(listOf(tree))
        assertEquals("Sign in\nSend button", words)
        assertTrue(ScreenContext.words(listOf(ScreenNode(children = (1..2000).map { ScreenNode(text = "line number $it of the page") })), max = 500).length < 560)
    }

    @Test fun aPictureIsOnlyPassedOnForAKnownAppThatIsNotAMoneyApp() {
        AssistHub.begin()
        AssistHub.offerShot("/x/first.jpg")
        assertNull("no app known yet, so no picture", AssistHub.screen.value)
        AssistHub.offer(ScreenContext(pkg = "", label = "", protectedWindow = true))
        assertEquals("", AssistHub.screen.value!!.screenshot)
        AssistHub.offer(ScreenContext(pkg = "com.somebank.mobile", blocked = true))
        assertEquals("", AssistHub.screen.value!!.screenshot)
        AssistHub.offer(ScreenContext(pkg = "com.example.notes", label = "Notes", text = "Milk"))
        assertEquals("/x/first.jpg", AssistHub.screen.value!!.screenshot)
        AssistHub.clear()
        assertNull(AssistHub.screen.value)
    }

    @Test fun whatTheScreenSessionLearnedIsKeptForTheSheetItOpenedAndDroppedForALaterOne() {
        AssistHub.begin(now = 1_000)
        AssistHub.offer(ScreenContext(pkg = "com.example.notes", text = "Milk"))
        AssistHub.keepIfFresh(now = 2_000)
        assertNotNull(AssistHub.screen.value)
        AssistHub.keepIfFresh(now = 60_000)
        assertNull(AssistHub.screen.value)
    }

    @Test fun theMessageIsHisWordsThenANoteOnlyTheAgentReads() {
        val screen = ScreenContext(pkg = "com.example.notes", label = "Notes", text = "Milk\nEggs", link = "https://example.com/list", screenshot = "/x/s.jpg")
        val msg = AssistMessage.compose("send this to Sam", AssistEntry.WAKE_WORD, screen)
        assertEquals("send this to Sam", AssistMessage.visible(msg))
        assertTrue(msg.contains("Notes (com.example.notes)")); assertTrue(msg.contains("Milk\nEggs")); assertTrue(msg.contains("https://example.com/list"))
        assertTrue(msg.contains("picture of the screen is attached")); assertTrue(msg.contains("wake word"))
        // The seam for the background screen, and the rule that never bends.
        assertTrue(msg.contains("screen_open_app")); assertTrue(msg.contains("banking"))
        assertTrue(msg.contains("approval"))
        val off = AssistMessage.compose("what is this", AssistEntry.SIDE_KEY, null)
        assertTrue(off.contains("not read")); assertFalse(off.contains("Words on screen"))
        assertTrue(AssistMessage.compose("hello", AssistEntry.SHORTCUT, null, typed = true).contains("typed"))
        assertTrue(AssistMessage.PROMPT.contains("screen_")); assertTrue(AssistMessage.PROMPT.contains("Banking"))
    }

    @Test fun theSheetSaysWhatItCanSeeOfTheScreen() {
        assertEquals("Not reading the screen", AssistWords.screenLine(null, readScreenOn = false)!!.text)
        assertNull(AssistWords.screenLine(null, readScreenOn = true))
        assertEquals(ScreenGuard.SAYS, AssistWords.screenLine(ScreenContext(pkg = "com.somebank.mobile", blocked = true), true)!!.text)
        assertEquals("I don't read banking apps", ScreenGuard.SAYS)
        val l = AssistWords.screenLine(ScreenContext(pkg = "com.example.notes", label = "Notes", text = "Milk"), true)!!
        assertTrue(l.reading); assertEquals("Reading the screen: Notes", l.text)
    }

    // ---- the way in -----------------------------------------------------------------------------

    @Test fun theEntryPointKnowsItsSourcesAndPicksTheWayThatWillShow() {
        assertEquals("com.past9.phoneaos.action.ASSIST", AssistEntry.ACTION)
        assertEquals("source", AssistEntry.EXTRA_SOURCE)
        assertEquals(setOf("side_key", "wake_word", "shortcut", "widget"), AssistEntry.SOURCES)
        assertEquals("wake_word", AssistEntry.source("wake_word"))
        assertEquals("shortcut", AssistEntry.source("anything else")); assertEquals("shortcut", AssistEntry.source(null))
        // The phone's assistant: its session shows over any app and the lock screen.
        assertEquals(AssistEntry.Route.SESSION, AssistEntry.route(sessionReady = true, appOnScreen = false, mayAppearOnTop = false))
        // Not the assistant: the sheet's own activity, when the phone lets it appear.
        assertEquals(AssistEntry.Route.ACTIVITY, AssistEntry.route(false, appOnScreen = true, mayAppearOnTop = false))
        assertEquals(AssistEntry.Route.ACTIVITY, AssistEntry.route(false, appOnScreen = false, mayAppearOnTop = true))
        // In the background with no way to appear: a full-screen notification.
        assertEquals(AssistEntry.Route.NOTIFICATION, AssistEntry.route(false, false, false))
    }

    // ---- the role and the Settings row -----------------------------------------------------------

    @Test fun theRoleIsReadFromThePhonesOwnSettings() {
        val pkg = "com.past9.phoneaos"
        assertTrue(AssistRole.heldBy(pkg, "$pkg/.assist.AssistService", null))
        assertTrue(AssistRole.heldBy(pkg, null, "$pkg/com.past9.phoneaos.assist.AssistService"))
        assertFalse(AssistRole.heldBy(pkg, "com.other.assistant/.Service", "com.other.assistant/.Interactor"))
        assertFalse(AssistRole.heldBy(pkg, null, null)); assertFalse(AssistRole.heldBy(pkg, "", ""))
        // A package that only starts the same way is not this app.
        assertFalse(AssistRole.heldBy(pkg, "$pkg.other/.Service", null))
        assertTrue(AssistRole.line(true).startsWith("On")); assertTrue(AssistRole.line(false).startsWith("Off"))
    }

    @Test fun theGuideOnlySaysWhatIsTrueForTheMakerOfThePhone() {
        val samsung = AssistRole.guide("Agent", manufacturer = "samsung", sdk = 36)
        assertEquals(2, samsung.steps.size); assertTrue(samsung.steps[1].contains("Agent"))
        assertTrue(samsung.triggers.any { it.contains("side button") })
        val pixel = AssistRole.guide("Agent", manufacturer = "Google", sdk = 36)
        assertTrue(pixel.triggers.any { it.contains("power button") }); assertFalse(pixel.triggers.any { it.contains("side button") })
        val other = AssistRole.guide("Agent", manufacturer = "Acme", sdk = 30)
        assertFalse(other.triggers.any { it.contains("Advanced features") || it.contains("side button") })
        assertTrue(other.triggers.any { it.contains("assistant shortcut") })
    }
}
