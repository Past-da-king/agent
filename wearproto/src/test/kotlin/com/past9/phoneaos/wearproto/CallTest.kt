package com.past9.phoneaos.wearproto

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallPlanTest {
    private fun req(mic: Boolean, speaker: Boolean, earbuds: Boolean = false, phoneAudio: Boolean = false) = CallStart("c1", WatchAudioCaps(mic, speaker, earbuds), phoneAudio)

    @Test fun aWatchWithAMicAndASpeakerCarriesTheSoundItself() {
        val p = CallAudioPlan.choose(req(true, true))
        assertEquals(Side.WATCH, p.mic); assertEquals(Side.WATCH, p.spk)
        assertTrue(p.endsWhenWatchLost)
    }

    @Test fun eachDirectionFallsBackToThePhoneOnItsOwn() {
        val noMic = CallAudioPlan.choose(req(false, true))
        assertEquals(Side.PHONE, noMic.mic); assertEquals(Side.WATCH, noMic.spk)
        val noSpeaker = CallAudioPlan.choose(req(true, false))
        assertEquals(Side.WATCH, noSpeaker.mic); assertEquals(Side.PHONE, noSpeaker.spk)
    }

    @Test fun aWatchWithNeitherIsOnlyTheScreenOfACallThatRunsOnThePhonesSound() {
        val p = CallAudioPlan.choose(req(false, false))
        assertEquals(Side.PHONE, p.mic); assertEquals(Side.PHONE, p.spk)
        assertFalse("the call carries on if the watch goes", p.endsWhenWatchLost)
        assertTrue(p.phoneMic && p.phoneSpeaker)
    }

    @Test fun earbudsOnTheWatchCountAsAWatchSpeakerAndSayWhy() {
        val p = CallAudioPlan.choose(req(true, true, earbuds = true))
        assertTrue(p.watchSpeaker); assertTrue(p.why.contains("earbuds"))
    }

    @Test fun choosingThePhonesSoundOverridesAWatchThatCouldDoIt() {
        val p = CallAudioPlan.choose(req(true, true, phoneAudio = true))
        assertEquals(Side.PHONE, p.mic); assertEquals(Side.PHONE, p.spk)
    }

    @Test fun callMessagesRoundTripAndBadOnesAreRefused() {
        val s = req(true, false, earbuds = true)
        assertEquals(s, CallWire.parseStart(CallWire.start(s)))
        assertNull(CallWire.parseStart("""{"mic":true}""".toByteArray()))
        assertEquals("c1" to true, CallWire.parseMute(CallWire.mute("c1", true)))
        assertEquals("c1", CallWire.parseId(CallWire.id("c1")))
        assertNull(CallWire.parseId(null))
        val plan = CallPlan("c1", Side.WATCH, Side.PHONE, "why")
        assertEquals(plan, CallWire.parsePlan(CallWire.plan(plan)))
        // A side nobody knows becomes the phone, never a watch that is not there.
        assertEquals(Side.PHONE, CallWire.parsePlan("""{"id":"x","mic":"moon","spk":"watch"}""".toByteArray())?.mic)
        val v = CallView("c1", CallView.SPEAKING, muted = true, line = "x".repeat(400), who = "out", mic = Side.WATCH, spk = Side.WATCH, agent = "Hina")
        val back = CallWire.parseView(CallWire.view(v))!!
        assertEquals(CallView.SPEAKING, back.phase); assertTrue(back.muted); assertTrue(back.line.length <= CallView.LINE_MAX)
    }

    @Test fun theRouterHandsCallRequestsToTheSinkAndAnswersEachWithAnAck() = runTest {
        val sink = object : RelaySink {
            val log = ArrayList<String>()
            override suspend fun ask(text: String, via: String) = RelayResult(true)
            override suspend fun answer(card: Long, option: String) = RelayResult(true)
            override suspend fun stop() = RelayResult(true)
            override suspend fun speakOnPhone() = RelayResult(true)
            override suspend fun hello() {}
            override suspend fun callStart(req: CallStart): RelayResult { log += "start ${req.id}"; return RelayResult(true) }
            override suspend fun callEnd(id: String): RelayResult { log += "end $id"; return RelayResult(true) }
            override suspend fun callMute(id: String, on: Boolean): RelayResult { log += "mute $id $on"; return RelayResult(true) }
            override fun callBeat(id: String) { log += "beat $id" }
            override suspend fun ringAnswer(id: String, what: String): RelayResult { log += "ring $id $what"; return RelayResult(what == "ack") }
        }
        val r = WatchRouter(sink)
        val a = Wire.parseAck(r.route(CallPaths.START, CallWire.start(CallStart("c9", WatchAudioCaps(true, true))))!!.data)!!
        assertTrue(a.ok); assertEquals("c9", a.id)
        assertEquals("c9", Wire.parseAck(r.route(CallPaths.END, CallWire.id("c9"))!!.data)!!.id)
        assertTrue(Wire.parseAck(r.route(CallPaths.MUTE, CallWire.mute("c9", true))!!.data)!!.ok)
        assertNull("a beat is never answered", r.route(CallPaths.BEAT, CallWire.id("c9")))
        val ring = Wire.parseAck(r.route(RingPaths.ANSWER, RingWire.answer("w1", "snooze"))!!.data)!!
        assertFalse(ring.ok); assertEquals("ring:w1", ring.id)
        assertEquals(listOf("start c9", "end c9", "mute c9 true", "beat c9", "ring w1 snooze"), sink.log)
        // Unreadable ones are refused, not ignored.
        assertFalse(Wire.parseAck(r.route(CallPaths.START, "no".toByteArray())!!.data)!!.ok)
        assertFalse(Wire.parseAck(r.route(RingPaths.ANSWER, RingWire.answer("w1", "delete"))!!.data)!!.ok)
    }

    @Test fun aSinkThatCannotCallSaysSoInsteadOfDoingNothing() = runTest {
        val sink = object : RelaySink {
            override suspend fun ask(text: String, via: String) = RelayResult(true)
            override suspend fun answer(card: Long, option: String) = RelayResult(true)
            override suspend fun stop() = RelayResult(true)
            override suspend fun speakOnPhone() = RelayResult(true)
            override suspend fun hello() {}
        }
        val a = Wire.parseAck(WatchRouter(sink).route(CallPaths.START, CallWire.start(CallStart("c1", WatchAudioCaps(true, true))))!!.data)!!
        assertFalse(a.ok); assertTrue(a.reason.isNotBlank())
    }

    @Test fun theStateKeepsTheNextTaskAndTheLiveCall() {
        val s = WatchState(agent = "A", nextTask = TaskView(4, "Send the invoice", 99L), callLive = true)
        val back = Wire.parseState(Wire.state(s))!!
        assertEquals(TaskView(4, "Send the invoice", 99L), back.nextTask); assertTrue(back.callLive)
        // An older phone sends neither, and both read as absent.
        val old = Wire.parseState("""{"v":1,"agent":"A","link":"local","working":false,"pending":[],"recent":[]}""".toByteArray())!!
        assertNull(old.nextTask); assertFalse(old.callLive)
    }
}

