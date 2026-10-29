package com.past9.phoneaos.ui

import androidx.compose.material.icons.rounded.*

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import com.past9.phoneaos.ui.theme.Eyebrow

/**
 * One look for every sheet that slides up: a morphing shape carrying the icon (the one expressive
 * moment), an eyebrow + headline that say what this is, roomy fields, and the main action pinned to
 * the bottom where the thumb is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(
    icon: ImageVector,
    eyebrow: String,
    title: String,
    onDismiss: () -> Unit,
    primary: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    shape: androidx.graphics.shapes.RoundedPolygon = MaterialShapes.Cookie9Sided,
    secondary: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AgentSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                SheetHead(icon, eyebrow, title, shape = shape)
                Spacer(Modifier.height(24.dp))
                content()
                Spacer(Modifier.height(16.dp))
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 20.dp).navigationBarsPadding()) {
                Button(onClick = onPrimary, enabled = primaryEnabled, modifier = Modifier.fillMaxWidth().height(64.dp), shapes = ButtonDefaults.shapes()) {
                    Text(primary, style = MaterialTheme.typography.titleMedium)
                }
                secondary?.invoke()
            }
        }
    }
}

val LocalSheetPreview = staticCompositionLocalOf { false }

/** The top corners every sheet has (tokens: --r-xl). */
val SheetShape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(topStart = com.past9.phoneaos.ui.theme.Radius.xl, topEnd = com.past9.phoneaos.ui.theme.Radius.xl)

/** The small bar at the top of a sheet. */
@Composable
fun SheetHandle(modifier: Modifier = Modifier) {
    Box(modifier.padding(vertical = 22.dp).size(32.dp, 4.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)))
}

/**
 * THE slide-up surface (29 Oct 2026, Ayanda: every centred pop-up on the phone becomes an on-brand slide-up sheet).
 * Every sheet, choice and confirmation in the app goes through here: the app's own surface, corners, handle and
 * scrim, light and dark. DesignTokensTest fails the build on any other ModalBottomSheet, AlertDialog or Dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSheet(onDismiss: () -> Unit, shape: androidx.compose.ui.graphics.Shape = SheetShape, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    // Screenshot tests can't capture a popup window, so they render the sheet in place.
    if (LocalSheetPreview.current) Surface(color = cs.surfaceContainerLow, contentColor = cs.onSurface, shape = shape, modifier = Modifier.fillMaxSize().padding(top = 48.dp)) {
        Column { SheetHandle(Modifier.align(Alignment.CenterHorizontally)); content() }
    } else ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cs.surfaceContainerLow, contentColor = cs.onSurface, scrimColor = cs.scrim.copy(alpha = 0.32f), shape = shape,
        dragHandle = { SheetHandle() }, content = content)
}

/** A sheet's head: the morphing icon tile (the one expressive moment), an eyebrow and the headline. */
@Composable
fun SheetHead(icon: ImageVector, eyebrow: String, title: String, line: String? = null, shape: androidx.graphics.shapes.RoundedPolygon = MaterialShapes.Cookie9Sided) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Settles from a soft square into the sheet's shape as it opens.
        var opened by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { opened = true }
        val p by animateFloatAsState(if (opened) 1f else 0f, MaterialTheme.motionScheme.slowSpatialSpec(), label = "sheet-shape")
        val morph = remember(shape) { Morph(MaterialShapes.Square.unit(), shape.unit()) }
        Box(Modifier.size(56.dp).clip(MorphShape(morph, p)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(eyebrow.uppercase(), style = Eyebrow, color = cs.primary)
            Text(title, style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
        }
    }
    if (line != null) Text(line, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
}

/** One option in a [ChoiceSheet]. [mark] is a small coloured tile (a profile's or project's colour). */
data class SheetChoice(val id: String, val title: String, val line: String = "", val icon: ImageVector? = null, val mark: androidx.compose.ui.graphics.Color? = null, val selected: Boolean = false)

/**
 * Pick one, as a slide-up sheet of big on-brand rows (never link-text buttons in a centred box): the profile an app is
 * for, a model, which computer. A tap picks and closes.
 */
@Composable
fun ChoiceSheet(icon: ImageVector, eyebrow: String, title: String, choices: List<SheetChoice>, onPick: (SheetChoice) -> Unit, onDismiss: () -> Unit, line: String? = null) {
    AgentSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            SheetHead(icon, eyebrow, title, line)
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { choices.forEach { c -> ChoiceRow(c) { onPick(c) } } }
        }
    }
}

