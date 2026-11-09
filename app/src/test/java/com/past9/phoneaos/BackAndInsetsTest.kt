package com.past9.phoneaos

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.remote.LinkScreen
import com.past9.phoneaos.ui.barClearance
import com.past9.phoneaos.ui.screens.HomeBar
import com.past9.phoneaos.ui.screens.OnbStep
import com.past9.phoneaos.ui.screens.OnboardingActions
import com.past9.phoneaos.ui.screens.OnboardingScreen
import com.past9.phoneaos.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI audit 9 Nov 2026, findings C-1, C-2 and C-4: system back goes one step back in onboarding and is the arrow on the
 * link screen, and a list's bottom padding always clears the floating bar, whatever the navigation inset.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class BackAndInsetsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val nameTitle = "First, what should I call you?"
    private fun back() { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle() }

    @Test fun `system back on an onboarding page goes one page back`() {
        rule.setContent { AppTheme(dark = false) { OnboardingScreen(OnboardingActions(), start = OnbStep.STYLE, initialUser = "Sam") } }
        rule.onNodeWithText(nameTitle).assertDoesNotExist()
        back()
        rule.onNodeWithText(nameTitle).assertExists()
        assertFalse("back on a later page must not close the app", rule.activity.isFinishing)
    }

    @Test fun `system back on the first onboarding page still leaves the app`() {
        rule.setContent { AppTheme(dark = false) { OnboardingScreen(OnboardingActions()) } }
        back()
        assertTrue(rule.activity.isFinishing)
    }

    @Test fun `system back on the link screen is the arrow`() {
        var backs by mutableIntStateOf(0)
        rule.setContent { AppTheme(dark = false) { LinkScreen(onBack = { backs++ }, onLinked = {}) } }
        back()
        assertEquals(1, backs)
        assertFalse(rule.activity.isFinishing)
    }

    @Test fun `list padding clears the floating bar with gesture and with three button navigation`() {
        var barPx by mutableIntStateOf(0)
        rule.setContent { AppTheme(dark = false) { Surface(color = MaterialTheme.colorScheme.surface) { Box { HomeBar("home", {}, {}, modifier = Modifier.onSizeChanged { barPx = it.height }) } } } }
        rule.waitForIdle()
        // No system bars under Robolectric: this is the bar plus its own 16dp bottom margin.
        val bar = with(rule.density) { barPx.toDp() }
        assertTrue("the bar was measured", bar > 40.dp)
        for (inset in listOf(0.dp, 24.dp, 48.dp)) {
            // On a device the bar is that much taller, because it sits above the inset.
            val pad = barClearance(bar + inset, inset)
            assertTrue("padding $pad must clear the bar $bar (inset $inset)", pad >= bar)
            // Home draws to the screen edge, so it adds the inset: the last row ends above the top of the bar.
            assertTrue(pad + inset >= bar + inset)
        }
        assertEquals(112.dp, barClearance(0.dp, 0.dp))
        assertEquals(216.dp, barClearance(248.dp, 48.dp))
    }
}
