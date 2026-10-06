package com.past9.phoneaos

import com.past9.phoneaos.tools.AppCatalog
import com.past9.phoneaos.tools.ConnectRequest
import org.junit.Assert.*
import org.junit.Test

class ConnectRequestTest {
    @Test fun roundTrips() {
        val r = ConnectRequest.of("Gmail", "check your inbox")
        assertEquals("CONNECT|gmail|Gmail|check your inbox", r.text)
        assertEquals(r, ConnectRequest.parse(r.text))
        assertEquals("https://logos.composio.dev/api/gmail", r.logo)
        assertEquals("Connect Gmail so I can check your inbox?", r.summary)
    }

    @Test fun pipesInTheReasonDontBreakIt() {
        val r = ConnectRequest.of("slack", "post to #a | #b")
        assertEquals("post to #a   #b", ConnectRequest.parse(r.text)!!.reason)
    }

    @Test fun noReasonStillReads() = assertEquals("Connect Google Calendar?", ConnectRequest.of("googlecalendar", "").summary)

    @Test fun notAConnectQuestion() {
        assertNull(ConnectRequest.parse("APPROVAL|send email|to Sam"))
        assertNull(ConnectRequest.parse("Which one?"))
    }

    @Test fun unknownAppsGetAReadableName() {
        assertEquals("Microsoft Teams", AppCatalog.name("microsoft_teams"))
        assertEquals("Some App", AppCatalog.name("some_app"))
    }

    @Test fun catalogueHasNoDuplicatesAndAllLogos() {
        val slugs = AppCatalog.popular.map { it.slug }
        assertEquals(slugs.size, slugs.toSet().size)
        assertTrue(AppCatalog.popular.size >= 40)
        assertTrue(AppCatalog.popular.all { it.logo.endsWith("/${it.slug}") && it.description.isNotBlank() })
    }
}
