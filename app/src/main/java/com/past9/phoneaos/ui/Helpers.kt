package com.past9.phoneaos.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.toShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.ui.screens.iconFor
import com.past9.phoneaos.ui.theme.Eyebrow
import com.past9.phoneaos.ui.theme.LocalExtra
import org.json.JSONObject

/** A helper as the UI sees it: the chat row plus the steps it logged. */
data class HelperView(val item: ChatItem, val steps: List<ChatItem>) {
    private val meta = JSONObject(item.meta)
    val id get() = item.id
    val label: String get() = meta.optString("label").ifBlank { "Helper" }
    /** working | done | failed | stopped */
    val state: String get() = meta.optString("state").ifBlank { "working" }
    val working get() = state == "working"
    /** The live step. A linked computer's helper rows call it summary. */
    val now: String get() = meta.optString("now").ifBlank { if (working) meta.optString("summary") else "" }
    val result: String get() = meta.optString("result")
    val model: String get() = meta.optString("model").substringAfterLast('/')
    val startedAt: Long get() = meta.optLong("startedAt", item.createdAt)
    val endedAt: Long get() = meta.optLong("endedAt", 0L)
    /** How many times the main agent sent it back to try harder. */
    val pushes: Int get() = meta.optInt("pushes")
    /** The profile it works in, and that profile's colour (an accent id). */
    val profile: String get() = meta.optString("profile")
    val profileColor: String get() = meta.optString("profileColor").ifBlank { "iris" }
    /** Set when this row is work the agent handed to a project (a linked computer's project chat): its name and icon. */
    // Rows from a linked computer carry project { id, name, glyph, color, image }; rows the phone builds use flat fields.
    private val projectObj: JSONObject? get() = meta.optJSONObject("project")
    val project: String get() = meta.optString("projectName").ifBlank { projectObj?.optString("name").orEmpty() }
    val projectGlyph: String get() = meta.optString("projectGlyph").ifBlank { projectObj?.optString("glyph").orEmpty() }
    val projectColor: String get() = meta.optString("projectColor").ifBlank { projectObj?.optString("color").orEmpty() }.ifBlank { "iris" }
    val projectImage: String get() = meta.optString("projectImage").ifBlank { projectObj?.optString("image").orEmpty() }

    companion object {
        /** Every helper in the chat, each with its own steps. */
        fun from(items: List<ChatItem>): List<HelperView> {
            val steps = items.filter { it.kind == "activity" && JSONObject(it.meta).has("helper") }.groupBy { JSONObject(it.meta).optLong("helper") }
            return items.filter { it.kind == "helper" }.map { HelperView(it, steps[it.id].orEmpty()) }
        }
    }
}

/** "under a minute", "4 min", "1 h 12 min". */
fun spanLabel(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(0)
    return when { m < 1 -> "under a minute"; m < 60 -> "$m min"; else -> "${m / 60} h${if (m % 60 > 0) " ${m % 60} min" else ""}" }
}

private fun plainFirstLine(md: String): String = md.lineSequence().map { it.trim().trimStart('-', '*', '#', '>', ' ').replace("**", "").replace("`", "") }
    .firstOrNull { it.isNotBlank() }.orEmpty()

private data class StateLook(val container: Color, val content: Color, val word: String)

@Composable
private fun lookOf(state: String): StateLook {
    val cs = MaterialTheme.colorScheme; val extra = LocalExtra.current
    return when (state) {
        "working" -> StateLook(cs.primaryContainer, cs.onPrimaryContainer, "Working")
        "done" -> StateLook(extra.successContainer, extra.success, "Done")
        "stopped" -> StateLook(cs.surfaceContainerHighest, cs.onSurfaceVariant, "Stopped")
        "interrupted" -> StateLook(cs.surfaceContainerHighest, cs.onSurfaceVariant, "Paused")
        else -> StateLook(cs.errorContainer, cs.onErrorContainer, "Didn't finish")
    }
}

/** The round badge that says at a glance whether a helper is working, done or stuck. */
@Composable
fun HelperOrb(state: String, size: Dp = 40.dp, profileColor: String? = null) {
    val look = lookOf(state)
    // A working helper shows in its profile's colour, so "that one's on my work stuff" reads at a glance.
    val tones = if (state == "working" && profileColor != null) com.past9.phoneaos.ui.screens.profileTones(profileColor) else look.container to look.content
    Box(Modifier.size(size).clip(CircleShape).background(tones.first), contentAlignment = Alignment.Center) {
        when (state) {
            "working" -> LoadingIndicator(Modifier.size(size * 0.8f), color = tones.second)
            "done" -> Icon(Icons.Rounded.Check, "Done", Modifier.size(size * 0.5f), tint = look.content)
            "stopped" -> Icon(Icons.Rounded.Stop, "Stopped", Modifier.size(size * 0.5f), tint = look.content)
            else -> Icon(Icons.Rounded.PriorityHigh, "Didn't finish", Modifier.size(size * 0.5f), tint = look.content)
        }
    }
}

