package com.past9.phoneaos.media

import android.app.SearchManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.provider.MediaStore
import com.past9.phoneaos.automation.IntentRules
import com.past9.phoneaos.automation.IntentSpec
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/*
 * Playing music and video through the apps already on the phone, whichever they are. Nothing here names an app:
 * the apps are found at run time from what they say they handle (a play-from-search action, YouTube watch links,
 * audio files), and the user can pick a default for music and for video in Settings > Media apps.
 *
 * This file is the rules: what to send, which app to pick, how to tell it worked. MediaTools.kt holds the tools and
 * the one class that touches Android.
 */

enum class MediaKind(val id: String) {
    SONG("song"), ARTIST("artist"), ALBUM("album"), PLAYLIST("playlist"), VIDEO("video"), PODCAST("podcast");

    val isVideo: Boolean get() = this == VIDEO

    companion object {
        fun parse(s: String?): MediaKind? = entries.firstOrNull { it.id == s?.trim()?.lowercase() }
    }
}

/** An installed app and what it says it handles. Any flag can be false: the app is still listed so it can be matched by name. */
data class MediaApp(
    val pkg: String,
    val label: String,
    val playFromSearch: Boolean = false,
    val videoLinks: Boolean = false,
    val audioFiles: Boolean = false,
) {
    val any: Boolean get() = playFromSearch || videoLinks || audioFiles
}

/** The intents that start playback. Each is an [IntentSpec] so the same sender and the same tests cover them. */
object MediaIntents {
    const val PLAY_FROM_SEARCH = "android.media.action.MEDIA_PLAY_FROM_SEARCH"
    /** The focus value for "no structure": the app decides what the words mean. */
    const val UNSTRUCTURED = "vnd.android.cursor.item/*"
    const val EXTRA_PLAYLIST = "android.intent.extra.playlist"
    const val WATCH = "https://www.youtube.com/watch?v="
    /** Any web page that is not YouTube: an app that opens this as well as a watch link is a browser, not a video app. */
    const val PLAIN_WEB = "https://example.org/"

    class Parts(val title: String?, val artist: String?, val album: String?)

    private val BY = Regex("^(.*\\S)\\s+by\\s+(\\S.*)$", RegexOption.IGNORE_CASE)

    /** "Underway by Someone" is a title and an artist; anything else is only what it was said to be. */
    fun parts(query: String, kind: MediaKind): Parts {
        val q = query.trim()
        val by = BY.find(q)
        return when (kind) {
            MediaKind.ARTIST -> Parts(null, q, null)
            MediaKind.ALBUM -> if (by != null) Parts(null, by.groupValues[2], by.groupValues[1]) else Parts(null, null, q)
            MediaKind.SONG -> if (by != null) Parts(by.groupValues[1], by.groupValues[2], null) else Parts(q, null, null)
            else -> Parts(q, null, null)
        }
    }

    fun focus(kind: MediaKind): String = when (kind) {
        MediaKind.SONG -> MediaStore.Audio.Media.ENTRY_CONTENT_TYPE
        MediaKind.ARTIST -> MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE
        MediaKind.ALBUM -> MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE
        MediaKind.PLAYLIST -> MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE
        else -> UNSTRUCTURED
    }

    /** The extras of a play-from-search request: the words, the focus and whatever the words could be split into. */
    fun extras(query: String, kind: MediaKind): LinkedHashMap<String, Any> {
        val p = parts(query, kind)
        val m = LinkedHashMap<String, Any>()
        m[SearchManager.QUERY] = query.trim()
        m[MediaStore.EXTRA_MEDIA_FOCUS] = focus(kind)
        p.artist?.let { m[MediaStore.EXTRA_MEDIA_ARTIST] = it }
        p.album?.let { m[MediaStore.EXTRA_MEDIA_ALBUM] = it }
        if (kind == MediaKind.PLAYLIST) m[EXTRA_PLAYLIST] = query.trim() else p.title?.let { m[MediaStore.EXTRA_MEDIA_TITLE] = it }
        return m
    }

