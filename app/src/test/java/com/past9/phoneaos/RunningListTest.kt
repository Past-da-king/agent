package com.past9.phoneaos

import com.past9.phoneaos.remote.CardTap
import com.past9.phoneaos.remote.LiveCard
import com.past9.phoneaos.remote.LiveCards
import com.past9.phoneaos.remote.RunCounts
import com.past9.phoneaos.remote.RunningFold
import com.past9.phoneaos.remote.RunningList
import com.past9.phoneaos.remote.SwitchRow
import com.past9.phoneaos.remote.tapOf
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The running list (29 Oct 2026): a project card goes straight to its chat, a helper card opens its sheet with Go to
 * chat, a project chat shows only its project's cards (the main thread all), a swiped card leaves until it moves
 * again, and the phone never keeps a card for ever.
 */
class RunningListTest {
    private fun card(id: String, kind: String = "helper", state: String = "working", depth: Int = 0, projectChat: String = "", parentChat: String = "",
                     chat: String = "main", owner: String = "main", ownerId: String = "", ownerName: String = "", activityAt: Long = 100, waiting: Boolean = false): JSONObject =
        JSONObject().put("id", id).put("row", id).put("kind", kind).put("chat", chat).put("label", id).put("state", state).put("depth", depth)
            .put("projectChat", projectChat).put("parentChat", parentChat).put("startedAt", 10).put("activityAt", activityAt).put("waiting", waiting)
            .put("owner", JSONObject().put("kind", owner).put("id", ownerId).put("name", ownerName).put("icon", JSONObject().put("glyph", "folder").put("color", "iris")))

    private fun parse(vararg c: JSONObject): List<LiveCard> = LiveCards.parse(JSONObject().put("seq", 1).put("cards", JSONArray(c.toList())))!!.second

    private val cards = parse(
        card("pa-c1", kind = "project-chat", projectChat = "c1", owner = "project", ownerId = "p1", ownerName = "Agent App"),
        card("h1", depth = 1, parentChat = "c1", chat = "c1", owner = "project", ownerId = "p1", ownerName = "Agent App"),
        card("pa-c2", kind = "project-chat", projectChat = "c2", owner = "project", ownerId = "p2", ownerName = "Cosmiprint"),
        card("h2", depth = 1, parentChat = "c2", chat = "c2", owner = "project", ownerId = "p2", ownerName = "Cosmiprint"),
        card("h3"),
    )

    @Test
    fun theMainThreadShowsEveryCardAProjectChatOnlyItsOwn() {
        assertEquals(listOf("pa-c1", "h1", "pa-c2", "h2", "h3"), LiveCards.forChat(cards, "main").map { it.id })
        assertEquals(listOf("pa-c1", "h1"), LiveCards.forChat(cards, "c1", "p1", "Agent App").map { it.id })
        // Another chat of the same project (known by id) still shows the project's cards.
        assertEquals(listOf("pa-c1", "h1"), LiveCards.forChat(cards, "c1-second", "p1", "Agent App").map { it.id })
        // With no id known, the project's name decides.
        assertEquals(listOf("pa-c2", "h2"), LiveCards.forChat(cards, "c2", "", "Cosmiprint").map { it.id })
    }

    @Test
    fun aProjectCardGoesStraightToItsChatAHelperCardOpensItsSheet() {
        assertEquals(CardTap.Chat("c1", "Agent App"), tapOf(cards[0]))
        assertEquals(CardTap.Sheet("h1"), tapOf(cards[1]))
        // Go to chat from a helper's sheet: its project chat, or the main thread for the main chat's own.
        assertEquals("c1", cards[1].homeChat)
        assertEquals("main", cards[4].homeChat)
    }

