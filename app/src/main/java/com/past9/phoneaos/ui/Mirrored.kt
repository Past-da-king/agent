package com.past9.phoneaos.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.TrendingDown

/**
 * Icons that point somewhere and so must flip in right to left languages (Arabic, Hebrew, Persian, Urdu).
 * Use these instead of the plain Icons.Rounded versions, which never flip.
 */
val ChevronEnd get() = Icons.AutoMirrored.Rounded.KeyboardArrowRight
val SendIcon get() = Icons.AutoMirrored.Rounded.Send
val OpenInNewIcon get() = Icons.AutoMirrored.Rounded.OpenInNew
val CallMadeIcon get() = Icons.AutoMirrored.Rounded.CallMade
val CallReceivedIcon get() = Icons.AutoMirrored.Rounded.CallReceived
val TrendingDownIcon get() = Icons.AutoMirrored.Rounded.TrendingDown
