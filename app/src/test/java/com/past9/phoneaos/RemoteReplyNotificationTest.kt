package com.past9.phoneaos

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.data.AppDb
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.remote.Link
import com.past9.phoneaos.remote.RemoteSession
import com.past9.phoneaos.system.AndroidPhone
import com.past9.phoneaos.system.ChatOnScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Issue #15 on a PAIRED phone (added 9 Nov 2026). When the computer runs the turn, this phone's own turn never ends:
 * the working notification went away with the computer's run and nothing was posted for the reply, which then sat in
 * the mirrored chat. These pin that the mirrored turn's own words reach him, once per turn, and never while the chat
 * is the screen in front of him.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteReplyNotificationTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private lateinit var db: AppDb
    private lateinit var session: RemoteSession

    @Before fun setUp() {
        ChatOnScreen.resumed = false
        ChatOnScreen.route = "home"
        nm.cancelAll()
        // Robolectric denies every runtime permission by default; the phone this app runs on has granted it.
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        db = AppDb.inMemory(app)
        session = RemoteSession(app, Link("mesh-1", "dev-1", "pass", "Desk", emptyList(), 0), db)
    }

    private fun reply(): Notification? = shadowOf(nm).getNotification(AndroidPhone.NID_REPLY)
    private fun said(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

    /** The computer's row as it lands in the mirrored chat (RemoteRows puts it there; the text is what matters here). */
    private suspend fun said_(text: String) { db.chat().insert(ChatItem(kind = "agent", text = text)) }

    @Test fun aTurnTheComputerRanTellsThePhone() = runBlocking {
        said_("Yesterday's answer, already read.")
        session.runEvent("run_started")
        session.runEvent("run_finished")
        said_("Three videos are up and the invoice is out.")
        session.tellReply()

        val n = reply()
        assertNotNull("the reply is announced on the phone that carries him", n)
        assertEquals("on the messages channel, like every message from the agent", AndroidPhone.CH_MESSAGES, n!!.channelId)
        assertTrue(said(n), said(n).contains("Three videos are up"))
    }

    @Test fun whatTheChatAlreadyHeldIsNotNews() = runBlocking {
        said_("Yesterday's answer, already read.")
        session.runEvent("run_started")
        session.runEvent("run_finished")
        session.tellReply()
        assertNull("a turn that said nothing announces nothing", reply())
    }

    @Test fun nothingWhileTheChatIsOnScreen() = runBlocking {
        ChatOnScreen.resumed = true; ChatOnScreen.route = "chat"
        said_("Yesterday's answer.")
        session.runEvent("run_started")
        said_("Here is the answer you are already reading.")
        session.runEvent("run_finished")
        session.tellReply()
        assertNull("he is watching the chat", reply())
    }

    @Test fun aSecondTurnUpdatesTheSameLine() = runBlocking {
        session.runEvent("run_started"); said_("First: the invoice is out."); session.runEvent("run_finished"); session.tellReply()
        session.runEvent("run_started"); said_("Second: and the statement too."); session.runEvent("run_finished"); session.tellReply()

        val n = reply()
        assertNotNull(n)
        assertTrue(said(n!!), said(n).contains("Second"))
        assertEquals("one line in the shade, not one per turn", 1, shadowOf(nm).allNotifications.size)
    }
}
