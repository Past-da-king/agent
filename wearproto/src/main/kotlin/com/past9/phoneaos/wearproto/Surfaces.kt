package com.past9.phoneaos.wearproto


/**
 * The data sources the watch face and other apps can show (docs/wear.md, "Complications"): what the agent is doing, how many
 * approvals wait, the next task, and a tap-to-talk. The Android side turns these values into complication data in the watch's own
 * language (strings.xml); here only what is true is decided, so it is tested on the JVM.
 */
data class TaskView(val id: Long, val title: String, val dueAt: Long = 0L)

enum class AgentStatus { IDLE, WORKING, NEEDS_YOU, IN_CALL, OFFLINE }

object FaceData {
    /** Most watch faces show about seven characters in a short text slot. */
    const val SHORT = 7

    fun status(s: WatchState?, callLive: Boolean = s?.callLive == true): AgentStatus = when {
        s == null -> AgentStatus.IDLE
        callLive -> AgentStatus.IN_CALL
        s.link == "offline" -> AgentStatus.OFFLINE
        s.pending.isNotEmpty() -> AgentStatus.NEEDS_YOU
        s.working -> AgentStatus.WORKING
        else -> AgentStatus.IDLE
    }

    /** How many things need him (0 = none), for the approvals count. */
    fun waiting(s: WatchState?): Int = s?.pending?.size ?: 0

    /** Where a tap on a face goes: the first waiting card, else the talk screen. */
    fun approvalsGo(s: WatchState?): String = if (waiting(s) > 0) "approvals" else "talk"

    /** The next task as a face shows it: [short] fits the small slot, [long] the wide one. */
    data class TaskFace(val short: String, val long: String, val hasTask: Boolean)

    fun nextTask(s: WatchState?): TaskFace {
        val t = s?.nextTask ?: return TaskFace("", "", false)
        val title = t.title.trim()
        if (title.isEmpty()) return TaskFace("", "", false)
        return TaskFace(shortWord(title), Limits.clip(title, 40), true)
    }

    /** The first word that fits, or its start: "Send invoice to Shey" -> "Send". */
    fun shortWord(title: String, max: Int = SHORT): String {
        val first = title.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        return if (first.length <= max) first else first.take(max - 1) + "…"
    }

    /** A number for a short slot: 1..99, "99+" beyond. */
    fun count(n: Int): String = if (n > 99) "99+" else n.toString()
}

/** One app that can be opened from the watch's launcher. */
data class AppEntry(val pkg: String, val label: String, val activity: String)

/**
 * The launcher's app list: alphabetical (not by case), our own app first, apps the user hid left out, a package listed once. The
 * recents are the apps opened from this launcher most recently (an app cannot read the system's recents, see [LaunchLog]).
 */
object AppGrid {
    fun order(apps: List<AppEntry>, self: String, hidden: Set<String> = emptySet()): List<AppEntry> {
        val seen = HashSet<String>()
        val unique = apps.filter { it.pkg !in hidden && seen.add(it.pkg) }
        val mine = unique.filter { it.pkg == self }
        val rest = unique.filter { it.pkg != self }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        return mine + rest
    }

    fun recents(apps: List<AppEntry>, log: LaunchLog, n: Int = 4): List<AppEntry> {
        val byPkg = apps.associateBy { it.pkg }
        return log.recent(n + 4).mapNotNull { byPkg[it] }.take(n)
    }
}

/**
 * Apps opened from this launcher, newest first. Wear OS has no public recents list: the system's recents need the signature-level
 * GET_TASKS, and usage access (UsageStatsManager) needs a special grant in Settings that a watch often does not offer. This is
 * the honest, permission-free answer: what was opened from here.
 */
class LaunchLog(private val keep: Int = 12, initial: List<String> = emptyList()) {
    private val order = ArrayList(initial)
    fun record(pkg: String) { order.remove(pkg); order.add(0, pkg); while (order.size > keep) order.removeAt(order.size - 1) }
    fun recent(n: Int): List<String> = order.take(n)
    fun forget(pkg: String) { order.remove(pkg) }
    fun toJson(): String = org.json.JSONArray(order).toString()
    companion object {
        fun fromJson(text: String?, keep: Int = 12): LaunchLog {
            val a = runCatching { org.json.JSONArray(text ?: "[]") }.getOrDefault(org.json.JSONArray())
            return LaunchLog(keep, (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() })
        }
    }
}

/**
 * Using the agent as the watch's home screen is optional and never forced. This decides what the settings screen offers, from what
 * the watch says it can do, and always keeps a way back to the stock launcher.
 */
object HomeGuide {
    enum class State { OFF, ON_THIS_APP_ASKED, HELD_BY_THIS_APP, HELD_BY_OTHER }
    enum class Step { ENABLE_COMPONENT, REQUEST_ROLE, OPEN_HOME_SETTINGS, OPEN_DEFAULT_APPS, SAY_HOW_TO_PICK, DISABLE_COMPONENT }

    /**
     * [componentOn]: our home screen component is switched on (it is off until he asks). [roleAvailable]/[roleHeld]: the HOME role,
     * where the watch has it. [homeSettings]/[defaultApps]: the system screens that exist on this watch.
     */
    fun stateOf(componentOn: Boolean, roleAvailable: Boolean, roleHeld: Boolean, defaultHome: String, self: String): State = when {
        !componentOn -> State.OFF
        roleHeld || defaultHome == self -> State.HELD_BY_THIS_APP
        defaultHome.isNotBlank() -> State.HELD_BY_OTHER
        else -> State.ON_THIS_APP_ASKED
    }

    /** What "Use as home screen" does, in order, on this watch. */
    fun turnOn(roleAvailable: Boolean, homeSettings: Boolean, defaultApps: Boolean): List<Step> = buildList {
        add(Step.ENABLE_COMPONENT)
        when {
            roleAvailable -> add(Step.REQUEST_ROLE)
            homeSettings -> add(Step.OPEN_HOME_SETTINGS)
            defaultApps -> add(Step.OPEN_DEFAULT_APPS)
            else -> add(Step.SAY_HOW_TO_PICK)
        }
    }

    /**
     * Back to the stock launcher: switching our component off is enough, because the system falls back to the launcher the watch
     * shipped with the moment no other app answers as home; the system screens are offered as well, for a watch that kept our choice.
     */
    fun turnOff(homeSettings: Boolean, defaultApps: Boolean): List<Step> = buildList {
        add(Step.DISABLE_COMPONENT)
        if (homeSettings) add(Step.OPEN_HOME_SETTINGS) else if (defaultApps) add(Step.OPEN_DEFAULT_APPS) else add(Step.SAY_HOW_TO_PICK)
    }
}