/**
 * A project's icon, like an app icon: its own picture, else its emoji, else its first letter, on a rounded tile in
 * the project's colour. A working project gets a small spinner in the corner, as helpers do.
 */
@Composable
fun ProjectTile(h: HelperView, size: Dp = 40.dp) {
    val accent = com.past9.phoneaos.ui.theme.accentOf(h.projectColor)
    val bitmap = remember(h.projectImage) {
        runCatching {
            val b64 = h.projectImage.substringAfter("base64,", "")
            if (b64.isEmpty()) null else android.util.Base64.decode(b64, android.util.Base64.DEFAULT).let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap()
        }.getOrNull()
    }
    val emoji = h.projectGlyph.takeIf { g -> g.isNotEmpty() && g.none { it.isLetterOrDigit() || it == '-' } }
    val glyph = projectGlyphs[h.projectGlyph]
    Box(Modifier.size(size)) {
        Box(Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.RoundedCornerShape(size * 0.28f)).background(accent.lP), contentAlignment = Alignment.Center) {
            when {
                bitmap != null -> androidx.compose.foundation.Image(bitmap, h.project, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                emoji != null -> Text(emoji, style = MaterialTheme.typography.titleLarge)
                glyph != null -> Icon(glyph, h.project, Modifier.size(size * 0.55f), tint = Color.White)
                else -> Text(h.project.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
        }
        if (h.working) LoadingIndicator(Modifier.size(size * 0.45f).align(Alignment.BottomEnd).clip(CircleShape).background(MaterialTheme.colorScheme.surface), color = accent.lP)
    }
}

/** The desktop app's project icon names (its own icon set) drawn with the phone's matching icons. */
private val projectGlyphs: Map<String, androidx.compose.ui.graphics.vector.ImageVector> = mapOf(
    "folder" to Icons.Rounded.Folder, "building" to Icons.Rounded.Business, "code" to Icons.Rounded.Code, "term" to Icons.Rounded.Terminal,
    "globe" to Icons.Rounded.Public, "doc" to Icons.Rounded.Description, "mail" to Icons.Rounded.Mail, "cal" to Icons.Rounded.CalendarMonth,
    "chat" to Icons.Rounded.ChatBubble, "tasks" to Icons.Rounded.TaskAlt, "heart" to Icons.Rounded.Favorite, "bolt" to Icons.Rounded.Bolt,
    "shield" to Icons.Rounded.Shield, "key" to Icons.Rounded.Key, "palette" to Icons.Rounded.Palette, "monitor" to Icons.Rounded.Monitor,
    "phone" to Icons.Rounded.PhoneAndroid, "person" to Icons.Rounded.Person, "server" to Icons.Rounded.Dns, "shot" to Icons.Rounded.Screenshot,
)

/** One helper as a calm, tappable row: who it is, what it's doing (or what it found), how long. */
@Composable
fun HelperCard(h: HelperView, onClick: () -> Unit, modifier: Modifier = Modifier, now: Long = System.currentTimeMillis(),
               /** The small card a linked computer's helpers use at the foot of the chat (the desktop side panel's size). */
               compact: Boolean = false, iconSize: Dp = 40.dp) {
    val cs = MaterialTheme.colorScheme
    val look = lookOf(h.state)
    val pad = if (compact) 10.dp else 14.dp
    Surface(onClick = onClick, shape = if (compact) MaterialTheme.shapes.large else MaterialTheme.shapes.extraLarge, color = cs.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = pad + if (compact) 2.dp else 0.dp, end = 10.dp, top = pad, bottom = pad), verticalAlignment = Alignment.CenterVertically) {
            if (h.project.isNotBlank()) ProjectTile(h, iconSize) else HelperOrb(h.state, iconSize, profileColor = h.profileColor.takeIf { h.profile.isNotBlank() })
            Spacer(Modifier.width(if (compact) 12.dp else 14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(h.label, style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    Text(if (h.working) spanLabel(now - h.startedAt) else look.word, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1)
                }
                if (h.profile.isNotBlank()) ProfileTag(h.profile, h.profileColor, Modifier.padding(top = 2.dp, bottom = 2.dp))
                val line = when {
                    h.working -> (h.now.ifBlank { h.steps.lastOrNull()?.text ?: "Getting started" }) + "…"
                    h.result.isNotBlank() -> plainFirstLine(h.result)
                    else -> plainFirstLine(h.item.text)
                }
                Text(line, style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium, color = if (h.working) cs.primary else cs.onSurfaceVariant, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.ChevronRight, "Open ${h.label}", tint = cs.onSurfaceVariant)
        }
    }
}

/**
 * Everything about one helper, full height: the brief the main agent wrote, what it's doing right now,
 * every step it took, and what it brought back. A running helper can be stopped from here.
 */
@Composable
fun HelperSheet(h: HelperView, onStop: ((Long) -> Unit)?, onDismiss: () -> Unit, now: Long = System.currentTimeMillis()) {
    val cs = MaterialTheme.colorScheme
    val look = lookOf(h.state)
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp)
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 40.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (h.project.isNotBlank()) ProjectTile(h, 52.dp) else HelperOrb(h.state, 52.dp, profileColor = h.profileColor.takeIf { h.profile.isNotBlank() })
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (h.project.isNotBlank()) "PROJECT" else "HELPER", style = Eyebrow, color = cs.primary)
                    Text(h.label, style = MaterialTheme.typography.headlineSmall)
                    val took = if (h.working) "for ${spanLabel(now - h.startedAt)}" else if (h.endedAt > 0) "in ${spanLabel(h.endedAt - h.startedAt)}" else ""
                    Text(listOf(look.word + if (took.isNotBlank()) " $took" else "", h.model.takeIf { it.isNotBlank() },
                        h.pushes.takeIf { it > 0 }?.let { "sent back ${if (it == 1) "once" else "$it times"}" }).filterNotNull().joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    if (h.profile.isNotBlank()) ProfileTag(h.profile, h.profileColor, Modifier.padding(top = 6.dp))
                }
            }
            if (h.working && onStop != null) {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { onStop(h.id); onDismiss() }, modifier = Modifier.fillMaxWidth().height(48.dp), shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.error)) {
                    Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Stop this helper")
                }
            }

            if (h.working) {
                Section("RIGHT NOW", null)
                val (nowBg, nowFg) = if (h.profile.isNotBlank()) com.past9.phoneaos.ui.screens.profileTones(h.profileColor) else cs.primaryContainer to cs.onPrimaryContainer
                Surface(shape = MaterialTheme.shapes.large, color = nowBg, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(28.dp), color = nowFg)
                        Spacer(Modifier.width(12.dp))
                        Text(h.now.ifBlank { h.steps.lastOrNull()?.text ?: "Getting started" }, style = MaterialTheme.typography.bodyLarge, color = nowFg)
                    }
                }
            }

            if (h.result.isNotBlank()) {
                Section(if (h.state == "done") "WHAT IT BROUGHT BACK" else "WHAT HAPPENED", null)
                Surface(shape = MaterialTheme.shapes.large, color = if (h.state == "done") cs.surfaceContainerHigh else look.container, modifier = Modifier.fillMaxWidth()) {
                    Markdown(h.result, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = if (h.state == "done") cs.onSurface else look.content)
                }
            }

            Section("THE BRIEF", "What your agent asked it to do")
            var open by remember { mutableStateOf(false) }
            val long = h.item.text.length > 320
            Surface(onClick = { open = !open }, enabled = long, shape = MaterialTheme.shapes.large, color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth().animateContentSize()) {
                Column(Modifier.padding(16.dp)) {
                    Text(h.item.text, style = MaterialTheme.typography.bodyMedium, maxLines = if (long && !open) 6 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                    if (long) Text(if (open) "Show less" else "Show the whole brief", style = MaterialTheme.typography.labelLarge, color = cs.primary, modifier = Modifier.padding(top = 8.dp))
                }
            }

            Section("STEPS", if (h.steps.isEmpty()) (if (h.working) "Nothing logged yet" else "It didn't log any steps") else "${h.steps.size} so far, oldest first")
            h.steps.forEachIndexed { i, a -> StepRow(a, last = i == h.steps.lastIndex, now = now) }
        }
    }
    // Screenshot tests can't capture a popup window, so they render the sheet in place.
    if (LocalSheetPreview.current) Surface(color = cs.surfaceContainerLow, shape = shape, modifier = Modifier.fillMaxSize().padding(top = 48.dp)) {
        Column { Box(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 22.dp).size(32.dp, 4.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)).background(cs.onSurfaceVariant.copy(alpha = 0.4f))); body() }
    } else ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = cs.surfaceContainerLow, shape = shape) { body() }
}