class PhoneCallGateTest {
    private val both = CallStart("c1", WatchAudioCaps(true, true))
    private val screenOnly = CallStart("c1", WatchAudioCaps(false, false))

    @Test fun oneCallAtATimeAndASecondRequestChangesNothing() {
        val g = PhoneCallGate()
        assertNotNull(g.open(both, 0))
        assertNull(g.open(both.copy(id = "c2"), 10))
        assertEquals("c1", g.currentId())
    }

    @Test fun aLateMessageAboutAnEarlierCallIsIgnored() {
        val g = PhoneCallGate()
        g.open(both, 0)
        assertFalse(g.close("old"))
        assertTrue(g.active())
        assertTrue(g.close("c1"))
        assertFalse(g.active())
        assertNotNull("a new call can start once the last one has ended", g.open(both.copy(id = "c3"), 100))
    }

    @Test fun aWatchThatCarriesTheSoundIsEndedAfterItGoesQuietForTooLong() {
        val g = PhoneCallGate(deadAfterMs = 20_000)
        g.open(both, 0)
        assertEquals(PhoneCallGate.Verdict.KEEP, g.judge(3_000))
        assertEquals(PhoneCallGate.Verdict.DROP_OUT, g.judge(8_000))
        g.heardAudio("c1", 9_000)
        assertEquals("a beat brings it back", PhoneCallGate.Verdict.KEEP, g.judge(10_000))
        assertEquals(PhoneCallGate.Verdict.END, g.judge(9_000 + 20_000))
    }

