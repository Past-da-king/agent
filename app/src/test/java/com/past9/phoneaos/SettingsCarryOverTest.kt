package com.past9.phoneaos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.data.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The browser settings added in 0.15.33 to 0.15.35 must still be there after an update and after the app is started
 * again. An update keeps the "settings" preferences file as the older version wrote it, so each test writes that
 * file the way an older version did, key by key, and reads it with today's code. The key names are spelled out
 * here on purpose: renaming one in the app must fail a test, because a renamed key is a setting lost on update.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsCarryOverTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private fun file() = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** What 0.15.33 had on disk with both of its browser settings changed by the user. */
    private fun write01533() = file().edit().clear()
        .putBoolean("onboarded", true).putString("userName", "Sam")
        .putString("x_isolated_origins", "https://web.whatsapp.com\nhttps://example.org")
        .putString("x_call_bridge_on", "true")
        .commit()

    /** What 0.15.34 and 0.15.35 had on disk with the test account switch on for one number. */
    private fun write01535() { write01533(); file().edit().putString("x_call_test_numbers", "15550100123").putString("x_call_test_on", "true").commit() }

    @Test fun whatAnOlderVersionStoredIsReadByThisOne() {
        write01535()
        val s = SettingsStore(ctx)
        assertEquals(listOf("https://web.whatsapp.com", "https://example.org"), s.isolatedOrigins())
        assertTrue(s.callBridgeOn())
        assertEquals(listOf("15550100123"), s.callTestNumbers()); assertTrue(s.callTestOn())
        assertEquals("Sam", s.state.value.userName)
    }

    @Test fun startingTheAppWritesNothingOverWhatIsStored() {
        write01535()
        val before = HashMap(file().all)
        val s = SettingsStore(ctx)
        s.state.value; s.isolatedOrigins(); s.callBridgeOn(); s.callTestOn(); s.callTestNumbers()
        assertEquals("opening the store and reading it changes no stored value", before, HashMap(file().all))
    }

    @Test fun anUpdateFromAVersionWithoutTheTestSwitchLeavesItOffAndKeepsTheRest() {
        // 0.15.33 had no test account switch: nothing was stored for it, so after the update it is off with no number,
        // and what 0.15.33 did store is untouched. (Calling forced by hand in the page on 0.15.33 was never a setting.)
        write01533()
        val s = SettingsStore(ctx)
        assertFalse(s.callTestOn()); assertTrue(s.callTestNumbers().isEmpty())
        assertTrue(s.callBridgeOn()); assertEquals(2, s.isolatedOrigins().size)
        assertNull(file().getString("x_call_test_on", null))
    }

    @Test fun whatTheSettingsScreenWritesIsOnDiskAtOnceUnderTheSameKeys() {
        file().edit().clear().commit()
        val s = SettingsStore(ctx)
        // The one supported way: Settings > Browser. These are the calls its sheet makes.
        assertNull(s.addCallTestNumber("+1 555 010 0123")); assertTrue(s.setCallTestOn(true)); s.setCallBridgeOn(true); assertNull(s.addIsolatedOrigin("https://example.org"))
        // Another handle on the same file, as a new process would open it: already there, with no wait for a background write.
        val disk = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        assertEquals("true", disk.getString("x_call_test_on", null)); assertEquals("15550100123", disk.getString("x_call_test_numbers", null))
        assertEquals("true", disk.getString("x_call_bridge_on", null)); assertTrue(disk.getString("x_isolated_origins", "")!!.contains("https://example.org"))
        // A new store (the app started again, or updated) reads them back.
        val again = SettingsStore(ctx)
        assertTrue(again.callTestOn()); assertEquals(listOf("15550100123"), again.callTestNumbers()); assertTrue(again.callBridgeOn())
        assertTrue("https://example.org" in again.isolatedOrigins())
    }

    @Test fun theSwitchNeedsANumberAndGoesOffWithTheLastOne() {
        file().edit().clear().commit()
        val s = SettingsStore(ctx)
        assertFalse("no number, no switch", s.setCallTestOn(true)); assertFalse(s.callTestOn())
        // A switch stored as on with no number (it cannot be made in the app) still reads as off.
        file().edit().putString("x_call_test_on", "true").commit()
        assertFalse(SettingsStore(ctx).callTestOn())
        s.addCallTestNumber("+15550100123"); assertTrue(s.callTestOn())
        s.removeCallTestNumber("15550100123")
        assertFalse(SettingsStore(ctx).callTestOn()); assertNull(file().getString("x_call_test_on", null)); assertNull(file().getString("x_call_test_numbers", null))
    }

    @Test fun anEmptiedSiteListStaysEmptyAfterARestart() {
        file().edit().clear().putString("x_isolated_origins", "").commit()
        assertTrue(SettingsStore(ctx).isolatedOrigins().isEmpty())
        // And nothing stored at all is the default list.
        file().edit().clear().commit()
        assertEquals(com.past9.phoneaos.browser.IsolatedOrigins.DEFAULT, SettingsStore(ctx).isolatedOrigins())
    }
}