/** A tappable option row: the app's tonal surface, a mark or icon, a title and a line, a check when chosen. */
@Composable
fun ChoiceRow(c: SheetChoice, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = if (c.selected) cs.primaryContainer else cs.surfaceContainerHigh, contentColor = if (c.selected) cs.onPrimaryContainer else cs.onSurface,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                c.mark != null -> Box(Modifier.size(36.dp).clip(MaterialTheme.shapes.small).background(c.mark), contentAlignment = Alignment.Center) {
                    if (c.icon != null) Icon(c.icon, null, Modifier.size(20.dp), tint = cs.surface) else Text(c.title.take(1).uppercase(), style = MaterialTheme.typography.titleSmall, color = cs.surface)
                }
                c.icon != null -> Box(Modifier.size(36.dp).clip(MaterialTheme.shapes.small).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
                    Icon(c.icon, null, Modifier.size(20.dp), tint = cs.onSecondaryContainer)
                }
            }
            if (c.mark != null || c.icon != null) Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(c.title, style = MaterialTheme.typography.titleMedium)
                if (c.line.isNotBlank()) Text(c.line, style = MaterialTheme.typography.bodySmall, color = if (c.selected) cs.onPrimaryContainer.copy(alpha = 0.8f) else cs.onSurfaceVariant)
            }
            if (c.selected) Icon(androidx.compose.material.icons.Icons.Rounded.Check, "Chosen", tint = cs.onPrimaryContainer)
            else Icon(androidx.compose.material.icons.Icons.Rounded.ChevronRight, null, tint = cs.onSurfaceVariant)
        }
    }
}

/**
 * Yes or no, as a slide-up sheet: what happens in one line, the main action big at the thumb, and a way out.
 * [destructive] paints the action in the error colour.
 */
@Composable
fun ConfirmSheet(icon: ImageVector, eyebrow: String, title: String, line: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, cancel: String = "Not now", destructive: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    AgentSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 20.dp).navigationBarsPadding()) {
            SheetHead(icon, eyebrow, title, line)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth().height(64.dp), shapes = ButtonDefaults.shapes(),
                colors = if (destructive) ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError) else ButtonDefaults.buttonColors()) {
                Text(confirm, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(56.dp), shapes = ButtonDefaults.shapes()) { Text(cancel, style = MaterialTheme.typography.titleSmall) }
        }
    }
}

/**
 * The app's text box for forms and search (the Material outlined field in the app's tokens: its tonal fill, its
 * corners, the accent only on focus). DesignTokensTest fails the build on a stock OutlinedTextField or TextField.
 */
@Composable
fun AppTextField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null, placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null, isError: Boolean = false,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
    singleLine: Boolean = false, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE, minLines: Int = 1,
    shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.medium,
) {
    val cs = MaterialTheme.colorScheme
    val fill = cs.surfaceContainerHighest.copy(alpha = 0.55f)
    OutlinedTextField(value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled, readOnly = readOnly, textStyle = textStyle,
        label = label, placeholder = placeholder, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
        supportingText = supportingText, isError = isError, visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        singleLine = singleLine, maxLines = maxLines, minLines = minLines, shape = shape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = cs.primary, unfocusedBorderColor = cs.surfaceContainerHighest, disabledBorderColor = cs.surfaceContainerHigh,
            focusedContainerColor = fill, unfocusedContainerColor = fill, disabledContainerColor = cs.surfaceContainerHigh,
            focusedLabelColor = cs.primary, unfocusedLabelColor = cs.onSurfaceVariant, cursorColor = cs.primary,
            focusedLeadingIconColor = cs.primary, unfocusedLeadingIconColor = cs.onSurfaceVariant,
            focusedPlaceholderColor = cs.onSurfaceVariant.copy(alpha = 0.6f), unfocusedPlaceholderColor = cs.onSurfaceVariant.copy(alpha = 0.6f)))
}

