package com.past9.phoneaos.tools

import android.content.Context
import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.bool
import com.past9.phoneaos.agent.enumOf
import com.past9.phoneaos.agent.int
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.screen.ScreenSession
import com.past9.phoneaos.screen.Screens
import org.json.JSONObject

/*
 * The background screen tools: the agent uses another app on a hidden display while the person keeps their own
 * screen. Every tool acts on the hidden display only; none of them can see or touch the screen the person is using.
 * The rules (Stop, Take over, per-app Allow / Ask me / Never, approval before a send, a payment or a delete) are in
 * ScreenSession, not here and not in the prompt.
 */

private fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null

class ScreenOpenAppTool(private val session: () -> ScreenSession) : Tool {
    override val spec = ToolSpec("screen_open_app",
        "Open an installed app on the BACKGROUND SCREEN: a hidden display where you use the app while the user keeps using their own screen, which is never touched. " +
            "Use it for a task inside an app that has no website, no connected-app tool and no intent that does it. Returns what is on the app's screen as a numbered list; " +
            "then use screen_tap, screen_type, screen_swipe, screen_key, screen_look and screen_wait, and screen_close when the task is done. " +
            "HOW TO USE IT (also from a linked computer, through this phone's device tools): 1. screen_open_app. 2. Read the list: each line is [number] kind \"words\" at x,y; the number is only good until the next answer, and every action answers with the fresh list. " +
            "3. Act with screen_tap (element number, or its words), screen_type, screen_swipe, screen_key. 4. If what you need is not listed, screen_look again (mode image sends you the picture) or swipe. 5. screen_close. " +
            "There are no ready-made actions for any app: you work the job out by reading the screen and pressing, like a person would. For example, with no search tool, open a search or assistant app, type the question, wait, and read the answer off the screen. " +
            "When a job worked, save it with screen_skill_save so it becomes one quick tool next time; check screen_skill_list first for one that exists. " +
            "Anything that sends, pays, deletes or posts asks the user first by itself. Banking and money apps are never opened here. If the background screen is not set up, the answer says so and the user is shown the steps.",
        schema(listOf("app"),
            "app" to str("The app's name as the user knows it (e.g. 'Calendar'), or its package name"),
            "link" to str("Optional: a deep link to open inside that app instead of its front screen (e.g. a geo:, https: or app-scheme link)"),
            "owner" to str("Your own name when you are a helper, a project chat or a routine (e.g. 'Uber booking helper'): it is shown to the user, and the screen is held for you. One job at a time; another agent is told it is busy.")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val app = input.optString("app").trim(); if (app.isEmpty()) return "Say which app."
        return session().open(ctx, app, input.optString("link").trim(), input.optString("owner"))
    }
}

class ScreenLookTool(private val session: () -> ScreenSession) : Tool {
    override val spec = ToolSpec("screen_look",
        "Read the background screen again: the app in front and a numbered list of what can be read or pressed ([n] kind \"words\" at x,y). " +
            "mode 'image' or 'both' also gives you the picture (for layouts, images, charts, or an app that gives no list); x,y on the picture are the screen's own. Numbers are only good until the next look.",
        schema(emptyList(),
            "mode" to enumOf("What to return, default tree", "tree", "image", "both"),
            "filter" to str("Only elements whose words contain this")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String = session().look(ctx, input.optString("mode"), input.optString("filter"))
}

class ScreenTapTool(private val session: () -> ScreenSession) : Tool {
    override val spec = ToolSpec("screen_tap",
        "Press something on the background screen: by its number from the last list (element), by its words (text), or at a point (x, y). " +
            "A press that sends, pays, deletes or posts asks the user first; so does a press at a point with nothing under it. Returns the screen afterwards.",
        schema(emptyList(),
            "element" to int("The element's number from the last list"),
            "text" to str("Or the words on it, e.g. 'Save'"),
            "x" to int("Or a point: x in screen pixels"), "y" to int("y in screen pixels"),
            "long" to bool("Press and hold (default false)")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String =
        session().tap(ctx, input.intOrNull("element"), input.optString("text"), input.intOrNull("x"), input.intOrNull("y"), input.optBoolean("long"))
}

class ScreenTypeTool(private val session: () -> ScreenSession) : Tool {
    override val spec = ToolSpec("screen_type",
        "Type text into a field on the background screen: the field with the focus, or the one you name (element or field). It replaces what is there unless append is true. " +
            "submit true presses the keyboard's own Send, Search or Go afterwards, which asks the user first. Returns what the field now reads and the screen afterwards.",
        schema(listOf("text"),
            "text" to str("What to type"),
            "element" to int("The field's number from the last list"),
            "field" to str("Or the field's words or hint, e.g. 'Search'"),
            "append" to bool("Add to what is there instead of replacing it (default false)"),
            "submit" to bool("Press the keyboard's Send / Search / Go after typing (default false)"),
            "secret" to bool("true for a password, a PIN or anything private: it is painted over in the recording and never stored (default false)")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String =
        session().type(ctx, input.optString("text"), input.intOrNull("element"), input.optString("field"), input.optBoolean("append"), input.optBoolean("submit"), input.optBoolean("secret"))
}

class ScreenSwipeTool(private val session: () -> ScreenSession) : Tool {
    override val spec = ToolSpec("screen_swipe",
        "Swipe on the background screen. direction 'up' moves the finger up, which shows what is further down; 'down', 'left' and 'right' likewise. Inside one element (a list, a carousel) when you give its number, else across the screen. Or from one point to another.",
        schema(emptyList(),
            "direction" to enumOf("Which way the finger moves", "up", "down", "left", "right"),
            "element" to int("Swipe inside this element"),
            "x1" to int("Or from this point"), "y1" to int(""), "x2" to int("to this point"), "y2" to int("")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String =
        session().swipe(ctx, input.optString("direction"), input.intOrNull("element"), input.intOrNull("x1"), input.intOrNull("y1"), input.intOrNull("x2"), input.intOrNull("y2"))
}

class ScreenKeyTool(private val session: () -> ScreenSession) : Tool {
    override val spec = ToolSpec("screen_key", "Press a key on the background screen: back, enter, tab, delete, escape, search, up, down, left, right. Enter with text typed asks the user first (it usually sends).",
        schema(listOf("key"), "key" to enumOf("The key", "back", "enter", "tab", "delete", "escape", "search", "up", "down", "left", "right")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String = session().key(ctx, input.optString("key"))
}

class ScreenWaitTool(private val session: () -> ScreenSession) : Tool {
    override val spec = ToolSpec("screen_wait", "Wait on the background screen until some words appear (or, with gone true, are no longer there), up to 30 seconds. With no text it just reads the screen once it has settled. Also how you wait while the user has taken over.",
        schema(emptyList(), "text" to str("The words to wait for"), "gone" to bool("Wait until they are gone instead (default false)"), "seconds" to int("How long at most, default 10")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String =
        session().waitFor(ctx, input.optString("text"), input.optBoolean("gone"), input.optInt("seconds", 10))
}

class ScreenCloseTool(private val session: () -> ScreenSession) : Tool {
    override val spec = ToolSpec("screen_close", "Close the background screen when the task is done: the apps on it are closed and nothing is left running. keep_app true hands the app in front to the user's own screen instead of closing it.",
        schema(emptyList(), "keep_app" to bool("Move the app to the user's screen instead of closing it (default false)")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String = session().close(input.optBoolean("keep_app"))
}

class ScreenStatusTool(private val session: () -> ScreenSession) : Tool {
    override val spec = ToolSpec("screen_status", "Whether the background screen is ready on this phone, and what is open on it.", schema())
    override suspend fun run(input: JSONObject, ctx: ToolContext): String = session().status()
}

fun screenTools(session: () -> ScreenSession): List<Tool> = listOf(ScreenOpenAppTool(session), ScreenLookTool(session), ScreenTapTool(session), ScreenTypeTool(session),
    ScreenSwipeTool(session), ScreenKeyTool(session), ScreenWaitTool(session), ScreenCloseTool(session), ScreenStatusTool(session))

fun screenTools(context: Context): List<Tool> = screenTools { Screens.session(context) }