    @Test fun aCallOnThePhonesOwnSoundCarriesOnWhateverTheWatchDoes() {
        val g = PhoneCallGate(deadAfterMs = 20_000)
        g.open(screenOnly, 0)
        assertEquals(PhoneCallGate.Verdict.KEEP, g.judge(60_000))
    }

    @Test fun aWatchThatBeatsButWhoseMicStreamBrokeIsStillEndedBecauseNobodyCanSpeakIntoTheCall() {
        val g = PhoneCallGate(deadAfterMs = 20_000)
        g.open(both, 0)
        for (t in 2_000..30_000 step 2_000) g.heard("c1", t.toLong())
        assertEquals(PhoneCallGate.Verdict.END, g.judge(30_000))
        // Frames keep it alive.
        val h = PhoneCallGate(deadAfterMs = 20_000); h.open(both, 0)
        for (t in 100..30_000 step 100) h.heardAudio("c1", t.toLong())
        assertEquals(PhoneCallGate.Verdict.KEEP, h.judge(30_000))
    }

    @Test fun aWatchThatOnlyCarriesTheSpeakerNeedsNoMicStream() {
        val g = PhoneCallGate(deadAfterMs = 20_000)
        g.open(CallStart("c1", WatchAudioCaps(mic = false, speaker = true)), 0)
        for (t in 2_000..30_000 step 2_000) g.heard("c1", t.toLong())
        assertEquals(PhoneCallGate.Verdict.KEEP, g.judge(30_000))
    }

    @Test fun aBeatFromAnotherCallDoesNotKeepThisOneAlive() {
        val g = PhoneCallGate(deadAfterMs = 20_000)
        g.open(both, 0)
        g.heard("other", 19_000)
        assertEquals(PhoneCallGate.Verdict.END, g.judge(20_000))
    }

    @Test fun framesAreCutIntoWholeHundredMillisecondPiecesAndNothingIsLost() {
        val f = MicFramer(3_200)
        assertTrue(f.feed(ByteArray(1_000) { 1 }).isEmpty())
        val out = f.feed(ByteArray(6_000) { 2 })
        assertEquals(2, out.size)
        assertTrue(out.all { it.size == 3_200 })
        // 1000 + 6000 = 7000 in, 6400 out, 600 kept: the next 2600 bytes complete the third frame.
        assertEquals(1, f.feed(ByteArray(2_600)).size)
        f.reset(); assertTrue(f.feed(ByteArray(100)).isEmpty())
    }
}

class WatchCallCoreTest {
    private val released = ArrayList<String>()
    private fun core(startTimeout: Long = 25_000, dead: Long = 20_000) = WatchCallCore({ released += it }, startTimeout, dead)
    private fun view(id: String, phase: String, line: String = "") = CallView(id, phase, line = line, who = if (line.isEmpty()) "" else "out")

