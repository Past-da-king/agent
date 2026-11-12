package com.past9.phoneaos.wear

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.past9.phoneaos.Graph
import com.past9.phoneaos.assist.AssistEntry
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.remote.LinkState
import com.past9.phoneaos.remote.RemoteHub
import com.past9.phoneaos.wakeup.WakeHub
import com.past9.phoneaos.wearproto.RelayResult
import com.past9.phoneaos.wearproto.RelaySink
import com.past9.phoneaos.wearproto.WatchRouter
import com.past9.phoneaos.wearproto.WatchState
import com.past9.phoneaos.wearproto.WearPaths
import com.past9.phoneaos.wearproto.Wire
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The phone's half of the watch app (docs/wear.md). It holds no brain of its own: a request from the watch goes through
 * the same calls the phone's own buttons use (runtime.sendAccepted, runtime.answer, runtime.stop), so it works whether the
 * agent runs on the phone or on the computer, and a watch can never approve what the phone could not.
 * It starts only once a watch has said hello, so a phone with no watch pays nothing.
 */
object WatchRelay {
    private const val TAG = "WatchRelay"
    private const val SEEN_KEY = "watch_seen"
    private var job: Job? = null
    @Volatile private var router: WatchRouter? = null
    private val follow = MutableStateFlow<WatchStateBuilder.Follow?>(null)
    private val kick = MutableStateFlow(0)
    @Volatile private var lastSent: ByteArray? = null
    private var ringJob: Job? = null
    /** The watch that last wrote to this phone: where a call's sound and picture go. */
    @Volatile var lastNode: String? = null
        private set

    private var settingsJob: Job? = null

    /** Called when the app's graph is built: if a watch is paired, keep it up to date. */
    fun attach(g: Graph) { WatchPairing.bound(g)?.let { lastNode = it; appContext = g.app; ensureRunning(g) } }

    /** A message came from the paired watch [node]: remember it, and start publishing. (Callers check the pairing first.) */
    fun seen(g: Graph, node: String) {
        lastNode = node; appContext = g.app
        ensureRunning(g)
    }

    /** A watch just paired: it gets the picture, the rings and the customisations from here on. */
    fun paired(g: Graph, node: String) { lastNode = node; appContext = g.app; ensureRunning(g); republish() }

    /** Unpaired from either side: nothing more is published, and nothing about the watch is kept. */
    @Synchronized fun unpaired() {
        job?.cancel(); job = null; ringJob?.cancel(); ringJob = null; settingsJob?.cancel(); settingsJob = null
        lastNode = null; lastSent = null
    }

    fun router(g: Graph): WatchRouter = router ?: synchronized(this) { router ?: WatchRouter(PhoneSink(g)).also { router = it } }

    @Synchronized private fun ensureRunning(g: Graph) {
        if (ringJob?.isActive != true) ringJob = g.scope.launch {
            // What rings on the phone rings the watch: the phone's wake-ups are the source, the watch only follows them.
            runCatching { WakeHub.ringing.collect { WatchRingRelay.push(g.app, it) } }.onFailure { Log.w(TAG, "ring stopped: ${it.message}") }
        }
        // The phone's customisations (agent name, mascot, accent, theme, language, voice) stay in step on the watch while it is paired.
        if (settingsJob?.isActive != true) settingsJob = g.scope.launch {
            runCatching { g.settings.state.collect { WatchPairing.publish(g, it) } }.onFailure { Log.w(TAG, "settings stopped: ${it.message}") }
        }
        if (job?.isActive == true) return
        job = g.scope.launch { runCatching { publish(g) }.onFailure { Log.w(TAG, "stopped: ${it.message}") } }
    }