    fun playFromSearch(query: String, kind: MediaKind, pkg: String): IntentSpec =
        IntentSpec(IntentRules.ACTIVITY, PLAY_FROM_SEARCH, pkg = pkg, extras = extras(query, kind), flags = listOf("new_task"))

    /** The same extras as a Bundle, for a media session's own play-from-search. */
    fun bundle(query: String, kind: MediaKind): Bundle = Bundle().also { b ->
        extras(query, kind).forEach { (k, v) -> if (k != SearchManager.QUERY) b.putString(k, v.toString()) }
    }

    fun watchUrl(id: String) = WATCH + id

    fun watch(videoId: String, pkg: String): IntentSpec =
        IntentSpec(IntentRules.ACTIVITY, android.content.Intent.ACTION_VIEW, pkg = pkg, data = watchUrl(videoId), flags = listOf("new_task"))

    fun results(query: String, pkg: String): IntentSpec =
        IntentSpec(IntentRules.ACTIVITY, android.content.Intent.ACTION_VIEW, pkg = pkg, data = YouTubeResults.url(query), flags = listOf("new_task"))

    /** A file on the phone, opened in a music app. The read grant is what lets that app open a content address. */
    fun viewAudio(uri: String, pkg: String): IntentSpec =
        IntentSpec(IntentRules.ACTIVITY, android.content.Intent.ACTION_VIEW, pkg = pkg, data = uri, mimeType = "audio/*", flags = listOf("new_task", "grant_read"))
}

/** Which app gets the job. */
object MediaResolve {
    class Pick(val app: MediaApp?, val why: String, val error: String? = null)

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** By name or package: exact first, then a start, then a part, so "metrolist" finds "Metrolist Nightly". */
    fun match(apps: List<MediaApp>, ref: String): MediaApp? {
        val r = norm(ref)
        if (r.isEmpty()) return null
        return apps.firstOrNull { norm(it.label) == r || it.pkg.equals(ref.trim(), true) }
            ?: apps.firstOrNull { norm(it.label).startsWith(r) }
            ?: apps.firstOrNull { norm(it.label).contains(r) || it.pkg.lowercase().contains(ref.trim().lowercase()) }
    }

    /**
     * The app that was asked for, else the user's default for this, else the phone's default handler, else an app that
     * already has a media session, else the first by name. [pool] is every app that can do it; [installed] is everything,
     * so an app that was named but does not advertise the ability is still found.
     */
    fun pick(pool: List<MediaApp>, installed: List<MediaApp>, arg: String?, stored: String?, systemDefault: String?, active: List<String>): Pick {
        if (!arg.isNullOrBlank()) {
            match(pool, arg)?.let { return Pick(it, "asked for") }
            match(installed, arg)?.let { return Pick(it, "asked for, though it does not say it takes play requests") }
            return Pick(null, "", "No installed app matches \"${arg.trim()}\".${if (pool.isNotEmpty()) " Apps that can play: ${pool.joinToString(", ") { it.label }}." else ""}")
        }
        stored?.takeIf { it.isNotBlank() }?.let { s -> pool.firstOrNull { it.pkg == s }?.let { return Pick(it, "your default in Settings") } }
        systemDefault?.let { s -> pool.firstOrNull { it.pkg == s }?.let { return Pick(it, "this phone's default for it") } }
        active.forEach { p -> pool.firstOrNull { it.pkg == p }?.let { return Pick(it, "it already has a media session") } }
        pool.minByOrNull { it.label.lowercase() }?.let { return Pick(it, "the first app that handles it") }
        return Pick(null, "")
    }
}

