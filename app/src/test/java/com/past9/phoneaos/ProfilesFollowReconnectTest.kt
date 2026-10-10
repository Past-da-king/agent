package com.past9.phoneaos

import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.data.AccountRef
import com.past9.phoneaos.data.AccountRules
import com.past9.phoneaos.data.ProfileStore
import com.past9.phoneaos.data.Rule
import com.past9.phoneaos.tools.AppGuard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reconnecting an app gives its account a new id: profiles follow it instead of locking helpers out of the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfilesFollowReconnectTest {
    private val store get() = ProfileStore(ApplicationProvider.getApplicationContext())
    private val old = AccountRef("slack_old-one", "slack", "team")
    private val new = AccountRef("slack_new-one", "slack", "team")

    @Test fun aReconnectedAccountTakesTheOldOnesPlaceAndRules() {
        val s = store
        val p = s.create("Work", listOf(old, AccountRef("gmail_a", "gmail", "me@example.com")))
        s.setRules(old.id, AccountRules(read = Rule.ALLOW, change = Rule.NEVER))
        assertTrue(s.followReconnected("slack", listOf(new)))
        assertEquals(listOf("gmail_a", "slack_new-one"), s.find(p.id)!!.accounts.map { it.id }.sorted())
        assertEquals(Rule.NEVER, s.rules(new.id).change)
        assertFalse(s.followReconnected("slack", listOf(new)))   // nothing stale any more
    }

    @Test fun withSeveralNewAccountsNothingIsGuessed() {
        val s = store
        val p = s.create("Work", listOf(old))
        assertFalse(s.followReconnected("slack", listOf(new, AccountRef("slack_other", "slack", "other"))))
        assertEquals(listOf(old.id), s.find(p.id)!!.accounts.map { it.id })
        // An account another profile already has isn't a candidate.
        s.create("Home", listOf(AccountRef("slack_other", "slack", "other")))
        assertTrue(s.followReconnected("slack", listOf(new, AccountRef("slack_other", "slack", "other"))))
        assertEquals(listOf(new.id), s.find(p.id)!!.accounts.map { it.id })
    }

    @Test fun aHelperIsLetInOnTheFirstTryAfterAReconnect() = runBlocking {
        val s = store
        val p = s.create("Work", listOf(old))
        // What the runtime does: looking an app's accounts up moves its profiles onto a reconnected account.
        val guard = AppGuard({ s.rules(it) }, { slugs -> listOf(new).filter { it.slug in slugs }.also { s.followReconnected("slack", it) } }, { s.find(p.id) })
        val v = guard.check(ProfilesTest.Ctx("helper-1"), listOf("SLACK_LIST_CHANNELS"), null, "")
        assertNull(v.refuse)
        assertEquals(new.id, v.account)
    }
}
