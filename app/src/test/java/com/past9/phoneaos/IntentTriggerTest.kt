package com.past9.phoneaos

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.automation.AutomationRoutine
import com.past9.phoneaos.automation.AutomationRuns
import com.past9.phoneaos.automation.AutomationServer
import com.past9.phoneaos.automation.IntentTriggerReceiver
import com.past9.phoneaos.automation.IntentTriggers
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Intents IN: any app fires a routine or sends an instruction with a broadcast. Pinned here:
 * 1. nothing happens without the token, and nothing while the switch is off;
 * 2. a routine is found by id, by its exact name, or by a part of the name only one routine has;
 * 3. an instruction is passed on;
 * 4. the receiver is in the manifest, exported, for both actions, and the real receiver does the work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IntentTriggerTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val token = "abc123def456"
    private var allowed = true
    private val fired = mutableListOf<Pair<String, String>>()
    private val told = mutableListOf<String>()
    private val routines = listOf(
        AutomationRoutine(1, "Morning brief", true, "Every day at 07:00"),
        AutomationRoutine(2, "Evening brief", true, "Every day at 19:00"),
        AutomationRoutine(3, "Weekly deliveries", true, "MON 08:00"),
    )
    private val handlers = AutomationServer.Handlers(
        allowed = { allowed }, routines = { routines },
        fire = { r, note -> fired += r.name to note; AutomationRuns.start("run") },
        instruct = { t -> told += t; AutomationRuns.start("instruction") },
        run = { AutomationRuns.get(it) },
    )
    private val savedSource = IntentTriggers.source

    @Before fun reset() { AutomationRuns.clear() }
    @After fun restore() { IntentTriggers.source = savedSource }

    private fun run(vararg extras: Pair<String, String?>) = IntentTriggers.handle(IntentTriggers.RUN_ROUTINE, extras.toMap(), token, handlers)

    @Test fun noTokenOrAWrongOneDoesNothing() {
        assertEquals(401, run("routine" to "Morning brief").code)
        assertEquals(401, run("token" to "nope", "routine" to "Morning brief").code)
        assertEquals(401, IntentTriggers.handle(IntentTriggers.INSTRUCT, mapOf("token" to "", "text" to "hi"), token, handlers).code)
        assertEquals("an empty token on our side never matches", 401, IntentTriggers.handle(IntentTriggers.INSTRUCT, mapOf("token" to "", "text" to "hi"), "", handlers).code)
        assertTrue(fired.isEmpty() && told.isEmpty())
    }

    @Test fun theSwitchOffRefusesEvenWithTheToken() {
        allowed = false
        val out = run("token" to token, "routine" to "Morning brief")
        assertEquals(403, out.code)
        assertTrue(fired.isEmpty())
    }

    @Test fun aRoutineIsFoundByNameIdOrAUniquePart() {
        val byName = run("token" to token, "routine" to "morning BRIEF", "note" to "from Tasker")
        assertEquals(200, byName.code)
        assertEquals("running", JSONObject(byName.body).getString("status"))
        assertEquals(200, run("token" to token, "routine" to "3").code)
        assertEquals(200, run("token" to token, "routine" to "deliver").code)
        assertEquals(listOf("Morning brief" to "from Tasker", "Weekly deliveries" to "", "Weekly deliveries" to ""), fired)
    }

    @Test fun anUnclearOrMissingRoutineRunsNothingAndNamesTheRoutines() {
        val two = run("token" to token, "routine" to "brief")
        assertEquals("two routines have 'brief' in the name: guessing is how the wrong one runs", 404, two.code)
        assertEquals(3, JSONObject(two.body).getJSONArray("routines").length())
        assertEquals(400, run("token" to token).code)
        assertTrue(fired.isEmpty())
    }

    @Test fun anInstructionIsPassedOn() {
        val out = IntentTriggers.handle(IntentTriggers.INSTRUCT, mapOf("token" to token, "text" to " What is on my list? "), token, handlers)
        assertEquals(200, out.code)
        assertEquals(listOf("What is on my list?"), told)
        assertEquals(400, IntentTriggers.handle(IntentTriggers.INSTRUCT, mapOf("token" to token), token, handlers).code)
        assertEquals(404, IntentTriggers.handle("com.past9.phoneaos.OTHER", mapOf("token" to token), token, handlers).code)
    }

    @Test fun theReceiverIsInTheManifestExportedForBothActions() {
        for (action in listOf(IntentTriggers.RUN_ROUTINE, IntentTriggers.INSTRUCT)) {
            val hit = app.packageManager.queryBroadcastReceivers(Intent(action).setPackage(app.packageName), 0).single().activityInfo
            assertEquals(IntentTriggerReceiver::class.java.name, hit.name)
            assertTrue(hit.exported)
        }
    }

    @Test fun theRealReceiverFiresTheRoutine() {
        IntentTriggers.source = { token to handlers }
        IntentTriggerReceiver().onReceive(app, Intent(IntentTriggers.RUN_ROUTINE).setPackage(app.packageName)
            .putExtra("token", token).putExtra("routine", "Morning brief").putExtra("note", "door opened"))
        val until = System.currentTimeMillis() + 5_000
        while (fired.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals(listOf("Morning brief" to "door opened"), fired)
    }

    @Test fun theRealReceiverIgnoresAWrongToken() {
        IntentTriggers.source = { token to handlers }
        IntentTriggerReceiver().onReceive(app, Intent(IntentTriggers.INSTRUCT).putExtra("token", "wrong").putExtra("text", "delete everything"))
        Thread.sleep(300)
        assertTrue(told.isEmpty())
    }

    @Test fun theRecipeCarriesTheTokenThePackageAndBothActions() {
        val r = IntentTriggers.recipe(token)
        assertTrue(r.contains(token) && r.contains("Package: com.past9.phoneaos") && r.contains(IntentTriggers.RUN_ROUTINE) && r.contains(IntentTriggers.INSTRUCT))
    }
}
