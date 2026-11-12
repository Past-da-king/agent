package com.past9.phoneaos.assist

import android.content.Context
import android.util.Log
import com.past9.phoneaos.App
import com.past9.phoneaos.remote.RemoteHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one live voice session, kept apart from the sheet that shows it. The sheet attaches to it and can go away (to a
 * floating bubble) while it keeps listening and talking, held in the foreground by [LiveService]. It also follows the
 * chat rows itself, so an answer, a question or an approval reaches the voice whether or not the sheet is on screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object LiveHost {
    private const val TAG = "LiveHost"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var assistant: LiveAssistant? = null
    private var follow: Job? = null
    private var contextJob: Job? = null

    private val _view = MutableStateFlow(LiveView())
    /** What the sheet and the bubble draw. */
    val view: StateFlow<LiveView> = _view

    private val _minimised = MutableStateFlow(false)
    /** True while the sheet is a bubble. */
    val minimised: StateFlow<Boolean> = _minimised

    private val _ended = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** The session ended: blank when on purpose, else why it could not start or broke. */
    val ended: SharedFlow<String> = _ended

    @Volatile var source: String = AssistEntry.SIDE_KEY
        private set

    val active: Boolean get() = assistant?.active == true

    /** Starts the session if none is running. Needs the microphone permission already. */
    fun start(context: Context, from: String) {
        if (active) return
        val app = context.applicationContext
        val g = App.graph(app)
        source = from
        _view.value = LiveView()
        _minimised.value = false
        AssistEntry.setListening(true)
        LiveService.start(app)
        val a = LiveAssistant(app, g, scope, { source }, { _minimised.value }, { v -> _view.value = v }, { why -> scope.launch { finished(app, why) } })
        assistant = a
        follow?.cancel()
        follow = scope.launch {
            val rows = RemoteHub.session.flatMapLatest { (it?.db ?: g.db).chat().all() }
            val working = RemoteHub.session.flatMapLatest { it?.status ?: g.runtime.status }
            combine(rows, working) { r, w -> r to w.working }.collect { (r, w) -> assistant?.onRows(r, w) }
        }
        // What is going on (questions waiting, agents at work) follows the session: a card changing reaches the voice and the strip.
        contextJob?.cancel()
        contextJob = scope.launch { AssistContextSource.updates(app, g, a.core.refs).distinctUntilChanged().conflate().collect { assistant?.onContext(it) } }
        a.start()
    }

    private fun finished(app: Context, why: String) {
        Log.i(TAG, "ended ${why.take(80)}")
        assistant = null
        follow?.cancel(); follow = null
        contextJob?.cancel(); contextJob = null
        _minimised.value = false
        AssistEntry.setListening(false)
        LiveService.stop(app)
        _ended.tryEmit(why)
    }

    fun end(reason: String = "") { assistant?.end(reason) }

    fun mute(on: Boolean) { assistant?.mute(on) }

    /** He picked which waiting question his answer is for (the choice sheet), or closed the sheet without picking. */
    fun pickAsk(ask: CtxAsk, answer: String) { assistant?.pickAsk(ask, answer) }
    fun dismissChoice() { assistant?.dismissChoice() }

    /** The sheet becomes a bubble. The caller closes the sheet. */
    fun minimise() { if (active) _minimised.value = true }

    /** The sheet is on screen again: no bubble. */
    fun attached() { _minimised.value = false }

    /**
     * The bubble was tapped. The screen is read first, while he is still in the other app, so the sheet that opens over
     * it knows what is there now; then the sheet opens.
     */
    fun expand(context: Context) {
        val app = context.applicationContext
        scope.launch {
            withTimeoutOrNull(1_500) { AssistScreenNow.capture(app) }
            _minimised.value = false
            runCatching { app.startActivity(AssistEntry.intent(app, source)) }.onFailure { Log.w(TAG, "could not open the sheet: ${it.message}") }
        }
    }
}
