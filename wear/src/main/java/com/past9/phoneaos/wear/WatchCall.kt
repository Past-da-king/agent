package com.past9.phoneaos.wear

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.past9.phoneaos.wearproto.Ack
import com.past9.phoneaos.wearproto.CallPaths
import com.past9.phoneaos.wearproto.CallPlan
import com.past9.phoneaos.wearproto.CallStart
import com.past9.phoneaos.wearproto.CallView
import com.past9.phoneaos.wearproto.CallWire
import com.past9.phoneaos.wearproto.PcmJitter
import com.past9.phoneaos.wearproto.WatchCallCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A call with the agent from the watch (docs/wear.md, "Call"). The phone runs the live voice session; this is the watch's half:
 * the call's state (a [WatchCallCore], tested apart from Android), the heartbeat, and, when the plan says the watch carries the
 * sound, the microphone stream out and the speaker stream in. Every path ends in [release] exactly once: he ends it, the phone ends
 * it, the link drops, the phone refuses, or it never starts.
 */
class WatchCall(private val app: Context, private val link: PhoneLink, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(WatchCallCore.State())
    val state: StateFlow<WatchCallCore.State> = _state
    private val core = WatchCallCore(release = { id -> release(id) })
    private val acks = MutableSharedFlow<Ack>(replay = 8, extraBufferCapacity = 8)
    private var loop: Job? = null
    @Volatile private var mic: WatchMicStream? = null
    @Volatile private var player: WatchSpeakerPlayer? = null
    @Volatile private var muted = false
    @Volatile private var retryOnPhone = false

    private fun now() = SystemClock.elapsedRealtime()
    private fun publish() { _state.value = core.state }

    /** What this watch can do for a call right now: its mic (judged from what it records) and its speaker or earbuds. */
    fun caps(): com.past9.phoneaos.wearproto.WatchAudioCaps {
        val micOk = MicRecorder.preflight(app) == MicPolicy.Verdict.OK && !MicPolicy.preferFallback(WatchStore.micDeadAt(app), System.currentTimeMillis())
        val am = app.getSystemService(AudioManager::class.java)
        val types = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type } }.getOrDefault(emptyList())
        val out = AudioProbe.outputs(app.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUDIO_OUTPUT), types)
        return AudioProbe.caps(micOk, out)
    }

    fun onAck(a: Ack) { acks.tryEmit(a) }

    /** Place a call. [phoneAudio]: use the phone's sound whatever this watch could do. A second tap while one is on changes nothing. */
    fun start(phoneAudio: Boolean = false) {
        val id = UUID.randomUUID().toString()
        if (!core.begin(id, now())) return
        muted = false; retryOnPhone = false
        publish()
        WatchCallService.start(app, withMic = caps().mic)
        loop = scope.launch { runCatching { run(id, phoneAudio) }.onFailure { Log.w(TAG, "call: ${it.message}"); core.refused(id, it.message.orEmpty()); publish() } }
    }

    private suspend fun run(id: String, phoneAudio: Boolean) {
        val req = CallStart(id, caps(), phoneAudio)
        if (!link.send(CallPaths.START, CallWire.start(req))) { core.refused(id, app.getString(R.string.phone_unreachable)); publish(); return }
        val ack = withTimeoutOrNull(8_000) { acks.first { it.id == id } }
        if (ack == null) { core.refused(id, app.getString(R.string.phone_unreachable)); publish(); return }
        if (!ack.ok) { core.refused(id, ack.reason.ifBlank { app.getString(R.string.call_could_not_start) }); publish(); return }
        var beatAt = 0L
        while (core.state.active && core.state.id == id) {
            core.tick(now())
            if (now() - beatAt >= CallPaths.BEAT_EVERY_MS) { beatAt = now(); link.send(CallPaths.BEAT, CallWire.id(id)) }
            publish()
            delay(500)
        }
        publish()
    }

    /** The phone's choice of where the sound goes: open what the watch has to carry. */
    fun onPlan(p: CallPlan) {
        core.onPlan(p, now()); publish()
        if (!core.state.active || core.state.id != p.id) return
        if (p.watchSpeaker && player == null) player = WatchSpeakerPlayer(app)
        if (p.watchMic && mic == null) mic = WatchMicStream(app, link, p.id, { muted }) { onMicDead(p.id) }.also { it.start() }
    }

    fun onPhone(v: CallView) { core.onPhone(v, now()); publish() }

    /** The phone opened the speaker channel: what comes in plays, after a short fill. */
    fun onSpeakerChannel(channel: ChannelClient.Channel) {
        val id = channel.path.substringAfterLast('/')
        if (core.state.id != id || !core.state.active) { runCatching { Wearable.getChannelClient(app).close(channel) }; return }
        player?.attach(channel)
    }

    /** The agent was cut off: drop what is queued, at once. */
    fun onFlush(id: String) { if (core.state.id == id) player?.flush() }

    fun toggleMute() {
        muted = !muted; core.muted(muted); publish()
        val id = core.state.id
        if (id.isNotEmpty()) scope.launch { link.send(CallPaths.MUTE, CallWire.mute(id, muted)) }
    }

    /** He ended it. The audio is let go at once; the phone is told, and ends its side too if it never hears. */
    fun end() {
        val id = core.state.id
        if (!core.state.active) return
        core.end(); publish()
        scope.launch { link.send(CallPaths.END, CallWire.id(id)) }
    }

    /** The watch mic is silent (a dead one that reports itself): remember it and carry on, on the phone's mic. */
    private fun onMicDead(id: String) {
        if (core.state.id != id || !core.state.active || retryOnPhone) return
        Log.w(TAG, "the watch mic is silent: moving the call to the phone's mic")
        WatchStore.setMicDead(app, true)
        retryOnPhone = true
        end()
        scope.launch { delay(RETRY_AFTER_MS); start() }
    }

    private fun release(id: String) {
        runCatching { mic?.stop() }; mic = null
        runCatching { player?.close() }; player = null
        WatchCallService.stop(app)
    }

    companion object {
        private const val TAG = "WatchCall"
        /** The phone closes its side of an ended call within about a second; a retry waits for that. */
        private const val RETRY_AFTER_MS = 2_500L
    }
}

