package com.past9.phoneaos.media

import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.app.NotificationManagerCompat
import com.past9.phoneaos.App
import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.bool
import com.past9.phoneaos.agent.enumOf
import com.past9.phoneaos.agent.int
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.automation.IntentSender
import com.past9.phoneaos.automation.IntentSpec
import com.past9.phoneaos.system.NotificationReader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** What the media tools need from the phone. Tests hand in a fake; [AndroidMediaDeps] is the real one. */
interface MediaDeps {
    /** Every launchable app, and the ones that handle media but are not launchable, with what each handles. */
    fun installed(): List<MediaApp>
    /** The package the phone itself would use for this, or null when it would ask. */
    fun systemDefault(video: Boolean): String?
    /** The package the user picked in Settings > Media apps, or null for automatic. */
    fun stored(video: Boolean): String?
    fun sessions(): Sessions
    /** Sends an intent: "sent", "sent: with a note" or "failed: why". */
    fun launch(spec: IntentSpec): String
    fun local(query: String, kind: MediaKind): LocalResult
    /** The YouTube results for these words, or null when they could not be read. */
    suspend fun youtube(query: String): List<YouTubeResults.Hit>?
    suspend fun screen(req: ScreenRequest, ctx: ToolContext): Fallback
    /** Connects to the app's media browser service and asks its session to play a search. False when it has none or it refused us. */
    suspend fun askViaBrowser(pkg: String, query: String, kind: MediaKind): Boolean
    suspend fun sleep(ms: Long)
}

class ScreenRequest(val app: MediaApp, val query: String, val kind: MediaKind)

sealed interface Fallback {
    /** The background screen is switched off. */
    data object Off : Fallback
    /** A learned job for this app ran; this is what it answered. */
    class Ran(val text: String) : Fallback
    /** No learned job yet: how to do it by hand with the screen tools, and save it. */
    class Hint(val text: String) : Fallback
}

const val NO_ACCESS_HELP = "Turn on notification access for Agent: Settings > Notifications > Device & app notifications > Notification access (the notifications_allow tool opens it)."

/**
 * play_media: play music or a video on this phone, in the app the user already uses. The app is found at run time
 * (see [MediaResolve]); the answer says what is playing or exactly which step failed.
 */
