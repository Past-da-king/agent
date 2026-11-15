package com.past9.phoneaos

import com.past9.phoneaos.data.AgentSettings
import com.past9.phoneaos.wear.WatchPairing
import com.past9.phoneaos.wearproto.Customisation
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the phone tells the watch about its own customisations. */
class WatchPairingPhoneTest {
    @Test fun theWatchGetsTheNameMascotAccentLanguageAndVoiceAndTheSystemTheme() {
        val c = WatchPairing.customisation("en-ZA", AgentSettings(agentName = "Hina", mascot = "ghost", accent = "teal", ttsProvider = "gemini", ttsVoice = "Kore"))
        assertEquals(Customisation("Hina", "ghost", "teal", Customisation.THEME_SYSTEM, "en-ZA", "gemini", "Kore"), c)
    }

    @Test fun anAgentThatIsNotNamedYetLeavesTheNameEmptySoTheWatchKeepsItsOwnWord() {
        assertEquals("", WatchPairing.customisation("", AgentSettings()).agentName)
    }

    @Test fun aChangeOnThePhoneIsAChangeInWhatIsPublished() {
        val s = AgentSettings(agentName = "Hina")
        assertEquals("teal", WatchPairing.customisation("", s.copy(accent = "teal")).accent)
        assertEquals("fr", WatchPairing.customisation("fr", s).language)
    }
}
