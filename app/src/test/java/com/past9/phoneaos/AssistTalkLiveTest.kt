package com.past9.phoneaos

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.past9.phoneaos.assist.AssistActions
import com.past9.phoneaos.assist.AssistPhase
import com.past9.phoneaos.assist.AssistSheetBody
import com.past9.phoneaos.assist.AssistView
import com.past9.phoneaos.assist.AssistWords
import com.past9.phoneaos.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The assist sheet always offers real-time Live talk while he could speak, even with the Live setting off (27 Nov 2026). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class AssistTalkLiveTest {
    @get:Rule val rule = createComposeRule()

    @Test fun listeningSheetOffersTalkLiveAndTheTapSwitches() {
        var taps = 0
        rule.setContent { AppTheme { AssistSheetBody(AssistView(AssistPhase.LISTENING), "Nova", null, AssistActions(onTalkLive = { taps++ })) } }
        rule.onNodeWithText("Talk live").assertIsDisplayed().performClick()
        assertEquals(1, taps)
    }

    @Test fun afterLiveFailedTheSheetSaysWhyAndOffersItAgain() {
        rule.setContent { AppTheme { AssistSheetBody(AssistView(AssistPhase.ERROR, problem = "Live voice stopped: no key."), "Nova", null, AssistActions(onTalkLive = {})) } }
        rule.onNodeWithText("Live voice stopped: no key.").assertIsDisplayed()
        rule.onNodeWithText("Talk live").assertIsDisplayed()
    }

    @Test fun notOfferedWhileATurnIsOnItsWay() {
        assertTrue(AssistWords.offersLive(AssistPhase.LISTENING))
        assertTrue(AssistWords.offersLive(AssistPhase.ANSWERING))
        assertTrue(AssistWords.offersLive(AssistPhase.ERROR))
        assertFalse(AssistWords.offersLive(AssistPhase.SENDING))
        assertFalse(AssistWords.offersLive(AssistPhase.LOCKED))
        assertFalse(AssistWords.offersLive(AssistPhase.APPROVAL))
    }
}
