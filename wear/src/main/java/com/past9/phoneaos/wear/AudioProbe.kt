package com.past9.phoneaos.wear

import com.past9.phoneaos.wearproto.WatchAudioCaps

/**
 * What this watch can do for a call, decided from what the system reports (pure: the Android side only reads the numbers).
 * A watch may have no speaker at all (some have none), a speaker, or earbuds, a headset or a hearing aid connected to it; and a mic
 * that reports itself and records nothing (see [MicPolicy]), so the mic is judged from what it records, never from the feature flag.
 */
object AudioProbe {
    // AudioDeviceInfo.TYPE_*, spelled out so this runs in a plain JVM test.
    const val TYPE_BUILTIN_SPEAKER = 2
    const val TYPE_WIRED_HEADSET = 3
    const val TYPE_WIRED_HEADPHONES = 4
    const val TYPE_BLUETOOTH_SCO = 7
    const val TYPE_BLUETOOTH_A2DP = 8
    const val TYPE_USB_HEADSET = 22
    const val TYPE_HEARING_AID = 23
    const val TYPE_BLE_HEADSET = 26
    const val TYPE_BLE_SPEAKER = 27

    private val earbudTypes = setOf(TYPE_WIRED_HEADSET, TYPE_WIRED_HEADPHONES, TYPE_BLUETOOTH_SCO, TYPE_BLUETOOTH_A2DP, TYPE_USB_HEADSET, TYPE_HEARING_AID, TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER)

    data class Out(val builtIn: Boolean, val earbuds: Boolean) { val any get() = builtIn || earbuds }

    /** A built-in speaker counts only on a watch that says it has audio output; earbuds count wherever they are connected. */
    fun outputs(hasAudioOutputFeature: Boolean, outputTypes: List<Int>): Out =
        Out(builtIn = hasAudioOutputFeature && TYPE_BUILTIN_SPEAKER in outputTypes, earbuds = outputTypes.any { it in earbudTypes })

    fun caps(micUsable: Boolean, out: Out): WatchAudioCaps = WatchAudioCaps(mic = micUsable, speaker = out.any, earbuds = out.earbuds && !out.builtIn)
}

/**
 * A mic that reports itself and records digital silence stays silent during a call too. A real microphone always has a noise floor,
 * so a stretch of windows that never rise above [MicPolicy.DEAD_PEAK] marks it dead and the call moves to the phone's mic.
 */
class CallMicHealth(private val windowsToDecide: Int = 30) {
    private var quiet = 0
    var dead = false; private set

    /** One 100 ms window's peak. True the moment the mic is judged dead. A muted window says nothing about the mic. */
    fun feed(peak: Int, muted: Boolean): Boolean {
        if (muted || dead) return false
        if (peak > MicPolicy.DEAD_PEAK) { quiet = 0; return false }
        quiet++
        if (quiet >= windowsToDecide) { dead = true; return true }
        return false
    }
}
