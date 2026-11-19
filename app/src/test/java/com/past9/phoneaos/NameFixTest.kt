package com.past9.phoneaos

import com.past9.phoneaos.voice.NameFix
import org.junit.Assert.assertEquals
import org.junit.Test

/** The live preview's sound-alike names (19 Nov 2026), and the places it must stay out of. */
class NameFixTest {
    private val names = listOf("Strat Edge", "SEI", "Strat CMS", "Strat Edge ID", "Meqry", "Ben van Rooyen")
    private fun fix(t: String) = NameFix.respell(t, names)

    @Test fun startedEdgeBesideACompanyWordBecomesTheName() = assertEquals("the Strat Edge letterhead is old", fix("the started edge letterhead is old"))
    @Test fun thatEdgeBesideACompanyWordBecomesTheName() = assertEquals("add it to the Strat Edge team", fix("add it to the that edge team"))
    @Test fun strataEdgeBecomesTheName() = assertEquals("the Strat Edge website needs it", fix("the strata edge website needs it"))
    @Test fun startEdgeBecomesTheName() = assertEquals("send the Strat Edge tender today", fix("send the start edge tender today"))
    @Test fun stratEdHyphenBecomesTheName() = assertEquals("the Strat Edge platform", fix("the strat-ed platform"))
    @Test fun aLongerNameKeepsItsLastWord() = assertEquals("tasks on Strat Edge ID please", fix("tasks on started edge ID please"))
    @Test fun lowercaseNameGetsItsSpelling() = assertEquals("the Strat Edge letterhead", fix("the strat edge letterhead"))

    @Test fun thatEdgeWithNothingCompanyLikeNearIsLeftAlone() = assertEquals("put it on that edge of the table", fix("put it on that edge of the table"))
    @Test fun talkAboutTheMistakeItselfIsLeftAlone() {
        assertEquals("it said started edge instead of Strat Edge", fix("it said started edge instead of Strat Edge"))
        assertEquals("it's saying that edge now", fix("it's saying that edge now"))
        assertEquals("it said started edge team", fix("it said started edge team"))
    }
    @Test fun aSentenceThatAlreadyHasTheNameIsNeverChanged() = assertEquals("Strat Edge letterhead, not the started edge letterhead", fix("Strat Edge letterhead, not the started edge letterhead"))

    @Test fun hisTwentyOneTwentyEightMessageIsUnchanged() {
        val said = "And yeah, man, Strat Edge, 'cause that word always confuses you. Okay, it said started edge instead of Strat Edge. Again, it's saying strat- it's saying that edge now. But it's supposed to be S-T-R-A-T-E-D-G-E. Okay, yeah, yeah, this transcriber is okay, but not not that better"
        assertEquals(said, fix(said))
    }
    @Test fun the188SecondTakeIsUnchanged() {
        val said = "Because Stratage has its own letterhead but some other company will have its own letterhead. Please add all the tasks on Strat Edge ID. Strat Edge ID thing."
        assertEquals(said, fix(said))
    }
}
