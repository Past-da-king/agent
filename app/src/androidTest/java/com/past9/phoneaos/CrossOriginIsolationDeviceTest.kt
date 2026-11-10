package com.past9.phoneaos

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.past9.phoneaos.browser.IsolatedOrigins
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Needs a real WebView, so it runs on a phone or an emulator, not on the build server:
 *   ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.past9.phoneaos.CrossOriginIsolationDeviceTest
 *
 * Two made-up pages are served to the WebView with the same isolation header. Only one of the two sites is on the
 * list given to the browser, through the same [IsolatedOrigins.apply] the app's browser uses. The listed site must
 * see crossOriginIsolated and SharedArrayBuffer; the other must not. Skipped (not failed) on a WebView too old for
 * the list, where the app does nothing either.
 */
@RunWith(AndroidJUnit4::class)
class CrossOriginIsolationDeviceTest {
    private val listed = "https://isolated.agent-test.example"
    private val other = "https://plain.agent-test.example"
    private val probe = "(function(){return JSON.stringify({coi:self.crossOriginIsolated===true,sab:typeof SharedArrayBuffer})})()"

    private fun load(profileName: String, origin: String): JSONObject {
        val done = CountDownLatch(1)
        var result = ""
        val inst = InstrumentationRegistry.getInstrumentation()
        var web: WebView? = null
        inst.runOnMainSync {
            web = WebView(inst.targetContext).apply {
                WebViewCompat.setProfile(this, profileName)
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        if (!request.url.toString().startsWith("https://") || !request.url.host.orEmpty().endsWith("agent-test.example")) return null
                        val headers = mapOf("Document-Isolation-Policy" to "isolate-and-require-corp", "Cross-Origin-Opener-Policy" to "same-origin", "Cross-Origin-Embedder-Policy" to "require-corp")
                        return WebResourceResponse("text/html", "utf-8", 200, "OK", headers, ByteArrayInputStream("<!doctype html><title>t</title><body>page</body>".toByteArray()))
                    }
                    override fun onPageFinished(view: WebView, url: String) {
                        view.evaluateJavascript(probe) { r -> result = r ?: ""; done.countDown() }
                    }
                }
                loadUrl("$origin/page")
            }
        }
        assertTrue("the page did not load", done.await(30, TimeUnit.SECONDS))
        inst.runOnMainSync { web?.destroy() }
        // evaluateJavascript hands back a JSON string of our JSON string.
        return JSONObject(org.json.JSONTokener(result).nextValue() as String)
    }

    @Test fun aListedSiteIsIsolatedAndAnotherIsNot() {
        var supported = false
        val inst = InstrumentationRegistry.getInstrumentation()
        val name = "coi-test"
        inst.runOnMainSync {
            supported = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) && WebViewFeature.isFeatureSupported(WebViewFeature.CROSS_ORIGIN_ISOLATED_ALLOWLIST)
            if (supported) {
                val p: Profile = ProfileStore.getInstance().getOrCreateProfile(name)
                assertTrue(IsolatedOrigins.apply(true, listOf(listed)) { p.setCrossOriginIsolatedAllowlist(it) })
            }
        }
        assumeTrue("This WebView has no cross-origin isolation list", supported)
        val yes = load(name, listed)
        assertTrue("listed site: $yes", yes.getBoolean("coi")); assertEquals("function", yes.getString("sab"))
        val no = load(name, other)
        assertFalse("unlisted site: $no", no.getBoolean("coi")); assertEquals("undefined", no.getString("sab"))
    }

    @Test fun anEmptyListIsolatesNothing() {
        var supported = false
        val inst = InstrumentationRegistry.getInstrumentation()
        val name = "coi-test-empty"
        inst.runOnMainSync {
            supported = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) && WebViewFeature.isFeatureSupported(WebViewFeature.CROSS_ORIGIN_ISOLATED_ALLOWLIST)
            if (supported) ProfileStore.getInstance().getOrCreateProfile(name).let { p -> IsolatedOrigins.apply(true, emptyList()) { p.setCrossOriginIsolatedAllowlist(it) } }
        }
        assumeTrue("This WebView has no cross-origin isolation list", supported)
        assertFalse(load(name, listed).getBoolean("coi"))
    }

    /** On any WebView, old or new, asking through the app's own path never throws. */
    @Test fun theAppsPathNeverThrows() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val supported = WebViewFeature.isFeatureSupported(WebViewFeature.CROSS_ORIGIN_ISOLATED_ALLOWLIST)
            val took = IsolatedOrigins.apply(supported, IsolatedOrigins.DEFAULT) {
                ProfileStore.getInstance().getOrCreateProfile("coi-test-path").setCrossOriginIsolatedAllowlist(it)
            }
            assertEquals(supported, took)
        }
    }
}
