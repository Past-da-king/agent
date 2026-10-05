package com.past9.phoneaos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Candidate Memory icons, shown the way they'd sit in the bottom bar. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class IconOptionsTest {
    @get:Rule val rule = createComposeRule()
    @Test fun options() {
        val opts = listOf("1 Open book" to Icons.Rounded.AutoStories, "2 Book" to Icons.Rounded.MenuBook, "3 Library" to Icons.Rounded.LocalLibrary,
            "4 Bookmarks" to Icons.Rounded.CollectionsBookmark, "5 Linked web" to Icons.Rounded.Hub, "6 Sparkle" to Icons.Rounded.AutoAwesome,
            "7 Bulb" to Icons.Rounded.Lightbulb, "8 Notes" to Icons.Rounded.StickyNote2, "9 Article" to Icons.Rounded.Article,
            "10 Interests" to Icons.Rounded.Interests, "11 Tree" to Icons.Rounded.AccountTree, "12 Spa" to Icons.Rounded.Spa)
        rule.setContent {
            AppTheme(dark = true, accent = "red") {
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Memory icon options", style = MaterialTheme.typography.headlineSmall)
                        opts.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { (label, icon) ->
                                    Column(Modifier.width(108.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(Modifier.size(72.dp, 52.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onPrimaryContainer), contentAlignment = Alignment.Center) {
                                            Icon(icon, null, tint = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(28.dp))
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        Text(label, style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.onRoot().captureRoboImage("build/screens/memory-icon-options.png")
    }
}
