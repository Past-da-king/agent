package com.past9.phoneaos.media

import com.past9.phoneaos.screen.ScreenElement
import kotlinx.coroutines.delay

/** What the learner needs from the background screen. The real one wraps ScreenSession; tests hand in a fake screen. */
interface ScreenOps {
    /** Opens the app on the background screen. Null when it is open there, else why not. */
    suspend fun open(): String?
    fun elements(): List<ScreenElement>
    fun width(): Int
    /** Presses [e]. [generic]: it is "whichever row matched", so a saved step finds it by place, not by words. Null when done, else why not. */
    suspend fun tap(e: ScreenElement, generic: Boolean): String?
    suspend fun type(e: ScreenElement, text: String): String?
    suspend fun settle()
}

sealed interface Learned {
    /** The background screen is switched off. */
    data object Off : Learned
    /** The app is open on the user's own screen: it was left alone. */
    class InFront(val text: String) : Learned
    class Failed(val text: String) : Learned
    /** The steps were done and the app's media session says it plays. */
    /** [clean]: it took one press on a result (and perhaps a Play button), so the steps recorded are the way to do it again. */
    class Playing(val o: MediaVerify.Outcome.Playing, val clean: Boolean = true) : Learned
    class NoAccess(val why: String) : Learned
}

/**
 * Works out how to play something in an app it has never seen, on the background screen: open it, find its search,
 * type the words, press the row that carries them (and a Play button when the row only opened a page), and watch the
 * app's own media session until it plays. It knows no app by name: only what [MediaScreenPlan] reads off the screen.
 * The steps it took are the ones the screen session recorded; the caller saves them as the app's play job.
 */
class MediaScreenLearner(
    private val ops: ScreenOps,
    private val sessions: () -> Sessions,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val waitMs: Long = 6_000,
) {
    suspend fun run(app: MediaApp, query: String): Learned {
        ops.open()?.let { return if (it.contains("user's own screen")) Learned.InFront(it) else Learned.Failed(it) }
        var els = ops.elements()
        var field = MediaScreenPlan.searchField(els)
        if (field == null) {
            val entry = MediaScreenPlan.searchEntry(els) ?: return Learned.Failed("${app.label} shows no search box or search button on the background screen")
            ops.tap(entry, false)?.let { return Learned.Failed(it) }
            els = ops.elements()
            field = MediaScreenPlan.searchField(els) ?: return Learned.Failed("pressed search in ${app.label} but no text box came up")
        }
        ops.type(field, query)?.let { return Learned.Failed(it) }
        ops.settle()
        val tried = mutableSetOf<String>()
        repeat(3) {
            els = ops.elements()
            val box = MediaScreenPlan.searchField(els) ?: field
            val pick = MediaScreenPlan.pickResult(els, query, box, tried, ops.width())
                ?: return Learned.Failed("${app.label} showed no result for \"$query\" on the background screen")
            tried += pick.label
            ops.tap(pick, true)?.let { return Learned.Failed(it) }
            when (val o = MediaVerify.await(sessions, app.pkg, query, waitMs, sleep = sleep)) {
                is MediaVerify.Outcome.Playing -> return Learned.Playing(o, tried.size == 1)
                is MediaVerify.Outcome.NoAccess -> return Learned.NoAccess(o.why)
                else -> {}
            }
            // The row opened a page (an album, an artist, a video page that waits): press its Play.
            MediaScreenPlan.playButton(ops.elements())?.let { b ->
                ops.tap(b, false)?.let { return Learned.Failed(it) }
                when (val o = MediaVerify.await(sessions, app.pkg, query, waitMs, sleep = sleep)) {
                    is MediaVerify.Outcome.Playing -> return Learned.Playing(o, tried.size == 1)
                    is MediaVerify.Outcome.NoAccess -> return Learned.NoAccess(o.why)
                    else -> {}
                }
            }
        }
        return Learned.Failed("pressed the best matches for \"$query\" in ${app.label} but nothing started playing")
    }
}
