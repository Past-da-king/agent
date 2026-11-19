package com.past9.phoneaos

import com.past9.phoneaos.remote.MentionProject
import com.past9.phoneaos.voice.DictationNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationNamesTest {
    private fun p(name: String, at: Long, vararg a: String) = MentionProject(name, name, a.toList(), "", "iris", "", at)

    @Test fun theNamesTheAppHoldsAreTheOnesToldAndTheCompanyThatHeadsSeveralProjectsComesFirst() {
        val projects = listOf(p("Meqry", 9), p("GrowOS", 8, "Grow OS"), p("Strat Edge ID", 1, "SEI"), p("Strat Edge Platform", 2), p("Strat CMS", 3))
        val names = DictationNames.build("Ayanda", "Hina", projects)
        assertEquals("Strat Edge", names[0])
        assertEquals(listOf("Strat Edge", "Strat Edge Platform", "Strat CMS", "Strat Edge ID", "SEI", "Meqry", "GrowOS", "Grow OS", "Ayanda", "Hina").sorted(), names.sorted())
        assertTrue(names.indexOf("SEI") < names.indexOf("Meqry"))
        assertTrue(names.indexOf("Strat CMS") < names.indexOf("Meqry"))
    }

    @Test fun aListHeKeepsGoesFirstAndPlaceholdersRepeatsAndOddOnesAreLeftOut() {
        val names = DictationNames.build(" ", "Your agent", listOf(p("Creek", 1), p("creek", 2), p("x", 3), p("a".repeat(80), 4)), DictationNames.alwaysOf("Strat Edge, SEI\nStrat CMS"))
        assertEquals(listOf("Strat Edge", "SEI", "Strat CMS", "creek"), names)
        assertTrue(DictationNames.build("A", "B", (1..300).map { p("Project $it", it.toLong()) }).size <= DictationNames.MAX)
    }
}
