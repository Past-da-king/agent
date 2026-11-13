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
    /** Sends an intent: "sent", "sent: with a note" or "failed: why". Only for an app already in front on the user's own screen: nothing else may use it to play. */
    fun launch(spec: IntentSpec): String
    fun local(query: String, kind: MediaKind): LocalResult
    /** The YouTube results for these words, or null when they could not be read. */
    suspend fun youtube(query: String): List<YouTubeResults.Hit>?
    /** The package in front on the user's own screen, or null (nothing, or not known). Reads only. */
    suspend fun userFront(): String?
    /** A saved play job for this app exists. */
    fun hasJob(app: MediaApp): Boolean
    /** Does the saved play job on the background screen. */
    suspend fun screenReplay(req: ScreenRequest, ctx: ToolContext): Fallback
    /** Works out how to play it on the background screen, by reading the app (see [MediaScreenLearner]). */
    suspend fun screenLearn(req: ScreenRequest, ctx: ToolContext): Learned
    /** Saves the run that just played as the app's play job (read-only playback, saved without a card). The words saved, or null when not saved. */
    suspend fun screenSave(req: ScreenRequest, ctx: ToolContext): String?
    /** Opens [link] in the app on the background screen. */
    suspend fun screenLink(app: MediaApp, link: String, ctx: ToolContext): Fallback
    /** The job is over: free the background screen (the app stays warm for a few minutes). */
    suspend fun screenDone(ctx: ToolContext)
    /** Connects to the app's media browser service and asks its session to play a search. False when it has none or it refused us. */
    suspend fun askViaBrowser(pkg: String, query: String, kind: MediaKind): Boolean
    suspend fun sleep(ms: Long)
}

class ScreenRequest(val app: MediaApp, val query: String, val kind: MediaKind)

sealed interface Fallback {
    /** The background screen is switched off. */
    data object Off : Fallback
    /** No saved play job for this app yet. */
    data object NoJob : Fallback
    /** The saved job (or the link) was done on the background screen; this is what it answered. */
    class Ran(val text: String) : Fallback
    class Failed(val text: String) : Fallback
    /** The app is open on the user's own screen: it was left alone. */
    class InFront(val text: String) : Fallback
}

const val NO_ACCESS_HELP = "Turn on notification access for Agent: Settings > Notifications > Device & app notifications > Notification access (the notifications_allow tool opens it)."

/**
 * play_media: play music or a video on this phone, in the app the user already uses. The app is found at run time
 * (see [MediaResolve]); the answer says what is playing or exactly which step failed.
 *
 * Nothing here ever opens an app on the user's own screen. Order: the app's own media session (it is already running),
 * its media browser service (it is closed), a saved play job on the background screen, and, when there is none or it
 * broke, working it out on the background screen once ([MediaScreenLearner]) and saving it for next time. An app that
 * is already in front on the user's screen is asked in place (it stays in front, so their screen does not change); the
 * background screen cannot open it then, because that would take it from under them.
 */
