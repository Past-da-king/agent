package com.past9.phoneaos

import android.app.SearchManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.automation.IntentRules
import com.past9.phoneaos.automation.IntentSpec
import com.past9.phoneaos.media.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * play_media and media_control. Pinned here, with fake apps and fake media sessions (no real app is named in the code,
 * the names below are only test data):
 * 1. what is sent per kind: the focus and the extras, the package, the watch link, a file;
 * 2. which app is picked: asked for, the user's default, the phone's default, one that is playing, the first;
 * 3. how success is judged from the media session, including an app that keeps playing what it had;
 * 4. the whole tool: the request, the session fallback, the file fallback, the background screen, and every failure in words;
 * 5. YouTube's result page parsed from a fixture;
 * 6. the real package scan, and that both tools are lent to linked computers and offered to the voice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaToolsTest {
    private val metro = MediaApp("test.music.one", "Metrolist Nightly", playFromSearch = true, audioFiles = true)
    private val rvx = MediaApp("test.music.two", "RVX Music", playFromSearch = true)
    private val yt = MediaApp("test.video.one", "RVX YouTube", videoLinks = true)
    private val files = MediaApp("test.files.one", "Plain Player", audioFiles = true)

    private class Ctx : ToolContext {
        override val agentLabel = "main"
        override suspend fun activity(text: String, meta: JSONObject): Long = 0
        override suspend fun updateActivity(id: Long, text: String, meta: JSONObject) {}
        override suspend fun ask(question: String, options: List<String>): String? = null
        override suspend fun notify(title: String, body: String) {}
    }

    class FakeSession(override val pkg: String, override val label: String, override var state: Int = PlaybackState.STATE_NONE,
                      override var title: String = "", override var artist: String = "", override var album: String = "",
                      override var position: Long = 0, override var duration: Long = 0) : MediaSessionView {
        val calls = mutableListOf<String>()
        var onPlayFromSearch: (FakeSession, String) -> Unit = { _, _ -> }
        override fun play() { calls += "play"; state = PlaybackState.STATE_PLAYING }
        override fun pause() { calls += "pause"; state = PlaybackState.STATE_PAUSED }
        override fun next() { calls += "next" }
        override fun previous() { calls += "previous" }
        override fun seekTo(ms: Long) { calls += "seek $ms"; position = ms }
        override fun playFromSearch(query: String, extras: Bundle) { calls += "search $query"; onPlayFromSearch(this, query) }
    }

    private class Deps(var apps: List<MediaApp>) : MediaDeps {
        var sys: String? = null
        var stored: String? = null
        var sessions: Sessions = Sessions.Ok(emptyList())
        val launched = mutableListOf<IntentSpec>()
        var onLaunch: (IntentSpec) -> String = { "sent" }
        var localResult: LocalResult = LocalResult.Found(emptyList())
        var hits: List<YouTubeResults.Hit>? = null
        var front: String? = null
        val jobs = mutableSetOf<String>()
        val order = mutableListOf<String>()
        val requests = mutableListOf<ScreenRequest>()
        val linked = mutableListOf<Pair<MediaApp, String>>()
        var replay: (ScreenRequest) -> Fallback = { Fallback.NoJob }
        var learn: (ScreenRequest) -> Learned = { Learned.Off }
        var link: (MediaApp, String) -> Fallback = { _, _ -> Fallback.Off }
        var slept = 0L
        val screenCalls: Int get() = order.count { it == "replay" || it == "learn" } + linked.size
        val list: MutableList<MediaSessionView> get() = ((sessions as Sessions.Ok).list as MutableList)
        override fun installed() = apps
        override fun systemDefault(video: Boolean) = sys
        override fun stored(video: Boolean) = stored
        override fun sessions() = sessions
        override fun launch(spec: IntentSpec): String { launched += spec; return onLaunch(spec) }
        override fun local(query: String, kind: MediaKind) = localResult
        override suspend fun youtube(query: String) = hits
        override suspend fun userFront() = front
        override fun hasJob(app: MediaApp) = app.pkg in jobs
        override suspend fun screenReplay(req: ScreenRequest, ctx: ToolContext): Fallback { order += "replay"; requests += req; return replay(req) }
        override suspend fun screenLearn(req: ScreenRequest, ctx: ToolContext): Learned { order += "learn"; requests += req; return learn(req) }
        override suspend fun screenSave(req: ScreenRequest, ctx: ToolContext): String? { order += "save"; jobs += req.app.pkg; return req.query }
        override suspend fun screenLink(app: MediaApp, link: String, ctx: ToolContext): Fallback { linked += app to link; return this.link(app, link) }
        override suspend fun screenDone(ctx: ToolContext) { order += "done" }
        var browserOk = false
        var browserAsked = 0
        override suspend fun askViaBrowser(pkg: String, query: String, kind: MediaKind): Boolean {
            browserAsked++
            if (browserOk) list += FakeSession(pkg, apps.first { it.pkg == pkg }.label, PlaybackState.STATE_PLAYING, query, "Some Band")
            return browserOk
        }
        override suspend fun sleep(ms: Long) { slept += ms }
    }

    private fun deps(vararg apps: MediaApp) = Deps(apps.toList()).also { it.sessions = Sessions.Ok(mutableListOf()) }
    private fun play(d: Deps, vararg kv: Pair<String, Any>): String = runBlocking {
        PlayMediaTool(d).run(JSONObject().also { j -> kv.forEach { (k, v) -> j.put(k, v) } }, Ctx())
    }
    private fun control(d: Deps, vararg kv: Pair<String, Any>): String = runBlocking {
        MediaControlTool(d).run(JSONObject().also { j -> kv.forEach { (k, v) -> j.put(k, v) } }, Ctx())
    }

    // ---- 1. what is sent --------------------------------------------------------------------------------------------

    @Test fun aSongWithAnArtistIsSplitAndFocusedOnAudio() {
        val i = IntentRules.build(MediaIntents.playFromSearch("Underway by Some Band", MediaKind.SONG, "test.music.one"))
        assertEquals("android.media.action.MEDIA_PLAY_FROM_SEARCH", i.action)
        assertEquals("test.music.one", i.`package`)
        assertTrue(i.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals("Underway by Some Band", i.getStringExtra(SearchManager.QUERY))
        assertEquals(MediaStore.Audio.Media.ENTRY_CONTENT_TYPE, i.getStringExtra(MediaStore.EXTRA_MEDIA_FOCUS))
        assertEquals("Underway", i.getStringExtra(MediaStore.EXTRA_MEDIA_TITLE))
        assertEquals("Some Band", i.getStringExtra(MediaStore.EXTRA_MEDIA_ARTIST))
        assertNull(i.getStringExtra(MediaStore.EXTRA_MEDIA_ALBUM))
    }

    @Test fun eachKindHasItsOwnFocusAndExtras() {
        fun x(q: String, k: MediaKind) = IntentRules.build(MediaIntents.playFromSearch(q, k, "p"))
        val artist = x("Daft Punk", MediaKind.ARTIST)
        assertEquals(MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE, artist.getStringExtra(MediaStore.EXTRA_MEDIA_FOCUS))
        assertEquals("Daft Punk", artist.getStringExtra(MediaStore.EXTRA_MEDIA_ARTIST))
        assertNull(artist.getStringExtra(MediaStore.EXTRA_MEDIA_TITLE))
        val album = x("Discovery by Daft Punk", MediaKind.ALBUM)
        assertEquals(MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE, album.getStringExtra(MediaStore.EXTRA_MEDIA_FOCUS))
        assertEquals("Discovery", album.getStringExtra(MediaStore.EXTRA_MEDIA_ALBUM))
        assertEquals("Daft Punk", album.getStringExtra(MediaStore.EXTRA_MEDIA_ARTIST))
        val list = x("Gym mix", MediaKind.PLAYLIST)
        assertEquals(MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE, list.getStringExtra(MediaStore.EXTRA_MEDIA_FOCUS))
        assertEquals("Gym mix", list.getStringExtra(MediaIntents.EXTRA_PLAYLIST))
        val pod = x("Radiolab", MediaKind.PODCAST)
        assertEquals(MediaIntents.UNSTRUCTURED, pod.getStringExtra(MediaStore.EXTRA_MEDIA_FOCUS))
        assertEquals("Radiolab", pod.getStringExtra(SearchManager.QUERY))
    }

    @Test fun aTitleThatContainsByIsNotSplitMidWord() {
        val p = MediaIntents.parts("Standby", MediaKind.SONG)
        assertEquals("Standby", p.title); assertNull(p.artist)
        val q = MediaIntents.parts("Stand by me by Ben E. King", MediaKind.SONG)
        assertEquals("Stand by me", q.title); assertEquals("Ben E. King", q.artist)
    }

    @Test fun aWatchLinkAndAFileCarryWhatTheAppNeeds() {
        val w = IntentRules.build(MediaIntents.watch("dQw4w9WgXcQ", "test.video.one"))
        assertEquals(Intent.ACTION_VIEW, w.action)
        assertEquals(Uri.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ"), w.data)
        assertEquals("test.video.one", w.`package`)
        val f = IntentRules.build(MediaIntents.viewAudio("content://media/external/audio/media/7", "test.files.one"))
        assertEquals("audio/*", f.type)
        assertEquals(Uri.parse("content://media/external/audio/media/7"), f.data)
        assertTrue(f.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("https://www.youtube.com/results?search_query=lofi+beats", MediaIntents.results("lofi beats", "").data)
    }

    // ---- 2. which app -----------------------------------------------------------------------------------------------

    @Test fun theAppAskedForWinsByPartOfItsName() {
        val pool = listOf(metro, rvx)
        assertEquals(metro, MediaResolve.pick(pool, pool, "metrolist", rvx.pkg, rvx.pkg, emptyList()).app)
        assertEquals(rvx, MediaResolve.pick(pool, pool, "RVX Music", null, null, emptyList()).app)
        assertEquals(rvx, MediaResolve.pick(pool, pool, "test.music.two", null, null, emptyList()).app)
    }

    @Test fun anInstalledAppThatDoesNotAdvertiseItIsStillFoundByName() {
        val other = MediaApp("test.other", "Other Player")
        val p = MediaResolve.pick(listOf(metro), listOf(metro, other), "other player", null, null, emptyList())
        assertEquals(other, p.app)
        assertTrue(p.why, p.why.contains("does not say"))
        val none = MediaResolve.pick(listOf(metro), listOf(metro), "nothing like it", null, null, emptyList())
        assertNull(none.app)
        assertTrue(none.error!!, none.error!!.contains("No installed app matches") && none.error!!.contains("Metrolist Nightly"))
    }

    @Test fun withoutANameItIsYourDefaultThenThePhonesThenAPlayingOneThenTheFirst() {
        val pool = listOf(metro, rvx)
        assertEquals(rvx, MediaResolve.pick(pool, pool, null, rvx.pkg, metro.pkg, listOf(metro.pkg)).app)
        assertEquals(metro, MediaResolve.pick(pool, pool, null, "gone.pkg", metro.pkg, listOf(rvx.pkg)).app)
        assertEquals(rvx, MediaResolve.pick(pool, pool, null, null, null, listOf("x", rvx.pkg)).app)
        assertEquals(metro, MediaResolve.pick(pool, pool, null, null, null, emptyList()).app)
        assertNull(MediaResolve.pick(emptyList(), emptyList(), null, null, null, emptyList()).app)
    }

    // ---- 3. judging success -----------------------------------------------------------------------------------------

    @Test fun whatIsPlayingIsComparedWithWhatWasAsked() {
        val s = FakeSession("p", "App", PlaybackState.STATE_PLAYING, "Underway (Official Audio)", "Some Band")
        assertTrue(MediaVerify.matches("Underway by Some Band", s))
        assertFalse(MediaVerify.matches("Thriller by Michael Jackson", s))
        assertTrue("nothing to compare means no complaint", MediaVerify.matches("the", s))
    }

    @Test fun startingThenPlayingCountsAndTheAppIsWatchedOnlyForItsOwnSession() = runBlocking {
        val other = FakeSession("other", "Other", PlaybackState.STATE_PLAYING, "Something")
        val mine = FakeSession("mine", "Mine", PlaybackState.STATE_BUFFERING, "Underway")
        var polls = 0
        val o = MediaVerify.await({ polls++; if (polls > 2) mine.state = PlaybackState.STATE_PLAYING; Sessions.Ok(listOf(other, mine)) }, "mine", "Underway", 5_000, sleep = {})
        assertTrue(o is MediaVerify.Outcome.Playing)
        assertEquals("mine", (o as MediaVerify.Outcome.Playing).s.pkg)
        assertTrue(o.matches)
    }

    @Test fun anAppThatKeepsPlayingWhatItHadIsWaitedOnThenReportedAsNotWhatWasAsked() = runBlocking {
        val mine = FakeSession("mine", "Mine", PlaybackState.STATE_PLAYING, "Old song")
        var slept = 0L
        val o = MediaVerify.await({ Sessions.Ok(listOf(mine)) }, "mine", "Underway", 2_000, staleTitle = "Old song", sleep = { slept += it })
        assertTrue(o is MediaVerify.Outcome.Playing && !(o as MediaVerify.Outcome.Playing).matches)
        assertEquals("it waited out the time", 2_000, slept)
    }

    @Test fun pausedNoSessionAndNoAccessAreToldApart() = runBlocking {
        val paused = FakeSession("mine", "Mine", PlaybackState.STATE_PAUSED, "Underway")
        assertTrue(MediaVerify.await({ Sessions.Ok(listOf(paused)) }, "mine", "x", 1_000, sleep = {}) is MediaVerify.Outcome.NotPlaying)
        assertTrue(MediaVerify.await({ Sessions.Ok(emptyList()) }, "mine", "x", 1_000, sleep = {}) is MediaVerify.Outcome.NoSession)
        assertTrue(MediaVerify.await({ Sessions.NoAccess("no") }, "mine", "x", 1_000, sleep = {}) is MediaVerify.Outcome.NoAccess)
    }

    @Test fun stillBufferingAtTheEndCountsAsPlayingButSaysSo() = runBlocking {
        val mine = FakeSession("mine", "Mine", PlaybackState.STATE_BUFFERING, "Underway")
        val o = MediaVerify.await({ Sessions.Ok(listOf(mine)) }, "mine", "Underway", 1_000, sleep = {})
        assertTrue(o is MediaVerify.Outcome.Playing && (o as MediaVerify.Outcome.Playing).buffering)
    }

    // ---- 4. the tool: music -----------------------------------------------------------------------------------------

    @Test fun whenTheRequestIsIgnoredTheSessionIsAskedToPlayTheSearch() {
        val d = deps(rvx)
        val s = FakeSession("test.music.two", "RVX Music", PlaybackState.STATE_PAUSED, "Old")
        s.onPlayFromSearch = { me, q -> me.state = PlaybackState.STATE_PLAYING; me.title = q }
        d.list += s
        val out = play(d, "query" to "Underway")
        assertEquals("Playing \"Underway\" in RVX Music. (asked the app's media session directly)", out)
        assertEquals(listOf("search Underway"), s.calls)
        assertTrue("nothing was opened anywhere", d.launched.isEmpty() && d.screenCalls == 0)
    }

    @Test fun anAppNamedInTheCallBeatsTheDefault() {
        val d = deps(metro, rvx)
        d.stored = metro.pkg
        val s = FakeSession("test.music.two", "RVX Music", PlaybackState.STATE_PAUSED, "Old")
        s.onPlayFromSearch = { me, q -> me.state = PlaybackState.STATE_PLAYING; me.title = q }
        d.list += s
        val out = play(d, "query" to "Underway", "app" to "rvx")
        assertTrue(out, out.startsWith("Playing \"Underway\" in RVX Music"))
    }

    @Test fun anAppThatIsClosedIsReachedThroughItsMediaBrowserService() {
        val d = deps(rvx)
        d.browserOk = true
        val out = play(d, "query" to "Underway")
        assertEquals("Playing \"Underway\" by Some Band in RVX Music. (asked the app's media session directly)", out)
        assertEquals(1, d.browserAsked)
        assertTrue(d.launched.isEmpty())
    }

    @Test fun anAppThatKeepsItsOldSongIsNotReportedAsSuccess() {
        val d = deps(rvx)
        val s = FakeSession("test.music.two", "RVX Music", PlaybackState.STATE_PLAYING, "Old song", "Old Band")
        d.list += s
        val out = play(d, "query" to "Underway")
        assertTrue(out, out.startsWith("failed:"))
        assertTrue(out, out.contains("kept playing \"Old song\" by Old Band in RVX Music and did not switch"))
    }

    @Test fun withoutNotificationAccessTheAnswerSaysItCannotConfirm() {
        val d = deps(metro)
        d.sessions = Sessions.NoAccess("Agent does not have notification access.")
        d.learn = { Learned.NoAccess("Agent does not have notification access.") }
        val out = play(d, "query" to "Underway")
        assertTrue(out, out.startsWith("Pressed play in Metrolist Nightly on the background screen") && out.contains("cannot confirm") && out.contains("Notification access"))
    }

    // the new rule: nothing opens on the user's screen

    @Test fun anAppNotInFrontIsNeverLaunchedByIntentItIsWorkedOutOnTheBackgroundScreenAndSaved() {
        val d = deps(rvx)
        d.front = "test.other.app"
        d.learn = { req -> Learned.Playing(MediaVerify.Outcome.Playing(FakeSession(req.app.pkg, req.app.label, PlaybackState.STATE_PLAYING, "Underway", "Some Band"), true)) }
        val out = play(d, "query" to "Underway by Some Band")
        assertTrue(out, out.startsWith("Playing \"Underway\" by Some Band in RVX Music."))
        assertTrue(out, out.contains("done on the background screen") && out.contains("saved as RVX Music's play job"))
        assertTrue("no intent: that would open it on his screen", d.launched.isEmpty())
        assertEquals(listOf("learn", "save", "done"), d.order)
        assertEquals("the user's front app is the same after as before", "test.other.app", d.front)
    }

    @Test fun aJobThatTookAWrongTurnIsNotSavedAndOneThatPlayedTheWrongThingIsNotEither() {
        val d = deps(rvx)
        d.learn = { req -> Learned.Playing(MediaVerify.Outcome.Playing(FakeSession(req.app.pkg, req.app.label, PlaybackState.STATE_PLAYING, "Underway"), true), clean = false) }
        val out = play(d, "query" to "Underway")
        assertTrue(out, out.startsWith("Playing") && out.contains("not saved") && out.contains("wrong turn"))
        assertFalse("save" in d.order)
        val d2 = deps(rvx)
        d2.learn = { req -> Learned.Playing(MediaVerify.Outcome.Playing(FakeSession(req.app.pkg, req.app.label, PlaybackState.STATE_PLAYING, "Something else"), false)) }
        val out2 = play(d2, "query" to "Underway")
        assertTrue(out2, out2.contains("may not be what was asked for") && out2.contains("not saved"))
        assertFalse("save" in d2.order)
    }

    @Test fun aSavedJobIsReplayedFirstAndTheSessionIsNotEvenAsked() {
        val d = deps(rvx)
        d.jobs += rvx.pkg
        val s = FakeSession(rvx.pkg, "RVX Music", PlaybackState.STATE_NONE)
        d.list += s
        d.replay = { s.state = PlaybackState.STATE_PLAYING; s.title = "Underway"; Fallback.Ran("Done the learned way in 4.1 s") }
        val out = play(d, "query" to "Underway")
        assertTrue(out, out.startsWith("Playing \"Underway\" in RVX Music.") && out.contains("the saved way"))
        assertTrue(s.calls.isEmpty() && d.browserAsked == 0 && d.launched.isEmpty())
        assertEquals(listOf("replay", "done"), d.order)
    }

    @Test fun aBrokenReplayIsLearnedAgainOnceNotLoopedAndNotSentToTheUsersScreen() {
        val d = deps(rvx)
        d.jobs += rvx.pkg
        d.replay = { Fallback.Failed("The learned way stopped part of the way.") }
        d.learn = { req -> Learned.Playing(MediaVerify.Outcome.Playing(FakeSession(req.app.pkg, req.app.label, PlaybackState.STATE_PLAYING, "Underway"), true)) }
        val out = play(d, "query" to "Underway")
        assertTrue(out, out.startsWith("Playing"))
        assertEquals(listOf("replay", "learn", "save", "done"), d.order)
        assertTrue(d.launched.isEmpty())
    }

    @Test fun aReplayThatRanButPlayedNothingIsLearnedAgain() {
        val d = deps(rvx)
        d.jobs += rvx.pkg
        d.replay = { Fallback.Ran("Done") }
        d.learn = { req -> Learned.Failed("no result") }
        val out = play(d, "query" to "Underway")
        assertTrue(out, out.startsWith("failed:") && out.contains("ran but nothing is playing, so it was learned again") && out.contains("no result"))
        assertEquals(listOf("replay", "learn"), d.order.take(2))
        assertEquals(1, d.order.count { it == "learn" })
    }

    @Test fun anAppInFrontIsAskedInPlaceAndStaysInFront() {
        val d = deps(rvx)
        d.front = rvx.pkg
        d.onLaunch = { s -> d.list += FakeSession(s.pkg, "RVX Music", PlaybackState.STATE_PLAYING, "Underway"); "sent" }
        val out = play(d, "query" to "Underway")
        assertTrue(out, out.startsWith("Playing \"Underway\" in RVX Music.") && out.contains("in front on the user's screen"))
        assertEquals("the same app was already in front, so nothing moved", rvx.pkg, d.front)
        assertEquals(0, d.screenCalls)
        assertEquals(1, d.launched.size)
    }

    @Test fun anAppInFrontThatDoesNotStartIsNotTakenToTheBackgroundScreenAndNothingElseIsLaunched() {
        val d = deps(rvx)
        d.front = rvx.pkg
        val out = play(d, "query" to "Underway")
        assertTrue(out, out.startsWith("failed:") && out.contains("in front on the user's own screen"))
        assertTrue("learn" !in d.order && "replay" !in d.order)
        assertEquals(1, d.launched.size)
        assertEquals(rvx.pkg, d.launched[0].pkg)
    }

    @Test fun whenTheBackgroundScreenIsOffTheFailureSaysSoAndNothingIsOpened() {
        val d = deps(rvx)
        val out = play(d, "query" to "Underway")
        assertTrue(out, out.startsWith("failed: could not get \"Underway\" playing in RVX Music."))
        assertTrue(out, out.contains("gave no media session") || out.contains("no media session"))
        assertTrue(out, out.contains("background screen is off") && out.contains("Settings > Background screen"))
        assertTrue(out, out.contains("Do not open the app on their screen"))
        assertTrue(d.launched.isEmpty())
    }

    @Test fun anAppOpenOnTheUsersScreenThatTheScreenRefusesIsReportedNotMoved() {
        val d = deps(rvx)
        d.learn = { Learned.InFront("RVX Music is open on the user's own screen right now, so it was left alone and nothing was opened.") }
        val out = play(d, "query" to "Underway")
        assertTrue(out, out.startsWith("failed:") && out.contains("left alone"))
        assertTrue(d.launched.isEmpty())
    }

    @Test fun aFileOnThePhoneOpensOnTheBackgroundScreenNotTheUsers() {
        val d = deps(rvx, files)
        d.localResult = LocalResult.Found(listOf(LocalTrack("content://media/external/audio/media/9", "Underway", "Some Band", "Album")))
        d.link = { app, _ -> d.list += FakeSession(app.pkg, "Plain Player", PlaybackState.STATE_PLAYING, "Underway", "Some Band"); Fallback.Ran("open") }
        val out = play(d, "query" to "Underway by Some Band", "app" to "RVX Music")
        assertEquals("Playing \"Underway\" by Some Band in Plain Player. (a file on the phone)", out)
        assertEquals("test.files.one", d.linked.single().first.pkg)
        assertEquals("content://media/external/audio/media/9", d.linked.single().second)
        assertTrue("never an intent", d.launched.isEmpty())
    }

    @Test fun localOnlySkipsTheAppsSearchAndDeniedStoragePermissionIsSaidPlainly() {
        val d = deps(metro)
        d.localResult = LocalResult.Denied
        val out = play(d, "query" to "Underway", "local" to true)
        assertTrue(out, out.startsWith("failed:") && out.contains("storage access"))
        assertTrue("no search request was sent", d.launched.isEmpty())
        assertEquals("the background screen is not tried for files", 0, d.screenCalls)
    }

    @Test fun noAppAtAllAndAnUnknownAppAreClearErrors() {
        val none = play(Deps(emptyList()), "query" to "Underway")
        assertTrue(none, none.startsWith("failed: could not get \"Underway\" playing."))
        val named = play(deps(metro), "query" to "Underway", "app" to "No Such")
        assertEquals("failed: No installed app matches \"No Such\". Apps that can play: Metrolist Nightly.", named)
        assertEquals("failed: say what to play.", play(deps(metro), "query" to " "))
        assertEquals("failed: kind must be one of song, artist, album, playlist, video, podcast.", play(deps(metro), "query" to "x", "kind" to "film"))
    }

    // ---- 4. the tool: video -----------------------------------------------------------------------------------------

    @Test fun aVideoIsFoundAndOpenedInTheVideoAppOnTheBackgroundScreen() {
        val d = deps(metro, yt)
        d.hits = listOf(YouTubeResults.Hit("AAAAAAAAAAA", "Promoted", "Ad", ""), YouTubeResults.Hit("dQw4w9WgXcQ", "How to tie a tie", "Ties Inc", "3:21"))
        d.front = "test.other.app"
        d.link = { app, _ -> d.list += FakeSession(app.pkg, "RVX YouTube", PlaybackState.STATE_PLAYING, "How to tie a tie", "Ties Inc"); Fallback.Ran("open") }
        val out = play(d, "query" to "how to tie a tie", "kind" to "video")
        assertTrue(out, out.startsWith("Playing \"How to tie a tie\" by Ties Inc in RVX YouTube.") && out.contains("user's screen was not touched"))
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", d.linked.single().second)
        assertEquals("test.video.one", d.linked.single().first.pkg)
        assertTrue("a video is never an intent to his screen", d.launched.isEmpty())
        assertEquals("test.other.app", d.front)
    }

    @Test fun aVideoAppAlreadyInFrontGetsTheLinkInPlace() {
        val d = deps(yt)
        d.front = yt.pkg
        d.hits = listOf(YouTubeResults.Hit("dQw4w9WgXcQ", "How to tie a tie", "Ties Inc", "3:21"))
        d.onLaunch = { s -> d.list += FakeSession(s.pkg, "RVX YouTube", PlaybackState.STATE_PLAYING, "How to tie a tie", "Ties Inc"); "sent" }
        val out = play(d, "query" to "how to tie a tie", "kind" to "video")
        assertTrue(out, out.contains("in front on the user's screen, so it was not moved"))
        assertTrue(d.linked.isEmpty())
        assertEquals(yt.pkg, d.front)
    }

    @Test fun aVideoThatOpensWithoutASessionIsOpenedButNotClaimedAsPlaying() {
        val d = deps(yt)
        d.hits = listOf(YouTubeResults.Hit("dQw4w9WgXcQ", "How to tie a tie", "Ties Inc", "3:21"))
        d.link = { _, _ -> Fallback.Ran("open") }
        val out = play(d, "query" to "how to tie a tie", "kind" to "video")
        assertTrue(out, out.startsWith("Opened \"How to tie a tie\"") && out.contains("cannot confirm"))
    }

    @Test fun whenYouTubeCannotBeReadTheAppsOwnSearchIsUsedOnTheBackgroundScreen() {
        val d = deps(yt)
        d.hits = null
        d.learn = { req -> Learned.Playing(MediaVerify.Outcome.Playing(FakeSession(req.app.pkg, req.app.label, PlaybackState.STATE_PLAYING, "lofi beats"), true)) }
        val out = play(d, "query" to "lofi beats", "kind" to "video")
        assertTrue(out, out.startsWith("Playing \"lofi beats\""))
        assertEquals(MediaKind.VIDEO, d.requests.single().kind)
        assertTrue(d.launched.isEmpty() && d.linked.isEmpty())
    }

    @Test fun aVideoFromTheBackgroundIsAFailureNeverANotificationOrAChooser() {
        val d = deps(yt)
        d.hits = listOf(YouTubeResults.Hit("dQw4w9WgXcQ", "x", "y", "1:00"))
        val out = play(d, "query" to "x", "kind" to "video")
        assertTrue(out, out.startsWith("failed:") && out.contains("background screen is off"))
        assertTrue(d.launched.isEmpty())
        val none = play(Deps(emptyList()), "query" to "x", "kind" to "video")
        assertTrue(none, none.startsWith("failed: no installed app opens YouTube video links"))
    }

    // ---- the guard: nothing else plays ------------------------------------------------------------------------------

    @Test fun anIntentOrLinkThatPlaysMediaIsRefusedWithThePathToPlayMedia() {
        val search = MediaIntents.playFromSearch("x", MediaKind.SONG, "app.rvx.android.youtube")
        assertTrue(MediaIntentGuard.refusal(search)!!.contains("play_media"))
        assertNotNull(MediaIntentGuard.refusal(MediaIntents.watch("dQw4w9WgXcQ", "app.rvx.android.youtube")))
        assertNotNull(MediaIntentGuard.refusal(MediaIntents.results("lofi", "com.google.android.youtube")))
        assertNotNull(MediaIntentGuard.refusal(MediaIntents.viewAudio("content://media/external/audio/media/9", "p")))
        assertNotNull(MediaIntentGuard.refusal(IntentSpec("activity", Intent.ACTION_VIEW, data = "https://music.youtube.com/watch?v=abc")))
        assertNotNull(MediaIntentGuard.refusal(IntentSpec("activity", Intent.ACTION_VIEW, data = "https://open.spotify.com/track/1")))
        // what is not playback still goes
        assertNull(MediaIntentGuard.refusal(IntentSpec("activity", Intent.ACTION_VIEW, data = "google.navigation:q=Sandton")))
        assertNull(MediaIntentGuard.refusal(IntentSpec("activity", Intent.ACTION_VIEW, data = "https://example.org/")))
        assertNull(MediaIntentGuard.refusal(IntentSpec("activity", Intent.ACTION_SEND, mimeType = "text/plain")))
        assertNull(MediaIntentGuard.refusal(IntentSpec("broadcast", "com.example.DO_THING")))
        assertNull(MediaIntentGuard.refusalForLink("https://news.example.org/story"))
        assertNotNull(MediaIntentGuard.refusalForLink("https://www.youtube.com/watch?v=abc"))
        assertNotNull(MediaIntentGuard.refusalForLink("https://youtu.be/abc"))
    }

    @Test fun androidIntentRefusesAPlayRequestWithoutSendingIt() {
        var sent = 0
        val tool = com.past9.phoneaos.automation.AndroidIntentTool(object : com.past9.phoneaos.automation.IntentSettings { override fun intentAllowlist() = emptyList<IntentRules.Pair>() }) { sent++; "sent" }
        val out = runBlocking {
            tool.run(JSONObject().put("kind", "activity").put("action", Intent.ACTION_VIEW).put("package", "app.rvx.android.youtube").put("data", "https://www.youtube.com/watch?v=dQw4w9WgXcQ"), Ctx())
        }
        assertTrue(out, out.startsWith("failed:") && out.contains("play_media"))
        val out2 = runBlocking {
            tool.run(JSONObject().put("kind", "activity").put("action", MediaIntents.PLAY_FROM_SEARCH).put("package", "app.rvx.android.youtube").put("extras", JSONObject().put("query", "BAD BISHHH")), Ctx())
        }
        assertTrue(out2, out2.startsWith("failed:"))
        assertEquals(0, sent)
    }

    // ---- reading an unknown app -------------------------------------------------------------------------------------

    private fun el(id: Int, text: String = "", desc: String = "", hint: String = "", res: String = "", top: Int = 0, flags: String = "", cls: String = "android.widget.TextView", w: Int = 720) =
        com.past9.phoneaos.screen.ScreenElement(id, cls, text, desc, hint, res, 0, top, w, top + 100, flags, 0)

    @Test fun theSearchBoxIsTheFieldThatSaysSearchElseTheOnlyField() {
        val a = el(1, hint = "Search YouTube Music", flags = "e", cls = "android.widget.EditText")
        val b = el(2, hint = "Name", flags = "e", cls = "android.widget.EditText", top = 300)
        assertEquals(a, MediaScreenPlan.searchField(listOf(b, a)))
        assertEquals(b, MediaScreenPlan.searchField(listOf(b)))
        assertNull("two fields and none says search", MediaScreenPlan.searchField(listOf(b, el(3, hint = "Email", flags = "e"))))
    }

    @Test fun aSearchButtonIsFoundWhenThereIsNoBoxAndVoiceSearchIsLeftAlone() {
        val voice = el(1, desc = "Voice search", flags = "c", top = 10)
        val btn = el(2, desc = "Search", flags = "c", top = 20, cls = "android.widget.ImageButton")
        val tab = el(3, text = "Explore", flags = "c", top = 2000)
        assertEquals(btn, MediaScreenPlan.searchEntry(listOf(voice, btn, tab)))
        assertEquals(tab, MediaScreenPlan.searchEntry(listOf(voice, tab)))
        assertNull(MediaScreenPlan.searchEntry(listOf(voice)))
    }

    @Test fun theResultIsTheRowWithMostOfTheWordsBelowTheBoxTopmostFirst() {
        val box = el(1, hint = "Search", flags = "e", top = 0)
        val chip = el(2, text = "Songs", flags = "c", top = 130)
        val other = el(3, text = "BAD BISHHH Remix - Someone Else", flags = "c", top = 300)
        val best = el(4, desc = "BAD BISHHH - Tony Dayimane, Lowfeye, Usimamane - 3 minutes", flags = "c", top = 450)
        val title = el(5, text = "Intro", flags = "c", top = 600)
        assertEquals(best, MediaScreenPlan.pickResult(listOf(box, chip, other, best, title), "BAD BISHHH by Tony Dayimane Lowfeye and Usimamane", box))
        assertEquals("a row already tried is skipped", other, MediaScreenPlan.pickResult(listOf(box, chip, other, title), "BAD BISHHH", box, setOf(best.label)))
        // nothing carries the words: the first wide row after the box
        assertEquals(title, MediaScreenPlan.pickResult(listOf(box, chip.copy(right = 100), title), "zzzz qqqq", box, screenWidth = 720))
        assertNull(MediaScreenPlan.pickResult(listOf(box), "anything", box))
    }

    private class FakeOps(val screens: List<List<com.past9.phoneaos.screen.ScreenElement>>, var openResult: String? = null) : ScreenOps {
        var screen = 0
        val actions = mutableListOf<String>()
        override suspend fun open(): String? { actions += "open"; return openResult }
        override fun elements() = screens[screen]
        override fun width() = 720
        override suspend fun tap(e: com.past9.phoneaos.screen.ScreenElement, generic: Boolean): String? { actions += "tap ${e.label}${if (generic) " (generic)" else ""}"; if (screen < screens.size - 1) screen++; return null }
        override suspend fun type(e: com.past9.phoneaos.screen.ScreenElement, text: String): String? { actions += "type $text"; if (screen < screens.size - 1) screen++; return null }
        override suspend fun settle() { actions += "settle" }
    }

    @Test fun theLearnerOpensSearchesTypesPressesTheMatchAndWatchesTheSession() = runBlocking {
        val home = listOf(el(1, hint = "Search songs", flags = "e", cls = "android.widget.EditText"))
        val results = listOf(el(1, hint = "Search songs", flags = "e", cls = "android.widget.EditText"), el(2, text = "Underway - Some Band", flags = "c", top = 300))
        val ops = FakeOps(listOf(home, results, results))
        val s = FakeSession(rvx.pkg, "RVX Music", PlaybackState.STATE_NONE)
        val list = mutableListOf<MediaSessionView>(s)
        val l = MediaScreenLearner(ops, { Sessions.Ok(list) }, sleep = { s.state = PlaybackState.STATE_PLAYING; s.title = "Underway"; s.artist = "Some Band" }, waitMs = 1000)
        val r = l.run(rvx, "Underway by Some Band")
        assertTrue(r.toString(), r is Learned.Playing && r.clean && r.o.matches)
        assertEquals(listOf("open", "type Underway by Some Band", "settle", "tap Underway - Some Band (generic)"), ops.actions)
    }

    @Test fun theLearnerPressesASearchButtonFirstWhenThereIsNoBoxAndPlayWhenTheRowOnlyOpenedAPage() = runBlocking {
        val home = listOf(el(1, desc = "Search", flags = "c", top = 20, cls = "android.widget.ImageButton"))
        val box = listOf(el(1, hint = "Search", flags = "e", cls = "android.widget.EditText"))
        val results = box + el(2, text = "Underway (album)", flags = "c", top = 300)
        val page = listOf(el(3, text = "Play", flags = "c", top = 300))
        val ops = FakeOps(listOf(home, box, results, page, page))
        val s = FakeSession(rvx.pkg, "RVX Music", PlaybackState.STATE_NONE)
        val l = MediaScreenLearner(ops, { Sessions.Ok(listOf(s)) }, sleep = { if (ops.actions.any { it == "tap Play" }) { s.state = PlaybackState.STATE_PLAYING; s.title = "Underway" } }, waitMs = 1000)
        val r = l.run(rvx, "Underway")
        assertTrue(r.toString(), r is Learned.Playing)
        assertEquals(listOf("open", "tap Search", "type Underway", "settle", "tap Underway (album) (generic)", "tap Play"), ops.actions)
    }

    @Test fun theLearnerSaysWhyItStoppedAndNeverRunsOnTheUsersScreen() = runBlocking {
        val none = MediaScreenLearner(FakeOps(listOf(listOf(el(1, text = "Welcome")))), { Sessions.Ok(emptyList()) }, sleep = {}, waitMs = 10).run(rvx, "x")
        assertTrue(none.toString(), none is Learned.Failed && none.text.contains("no search box or search button"))
        val front = MediaScreenLearner(FakeOps(listOf(emptyList()), "RVX Music is open on the user's own screen right now, so it was left alone and nothing was opened."), { Sessions.Ok(emptyList()) }, sleep = {}, waitMs = 10).run(rvx, "x")
        assertTrue(front is Learned.InFront)
        val box = listOf(el(1, hint = "Search", flags = "e"), el(2, text = "x - y", flags = "c", top = 300))
        val silent = MediaScreenLearner(FakeOps(listOf(box)), { Sessions.Ok(emptyList()) }, sleep = {}, waitMs = 10).run(rvx, "x y")
        assertTrue(silent.toString(), silent is Learned.Failed && (silent.text.contains("no result") || silent.text.contains("nothing started playing")))
        val noAccess = MediaScreenLearner(FakeOps(listOf(box)), { Sessions.NoAccess("no") }, sleep = {}, waitMs = 10).run(rvx, "x y")
        assertTrue(noAccess is Learned.NoAccess)
    }
    // ---- media_control ----------------------------------------------------------------------------------------------

    @Test fun nowPlayingNamesTheTitleArtistAppStateAndPlace() {
        val d = deps()
        d.list += FakeSession("test.music.one", "Metrolist Nightly", PlaybackState.STATE_PAUSED, "Old", "A")
        d.list += FakeSession("test.video.one", "RVX YouTube", PlaybackState.STATE_PLAYING, "Underway", "Some Band", "Album", 83_000, 225_000)
        val out = control(d, "action" to "now_playing")
        assertTrue(out, out.startsWith("Playing: Underway by Some Band (Album) in RVX YouTube [test.video.one], 1:23 of 3:45"))
        assertTrue(out, out.contains("Also open: Paused: Old by A in Metrolist Nightly"))
    }

    @Test fun pauseGoesToThePlayingSessionAndSeekNeedsAPosition() {
        val d = deps()
        val a = FakeSession("a", "A", PlaybackState.STATE_PAUSED, "Old"); val b = FakeSession("b", "B", PlaybackState.STATE_PLAYING, "Now")
        d.list += a; d.list += b
        val out = control(d, "action" to "pause")
        assertEquals(listOf("pause"), b.calls); assertTrue(a.calls.isEmpty())
        assertTrue(out, out.startsWith("Sent pause to B. Now: Paused: Now"))
        b.state = PlaybackState.STATE_PLAYING
        assertEquals("failed: seek needs position_ms.", control(d, "action" to "seek"))
        control(d, "action" to "seek", "position_ms" to 30_000)
        assertEquals("seek 30000", b.calls.last())
        control(d, "action" to "next", "app" to "A")
        assertEquals(listOf("next"), a.calls)
    }

    @Test fun controlWithoutAccessOrSessionsOrAnUnknownAppSaysWhy() {
        val d = deps()
        d.sessions = Sessions.NoAccess("Agent does not have notification access.")
        assertTrue(control(d, "action" to "pause").startsWith("failed: Agent does not have notification access."))
        d.sessions = Sessions.Ok(mutableListOf())
        assertEquals("Nothing is playing: no app has an open media session.", control(d, "action" to "now_playing"))
        assertTrue(control(d, "action" to "pause").contains("Use play_media to start something"))
        d.list += FakeSession("a", "A", PlaybackState.STATE_PLAYING, "x")
        assertTrue(control(d, "action" to "pause", "app" to "zzz").contains("no media session from \"zzz\""))
        assertTrue(control(d, "action" to "rewind").startsWith("failed: action must be one of"))
    }

    // ---- 5. YouTube's page ------------------------------------------------------------------------------------------

    private val page = """<html><head></head><body><script>var ytInitialData = {"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[
        {"itemSectionRenderer":{"contents":[
          {"adSlotRenderer":{"x":"} {"}},
          {"videoRenderer":{"videoId":"LIVEVIDEO01","title":{"runs":[{"text":"Live now"}]},"ownerText":{"runs":[{"text":"Radio"}]}}},
          {"videoRenderer":{"videoId":"dQw4w9WgXcQ","title":{"runs":[{"text":"Never } Gonna \"Give\" You Up"}]},"ownerText":{"runs":[{"text":"Rick Astley"}]},"lengthText":{"simpleText":"3:33"}}},
          {"reelShelfRenderer":{"items":[{"reelItemRenderer":{"videoId":"SHORTSSSS01"}}]}},
          {"videoRenderer":{"videoId":"abcdefghijk","title":{"simpleText":"Second"},"longBylineText":{"runs":[{"text":"Someone"}]},"lengthText":{"simpleText":"1:00"}}}
        ]}}]}}}}};</script><script>var other = 1;</script></body></html>"""

    @Test fun theResultPageIsReadWithoutAKeyAndTheFirstRealVideoIsPicked() {
        val hits = YouTubeResults.parse(page)
        assertEquals(listOf("LIVEVIDEO01", "dQw4w9WgXcQ", "abcdefghijk"), hits.map { it.id })
        assertEquals("Never } Gonna \"Give\" You Up", hits[1].title)
        assertEquals("Rick Astley", hits[1].channel)
        assertEquals("3:33", hits[1].length)
        assertEquals("Someone", hits[2].channel)
        assertEquals("a live stream has no running time, so the first real video wins", "dQw4w9WgXcQ", YouTubeResults.best(hits)!!.id)
    }

    @Test fun aChangedPageStillYieldsTheFirstVideoIdsAndAnEmptyOneYieldsNothing() {
        val odd = "<html>ytInitialData = not json at all \"videoId\":\"zzzzzzzzzzz\" \"videoId\":\"yyyyyyyyyyy\" \"videoId\":\"zzzzzzzzzzz\"</html>"
        assertEquals(listOf("zzzzzzzzzzz", "yyyyyyyyyyy"), YouTubeResults.parse(odd).map { it.id })
        assertTrue(YouTubeResults.parse("<html>nothing here</html>").isEmpty())
        assertNull(YouTubeResults.best(emptyList()))
        assertNull(YouTubeResults.initialData("<html></html>"))
    }

    // ---- 6. the phone's own scan, the lending and the voice ---------------------------------------------------------

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun resolve(pkg: String, name: String = "$pkg.Main") = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply { packageName = pkg; this.name = name; applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = pkg; this.name = pkg } }
    }

    private fun install(pkg: String, label: String) {
        val info = android.content.pm.PackageInfo().apply {
            packageName = pkg
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = pkg; this.name = label; nonLocalizedLabel = label }
        }
        shadowOf(app.packageManager).installPackage(info)
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), resolve(pkg))
    }

    private fun link(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)

    @Test fun thePackageScanFindsWhatEachAppSaysItHandlesAndLeavesBrowsersOut() {
        val pm = shadowOf(app.packageManager)
        install("test.music.one", "Metrolist Nightly"); install("test.video.one", "RVX YouTube"); install("test.browser", "A Browser"); install("test.plain", "Calculator")
        pm.addResolveInfoForIntent(Intent(MediaIntents.PLAY_FROM_SEARCH), resolve("test.music.one"))
        pm.addResolveInfoForIntent(link(MediaIntents.WATCH + "dQw4w9WgXcQ"), resolve("test.video.one"))
        pm.addResolveInfoForIntent(link(MediaIntents.WATCH + "dQw4w9WgXcQ"), resolve("test.browser"))
        pm.addResolveInfoForIntent(link(MediaIntents.PLAIN_WEB), resolve("test.browser"))
        pm.addResolveInfoForIntent(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://media/external/audio/media/1"), "audio/*"), resolve("test.music.one"))
        val apps = AndroidMediaDeps(app).installed().associateBy { it.pkg }
        assertTrue(apps["test.music.one"]!!.playFromSearch && apps["test.music.one"]!!.audioFiles && !apps["test.music.one"]!!.videoLinks)
        assertTrue(apps["test.video.one"]!!.videoLinks && !apps["test.video.one"]!!.playFromSearch)
        assertFalse("a browser opens any web page, so it is not a video app", apps["test.browser"]!!.videoLinks)
        assertFalse(apps["test.plain"]!!.any)
        assertEquals("Metrolist Nightly", apps["test.music.one"]!!.label)
    }

    @Test fun thePhonesOwnDefaultIsUsedOnlyWhenItIsAnAppAndNotTheChooser() {
        val pm = shadowOf(app.packageManager)
        val deps = AndroidMediaDeps(app)
        assertNull(deps.systemDefault(false))
        pm.addResolveInfoForIntent(Intent(MediaIntents.PLAY_FROM_SEARCH), resolve("android", "com.android.internal.app.ResolverActivity"))
        assertNull("the chooser is not a default", deps.systemDefault(false))
        pm.addResolveInfoForIntent(link(MediaIntents.WATCH + "dQw4w9WgXcQ"), resolve("test.video.one"))
        assertEquals("test.video.one", deps.systemDefault(true))
    }

    @Test fun withoutNotificationAccessTheRealSessionsSayHowToGetIt() {
        val s = AndroidMediaDeps(app).sessions()
        assertTrue(s is Sessions.NoAccess)
        assertTrue((s as Sessions.NoAccess).why.contains("notification access"))
    }

    @Test fun theMediaStoreSearchAnswersEmptyNotACrashWhenThereIsNothing() {
        val r = AndroidMediaDeps(app).local("Underway", MediaKind.SONG)
        assertTrue(r is LocalResult.Found || r is LocalResult.Denied)
    }

    @Test fun bothToolsAreLentAndOfferedToTheVoiceAndNamedInAndroidIntent() {
        val lent = com.past9.phoneaos.remote.DeviceTools
        assertTrue("play_media" in lent.LENT && "media_control" in lent.LENT)
        assertEquals("automation", lent.groupOf("play_media")); assertEquals("automation", lent.groupOf("media_control"))
        val live = com.past9.phoneaos.assist.LiveAssistCore.tools().getJSONObject(0).getJSONArray("functionDeclarations")
        val names = (0 until live.length()).map { live.getJSONObject(it).getString("name") }
        assertEquals(listOf("hand_to_agent", "answer_ask", "play_media", "media_control"), names)
        val call = com.past9.phoneaos.voice.CallController.callTools(linked = true).getJSONObject(0).getJSONArray("functionDeclarations")
        assertTrue((0 until call.length()).map { call.getJSONObject(it).getString("name") }.containsAll(listOf("play_media", "media_control")))
        assertTrue(com.past9.phoneaos.assist.LiveAssistCore.systemPrompt("A", "B", true).contains("play_media"))
        val tools = MediaTools.tools(Deps(emptyList()))
        assertEquals(listOf("play_media", "media_control"), tools.map { it.spec.name })
        assertTrue(tools[0].spec.description.contains("ONLY way to play"))
        val intent = com.past9.phoneaos.automation.AndroidIntentTool(object : com.past9.phoneaos.automation.IntentSettings { override fun intentAllowlist() = emptyList<IntentRules.Pair>() }) { "sent" }
        assertTrue(intent.spec.description.contains("play_media is the only way to play"))
    }
}
