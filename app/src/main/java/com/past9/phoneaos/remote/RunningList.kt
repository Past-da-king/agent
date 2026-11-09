package com.past9.phoneaos.remote

import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.ui.HelperLive
import com.past9.phoneaos.ui.HelperView
import org.json.JSONArray
import org.json.JSONObject

/** One of the computer's projects (data.list 'project'): its icon and its chats, oldest first. */
data class ProjectInfo(val id: String, val name: String, val glyph: String = "", val color: String = "iris", val image: String = "",
                       val hidden: Boolean = false, val chats: List<String> = emptyList()) {
    /** The chat a tap on the project opens: its newest. */
    val latestChat get() = chats.lastOrNull().orEmpty()
}

/** One chat's state on the computer (chat.states). */
data class ChatState(val running: Boolean = false, val waiting: Boolean = false)

/** How much is going on, for the one header line. */
data class RunCounts(val working: Int = 0, val queued: Int = 0, val needsYou: Int = 0) {
    /** Anything really at work or about to be. */
    val running get() = working + queued
}

/** One project's part of the running list (or the main chat's): a card with the helpers under it is one cluster. */
data class RunGroup(val key: String, val name: String, val project: ProjectInfo?, val clusters: List<List<LiveCard>>, val working: Int) {
    val isMain get() = key == RunningList.MAIN
    val cards get() = clusters.flatten()
}

/** One line of the project switcher: the main chat, or a project and the chat a tap opens. */
data class SwitchRow(val key: String, val name: String, val chat: String, val project: ProjectInfo?, val running: Int, val current: Boolean)

/**
 * The running list, one rule for every screen (9 Nov 2026): ALL running work across ALL projects in one list, grouped
 * by project with the place he is in first. It folds with one tap, the choice is kept on the device, and a card that
 * needs him stays in view even when folded. Only work that is really running counts.
 */
object RunningList {
    const val MAIN = "main"
    /** The built-in project that holds old chats. */
    const val ARCHIVE = "earlier-chats"

    fun projects(a: JSONArray?): List<ProjectInfo> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { p ->
            val id = p.optString("id").ifBlank { return@mapNotNull null }
            val icon = p.optJSONObject("icon")
            val chats = p.optJSONArray("chats")?.let { c -> (0 until c.length()).map { c.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
            ProjectInfo(id, p.optString("name").ifBlank { "Project" }, icon?.optString("glyph").orEmpty(), icon?.optString("color").orEmpty().ifBlank { "iris" },
                icon?.optString("image").orEmpty(), p.optBoolean("hidden"), chats)
        }
    }

    fun states(a: JSONArray?): Map<String, ChatState> {
        if (a == null) return emptyMap()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("chat").isNotBlank() }
            .associate { it.optString("chat") to ChatState(it.optBoolean("running"), it.optBoolean("waiting")) }
    }

    /** A card that waits on him: an approval or a question is open, or its chat is done and its agent spoke last. */
    fun needsYou(c: LiveCard, states: Map<String, ChatState> = emptyMap()): Boolean =
        c.waiting || (c.isProject && states[c.projectChat]?.waiting == true)

    /**
     * Really at work: ended, failed, stopped and dead ones never count, nor one that only waits on him. "At work" is
     * the app's one rule (ui/HelperLive.kt), asked about the card the same way the card is drawn (LiveCards.view): a
     * card is here because the computer listed it a moment ago, so its sign of life is now.
     */
    fun live(c: LiveCard, states: Map<String, ChatState> = emptyMap(), at: Long = HelperLive.now()): Boolean =
        !needsYou(c, states) && HelperLive.isLive(probe(c, at), at)

    private fun probe(c: LiveCard, at: Long): HelperView = HelperView(ChatItem(0L, "helper", c.label,
        JSONObject().put("label", c.label).put("state", c.state).put("startedAt", c.startedAt).put("endedAt", c.endedAt).put("beat", at).toString(), c.startedAt), emptyList())

    fun counts(cards: List<LiveCard>, states: Map<String, ChatState> = emptyMap()): RunCounts {
        var working = 0; var queued = 0; var needs = 0
        for (c in cards) when {
            needsYou(c, states) -> needs++
            !live(c, states) -> Unit
            c.queued -> queued++
            else -> working++
        }
        return RunCounts(working, queued, needs)
    }

    /** The header line: only the parts that are not 0. */
    fun header(n: RunCounts): String {
        val parts = ArrayList<String>()
        if (n.working > 0) parts += "${n.working} working"
        if (n.queued > 0) parts += "${n.queued} waiting to start"
        if (n.needsYou > 0) parts += "${n.needsYou} needs you"
        return if (parts.isEmpty()) "Nothing running" else parts.joinToString(", ")
    }

