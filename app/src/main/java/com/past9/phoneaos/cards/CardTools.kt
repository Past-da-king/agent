package com.past9.phoneaos.cards

import android.content.Context
import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.bool
import com.past9.phoneaos.agent.int
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.data.ChatDao
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.data.SettingsStore
import org.json.JSONObject

/** Who has read the design guide in this run: card_save insists on it, so every card follows it. */
internal object GuideReaders { val read: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf()) }

/** Data from the tool call: an object or array as JSON, or a JSON string; anything else is stored as null. */
private fun dataOf(input: JSONObject): String? = when (val d = input.opt("data")) {
    null, JSONObject.NULL -> null
    is JSONObject, is org.json.JSONArray -> d.toString()
    is String -> runCatching { JSONObject(d).toString() }.recoverCatching { org.json.JSONArray(d).toString() }.getOrNull()
    else -> null
}

class CardGuideTool(private val context: Context) : Tool {
    override val spec = ToolSpec("card_guide", "Read the app's guide for cards BEFORE building or changing one: cards are OpenUI (the same language as chat answers, drawn natively in the user's accent and light or dark theme), with the rules, data placeholders and a worked example. card_save won't work until you have.", schema())
    override suspend fun run(input: JSONObject, ctx: ToolContext): String { GuideReaders.read += ctx.agentLabel; return CardKit.guide(context) }
}

class CardSaveTool(private val context: Context, private val store: CardStore, private val settings: SettingsStore, private val chat: ChatDao,
                   private val schedule: suspend (Card) -> Unit) : Tool {
    override val spec = ToolSpec("card_save",
        "Create or replace a live CARD: a small screen in OpenUI the user opens from Home or the chat, drawn natively in the app's accent and theme (read card_guide first). " +
            "Give it data for the body's {{key}} placeholders, and either a script that refreshes the data with no AI (prices, APIs, counting emails in a connected app) or nothing (you or a routine call card_update).",
        schema(listOf("title", "openui"),
            "id" to str("Existing card id to replace (omit for a new card)"),
            "title" to str("Short title, e.g. 'Monitor prices'"),
            "icon" to str("Kit icon name for the tile: mail send tag cart calendar chart money briefcase users target list star bolt doc search"),
            "openui" to str("The card's body in OpenUI Lang: root = Stack([...]) and the components from card_guide. {{key}} in a string is filled from data"),
            "html" to str("Legacy: HTML body from before OpenUI. Do not use for new cards"),
            "data" to JSONObject().put("description", "The values for the body's {{key}} placeholders, as a JSON object"),
            "headline" to str("One short line for the Home tile, e.g. 'R 3,299 cheapest' or '12 sent, 3 replies'"),
            "source" to str("Where the data comes from, e.g. 'takealot.com' or 'Gmail'"),
            "script" to str("Optional Node script that refreshes the data with no AI. Its LAST output line must be {\"data\": {...}}. apps(slug, args, account?) reads connected apps"),
            "every_minutes" to int("How often the script runs (at least 15; 1440 = daily). 0 = only when the user taps refresh"),
            "pin" to bool("Put it on the Home page (only if the user asked for it there)")))

    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        if (ctx.agentLabel !in GuideReaders.read) return "Read card_guide first: it has the rules and patterns every card must follow. Then call card_save again."
        val old = input.optString("id").takeIf { it.isNotBlank() }?.let { store.get(it) }
        val title = input.optString("title").trim().ifBlank { old?.title ?: "Card" }
        val id = old?.id ?: generateSequence(0) { it + 1 }.map { if (it == 0) Card.slug(title) else "${Card.slug(title)}-$it" }.first { store.get(it) == null }
        val script = input.optString("script").ifBlank { old?.script.orEmpty() }
        if (input.optString("openui").isBlank() && input.optString("html").isBlank() && old?.isOpenUi != true && old?.html.isNullOrBlank())
            return "A card needs an openui body (root = Stack([...]), see card_guide)."
        var card = (old ?: Card(id = id, title = title, html = "")).copy(
            title = title, icon = input.optString("icon").ifBlank { old?.icon ?: "chart" }, html = input.optString("html").ifBlank { old?.html.orEmpty() },
            // A new OpenUI body replaces an old HTML one for good.
            openui = input.optString("openui").ifBlank { old?.openui.orEmpty() },
            data = dataOf(input) ?: old?.data ?: "null", headline = input.optString("headline").ifBlank { old?.headline.orEmpty() },
            source = input.optString("source").ifBlank { old?.source.orEmpty() }, script = script,
            everyMinutes = if (input.has("every_minutes")) input.optInt("every_minutes").let { if (it <= 0) 0 else it.coerceAtLeast(15) } else old?.everyMinutes ?: 0,
            pinned = if (input.has("pin")) input.optBoolean("pin") else old?.pinned ?: false, updatedAt = System.currentTimeMillis())

        // A script that reads the user's apps runs without them watching: they say yes once, per card.
        var note = ""
        if (script.contains("apps(") && !card.appsAllowed) {
            val a = ctx.ask("Let the \"$title\" card read your connected apps by itself to stay up to date? It can only read, never send or change anything.", listOf("Allow", "Not now"))
            if (a == "Allow") card = card.copy(appsAllowed = true) else note = "\nThe user didn't allow it to read their apps, so apps() calls will fail. Fill the data yourself (card_update) instead."
        }
        if (script.isNotBlank()) {
            val r = CardScripts.run(context, card, script = script)
            card = if (r.ok) card.withData(r.data!!, card.updatedAt)
                else { note += "\nTHE SCRIPT FAILED on its first run, so the card shows the last data you gave it:\n${r.log.takeLast(1500)}\nFix the script and save again."; card.copy(error = r.log.takeLast(400)) }
        }
        store.put(card)
        schedule(card)
        if (old == null) chat.insert(ChatItem(kind = "card", text = card.title, meta = JSONObject().put("card", card.id).toString()))
        ctx.activity("${if (old == null) "Built" else "Updated"} the card: ${card.title}", JSONObject().put("tool", "cards"))
        return "Card \"${card.title}\" saved (id ${card.id})${if (card.pinned) ", on the Home page" else ""}${if (card.script.isNotBlank() && card.everyMinutes > 0) ", refreshing every ${card.everyMinutes} min" else ""}.$note" +
            (if (card.isOpenUi) OpenUiCheck.warnings(card.openUiSource()).let { if (it.isBlank()) "" else "\n$it" } else "\nThis card is legacy HTML: rebuild it as openui so it follows the app's theme.")
    }
}

