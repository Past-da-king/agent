package com.past9.phoneaos.wearproto

/**
 * The phone's side of a watch call, apart from Android: one call from the watch at a time, the plan it runs on, and what to do when
 * the watch goes quiet. The phone's CallController owns the live session; this only decides.
 */
class PhoneCallGate(private val deadAfterMs: Long = 20_000L) {
    enum class Verdict { KEEP, DROP_OUT, END }

    private var id: String? = null
    private var plan: CallPlan? = null
    private val link = LinkWatch(deadAfterMs = deadAfterMs)
    private val audio = LinkWatch(deadAfterMs = deadAfterMs)

    @Synchronized fun active(): Boolean = id != null
    @Synchronized fun currentId(): String? = id
    @Synchronized fun currentPlan(): CallPlan? = plan

    /** A call asked for by the watch. Null when one is already on (the watch is told to wait, nothing changes). */
    @Synchronized fun open(req: CallStart, now: Long): CallPlan? {
        if (id != null) return null
        val p = CallAudioPlan.choose(req)
        id = req.id; plan = p; link.reset(now); audio.reset(now)
        return p
    }

    /** True when [callId] is the call that is on (a late message about an earlier call is ignored). */
    @Synchronized fun owns(callId: String): Boolean = id == callId

    /** Anything from the watch about this call (a beat, a mic frame) proves it is still there. */
    @Synchronized fun heard(callId: String, now: Long) { if (id == callId) link.beat(now) }

    /** A frame of the watch's mic arrived. When the watch is the call's microphone, only these keep the call alive: a watch that still beats but whose mic stream broke is a call nobody can speak into. */
    @Synchronized fun heardAudio(callId: String, now: Long) { if (id == callId) { link.beat(now); audio.beat(now) } }

    @Synchronized fun close(callId: String): Boolean { if (id != callId) return false; id = null; plan = null; return true }

    /**
     * Every second while the call lives. A watch that is the call's microphone or speaker and has been silent too long ends the
     * call (nobody can hear it or speak to it); a short gap is only shown as a drop-out. A call that runs on the phone's own
     * sound carries on whatever the watch does.
     */
    @Synchronized fun judge(now: Long): Verdict {
        val p = plan ?: return Verdict.KEEP
        val worst = if (p.watchMic) maxOf(link.health(now), audio.health(now)) else link.health(now)
        return when (worst) {
            LinkWatch.Health.OK -> Verdict.KEEP
            LinkWatch.Health.DEGRADED -> if (p.endsWhenWatchLost) Verdict.DROP_OUT else Verdict.KEEP
            LinkWatch.Health.DEAD -> if (p.endsWhenWatchLost) Verdict.END else Verdict.KEEP
        }
    }
}

/**
 * Reads the microphone frames from the watch and decides which to forward: nothing while muted, and a frame is never sent in two
 * pieces over the model's 100 ms chunk size. Pure so the framing is tested.
 */
class MicFramer(private val frameBytes: Int = 3_200) {
    private var carry = ByteArray(0)
    /** Appends [pcm] and returns the whole frames that are now complete (the rest is kept for the next call). */
    @Synchronized fun feed(pcm: ByteArray, n: Int = pcm.size): List<ByteArray> {
        val all = ByteArray(carry.size + n); System.arraycopy(carry, 0, all, 0, carry.size); System.arraycopy(pcm, 0, all, carry.size, n)
        val out = ArrayList<ByteArray>()
        var i = 0
        while (all.size - i >= frameBytes) { out += all.copyOfRange(i, i + frameBytes); i += frameBytes }
        carry = all.copyOfRange(i, all.size)
        return out
    }
    @Synchronized fun reset() { carry = ByteArray(0) }
}
