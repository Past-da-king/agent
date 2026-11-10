package com.past9.phoneaos.screen

/**
 * The rule that keeps the person's own screen theirs. Before the agent opens an app: is that app the one they have
 * in front? Then it is refused, never moved. After the launch: did the app come up on their screen anyway? Then it
 * is moved to the hidden display at once, and what they had in front is put back. Plain logic, no Android.
 */
object ScreenLanding {
    /** One task as the system lists it; the list is top first per display. Display 0 is the person's screen. */
    data class Task(val id: Int, val display: Int, val pkg: String, val visible: Boolean)

    sealed class Decision {
        /** Nothing of ours is on the person's screen. */
        object Leave : Decision()
        /** The app is open in front of the person: do not launch, move or touch it. */
        data class Refuse(val pkg: String) : Decision()
        /** Tasks of ours that landed on the person's screen go to the hidden display; [restore] is the task they had in front, to bring back when ours pushed it down. */
        data class Fix(val move: List<Int>, val restore: Int?) : Decision()
    }

    /** What the person has in front: the top visible task on their screen. */
    fun front(tasks: List<Task>): Task? = tasks.firstOrNull { it.display == 0 && it.visible }

    fun before(tasks: List<Task>, pkg: String): Decision =
        if (tasks.any { it.display == 0 && it.visible && it.pkg == pkg }) Decision.Refuse(pkg) else Decision.Leave

    fun after(before: List<Task>, after: List<Task>, pkg: String): Decision {
        val was = front(before)
        // The app we opened is on their screen, and it is not what they had in front themselves.
        val stray = after.filter { it.display == 0 && it.pkg == pkg && it.id != was?.id }.map { it.id }
        if (stray.isEmpty()) return Decision.Leave
        val now = front(after)
        val pushed = was != null && now != null && now.id != was.id && now.id in stray && after.any { it.id == was.id }
        return Decision.Fix(stray, if (pushed) was!!.id else null)
    }
}