    /**
     * Whether the list is folded. Nothing running: always folded (he can peek at what just finished, which is not
     * kept). Otherwise his own choice, the same on every screen.
     */
    fun collapsed(choice: Boolean, n: RunCounts, peek: Boolean = false): Boolean = if (n.running == 0) !peek else choice

    /** The group key of the place he is in: a project's id from the project or one of its chats, else the main chat. */
    fun currentKey(current: String, projects: List<ProjectInfo>): String {
        if (current.isBlank() || current == MAIN) return MAIN
        return projects.firstOrNull { it.id == current || current in it.chats }?.id ?: current
    }

    private fun keyOf(c: LiveCard, byChat: Map<String, ProjectInfo>, byName: Map<String, ProjectInfo>): String {
        if (c.ownerKind == "project") {
            if (c.ownerId.isNotBlank()) return c.ownerId
            byName[c.ownerName]?.let { return it.id }
        }
        for (chat in listOf(c.projectChat, c.parentChat, c.chat)) if (chat.isNotBlank()) byChat[chat]?.let { return it.id }
        return if (c.ownerKind == "project" && c.ownerName.isNotBlank()) "n:" + c.ownerName else MAIN
    }

    /**
     * Every card, grouped by the project it belongs to (the owner the computer names, else the project whose chats
     * hold its chat, else the main chat). The computer's order is kept, with the place he is in first. A helper's
     * own helpers stay folded under it.
     */
    fun groups(cards: List<LiveCard>, projects: List<ProjectInfo>, current: String = MAIN, states: Map<String, ChatState> = emptyMap()): List<RunGroup> {
        val byChat = HashMap<String, ProjectInfo>()
        projects.forEach { p -> p.chats.forEach { byChat[it] = p } }
        val byId = projects.associateBy { it.id }
        val byName = projects.associateBy { it.name }
        val by = LinkedHashMap<String, MutableList<List<LiveCard>>>()
        val names = HashMap<String, String>()
        for (cluster in groupsOf(cards)) {
            val head = cluster.first()
            val key = keyOf(head, byChat, byName)
            by.getOrPut(key) { mutableListOf() } += cluster
            if (key !in names) names[key] = if (key == MAIN) "Main chat" else byId[key]?.name ?: head.ownerName.ifBlank { "Project" }
        }
        val here = currentKey(current, projects)
        return by.map { (k, cl) -> RunGroup(k, names[k].orEmpty(), byId[k], cl, cl.sumOf { g -> g.count { live(it, states) } }) }
            .sortedByDescending { it.key == here }
    }

    /**
     * The cards a group shows. Folded: only the ones that need him. Open: every cluster with something at work or
     * waiting on him, and the finished ones too when [finished] is asked for.
     */
    fun shown(g: RunGroup, collapsed: Boolean, states: Map<String, ChatState> = emptyMap(), finished: Boolean = false): List<LiveCard> {
        if (collapsed) return g.cards.filter { needsYou(it, states) }
        return g.clusters.filter { cl -> finished || cl.any { live(it, states) || needsYou(it, states) } }.flatten()
    }

    /** The same across every group, in order. */
    fun shown(groups: List<RunGroup>, collapsed: Boolean, states: Map<String, ChatState> = emptyMap(), finished: Boolean = false): List<LiveCard> =
        groups.flatMap { shown(it, collapsed, states, finished) }

    /** How many finished cards the open list keeps behind its "finished" line. */
    fun finishedCount(groups: List<RunGroup>, states: Map<String, ChatState> = emptyMap()): Int =
        groups.sumOf { g -> g.clusters.filter { cl -> cl.none { live(it, states) || needsYou(it, states) } }.sumOf { it.size } }

    /**
     * The switcher's lines: the main chat first, then his projects in the computer's order with the archive last.
     * Hidden projects are left out. Each says how much is running there and which one he is in.
     */
    fun switcher(projects: List<ProjectInfo>, cards: List<LiveCard>, current: String = MAIN, states: Map<String, ChatState> = emptyMap()): List<SwitchRow> {
        val here = currentKey(current, projects)
        val running = groups(cards, projects, current, states).associate { it.key to it.working }
        val seen = projects.filter { !it.hidden }
        val ordered = seen.filter { it.id != ARCHIVE } + seen.filter { it.id == ARCHIVE }
        return listOf(SwitchRow(MAIN, "Main chat", MAIN, null, running[MAIN] ?: 0, here == MAIN)) +
            ordered.map { p -> SwitchRow(p.id, p.name, p.latestChat, p, running[p.id] ?: 0, here == p.id) }
    }
}
