package com.past9.phoneaos.ui

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
    val cs = MaterialTheme.colorScheme
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
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
    // Screenshot tests can't capture a popup window, so they render the sheet in place.
    if (LocalSheetPreview.current) Surface(color = cs.surfaceContainerLow, shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp), modifier = Modifier.fillMaxSize().padding(top = 48.dp)) {
        Column { Box(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 22.dp).size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(cs.onSurfaceVariant.copy(alpha = 0.4f))); body() }
    } else ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cs.surfaceContainerLow, shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp)) { body() }
}

val LocalSheetPreview = staticCompositionLocalOf { false }

/** A field that looks like a page you write on, not a form box. */
@Composable
fun SheetField(
    value: String, onChange: (String) -> Unit, label: String, placeholder: String,
    big: Boolean = false, minLines: Int = 1, singleLine: Boolean = false, modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val border by animateColorAsState(if (focused) cs.primary else cs.surfaceContainerHighest, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "field")
    val style = (if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge).copy(color = cs.onSurface)
    Column(modifier.fillMaxWidth()) {
        Text(label.uppercase(), style = Eyebrow, color = if (focused) cs.primary else cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerHighest.copy(alpha = 0.55f), border = androidx.compose.foundation.BorderStroke(2.dp, border)) {
            BasicTextField(value, onChange, textStyle = style, singleLine = singleLine, minLines = minLines, cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
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