class PlayMediaTool(private val deps: MediaDeps, private val waitMs: Long = 8_000, private val retryMs: Long = 5_000) : Tool {
    override val spec = ToolSpec("play_media",
        "PLAY music or a video on this phone, in the app the user already uses. This is the ONLY way to play media: when asked to play, put on or listen to something, call this. " +
            "Never use android_intent, open_url or any link for it (they would put the app on the user's own screen, and are refused); never open the app yourself after it fails. " +
            "It plays WITHOUT touching what the user is looking at: through the app's media session, or by using the app on the background screen (the first time it works the app out and saves the steps, so later plays take seconds). " +
            "query: what to play, as the user said it ('Underway by Name', 'Daft Punk', 'lofi beats', 'how to tie a tie'). " +
            "kind: song (default), artist, album, playlist, podcast, or video (it finds the video and plays it in the user's video app). " +
            "app: only when the user names one (its name or package); otherwise the default they chose in Settings > Media apps, else the phone's default, else an app that handles play requests. " +
            "local: true to play a file stored on the phone only. " +
            "It then checks the app's media session and answers what is playing (title, artist, app), or which step failed and why. Do not tell the user it is playing unless this says so; if it failed, say that. " +
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

    private fun offNote(label: String) = "the background screen is off: switching it on in Settings > Background screen lets the agent use $label there without touching the user's screen"

    /** The app's own media session, or its media browser service when it is closed. Nothing is opened. Null when it did not play. */
    private suspend fun viaSession(chosen: MediaApp, query: String, kind: MediaKind, stale: String?, log: MutableList<String>): String? {
        val label = chosen.label
        val s = (deps.sessions() as? Sessions.Ok)?.list?.firstOrNull { it.pkg == chosen.pkg }
        val asked = when {
            s != null -> { runCatching { s.playFromSearch(query, MediaIntents.bundle(query, kind)) }.onFailure { log += "asking $label's session to play it failed (${it.message})" }; true }
            else -> deps.askViaBrowser(chosen.pkg, query, kind)
        }
        if (!asked) { log += "$label has no media session, and no media browser service that let the Agent app in, to ask directly"; return null }
        var o = MediaVerify.await(deps::sessions, chosen.pkg, query, waitMs, stale, sleep = { deps.sleep(it) })
        // A session that is in error, stopped or paused ON THE RIGHT TRACK: its transport Play is the cheapest way back before any screen route.
        if (o is MediaVerify.Outcome.NotPlaying && MediaVerify.retryable(o.s) && MediaVerify.matches(query, o.s)) {
            val cur = o.s
            val was = MediaVerify.state(cur)
            runCatching { cur.play() }.onFailure { log += "asking $label's session to Play failed (${it.message})" }
            val again = MediaVerify.await(deps::sessions, chosen.pkg, query, retryMs, stale, sleep = { deps.sleep(it) })
            if (again is MediaVerify.Outcome.Playing && !again.matches) { runCatching { again.s.pause() }; log += "$label started something else after Play (${MediaVerify.what(again.s)}), so it was paused"; return null }
            else if (again is MediaVerify.Outcome.Playing) return playingLine(again, " (the app's session was $was, so it was sent Play)")
            else log += "$label's session was $was on the right track and Play did not start it"
            o = again
        }
        when (o) {
            is MediaVerify.Outcome.Playing -> if (o.matches || o.s.title != stale) return playingLine(o, " (asked the app's media session directly)")
                else log += "$label kept playing ${MediaVerify.what(o.s)} and did not switch"
            is MediaVerify.Outcome.NotPlaying -> if (log.none { it.contains("on the right track") }) log += "$label has a media session, but it is ${MediaVerify.state(o.s)}"
            MediaVerify.Outcome.NoSession -> log += "$label gave no media session within ${waitMs / 1000} s of being asked"
            is MediaVerify.Outcome.NoAccess -> return "Asked $label to play it. I cannot confirm it is playing: ${o.why} $NO_ACCESS_HELP"
        }
        return null
    }

    /** The app is already in front on the user's own screen: ask it there. It stays in front, so their screen does not change. */
    private suspend fun inPlace(chosen: MediaApp, query: String, kind: MediaKind, stale: String?, log: MutableList<String>): String? {
        val res = deps.launch(MediaIntents.playFromSearch(query, kind, chosen.pkg))
        if (res.startsWith("failed")) { log += "${chosen.label} is open in front of the user and refused the play request (${res.removePrefix("failed: ")})"; return null }
        when (val o = MediaVerify.await(deps::sessions, chosen.pkg, query, waitMs, stale, sleep = { deps.sleep(it) })) {
            is MediaVerify.Outcome.Playing -> if (o.matches || o.s.title != stale) return playingLine(o, " (asked in place: ${chosen.label} is in front on the user's screen, so it was not moved)")
                else log += "${chosen.label} kept playing ${MediaVerify.what(o.s)} and did not switch"
            is MediaVerify.Outcome.NoAccess -> return "Asked ${chosen.label} (open in front of the user) to play it. I cannot confirm it is playing: ${o.why} $NO_ACCESS_HELP"
            else -> log += "${chosen.label} is open in front of the user and did not start it"
        }
        log += "it is in front on the user's own screen, so it was not taken to the background screen"
        return null
    }

    /** The background screen: the saved play job; else, or when it broke, work it out once and save it. Null when nothing played. */
    private suspend fun onBackgroundScreen(req: ScreenRequest, ctx: ToolContext, log: MutableList<String>): String? {
        val label = req.app.label
        if (deps.hasJob(req.app)) {
            when (val f = deps.screenReplay(req, ctx)) {
                Fallback.Off -> { log += offNote(label); return null }
                is Fallback.InFront -> { log += f.text.take(200); return null }
                is Fallback.Ran -> when (val o = MediaVerify.await(deps::sessions, req.app.pkg, req.query, retryMs, sleep = { deps.sleep(it) })) {
                    is MediaVerify.Outcome.Playing -> return playingLine(o, " (replayed the saved play job on the background screen, not worked out again: ${f.text.substringBefore(". Steps").take(80)})")
                    is MediaVerify.Outcome.NoAccess -> return "Ran the saved play job for $label. I cannot confirm it is playing: ${o.why} $NO_ACCESS_HELP"
                    else -> log += "the saved play job for $label ran but nothing is playing, so it was learned again"
                }
                is Fallback.Failed -> log += "the saved play job for $label stopped (${f.text.take(120)}), so it was learned again"
                Fallback.NoJob -> {}
            }
        }
        return when (val l = deps.screenLearn(req, ctx)) {
            Learned.Off -> { log += offNote(label); null }
            is Learned.InFront -> { log += l.text.take(200); null }
            is Learned.Failed -> { log += "working it out on the background screen failed: ${l.text.take(200)}"; null }
            is Learned.NoAccess -> "Pressed play in $label on the background screen. I cannot confirm it is playing: ${l.why} $NO_ACCESS_HELP"
            is Learned.Playing -> {
                val saved = if (l.o.matches && l.clean) deps.screenSave(req, ctx) else null
                playingLine(l.o, " (done on the background screen" + (if (saved != null) "; saved as $label's play job, so the next play takes a few seconds" else "; not saved, because " + (if (!l.o.matches) "it may not be what was asked for" else "it took a wrong turn first, so it will be worked out again next time")) + ")")
            }
        }
    }

    private fun failed(query: String, label: String, log: List<String>) =
        "failed: could not get \"$query\" playing" + (if (label.isNotEmpty()) " in $label" else "") + ". What was tried: " + log.joinToString("; ") +
            ". Tell the user this failed. Do not open the app on their screen to try another way."

    /** The job is over: free the background screen for the next one. */
    private suspend fun done(ctx: ToolContext) { runCatching { deps.screenDone(ctx) } }

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
        val inFront = chosen != null && deps.userFront() == chosen.pkg

        if (chosen == null && !localOnly) log += "no app on this phone says it can play from a search (install a music app, or name one with app)"
        if (chosen != null && !localOnly) {
            val req = ScreenRequest(chosen, query, kind)
            val before = (first as? Sessions.Ok)?.list?.firstOrNull { it.pkg == chosen.pkg }
            val stale = before?.takeIf { MediaVerify.playing(it) }?.title
            val hadJob = deps.hasJob(chosen)
            // A saved job is the quickest way back in, so it goes first when there is one (and the app is not in the user's hands).
            if (!inFront && hadJob) onBackgroundScreen(req, ctx, log)?.let { return it.also { done(ctx) } }
            viaSession(chosen, query, kind, stale, log)?.let { return it }
            if (inFront) inPlace(chosen, query, kind, stale, log)?.let { return it }
            // With a saved job the screen was already tried (and learned again if it broke) above.
            else if (!hadJob) onBackgroundScreen(req, ctx, log)?.let { return it.also { done(ctx) } }
        }

        // A file on the phone.
        if (!(chosen != null && !localOnly && inFront)) when (val r = deps.local(query, kind)) {
            LocalResult.Denied -> log += "the phone's music files could not be read (Agent needs storage access: allow it in the phone's app settings for Agent)"
            is LocalResult.Found -> {
                val t = LocalRank.best(r.tracks, query)
                if (t == null) log += "no file on the phone matches \"$query\""
                else {
                    val viewer = chosen?.takeIf { it.audioFiles } ?: MediaResolve.pick(installed.filter { it.audioFiles }, installed, null, deps.stored(false), deps.systemDefault(false), active).app
                    if (viewer == null) log += "found \"${t.title}\" on the phone but no app can open music files"
                    else {
                        val f = if (deps.userFront() == viewer.pkg) Fallback.Ran(deps.launch(MediaIntents.viewAudio(t.uri, viewer.pkg))) else deps.screenLink(viewer, t.uri, ctx)
                        when {
                            f is Fallback.Ran && f.text.startsWith("failed") -> log += "found \"${t.title}\" on the phone but could not open it in ${viewer.label} (${f.text.removePrefix("failed: ").take(120)})"
                            f is Fallback.Ran -> when (val o = MediaVerify.await(deps::sessions, viewer.pkg, "${t.title} ${t.artist}", retryMs, sleep = { deps.sleep(it) })) {
                                is MediaVerify.Outcome.Playing -> return playingLine(o, " (a file on the phone)").also { done(ctx) }
                                is MediaVerify.Outcome.NoAccess -> return "Opened \"${t.title}\" from the phone's files in ${viewer.label}. I cannot confirm it is playing: ${o.why} $NO_ACCESS_HELP"
                                else -> log += "opened the file \"${t.title}\" in ${viewer.label} but it did not start"
                            }
                            f is Fallback.Off -> log += offNote(viewer.label)
                            f is Fallback.InFront -> log += f.text.take(160)
                            f is Fallback.Failed -> log += "found \"${t.title}\" on the phone but could not open it on the background screen (${f.text.take(120)})"
                            else -> {}
                        }
                    }
                }
            }
        }
        return failed(query, label, log)
    }