/** One media session on the phone, as the tools see it. Tests use fakes; [AndroidMediaSessions] wraps MediaController. */
interface MediaSessionView {
    val pkg: String
    val label: String
    val state: Int
    val title: String
    val artist: String
    val album: String
    val position: Long
    val duration: Long
    fun play()
    fun pause()
    fun next()
    fun previous()
    fun seekTo(ms: Long)
    fun playFromSearch(query: String, extras: Bundle)
}

sealed interface Sessions {
    class Ok(val list: List<MediaSessionView>) : Sessions
    class NoAccess(val why: String) : Sessions
}

object MediaVerify {
    fun playing(s: MediaSessionView) = s.state == PlaybackState.STATE_PLAYING
    fun starting(s: MediaSessionView) = s.state == PlaybackState.STATE_BUFFERING || s.state == PlaybackState.STATE_CONNECTING

    private val NOISE = setOf("by", "the", "a", "an", "of", "feat", "ft", "and", "play", "song", "music")
    fun words(t: String): List<String> = t.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() && it !in NOISE }

    /** At least 60% of the words asked for are in the title, artist or album that is playing. */
    fun matches(query: String, s: MediaSessionView): Boolean {
        val q = words(query)
        if (q.isEmpty()) return true
        val hay = words("${s.title} ${s.artist} ${s.album}").toSet()
        return q.count { it in hay } * 10 >= q.size * 6
    }

    fun state(s: MediaSessionView): String = when (s.state) {
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> "starting"
        PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_ERROR -> "error"
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS, PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "skipping"
        else -> "idle"
    }

    fun what(s: MediaSessionView): String {
        val t = s.title.ifBlank { "an unnamed item" }
        return "\"$t\"" + (if (s.artist.isNotBlank()) " by ${s.artist}" else "") + " in ${s.label}"
    }

    sealed interface Outcome {
        /** [buffering]: it was still loading when the wait ended. [matches]: what is playing looks like what was asked for. */
        class Playing(val s: MediaSessionView, val matches: Boolean, val buffering: Boolean = false) : Outcome
        /** The app has a media session but it is not playing. */
        class NotPlaying(val s: MediaSessionView) : Outcome
        data object NoSession : Outcome
        class NoAccess(val why: String) : Outcome
    }

    /**
     * Watches the app's media session until it plays. [staleTitle] is what the app was playing before the request: if
     * that is still what plays and it is not what was asked for, the wait goes on (the app may be about to switch).
     */
    suspend fun await(read: () -> Sessions, pkg: String, query: String, timeoutMs: Long, staleTitle: String? = null,
                      pollMs: Long = 500, sleep: suspend (Long) -> Unit = { delay(it) }): Outcome {
        var waited = 0L
        var last: MediaSessionView? = null
        while (true) {
            val r = read()
            if (r is Sessions.NoAccess) return Outcome.NoAccess(r.why)
            val mine = (r as Sessions.Ok).list.filter { it.pkg == pkg }
            val hit = mine.firstOrNull { playing(it) } ?: mine.firstOrNull { starting(it) } ?: mine.firstOrNull()
            if (hit != null) last = hit
            if (hit != null && playing(hit)) {
                val ok = matches(query, hit)
                val stale = !ok && staleTitle != null && hit.title == staleTitle
                if (!stale) return Outcome.Playing(hit, ok)
            }
            if (waited >= timeoutMs) break
            sleep(pollMs); waited += pollMs
        }
        val s = last ?: return Outcome.NoSession
        return if (playing(s)) Outcome.Playing(s, matches(query, s)) else if (starting(s)) Outcome.Playing(s, matches(query, s), buffering = true) else Outcome.NotPlaying(s)
    }

    /** The session to act on: the one playing (or starting), else the most recent, or the named app's. */
    fun choose(list: List<MediaSessionView>, app: String? = null): MediaSessionView? {
        val pool = if (app.isNullOrBlank()) list else {
            val m = MediaResolve.match(list.map { MediaApp(it.pkg, it.label) }, app) ?: return null
            list.filter { it.pkg == m.pkg }
        }
        return pool.firstOrNull { playing(it) } ?: pool.firstOrNull { starting(it) } ?: pool.firstOrNull()
    }

    fun clock(ms: Long): String {
        if (ms < 0) return "?"
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** One line for the agent: what the session is, in what state, and where it is in it. */
    fun line(s: MediaSessionView): String = buildString {
        append(state(s).replaceFirstChar { it.uppercase() }).append(": ").append(s.title.ifBlank { "(no title)" })
        if (s.artist.isNotBlank()) append(" by ").append(s.artist)
        if (s.album.isNotBlank()) append(" (").append(s.album).append(")")
        append(" in ").append(s.label).append(" [").append(s.pkg).append("]")
        if (s.duration > 0) append(", ").append(clock(s.position)).append(" of ").append(clock(s.duration))
    }
}