    @Test
    fun aSwipedCardLeavesAndComesBackOnlyWhenItMovesAgain() {
        val hidden = mapOf("pa-c1" to 200L, "h3" to 50L)
        // pa-c1 swiped after its last move: gone, with its finished helpers; a working helper under it stays visible.
        val done = parse(
            card("pa-c1", kind = "project-chat", projectChat = "c1", owner = "project", ownerId = "p1", state = "done", waiting = true),
            card("h1", depth = 1, parentChat = "c1", state = "done"),
            card("h1b", depth = 1, parentChat = "c1", state = "working"),
            card("h3", activityAt = 100),
        )
        assertEquals(listOf("h1b", "h3"), LiveCards.visible(done, hidden).map { it.id })
        // It moved after the swipe: back.
        val moved = parse(card("pa-c1", kind = "project-chat", projectChat = "c1", activityAt = 300))
        assertEquals(listOf("pa-c1"), LiveCards.visible(moved, hidden).map { it.id })
    }

    @Test
    fun theSessionDropsCardsFromAComputerItNoLongerHearsAndSwipesCallDismiss() {
        val src = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val session = File(src, "com/past9/phoneaos/remote/RemoteSession.kt").readText()
        assertTrue("swipe goes to helpers.dismiss", session.contains("call(\"helpers.dismiss\""))
        assertTrue("stale cards are dropped when the link stays down", session.contains("STALE_CARDS_MS") && session.contains("publishCards(emptyList())"))
        val ui = File(src, "com/past9/phoneaos/remote/LiveCardsUi.kt").readText()
        assertTrue("each card swipes", ui.contains("SwipeToDismissBox"))
        assertTrue("the sheet has Go to chat", File(src, "com/past9/phoneaos/ui/Helpers.kt").readText().let { it.contains("Text(\"Go to chat\")") || it.contains("Text(stringResource(R.string.ui_go_to_chat))") })
        val pchat = File(src, "com/past9/phoneaos/ui/screens/ProjectChat.kt").readText()
        assertTrue("a project chat folds its list into the same bubbles as the main chat", pchat.contains("AgentsPill(live") && pchat.contains("LiveCardList(mine, onCollapse") && pchat.contains("rememberAgentsFold()"))
        assertTrue("a project chat's title opens the switcher", pchat.contains("SwitcherTitle(chat"))
        val chatKt = File(src, "com/past9/phoneaos/ui/screens/Chat.kt").readText()
        assertTrue("the main chat keeps its list, its fold into bubbles, and its title opens the profile", chatKt.contains("LiveCardList(live, onCollapse") && chatKt.contains("AgentsPill(live") && chatKt.contains("clickable(onClick = actions.onProfile)"))
        assertTrue("Home's main agent card shows it too", File(src, "com/past9/phoneaos/ui/screens/Home.kt").readText().contains("HomeRunning("))
        val feed = File(src, "com/past9/phoneaos/remote/RunningListUi.kt").readText()
        assertTrue("projects and chat states follow the link's events, never a timer", feed.contains("s.events.collectLatest") && feed.contains("\"chat.states\"") && feed.contains("listOf(\"project\")"))
        assertTrue("Connections names the line Mini apps", File(src, "com/past9/phoneaos/ui/screens/Connections.kt").readText().let { it.contains("Text(\"Mini apps\"") || it.contains("Text(stringResource(R.string.conn_mini_apps)") })
    }

    // ---- 9 Nov 2026: ONE running list across all projects, one fold for the whole app, and the project switcher ----

    private val projects = RunningList.projects(JSONArray(listOf(
        JSONObject().put("id", "p1").put("name", "Garden").put("icon", JSONObject().put("glyph", "leaf").put("color", "mint")).put("chats", JSONArray(listOf("c1-old", "c1"))),
        JSONObject().put("id", "p2").put("name", "Shop").put("icon", JSONObject().put("glyph", "cart").put("color", "ember")).put("chats", JSONArray(listOf("c2"))),
        JSONObject().put("id", "p3").put("name", "Recipes").put("chats", JSONArray(listOf("c3"))),
        JSONObject().put("id", "p4").put("name", "Hidden one").put("hidden", true).put("chats", JSONArray(listOf("c4"))),
        JSONObject().put("id", "earlier-chats").put("name", "Earlier chats").put("chats", JSONArray(listOf("old1"))),
        JSONObject().put("id", "p5").put("name", "Empty"),
    )))