    @Test fun aCallStartsConnectingGoesLiveShowsTheLineAndEndsOnce() {
        released.clear()
        val c = core()
        assertTrue(c.begin("c1", 0))
        assertEquals(WatchCallCore.STARTING, c.state.phase)
        c.onPhone(view("c1", CallView.CONNECTING), 500)
        assertEquals(WatchCallCore.STARTING, c.state.phase)
        c.onPhone(view("c1", CallView.LISTENING), 1_000)
        assertEquals(WatchCallCore.LIVE, c.state.phase)
        c.onPhone(view("c1", CallView.SPEAKING, "Hello there"), 2_000)
        assertEquals("Hello there", c.state.line)
        c.end(); c.end()
        assertEquals(WatchCallCore.ENDED, c.state.phase)
        assertEquals("the speaker and mic are let go exactly once", listOf("c1"), released)
    }

    @Test fun aSecondTapWhileACallIsOnChangesNothing() {
        val c = core(); assertTrue(c.begin("c1", 0)); assertFalse(c.begin("c2", 10)); assertEquals("c1", c.state.id)
    }

    @Test fun aPictureAboutAnotherCallIsIgnored() {
        val c = core(); c.begin("c1", 0)
        c.onPhone(view("zzz", CallView.ENDED), 100)
        assertTrue(c.state.active); assertTrue(released.isEmpty() || released.none { it == "zzz" })
    }

    @Test fun theCallThatNeverStartsFailsInsteadOfSittingThere() {
        released.clear()
        val c = core(startTimeout = 25_000); c.begin("c1", 0)
        c.tick(24_000); assertEquals(WatchCallCore.STARTING, c.state.phase)
        c.tick(26_000)
        assertEquals(WatchCallCore.FAILED, c.state.phase); assertTrue(c.state.error.isNotBlank()); assertEquals(listOf("c1"), released)
    }

    @Test fun thePhoneSayingNoEndsItAtOnceWithItsReason() {
        released.clear()
        val c = core(); c.begin("c1", 0)
        c.refused("c1", "Add a Google key in Settings.")
        assertEquals(WatchCallCore.FAILED, c.state.phase); assertEquals("Add a Google key in Settings.", c.state.error); assertEquals(1, released.size)
    }

    @Test fun aDropOutIsShownAsReconnectingAndTheCallComesBack() {
        val c = core(); c.begin("c1", 0)
        c.onPhone(view("c1", CallView.LISTENING), 1_000)
        c.tick(8_000)
        assertTrue(c.state.degraded); assertTrue(c.state.active)
        c.onPhone(view("c1", CallView.LISTENING), 9_000)
        assertFalse(c.state.degraded)
        c.onPhone(view("c1", CallView.RECONNECTING), 10_000)
        assertTrue(c.state.degraded); assertEquals(WatchCallCore.LIVE, c.state.phase)
    }

    @Test fun aPhoneThatGoesQuietForTooLongEndsTheCallOnTheWatchAndFreesTheAudio() {
        released.clear()
        val c = core(dead = 20_000); c.begin("c1", 0)
        c.onPhone(view("c1", CallView.LISTENING), 1_000)
        c.tick(30_000)
        assertEquals(WatchCallCore.ENDED, c.state.phase); assertEquals("Lost the phone.", c.state.error); assertEquals(listOf("c1"), released)
    }

    @Test fun thePhoneEndingTheCallEndsItHereToo() {
        released.clear()
        val c = core(); c.begin("c1", 0)
        c.onPhone(view("c1", CallView.LISTENING), 1_000)
        c.onPhone(CallView("c1", CallView.ENDED, error = "The call was cut off."), 2_000)
        assertEquals(WatchCallCore.ENDED, c.state.phase); assertEquals("The call was cut off.", c.state.error); assertEquals(1, released.size)
        c.onPhone(view("c1", CallView.LISTENING), 3_000)
        assertEquals("a late picture cannot bring an ended call back", WatchCallCore.ENDED, c.state.phase)
    }

    @Test fun muteIsKeptWhileTheCallLivesAndClearedWhenItEnds() {
        val c = core(); c.begin("c1", 0)
        c.onPhone(view("c1", CallView.LISTENING), 1)
        c.muted(true); assertTrue(c.state.muted)
        c.end(); assertFalse(c.state.muted)
        c.muted(true); assertFalse("no mute on a call that is over", c.state.muted)
    }

