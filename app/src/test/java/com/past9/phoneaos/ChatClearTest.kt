package com.past9.phoneaos

import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.agent.AgentRuntime
import com.past9.phoneaos.agent.clearNotice
import com.past9.phoneaos.data.AppDb
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.data.PowerMode
import com.past9.phoneaos.data.Provider
import com.past9.phoneaos.data.SettingsStore
import com.past9.phoneaos.data.TurnRow
import com.past9.phoneaos.remote.NewChatOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Clearing the chat (issue #14, 4 Nov 2026). With a computer paired the chat on screen is that computer's, so the clear
 * is two halves in one order: the computer is asked for a new chat (memory.newChat) first, then this phone's copy goes.
 * Doing it the other way round put the whole old conversation back at the next read, and the next turn still carried it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatClearTest {
    private lateinit var db: AppDb
    private lateinit var settings: SettingsStore

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = AppDb.inMemory(ctx); settings = SettingsStore(ctx)
        settings.setMode(PowerMode.API_KEY); settings.setProvider(Provider.ANTHROPIC); settings.setApiKey(Provider.ANTHROPIC, "sk-test")
        settings.setUserName("Sam")
    }

    private fun runtime() = AgentRuntime(ApplicationProvider.getApplicationContext(), db, settings,
        CoroutineScope(SupervisorJob() + Dispatchers.IO), FakePhone(), { null })

    /** A chat with something in it, both threads' turns, and the ids a turn resumes from. */
    private suspend fun seed() {
        db.chat().insert(ChatItem(kind = "user", text = "Where is my parcel?"))
        db.chat().insert(ChatItem(kind = "agent", text = "At the depot since Tuesday."))
        db.chat().insertTurn(TurnRow(thread = "main", json = "{}"))
        db.chat().insertTurn(TurnRow(thread = "branch-1", json = "{}"))
        settings.setExtra("claude_session", "sess-1")
        settings.setExtra("conversation_id", "conv-1")
        settings.setExtra("branch_pending", "1")
    }

    private fun count(table: String): Int = db.query("SELECT COUNT(*) FROM $table", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    @Test fun theComputerIsAskedForANewChatAndThenThePhoneIsCleared() = runBlocking {
        seed()
        val rt = runtime()
        val asked = mutableListOf<String>()
        rt.newChatOnComputer = { chat -> asked += chat; NewChatOutcome.STARTED }
        rt.clearChat()
        assertEquals("the computer is asked for a new chat, main, before the phone is cleared", listOf("main"), asked)
        assertEquals("no rows left", 0, count("chat_items"))
        assertEquals("no turns left, in either thread", 0, count("turns"))
        assertNull("nothing to resume", settings.extra("claude_session"))
        assertNull("nothing to resume", settings.extra("conversation_id"))
        assertNull("no branch left waiting", settings.extra("branch_pending"))
    }

    @Test fun anOlderComputerIsClearedHereAndSaidSoPlainly() = runBlocking {
        seed()
        val rt = runtime()
        rt.newChatOnComputer = { NewChatOutcome.COMPUTER_OLDER }
        rt.clearChat()
        assertEquals("the phone is still cleared", 0, count("turns"))
        val rows = db.chat().all().first()
        assertEquals("one line, saying so", 1, rows.size)
        assertTrue(rows.single().text, rows.single().text.contains("older"))
        assertTrue("it says the computer's copy is still there", rows.single().text.contains("computer"))
    }

    @Test fun aComputerItCouldNotReachIsNotBlamedOnAnOldApp() = runBlocking {
        seed()
        val rt = runtime()
        rt.newChatOnComputer = { NewChatOutcome.UNREACHABLE }
        rt.clearChat()
        val line = db.chat().all().first().single().text
        assertTrue(line, line.contains("could not be reached"))
        assertTrue(line, !line.contains("older"))
        assertEquals("the phone's own copy is gone either way", 0, count("turns"))
    }

    @Test fun aClearThatFullyHappenedSaysNothing() = runBlocking {
        seed()
        val rt = runtime()
        rt.newChatOnComputer = { NewChatOutcome.NOT_LINKED }
        rt.clearChat()
        assertEquals("no notice when there was nothing more to clear", 0, count("chat_items"))
    }

    @Test fun theTwoFailuresDoNotShareOneMessage() {
        assertNull(clearNotice(NewChatOutcome.STARTED))
        assertNull(clearNotice(NewChatOutcome.NOT_LINKED))
        val older = clearNotice(NewChatOutcome.COMPUTER_OLDER).orEmpty()
        val gone = clearNotice(NewChatOutcome.UNREACHABLE).orEmpty()
        assertTrue(older, older.isNotEmpty())
        assertTrue(gone, gone.isNotEmpty())
        assertTrue("one needs the app updated, the other a link back", older != gone)
    }

    @Test fun aFailedAskStillClearsThePhoneAndNeverThrows() = runBlocking {
        val rt = runtime()
        rt.newChatOnComputer = { throw IllegalStateException("Not connected to a computer right now.") }
        db.chat().insert(ChatItem(kind = "user", text = "still here before"))
        db.chat().insertTurn(TurnRow(thread = "main", json = "{}"))
        val done = runCatching { rt.clearChat() }
        assertTrue("the clear itself never throws", done.isSuccess)
        assertEquals("the phone is cleared anyway", 0, count("turns"))
        val line = db.chat().all().first().single().text
        assertTrue(line, line.contains("could not be reached"))
    }
}
