package com.past9.phoneaos

import com.past9.phoneaos.remote.DeviceTools
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every tool the phone has is either lent to a linked computer or on the phone-only list with a reason.
 * (10 Nov 2026: the main agent on the computer could not reach the background screen from a chat started on the phone.)
 * This reads every ToolSpec("name", ...) in the app source, so a new tool cannot be left unreachable without anyone deciding so.
 */
class LentToolsTest {
    private val spec = Regex("ToolSpec[(]\"([a-z][a-z0-9_]*)\"")

    private fun names(): Set<String> = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.flatMap { f -> spec.findAll(f.readText()).map { it.groupValues[1] } }.toSet()

    @Test fun everyToolIsLentOrPhoneOnlyOnPurpose() {
        val all = names()
        assertTrue("the scan found too few tools (${all.size}): it is broken", all.size > 80)
        val undecided = all.filter { it !in DeviceTools.LENT && it !in DeviceTools.PHONE_ONLY }
        assertTrue("Neither lent to linked devices nor phone-only on purpose: $undecided. Add each to DeviceTools.LENT (and to all() and groupOf) or to PHONE_ONLY with the reason.", undecided.isEmpty())
        val both = DeviceTools.LENT.filter { it in DeviceTools.PHONE_ONLY }
        assertTrue("Both lent and phone-only: $both", both.isEmpty())
        val noGroup = DeviceTools.LENT.filter { DeviceTools.groupOf(it) == null }
        assertTrue("Lent but in no group, so never published: $noGroup", noGroup.isEmpty())
        val gone = (DeviceTools.LENT + DeviceTools.PHONE_ONLY.keys).filter { it !in all }
        assertTrue("Listed but no such tool any more: $gone", gone.isEmpty())
        assertTrue(DeviceTools.groupOf("web_reddit_search") == "browser" && DeviceTools.groupOf("app_notes_add_note") == "screen")
    }
}
