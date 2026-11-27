package com.past9.phoneaos.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.PlaybackState
import android.util.Log
import com.past9.phoneaos.media.MediaSessionView
import com.past9.phoneaos.media.Sessions

/**
 * Other sound (a song, a podcast, a video) steps aside while the agent listens or talks, and comes back after.
 *
 * 27 Nov 2026: he was listening to a song, started a call with the agent, and it "acted stupid". The call asked for
 * audio focus only once Gemini Live had connected, never looked at the answer, and trusted every player to pause by
 * itself. A player that keeps going on a transient focus loss (several do) left the song in the microphone: the
 * phone's echo canceller only removes the agent's own voice, so Live heard music the whole time, its turn detection
 * never saw a pause and it talked over itself or never answered.
 *
 * Now: focus is asked for the moment the call (or the listening) starts, and [enforce] checks a moment later. Anything
 * still playing is paused through its media session, and only what this hold paused is played again on [release].
 * Everything goes through [Port], so the rules run in a plain JVM test.
 */
class MediaHold(private val port: Port) {

    interface Port {
        /** Asks for transient focus for speech; true when it was granted. */
        fun requestFocus(): Boolean
        fun abandonFocus()
        /** True while some app's audio is playing. */
        fun musicActive(): Boolean
        /** The media sessions open right now, or null when the app may not see them (no notification access). */
        fun sessions(): List<MediaSessionView>?
        /** This app's own package: its own sessions (voice notes) are never touched. */
        val ownPackage: String
    }

    /** What the hold did, for the log and the call's status. */
    data class Outcome(val focus: Boolean, val paused: List<String>, val stillPlaying: Boolean, val canSeeSessions: Boolean)

    private var held = false
    private var focus = false
    private val pausedByUs = mutableListOf<MediaSessionView>()

    @get:Synchronized val holding: Boolean get() = held

    /** Asks for focus. Safe to call more than once: only the first call asks. */
    @Synchronized fun take(): Boolean {
        if (held) return focus
        held = true
        focus = runCatching { port.requestFocus() }.getOrDefault(false)
        Log.i(TAG, "focus ${if (focus) "granted" else "refused"}")
        return focus
    }

    /**
     * A moment after [take]: whatever is still playing is paused through its session, so nothing plays into the
     * microphone. Only sessions that are really playing, never this app's own. Does nothing once released.
     */
    @Synchronized fun enforce(): Outcome {
        if (!held) return Outcome(focus, emptyList(), false, true)
        val active = runCatching { port.musicActive() }.getOrDefault(false)
        val list = runCatching { port.sessions() }.getOrNull()
        if (!active) return Outcome(focus, pausedByUs.map { it.pkg }, false, list != null)
        val playing = list.orEmpty().filter { it.state == PlaybackState.STATE_PLAYING && it.pkg != port.ownPackage && pausedByUs.none { p -> p === it } }
        playing.forEach { s -> if (runCatching { s.pause() }.isSuccess) pausedByUs += s }
        val out = Outcome(focus, pausedByUs.map { it.pkg }, list == null, list != null)
        Log.i(TAG, "enforce: music was still playing, paused ${out.paused}${if (list == null) " (no access to media sessions)" else ""}")
        return out
    }

    /**
     * The call or the listening is over: focus goes back (players that paused for it carry on by themselves), and
     * what this hold paused is played again, unless it is already playing or he started something else meanwhile.
     * Runs once; later calls do nothing.
     */
    @Synchronized fun release() {
        if (!held) return
        held = false
        runCatching { if (focus) port.abandonFocus() }
        focus = false
        val now = runCatching { port.sessions() }.getOrNull()
        // He started another player meanwhile: that is what he wants to hear, so nothing old is resumed over it.
        val otherPlaying = now.orEmpty().any { s -> s.state == PlaybackState.STATE_PLAYING && pausedByUs.none { it.pkg == s.pkg } && s.pkg != port.ownPackage }
        if (!otherPlaying) pausedByUs.forEach { s ->
            val current = now?.firstOrNull { it.pkg == s.pkg } ?: s
            if (current.state == PlaybackState.STATE_PAUSED) runCatching { current.play() }
        }
        pausedByUs.clear()
    }

    companion object {
        private const val TAG = "AosMediaHold"
        /** How long players get to pause by themselves before the hold pauses them. */
        const val ENFORCE_AFTER_MS = 700L

        /** The phone's own focus and media sessions. [context] null: focus only (nothing can be paused by session). */
        fun android(context: Context?, am: AudioManager): MediaHold = MediaHold(AndroidPort(context?.applicationContext, am))
    }

    private class AndroidPort(private val context: Context?, private val am: AudioManager) : Port {
        private var request: AudioFocusRequest? = null
        override val ownPackage: String = context?.packageName.orEmpty()

        override fun requestFocus(): Boolean {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener { change -> Log.i(TAG, "focus change $change") }
                .build()
            request = req
            return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }

        override fun abandonFocus() { request?.let { am.abandonAudioFocusRequest(it) }; request = null }

        override fun musicActive(): Boolean = am.isMusicActive

        override fun sessions(): List<MediaSessionView>? {
            val c = context ?: return null
            return (com.past9.phoneaos.media.AndroidMediaDeps(c).sessions() as? Sessions.Ok)?.list
        }
    }
}