class PlayMediaTool(private val deps: MediaDeps, private val waitMs: Long = 8_000, private val retryMs: Long = 5_000) : Tool {
    override val spec = ToolSpec("play_media",
        "PLAY music or a video on this phone, in the app the user already uses. ALWAYS use this when asked to play, put on or listen to something: never open a search page or a link with android_intent for that. " +
            "query: what to play, as the user said it ('Underway by Name', 'Daft Punk', 'lofi beats', 'how to tie a tie'). " +
            "kind: song (default), artist, album, playlist, podcast, or video (a YouTube video: it finds the video and opens it in the user's video app). " +
            "app: only when the user names one (its name or package); otherwise the default they chose in Settings > Media apps, else the phone's default, else an app that handles play requests. " +
            "local: true to play a file stored on the phone only. " +
            "It then checks the app's media session and answers what is playing (title, artist, app), or which step failed and why. Do not tell the user it is playing unless this says so. " +
            "To pause, skip or see what is playing, use media_control.",
        schema(listOf("query"),
            "query" to str("What to play, in the user's words"),
            "kind" to enumOf("What it is", "song", "artist", "album", "playlist", "video", "podcast"),
            "app" to str("The app to use, by name or package. Leave out to use the user's default."),
            "local" to bool("true: only files stored on the phone")))

    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val query = input.optString("query").trim()
        if (query.isEmpty()) return "failed: say what to play."
        val kind = if (input.optString("kind").isBlank()) MediaKind.SONG else MediaKind.parse(input.optString("kind"))
            ?: return "failed: kind must be one of song, artist, album, playlist, video, podcast."
        val app = input.optString("app").trim().ifBlank { null }
        val out = runCatching { if (kind.isVideo) video(query, app, ctx) else music(query, kind, app, input.optBoolean("local", false), ctx) }
            .getOrElse { "failed: ${it.message ?: it::class.java.simpleName}" }
        runCatching { ctx.activity("Play: ${query.take(60)} (${out.substringBefore(':').take(20)})", JSONObject().put("tool", "play_media").put("query", query).put("result", out.take(300))) }
        return out
    }

    private fun playingLine(p: MediaVerify.Outcome.Playing, note: String = ""): String =
        "Playing ${MediaVerify.what(p.s)}." + (if (p.buffering) " It is still loading." else "") +
            (if (!p.matches) " That may not be what was asked for: the app may have kept what it had." else "") + note

    private suspend fun music(query: String, kind: MediaKind, appArg: String?, localOnly: Boolean, ctx: ToolContext): String {
        val log = mutableListOf<String>()
        val installed = deps.installed()
        val pool = installed.filter { it.playFromSearch }
        val first = deps.sessions()
        val active = (first as? Sessions.Ok)?.list.orEmpty().sortedByDescending { MediaVerify.playing(it) }.map { it.pkg }
        val pick = MediaResolve.pick(pool, installed, appArg, deps.stored(false), deps.systemDefault(false), active)
        pick.error?.let { return "failed: $it" }
        val chosen = pick.app
        val label = chosen?.label.orEmpty()

        if (chosen == null && !localOnly) log += "no app on this phone says it can play from a search (install a music app, or name one with app)"
        if (chosen != null && !localOnly) {
            val before = (first as? Sessions.Ok)?.list?.firstOrNull { it.pkg == chosen.pkg }
            val stale = before?.takeIf { MediaVerify.playing(it) }?.title
            val res = deps.launch(MediaIntents.playFromSearch(query, kind, chosen.pkg))
            val blocked = res.startsWith("sent: as a notification")
            when {
                res.startsWith("failed") -> log += "the play request to $label failed (${res.removePrefix("failed: ")})"
                blocked -> log += "Android would not open $label from the background, so it was left as a \"Tap to open\" notification (open the Agent app, or allow Display over other apps, so it can open at once)"
                else -> {
                    when (val o = MediaVerify.await(deps::sessions, chosen.pkg, query, waitMs, stale, sleep = { deps.sleep(it) })) {
                        is MediaVerify.Outcome.Playing -> if (o.matches || o.s.title != stale) return playingLine(o)
                            else log += "$label kept playing ${MediaVerify.what(o.s)} and did not switch"
                        is MediaVerify.Outcome.NotPlaying -> log += "$label opened and has a media session, but it is ${MediaVerify.state(o.s)}"
                        MediaVerify.Outcome.NoSession -> log += "$label got the request but no media session from it appeared in ${waitMs / 1000} s"
                        is MediaVerify.Outcome.NoAccess -> return "Sent the play request to $label ($label is ${pick.why}). I cannot confirm it is playing: ${o.why} $NO_ACCESS_HELP"
                    }
                }
            }
            // The session itself can be told to play a search, which many apps honour when they ignore the screen's request.
            val s = (deps.sessions() as? Sessions.Ok)?.list?.firstOrNull { it.pkg == chosen.pkg }
            // No session yet (the app is not running): connect to its media browser service, which is how Android Auto and assistants reach an app that is closed.
            val asked = when {
                s != null -> { runCatching { s.playFromSearch(query, MediaIntents.bundle(query, kind)) }.onFailure { log += "asking $label's session to play it failed (${it.message})" }; true }
                else -> deps.askViaBrowser(chosen.pkg, query, kind)
            }
            if (asked) {
                when (val o = MediaVerify.await(deps::sessions, chosen.pkg, query, retryMs, stale, sleep = { deps.sleep(it) })) {
                    is MediaVerify.Outcome.Playing -> if (o.matches || o.s.title != stale) return playingLine(o, " (asked the app's media session directly)")
                        else log += "asking $label's media session to play it left ${MediaVerify.what(o.s)} playing"
                    else -> log += "asking $label's media session to play it did not start anything"
                }
            } else log += "$label has no media session, and no media browser service that let the Agent app in, to ask directly"
        }

        // A file on the phone.
        when (val r = deps.local(query, kind)) {
            LocalResult.Denied -> log += "the phone's music files could not be read (Agent needs storage access: allow it in the phone's app settings for Agent)"
            is LocalResult.Found -> {
                val t = LocalRank.best(r.tracks, query)
                if (t == null) log += "no file on the phone matches \"$query\""
                else {
                    val viewer = chosen?.takeIf { it.audioFiles } ?: MediaResolve.pick(installed.filter { it.audioFiles }, installed, null, deps.stored(false), deps.systemDefault(false), active).app
                    val spec = if (viewer != null) MediaIntents.viewAudio(t.uri, viewer.pkg)
                        else if (chosen != null) MediaIntents.playFromSearch("${t.title} ${t.artist}".trim(), MediaKind.SONG, chosen.pkg) else null
                    val target = viewer ?: chosen
                    if (spec == null || target == null) log += "found \"${t.title}\" on the phone but no app can open music files"
                    else {
                        val res = deps.launch(spec)
                        if (res.startsWith("failed") || res.startsWith("sent: as a notification")) log += "found \"${t.title}\" on the phone but could not open it in ${target.label} (${res.removePrefix("failed: ").take(120)})"
                        else when (val o = MediaVerify.await(deps::sessions, target.pkg, "${t.title} ${t.artist}", retryMs, sleep = { deps.sleep(it) })) {
                            is MediaVerify.Outcome.Playing -> return playingLine(o, " (a file on the phone)")
                            is MediaVerify.Outcome.NoAccess -> return "Opened \"${t.title}\" from the phone's files in ${target.label}. I cannot confirm it is playing: ${o.why} $NO_ACCESS_HELP"
                            else -> log += "opened the file \"${t.title}\" in ${target.label} but it did not start"
                        }
                    }
                }
            }
        }

        // Last: use the app on the background screen, the way a person would.
        if (chosen != null && !localOnly) {
            when (val f = deps.screen(ScreenRequest(chosen, query, kind), ctx)) {
                Fallback.Off -> log += "the background screen is off: switching it on in Settings > Background screen lets the agent search in $label itself, tap the result and save the steps for next time"
                is Fallback.Ran -> when (val o = MediaVerify.await(deps::sessions, chosen.pkg, query, retryMs, sleep = { deps.sleep(it) })) {
                    is MediaVerify.Outcome.Playing -> return playingLine(o, " (done on the background screen)")
                    else -> log += "the saved play job for $label ran (${f.text.take(120)}) but nothing is playing"
                }
                is Fallback.Hint -> log += f.text
            }
        }
        return "failed: could not get \"$query\" playing" + (if (label.isNotEmpty()) " in $label" else "") + ". What was tried: " + log.joinToString("; ") + "."
    }

    private suspend fun video(query: String, appArg: String?, ctx: ToolContext): String {
        val installed = deps.installed()
        val pool = installed.filter { it.videoLinks }
        val first = deps.sessions()
        val active = (first as? Sessions.Ok)?.list.orEmpty().sortedByDescending { MediaVerify.playing(it) }.map { it.pkg }
        val pick = MediaResolve.pick(pool, installed, appArg, deps.stored(true), deps.systemDefault(true), active)
        pick.error?.let { return "failed: $it" }
        val chosen = pick.app
        val pkg = chosen?.pkg.orEmpty()
        val label = chosen?.label ?: "the phone's default app"
        val hit = deps.youtube(query)?.let { YouTubeResults.best(it) }
        if (hit == null) {
            // No video to point at: let the app's own search try, else show the results page.
            if (chosen?.playFromSearch == true) {
                val res = deps.launch(MediaIntents.playFromSearch(query, MediaKind.VIDEO, chosen.pkg))
                if (res.startsWith("failed")) return "failed: could not look the video up on YouTube and ${chosen.label} refused the search (${res.removePrefix("failed: ")})."
                return "Could not look the video up on YouTube from here, so ${chosen.label} was asked to search for \"$query\". It may only have opened its search; check what is playing with media_control now_playing."
            }
            val res = deps.launch(MediaIntents.results(query, pkg))
            return if (res.startsWith("failed")) "failed: could not look the video up on YouTube (no connection, or YouTube's page changed) and the results page would not open (${res.removePrefix("failed: ")})."
            else "Could not pick a video: YouTube could not be read from here. Opened the results page for \"$query\" in $label instead; nothing is playing yet."
        }
        val res = deps.launch(MediaIntents.watch(hit.id, pkg))
        if (res.startsWith("failed")) return "failed: could not open the video in $label (${res.removePrefix("failed: ")})."
        if (res.startsWith("sent: as a notification")) return "failed: Android would not open $label from the background, so the video is a \"Tap to open\" notification. Open the Agent app, or allow Display over other apps, so it can open at once."
        val title = hit.title.ifBlank { query }
        if (pkg.isEmpty()) return "Opened \"$title\" (${hit.channel.ifBlank { "YouTube" }}) with the phone's default for YouTube links, which is not a video app that shares a media session, so I cannot confirm it is playing."
        return when (val o = MediaVerify.await(deps::sessions, pkg, title, waitMs, sleep = { deps.sleep(it) })) {
            is MediaVerify.Outcome.Playing -> "Playing ${MediaVerify.what(o.s)}." + (if (o.buffering) " It is still loading." else "")
            is MediaVerify.Outcome.NotPlaying -> "Opened \"$title\" in $label, but its media session says ${MediaVerify.state(o.s)}. Try media_control play."
            MediaVerify.Outcome.NoSession -> "Opened \"$title\" (${hit.channel}) in $label. No media session appeared in ${waitMs / 1000} s, so I cannot confirm it is playing: video apps often publish one only once the video has started."
            is MediaVerify.Outcome.NoAccess -> "Opened \"$title\" (${hit.channel}) in $label. I cannot confirm it is playing: ${o.why} $NO_ACCESS_HELP"
        }
    }
}

