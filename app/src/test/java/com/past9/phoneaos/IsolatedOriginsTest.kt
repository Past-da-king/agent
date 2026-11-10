package com.past9.phoneaos

import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.browser.IsolatedOrigins
import com.past9.phoneaos.browser.IsolatedOrigins.Parsed
import com.past9.phoneaos.data.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings > Browser > Sites that need advanced browser features: the list, what may go on it, and the browser taking it or not. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IsolatedOriginsTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun store() = SettingsStore(ctx)

    @Before fun clean() { store().setExtra("isolated_origins", null); store().setExtra("call_bridge_on", null) }

    private fun ok(input: String) = (IsolatedOrigins.normalise(input) as? Parsed.Ok)?.origin
    private fun bad(input: String) = (IsolatedOrigins.normalise(input) as? Parsed.Bad)?.why

    // ---- what may go on the list ----

    @Test fun anAddressBecomesAnExactOrigin() {
        assertEquals("https://web.whatsapp.com", ok("https://web.whatsapp.com"))
        assertEquals("https://web.whatsapp.com", ok("  HTTPS://Web.WhatsApp.com/  "))
        assertEquals("https://stackblitz.com", ok("stackblitz.com"))
        assertEquals("https://example.com", ok("https://example.com:443"))
        assertEquals("https://example.com:8443", ok("https://example.com:8443/"))
        assertEquals("https://192.168.1.20:8443", ok("192.168.1.20:8443"))
    }

    @Test fun onlyHttps() {
        assertNotNull(bad("http://example.com"))
        assertNotNull(bad("ftp://example.com"))
        assertNotNull(bad("file:///sdcard/page.html"))
        assertNotNull(bad("javascript://example.com"))
    }

    @Test fun noWildcards() {
        assertNotNull(bad("*"))
        assertNotNull(bad("https://*"))
        assertNotNull(bad("https://*.example.com"))
        assertNotNull(bad("*.example.com"))
    }

    @Test fun noPathsQueriesOrFragments() {
        assertNotNull(bad("https://example.com/app"))
        assertNotNull(bad("https://example.com/?a=1"))
        assertNotNull(bad("https://example.com/#x"))
        assertTrue(bad("https://example.com/app")!!.contains("https://example.com"))
    }

    @Test fun nonsenseIsRefused() {
        assertNotNull(bad(""))
        assertNotNull(bad("   "))
        assertNotNull(bad("not a site"))
        assertNotNull(bad("localhost"))
        assertNotNull(bad("https://"))
        assertNotNull(bad("https://user:pw@example.com"))
        assertNotNull(bad("https://exa_mple.com"))
        assertNotNull(bad("https://example.com:99999"))
        assertNotNull(bad("https://-bad-.com"))
    }

    // ---- the list itself ----

    @Test fun aFreshInstallHasWhatsAppWebOnly() {
        assertEquals(listOf("https://web.whatsapp.com"), store().isolatedOrigins())
        assertEquals(listOf("https://web.whatsapp.com"), IsolatedOrigins.parse(null))
    }

    @Test fun addingAndRemovingArePersisted() {
        assertNull(store().addIsolatedOrigin("stackblitz.com"))
        assertNull(store().addIsolatedOrigin("https://Chat.WebLLM.ai/"))
        // A new store reads the same file: it survived.
        assertEquals(listOf("https://web.whatsapp.com", "https://stackblitz.com", "https://chat.webllm.ai"), store().isolatedOrigins())
        store().removeIsolatedOrigin("https://stackblitz.com")
        assertEquals(listOf("https://web.whatsapp.com", "https://chat.webllm.ai"), store().isolatedOrigins())
    }

    @Test fun aBadAddressIsNotSavedAndSaysWhy() {
        val why = store().addIsolatedOrigin("http://example.com")
        assertEquals("Only https sites can be added.", why)
        assertNotNull(store().addIsolatedOrigin("https://*.example.com"))
        assertNotNull(store().addIsolatedOrigin("https://example.com/page"))
        assertEquals(listOf("https://web.whatsapp.com"), store().isolatedOrigins())
    }

    @Test fun theSameSiteIsNotAddedTwice() {
        val why = store().addIsolatedOrigin("WEB.whatsapp.com/")
        assertTrue(why!!.contains("already"))
        assertEquals(1, store().isolatedOrigins().size)
    }

    @Test fun theDefaultCanBeRemovedAndDoesNotComeBack() {
        store().removeIsolatedOrigin("https://web.whatsapp.com")
        assertEquals(emptyList<String>(), store().isolatedOrigins())
        assertEquals(emptyList<String>(), SettingsStore(ctx).isolatedOrigins())
        assertNull(store().addIsolatedOrigin("example.com"))
        assertEquals(listOf("https://example.com"), store().isolatedOrigins())
    }

    @Test fun aStoredListThatWasTamperedWithIsCleaned() {
        store().setExtra("isolated_origins", "https://ok.example.com\n*\nhttp://plain.example.com\nhttps://ok.example.com\nhttps://x.example.com/path\n")
        assertEquals(listOf("https://ok.example.com"), store().isolatedOrigins())
    }

    @Test fun theListHasACeiling() {
        var list = emptyList<String>()
        repeat(IsolatedOrigins.MAX) { i -> list = IsolatedOrigins.add(list, "site$i.example.com").also { assertNull(it.second) }.first }
        val (same, why) = IsolatedOrigins.add(list, "one-more.example.com")
        assertEquals(IsolatedOrigins.MAX, same.size)
        assertNotNull(why)
    }

    // ---- the browser taking the list ----

    @Test fun aBrowserThatSupportsItGetsTheExactList() {
        var got: Set<String>? = null
        assertTrue(IsolatedOrigins.apply(true, listOf("https://web.whatsapp.com", "https://stackblitz.com")) { got = it })
        assertEquals(setOf("https://web.whatsapp.com", "https://stackblitz.com"), got)
    }

    @Test fun anOlderBrowserIsLeftAlone() {
        var called = false
        assertFalse(IsolatedOrigins.apply(false, listOf("https://web.whatsapp.com")) { called = true })
        assertFalse(called)
    }

    @Test fun aBrowserThatRefusesDoesNotCrashTheApp() {
        assertFalse(IsolatedOrigins.apply(true, listOf("https://web.whatsapp.com")) { throw UnsupportedOperationException("This method is not supported by the current version of the framework and the current WebView APK") })
        assertFalse(IsolatedOrigins.apply(true, listOf("https://web.whatsapp.com")) { throw IllegalArgumentException("bad origin") })
    }

    @Test fun aWildcardNeverReachesTheBrowser() {
        var got: Set<String>? = null
        IsolatedOrigins.apply(true, listOf("*", "https://*", "http://plain.example.com", "https://ok.example.com")) { got = it }
        assertEquals(setOf("https://ok.example.com"), got)
    }

    @Test fun anEmptyListClearsTheBrowsersList() {
        var got: Set<String>? = null
        assertTrue(IsolatedOrigins.apply(true, emptyList()) { got = it })
        assertEquals(emptySet<String>(), got)
    }

    /** Robolectric has no WebView provider, like a phone with a WebView too old for the list: the feature check says no and nothing is called. */
    @Test fun theRealFeatureCheckIsFalseWithoutAWebViewThatHasIt() {
        val supported = runCatching { androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.CROSS_ORIGIN_ISOLATED_ALLOWLIST) }.getOrDefault(false)
        var called = false
        assertEquals(supported, IsolatedOrigins.apply(supported, store().isolatedOrigins()) { called = true })
        assertEquals(supported, called)
    }

    // ---- the settings line and the call switch ----

    @Test fun theSettingsLineSaysWhatIsListed() {
        assertEquals("web.whatsapp.com", IsolatedOrigins.line(IsolatedOrigins.DEFAULT))
        assertTrue(IsolatedOrigins.line(emptyList()).startsWith("None"))
        assertTrue(IsolatedOrigins.line(listOf("https://a.example.com", "https://b.example.com", "https://c.example.com")).startsWith("3 sites"))
    }

    @Test fun theAgentsVoiceInCallsIsOffUntilSwitchedOn() {
        assertFalse(store().callBridgeOn())
        store().setCallBridgeOn(true)
        assertTrue(SettingsStore(ctx).callBridgeOn())
        store().setCallBridgeOn(false)
        assertFalse(SettingsStore(ctx).callBridgeOn())
    }
}
