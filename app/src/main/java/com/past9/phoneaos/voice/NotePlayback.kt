package com.past9.phoneaos.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Physical waveform coordinates to milliseconds, including mirrored layouts. */
fun noteSeek(x: Float, width: Float, duration: Int, rtl: Boolean): Int {
    if (!x.isFinite() || !width.isFinite() || width <= 0 || duration <= 0) return 0
    val fraction = (x / width).coerceIn(0f, 1f)
    return ((if (rtl) 1f - fraction else fraction) * duration).toInt().coerceIn(0, duration)
}

fun noteTime(ms: Int): String = "%d:%02d".format(java.util.Locale.ROOT, ms.coerceAtLeast(0) / 60000, ms.coerceAtLeast(0) / 1000 % 60)
fun noteTimeParts(ms: Int): Pair<Int, Int> = (ms.coerceAtLeast(0) / 60000) to (ms.coerceAtLeast(0) / 1000 % 60)

fun shouldPauseNotesOnStop(changingConfigurations: Boolean, interactive: Boolean): Boolean = !changingConfigurations && interactive

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
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
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
        if (active) return true
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
    fun pauseAll() {
        val pause = pauseOwner
        owner = null; pauseOwner = null
        pause?.invoke()
    }
}

/** One shared note instance for autoplay and its chat bubble; detached notes keep their place. */
class NotePlayers(context: Context) {
    private val app = context.applicationContext
    private val preparationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    private data class Entry(val player: NotePlayback, var attached: Int = 0,
        var path: String = "", var preparation: kotlinx.coroutines.Deferred<Boolean>? = null)
    private val entries = LinkedHashMap<Long, Entry>(16, 0.75f, true)
    fun get(id: Long): NotePlayback {
        val entry = entries.getOrPut(id) { Entry(NotePlayback(app)) }
        if (entries.size > 64) {
            val iterator = entries.entries.iterator()
            while (entries.size > 64 && iterator.hasNext()) {
                val candidate = iterator.next()
                if (candidate.key != id && candidate.value.attached == 0 && !candidate.value.player.playing && !candidate.value.player.preparing) {
                    candidate.value.player.close(); iterator.remove()
                }
            }
        }
        return entry.player
    }
    suspend fun prepare(id: Long, path: String): Boolean {
        get(id)
        val entry = entries.getValue(id)
        val current = entry.preparation
        val work = if (entry.path == path && current != null && !current.isCompleted) current else {
            entry.preparation?.cancel()
            entry.path = path
            preparationScope.async { entry.player.prepare(path) }.also { entry.preparation = it }
        }
        return work.await()
    }
    fun play(id: Long, path: String, failed: (Throwable) -> Unit) {
        preparationScope.launch {
            try { if (prepare(id, path)) get(id).play() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { failed(e) }
        }
    }
    fun attach(id: Long) { get(id); entries.getValue(id).attached++ }
    fun detach(id: Long, changingConfigurations: Boolean = false, interactive: Boolean = true) {
        entries[id]?.let { entry ->
            entry.attached = (entry.attached - 1).coerceAtLeast(0)
            // View recycling does not own playback. Activity stop and explicit actions do.
        }
    }
    fun stopAll() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { stopAll() }; return
        }
        NotePlayback.pauseAll()
        entries.values.forEach { it.preparation?.cancel(); if (it.player.preparing) it.player.release() else it.player.pause() }
    }
    suspend fun autoPlay(id: Long, path: String): NotePlayback {
        val note = get(id)
        if (prepare(id, path)) note.play()
        return note
    }
}

