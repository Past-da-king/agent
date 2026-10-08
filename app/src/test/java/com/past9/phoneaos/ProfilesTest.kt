package com.past9.phoneaos

import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.agent.AgentRuntime
import com.past9.phoneaos.agent.Block
import com.past9.phoneaos.agent.Role
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.data.*
import com.past9.phoneaos.tools.AppGuard
import com.past9.phoneaos.ui.screens.quotedFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfilesTest {
    private lateinit var db: AppDb
    private lateinit var settings: SettingsStore
    private lateinit var store: ProfileStore
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before fun setUp() {
        db = AppDb.inMemory(ctx); settings = SettingsStore(ctx); store = ProfileStore(ctx)
        store.profiles.value.forEach { store.delete(it.id) }
        settings.setMode(PowerMode.API_KEY); settings.setProvider(Provider.ANTHROPIC); settings.setApiKey(Provider.ANTHROPIC, "sk-test")
    }

    class Ctx(override val agentLabel: String = "main", private val answer: String? = "Approve") : ToolContext {
        val asked = mutableListOf<String>()
        override suspend fun activity(text: String, meta: JSONObject) = 0L
        override suspend fun updateActivity(id: Long, text: String, meta: JSONObject) {}
        override suspend fun ask(question: String, options: List<String>): String? { asked += question; return answer }
        override suspend fun notify(title: String, body: String) {}
    }

    private val work = AccountRef("ca_work", "gmail", "sam@northwind.example")
    private val home = AccountRef("ca_home", "gmail", "me@gmail.com")
    private val slack = AccountRef("ca_slack", "slack", "Northwind")
    private fun guard(profile: AgentProfile? = null) = AppGuard({ store.rules(it) }, { slugs -> listOf(work, home, slack).filter { it.slug in slugs } }, { profile })

    @Test fun sendingFromAnAccountSetToAllowedDoesNotAsk() = runBlocking {
        store.setRules(home.id, AccountRules(change = Rule.ALLOW))
        val c = Ctx()
        val v = guard().check(c, listOf("GMAIL_SEND_EMAIL"), home.id, "{}")
        assertNull(v.refuse); assertTrue(c.asked.isEmpty())
        // The other Gmail still asks, and the approval names the account.
        val v2 = guard().check(c, listOf("GMAIL_SEND_EMAIL"), work.id, "{}")
        assertNull(v2.refuse); assertTrue(c.asked.single().startsWith("APPROVAL|gmail send email · sam@northwind.example|"))
    }

    @Test fun neverMeansNeverEvenWithApproval() = runBlocking {
        store.setRules(work.id, AccountRules(change = Rule.NEVER))
        val c = Ctx()
        val v = guard().check(c, listOf("GMAIL_SEND_EMAIL"), work.id, "{}")
        assertTrue(v.refuse!!.startsWith("Not allowed")); assertTrue(c.asked.isEmpty())
        // Reading the same account is still fine.
        assertNull(guard().check(c, listOf("GMAIL_FETCH_EMAILS"), work.id, "{}").refuse)
    }

    @Test fun withNoAccountNamedTheStrictestOfThatAppsAccountsApplies() = runBlocking {
        store.setRules(home.id, AccountRules(change = Rule.ALLOW))
        val c = Ctx()
        guard().check(c, listOf("GMAIL_SEND_EMAIL"), null, "{}")
        assertEquals(1, c.asked.size) // the work account still asks, so the default one might be it
    }

    @Test fun aHelperInAProfileOnlyUsesThatProfilesAccounts() = runBlocking {
        val p = store.create("Northwind work", listOf(work, slack))
        val c = Ctx("helper-7")
        // Its one Gmail is picked for it.
        val v = guard(p).check(c, listOf("GMAIL_FETCH_EMAILS"), null, "{}")
        assertNull(v.refuse); assertEquals(work.id, v.account)
        // Another Gmail is refused, and so is an app the profile doesn't have.
        assertTrue(guard(p).check(c, listOf("GMAIL_FETCH_EMAILS"), home.id, "{}").refuse!!.contains("isn't in your profile"))
        assertTrue(guard(store.create("Personal", listOf(home))).check(c, listOf("SLACK_SEND_MESSAGE"), null, "{}").refuse!!.contains("has no Slack"))
    }

    @Test fun anAccountCanBeInManyProfilesAndLeavesThemAllWhenDisconnected() {
        val a = store.create("Work", listOf(work)); val b = store.create("Everything", listOf(work, home))
        assertEquals(setOf(a.id, b.id), store.profilesWith(work.id).map { it.id }.toSet())
        store.setRules(work.id, AccountRules(change = Rule.ALLOW))
        store.forgetAccount(work.id)
        assertTrue(store.profilesWith(work.id).isEmpty())
        assertEquals(AccountRules.DEFAULT, store.rules(work.id))
        assertEquals(listOf(home.id), store.find("everything")!!.accounts.map { it.id })
    }

    @Test fun writeWordsAreRecognised() {
        assertTrue(AppGuard.isWrite("GMAIL_SEND_EMAIL", "gmail"))
        assertTrue(AppGuard.isWrite("GMAIL_ADD_LABEL_TO_EMAIL", "gmail"))
        assertTrue(AppGuard.isWrite("MICROSOFT_TEAMS_SEND_MESSAGE", "microsoft_teams"))
        assertFalse(AppGuard.isWrite("GMAIL_FETCH_EMAILS", "gmail"))
        assertFalse(AppGuard.isWrite("GMAIL_GET_PROFILE", "gmail"))
        assertEquals(listOf("microsoft", "microsoft_teams"), AppGuard.candidates("MICROSOFT_TEAMS_SEND_MESSAGE"))
    }

    // ---- the main agent and its helpers -----------------------------------------------------------------

    private fun runtime(p: ScriptedProvider) = AgentRuntime(ctx, db, settings, CoroutineScope(SupervisorJob() + Dispatchers.IO), FakePhone(), { p })
    private suspend fun idle(rt: AgentRuntime) = withTimeout(15_000) { rt.awaitIdle() }

    @Test fun helpersStartedTogetherReportOnceWhenAllAreDone() = runBlocking {
        val p = ScriptedProvider(call("delegate", JSONObject().put("tasks", JSONArray(listOf("price at A", "price at B", "price at C"))).put("together", true)),
            say("Checking three shops."), say("A is cheapest at R 899."))
        val rt = runtime(p)
        rt.send("Which shop is cheapest?")
        idle(rt)
        val turns = p.seen.filter { !it.first.contains("You are a HELPER") }.map { it.second.last { m -> m.role == Role.USER && m.blocks.any { b -> b is Block.Text } }.text }
        val back = turns.filter { it.contains("[Helper") || it.contains("[Helpers finished") }.distinct()
        assertEquals("one packed message, got $back", 1, back.size)
        assertTrue(back.single().startsWith("[Helpers finished: all 3"))
        assertTrue(listOf("A", "B", "C").all { back.single().contains("helper found: price at $it") })
    }

    @Test fun theReplyQuotesWhatItAnswersAndHoldStaysQuiet() = runBlocking {
        val p = ScriptedProvider(delegate("Find the Durban library hours"), say("On it."), say("HOLD"))
        val rt = runtime(p)
        rt.send("When does the library open?")
        idle(rt)
        val items = db.chat().all().first()
        assertTrue("HOLD must never reach the chat", items.none { it.kind == "agent" && it.text.contains("HOLD") })
        val ask = items.first { it.kind == "user" }
        val onIt = items.first { it.kind == "agent" && it.text == "On it." }
        assertEquals(ask.id, JSONObject(onIt.meta).optLong("replyTo"))
        // Right after the question it needs no quote...
        assertNull(quotedFor(onIt, items, items.associateBy { it.id }))
        // ...but a later answer to it, with other talk in between, shows what it answers.
        val later = db.chat().insert(ChatItem(kind = "agent", text = "Opens at 8.", meta = JSONObject().put("replyTo", ask.id).put("late", true).toString()))
        val all = db.chat().all().first()
        assertEquals(ask.id, quotedFor(all.first { it.id == later }, all, all.associateBy { it.id })?.id)
    }

    @Test fun aHelperGivenAProfileIsToldItsAccounts() = runBlocking {
        val prof = ProfileStore(ctx).create("Northwind work", listOf(work, slack))
        val p = ScriptedProvider(call("delegate", JSONObject().put("tasks", JSONArray(listOf("Reply to the latest client email"))).put("profiles", JSONArray(listOf("northwind work")))),
            say("On it."), say("Done."))
        val rt = runtime(p)
        rt.send("Reply to the client")
        idle(rt)
        val sys = p.seen.first { it.first.contains("You are a HELPER") }.first
        assertTrue(sys.contains("YOUR PROFILE: Northwind work") && sys.contains("account ca_work") && !sys.contains("ca_home"))
        assertEquals("Northwind work", JSONObject(db.chat().all().first().first { it.kind == "helper" }.meta).getString("profile"))
        // The main agent sees the profiles it can hand out.
        assertTrue(rt.systemPrompt("x").contains("PROFILES: every helper works inside exactly one"))
        assertTrue(rt.systemPrompt("x").contains(prof.name))
    }

    @Test fun anUnknownProfileIsRefusedWithTheRealOnes() = runBlocking {
        ProfileStore(ctx).create("Personal")
        val p = ScriptedProvider(call("delegate", JSONObject().put("tasks", JSONArray(listOf("x"))).put("profiles", JSONArray(listOf("Work")))), say("Hmm."))
        val rt = runtime(p)
        rt.send("go"); idle(rt)
        val res = (p.seen[1].second.last().blocks.single() as Block.ToolResult).content
        assertTrue(res, res.contains("No profile called \"Work\"") && res.contains("Personal"))
    }

    @Test fun aReportBecomesACardWithTheFullTextOnThePhone() = runBlocking {
        val p = ScriptedProvider(call("report", JSONObject().put("title", "Cyber tender readiness").put("summary", "Two gaps: ISO 27001 and a SOC partner.")
            .put("markdown", "## Gaps\n\n- ISO 27001\n- SOC partner\n")), say("Two gaps; the report has the plan."))
        val rt = runtime(p)
        rt.send("Are we ready for the cyber tender?")
        // Poll rather than block the test's main thread in join (Robolectric's looper then never runs the turn).
        withTimeout(20_000) { while (db.chat().all().first().none { it.kind == "agent" }) kotlinx.coroutines.delay(200) }
        idle(rt)
        val card = db.chat().all().first().single { it.kind == "report" }
        val meta = JSONObject(card.meta)
        assertEquals("Cyber tender readiness", meta.getString("title"))
        assertTrue(java.io.File(meta.getString("path")).readText().contains("## Gaps"))
        assertEquals(db.chat().all().first().first { it.kind == "user" }.id, meta.optLong("replyTo"))
    }

    @Test fun everyHelperGetsAProfileGeneralWhenNoneIsNamed() = runBlocking {
        val p = ScriptedProvider(delegate("Weather in Durban tomorrow"), say("On it."), say("Sunny."))
        val rt = runtime(p)
        rt.send("Weather?"); idle(rt)
        val meta = JSONObject(db.chat().all().first().first { it.kind == "helper" }.meta)
        assertEquals(AgentProfile.GENERAL, meta.getString("profile")); assertEquals("graphite", meta.getString("profileColor"))
        assertTrue(p.seen.first { it.first.contains("You are a HELPER") }.first.contains("YOUR PROFILE: General"))
    }

    @Test fun theAgentCanMakeAProfileWithTheAppsItNeeds() = runBlocking {
        val tool = com.past9.phoneaos.tools.ProfileCreateTool(store) { slugs -> listOf(work, home, slack).filter { it.slug in slugs } }
        val out = tool.run(JSONObject().put("name", "Job hunt").put("apps", JSONArray(listOf("gmail:me@gmail.com", "linkedin"))), Ctx())
        val p = store.find("job hunt")!!
        assertEquals(listOf(home.id), p.accounts.map { it.id })
        assertTrue(out, out.contains("linkedin"))
        assertTrue(p.color in AgentProfile.COLORS)
        assertNotEquals("A second profile gets a different colour", p.color, store.create("Side gig").color)
    }
}
