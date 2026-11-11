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
        var screen: Fallback = Fallback.Off
        var slept = 0L
        var screenCalls = 0
        val list: MutableList<MediaSessionView> get() = ((sessions as Sessions.Ok).list as MutableList)
        override fun installed() = apps
        override fun systemDefault(video: Boolean) = sys
        override fun stored(video: Boolean) = stored
        override fun sessions() = sessions
        override fun launch(spec: IntentSpec): String { launched += spec; return onLaunch(spec) }
        override fun local(query: String, kind: MediaKind) = localResult
        override suspend fun youtube(query: String) = hits
        override suspend fun screen(req: ScreenRequest, ctx: ToolContext): Fallback { screenCalls++; return screen }
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

    @Test fun theRequestIsSentToTheChosenAppAndAnswersWhatIsPlaying() {
        val d = deps(metro, rvx)
        d.stored = metro.pkg
        d.onLaunch = { s -> d.list += FakeSession(s.pkg, "Metrolist Nightly", PlaybackState.STATE_PLAYING, "Underway", "Some Band"); "sent" }
        val out = play(d, "query" to "Underway by Some Band")
        assertEquals("Playing \"Underway\" by Some Band in Metrolist Nightly.", out)
        assertEquals(1, d.launched.size)
        assertEquals("test.music.one", d.launched[0].pkg)
        assertEquals("android.media.action.MEDIA_PLAY_FROM_SEARCH", d.launched[0].action)
    }

    @Test fun anAppNamedInTheCallBeatsTheDefault() {
        val d = deps(metro, rvx)
        d.stored = metro.pkg
        d.onLaunch = { s -> d.list += FakeSession(s.pkg, "RVX Music", PlaybackState.STATE_PLAYING, "Underway"); "sent" }
        val out = play(d, "query" to "Underway", "app" to "rvx")
        assertTrue(out, out.startsWith("Playing \"Underway\" in RVX Music"))
        assertEquals("test.music.two", d.launched[0].pkg)
    }

    @Test fun whenTheRequestIsIgnoredTheSessionIsAskedToPlayTheSearch() {
        val d = deps(rvx)
        val s = FakeSession("test.music.two", "RVX Music", PlaybackState.STATE_PAUSED, "Old")
        s.onPlayFromSearch = { me, q -> me.state = PlaybackState.STATE_PLAYING; me.title = q }
        d.list += s
        val out = play(d, "query" to "Underway")
        assertEquals("Playing \"Underway\" in RVX Music. (asked the app's media session directly)", out)
        assertEquals(listOf("search Underway"), s.calls)
    }

    @Test fun anAppThatIsClosedIsReachedThroughItsMediaBrowserService() {
        val d = deps(rvx)
        d.browserOk = true
        val out = play(d, "query" to "Underway")
        assertEquals("Playing \"Underway\" by Some Band in RVX Music. (asked the app's media session directly)", out)
        assertEquals(1, d.browserAsked)
    }

    @Test fun aRequestAndroidTurnedIntoANotificationStillTriesTheSession() {
        val d = deps(rvx)
        d.onLaunch = { "sent: as a notification the user taps, because Android does not let an app open a screen from the background." }
        val s = FakeSession("test.music.two", "RVX Music", PlaybackState.STATE_PAUSED, "Old")
        s.onPlayFromSearch = { me, q -> me.state = PlaybackState.STATE_PLAYING; me.title = q }
        d.list += s
        val out = play(d, "query" to "Underway")
        assertTrue(out, out.startsWith("Playing"))
        assertEquals("no wait was spent on an intent that never started", 0L, d.slept)
    }

    @Test fun anAppThatOpensButNeverPlaysIsSaidSoWithEveryStepTried() {
        val d = deps(rvx)
        val out = play(d, "query" to "Underway")
        assertTrue(out, out.startsWith("failed: could not get \"Underway\" playing in RVX Music."))
        assertTrue(out, out.contains("no media session from it appeared in 8 s"))
        assertTrue(out, out.contains("no media browser service that let the Agent app in"))
        assertTrue(out, out.contains("no file on the phone matches"))
        assertTrue(out, out.contains("background screen is off") && out.contains("Settings > Background screen"))
        assertEquals(1, d.screenCalls)
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
        val out = play(d, "query" to "Underway")
        assertTrue(out, out.startsWith("Sent the play request to Metrolist Nightly") && out.contains("cannot confirm") && out.contains("Notification access"))
    }

    @Test fun aFileOnThePhonePlaysThroughAMusicApp() {
        val d = deps(rvx, files)
        d.localResult = LocalResult.Found(listOf(LocalTrack("content://media/external/audio/media/9", "Underway", "Some Band", "Album")))
        d.onLaunch = { s ->
            if (s.action == Intent.ACTION_VIEW) d.list += FakeSession(s.pkg, "Plain Player", PlaybackState.STATE_PLAYING, "Underway", "Some Band")
            "sent"
        }
        val out = play(d, "query" to "Underway by Some Band", "app" to "RVX Music")
        assertEquals("Playing \"Underway\" by Some Band in Plain Player. (a file on the phone)", out)
        val view = d.launched.last()
        assertEquals("test.files.one", view.pkg)
        assertEquals("audio/*", view.mimeType)
        assertEquals("content://media/external/audio/media/9", view.data)
    }

    @Test fun localOnlySkipsTheAppsSearchAndDeniedStoragePermissionIsSaidPlainly() {
        val d = deps(metro)
        d.localResult = LocalResult.Denied
        val out = play(d, "query" to "Underway", "local" to true)
        assertTrue(out, out.startsWith("failed:") && out.contains("storage access"))
        assertTrue("no search request was sent", d.launched.isEmpty())
        assertEquals("the background screen is not tried for files", 0, d.screenCalls)
    }

    @Test fun aSavedPlayJobOnTheBackgroundScreenIsRunAndVerified() {
        val d = deps(rvx)
        d.screen = Fallback.Ran("ok")
        // The screen job is what starts it: the session appears only after the screen was used.
        val s = FakeSession("test.music.two", "RVX Music", PlaybackState.STATE_NONE, "")
        d.list += s
        var asked = false
        s.onPlayFromSearch = { _, _ -> asked = true }
        val screenDeps = object : MediaDeps by d {
            override suspend fun screen(req: ScreenRequest, ctx: ToolContext): Fallback { s.state = PlaybackState.STATE_PLAYING; s.title = "Underway"; return Fallback.Ran("done") }
        }
        val out = runBlocking { PlayMediaTool(screenDeps).run(JSONObject().put("query", "Underway"), Ctx()) }
        assertTrue(asked)
        assertTrue(out, out.startsWith("Playing") && out.endsWith("(done on the background screen)"))
    }

    @Test fun withoutALearnedJobTheFailureCarriesTheStepsToDoItByHand() {
        val d = deps(rvx)
        d.screen = Fallback.Hint("the background screen is on but there is no saved play job for RVX Music yet. Do it once with the screen tools")
        val out = play(d, "query" to "Underway")
        assertTrue(out, out.contains("no saved play job for RVX Music"))
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

    @Test fun aVideoIsFoundAndOpenedInTheVideoApp() {
        val d = deps(metro, yt)
        d.stored = null
        d.hits = listOf(YouTubeResults.Hit("AAAAAAAAAAA", "Promoted", "Ad", ""), YouTubeResults.Hit("dQw4w9WgXcQ", "How to tie a tie", "Ties Inc", "3:21"))
        d.onLaunch = { s -> d.list += FakeSession(s.pkg, "RVX YouTube", PlaybackState.STATE_PLAYING, "How to tie a tie", "Ties Inc"); "sent" }
        val out = play(d, "query" to "how to tie a tie", "kind" to "video")
        assertEquals("Playing \"How to tie a tie\" by Ties Inc in RVX YouTube.", out)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", d.launched.single().data)
        assertEquals("test.video.one", d.launched.single().pkg)
    }

    @Test fun aVideoThatOpensWithoutASessionIsOpenedButNotClaimedAsPlaying() {
        val d = deps(yt)
        d.hits = listOf(YouTubeResults.Hit("dQw4w9WgXcQ", "How to tie a tie", "Ties Inc", "3:21"))
        val out = play(d, "query" to "how to tie a tie", "kind" to "video")
        assertTrue(out, out.startsWith("Opened \"How to tie a tie\"") && out.contains("cannot confirm"))
    }

    @Test fun whenYouTubeCannotBeReadTheResultsPageOpensAndItSaysNothingIsPlaying() {
        val d = deps(yt)
        d.hits = null
        val out = play(d, "query" to "lofi beats", "kind" to "video")
        assertTrue(out, out.startsWith("Could not pick a video") && out.contains("nothing is playing yet"))
        assertEquals("https://www.youtube.com/results?search_query=lofi+beats", d.launched.single().data)
    }

    @Test fun aVideoFromTheBackgroundIsAFailureNotASilentNotification() {
        val d = deps(yt)
        d.hits = listOf(YouTubeResults.Hit("dQw4w9WgXcQ", "x", "y", "1:00"))
        d.onLaunch = { "sent: as a notification the user taps" }
        assertTrue(play(d, "query" to "x", "kind" to "video").startsWith("failed: Android would not open RVX YouTube from the background"))
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
        assertEquals(listOf("hand_to_agent", "play_media", "media_control"), names)
        val call = com.past9.phoneaos.voice.CallController.callTools(linked = true).getJSONObject(0).getJSONArray("functionDeclarations")
        assertTrue((0 until call.length()).map { call.getJSONObject(it).getString("name") }.containsAll(listOf("play_media", "media_control")))
        assertTrue(com.past9.phoneaos.assist.LiveAssistCore.systemPrompt("A", "B", true).contains("play_media"))
        val tools = MediaTools.tools(Deps(emptyList()))
        assertEquals(listOf("play_media", "media_control"), tools.map { it.spec.name })
        assertTrue(tools[0].spec.description.contains("never open a search page"))
        val intent = com.past9.phoneaos.automation.AndroidIntentTool(object : com.past9.phoneaos.automation.IntentSettings { override fun intentAllowlist() = emptyList<IntentRules.Pair>() }) { "sent" }
        assertTrue(intent.spec.description.contains("use play_media"))
    }
}