class NotePlayback(context: Context) {
    companion object {
        private val coordinator = NotePlaybackCoordinator()
        fun pauseAll() {
            if (Looper.myLooper() == Looper.getMainLooper()) coordinator.pauseAll()
            else Handler(Looper.getMainLooper()).post { coordinator.pauseAll() }
        }
    }
    private val app = context.applicationContext
    var playing by mutableStateOf(false); private set
    var duration by mutableStateOf(0); private set
    var position by mutableStateOf(0); private set
    var started by mutableStateOf(false); private set
    var error by mutableStateOf(false); private set
    var focusBlocked by mutableStateOf(false); private set
    var preparing by mutableStateOf(false); private set
    private var ready = false
    private var pendingPlay = false
    private var sourcePath = ""
    private var generation = 0
    private var seekTarget: Int? = null
    private var inFlightSeek: Int? = null
    private var player: MediaPlayer? = null
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progress = object : Runnable {
        override fun run() {
            if (!playing) return
            tick()
            if (playing) progressHandler.postDelayed(this, 100)
        }
    }
    private var noisyRegistered = false
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }
    private fun watchNoisy() {
        if (!noisyRegistered) {
            ContextCompat.registerReceiver(app, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
            noisyRegistered = true
        }
    }
    private fun unwatchNoisy() {
        if (noisyRegistered) { app.unregisterReceiver(noisy); noisyRegistered = false }
    }
    private val controls = NoteMediaControls(app, ::pause)
    private val focus = NoteFocus(AndroidNoteFocus(app), ::pauseForFocus, {
        runCatching { startReadyPlayer() }.onFailure { fail() }
    }, { level -> runCatching { player?.setVolume(level, level) }; Unit })

    suspend fun prepare(path: String): Boolean {
        if (sourcePath == path && (ready || preparing)) return true
        val same = sourcePath == path
        val savedPosition = if (same) position else 0
        val savedDuration = if (same) duration else 0
        val savedStarted = same && started
        release()
        sourcePath = path; position = savedPosition; duration = savedDuration; started = savedStarted
        error = false; focusBlocked = false; preparing = true
        val ownGeneration = generation
        try {
            val metadataDuration = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val metadata = android.media.MediaMetadataRetriever()
                try {
                    metadata.setDataSource(path)
                    metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
                } finally { metadata.release() }
            }
            if (generation != ownGeneration) return false
            duration = metadataDuration.takeIf { it > 0 } ?: savedDuration
            if (duration > 0) position = position.coerceIn(0, duration)
            val next = MediaPlayer()
            player = next
            next.setWakeMode(app, android.os.PowerManager.PARTIAL_WAKE_LOCK)
            next.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            next.setDataSource(path)
            next.setOnSeekCompleteListener { if (player === it) completeSeek() }
            next.setOnPreparedListener {
                if (player !== it) return@setOnPreparedListener
                ready = true; preparing = false
                duration = it.duration.takeIf { value -> value > 0 } ?: duration
                val pendingSeek = seekTarget
                if (pendingSeek != null) {
                    if (pendingSeek in 1 until duration) issueSeek(pendingSeek) else seekTarget = null
                } else if (position > 0 && position < duration) seek(position)
                if (pendingPlay) { pendingPlay = false; runCatching { play() }.onFailure { fail() } }
            }
            next.setOnErrorListener { _, _, _ -> if (player === next) fail(); true }
            next.setOnCompletionListener {
                if (player !== it) return@setOnCompletionListener
                progressHandler.removeCallbacks(progress)
                playing = false; position = duration.coerceAtLeast(0)
                inFlightSeek = null; seekTarget = null
                unwatchNoisy(); controls.hide(); focus.stop(); coordinator.release(this)
            }
            next.prepareAsync()
            return true
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (generation == ownGeneration) release()
            throw e
        } catch (e: Exception) {
            if (generation == ownGeneration) fail()
            throw e
        }
    }
    private fun fail() { release(); error = true }
    private fun startReadyPlayer() {
        if (!ready) return
        player?.start()
        playing = true; started = true; focusBlocked = false
        progressHandler.removeCallbacks(progress)
        progressHandler.postDelayed(progress, 100)
        watchNoisy()
        controls.show(position, duration)
    }
    fun play() {
        if (playing) return
        if (!ready && !preparing) return
        coordinator.claim(this, ::pause)
        Tts.stop()
        if (!ready) { pendingPlay = true; return }
        error = false
        if (!focus.play()) {
            focusBlocked = true; focus.stop(); coordinator.release(this)
            return
        }
        if (duration > 0 && position >= duration) seek(0)
        try { startReadyPlayer() } catch (e: Exception) { fail(); throw e }
    }
    private fun pauseForFocus() {
        pendingPlay = false
        progressHandler.removeCallbacks(progress)
        if (ready && !error && playing) player?.let {
            runCatching {
                if (it.isPlaying) it.pause()
                if (inFlightSeek == null && seekTarget == null) position = it.currentPosition.coerceAtLeast(0)
            }
        }
        playing = false; unwatchNoisy(); controls.hide()
    }
    fun pause() { pauseForFocus(); focus.stop(); coordinator.release(this) }
    fun seek(ms: Int) {
        if (duration <= 0) return
        val target = ms.coerceIn(0, duration)
        position = target; started = true; seekTarget = target
        if (ready && inFlightSeek == null) issueSeek(target)
    }
    private fun issueSeek(target: Int) {
        inFlightSeek = target
        player?.seekTo(target)
    }
    private fun completeSeek() {
        val completed = inFlightSeek ?: return
        val latest = seekTarget ?: completed
        inFlightSeek = null
        if (latest != completed) issueSeek(latest)
        else { seekTarget = null; position = latest }
    }
    fun tick() {
        if (playing && inFlightSeek == null && seekTarget == null)
            runCatching { position = player?.currentPosition?.coerceAtLeast(0) ?: position }.onFailure { fail() }
    }
    /** Release native resources and focus, keeping position for a recycled bubble or rotation. */
    fun release() {
        pause(); generation++
        player?.release(); player = null
        ready = false; preparing = false; inFlightSeek = null; seekTarget = null
    }
    fun close() {
        release(); sourcePath = ""; position = 0; duration = 0; started = false
        error = false; focusBlocked = false
    }
}
