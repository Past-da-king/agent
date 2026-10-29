package com.past9.phoneaos

import com.past9.phoneaos.remote.CardTap
import com.past9.phoneaos.remote.LiveCard
import com.past9.phoneaos.remote.LiveCards
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
        assertTrue("the sheet has Go to chat", File(src, "com/past9/phoneaos/ui/Helpers.kt").readText().contains("Text(\"Go to chat\")"))
        val pchat = File(src, "com/past9/phoneaos/ui/screens/ProjectChat.kt").readText()
        assertTrue("a project chat lists only its project's cards", pchat.contains("live?.scoped(chat"))
        assertTrue("Connections names the line Mini apps", File(src, "com/past9/phoneaos/ui/screens/Connections.kt").readText().contains("Text(\"Mini apps\""))
    }
}
