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
    /** Presses Back on the background screen. Null when pressed, else why not. */
    suspend fun back(): String? = "Back is not available"
    /** Swipes down on the background screen (folds an expanded player). Null when done, else why not. */
    suspend fun swipeDown(): String? = "Swipe is not available"
    /** Starts the app again from its front door with a clean task (where it was left is forgotten). Null when open there. */
    suspend fun openFresh(): String? = open()
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
    companion object {
        const val FIELD_POLLS = 6
        const val FIELD_POLL_MS = 500L
    }

    private fun hasSearch(els: List<ScreenElement>) = MediaScreenPlan.searchField(els) != null || MediaScreenPlan.searchEntry(els) != null

    /**
     * Gets the app to a state where search can be found. An app often comes back to where it was left: an expanded
     * player or a video page, covering the search. Generic steps only: Back (up to 3 times, looking after each), fold
     * the player with a swipe down, then start the app fresh with a clean task. Null when no search shows after all that.
     * Everything happens on the background screen.
     */
    suspend fun reachSearch(): List<ScreenElement>? {
        var els = ops.elements()
        if (hasSearch(els)) return els
        for (i in 1..3) {
            if (ops.back() != null) break
            els = ops.elements()
            if (hasSearch(els)) return els
            if (els.isEmpty()) break // Back left the app
        }
        if (els.isNotEmpty() && ops.swipeDown() == null) {
            els = ops.elements()
            if (hasSearch(els)) return els
        }
        if (ops.openFresh() != null) return null
        els = ops.elements()
        return els.takeIf { hasSearch(it) }
    }

    /** Waits up to about 3 s for a text box to appear after search was pressed (a search page animates in). */
    private suspend fun awaitField(first: List<ScreenElement>): Pair<List<ScreenElement>, ScreenElement?> {
        var els = first
        for (i in 0..FIELD_POLLS) {
            MediaScreenPlan.searchField(els)?.let { return els to it }
            if (i == FIELD_POLLS) break
            sleep(FIELD_POLL_MS)
            els = ops.elements()
        }
        return els to null
    }

    suspend fun run(app: MediaApp, query: String): Learned {
        ops.open()?.let { return if (it.contains("user's own screen")) Learned.InFront(it) else Learned.Failed(it) }
        var els = reachSearch() ?: return Learned.Failed("${app.label} shows no search box or search button on the background screen, even after Back, folding the player and starting it fresh")
        var field = MediaScreenPlan.searchField(els)
        if (field == null) {
            val entry = MediaScreenPlan.searchEntry(els) ?: return Learned.Failed("${app.label} shows no search box or search button on the background screen")
            ops.tap(entry, false)?.let { return Learned.Failed(it) }
            var waited = awaitField(ops.elements())
            if (waited.second == null) {
                // Pressed, and still no text box: the press may have missed (or closed it again). Press search once more and wait again.
                val again = MediaScreenPlan.searchEntry(waited.first) ?: entry
                ops.tap(again, false)?.let { return Learned.Failed(it) }
                waited = awaitField(ops.elements())
            }
            els = waited.first
            field = waited.second ?: return Learned.Failed("pressed search in ${app.label} (twice, waiting about 3 s each time) but no text box came up")
        }
        ops.type(field, query)?.let { return Learned.Failed(it) }
        ops.settle()
        val tried = mutableSetOf<String>()
        repeat(3) {
            els = ops.elements()
            val box = MediaScreenPlan.searchField(els) ?: field
            val pick = MediaScreenPlan.pickResult(els, query, box, tried, ops.width())
                ?: run { ops.settle(); els = ops.elements(); MediaScreenPlan.pickResult(els, query, MediaScreenPlan.searchField(els) ?: field, tried, ops.width()) }
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