@Composable
private fun Section(eyebrow: String, line: String?) {
    Spacer(Modifier.height(24.dp))
    Text(eyebrow, style = Eyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (line != null) Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(10.dp))
}

/** A step on a thin timeline: icon, what it did, when. */
@Composable
private fun StepRow(a: ChatItem, last: Boolean, now: Long) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(28.dp)) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(iconFor(JSONObject(a.meta).optString("tool")), null, Modifier.size(15.dp), tint = cs.onSecondaryContainer)
            }
            if (!last) Box(Modifier.width(2.dp).weight(1f).background(cs.outlineVariant))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(top = 4.dp, bottom = if (last) 0.dp else 16.dp)) {
            Text(a.text, style = MaterialTheme.typography.bodyMedium)
            Text(relativeTime(a.createdAt, now), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
    }
}

/** Which profile a helper works in: its mark and name, in that profile's colour. */
@Composable
fun ProfileTag(name: String, color: String, modifier: Modifier = Modifier) {
    val (bg, fg) = com.past9.phoneaos.ui.screens.profileTones(color)
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(50), color = bg, modifier = modifier) {
        Row(Modifier.padding(start = 4.dp, end = 10.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(start = 4.dp).size(10.dp).clip(androidx.compose.material3.MaterialShapes.Cookie9Sided.toShape()).background(fg))
            Spacer(Modifier.width(6.dp))
            Text(name, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
