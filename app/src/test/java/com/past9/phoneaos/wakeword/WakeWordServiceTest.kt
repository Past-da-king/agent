package com.past9.phoneaos.wakeword

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.App
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** The settings, the phrase following the agent's name, and the service's notification, on a pretend phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WakeWordServiceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val store get() = WakeWord.store(app)
    private val agent get() = App.graph(app).settings
    private val nm get() = app.getSystemService(NotificationManager::class.java)

    @Before fun clean() {
        store.setEnabled(false); store.setPhrase(""); store.setSensitivity(Sensitivity.MEDIUM); store.setLearned("", emptyList())
        store.setOnlyCharging(false); store.setPauseOnSaver(true); store.setPauseLowBattery(false)
        agent.setAgentName("Nova")
    }
    @After fun tidy() { MicUse.held.value.forEach { MicUse.release(it) }; MicUse.onHold = null }

    private fun title() = shadowOf(nm).getNotification(WakeWordService.ID)?.let { shadowOf(it).contentTitle?.toString() }
    private fun text() = shadowOf(nm).getNotification(WakeWordService.ID)?.let { shadowOf(it).contentText?.toString() }
    private fun actions() = shadowOf(nm).getNotification(WakeWordService.ID)?.actions?.map { it.title.toString() }.orEmpty()
    private fun idle() = ShadowLooper.idleMainLooper()

    // ---- settings ----

    @Test fun theDefaultsAreOffAndCareful() {
        val s = WakeWordStore(app).state.value
        assertFalse(s.enabled); assertEquals("", s.customPhrase); assertEquals(Sensitivity.MEDIUM, s.sensitivity)
        assertTrue(s.pauseOnSaver); assertFalse(s.pauseLowBattery); assertFalse(s.onlyCharging); assertTrue(s.learned.isEmpty())
    }

    @Test fun settingsSurviveAndAreSeenAtOnce() {
        store.setSensitivity(Sensitivity.HIGH); store.setOnlyCharging(true); store.setPhrase("  Okay Yuki ")
        assertEquals(Sensitivity.HIGH, store.state.value.sensitivity)
        val again = WakeWordStore(app).state.value          // as after the app restarts
        assertEquals(Sensitivity.HIGH, again.sensitivity); assertTrue(again.onlyCharging); assertEquals("Okay Yuki", again.customPhrase)
    }

    @Test fun thePhraseIsTheAgentsNameAsItIsThisInstant() {
        assertEquals("Hey Nova", WakeWord.phrase(app))
        val nova = WakeWord.keywords(app)
        agent.setAgentName("Thandi")                         // renamed: no restart, no rebuild
        assertEquals("Hey Thandi", WakeWord.phrase(app))
        val thandi = WakeWord.keywords(app)
        assertEquals(1, nova.size); assertEquals(1, thandi.size)
        assertTrue(nova[0].startsWith("▁HE Y ▁NO V A ")); assertTrue(thandi[0].startsWith("▁HE Y ▁THAN DI "))
        store.setPhrase("Okay Yuki")
        assertEquals("Okay Yuki", WakeWord.phrase(app))
        store.setPhrase("")
        assertEquals("Hey Thandi", WakeWord.phrase(app))
    }

    @Test fun whatWasLearnedBelongsToThePhraseItWasLearnedFor() {
        val heard = listOf(listOf("▁HE", "Y", "▁TA", "ND", "Y"))
        store.setLearned("Hey Thandi", heard)
        agent.setAgentName("Thandi")
        assertEquals(2, WakeWord.keywords(app).size)
        // A different name: the learned sound is not used for it, and comes back if the name does.
        agent.setAgentName("Nova"); assertEquals(1, WakeWord.keywords(app).size)
        agent.setAgentName("thandi"); assertEquals(2, WakeWord.keywords(app).size)
        store.setLearned("Hey Thandi", emptyList()); assertEquals(1, WakeWord.keywords(app).size)
    }

    @Test fun sensitivityChangesTheKeywordLine() {
        val lines = Sensitivity.entries.map { store.setSensitivity(it); WakeWord.keywords(app).single() }
        assertEquals(3, lines.toSet().size)
    }

    @Test fun aPhraseTooShortToListenForGivesNoKeywords() {
        store.setPhrase("No")
        assertTrue(WakeWord.keywords(app).isEmpty())
    }

    // ---- the service and its notification ----

    @Test fun switchedOffTheServiceStopsItself() {
        val c = Robolectric.buildService(WakeWordService::class.java).create().startCommand(0, 1); idle()
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
        assertEquals(WakeState.Off, WakeWord.live.value.state)
    }

    @Test fun withoutTheMicrophonePermissionItSaysSoAndDoesNotListen() {
        store.setEnabled(true)
        val c = Robolectric.buildService(WakeWordService::class.java).create().startCommand(0, 1); idle()
        assertEquals("Not listening for \"Hey Nova\"", title())
        assertEquals(WakePause.NO_MIC_PERMISSION.line, text())
        assertEquals(WakeState.Paused(WakePause.NO_MIC_PERMISSION), WakeWord.live.value.state)
        assertFalse(shadowOf(c.get()).isStoppedBySelf)
        c.destroy()
        assertEquals(WakeState.Off, WakeWord.live.value.state)
    }

    @Test fun theNotificationFollowsTheAgentsNameAndThePauseButton() {
        store.setEnabled(true)
        val c = Robolectric.buildService(WakeWordService::class.java).create().startCommand(0, 1); idle()
        assertEquals(listOf("Pause"), actions())
        agent.setAgentName("Yuki"); idle()
        assertEquals("Not listening for \"Hey Yuki\"", title())
        // Pause in the notification: it stays paused, says so, and offers Resume.
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        c.get().onStartCommand(Intent(app, WakeWordService::class.java).setAction(WakeWordService.ACTION_PAUSE), 0, 2); idle()
        assertTrue(store.state.value.userPaused)
        assertEquals(WakePause.USER.line, text()); assertEquals(listOf("Resume"), actions())
        assertEquals(WakeState.Paused(WakePause.USER), WakeWord.live.value.state)
        c.destroy()
    }

    @Test fun itStandsAsideWhileTheAppUsesTheMicrophoneAndComesBack() {
        store.setEnabled(true)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        MicUse.hold("call")                                  // a voice call is already up
        val c = Robolectric.buildService(WakeWordService::class.java).create().startCommand(0, 1); idle()
        assertEquals(WakeState.Paused(WakePause.APP_MIC), WakeWord.live.value.state)
        assertEquals(WakePause.APP_MIC.line, text())
        assertNotNull("the service is the one told to let go", MicUse.onHold)
        MicUse.release("call"); idle()
        // The call is over: it goes straight back to listening (on this pretend phone the model then fails to load, and it says that).
        val after = WakeWord.live.value.state
        assertTrue("after the call: $after", after == WakeState.Listening || after == WakeState.Paused(WakePause.ENGINE))
        c.destroy()
        assertNull(MicUse.onHold)
    }

    @Test fun aHeardPhraseOpensTheAssistantByItsAgreedAction() {
        val i = WakeTarget.intent(app)
        // Nothing in this build answers the assist action yet, so it falls back to the app's own voice screen: never to nothing.
        assertEquals("wake_word", i.getStringExtra("source"))
        assertTrue(i.action == WakeTarget.ACTION || (i.component?.className == com.past9.phoneaos.MainActivity::class.java.name && i.getStringExtra("open") == "call"))
        assertTrue(i.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals("com.past9.phoneaos.action.ASSIST", WakeTarget.ACTION)
    }
}
