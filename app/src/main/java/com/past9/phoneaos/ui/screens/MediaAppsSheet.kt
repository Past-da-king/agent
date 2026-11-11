package com.past9.phoneaos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.past9.phoneaos.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.media.MediaApp
import com.past9.phoneaos.ui.AppCard

/** One installed app that handles media, and what it can do. */
data class MediaAppRow(
    val pkg: String,
    val label: String,
    val playFromSearch: Boolean = false,
    val videoLinks: Boolean = false,
    val audioFiles: Boolean = false,
    /** It has a media session open right now. */
    val sessionNow: Boolean = false,
) {
    /** What it can do, in words. Without a Context (tests) it stays English. */
    fun abilities(ctx: android.content.Context? = null): List<String> = listOfNotNull(
        ctx.str(R.string.media_plays_search, "Plays from search").takeIf { playFromSearch }, ctx.str(R.string.media_video_links, "Video links").takeIf { videoLinks },
        ctx.str(R.string.media_local_files, "Local files").takeIf { audioFiles }, ctx.str(R.string.media_playing_now, "Playing now").takeIf { sessionNow },
    )
    val music: Boolean get() = playFromSearch || audioFiles
    val video: Boolean get() = videoLinks

    companion object {
        fun of(a: MediaApp, sessionNow: Boolean) = MediaAppRow(a.pkg, a.label, a.playFromSearch, a.videoLinks, a.audioFiles, sessionNow)
    }
}

/** Settings > Media apps: what the sheet reads and writes. An empty pick means automatic. */
data class MediaAppsActions(
    val rows: () -> List<MediaAppRow> = { emptyList() },
    val music: () -> String = { "" },
    val setMusic: (String) -> Unit = {},
    val video: () -> String = { "" },
    val setVideo: (String) -> Unit = {},
    /** False when Android has not given Agent notification access: it is needed to see what is playing and to pause or skip. */
    val hasSessionAccess: () -> Boolean = { true },
    val openSessionAccess: () -> Unit = {},
)

private fun android.content.Context?.str(id: Int, en: String, vararg a: Any): String = this?.getString(id, *a) ?: en

/** The Settings line under Automation, in words. */
object MediaAppsUi {
    fun line(rows: List<MediaAppRow>, music: String, video: String, ctx: android.content.Context? = null): String {
        val m = rows.firstOrNull { it.pkg == music }?.label
        val v = rows.firstOrNull { it.pkg == video }?.label
        return when {
            m == null && v == null -> ctx.str(R.string.media_line_auto, "Which app plays your music and video · automatic")
            else -> listOfNotNull(m?.let { ctx.str(R.string.media_line_music, "Music: $it", it) }, v?.let { ctx.str(R.string.media_line_video, "Video: $it", it) }).joinToString(" · ")
        }
    }
}

/**
 * Settings > Media apps: every installed app that can play for the agent, what each handles, and the user's own default
 * for music and for video. A slide-up sheet in the app's own pieces, never a pop-up.
 */
@Composable
internal fun MediaAppsSheet(actions: MediaAppsActions, onDismiss: () -> Unit) {
    val rows = remember { actions.rows() }
    var music by remember { mutableStateOf(actions.music()) }
    var video by remember { mutableStateOf(actions.video()) }
    val access = remember { actions.hasSessionAccess() }
    com.past9.phoneaos.ui.AgentSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            com.past9.phoneaos.ui.SheetHead(Icons.Rounded.LibraryMusic, stringResource(R.string.media_title), stringResource(R.string.media_play),
                stringResource(R.string.media_intro))
            Spacer(Modifier.height(20.dp))
            if (!access) {
                AppCard(padding = PaddingValues(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.NotificationsOff, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.media_access_off), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.media_access_body),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = actions.openSessionAccess) { Text(stringResource(R.string.conn_allow), style = MaterialTheme.typography.labelLarge) }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            if (rows.isEmpty()) {
                AppCard(padding = PaddingValues(vertical = 4.dp)) {
                    Text(stringResource(R.string.media_none),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                }
            }
            rows.forEachIndexed { i, r ->
                if (i > 0) Spacer(Modifier.height(12.dp))
                AppCard(padding = PaddingValues(vertical = 4.dp)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("media-app-${r.pkg}")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (r.video && !r.music) Icons.Rounded.PlayCircle else Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                                Text(r.pkg, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(r.abilities(LocalContext.current).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = music == r.pkg, enabled = r.music, onClick = { music = if (music == r.pkg) "" else r.pkg; actions.setMusic(music) },
                                label = { Text(stringResource(R.string.media_music_app), style = MaterialTheme.typography.labelLarge) }, shape = RoundedCornerShape(50),
                                leadingIcon = { Icon(Icons.Rounded.MusicNote, null, Modifier.size(18.dp)) },
                                modifier = Modifier.height(40.dp).testTag("music-${r.pkg}"))
                            FilterChip(selected = video == r.pkg, enabled = r.video, onClick = { video = if (video == r.pkg) "" else r.pkg; actions.setVideo(video) },
                                label = { Text(stringResource(R.string.media_video_app), style = MaterialTheme.typography.labelLarge) }, shape = RoundedCornerShape(50),
                                leadingIcon = { Icon(Icons.Rounded.PlayCircle, null, Modifier.size(18.dp)) },
                                modifier = Modifier.height(40.dp).testTag("video-${r.pkg}"))
                        }
                    }
                }
            }
            Text(stringResource(R.string.media_tap_again), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
        }
    }
}