/** YouTube's own result page, read without any key: the first video that is really a video. */
object YouTubeResults {
    class Hit(val id: String, val title: String, val channel: String, val length: String)

    fun url(query: String) = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query.trim(), "UTF-8")

    private val ID = Regex("^[A-Za-z0-9_-]{11}$")

    /** The JSON object after "ytInitialData =", found by counting braces so a brace inside a title cannot end it early. */
    fun initialData(html: String): String? {
        val key = html.indexOf("ytInitialData")
        if (key < 0) return null
        val start = html.indexOf('{', key)
        if (start < 0) return null
        var depth = 0; var inStr = false; var esc = false
        for (i in start until html.length) {
            val c = html[i]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) { '"' -> inStr = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) return html.substring(start, i + 1) } }
        }
        return null
    }

    private fun text(o: JSONObject?): String = o?.optString("simpleText")?.takeIf { it.isNotBlank() }
        ?: o?.optJSONArray("runs")?.let { a -> (0 until a.length()).joinToString("") { a.optJSONObject(it)?.optString("text").orEmpty() } }.orEmpty()

    private fun walk(n: Any?, out: MutableList<Hit>) {
        when (n) {
            is JSONObject -> {
                n.optJSONObject("videoRenderer")?.let { v ->
                    val id = v.optString("videoId")
                    if (ID.matches(id)) out += Hit(id, text(v.optJSONObject("title")), text(v.optJSONObject("ownerText") ?: v.optJSONObject("longBylineText")), text(v.optJSONObject("lengthText")))
                }
                n.keys().forEach { k -> if (k != "videoRenderer") walk(n.opt(k), out) }
            }
            is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i), out)
        }
    }

    fun parse(html: String): List<Hit> {
        val hits = mutableListOf<Hit>()
        runCatching { initialData(html)?.let { walk(JSONObject(it), hits) } }
        if (hits.isNotEmpty()) return hits.distinctBy { it.id }
        // The page's shape changed: take the first video ids in the order they appear.
        return Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"").findAll(html).map { it.groupValues[1] }.distinct().map { Hit(it, "", "", "") }.toList()
    }

    /** The first result that has a running time (a live stream or a promoted card has none), else the first. */
    fun best(hits: List<Hit>): Hit? = hits.firstOrNull { it.length.isNotBlank() } ?: hits.firstOrNull()
}

/** A music file on the phone. */
class LocalTrack(val uri: String, val title: String, val artist: String, val album: String)

sealed interface LocalResult {
    class Found(val tracks: List<LocalTrack>) : LocalResult
    data object Denied : LocalResult
}

object LocalRank {
    /** The track that best fits the words, or null when none shares enough of them. */
    fun best(tracks: List<LocalTrack>, query: String): LocalTrack? {
        val q = MediaVerify.words(query)
        if (q.isEmpty()) return tracks.firstOrNull()
        return tracks.map { t ->
            val hay = MediaVerify.words("${t.title} ${t.artist} ${t.album}").toSet()
            t to q.count { it in hay }
        }.filter { it.second * 10 >= q.size * 6 }.maxByOrNull { it.second }?.first
    }
}
