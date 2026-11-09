package com.past9.phoneaos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.agent.AgentStatus
import com.past9.phoneaos.agent.ModelInfo
import com.past9.phoneaos.data.*
import com.past9.phoneaos.miniapps.MiniAppInfo
import com.past9.phoneaos.miniapps.MiniAppsScreen
import com.past9.phoneaos.miniapps.PinnedAppsRow
import com.past9.phoneaos.remote.BrainRef
import com.past9.phoneaos.remote.Link
import com.past9.phoneaos.remote.LinkScreen
import com.past9.phoneaos.remote.LinkedDevicesScreen
import com.past9.phoneaos.remote.LiveCard
import com.past9.phoneaos.remote.LiveCardList
import com.past9.phoneaos.remote.LiveCards
import com.past9.phoneaos.remote.LiveCardsUi
import com.past9.phoneaos.remote.LiveStep
import com.past9.phoneaos.remote.AgentsPill
import com.past9.phoneaos.remote.MentionProject
import com.past9.phoneaos.remote.RemoteHub
import com.past9.phoneaos.remote.RemoteIds
import com.past9.phoneaos.tools.Connection
import com.past9.phoneaos.tools.Toolkit
import com.past9.phoneaos.ui.CardTiles
import com.past9.phoneaos.ui.ChoiceSheet
import com.past9.phoneaos.ui.ComposerActions
import com.past9.phoneaos.ui.ConfirmSheet
import com.past9.phoneaos.ui.HelperCard
import com.past9.phoneaos.ui.HelperView
import com.past9.phoneaos.ui.LocalSheetPreview
import com.past9.phoneaos.ui.SheetChoice
import com.past9.phoneaos.ui.screens.*
import com.past9.phoneaos.ui.theme.AppTheme
import com.past9.phoneaos.voice.CallState
import com.past9.phoneaos.voice.DictationTape
import com.past9.phoneaos.voice.Dictations
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * UI audit, 9 Nov 2026: the screens, sheets and states ScreensTest does not draw (project chats, helper cards and the
 * pill, recordings, wake-ups, mini apps, the browser, Settings scrolled end to end, linking, the call, the model
 * picker, subscription setup, empty, loading and error states) plus stress states (long names, unbroken words, long
 * button labels, big counts). Each one at 390dp and at 360dp wide, light and dark.
 * PNGs land in app/build/audit/<width>/<name>-light|dark.png.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AuditShotsTest(private val width: Int, private val height: Int, private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "w{0}-dark={2}")
        fun params() = listOf(arrayOf<Any>(390, 844, false), arrayOf<Any>(390, 844, true), arrayOf<Any>(360, 780, false), arrayOf<Any>(360, 780, true))
    }
    @get:Rule val rule = createComposeRule()

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun start(content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("w${width}dp-h${height}dp-xxhdpi")
        rule.mainClock.autoAdvance = false
        // The page colour comes from the Surface each route sits on in MainActivity; without it Home and Profile draw on white in dark.
        rule.setContent { AppTheme(dark = dark) { Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) { content() } } }
        rule.mainClock.advanceTimeBy(900)
    }
    private fun cap(name: String) {
        File("build/audit/$width").mkdirs()
        rule.onRoot().captureRoboImage(File("build/audit/$width/$name-${if (dark) "dark" else "light"}.png").path)
    }
    private fun tick(ms: Long = 900) = rule.mainClock.advanceTimeBy(ms)
    private fun shot(name: String, content: @Composable () -> Unit) { start(content); cap(name) }
    private fun preview(content: @Composable () -> Unit) = start { CompositionLocalProvider(LocalSheetPreview provides true) { content() } }
    private fun sheet(name: String, content: @Composable () -> Unit) { preview(content); cap(name) }
    /** Loose parts (cards, chips, rows) on the app's own surface. */
    private fun parts(name: String, content: @Composable () -> Unit) = shot(name) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
    private fun scrollDown() { rule.onRoot().performTouchInput { swipeUp(startY = height * 0.8f, endY = height * 0.15f, durationMillis = 300) }; tick(1500) }

    private val now = System.currentTimeMillis()
    private var nextId = 1L
    private fun item(kind: String, text: String, meta: JSONObject = JSONObject()) = ChatItem(id = nextId++, kind = kind, text = text, meta = meta.toString(), createdAt = now)

    private val longName = "Strat Edge Intelligence platform access matrix round three follow-ups"
    private val unbroken = "https://example.com/a/very/long/path/that-never-breaks-anywhere-at-all-0123456789abcdefghijklmnopqrstuvwxyz0123456789"

    // ---------- helper running cards, the pill, project chats and @-mentions ----------
    private fun card(id: String, label: String, state: String = "working", line: String = "Reading the code", project: String = "", color: String = "iris",
                     isProject: Boolean = false, queued: Boolean = false, depth: Int = 0, waiting: Boolean = false, result: String = "") = LiveCard(
        id = id, row = id, chat = if (isProject) "pc-$id" else "main", label = label, state = state, queued = queued, line = line, done = result, depth = depth,
        projectChat = if (isProject) "pc-$id" else "", ownerKind = if (project.isBlank()) "main" else "project", ownerName = project, ownerGlyph = "folder", ownerColor = color, ownerImage = "",
        model = "claude-sonnet-5-5", startedAt = now - 4 * 60_000, endedAt = if (state == "working") 0L else now - 30_000, brief = "$label: do the whole job and report back with what changed.",
        result = result, steps = listOf(LiveStep("Read 14 files", "done", now - 200_000), LiveStep("Running the tests", "running", now - 20_000)),
        ownerId = if (project.isBlank()) "" else "p-$project", waiting = waiting, activityAt = now - 20_000)
    private val ids = RemoteIds()
    private fun liveUi(cards: List<LiveCard>) = LiveCardsUi(cards, { c -> LiveCards.view(c, ids) }, {})
    private val liveCards get() = listOf(
        card("a", "Impetix Inbox", project = "Impetix Inbox", color = "ocean", isProject = true, line = "Fixing the onboarding 404"),
        card("b", "Quotes page redesign", project = "Impetix Inbox", color = "ocean", depth = 1, line = "Checking 1440 and 390 in light and dark"),
        card("c", "Render every screen", line = "Running Gradle"),
        card("d", longName, project = longName, color = "ember", isProject = true, line = "Waiting for you", state = "done", waiting = true, result = "Done: 31 routes moved into the registry."),
        card("e", "Queued research", queued = true, line = "Queued"),
        card("f", "Evetech follow-up", state = "failed", result = "Could not sign in: the code expired."),
    )

    @Test fun liveCardList() { parts("a02-live-cards") { LiveCardList(liveUi(liveCards)) } }
    @Test fun liveCardsMany() { parts("a02b-live-cards-many") { LiveCardList(liveUi((1..9).map { card("m$it", if (it % 3 == 0) "$longName $it" else "Helper $it", project = if (it % 2 == 0) "GrowOS" else "", color = "mint", depth = if (it % 4 == 0) 1 else 0) })) } }
    @Test fun agentsPill() { parts("a03-agents-pill") {
        AgentsPill(liveUi(liveCards), {})
        AgentsPill(liveUi((1..12).map { card("p$it", "Helper $it", project = "Project number $it with a long name", color = listOf("ocean", "mint", "ember", "iris")[it % 4], isProject = it % 2 == 0) }), {})
        AgentsPill(liveUi(listOf(card("one", "Only one"))), {})
    } }
    @Test fun chatWithLiveCards() {
        val items = listOf(
            item("user", "@Impetix Inbox fix the onboarding 404 and tell me when it's live", JSONObject().put("mention", JSONObject().put("project", "p1").put("name", "Impetix Inbox").put("chat", "pc1").put("fresh", true).put("said", "fix the onboarding 404 and tell me when it's live"))),
            item("agent", "Fixed and deployed to the preview. The onboarding link now opens the first step instead of a 404.", JSONObject().put("mentionReply", JSONObject().put("project", "p1").put("name", "Impetix Inbox").put("chat", "pc1").put("glyph", "mail").put("color", "ocean"))),
            item("user", "@$longName where are we", JSONObject().put("mention", JSONObject().put("project", "p2").put("name", longName).put("chat", "pc2").put("fresh", false).put("said", "where are we"))),
            item("agent", "Three helpers are on it now."),
        )
        shot("a04-chat-live-cards-mentions") { ChatScreen(items, AgentStatus(true, "Working", 3), "Hina", "Ayanda", false, "", {}, ChatActions(), live = liveUi(liveCards.take(4))) }
    }
    @Test fun mentionChipAndComposer() { parts("a05-mention-chip") {
        MentionChip(MentionProject("p1", "Impetix Inbox", emptyList(), "mail", "ocean", "", 0L)) {}
        MentionChip(MentionProject("p2", longName, emptyList(), "folder", "ember", "", 0L)) {}
        MentionChip(MentionProject("p3", "X", emptyList(), "", "", "", 0L)) {}
        MessageComposer("@Impetix fix the follow-ups and $unbroken", {}, false, ComposerActions(onSend = {}), emptyList())
        MessageComposer("", {}, true, ComposerActions(onSend = {}), emptyList(), failed = "Not connected to your computer. Your words are still here.", onRetry = {})
        MessageComposer("Typing while it listens", {}, false, ComposerActions(onSend = {}, onRecordings = {}), listOf("/nope/a.jpg", "/nope/b.jpg"), docs = listOf("/nope/Strat Edge Intelligence who can see what - final v3 (signed).pdf"), listening = true)
    } }
    @Test fun projectChatNotLinked() { shot("a06-project-chat-no-computer") { ProjectChatScreen("pc1", "Impetix Inbox", {}, liveUi(liveCards)) } }
    @Test fun projectChatLongName() { shot("a06b-project-chat-long-name") { ProjectChatScreen("pc2", longName, {}) } }
    @Test fun helperCardStates() {
        val views = HelperView.from(listOf(
            item("helper", "Short job", JSONObject().put("label", "Short").put("state", "working").put("now", "Browsing").put("startedAt", now - 60_000)),
            item("helper", "Long job", JSONObject().put("label", longName).put("state", "working").put("now", "Reading $unbroken").put("startedAt", now - 99 * 60_000).put("model", "anthropic/claude-opus-5-5-20260801-thinking").put("profile", "Strat Edge Works and partners").put("profileColor", "ocean")),
            item("helper", "Done job", JSONObject().put("label", "Sharks tickets").put("state", "done").put("startedAt", now - 30 * 60_000).put("endedAt", now - 22 * 60_000).put("result", "Tickets from R 180, about 40 left.")),
            item("helper", "Failed job", JSONObject().put("label", "Evetech follow-up").put("state", "failed").put("startedAt", now - 30 * 60_000).put("endedAt", now - 22 * 60_000).put("result", "Could not sign in: the code expired after $unbroken")),
        ))
        parts("a07-helper-cards") { views.forEach { HelperCard(it, {}, now = now) }; views.forEach { HelperCard(it, {}, now = now, compact = true) } }
    }
    @Test fun helperSheetFailedLong() {
        val v = HelperView.from(listOf(item("helper", "Sign in to Evetech with the saved login and ask where order 4471 is. $unbroken",
            JSONObject().put("label", longName).put("state", "failed").put("startedAt", now - 30 * 60_000).put("endedAt", now - 22 * 60_000).put("model", "anthropic/claude-opus-5-5-20260801-thinking").put("result", "Could not sign in: the code expired.\n\n$unbroken"))))[0]
        sheet("a08-sheet-helper-failed-long") { com.past9.phoneaos.ui.HelperSheet(v, {}, {}, now = now, onGoToChat = {}) }
    }

    // ---------- recordings ----------
    private fun seedRecordings(many: Boolean) {
        val f = Dictations.of(ctx)
        f.list().forEach { f.forget(it.id) }
        if (!many) return
        File(f.dir, "20261109-105630.wav").writeBytes(DictationTape.header(0) + ByteArray(150 * DictationTape.BYTES_PER_SECOND))
        f.note("20261109-105630", now - 60_000, 150_000, "Still talking about the access matrix and", "recording", "main", flush = true)
        File(f.dir, "20261108-093000.wav").writeBytes(DictationTape.header(0) + ByteArray(64 * DictationTape.BYTES_PER_SECOND))
        f.note("20261108-093000", now - 86_400_000L, 64_000, "So what I was saying is I want the whole thing finished and deployed so I can test it fully on my phone, and everyone can see it before the weekend. $unbroken", "done", "main", flush = true)
        File(f.dir, "20261107-204113.wav").writeBytes(DictationTape.header(0) + ByteArray(10 * DictationTape.BYTES_PER_SECOND))
        f.note("20261107-204113", now - 2 * 86_400_000L, 10_000, "The first half came through before the app was closed.", "interrupted", "main", flush = true)
        File(f.dir, "20261106-204113.wav").writeBytes(DictationTape.header(0) + ByteArray(3700 * DictationTape.BYTES_PER_SECOND / 10))
        f.note("20261106-204113", now - 3 * 86_400_000L, 3_700_000, "", "kept", "pc1", flush = true)
        f.note("20261105-101010", now - 4 * 86_400_000L, 9_000, "Audio is gone but the words stayed.", "done", "main", flush = true)
    }
    @Test fun recordingsEmpty() { seedRecordings(false); sheet("a10-recordings-empty") { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { RecordingsScreen("main") {} } } }
    @Test fun recordingsStates() { seedRecordings(true); sheet("a11-recordings-states") { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { RecordingsScreen("main") {} } } }
    @Test fun recordingRecoveredSheet() {
        seedRecordings(true)
        preview { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { Box(Modifier.fillMaxSize()) { RecordingsScreen("main") {} } } }
        rule.onNodeWithText("The first half came through", substring = true).performClick(); tick(1200)
        cap("a12-recording-recovered-sheet")
    }

    // ---------- wake-ups, hand-offs ----------
    @Test fun wakeupsNotLinked() { sheet("a15-sheet-wakeups-not-linked") { WakeupsSheet {} } }
    @Test fun handoffSheet() { sheet("a16-sheet-handoffs") { com.past9.phoneaos.handoff.HandoffSheet {} } }

    // The ringing wake-up screen is a plain Android view (wakeup/WakeUpActivity.kt, WakeScreen), drawn here as the activity shows it.
    private fun ringing(name: String, severity: String, title: String, what: String, link: String, cancelled: Boolean = false) = shot(name) {
        val r = com.past9.phoneaos.wakeup.Ringing(com.past9.phoneaos.wakeup.Wake("w-$name", severity, title, what, link, "meeting", now - 120_000, cancelled, emptyList(), emptySet(), "Guavas Slack desk"), true)
        androidx.compose.ui.viewinterop.AndroidView(factory = { c -> com.past9.phoneaos.wakeup.WakeScreen.build(c, r, {}, {}, { _, _ -> }) }, modifier = Modifier.fillMaxSize())
    }
    @Test fun wakeRingingUrgent() { ringing("a17-wake-ringing-urgent", "urgent", "Standup with the product team starts now", "Join the standup on Teams; Thabo is presenting your numbers", "https://teams.example/join") }
    @Test fun wakeRingingCritical() { ringing("a18-wake-ringing-critical-long", "critical", "$longName: the live site is down for every customer", "Open the status page, restart the engine service on the pilot server and tell Siphelele Mofokeng and Bongani what happened. $unbroken", "") }
    @Test fun wakeRingingHeadsUp() { ringing("a19-wake-ringing-heads-up-cancelled", "heads_up", "Ben is waiting for the Academy note", "Send it from Slack", "https://slack.example/t", cancelled = true) }

    // ---------- mini apps ----------
    private val apps get() = listOf(
        MiniAppInfo("cal", "Week calendar", "Every calendar in one week view", "clock", 1, 3, true, now - 3_600_000),
        MiniAppInfo("ten", "Tender desk dashboard for Strat Edge Intelligence", "Open tenders, readiness and what is due this week, by bid manager and by client, refreshed every 15 minutes", "grid", 2, 12, true, now - 86_400_000L),
        MiniAppInfo("out", "Outreach", "Sent, replies and follow-ups", "mail", 0, 1, false, 0L),
        MiniAppInfo("cos", "Cosmiprint score", "Views per video", "sun", 3, 4, true, now),
        MiniAppInfo("mon", "Money", "", "", 4, 1, true, now),
        MiniAppInfo("xx", "Supercalifragilisticexpialidocious", "Unbroken", "apps", 5, 1, true, now),
    )
    @Test fun miniAppsList() { shot("a20-miniapps") { MiniAppsScreen(apps, {}, {}) { _, _ -> } } }
    @Test fun miniAppsEmpty() { shot("a21-miniapps-empty") { MiniAppsScreen(emptyList(), {}, {}) { _, _ -> } } }
    @Test fun pinnedAppsRow() { parts("a22-pinned-apps-row") { PinnedAppsRow(apps, {}, {}); PinnedAppsRow(apps.take(1), {}, {}) } }

    // ---------- browser ----------
    @Test fun browserManyProfiles() { shot("a25-browser-many-profiles") { BrowserScreen(emptyMap(), listOf("Personal", "Strat Edge Works", "Guavas", "Impetix and Meqry outreach", "Cosmiprint"), BrowserActions()) } }
    @Test fun browserNewProfileSheet() {
        preview { BrowserScreen(emptyMap(), listOf("Personal", "Work"), BrowserActions()) }
        rule.onNodeWithText("Profile").performClick(); tick()
        cap("a26-browser-new-profile-sheet")
    }
    @Test fun browserOpenTabs() {
        val engines = mapOf("main/Personal" to com.past9.phoneaos.browser.BrowserEngine(ctx, "Personal"), "main/Work" to com.past9.phoneaos.browser.BrowserEngine(ctx, "Work"),
            "Evetech follow-up helper with a long name/1" to com.past9.phoneaos.browser.BrowserEngine(ctx, "Personal"))
        shot("a27-browser-open-tabs") { BrowserScreen(engines, listOf("Personal", "Work"), BrowserActions()) }
    }

    // ---------- settings, every section ----------
    private val keySettings = AgentSettings(mode = PowerMode.API_KEY, provider = Provider.ANTHROPIC, model = "claude-sonnet-5-5", helperModel = "claude-haiku-4-5-20251001", hasKey = true, userName = "Ayanda", agentName = "Hina", onboarded = true)
    private val subSettings = keySettings.copy(mode = PowerMode.SUBSCRIPTION, subKind = SubKind.CLAUDE, subModel = "opus", ttsProvider = "gemini",
        helperRoster = listOf(HelperModel("anthropic/claude-sonnet-5-5", "Claude Sonnet 5.5", listOf("Web research", "Browsing sites", "Writing")), HelperModel("deepseek/deepseek-v4.1-flash", "DeepSeek V4.1 Flash", listOf("Quick lookups"))))
    private fun settingsScrolled(name: String, s: AgentSettings, info: RuntimeInfo, actions: SettingsActions = SettingsActions()) {
        start { SettingsScreen(s, info, "0.15.28", actions) }
        cap("$name-1-top"); scrollDown(); cap("$name-2-middle"); scrollDown(); cap("$name-3-bottom")
    }
    @Test fun settingsApiKey() { settingsScrolled("a30-settings-key", keySettings, RuntimeInfo(false, 0, false, "Runtime pack not in this build")) }
    @Test fun settingsSubscription() { settingsScrolled("a31-settings-sub", subSettings, RuntimeInfo(true, 412_000_000, true, "Signed in"), SettingsActions(linkedLine = "Linked to DESKTOP-PAST9 · answering now")) }
    @Test fun settingsStress() {
        settingsScrolled("a32-settings-stress", subSettings.copy(userName = "Ayanda Thandolwethu Phaketsi-van der Westhuizen", agentName = "Hina the Extraordinarily Long Named Agent", subModel = "claude-opus-5-5-20260801-thinking-extended-1m"),
            RuntimeInfo(true, 1_912_000_000, false, "Not signed in: the sign-in expired on 3 Nov and the runtime needs you to sign in again from this phone"),
            SettingsActions(linkedLine = "Linked to DESKTOP-PAST9-WORKSTATION-OFFICE-UMHLANGA · nothing can answer right now, your computers are off or out of reach"))
    }
    @Test fun settingsNoPower() { shot("a33-settings-no-power") { SettingsScreen(AgentSettings(onboarded = true), RuntimeInfo(false, 0, false, ""), "0.15.28", SettingsActions()) } }
    @Test fun settingsSubPickSheet() {
        preview { SettingsScreen(keySettings, RuntimeInfo(false, 0, false, "Not set up"), "0.15.28", SettingsActions()) }
        rule.onNodeWithText("Subscription").performClick(); tick()
        cap("a34-settings-sheet-subscription")
    }
    @Test fun settingsNameSheet() {
        preview { SettingsScreen(keySettings, RuntimeInfo(false, 0, false, "Not set up"), "0.15.28", SettingsActions()) }
        rule.onNodeWithText("Your name").performClick(); tick()
        cap("a35-settings-sheet-name")
    }
    @Test fun settingsResetSheet() {
        preview { SettingsScreen(keySettings, RuntimeInfo(false, 0, false, "Not set up"), "0.15.28", SettingsActions()) }
        scrollDown(); scrollDown(); scrollDown(); rule.onNodeWithText("Reset everything").performClick(); tick()
        cap("a36-settings-sheet-reset")
    }
    @Test fun voiceSheet() { sheet("a37-sheet-voice-phone") { VoiceSheet(keySettings, VoiceActions()) {} } }
    @Test fun voiceSheetGoogle() { sheet("a38-sheet-voice-google-live") { VoiceSheet(keySettings.copy(ttsProvider = "gemini", ttsVoice = "Achernar", liveEnabled = true, speakReplies = true), VoiceActions(hasKey = { true })) {} } }
    @Test fun voiceSheetNoKey() { sheet("a39-sheet-voice-elevenlabs-no-key") { VoiceSheet(keySettings.copy(ttsProvider = "elevenlabs"), VoiceActions()) {} } }

    // ---------- linking and devices ----------
    @Test fun linkReady() { shot("a40-link-scan") { LinkScreen({}, {}) } }
    @Test fun linkTyping() {
        start { LinkScreen({}, {}) }
        runCatching { rule.onNodeWithText("Type the address and code instead", substring = true).performClick() }; tick()
        cap("a41-link-type-address")
        runCatching { rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("http://192.168.1.20") }; tick()
        cap("a42-link-address-problem")
    }
    @Test fun linkBadCode() { shot("a43-link-bad-code") { LinkScreen({}, {}, offerText = "this is not a pairing code") } }
    @Suppress("UNCHECKED_CAST")
    @Test fun linkedDevices() {
        val f = RemoteHub::class.java.getDeclaredField("_link").apply { isAccessible = true }
        val flow = f.get(null) as MutableStateFlow<Link?>
        val before = flow.value
        try {
            flow.value = Link("mesh1", "phone1", "pass", "DESKTOP-PAST9-WORKSTATION-OFFICE", listOf(
                BrainRef("b1", "DESKTOP-PAST9-WORKSTATION-OFFICE", listOf("https://192.168.1.20:47600"), "fp1", 1),
                BrainRef("b2", "impetix", listOf("https://159.195.109.117:47600", "https://impetix.example.net:47600"), "fp2", 2),
                BrainRef("b3", "Second PC", listOf("https://192.168.1.31:47600"), "fp3", 3)), now - 12 * 86_400_000L, on = false)
            start { LinkedDevicesScreen {} }
            cap("a44-linked-devices-1-top"); scrollDown(); cap("a44-linked-devices-2-bottom")
        } finally { flow.value = before }
    }

    // ---------- notifications ----------
    @Test fun notificationsOnWithManyApps() {
        val appList = (1..14).map { PhoneApp("com.app$it", if (it == 3) "An application with a very long label that keeps going" else "App $it", null) }
        val rows = listOf(NotificationRow(1, "com.app1", "App 1", "Payment received from a company with a very long registered name (Pty) Ltd", "R 2,500.00 from ACME PTY LTD into Savings. $unbroken", now - 600_000),
            NotificationRow(2, "com.app2", "App 2", "", "", now - 3_600_000))
        shot("a50-notifications-on") { NotificationsScreen(true, appList, setOf("com.app1", "com.app3"), rows, NotificationsActions()) }
    }
    @Test fun notificationsEmpty() { shot("a51-notifications-empty") { NotificationsScreen(true, emptyList(), emptySet(), emptyList(), NotificationsActions()) } }

    // ---------- the call ----------
    @Test fun callIdle() { shot("a55-call-idle") { CallScreen("Hina", CallState(), {}, {}, {}) } }
    @Test fun callConnecting() { shot("a56-call-connecting") { CallScreen("Hina", CallState(active = true, phase = "connecting"), {}, {}, {}) } }
    @Test fun callListeningMuted() { shot("a57-call-muted") { CallScreen("Hina", CallState(active = true, phase = "listening", muted = true, mine = 0.4f), {}, {}, {}) } }
    @Test fun callWorking() { shot("a58-call-working-reply") { CallScreen("Hina the Extraordinarily Long Named Agent", CallState(active = true, phase = "speaking", theirs = 0.7f,
        delegated = "Find the three cheapest flights from Joburg to Durban on Friday morning", reply = "FlySafair 06:05 is the cheapest at R 899. Lift 07:30 is R 1,020 and FlySafair 09:40 is R 1,049. All three still have window seats. Want me to hold the 06:05?"),
        {}, {}, {}, actions = listOf("Searched memory", "Opened FlySafair", "Read 24 results")) } }
    @Test fun callWithApproval() { shot("a59-call-approval") { CallScreen("Hina", CallState(active = true, phase = "listening", delegated = "Book the flight"), {}, {}, {},
        question = item("question", "APPROVAL|Book FlySafair FA 151, Fri 9 Oct 06:05|Passenger: Sam Dlamini\nSeat: 14A (window)\nFare: R 899 incl. taxes\nPaid with: card ending 4421\nRefund: none after 24 hours\nBaggage: 20 kg checked")) } }
    @Test fun callFailed() { shot("a60-call-failed") { CallScreen("Hina", CallState(phase = "failed", error = "The voice key was refused (401). Check the Google key in Settings, Voice."), {}, {}, {}) } }
    @Test fun callEnded() { shot("a61-call-ended") { CallScreen("Hina", CallState(phase = "ended", lines = listOf("you" to "Book the early one", "agent" to "Done, FA 151 is held.")), {}, {}, {}) } }

    // ---------- model picker ----------
    private val models = listOf(ModelInfo("anthropic/claude-opus-5-5", "Claude Opus 5.5", true, "2026-08-01"), ModelInfo("anthropic/claude-sonnet-5-5", "Claude Sonnet 5.5", true, "2026-08-01"),
        ModelInfo("deepseek/deepseek-v4.1-flash", "DeepSeek V4.1 Flash", false, "2026-09-01", note = "Cheap and fast"), ModelInfo("google/gemini-3-flash", "Gemini 3 Flash", true, "2026-07-01"),
        ModelInfo("openrouter/some-vendor/an-extremely-long-model-identifier-that-does-not-break-20261101-preview", "An Extremely Long Model Name That Goes On Preview Extended Thinking 1M", true, "2026-11-01", note = "New this week"),
        ModelInfo("openai/gpt-5-mini", "GPT-5 mini", true, "2026-05-01"), ModelInfo("x/y", "", false, ""))
    @Test fun modelPicker() { sheet("a65-sheet-model-picker") { ModelPickerSheet("Model", models, "anthropic/claude-sonnet-5-5", null, {}, {}, subtitle = "What your agent thinks with") } }
    @Test fun modelPickerLoading() { sheet("a66-sheet-model-picker-loading") { ModelPickerSheet("Model", null, "", null, {}, {}) } }
    @Test fun modelPickerError() { sheet("a67-sheet-model-picker-error") { ModelPickerSheet("Helpers' model", null, "", "Couldn't load the models: 401 Unauthorized. Check your key in Settings and try again.", {}, {}) } }
    @Test fun modelPickerNoMatch() {
        preview { ModelPickerSheet("Model", models, "anthropic/claude-sonnet-5-5", null, {}, {}) }
        runCatching { rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("zzzz") }; tick()
        cap("a68-sheet-model-picker-no-match")
    }

    // ---------- subscription setup ----------
    private val log = listOf("Downloading runtime 41%", "Unpacking node-v22.11.0-linux-arm64", "npm install @anthropic-ai/claude-code", unbroken, "added 212 packages in 48s")
    @Test fun subSetupFresh() { shot("a70-sub-setup-fresh") { SubSetupScreen(SubKind.CLAUDE, "Opens claude.ai in your browser", RuntimeInfo(true, 0, false, "Not set up"), false, emptyList(), false, {}, {}, {}, {}) } }
    @Test fun subSetupInstalling() { shot("a71-sub-setup-installing") { SubSetupScreen(SubKind.CLAUDE, "Opens claude.ai in your browser", RuntimeInfo(true, 120_000_000, false, "Installing"), false, log, true, {}, {}, {}, {}) } }
    @Test fun subSetupSignIn() { shot("a72-sub-setup-sign-in") { SubSetupScreen(SubKind.CODEX, "Opens chatgpt.com in your browser", RuntimeInfo(true, 412_000_000, false, "Installed, not signed in"), true, log, false, {}, {}, {}, {}) } }
    @Test fun subSetupCode() { shot("a73-sub-setup-code-error") { SubSetupScreen(SubKind.CLAUDE, "Opens claude.ai in your browser", RuntimeInfo(true, 412_000_000, false, "Installed, not signed in"), true, log, false, {}, {}, {}, {},
        signingIn = true, signInError = "That code was not accepted. Codes last ten minutes: start the sign-in again and paste the new one.", needsCode = true) } }
    @Test fun subSetupDone() { shot("a74-sub-setup-signed-in") { SubSetupScreen(SubKind.ANTIGRAVITY, "Opens Google sign-in", RuntimeInfo(true, 412_000_000, true, "Signed in"), true, log, false, {}, {}, {}, {},
        modelLabel = "claude-opus-5-5-20260801-thinking-extended-1m") } }
    @Test fun subSetupNoRuntime() { shot("a75-sub-setup-unavailable") { SubSetupScreen(SubKind.OPENCODE, "", RuntimeInfo(false, 0, false, "Runtime pack not in this build"), false, emptyList(), false, {}, {}, {}, {}, verifying = true) } }

    // ---------- memory: empty and no results ----------
    @Test fun memoryEmpty() { shot("a80-memory-empty") { MemoryScreen(emptyList(), MemoryActions()) } }
    @Test fun memoryNoResults() {
        val m = (1..6).map { MemoryRow(it.toLong(), if (it == 2) longName else "Page $it", "Body of page $it. $unbroken", if (it % 2 == 0) "person" else "fact", "topic a, topic b, a much longer topic name than usual, another", pinned = it == 1, updatedAt = now - it * 3_600_000L) }
        start { MemoryScreen(m, MemoryActions()) }
        cap("a81-memory-stress")
        runCatching { rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("zebra crossing") }; tick()
        cap("a82-memory-search-no-results")
    }
    @Test fun wikiPageMissing() { shot("a83-memory-page-missing") { WikiPageScreen(null, emptyList(), {}, {}, {}, {}, {}) } }
    @Test fun wikiPageStress() { shot("a84-memory-page-stress") { val p = MemoryRow(9, longName, "# $longName\n\n$unbroken\n\n| Need | Us | Them | When | Who |\n|---|---|---|---|---|\n| ISO 27001 certificate | No | Yes | 2027 | Siphelele Mofokeng |\n\nSee [[Nothing here]] and [[Acme]].", "decision", "access, strat edge platform, finance, a much longer topic name than usual", pinned = true, updatedAt = now)
        WikiPageScreen(p, listOf(p), {}, {}, {}, {}, {}) } }
    @Test fun reportMissing() { shot("a85-report-missing-file") { ReportScreen("/nope/missing.md") {} } }

    // ---------- shared sheets ----------
    @Test fun confirmSheetDestructive() { sheet("a88-sheet-confirm-destructive") { ConfirmSheet(Icons.Rounded.DeleteForever, "This phone", "Reset everything?", "Deletes keys, memory, tasks and routines on this phone. It cannot be undone.", "Reset everything", {}, {}, destructive = true) } }
    @Test fun confirmSheetLongLabels() { sheet("a89-sheet-confirm-long-labels") { ConfirmSheet(Icons.Rounded.Devices, "Linked devices and every computer that can answer", "Unlink DESKTOP-PAST9-WORKSTATION-OFFICE-UMHLANGA from this phone and stop it answering?", "This phone goes back to its own agent. $unbroken",
        "Unlink this computer, keep the chat that is on the phone and go back to the agent that runs on this phone", {}, {}, cancel = "Keep it linked and carry on using the computer's agent from here") } }
    @Test fun choiceSheetLong() { sheet("a90-sheet-choice-long") { ChoiceSheet(Icons.Rounded.Folder, "Profile", "Which profile is it for?", listOf(
        SheetChoice("a", "Personal", "Your own accounts", selected = true), SheetChoice("b", "Strat Edge Works and every partner company we sell with", "Microsoft 365, Slack, the shared mailbox and the tender desk for the whole group of companies"),
        SheetChoice("c", "Guavas"), SheetChoice("d", "Supercalifragilisticexpialidocious-Supercalifragilisticexpialidocious", unbroken), SheetChoice("e", "Impetix", "Outreach"), SheetChoice("f", "Cosmiprint", "The agents' own business"),
        SheetChoice("g", "MemGallery"), SheetChoice("h", "StudyCanvas", "Learners")), {}, {}, line = "It decides which accounts this app's sign-in belongs to.") } }
    @Test fun addTaskSheet360() { sheet("a91-sheet-task") { AddTaskSheet({}, {}) { _, _ -> } } }

    // ---------- stress states on the main screens ----------
    @Test fun chatStress() {
        val items = listOf(
            item("user", "Check this $unbroken and tell me what Pneumonoultramicroscopicsilicovolcanoconiosis-Pneumonoultramicroscopicsilicovolcanoconiosis means"),
            item("activity", "Opened $unbroken", JSONObject().put("tool", "browser")),
            item("agent", "It is at $unbroken\n\n`pnpm --filter @strat/engine test -- --run modules/finance/server/access.test.ts --reporter=verbose`\n\n| Route | Before | After | Owner | Note |\n|---|---|---|---|---|\n| /api/v1/finance/invoices/:id/reject-over-limit | 404 | 403 | Siphelele Mofokeng | fixed in round 3 |"),
            item("notice", "Something went wrong: the provider answered 529 overloaded after three tries. Your message is kept; send it again in a minute."),
            item("question", "Which of these should the Strat Edge Intelligence tender desk orchestrator do first when a new tender arrives?", JSONObject().put("options", JSONArray(listOf(
                "Read every document in the pack, build the full checklist and only then ask me anything", "Ask me first", "Do the checklist and the pricing sheet at the same time with two helpers and report back when both are done", "Skip")))),
            item("question", "APPROVAL|Send the signed who-can-see-what document to Siphelele Mofokeng, Bongani and the whole Mthashana project team at once|To: siphelele.mofokeng@stratedge.example, bongani@stratedge.example\nSubject: $longName\nAttachment: who-can-see-what-final-v3-signed.pdf"),
        )
        start { ChatScreen(items, AgentStatus(true, "Waiting for you on the approval for the access matrix document", 12), "Hina the Extraordinarily Long Named Agent", "Ayanda", true, "", {}, ChatActions(), openCount = 128, modelLabel = "claude-opus-5-5-20260801-thinking-extended-1m") }
        cap("a92-chat-stress-1-bottom")
        rule.onRoot().performTouchInput { swipeDown(startY = height * 0.3f, endY = height * 0.85f, durationMillis = 300) }; tick(1500)
        cap("a92-chat-stress-2-up")
    }
    @Test fun tasksStress() {
        val goals = listOf(GoalRow(1, longName, "Why: $unbroken"), GoalRow(2, "Empty goal", ""))
        val tasks = (1..14).map { TaskRow(it.toLong(), if (it < 6) 1L else null, if (it % 3 == 0) "$longName number $it and then some more words so it wraps to a third line on a narrow phone" else if (it == 4) unbroken else "Task $it",
            notes = if (it % 4 == 0) "Notes: $unbroken" else "", dueAt = if (it % 2 == 0) now - it * 86_400_000L else now + it * 86_400_000L, status = listOf("todo", "doing", "blocked", "done")[it % 4], owner = if (it % 2 == 0) "user" else "agent",
            blocker = if (it % 4 == 2) "Needs Ayanda to merge PR 19 on the phone repo before any unit test can run, and to approve the deploy" else "") }
        shot("a93-tasks-stress-mine") { TasksScreen(goals, tasks, TaskActions(), startTab = 0) }
    }
    @Test fun tasksStressAgent() {
        val goals = listOf(GoalRow(1, longName, "Why: $unbroken"))
        val tasks = (1..14).map { TaskRow(it.toLong(), if (it < 9) 1L else null, if (it % 3 == 0) "$longName number $it" else "Task $it", status = listOf("todo", "doing", "blocked", "done")[it % 4], owner = "agent",
            blocker = if (it % 4 == 2) "Needs Ayanda to merge PR 19 on the phone repo before any unit test can run, and to approve the deploy" else "") }
        shot("a94-tasks-stress-agent") { TasksScreen(goals, tasks, TaskActions(), startTab = 1) }
    }
    @Test fun homeStress() {
        val helpers = listOf(item("helper", "Long", JSONObject().put("label", longName).put("state", "working").put("now", "Reading $unbroken").put("startedAt", now - 240 * 60_000).put("profile", "Strat Edge Works and partners").put("profileColor", "ocean")),
            item("helper", "Second", JSONObject().put("label", "Second").put("state", "working").put("startedAt", now - 60_000)), item("helper", "Third", JSONObject().put("label", "Third").put("state", "working").put("startedAt", now - 60_000)))
        val s = HomeState("Hina the Extraordinarily Long Named Agent", "Ayanda Thandolwethu Phaketsi-van der Westhuizen", AgentStatus(true, "Waiting for you on the approval for the access matrix document", 128),
            helpers + listOf(item("question", "APPROVAL|Send the signed who-can-see-what document to the whole Mthashana project team|To: everyone")),
            listOf(GoalRow(1, longName, ""), GoalRow(2, "Short", ""), GoalRow(3, "Cosmiprint content machine live by Mon 2 Nov", "")),
            (1..120).map { TaskRow(it.toLong(), (it % 3 + 1).toLong(), if (it % 5 == 0) "$longName $it" else "Task $it", dueAt = now - 3_600_000L, owner = if (it % 2 == 0) "user" else "agent", status = if (it % 7 == 0) "done" else "todo") },
            (1..23).map { TriggerRow(it.toLong(), "Routine $it", "daily", "07:00", "Do it") }, notifications24h = 1240, notifApps = 37, browserLive = true, miniApps = apps,
            cards = listOf(com.past9.phoneaos.cards.Card("m", "Monitor prices on every store in South Africa", "tag", "", headline = "R 1,203,299.00 cheapest of 1,482 listings", updatedAt = now - 2 * 3_600_000),
                com.past9.phoneaos.cards.Card("o", "O", "mail", "", headline = "", updatedAt = 0L)))
        start { HomeScreen(s, HomeActions()); Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { HomeBar("home", {}, {}) } }
        cap("a95-home-stress-1-top"); scrollDown(); cap("a95-home-stress-2-middle"); scrollDown(); cap("a95-home-stress-3-bottom")
    }
    @Test fun homeEmpty() { shot("a96-home-empty") { HomeScreen(HomeState("Your agent", "", AgentStatus(), emptyList(), emptyList(), emptyList(), emptyList()), HomeActions()); Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { HomeBar("home", {}, {}) } } }
    @Test fun routinesEmpty() { shot("a97-routines-empty") { RoutinesScreen(emptyList(), RoutineActions()) } }
    @Test fun routinesStress() {
        val r = listOf(TriggerRow(1, longName, "daily", "07:00", "Brief me on $unbroken and everything else that matters today.", lastRunAt = now - 3_600_000 * 5, lastResult = "Failed: $unbroken"),
            TriggerRow(2, "Event", "email", "from:a-very-long-address-that-keeps-going@a-very-long-domain-name-for-a-company.example.co.za subject:invoice", "Tell me."),
            TriggerRow(3, "Off", "interval", "15", "Check.", enabled = false), TriggerRow(4, "Weekly", "weekly", "MON,TUE,WED,THU,FRI,SAT,SUN 18:00", "Plan."))
        shot("a98-routines-stress") { RoutinesScreen(r, RoutineActions()) }
    }
    @Test fun connectionsStress() {
        val kits = listOf(Toolkit("gmail", "Gmail", "Read, search, draft and send email", "", 40, false, true), Toolkit("slack", "Slack", "Messages, channels and threads", "", 60, false, true),
            Toolkit("microsoft_teams", "Microsoft Teams and SharePoint Online for Business", "Chats, meetings, transcripts, channels, files and everything else in the tenant", "", 30, false, true))
        val conns = listOf(Connection("1", "gmail", "ACTIVE", "ayanda.thandolwethu.phaketsi-vanderwesthuizen@a-very-long-company-domain.example.co.za"), Connection("2", "gmail", "EXPIRED", "past952@gmail.com"),
            Connection("3", "slack", "ACTIVE", "Strat Edge Works and every partner company workspace"), Connection("4", "microsoft_teams", "INITIATED", "microsoft_teams"), Connection("5", "gmail", "FAILED", "kkronas952@gmail.com"))
        val profiles = listOf(AgentProfile("p1", "Strat Edge Works and every partner company we sell with", conns.take(4).map { AccountRef(it.id, it.toolkit, it.label) }, "Work", color = "ocean"), AgentProfile("p2", "P", emptyList(), color = "mint"),
            AgentProfile("p3", "Guavas", conns.takeLast(1).map { AccountRef(it.id, it.toolkit, it.label) }, color = "ember"), AgentProfile("p4", "Cosmiprint", emptyList(), color = "iris"))
        start { ConnectionsScreen(ConnectionsState(hasKey = true, consumer = true, toolkits = kits, connected = conns, profiles = profiles, notificationsAllowed = false), ConnectionsActions(), initialAppsOpen = true) }
        cap("a99-connections-stress-1-top"); scrollDown(); cap("a99-connections-stress-2-middle"); scrollDown(); cap("a99-connections-stress-3-bottom")
    }
    @Test fun cardTilesStress() { parts("a99b-card-tiles-stress") { CardTiles(listOf(
        com.past9.phoneaos.cards.Card("m", "Monitor prices on every store in South Africa", "tag", "", headline = "R 1,203,299.00 cheapest of 1,482 listings", updatedAt = now - 2 * 3_600_000),
        com.past9.phoneaos.cards.Card("o", "Outreach", "mail", "", headline = "14 sent", updatedAt = now - 40 * 86_400_000L),
        com.past9.phoneaos.cards.Card("x", "Supercalifragilisticexpialidocious", "sun", "", headline = unbroken, updatedAt = now)), {}) } }
}
