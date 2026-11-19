package com.past9.phoneaos.voice

/**
 * A hook for driving the whole dictation flow on an emulator that has no microphone and no speech recogniser (19 Nov 2026,
 * like WakeEar.testFeed). Only debug builds ever set it (src/debug DictationProbe); in a release build it stays null and
 * the real mic and the real recogniser are used.
 */
object DictationSeam {
    interface Mic { fun start(); fun stop() }
    interface Source {
        /** The live recogniser stand-in: a faint caption as the take plays. */
        fun live(): LiveWords
        /** The mic stand-in: the take's audio in 100 ms blocks at real speed, then quiet until stopped. */
        fun mic(onPcm: (ByteArray, Int) -> Unit): Mic
    }
    @Volatile var source: Source? = null
}