/** The watch's microphone, streamed to the phone: 100 ms frames of 16 kHz mono PCM16 over a channel, until the call ends. */
internal class WatchMicStream(private val app: Context, private val link: PhoneLink, private val callId: String, private val muted: () -> Boolean, private val onDead: () -> Unit) {
    @Volatile private var running = true
    private val health = CallMicHealth()
    private var thread: Thread? = null

    fun start() { thread = thread(name = "watch-call-mic", isDaemon = true) { run() } }
    fun stop() { running = false; thread?.interrupt() }

    @SuppressLint("MissingPermission")
    private fun run() {
        val client = Wearable.getChannelClient(app)
        var channel: ChannelClient.Channel? = null
        var rec: AudioRecord? = null
        try {
            val node = runBlocking { link.phoneNode() } ?: return
            val rate = CallPaths.MIC_RATE
            val frame = rate / 10
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) { onDead(); return }
            // The voice-communication source brings the platform's echo canceller: the watch's mic and speaker are a few centimetres apart.
            val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, frame * 2 * 4))
            rec = r
            if (r.state != AudioRecord.STATE_INITIALIZED) { onDead(); return }
            channel = Tasks.await(client.openChannel(node, CallPaths.MIC_CHANNEL + "/" + callId), 10, TimeUnit.SECONDS)
            val out = Tasks.await(client.getOutputStream(channel), 10, TimeUnit.SECONDS)
            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { onDead(); return }
            val pcm = ShortArray(frame); val bytes = ByteArray(frame * 2)
            out.use { os ->
                while (running) {
                    var n = 0
                    while (n < frame && running) { val k = r.read(pcm, n, frame - n); if (k <= 0) { Thread.sleep(20); if (!running) break; continue }; n += k }
                    if (n < frame) break
                    var peak = 0
                    val m = muted()
                    for (i in 0 until frame) {
                        // Muted: the frames still flow (they keep the link alive) but carry silence; nothing the mic hears leaves the watch.
                        val v = if (m) 0 else pcm[i].toInt()
                        val a = if (v < 0) -v else v
                        if (a > peak) peak = a
                        bytes[2 * i] = (v and 0xFF).toByte(); bytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
                    }
                    os.write(bytes); os.flush()
                    if (health.feed(peak, m)) { onDead(); break }
                }
            }
        } catch (e: InterruptedException) {
            // stopped
        } catch (e: Exception) {
            if (running) Log.w("WatchCall", "mic stream: ${e.message}")
        } finally {
            runCatching { rec?.stop() }; runCatching { rec?.release() }
            channel?.let { runCatching { client.close(it) } }
        }
    }
}