    /** Publish again even when nothing changed (a watch just opened and may have missed the last item). */
    fun republish() { lastSent = null; kick.value++; WatchPairing.publisher.forget(); WatchRingRelay.republish(); WatchRingRelay.push(appContext ?: return, WakeHub.ringing.value) }
    @Volatile private var appContext: Context? = null

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private suspend fun publish(g: Graph) {
        val rows = RemoteHub.session.flatMapLatest { (it?.db ?: g.db).chat().all() }.map { it.takeLast(120) }
        val link = RemoteHub.session.flatMapLatest { s ->
            if (s == null) flowOf(if (RemoteHub.link.value?.on == true) "offline" else "local")
            else s.state.map { if (it is LinkState.Connected) "pc" else "offline" }
        }
        val working = RemoteHub.session.flatMapLatest { it?.status ?: g.runtime.status }.map { it.working }
        // Who the agent is and where it runs change rarely: read together, so the main combine stays at five flows.
        val who = combine(link, g.settings.state.map { it.agentName }.distinctUntilChanged(), kick) { l, name, _ ->
            l to name.takeIf { it.isNotBlank() && it != "Your agent" }.orEmpty()
        }
        // The next task (for the watch face) and whether a call is live: two small flows read together.
        val tasks = RemoteHub.session.flatMapLatest { (it?.db ?: g.db).tasks().tasks() }.map { WatchStateBuilder.nextTask(it) }.distinctUntilChanged()
        val extras = combine(tasks, g.call.state.map { it.active }.distinctUntilChanged()) { t, c -> t to c }
        combine(rows, working, who, follow, extras) { r, w, (l, name), f, (task, call) ->
            WatchStateBuilder.build(r, w, f, name, l, task, call)
        }.debounce(300).collect { state -> push(g.app, state) }
    }

    private fun push(context: Context, state: WatchState) {
        val bytes = Wire.state(state)
        if (lastSent?.contentEquals(bytes) == true) return
        lastSent = bytes
        // Urgent when something waits for him, so a card reaches the wrist at once.
        val req = PutDataRequest.create(WearPaths.STATE).setData(bytes).apply { if (state.pending.isNotEmpty()) setUrgent() }
        runCatching { Wearable.getDataClient(context).putDataItem(req) }.onFailure { Log.w(TAG, "put failed: ${it.message}") }
    }

    /** The watch's requests, through the phone's own paths. */
    internal class PhoneSink(private val g: Graph) : RelaySink {
        override suspend fun ask(text: String, via: String): RelayResult {
            val before = currentRows(g).lastOrNull()?.id ?: 0L
            return runCatching { g.runtime.sendAccepted(WatchStateBuilder.compose(text, via), emptyList()) }.fold(
                onSuccess = { follow.value = WatchStateBuilder.Follow(before, text); RelayResult(true) },
                onFailure = { RelayResult(false, it.message.orEmpty().ifBlank { "The agent did not take that." }) },
            )
        }

        override suspend fun answer(card: Long, option: String): RelayResult {
            // Only a card that still waits, and only an answer the card itself offers: the watch cannot invent either.
            val check = WatchStateBuilder.checkAnswer(currentRows(g), card, option)
            if (!check.ok) return check
            // The same call the phone's own buttons and its notification buttons make (system/Phone.kt AnswerReceiver).
            g.runtime.answer(card, option)
            runCatching { NotificationManagerCompat.from(g.app).cancel(card.toInt()) }
            return RelayResult(true)
        }

        override suspend fun stop(): RelayResult { g.runtime.stop(); return RelayResult(true) }

        override suspend fun speakOnPhone(): RelayResult {
            // The phone's own assist sheet listens; its words go to the agent and the answer comes back to the watch.
            follow.value = WatchStateBuilder.Follow(currentRows(g).lastOrNull()?.id ?: 0L)
            AssistEntry.open(g.app, AssistEntry.SHORTCUT)
            return RelayResult(true)
        }

        override suspend fun hello() { appContext = g.app; republish() }

        // A call with the agent from the watch, and a ring answered on the watch: see WatchCallBridge and WatchRingRelay.
        override suspend fun callStart(req: com.past9.phoneaos.wearproto.CallStart): RelayResult {
            val to = lastNode ?: return RelayResult(false, "No watch to call from.")
            return WatchCallBridge.start(g, to, req)
        }
        override suspend fun callEnd(id: String) = WatchCallBridge.end(g, id)
        override suspend fun callMute(id: String, on: Boolean) = WatchCallBridge.mute(g, id, on)
        override fun callBeat(id: String) = WatchCallBridge.beat(id)
        override suspend fun ringAnswer(id: String, what: String) = WatchRingRelay.answer(g.app, id, what)
    }

    internal suspend fun currentRows(g: Graph): List<ChatItem> =
        (RemoteHub.session.value?.db ?: g.db).chat().all().first().takeLast(120)
}
