package com.past9.phoneaos.wakeup

import android.content.Context
import android.widget.ScrollView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The ringing screen is built in code for both the lock-screen activity and the overlay. 0.15.0 to 0.15.6 crashed the
 * app the moment a wake-up showed (View.setTag with a key that is not an app resource id), on every real phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WakeScreenTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun wake(id: String, sev: String) = Wake(id, sev, "Standup in 5 minutes", "Join the call", "https://example.com/m", "meeting",
        System.currentTimeMillis(), false, emptyList(), emptySet(), "Test PC")

    @Test fun buildsAndRebindsWithoutCrashing() {
        var acked = ""
        val v = WakeScreen.build(ctx, Ringing(wake("w1", "critical"), true), { acked = it }, {}, { _, _ -> })
        assertTrue(v is ScrollView)
        WakeScreen.bind(v, Ringing(wake("w2", "urgent"), true))
        // The buttons answer the wake that is bound now.
        val root = (v as ScrollView).getChildAt(0) as android.view.ViewGroup
        val ack = (0 until root.childCount).map { root.getChildAt(it) }.filterIsInstance<android.widget.Button>().first { it.text == "I'm on it" }
        ack.performClick()
        assertEquals("w2", acked)
    }
}
