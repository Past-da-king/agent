package com.past9.phoneaos

import com.past9.phoneaos.tools.ComposioConnect
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ComposioConnect.ensureLoaded (PR #10 by Magarto) loads the app tools when they are missing, so a process started in
 * the background without network does not keep an empty tool list until it dies. A merge (dfc9461) dropped it once;
 * this fails when it, or any of its call sites, goes missing again.
 */
class ComposioEnsureLoadedGuardTest {
    private val java = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
    private fun src(rel: String) = File(java, "com/past9/phoneaos/$rel").readText()

    @Test fun ensureLoadedExistsAndKeepsWhyItFailed() {
        assertTrue("ComposioConnect.ensureLoaded is gone", ComposioConnect::class.java.methods.any { it.name == "ensureLoaded" })
        assertTrue("ComposioConnect.lastError is gone (the prompt note needs it)", ComposioConnect::class.java.methods.any { it.name == "getLastError" })
        val body = src("tools/ComposioConnect.kt")
        assertTrue("a failed load is no longer retried at most once a minute", body.contains("60_000"))
    }

    @Test fun everyToolLookupPathCallsIt() {
        val rt = src("agent/AgentRuntime.kt")
        assertTrue("AgentRuntime.ensureAppTools is gone", rt.contains("private suspend fun ensureAppTools()"))
        assertTrue("ensureAppTools no longer calls ComposioConnect.ensureLoaded", Regex("""ensureAppTools\(\)\s*\{[^}]*ComposioConnect\.ensureLoaded\(""").containsMatchIn(rt.replace("\n", " ")))
        for (fn in listOf("runTurn", "runBranchTurn", "runHelper", "runBackground")) {
            val at = rt.indexOf("fun $fn(")
            assertTrue("$fn is gone", at >= 0)
            val head = rt.substring(at, minOf(rt.length, at + 900))
            assertTrue("$fn no longer calls ensureAppTools() before it works: a background process can keep an empty Composio tool list", head.contains("ensureAppTools()"))
        }
        assertTrue("opening Connections no longer loads the tools", src("MainActivity.kt").contains(".ensureLoaded(ck)"))
        assertTrue("the prompt no longer says the Composio tools couldn't load", rt.contains("couldn't load the Composio tools"))
    }
}