class CardUpdateTool(private val store: CardStore) : Tool {
    override val spec = ToolSpec("card_update", "Give a card fresh data for its {{key}} placeholders (data may also carry a new \"openui\" body or \"headline\"; optionally a Home headline or source). Use it from routines for cards that need judgement, like a daily brief.",
        schema(listOf("id", "data"), "id" to str("Card id"), "data" to JSONObject().put("description", "The new data, as a JSON object"),
            "headline" to str("New Home tile line (optional)"), "source" to str("Where it came from (optional)")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val data = dataOf(input) ?: return "data must be a JSON object."
        val c = store.update(input.optString("id")) { it.withData(data).copy(headline = input.optString("headline").ifBlank { it.withData(data).headline },
            source = input.optString("source").ifBlank { it.source }) } ?: return "No card ${input.optString("id")}."
        ctx.activity("Updated the card: ${c.title}", JSONObject().put("tool", "cards"))
        return "Card \"${c.title}\" updated."
    }
}

class CardListTool(private val store: CardStore) : Tool {
    override val spec = ToolSpec("card_list", "List the user's live cards: id, title, what's on the Home tile, how it refreshes, and any problem.", schema())
    override suspend fun run(input: JSONObject, ctx: ToolContext): String = store.cards.value.joinToString("\n") { c ->
        "${c.id}: ${c.title}${if (c.pinned) " [on Home]" else ""} · ${c.headline.ifBlank { "no headline" }} · " +
            (if (c.script.isNotBlank()) "script every ${c.everyMinutes} min" else "filled by the agent") + (if (c.error.isNotBlank()) " · PROBLEM: ${c.error.take(120)}" else "")
    }.ifBlank { "No cards yet." }
}

class CardShowTool(private val store: CardStore, private val chat: ChatDao) : Tool {
    override val spec = ToolSpec("card_show", "Show one of the user's cards in the chat, so they can tap to open it.", schema(listOf("id"), "id" to str("Card id")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val c = store.get(input.optString("id")) ?: return "No card ${input.optString("id")}."
        chat.insert(ChatItem(kind = "card", text = c.title, meta = JSONObject().put("card", c.id).toString()))
        return "Shown in the chat."
    }
}

class CardPinTool(private val store: CardStore) : Tool {
    override val spec = ToolSpec("card_pin", "Put a card on the Home page, or take it off.", schema(listOf("id", "pinned"), "id" to str("Card id"), "pinned" to bool("true = on Home")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String =
        store.update(input.optString("id")) { it.copy(pinned = input.optBoolean("pinned")) }?.let { if (it.pinned) "\"${it.title}\" is on the Home page." else "\"${it.title}\" is off the Home page." } ?: "No such card."
}

class CardDeleteTool(private val store: CardStore, private val unschedule: suspend (Card) -> Unit) : Tool {
    override val spec = ToolSpec("card_delete", "Delete a card the user no longer wants.", schema(listOf("id"), "id" to str("Card id")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val c = store.get(input.optString("id")) ?: return "No such card."
        unschedule(c.copy(script = "")); store.delete(c.id); return "Deleted \"${c.title}\"."
    }
}

/** A cheap check on a card body, so the agent hears about a body that would draw nothing or break the house rules. */
object OpenUiCheck {
    fun warnings(src: String): String {
        val out = mutableListOf<String>()
        if (com.past9.phoneaos.ui.openui.OpenUiLang.render(com.past9.phoneaos.ui.openui.OpenUiLang.parse(src)).root == null) out += "The body drew nothing: it must define root = Stack([...]) and every name must be reachable from root."
        if (src.contains('\u2014') || src.contains('\u2013')) out += "Remove the em or en dashes."
        if (Regex("#[0-9A-Fa-f]{6}\\b").containsMatchIn(src)) out += "Remove hex colours: the app picks the colours."
        return out.joinToString(" ")
    }
}