/** media_control: pause, play, skip, seek or ask what is playing, through the phone's media sessions. */
class MediaControlTool(private val deps: MediaDeps) : Tool {
    override val spec = ToolSpec("media_control",
        "Control what is playing on this phone, in any media app (music or video): play, pause, next, previous, seek to a position, or now_playing (title, artist, album, app, state, position and length). " +
            "Acts on the app that is playing, else the one that played most recently; name an app with app to pick another. To START something use play_media. Needs notification access for Agent: the answer says so when it is missing.",
        schema(listOf("action"),
            "action" to enumOf("What to do", "play", "pause", "next", "previous", "seek", "now_playing"),
            "position_ms" to int("For seek: where to go, in milliseconds from the start"),
            "app" to str("Act on this app's session instead, by name or package")))

    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val action = input.optString("action").trim().lowercase()
        if (action !in listOf("play", "pause", "next", "previous", "seek", "now_playing")) return "failed: action must be one of play, pause, next, previous, seek, now_playing."
        val app = input.optString("app").trim().ifBlank { null }
        val r = deps.sessions()
        if (r is Sessions.NoAccess) return "failed: ${r.why} $NO_ACCESS_HELP"
        val list = (r as Sessions.Ok).list
        if (action == "now_playing") {
            val s = MediaVerify.choose(list, app) ?: return if (list.isEmpty()) "Nothing is playing: no app has an open media session." else "No media session from \"$app\". Open sessions: ${list.joinToString(", ") { it.label }}."
            val others = list.filter { it !== s }
            return MediaVerify.line(s) + if (others.isNotEmpty()) "\nAlso open: " + others.joinToString("; ") { MediaVerify.line(it) } else ""
        }
        val s = MediaVerify.choose(list, app) ?: return "failed: ${if (list.isEmpty()) "no app has an open media session, so there is nothing to ${action}. Use play_media to start something" else "no media session from \"$app\""}."
        runCatching {
            when (action) {
                "play" -> s.play(); "pause" -> s.pause(); "next" -> s.next(); "previous" -> s.previous()
                else -> {
                    if (!input.has("position_ms")) return "failed: seek needs position_ms."
                    s.seekTo(input.optLong("position_ms").coerceAtLeast(0))
                }
            }
        }.onFailure { return "failed: ${s.label} refused it (${it.message ?: it::class.java.simpleName})." }
        deps.sleep(700)
        val now = (deps.sessions() as? Sessions.Ok)?.list?.firstOrNull { it.pkg == s.pkg } ?: s
        return "Sent $action to ${s.label}. Now: " + MediaVerify.line(now)
    }
}

