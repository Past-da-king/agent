package com.past9.phoneaos.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Physical waveform coordinates to milliseconds, including mirrored layouts. */
fun noteSeek(x: Float, width: Float, duration: Int, rtl: Boolean): Int {
    if (!x.isFinite() || !width.isFinite() || width <= 0 || duration <= 0) return 0
    val fraction = (x / width).coerceIn(0f, 1f)
    return ((if (rtl) 1f - fraction else fraction) * duration).toInt().coerceIn(0, duration)
}

fun noteTime(ms: Int): String = "%d:%02d".format(java.util.Locale.ROOT, ms.coerceAtLeast(0) / 60000, ms.coerceAtLeast(0) / 1000 % 60)

fun noteSpokenTime(ms: Int): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    val minutes = seconds / 60
    val rest = seconds % 60
    val secondPart = "$rest " + if (rest == 1) "second" else "seconds"
    return if (minutes == 0) secondPart else "$minutes " + (if (minutes == 1) "minute" else "minutes") + " $secondPart"
}

interface NoteFocusPort {
    fun request(listener: (Int) -> Unit): Boolean
    fun abandon()
}

class AndroidNoteFocus(context: Context) : NoteFocusPort {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null
    private var generation = 0
    override fun request(listener: (Int) -> Unit): Boolean {
        abandon()
        val ownGeneration = generation
        val next = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { if (generation == ownGeneration) listener(it) }.build()
        request = next
        return manager.requestAudioFocus(next) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
    override fun abandon() {
        generation++
        request?.let { manager.abandonAudioFocusRequest(it) }
        request = null
    }
}

/** Focus state machine is independent of MediaPlayer and tested with a fake port. */
class NoteFocus(private val port: NoteFocusPort, private val pause: () -> Unit,
                private val resume: () -> Unit, private val volume: (Float) -> Unit) {
    private var active = false
    private var resumeOnGain = false
    fun play(): Boolean {
        resumeOnGain = false
        active = port.request(::changed)
        return active
    }
    fun stop() {
        active = false
        resumeOnGain = false
        port.abandon()
        volume(1f)
    }
    fun changed(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { pause(); stop() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnGain = resumeOnGain || active
                active = false
                pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                volume(1f)
                if (resumeOnGain) { resumeOnGain = false; active = true; resume() }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> if (active) volume(0.2f)
        }
    }
}

/** A shared coordinator pauses the previous bubble, including one waiting to regain focus. */
class NotePlaybackCoordinator {
    private var owner: Any? = null
    private var pauseOwner: (() -> Unit)? = null
    fun claim(next: Any, pause: () -> Unit) {
        if (owner !== next) pauseOwner?.invoke()
        owner = next
        pauseOwner = pause
    }
    fun release(previous: Any) {
        if (owner === previous) { owner = null; pauseOwner = null }
    }
}

class NotePlayback(context: Context) {
    companion object { private val coordinator = NotePlaybackCoordinator() }
    var playing by mutableStateOf(false); private set
    var duration by mutableStateOf(0); private set
    var position by mutableStateOf(0); private set
    var started by mutableStateOf(false); private set
    var error by mutableStateOf(false); private set
    private var ready = false
    private var pendingPlay = false
    private var player: MediaPlayer? = null
    private val focus = NoteFocus(AndroidNoteFocus(context), ::pauseForFocus, {
        player?.start(); playing = true
    }, { level -> runCatching { player?.setVolume(level, level) }; Unit })

    suspend fun prepare(path: String) {
        close()
        duration = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val metadata = android.media.MediaMetadataRetriever()
            try {
                metadata.setDataSource(path)
                metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
            } finally { metadata.release() }
        }
        val next = MediaPlayer()
        try {
            next.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            next.setDataSource(path)
            next.setOnPreparedListener {
                ready = true; duration = it.duration
                if (position > 0) it.seekTo(position)
                if (pendingPlay) { pendingPlay = false; runCatching { play() }.onFailure { error = true } }
            }
            next.setOnErrorListener { _, _, _ ->
                error = true; playing = false; pendingPlay = false; focus.stop()
                coordinator.release(this)
                true
            }
            next.setOnCompletionListener {
                playing = false; position = duration; focus.stop()
                coordinator.release(this)
            }
            player = next
            next.prepareAsync()
        } catch (e: Exception) { player = null; ready = false; next.release(); error = true; throw e }
    }
    fun play() {
        coordinator.claim(this, ::pause)
        if (!ready) { pendingPlay = true; return }
        val p = player ?: return
        error = false
        if (!focus.play()) { coordinator.release(this); error = true; return }
        if (position >= duration) seek(0)
        try { p.start(); playing = true; started = true }
        catch (e: Exception) { focus.stop(); coordinator.release(this); throw e }
    }
    private fun pauseForFocus() {
        pendingPlay = false
        if (ready && !error) player?.let { if (it.isPlaying) it.pause(); position = it.currentPosition }
        playing = false
    }
    fun pause() { pauseForFocus(); focus.stop(); coordinator.release(this) }
    fun seek(ms: Int) {
        position = ms.coerceIn(0, duration)
        started = true
        if (ready) player?.seekTo(position)
    }
    fun tick() { if (playing) position = player?.currentPosition ?: position }
    fun close() {
        pause()
        player?.release(); player = null
        position = 0; duration = 0; started = false; ready = false; error = false
    }
}