    /** Three projects and the main chat. p3's helper names no owner: only the project's chats[] say where it belongs. */
    private val across = parse(
        card("m1"),
        card("pa-c1", kind = "project-chat", projectChat = "c1", owner = "project", ownerId = "p1", ownerName = "Garden"),
        card("h1", depth = 1, parentChat = "c1", chat = "c1", owner = "project", ownerId = "p1", ownerName = "Garden"),
        card("pa-c2", kind = "project-chat", projectChat = "c2", owner = "project", ownerId = "p2", ownerName = "Shop"),
        card("h2", depth = 1, parentChat = "c2", chat = "c2", owner = "project", ownerId = "p2", ownerName = "Shop", state = "done"),
        card("h2b", depth = 1, parentChat = "c2", chat = "c2", owner = "project", ownerId = "p2", ownerName = "Shop", state = "failed"),
        card("h3", chat = "c3"),
        card("h3b", chat = "c3", state = "stopped"),
        card("p1-again", owner = "project", ownerId = "p1", ownerName = "Garden"),
    )

    @Test
    fun cardsFromThreeProjectsGroupByProjectWithTheCurrentOneFirst() {
        val fromMain = RunningList.groups(across, projects)
        assertEquals(listOf("main", "p1", "p2", "p3"), fromMain.map { it.key })
        assertEquals(listOf("Main chat", "Garden", "Shop", "Recipes"), fromMain.map { it.name })
        // In a project chat (known by one of its chat ids, or by the project's id) that project leads; the rest keep their order.
        assertEquals(listOf("p2", "main", "p1", "p3"), RunningList.groups(across, projects, "c2").map { it.key })
        assertEquals(listOf("p3", "main", "p1", "p2"), RunningList.groups(across, projects, "p3").map { it.key })
        // A project's cards stay together even when the computer lists them apart, and sub-helpers stay under their parent.
        val garden = fromMain.first { it.key == "p1" }
        assertEquals(listOf(listOf("pa-c1", "h1"), listOf("p1-again")), garden.clusters.map { cl -> cl.map { it.id } })
    }

    @Test
    fun countsIgnoreDoneFailedAndStopped() {
        val g = RunningList.groups(across, projects).associate { it.key to it.working }
        assertEquals(mapOf("main" to 1, "p1" to 3, "p2" to 1, "p3" to 1), g)
        assertEquals(RunCounts(working = 6), RunningList.counts(across))
        val ended = parse(card("a", state = "done"), card("b", state = "failed"), card("c", state = "stopped"), card("d", state = "working").put("endedAt", 50))
        assertEquals(RunCounts(), RunningList.counts(ended))
        assertEquals(0, RunningList.groups(ended, projects).sumOf { it.working })
    }

    private val mixed = parse(
        card("w1"), card("w2", chat = "c3"),
        card("q1").put("queued", true),
        card("pa-c1", kind = "project-chat", projectChat = "c1", owner = "project", ownerId = "p1", ownerName = "Garden", state = "done", waiting = true),
        card("old", state = "done"),
    )

    @Test
    fun theHeaderSaysOnlyThePartsThatAreNotZero() {
        assertEquals(RunCounts(2, 1, 1), RunningList.counts(mixed))
        assertEquals("2 working, 1 waiting to start, 1 needs you", RunningList.header(RunningList.counts(mixed)))
        assertEquals("Nothing running", RunningList.header(RunCounts()))
        assertEquals("3 working", RunningList.header(RunCounts(3, 0, 0)))
        assertEquals("1 working, 2 need you", RunningList.header(RunCounts(1, 0, 2)))
        assertEquals("4 waiting to start", RunningList.header(RunCounts(0, 4, 0)))
        // chat.states can say a project chat waits on him before its card does.
        val viaStates = parse(card("pa-c2", kind = "project-chat", projectChat = "c2", owner = "project", ownerId = "p2"))
        assertEquals(RunCounts(0, 0, 1), RunningList.counts(viaStates, RunningList.states(JSONArray(listOf(JSONObject().put("chat", "c2").put("running", false).put("waiting", true))))))
    }