object MediaTools {
    fun tools(deps: MediaDeps): List<Tool> = listOf(PlayMediaTool(deps), MediaControlTool(deps))
    fun tools(context: Context): List<Tool> = tools(AndroidMediaDeps(context))

    /** Run one of the media tools straight from the phone (the live voice and a voice call), with no chat to ask in. */
    suspend fun runDirect(context: Context, name: String, args: JSONObject): String {
        val tool = tools(context).firstOrNull { it.spec.name == name } ?: return "failed: no such tool $name."
        return tool.run(args, VoiceToolContext(context))
    }

    /** The "Play from search" words in the tool descriptions of other tools, kept in one place. */
    const val PREFER = "To play music or a video use play_media; to pause, skip or see what is playing use media_control."
}

/** A tool run for the voice: no chat to put activity in and nobody to ask mid-sentence. */
class VoiceToolContext(private val context: Context) : ToolContext {
    override val agentLabel = "voice"
    override suspend fun activity(text: String, meta: JSONObject): Long = 0
    override suspend fun updateActivity(id: Long, text: String, meta: JSONObject) {}
    override suspend fun ask(question: String, options: List<String>): String? = null
    override suspend fun notify(title: String, body: String) = App.graph(context).phone.notify(title, body)
}

/** The phone's own side of the media tools: package manager, media sessions, the media store, the web. */
class AndroidMediaDeps(private val context: Context) : MediaDeps {
    private val pm get() = context.packageManager
    private val settings get() = App.graph(context).settings

