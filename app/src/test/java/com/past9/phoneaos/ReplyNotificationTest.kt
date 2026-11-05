package com.past9.phoneaos

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.agent.AgentRuntime
import com.past9.phoneaos.data.AppDb
import com.past9.phoneaos.data.PowerMode
import com.past9.phoneaos.data.Provider
import com.past9.phoneaos.data.SettingsStore
import com.past9.phoneaos.system.AndroidPhone
import com.past9.phoneaos.system.ChatOnScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Issue #15, 5 Nov 2026: a background turn ended in silence. The "working" notification went away with the service that
 * owned it and nothing else was posted, so the reply waited in the chat until the user happened to open the app.
 *
 * The rules now, one test each:
 * 1. a turn that ends while the chat is NOT on screen leaves ONE notification carrying the reply, on the messages
 *    channel, tapping it opens the conversation, and it goes away by itself when tapped;
 * 2. nothing at all when the chat is being watched (that is noise), nor when the app is only open on another screen;
 * 3. nothing, quietly, without the notification permission (Android 13+);
 * 4. a second turn UPDATES that notification instead of stacking another one beside it;
 * 5. the working notification going away does not take the reply with it;
 * 6. a turn that chose to say nothing (HOLD) notifies nobody.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReplyNotificationTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private val phone by lazy { AndroidPhone(app) }
    private lateinit var db: AppDb
    private lateinit var settings: SettingsStore

    @Before fun setUp() {
        ChatOnScreen.resumed = false
        ChatOnScreen.route = "home"
        nm.cancelAll()
        // Robolectric denies every runtime permission by default; the phone this app is installed on has granted it
        // (the app targets 28, so Android 13+ hands POST_NOTIFICATIONS over without asking). The one test that cares
        // denies it again itself.
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        db = AppDb.inMemory(app); settings = SettingsStore(app)
        settings.setMode(PowerMode.API_KEY); settings.setProvider(Provider.ANTHROPIC); settings.setApiKey(Provider.ANTHROPIC, "sk-test"); settings.setUserName("Sam")
    }

    private fun runtime(provider: ScriptedProvider) =
        AgentRuntime(app, db, settings, CoroutineScope(SupervisorJob() + Dispatchers.IO), phone, { provider })

    private fun posted(): List<Notification> = shadowOf(nm).allNotifications.toList()
    private fun reply() = shadowOf(nm).getNotification(AndroidPhone.NID_REPLY)

    /** Everything one notification says on screen: the MessagingStyle words and the plain title and text. */
    private fun said(n: Notification): String {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val words = style?.messages?.joinToString(" ") { it.text?.toString().orEmpty() }.orEmpty()
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        return "$words $title $text"
    }

    @Test fun aFinishedTurnLeavesOneNotificationCarryingTheReply() = runBlocking {
        val rt = runtime(ScriptedProvider(say("The tender is submitted. Reference TND-2026-0031.")))
        // Nobody is looking at the chat: the app is in a pocket and the turn finishes while it is away.
        ChatOnScreen.resumed = false
        rt.send("submit the tender")
        withTimeout(20_000) { rt.awaitIdle() }

        val n = reply()
        assertNotNull("the reply is announced", n)
        assertEquals("on the messages channel, like every message from the agent", AndroidPhone.CH_MESSAGES, n!!.channelId)
        assertTrue(said(n), said(n).contains("The tender is submitted"))
        assertEquals("one notification, not one per step", 1, posted().size)
        assertTrue("tapping it clears it", n.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test fun nothingIsPostedWhileTheChatIsOnScreen() {
        ChatOnScreen.resumed = true; ChatOnScreen.route = "chat"
        phone.replyPosted("Here is the answer you are already reading.")
        assertEquals(0, posted().size)
    }

    @Test fun theAppOpenOnAnotherScreenIsNotWatchingTheChat() {
        ChatOnScreen.resumed = true; ChatOnScreen.route = "settings"
        phone.replyPosted("Done: three videos are up.")
        assertNotNull("he is in the app but not looking at the chat", reply())
    }

    @Test fun nothingIsPostedWithoutTheNotificationPermission() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ChatOnScreen.resumed = false
        phone.replyPosted("Done.")
        assertEquals(0, posted().size)
    }

    @Test fun aSecondTurnUpdatesTheSameNotificationInsteadOfStackingAnother() = runBlocking {
        val rt = runtime(ScriptedProvider(say("First: the invoice is out."), say("Second: and the statement too.")))
        ChatOnScreen.resumed = false
        rt.send("send the invoice")
        withTimeout(20_000) { rt.awaitIdle() }
        assertTrue(said(reply()!!).contains("invoice"))

        rt.send("and the statement")
        withTimeout(20_000) { rt.awaitIdle() }

        assertEquals("still one line in the shade", 1, posted().size)
        val now = said(reply()!!)
        assertTrue(now, now.contains("statement"))
        assertFalse("the newer reply replaced the older one", now.contains("First: the invoice"))
    }

    @Test fun theWorkingNotificationGoingAwayLeavesTheReply() {
        // WorkService's own notification is id 42 on its own channel; stopping the service cancels that id and nothing else.
        nm.createNotificationChannel(NotificationChannel(AndroidPhone.CH_WORK, "Agent working", NotificationManager.IMPORTANCE_MIN))
        nm.notify(42, NotificationCompat.Builder(app, AndroidPhone.CH_WORK)
            .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Working on it").setOngoing(true).build())
        ChatOnScreen.resumed = false
        phone.replyPosted("All done: the pack is on your computer.")

        nm.cancel(42)
        assertNull("the working notification is gone with the job", shadowOf(nm).getNotification(42))
        assertNotNull("the reply stayed", reply())
        assertEquals(1, posted().size)
    }

    @Test fun aTurnThatChoseToSayNothingNotifiesNobody() = runBlocking {
        val rt = runtime(ScriptedProvider(say("HOLD")))
        ChatOnScreen.resumed = false
        rt.send("anything new?")
        withTimeout(20_000) { rt.awaitIdle() }
        assertEquals(0, posted().size)
    }

    @Test fun aChatOpenedUnderTheReplyTakesItOutOfTheShade() {
        ChatOnScreen.resumed = false
        phone.replyPosted("Two things need you.")
        assertNotNull(reply())
        AndroidPhone.clearReply(app)
        assertNull(reply())
    }
}