    @Test fun aNewCallCanStartAfterTheLastOneEndedAndReleasesAgain() {
        released.clear()
        val c = core(); c.begin("c1", 0); c.end()
        assertTrue(c.begin("c2", 100)); c.end()
        assertEquals(listOf("c1", "c2"), released)
    }
}

class LinkWatchTest {
    @Test fun healthGoesFromOkToDegradedToDeadAndABeatBringsItBack() {
        val w = LinkWatch(degradedAfterMs = 6_000, deadAfterMs = 20_000, start = 0)
        assertEquals(LinkWatch.Health.OK, w.health(5_999))
        assertEquals(LinkWatch.Health.DEGRADED, w.health(6_000))
        assertEquals(LinkWatch.Health.DEAD, w.health(20_000))
        w.beat(20_500)
        assertEquals(LinkWatch.Health.OK, w.health(21_000))
    }
}

class PcmJitterTest {
    // 24 kHz mono PCM16: 48 bytes per millisecond.
    private fun ms(n: Int) = ByteArray(n * 48) { 1 }

    @Test fun nothingPlaysUntilALittleHasBeenKeptBack() {
        val j = PcmJitter(24_000, prebufferMs = 200)
        j.push(ms(100)); assertNull(j.pull(4_800))
        j.push(ms(120)); assertNotNull(j.pull(4_800))
    }

    @Test fun whatIsPulledComesOutInOrderAndInTheSizeAsked() {
        val j = PcmJitter(24_000, prebufferMs = 0)
        j.push(ByteArray(100) { 1 }); j.push(ByteArray(100) { 2 })
        val a = j.pull(60)!!; assertEquals(60, a.size)
        val b = j.pull(100)!!; assertEquals(40, b.size); assertTrue(b.all { it == 1.toByte() })
        assertTrue(j.pull(1_000)!!.all { it == 2.toByte() })
    }

    @Test fun whenItRunsDryItRefillsBeforePlayingAgainAndCountsTheGap() {
        val j = PcmJitter(24_000, prebufferMs = 100)
        j.push(ms(150)); assertNotNull(j.pull(100_000))
        assertNull(j.pull(100_000)); assertEquals(1, j.underruns)
        j.push(ms(50)); assertNull("still filling", j.pull(100_000))
        j.push(ms(60)); assertNotNull(j.pull(100_000))
    }

    @Test fun aBacklogAfterAStallIsDroppedDownSoTheVoiceDoesNotLagTheConversation() {
        val j = PcmJitter(24_000, prebufferMs = 100, maxMs = 1_000, catchUpMs = 300)
        repeat(30) { j.push(ms(100)) }
        assertTrue("never more than the limit", j.bufferedMs <= 1_000)
        assertTrue(j.droppedBytes > 0)
    }

    @Test fun oneBigBurstAfterAStallIsCutDownToTheCatchUpSize() {
        val j = PcmJitter(24_000, prebufferMs = 100, maxMs = 1_000, catchUpMs = 300)
        j.push(ms(3_000))
        assertEquals(300, j.bufferedMs)
        assertEquals("the newest sound is kept", 3_000 * 48 - 300 * 48, j.droppedBytes.toInt())
    }

    @Test fun beingCutOffDropsEverythingQueuedAtOnce() {
        val j = PcmJitter(24_000, prebufferMs = 0)
        j.push(ms(500)); j.flush()
        assertEquals(0, j.bufferedMs); assertNull(j.pull(1_000))
    }

    @Test fun aSilentChunkIsNotQueued() { val j = PcmJitter(24_000); j.push(ByteArray(0)); assertEquals(0, j.bufferedMs) }

    @Test fun framesKeepTheirBytesExactly() {
        val j = PcmJitter(24_000, prebufferMs = 0); val src = ByteArray(480) { (it % 251).toByte() }
        j.push(src); assertArrayEquals(src, j.pull(1_000))
    }
}
