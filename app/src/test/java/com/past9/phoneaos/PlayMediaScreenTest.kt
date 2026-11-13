package com.past9.phoneaos

import com.past9.phoneaos.screen.RunStep
import com.past9.phoneaos.screen.ScreenElement
import com.past9.phoneaos.screen.ScreenLanding
import com.past9.phoneaos.screen.ScreenLanding.Task
import com.past9.phoneaos.screen.ScreenSkills
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What play_media's background-screen job needs from the screen layer: a result row is saved by place, and a launch never changes the user's front app. */
class PlayMediaScreenTest {
    private fun el(id: Int, text: String, res: String, top: Int, flags: String = "c", cls: String = "android.widget.TextView") =
        ScreenElement(id, cls, text, "", "", res, 0, top, 720, top + 100, flags, 0)

    @Test fun aGenericPressIsSavedByPlaceAndKindNotByTheWordsOfTheRowItHadThatDay() {
        val rows = listOf(el(1, "BAD BISHHH - Tony Dayimane", "id/title", 300), el(2, "Another song", "id/title", 450))
        val sel = ScreenSkills.selector(rows[0], rows, 720, 1600)
        val run = listOf(
            RunStep(1, "open", "Opened"),
            RunStep(2, "tap", "Pressed row", sel, extra = JSONObject().put("generic", true)),
            RunStep(3, "tap", "Pressed other", sel))
        val (steps, _) = ScreenSkills.fromRun(run, mapOf("query" to "BAD BISHHH"))
        val generic = steps.getJSONObject(1).getJSONObject("sel")
        assertEquals("", generic.getString("text"))
        assertEquals("id/title", generic.getString("res"))
        assertEquals("a press that was not generic keeps its words", "{query} - Tony Dayimane", steps.getJSONObject(2).getJSONObject("sel").getString("text"))
        // on another day, another query: the same place is found, whatever the row says
        val other = listOf(el(7, "Underway - Some Band", "id/title", 300), el(8, "Third", "id/title", 450))
        assertEquals(7, ScreenSkills.find(generic, other, JSONObject().put("query", "Underway"))?.id)
    }

    @Test fun aLaunchOntoTheHiddenDisplayLeavesTheUsersFrontAppAsItWas() {
        val chrome = Task(1, 0, "com.android.chrome", true)
        val rvx = "app.rvx.android.youtube"
        val before = listOf(chrome)
        val after = listOf(chrome, Task(9, 7, rvx, true))
        assertTrue(ScreenLanding.after(before, after, rvx) is ScreenLanding.Decision.Leave)
        assertEquals(chrome, ScreenLanding.front(after))
    }

    @Test fun aLaunchThatCameUpOnTheUsersScreenIsMovedAndTheirAppPutBackSoTheFrontIsTheSame() {
        val chrome = Task(1, 0, "com.android.chrome", true)
        val rvx = "app.rvx.android.youtube"
        val before = listOf(chrome)
        var now = listOf(Task(2, 0, rvx, true), chrome.copy(visible = false))
        val fix = ScreenLanding.after(before, now, rvx) as ScreenLanding.Decision.Fix
        // what the helper does: move ours off display 0, bring theirs back to the front
        now = now.map { if (it.id in fix.move) it.copy(display = 7) else it }.map { if (it.id == fix.restore) it.copy(visible = true) else it }
        assertEquals(ScreenLanding.front(before)?.pkg, ScreenLanding.front(now)?.pkg)
        assertFalse(now.any { it.display == 0 && it.pkg == rvx })
    }

    @Test fun anAppTheUserHasInFrontIsNeverOpenedOnTheBackgroundScreen() {
        val rvx = "app.rvx.android.youtube"
        assertTrue(ScreenLanding.before(listOf(Task(3, 0, rvx, true)), rvx) is ScreenLanding.Decision.Refuse)
    }
}