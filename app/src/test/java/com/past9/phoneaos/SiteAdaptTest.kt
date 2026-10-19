package com.past9.phoneaos

import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.browser.*
import com.past9.phoneaos.data.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The browser works out how a site wants to be opened, without any per-site code. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SiteAdaptTest {
    private val page = "Welcome back. Your projects, your recent files, and everything you were working on is here. Create a new one any time."

    /** A pretend browser: what each site answers depends only on whether it is shown a phone or a desktop browser. */
    private class Fake(override var desktop: Boolean = false, val answers: (url: String, desktop: Boolean) -> Loaded) : SitePort {
        val loads = mutableListOf<Pair<String, Boolean>>()
        override suspend fun setDesktop(on: Boolean) { desktop = on }
        override suspend fun load(url: String): Loaded { loads += url to desktop; return answers(url, desktop) }
        override suspend fun snapshot() = "outline"
    }
    private fun ok(url: String, text: String = page) = Loaded(url, "Title", 200, null, text)
    private fun catalog() = SiteCatalog(SettingsStore(ApplicationProvider.getApplicationContext()))

    @Test fun aNewSiteStartsAsAPhoneEvenIfTheLastSiteLeftTheBrowserOnDesktop() = runBlocking {
        val f = Fake(desktop = true) { u, _ -> ok(u) }
        val o = SiteAdapt.open(f, catalog(), "https://fresh.example.com/", null)
        assertTrue(o.verdict.ok); assertFalse(o.desktop); assertEquals(listOf("https://fresh.example.com/" to false), f.loads)
    }

    @Test fun aSiteThatLoadsIsLeftAloneAndRemembered() = runBlocking {
        val c = catalog(); val f = Fake { u, _ -> ok(u) }
        val o = SiteAdapt.open(f, c, "https://notes.example.com/home", null)
        assertTrue(o.verdict.ok); assertFalse(o.desktop); assertEquals(1, f.loads.size)
        assertEquals("works", c.get("https://notes.example.com")!!.outcome)
    }

    @Test fun aPhoneRefusalIsRetriedAsDesktopAndRememberedForNextTime() = runBlocking {
        val c = catalog()
        val f = Fake { u, d -> if (d) ok(u) else Loaded(u, "Oops", 200, null, "This browser is not supported. Please use a desktop browser.") }
        val o = SiteAdapt.open(f, c, "https://studio.example.com/", null)
        assertTrue(o.verdict.ok); assertTrue(o.desktop); assertTrue(o.triedBoth)
        assertTrue(o.notes, o.notes.contains("worked as a desktop one") && o.notes.contains("Remembered"))
        assertTrue(c.get("https://studio.example.com")!!.desktop)
        // Next visit: straight to desktop, one load, no refusal first.
        val g = Fake { u, d -> if (d) ok(u) else Loaded(u, "Oops", 200, null, "This browser is not supported. Please use a desktop browser.") }
        val again = SiteAdapt.open(g, c, "https://studio.example.com/projects", null)
        assertTrue(again.verdict.ok); assertEquals(listOf("https://studio.example.com/projects" to true), g.loads)
        assertTrue(again.notes, again.notes.contains("learned earlier"))
    }

    @Test fun aDesktopRefusalFlipsTheOtherWayToo() = runBlocking {
        val c = catalog()
        val f = Fake(desktop = true) { u, d -> if (!d) ok(u) else Loaded(u, "", 403, null, "Access denied") }
        val o = SiteAdapt.open(f, c, "https://m.shop.example.org/", null)
        assertTrue(o.verdict.ok); assertFalse(o.desktop); assertFalse(c.get("https://shop.example.org")!!.desktop)
    }

    @Test fun whenNeitherWorksItSaysWhatItSawAndWhatToDoNext() = runBlocking {
        val c = catalog(); val f = Fake { u, _ -> Loaded(u, "", 403, null, "Access denied") }
        val o = SiteAdapt.open(f, c, "https://walled.example.com/", null)
        assertFalse(o.verdict.ok); assertTrue(o.triedBoth); assertEquals(2, f.loads.size)
        assertTrue(o.notes, o.notes.contains("THE SITE REFUSED THIS BROWSER") && o.notes.contains("browser_network") && o.notes.contains("machine_handoff") && o.notes.contains("phone and desktop"))
        assertEquals("refused", c.get("https://walled.example.com")!!.outcome)
    }

    @Test fun aHumanCheckIsNotRetriedAsADifferentBrowserItNeedsAPerson() = runBlocking {
        val c = catalog(); val f = Fake { u, _ -> Loaded(u, "Just a moment...", 403, null, "Verify you are human. Checking your browser before accessing the site.") }
        val o = SiteAdapt.open(f, c, "https://guarded.example.com/", null)
        assertEquals(Refusal.HUMAN_CHECK, o.verdict.refusal); assertFalse(o.triedBoth); assertEquals(1, f.loads.size)
        assertTrue(o.notes, o.notes.contains("browser_handoff"))
    }

    @Test fun anExplicitChoiceIsRespectedAndNeverSecondGuessed() = runBlocking {
        val c = catalog(); val f = Fake { u, d -> if (d) Loaded(u, "", 403, null, "Access denied") else ok(u) }
        val o = SiteAdapt.open(f, c, "https://x.example.com/", true)
        assertFalse(o.verdict.ok); assertFalse(o.triedBoth); assertEquals(listOf("https://x.example.com/" to true), f.loads)
    }

    @Test fun healthReadsCommonRefusalsAndIgnoresNormalPages() {
        assertEquals(Refusal.NONE, SiteHealth.assess(200, null, "Home", page).refusal)
        assertEquals(Refusal.UNSUPPORTED_BROWSER, SiteHealth.assess(200, null, "", "Sorry, your browser isn't supported. Try a different browser.").refusal)
        assertEquals(Refusal.UNSUPPORTED_BROWSER, SiteHealth.assess(200, null, "", "This app is only available on desktop.").refusal)
        assertEquals(Refusal.HTTP_BLOCKED, SiteHealth.assess(403, null, "", "Forbidden").refusal)
        assertEquals(Refusal.HTTP_BLOCKED, SiteHealth.assess(200, null, "Error", "Access Denied. You don't have permission").refusal)
        assertEquals(Refusal.HUMAN_CHECK, SiteHealth.assess(200, null, "", "Please complete the captcha to continue").refusal)
        assertEquals(Refusal.SIGNIN_REFUSED, SiteHealth.assess(200, null, "", "Couldn't sign you in. This browser or app may not be secure.").refusal)
        assertEquals(Refusal.BLANK, SiteHealth.assess(200, null, "", "   ").refusal)
        assertEquals(Refusal.LOAD_ERROR, SiteHealth.assess(null, "net::ERR_CERT_DATE_INVALID", "", "").refusal)
        // Exact texts read from the real Android WebView on the test phone.
        assertEquals(Refusal.HTTP_BLOCKED, SiteHealth.assess(null, null, "httpbin.org/status/403", "Webpage not available The webpage at https://httpbin.org/status/403 could not be loaded because: net::ERR_HTTP_RESPONSE_CODE_FAILURE").refusal)
        assertEquals(Refusal.LOAD_ERROR, SiteHealth.assess(null, null, "", "Webpage not available The webpage at https://x.example could not be loaded because: net::ERR_NAME_NOT_RESOLVED").refusal)
        assertEquals(Refusal.UNSUPPORTED_BROWSER, SiteHealth.assess(200, null, "WhatsApp Web", "Use WhatsApp Web by going to web.whatsapp.com from a browser on your computer.").refusal)
        assertEquals(Refusal.NONE, SiteHealth.assess(200, null, "Example Domain", "Example Domain This domain is for use in illustrative examples in documents. You may use this domain in literature without prior coordination or asking for permission. More information...").refusal)
        // A long page that merely mentions an unsupported browser in its footer is not a refusal.
        assertEquals(Refusal.NONE, SiteHealth.assess(200, null, "Docs", page.repeat(30) + " Note: Internet Explorer is not supported.").refusal)
        assertTrue(SiteHealth.assess(403, null, "", "Forbidden").identityMayHelp); assertFalse(SiteHealth.assess(200, null, "", "Verify you are human").identityMayHelp)
    }

    @Test fun catalogKeysByHostSoSubSitesAreLearnedSeparately() {
        assertEquals("flow.google.com", SiteCatalog.siteOf("https://www.flow.google.com/x")); assertEquals("shop.example.co.za", SiteCatalog.siteOf("m.shop.example.co.za/a?b=1"))
        val c = catalog(); c.record("https://a.example.com", true, "works"); c.record("https://b.example.com", false, "works")
        assertTrue(c.get("a.example.com")!!.desktop); assertFalse(c.get("b.example.com")!!.desktop); assertNull(c.get("c.example.com"))
        assertEquals(2, c.list().size); c.forget("a.example.com"); assertNull(c.get("a.example.com"))
    }
}
