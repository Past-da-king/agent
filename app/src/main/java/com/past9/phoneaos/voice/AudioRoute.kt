package com.past9.phoneaos.voice

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Where the voice comes out, and which microphone hears him.
 *
 * The session used to set MODE_IN_COMMUNICATION and then `isSpeakerphoneOn = true`, unconditionally.
 * That was written to keep a hands-free call off the earpiece, and it did — but on Android 12+
 * speakerphone-on IS "make the loudspeaker the communication device", which overrides a connected
 * headset. So with earphones in, the agent still talked out of the phone. Reported from the handset
 * 30 Sep 2026: "I'm connecting to my earphones and I want to talk to it. It talks through my speaker."
 *
 * The route is now CHOSEN: a headset if one is there, the loudspeaker only when nothing is, and it is
 * chosen again every time something is plugged in, paired or taken out.
 */
object AudioRoute {

    enum class Kind { BLUETOOTH, WIRED, USB, HEARING_AID, SPEAKER }

    /** What the screen shows: the kind picks the icon, the name is the device's own ("Galaxy Buds FE"). */
    data class Route(val kind: Kind, val name: String) {
        val isHeadset: Boolean get() = kind != Kind.SPEAKER
    }

    val Speaker = Route(Kind.SPEAKER, "Phone speaker")

    /** AudioDeviceInfo.TYPE_* values, spelled out so this file's logic runs in a plain JVM test. */
    const val TYPE_BUILTIN_EARPIECE = 1
    const val TYPE_BUILTIN_SPEAKER = 2
    const val TYPE_WIRED_HEADSET = 3
    const val TYPE_WIRED_HEADPHONES = 4
    const val TYPE_BLUETOOTH_SCO = 7
    const val TYPE_USB_HEADSET = 22
    const val TYPE_HEARING_AID = 23
    const val TYPE_BLE_HEADSET = 26

    /**
     * Most preferred first. Something physically plugged in wins over something paired, because
     * plugging a cable in is the more deliberate act; and BLE audio wins over classic SCO because when
     * a pair of earbuds offers both, LE is the better link. The earpiece is deliberately absent — this
     * is never a phone held to the ear.
     */
    private val preference = listOf(
        TYPE_WIRED_HEADSET, TYPE_USB_HEADSET, TYPE_WIRED_HEADPHONES,
        TYPE_BLE_HEADSET, TYPE_BLUETOOTH_SCO, TYPE_HEARING_AID,
        TYPE_BUILTIN_SPEAKER,
    )

    /** Index into [types] of the device the voice should use, or -1 if none of them is usable. */
    fun pick(types: List<Int>): Int {
        for (want in preference) {
            val i = types.indexOf(want)
            if (i >= 0) return i
        }
        return -1
    }

    fun kindOf(type: Int): Kind = when (type) {
        TYPE_BLE_HEADSET, TYPE_BLUETOOTH_SCO -> Kind.BLUETOOTH
        TYPE_WIRED_HEADSET, TYPE_WIRED_HEADPHONES -> Kind.WIRED
        TYPE_USB_HEADSET -> Kind.USB
        TYPE_HEARING_AID -> Kind.HEARING_AID
        else -> Kind.SPEAKER
    }

    /** A name worth showing. The phone reports its own model for built-in and wired outputs. */
    fun label(type: Int, productName: String): Route {
        val kind = kindOf(type)
        val name = when (kind) {
            Kind.SPEAKER -> Speaker.name
            Kind.WIRED -> "Wired earphones"
            Kind.USB -> productName.trim().ifEmpty { "USB headset" }
            Kind.HEARING_AID -> productName.trim().ifEmpty { "Hearing aid" }
            Kind.BLUETOOTH -> productName.trim().ifEmpty { "Bluetooth earphones" }
        }
        return Route(kind, name)
    }
}

/**
 * Holds the route for one session: picks it, keeps it right as devices come and go, and puts the
 * phone back the way it found it.
 */
class AudioRouter(private val am: AudioManager, private val onRoute: (AudioRoute.Route) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var legacySco = false

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = apply()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = apply()
    }

    fun start() {
        if (started) return
        started = true
        // Communication mode is still required: it is what enables the platform echo canceller on the
        // capture side, and what makes a Bluetooth headset's microphone reachable at all.
        runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
        apply()
        // The callback also fires once with everything already connected, which costs one redundant
        // apply() and saves having a separate "initial" path that could disagree with this one.
        runCatching { am.registerAudioDeviceCallback(devices, main) }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { am.unregisterAudioDeviceCallback(devices) }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                if (legacySco) {
                    am.isBluetoothScoOn = false
                    am.stopBluetoothSco()
                }
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }
        }
        legacySco = false
        // Leaving the device in MODE_IN_COMMUNICATION after a call makes every other app's audio
        // sound wrong until something else resets it.
        runCatching { am.mode = AudioManager.MODE_NORMAL }
    }

    private fun apply() {
        if (!started) return
        val route = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) modern() else legacy()
        }.getOrElse {
            Log.w(TAG, "routing failed: ${it.message}")
            // Never leave him on the earpiece because a route call threw.
            @Suppress("DEPRECATION")
            runCatching { am.isSpeakerphoneOn = true }
            AudioRoute.Speaker
        }
        Log.i(TAG, "route -> ${route.kind} ${route.name}")
        onRoute(route)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun modern(): AudioRoute.Route {
        val available = am.availableCommunicationDevices
        val i = AudioRoute.pick(available.map { it.type })
        val target = available.getOrNull(i)
        // Already there: asking again would tear a Bluetooth voice link down and bring it back up,
        // which is an audible gap every time some unrelated device connects.
        if (target != null && am.communicationDevice?.id == target.id) {
            return AudioRoute.label(target.type, target.productName?.toString().orEmpty())
        }
        if (target != null && am.setCommunicationDevice(target)) {
            return AudioRoute.label(target.type, target.productName?.toString().orEmpty())
        }
        // The headset refused (a Bluetooth link that would not come up) or nothing was listed at
        // all: fall back to the loudspeaker rather than the platform default, which is the earpiece.
        val speaker = available.firstOrNull { it.type == AudioRoute.TYPE_BUILTIN_SPEAKER }
        if (speaker == null || !am.setCommunicationDevice(speaker)) {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = true
        }
        return AudioRoute.Speaker
    }

    /** Android 10 and 11, which have no communication-device API. */
    @Suppress("DEPRECATION")
    private fun legacy(): AudioRoute.Route {
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val i = AudioRoute.pick(outs.map { it.type })
        val target = outs.getOrNull(i)
        val kind = target?.let { AudioRoute.kindOf(it.type) } ?: AudioRoute.Kind.SPEAKER
        if (kind == AudioRoute.Kind.BLUETOOTH) {
            am.isSpeakerphoneOn = false
            am.startBluetoothSco()
            am.isBluetoothScoOn = true
            legacySco = true
        } else {
            if (legacySco) {
                am.isBluetoothScoOn = false
                am.stopBluetoothSco()
                legacySco = false
            }
            // Wired and USB route themselves once the loudspeaker is not being forced.
            am.isSpeakerphoneOn = kind == AudioRoute.Kind.SPEAKER
        }
        return if (target == null) AudioRoute.Speaker
        else AudioRoute.label(target.type, target.productName?.toString().orEmpty())
    }

    private companion object {
        const val TAG = "AosAudioRoute"
    }
}