    private suspend fun video(query: String, appArg: String?, ctx: ToolContext): String {
        val installed = deps.installed()
        val pool = installed.filter { it.videoLinks }
        val first = deps.sessions()
        val active = (first as? Sessions.Ok)?.list.orEmpty().sortedByDescending { MediaVerify.playing(it) }.map { it.pkg }
        val pick = MediaResolve.pick(pool, installed, appArg, deps.stored(true), deps.systemDefault(true), active)
        pick.error?.let { return "failed: $it" }
        val chosen = pick.app ?: return "failed: no installed app opens video links. A video is never opened with the phone's chooser or browser, because that would take over the user's screen. Install a video app, or name one with app."
        val label = chosen.label
        val pkg = chosen.pkg
        val log = mutableListOf<String>()
        val inFront = deps.userFront() == pkg
        val hit = deps.youtube(query)?.let { YouTubeResults.best(it) }
        if (hit != null) {
            val title = hit.title.ifBlank { query }
            val f = if (inFront) Fallback.Ran(deps.launch(MediaIntents.watch(hit.id, pkg))) else deps.screenLink(chosen, MediaIntents.watchUrl(hit.id), ctx)
            when {
                f is Fallback.Ran && f.text.startsWith("failed") -> log += "could not open the video in $label (${f.text.removePrefix("failed: ").take(120)})"
                f is Fallback.Ran -> {
                    val out = when (val o = MediaVerify.await(deps::sessions, pkg, title, waitMs, sleep = { deps.sleep(it) })) {
                        is MediaVerify.Outcome.Playing -> "Playing ${MediaVerify.what(o.s)}." + (if (o.buffering) " It is still loading." else "") +
                            (if (inFront) " ($label is in front on the user's screen, so it was not moved.)" else " (on the background screen: the user's screen was not touched.)")
                        is MediaVerify.Outcome.NotPlaying -> "Opened \"$title\" in $label, but its media session says ${MediaVerify.state(o.s)}. Try media_control play."
                        MediaVerify.Outcome.NoSession -> "Opened \"$title\" (${hit.channel}) in $label. No media session appeared in ${waitMs / 1000} s, so I cannot confirm it is playing: video apps often publish one only once the video has started."
                        is MediaVerify.Outcome.NoAccess -> "Opened \"$title\" (${hit.channel}) in $label. I cannot confirm it is playing: ${o.why} $NO_ACCESS_HELP"
                    }
                    if (!inFront) done(ctx)
                    return out
                }
                f is Fallback.Off -> log += offNote(label)
                f is Fallback.InFront -> log += f.text.take(160)
                f is Fallback.Failed -> log += "could not open the video on the background screen (${f.text.take(120)})"
                else -> {}
            }
        } else log += "could not look the video up online from here (no connection, or the results page changed)"
        // No link to open: use the app's own search on the background screen, the same way as for music.
        if (!inFront) onBackgroundScreen(ScreenRequest(chosen, query, MediaKind.VIDEO), ctx, log)?.let { return it.also { done(ctx) } }
        return failed(query, label, log)
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
    const val PREFER = "To play music or a video use play_media, the only way to play (never android_intent, open_url or opening the app); to pause, skip or see what is playing use media_control."
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

    private val learnedStore get() = App.graph(context).runtime.learned

    private fun playJob(pkg: String): Pair<com.past9.phoneaos.connections.LearnedApp, com.past9.phoneaos.connections.LearnedTool>? {
        val app = learnedStore.apps.value.firstOrNull { it.isScreen && it.pkg == pkg } ?: return null
        val tool = app.tools.firstOrNull { it.name == JOB } ?: app.tools.firstOrNull { it.name == LEGACY_JOB } ?: return null
        return app to tool
    }

    override fun hasJob(app: MediaApp) = playJob(app.pkg) != null

    override suspend fun userFront(): String? = if (!com.past9.phoneaos.screen.Screens.on(context)) null else com.past9.phoneaos.screen.Screens.session(context).userFront()

    override suspend fun screenReplay(req: ScreenRequest, ctx: ToolContext): Fallback {
        if (!com.past9.phoneaos.screen.Screens.on(context)) return Fallback.Off
        val (app, tool) = playJob(req.app.pkg) ?: return Fallback.NoJob
        val out = com.past9.phoneaos.screen.ScreenLearned.run(context, app, tool, JSONObject().put("query", req.query), ctx)
        return when {
            out.startsWith("Done the learned way") -> Fallback.Ran(out)
            out.contains("user's own screen") -> Fallback.InFront(out)
            else -> Fallback.Failed(out)
        }
    }

    override suspend fun screenLearn(req: ScreenRequest, ctx: ToolContext): Learned {
        if (!com.past9.phoneaos.screen.Screens.on(context)) return Learned.Off
        val session = com.past9.phoneaos.screen.Screens.session(context)
        val ops = object : ScreenOps {
            // A cold start: where an earlier try left the app is not where a search begins.
            override suspend fun open(): String? {
                runCatching { session.close() }
                val r = session.open(ctx, req.app.pkg, owner = "play_media", fresh = true)
                return if (r.contains("is open on the background screen")) null else r
            }
            override fun elements() = session.screenElements()
            override fun width() = session.state.value.width
            override suspend fun tap(e: com.past9.phoneaos.screen.ScreenElement, generic: Boolean): String? {
                val r = session.tap(ctx, e.id, "", null, null, false, generic)
                return if (r.startsWith("Pressed")) null else r.lineSequence().first().take(200)
            }
            override suspend fun type(e: com.past9.phoneaos.screen.ScreenElement, text: String): String? {
                val r = session.type(ctx, text, e.id, "", append = false, submit = true)
                return if (r.startsWith("Typed") && !r.contains("Not submitted")) null else r.lineSequence().first().take(200)
            }
            override suspend fun settle() { runCatching { session.waitFor(ctx, "", false, 6, stable = 1) } }
            override suspend fun back(): String? { val r = session.key(ctx, "back"); return if (r.startsWith("Pressed")) null else r.lineSequence().first().take(200) }
            override suspend fun swipeDown(): String? { val r = session.swipe(ctx, "down", null, null, null, null, null); return if (r.startsWith("Swiped")) null else r.lineSequence().first().take(200) }
            override suspend fun openFresh(): String? {
                val r = session.open(ctx, req.app.pkg, owner = "play_media", fresh = true)
                return if (r.contains("is open on the background screen")) null else r
            }
        }
        return MediaScreenLearner(ops, ::sessions, sleep = { delay(it) }).run(req.app, req.query)
    }

    override suspend fun screenSave(req: ScreenRequest, ctx: ToolContext): String? {
        val g = App.graph(context)
        val r = com.past9.phoneaos.screen.ScreenLearned.save(com.past9.phoneaos.screen.Screens.session(context), g.runtime.learned, com.past9.phoneaos.screen.InstalledApps(context),
            com.past9.phoneaos.screen.Screens.config(context).blocklist, ctx, JOB,
            "Plays something in ${req.app.label}: searches for the query and presses the best matching result. Playback only: it never sends, pays or deletes. Found out by the agent on the background screen.",
            mapOf("query" to req.query), readOnly = true, by = "auto", autoApprove = true)
        return if (r.startsWith("Saved")) req.query else null
    }

    override suspend fun screenLink(app: MediaApp, link: String, ctx: ToolContext): Fallback {
        if (!com.past9.phoneaos.screen.Screens.on(context)) return Fallback.Off
        val r = com.past9.phoneaos.screen.Screens.session(context).open(ctx, app.pkg, link = link, owner = "play_media")
        return when {
            r.contains("is open on the background screen") -> Fallback.Ran(r)
            r.contains("user's own screen") -> Fallback.InFront(r)
            else -> Fallback.Failed(r)
        }
    }

    override suspend fun screenDone(ctx: ToolContext) { runCatching { com.past9.phoneaos.screen.Screens.session(context).release() } }

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

    companion object {
        /** The name of the play job saved for each app (tool app_<app>_play). */
        const val JOB = "play"
        /** What the first version told the agent to save it as; still run when it is there. */
        const val LEGACY_JOB = "play_media"
    }
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