/**
 * A short line the app says (replaces Android's grey Toast): the app's inverse surface, its type and corners, at the
 * bottom, gone after a few seconds. [AppNoticeHost] draws it once at the root.
 */
object AppNotice {
    val text = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, Long>?>(null)
    fun say(line: String) { text.value = line to System.nanoTime() }
}

@Composable
fun AppNoticeHost(modifier: Modifier = Modifier) {
    val cur by AppNotice.text.collectAsState()
    LaunchedEffect(cur?.second) { if (cur != null) { kotlinx.coroutines.delay(4_000); AppNotice.text.value = null } }
    androidx.compose.animation.AnimatedVisibility(cur != null, modifier, enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut()) {
        val cs = MaterialTheme.colorScheme
        Surface(onClick = { AppNotice.text.value = null }, shape = MaterialTheme.shapes.medium, color = cs.inverseSurface, contentColor = cs.inverseOnSurface, shadowElevation = 6.dp,
            modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 24.dp).fillMaxWidth()) {
            Text(cur?.first.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
        }
    }
}

/** A field that looks like a page you write on, not a form box. */
@Composable
fun SheetField(
    value: String, onChange: (String) -> Unit, label: String, placeholder: String,
    big: Boolean = false, minLines: Int = 1, singleLine: Boolean = false, modifier: Modifier = Modifier,
    /** Addresses, usernames, keys: no auto-capitals, and the right keyboard. */
    keyboard: KeyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    secret: Boolean = false, mono: Boolean = false, maxLines: Int = Int.MAX_VALUE,
) {
    val cs = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val border by animateColorAsState(if (focused) cs.primary else cs.surfaceContainerHighest, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "field")
    val style = (if (big) MaterialTheme.typography.headlineSmall else if (mono) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge).copy(color = cs.onSurface,
        fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else null)
    Column(modifier.fillMaxWidth()) {
        Text(label.uppercase(), style = Eyebrow, color = if (focused) cs.primary else cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerHighest.copy(alpha = 0.55f), border = androidx.compose.foundation.BorderStroke(2.dp, border)) {
            BasicTextField(value, onChange, textStyle = style, singleLine = singleLine, minLines = minLines, maxLines = if (singleLine) 1 else maxLines, cursorBrush = SolidColor(cs.primary),
                keyboardOptions = keyboard, visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = if (big) 18.dp else 16.dp)
                    .onFocusChanged { focused = it.isFocused },
                decorationBox = { inner ->
                    Box { if (value.isEmpty()) Text(placeholder, style = style.copy(color = cs.onSurfaceVariant.copy(alpha = 0.5f), fontWeight = androidx.compose.ui.text.font.FontWeight.Normal)); inner() }
                })
        }
    }
}


/** Pick one: big tappable chips that wrap, the chosen one filled. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceChips(options: List<Triple<String, String, ImageVector?>>, selected: String, onSelect: (String) -> Unit, label: String? = null) {
    Column {
        if (label != null) Text(label.uppercase(), style = Eyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (id, text, icon) ->
                val on = id == selected
                FilterChip(selected = on, onClick = { onSelect(id) }, label = { Text(text, style = MaterialTheme.typography.labelLarge) },
                    leadingIcon = icon?.let { { Icon(it, null, Modifier.size(18.dp)) } }, shape = RoundedCornerShape(50),
                    modifier = Modifier.height(40.dp))
            }
        }
    }
}

/** A bigger choice with a line explaining it: used where the options need words (when a routine runs). */
@Composable
fun ChoiceCard(icon: ImageVector, title: String, line: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) cs.primaryContainer else cs.surfaceContainerHigh, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "choice")
    val corner by animateFloatAsState(if (selected) 28f else 16f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "corner")
    Surface(onClick = onClick, shape = RoundedCornerShape(corner.dp), color = bg, modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Icon(icon, null, tint = if (selected) cs.onPrimaryContainer else cs.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (selected) cs.onPrimaryContainer else cs.onSurface)
            Text(line, style = MaterialTheme.typography.bodySmall, color = if (selected) cs.onPrimaryContainer.copy(alpha = 0.8f) else cs.onSurfaceVariant, minLines = 2, maxLines = 2)
        }
    }
}
