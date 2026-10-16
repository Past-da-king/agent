package com.past9.phoneaos.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tablets and unfolded phones. Material's window width classes: compact under 600dp (every phone in portrait),
 * medium 600 to 839dp (tablets in portrait, foldables), expanded 840dp and up (tablets in landscape).
 * Phones stay exactly as they were: every helper here is a no-op below the column width.
 */
enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

object Readable {
    /** The chat: long lines are hard to read past about 80 characters. */
    val CHAT = 760.dp
    /** Lists and settings-style pages. */
    val LIST = 720.dp
    /** Onboarding steps and forms. */
    val FORM = 600.dp
    /** Home in two columns on wide screens. */
    val WIDE = 1120.dp
}

/** Set inside a pane of a two-pane layout: screens in it size themselves to the pane, not the window. */
val LocalPaneWidth = androidx.compose.runtime.staticCompositionLocalOf<Dp?> { null }

@Composable fun windowWidthDp(): Dp = LocalPaneWidth.current ?: LocalConfiguration.current.screenWidthDp.dp

@Composable fun widthClass(): WidthClass = windowWidthDp().let { w ->
    when { w < 600.dp -> WidthClass.COMPACT; w < 840.dp -> WidthClass.MEDIUM; else -> WidthClass.EXPANDED }
}

/**
 * Side padding that keeps content in a centred column at most [max] wide, and never less than [min].
 * Use it as a list's horizontal contentPadding so the whole screen still scrolls, not just the column.
 */
@Composable fun sidePad(min: Dp, max: Dp = Readable.LIST): Dp = maxOf(min, (windowWidthDp() - max) / 2)

/** A single element (an input bar, a button row) centred at most [max] wide. */
fun Modifier.readableWidth(max: Dp = Readable.LIST): Modifier = this.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max)
