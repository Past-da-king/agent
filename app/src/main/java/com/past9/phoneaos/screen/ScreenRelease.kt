package com.past9.phoneaos.screen

/**
 * What the screen helper does when the hidden display goes away, as plain logic with no Android in it.
 *
 * The rule: nothing of ours is ever moved onto the person's own screen (display 0) unless they pressed Take over (or
 * the agent was told keep_app). When a display is removed with apps still on it, Android moves them to display 0, so
 * the helper removes the tasks FIRST and only then lets the display go, and it does the same when the app that owns
 * the job is gone.
 */
object ScreenRelease {
    /** One task as the system lists it. Display 0 is the person's own screen. */
    data class Row(val id: Int, val display: Int, val pkg: String)

    sealed class Step {
        data class Remove(val task: Int) : Step()
        /** Only for an app the person asked to keep (moveBack in [plan]): it goes to their screen on purpose. */
        data class Keep(val task: Int) : Step()
        object ReleaseDisplay : Step()
    }

    /** Every task on [display] is removed before the display is released; apps named in [moveBack] are kept, by request. The display is always the last step. */
    fun plan(rows: List<Row>, display: Int, moveBack: Set<String>): List<Step> {
        if (display <= 0) return emptyList()
        val mine = rows.filter { it.display == display }
        val keep = mine.filter { it.pkg in moveBack }.map { Step.Keep(it.id) }
        val gone = mine.filter { it.pkg !in moveBack }.map { Step.Remove(it.id) }
        return gone + keep + Step.ReleaseDisplay
    }

    /**
     * Tasks left on a hidden display of ours that this helper no longer owns: a previous run's, or one that was not
     * cleaned. [ours] are the displays that carry our name; [current] is the one this helper has open now, or null.
     * They are removed, never moved to the person's screen.
     */
    fun orphans(rows: List<Row>, ours: Set<Int>, current: Int?): List<Int> =
        rows.filter { it.display > 0 && it.display in ours && it.display != current }.map { it.id }

    /**
     * Knows whether the app that owns the job is still there. The helper counts the app's open connections; when the
     * last one closes, [due] says when to clean up, unless the app connects again within the grace time.
     */
    class ClientWatch(private val graceMs: Long) {
        private var clients = 0
        private var lastGone = -1L
        val connected: Int @Synchronized get() = clients

        @Synchronized fun came() { clients++; lastGone = -1L }
        @Synchronized fun went(now: Long) { if (clients > 0) clients--; if (clients == 0) lastGone = now }
        /** True when nobody has been connected for the whole grace time and there is a display to clean. */
        @Synchronized fun due(now: Long, hasDisplay: Boolean): Boolean = hasDisplay && clients == 0 && lastGone >= 0 && now - lastGone >= graceMs
        @Synchronized fun reset() { lastGone = -1L }
    }

    /** The reasons for which the helper accepts a move onto the person's own screen. Anything else is refused. */
    val MAIN_REASONS = setOf("take_over", "bring_to_screen", "keep_app")
    fun mayMoveToMain(reason: String): Boolean = reason in MAIN_REASONS
}