    private fun label(pkg: String): String = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    private fun packages(i: Intent): Set<String> =
        runCatching { pm.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }.toSet() }.getOrDefault(emptySet())

    private fun link(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)

    override fun installed(): List<MediaApp> {
        val search = packages(Intent(MediaIntents.PLAY_FROM_SEARCH))
        // An app that opens any web page as well as a watch link is a browser, not a video app.
        val video = packages(link(MediaIntents.WATCH + "dQw4w9WgXcQ")) - packages(link(MediaIntents.PLAIN_WEB))
        val audio = packages(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://media/external/audio/media/1"), "audio/*"))
        val launcher = runCatching { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { it.activityInfo.packageName } }.getOrDefault(emptyList())
        return (launcher + search + video + audio).toSet().filter { it != context.packageName }
            .map { MediaApp(it, label(it), it in search, it in video, it in audio) }
    }

    override fun systemDefault(video: Boolean): String? {
        val i = if (video) link(MediaIntents.WATCH + "dQw4w9WgXcQ") else Intent(MediaIntents.PLAY_FROM_SEARCH)
        val ri = runCatching { pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) }.getOrNull()?.activityInfo ?: return null
        return ri.packageName.takeIf { it != "android" && !ri.name.contains("ResolverActivity") }
    }

    override fun stored(video: Boolean): String? = settings.mediaDefault(video)

    override fun sessions(): Sessions {
        if (context.packageName !in NotificationManagerCompat.getEnabledListenerPackages(context))
            return Sessions.NoAccess("Agent does not have notification access, which Android requires to see media sessions.")
        return try {
            val msm = context.getSystemService(MediaSessionManager::class.java) ?: return Sessions.NoAccess("This phone has no media session service.")
            Sessions.Ok(msm.getActiveSessions(ComponentName(context, NotificationReader::class.java)).map { ControllerView(it, label(it.packageName)) })
        } catch (e: SecurityException) {
            Sessions.NoAccess("Android refused Agent the media sessions.")
        }
    }

    override fun launch(spec: IntentSpec): String = IntentSender(context).send(spec)

    override fun local(query: String, kind: MediaKind): LocalResult {
        val p = MediaIntents.parts(query, kind)
        val terms = listOfNotNull(p.title, p.artist, p.album).ifEmpty { listOf(query.trim()) }
        val cols = listOf(MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM)
        val where = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND (" + terms.flatMap { cols }.joinToString(" OR ") { "$it LIKE ?" } + ")"
        val args = terms.flatMap { t -> cols.map { "%${t.replace("%", "").replace("_", "")}%" } }.toTypedArray()
        return try {
            val out = mutableListOf<LocalTrack>()
            context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM),
                where, args, null)?.use { c ->
                while (c.moveToNext() && out.size < 100) out += LocalTrack(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0)).toString(),
                    c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getString(3).orEmpty())
            }
            LocalResult.Found(out)
        } catch (e: SecurityException) { LocalResult.Denied }
    }

    override suspend fun youtube(query: String): List<YouTubeResults.Hit>? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(YouTubeResults.url(query)).openConnection() as HttpURLConnection
            c.connectTimeout = 8_000; c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            c.setRequestProperty("Cookie", "SOCS=CAI; CONSENT=YES+1")
            try { YouTubeResults.parse(c.inputStream.bufferedReader().use { it.readText() }).takeIf { it.isNotEmpty() } } finally { c.disconnect() }
        }.getOrNull()
    }

    override suspend fun screen(req: ScreenRequest, ctx: ToolContext): Fallback {
        if (!com.past9.phoneaos.screen.Screens.on(context)) return Fallback.Off
        val g = App.graph(context)
        val app = g.runtime.learned.apps.value.firstOrNull { it.isScreen && it.pkg == req.app.pkg }
        val tool = app?.tools?.firstOrNull { it.name == SKILL }
        if (app != null && tool != null)
            return Fallback.Ran(com.past9.phoneaos.connections.WebAppRun.call(app, tool, JSONObject().put("query", req.query), ctx, g.runtime.pageRunner))
        return Fallback.Hint("the background screen is on but there is no saved play job for ${req.app.label} yet. Do it once with the screen tools: screen_open_app ${req.app.label}, screen_type the search into its search box, " +
            "screen_tap the first matching result, screen_wait, check with media_control now_playing, then screen_skill_save name $SKILL with inputs {query: \"${req.query}\"} so next time play_media replays it")
    }

    override suspend fun askViaBrowser(pkg: String, query: String, kind: MediaKind): Boolean = withContext(Dispatchers.Main) {
        val svc = runCatching { pm.queryIntentServices(Intent("android.media.browse.MediaBrowserService").setPackage(pkg), 0).firstOrNull()?.serviceInfo }.getOrNull()
            ?: return@withContext false
        val connected = CompletableDeferred<Boolean>()
        val browser = MediaBrowser(context, ComponentName(svc.packageName, svc.name), object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() { connected.complete(true) }
            override fun onConnectionFailed() { connected.complete(false) }
        }, null)
        runCatching { browser.connect() }.onFailure { return@withContext false }
        val ok = withTimeoutOrNull(4_000) { connected.await() } ?: false
        if (!ok) { runCatching { browser.disconnect() }; return@withContext false }
        val sent = runCatching { MediaController(context, browser.sessionToken).transportControls.playFromSearch(query, MediaIntents.bundle(query, kind)) }.isSuccess
        // Stay connected while the app starts; let go afterwards so the connection does not hold the service.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { browser.disconnect() } }, 15_000)
        sent
    }

    override suspend fun sleep(ms: Long) = delay(ms)

    companion object { const val SKILL = "play_media" }
}

/** A MediaController as the tools see it. */
class ControllerView(private val c: MediaController, override val label: String) : MediaSessionView {
    override val pkg: String get() = c.packageName
    override val state: Int get() = c.playbackState?.state ?: 0
    override val title: String get() = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
    override val artist: String get() = (c.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: c.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty()
    override val album: String get() = c.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
    override val position: Long get() = c.playbackState?.position ?: -1
    override val duration: Long get() = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1
    override fun play() = c.transportControls.play()
    override fun pause() = c.transportControls.pause()
    override fun next() = c.transportControls.skipToNext()
    override fun previous() = c.transportControls.skipToPrevious()
    override fun seekTo(ms: Long) = c.transportControls.seekTo(ms)
    override fun playFromSearch(query: String, extras: Bundle) = c.transportControls.playFromSearch(query, extras)
}
