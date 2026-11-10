package com.past9.phoneaos.voice

import org.json.JSONObject

/** Sound that is played somewhere other than this phone's speaker: the fake microphone of a call in the browser. */
interface ExternalAudio {
    /** 24 kHz mono PCM16, the model's voice, in order. Must not block. */
    fun play(pcm: ByteArray)
    /** The other person talked over the voice: drop what has not played yet. */
    fun flush()
}

/** The part of a Live voice session that a call running outside the phone's own audio needs. [LiveSession] is the real one. */
interface LiveLink {
    fun start()
    fun openExternalAudio(out: ExternalAudio)
    fun sendAudio(pcm: ByteArray): Boolean
    fun announce(text: String)
    fun sendToolResult(id: String, name: String, result: JSONObject)
    fun stop()
}