/** The agent's voice on the watch: what the phone streams is held back a little, then played; cut off means dropped at once. */
internal class WatchSpeakerPlayer(private val app: Context) {
    private val jitter = PcmJitter(CallPaths.SPK_RATE)
    @Volatile private var running = true
    private var track: AudioTrack? = null
    private var focus: AudioFocusRequest? = null
    private val am = app.getSystemService(AudioManager::class.java)
    private var savedMode = AudioManager.MODE_NORMAL
    private var reader: Thread? = null
    private val writer: Thread

    init {
        val min = AudioTrack.getMinBufferSize(CallPaths.SPK_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(CallPaths.SPK_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(min, CallPaths.SPK_RATE / 2))
                .setTransferMode(AudioTrack.MODE_STREAM).build().apply { play() }
        }.getOrNull()
        savedMode = am.mode
        runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
        runCatching {
            val f = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).build()
            am.requestAudioFocus(f); focus = f
        }
        writer = thread(name = "watch-call-spk", isDaemon = true) {
            while (running) {
                val b = jitter.pull(4_800)
                if (b == null) { runCatching { Thread.sleep(10) }; continue }
                runCatching { track?.write(b, 0, b.size) }
            }
        }
    }

    fun attach(channel: ChannelClient.Channel) {
        val client = Wearable.getChannelClient(app)
        reader = thread(name = "watch-call-spk-in", isDaemon = true) {
            try {
                Tasks.await(client.getInputStream(channel), 10, TimeUnit.SECONDS).use { input ->
                    val buf = ByteArray(4_800)
                    while (running) { val n = input.read(buf); if (n < 0) break; if (n > 0) jitter.push(buf.copyOf(n - (n and 1))) }
                }
            } catch (e: Exception) { if (running) Log.w("WatchCall", "speaker stream: ${e.message}") }
            runCatching { client.close(channel) }
        }
    }

    fun flush() { jitter.flush(); runCatching { track?.pause(); track?.flush(); track?.play() } }

    fun close() {
        running = false
        reader?.interrupt()
        runCatching { track?.stop() }; runCatching { track?.release() }; track = null
        runCatching { focus?.let { am.abandonAudioFocusRequest(it) } }
        runCatching { am.mode = savedMode }
    }
}

/**
 * Keeps the call alive when the screen dims or he leaves the app: without a foreground service of the microphone type Android hands
 * a backgrounded app silence instead of the mic. Its notification shows the call and ends it.
 */
class WatchCallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.call_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).putExtra("go", "call").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val end = PendingIntent.getService(this, 2, Intent(this, WatchCallService::class.java).setAction(ACTION_END), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_mic).setContentTitle(getString(R.string.call_ongoing))
            .setOngoing(true).setCategory(Notification.CATEGORY_CALL).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.call_end), end).build()).build()
        // The microphone type needs the mic permission (Android 14 refuses it otherwise), so it is asked for only when the watch mic will be used.
        val type = if (Build.VERSION.SDK_INT >= 30) (if (withMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        runCatching { startForeground(ID, n, type) }.onFailure { runCatching { startForeground(ID, n) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END) { (application as WatchApp).call.end(); stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        private const val ID = 4401
        private const val CHANNEL = "call"
        private const val ACTION_END = "end"
        @Volatile private var withMic = true
        fun start(context: Context, withMic: Boolean = true) { this.withMic = withMic; runCatching { context.startForegroundService(Intent(context, WatchCallService::class.java)) }.onFailure { Log.w("WatchCall", "service: ${it.message}") } }
        fun stop(context: Context) { runCatching { context.stopService(Intent(context, WatchCallService::class.java)) } }
    }
}