    @Test
    fun oneTapFoldsItEverywhereAndACardThatNeedsHimStaysInView() {
        val n = RunningList.counts(mixed)
        val fold = RunningFold(null)
        assertTrue("open until he folds it", !fold.collapsed(n))
        fold.toggle(n)
        assertTrue("one tap folds it", fold.collapsed(n))
        val groups = RunningList.groups(mixed, projects)
        assertEquals("folded: only the card that needs him", listOf("pa-c1"), RunningList.shown(groups, true).map { it.id })
        assertEquals("open: everything at work or waiting on him, finished ones behind their own line", listOf("w1", "q1", "w2", "pa-c1"), RunningList.shown(groups, false).map { it.id })
        assertEquals(1, RunningList.finishedCount(groups))
        fold.toggle(n)
        assertTrue("and one tap opens it again", !fold.collapsed(n))
    }

    @Test
    fun itIsFoldedByItselfWhenNothingRuns() {
        val idle = RunCounts(0, 0, 1)
        assertTrue(RunningList.collapsed(false, idle))
        assertTrue(RunningList.collapsed(false, RunCounts()))
        assertTrue(!RunningList.collapsed(false, RunCounts(1, 0, 0)))
        assertTrue(RunningList.collapsed(true, RunCounts(0, 1, 0)))
        // A look at what just finished is only a peek: his kept choice for running work does not change.
        val fold = RunningFold(null)
        fold.toggle(idle)
        assertTrue(!fold.collapsed(idle) && !fold.choice)
        fold.toggle(idle)
        assertTrue(fold.collapsed(idle) && !fold.choice)
    }

    @Test
    fun theSwitcherListsTheMainChatThenHisProjectsSkippingHidden() {
        val rows = RunningList.switcher(projects, across, "c1")
        assertEquals(listOf("main", "p1", "p2", "p3", "p5", "earlier-chats"), rows.map { it.key })
        assertEquals("Main chat", rows.first().name)
        assertTrue("no hidden project", rows.none { it.name == "Hidden one" })
        // A tap opens the project's newest chat (the last of chats[]); one with no chat has none to open.
        assertEquals(listOf("main", "c1", "c2", "c3", "", "old1"), rows.map { it.chat })
        assertEquals(listOf("p1"), rows.filter { it.current }.map { it.key })
        assertEquals(listOf(1, 3, 1, 1, 0, 0), rows.map { it.running })
        // No projects at all: just the main chat, marked.
        assertEquals(listOf(SwitchRow("main", "Main chat", "main", null, 0, true)), RunningList.switcher(emptyList(), emptyList()))
        assertEquals("2 min ago · Fixed the gate", com.past9.phoneaos.ui.switcherLine("## Fixed the gate\nand more", 1_000_000L, 1_000_000L + 125_000L))
    }

    @Test
    fun helpersFoldUnderTheHelperThatStartedThemAndChatsInNoProjectAreOtherChats() {
        val list = parse(
            card("a"), card("x", chat = "loose-chat"),
            card("a1").put("parentHelper", "a"),
            card("x1", chat = "loose-chat").put("parentHelper", "x"),
            card("a2", depth = 1).put("parentHelper", "a"),
        )
        assertEquals(listOf(listOf("a", "a1", "a2"), listOf("x", "x1")), RunningList.clusters(list).map { cl -> cl.map { it.id } })
        val g = RunningList.groups(list, projects)
        assertEquals(listOf("main", "other"), g.map { it.key })
        assertEquals(listOf("Main chat", "Other chats"), g.map { it.name })
        assertEquals(listOf("3 working", "2 working"), g.map { RunningList.header(it.counts) })
        // A computer that does not say who started whom (0.2.122): the order and depth it sends decide, as before.
        assertEquals(com.past9.phoneaos.remote.groupsOf(across).map { cl -> cl.map { it.id } }, RunningList.clusters(across).map { cl -> cl.map { it.id } })
        assertEquals("1 helper under it", RunningList.under(1))
        assertEquals("3 helpers under it", RunningList.under(3))
        // A folded group is his choice too; without a device to keep it on it still holds while the app runs.
        val fold = RunningFold(null)
        fold.toggleGroup("p1"); assertEquals(setOf("p1"), fold.groups)
        fold.toggleGroup("p1"); assertTrue(fold.groups.isEmpty())
    }
}
