package com.past9.phoneaos

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.remote.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnswerOrderStatusTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val pc = BrainRef("pc", "Desk", emptyList(), "", 0)
    private val server = BrainRef("server", "Server", emptyList(), "", 1)

    @Test fun statusUsesResourcesAndReportsAnActiveTurn() {
        assertEquals(app.getString(R.string.answer_order_none), BrainChooser.answeringLine(app, listOf(pc, server), null))
        assertEquals("Desk is answering: first in order.", BrainChooser.answeringLine(app, listOf(pc, server), pc.id))
        assertTrue(BrainChooser.answeringLine(app, listOf(pc, server), server.id, setOf(pc.id), true).contains("finishing this turn"))
        assertTrue(BrainChooser.answeringLine(app, listOf(pc, server), server.id, setOf(pc.id), false).contains("handing back in answer order"))
        assertTrue(BrainChooser.answeringLine(app, listOf(pc, server.copy(priority = 0)), server.id).contains(app.getString(R.string.answer_order_tied)))
    }
    @Test fun translatedResourcesSupplyEveryPartOfTheAnsweringLine() {
        val used = mutableSetOf<Int>()
        @Suppress("DEPRECATION")
        val translated = object : android.content.res.Resources(app.assets, app.resources.displayMetrics, app.resources.configuration) {
            override fun getString(id: Int): String { used += id; return "translated:$id" }
            override fun getString(id: Int, vararg formatArgs: Any?): String { used += id; return "translated:$id[" + formatArgs.joinToString() + "]" }
        }
        val context = object : android.content.ContextWrapper(app) {
            override fun getResources(): android.content.res.Resources = translated
        }
        val text = BrainChooser.answeringLine(context, listOf(pc.copy(pairedAt = 1), server.copy(priority = 0, pairedAt = 2)), server.id, setOf(pc.id), true)
        assertTrue(text.startsWith("translated:"))
        assertTrue(used.containsAll(setOf(R.string.answer_order_answering, R.string.answer_order_finishing, R.string.answer_order_tied)))
        used.clear()
        assertTrue(BrainChooser.answeringLine(context, emptyList(), null).startsWith("translated:"))
        assertEquals(setOf(R.string.answer_order_none), used)
    }

    @Test fun storageWarningDoesNotRequireTheWordDisk() {
        val text = "This device is not answering: app data drive has only 0.3 GB free. New messages use another device until space is freed."
        assertEquals(text, NodeStatusCache.warningFrom(JSONObject().put("answering", text)))
        assertEquals("Storage is critical", NodeStatusCache.warningFrom(JSONObject().put("availability", "Storage is critical")))
        assertEquals("", NodeStatusCache.warningFrom(JSONObject().put("answering", "Desk is answering: first in order.")))
    }
    @Test fun optionalStatusCachesSuccessAndDoesNotRepeatUnsupportedCalls() = runBlocking {
        var time = 1L
        val cache = NodeStatusCache { time }
        var attempts = 0
        repeat(3) { assertEquals("", cache.warning("old") { attempts++; throw RpcException("Unknown RPC devices.status") }) }
        time += 120_000
        assertEquals("", cache.warning("old") { attempts++; JSONObject() })
        assertEquals(1, attempts)
        val text = "Low storage space"
        repeat(3) { assertEquals(text, cache.warning("new") { attempts++; JSONObject().put("answering", text) }) }
        assertEquals(2, attempts)
    }
    @Test fun aNodeThatNeverAnswersTheOptionalMethodDoesNotBreakOrDelayEveryRefresh() = runBlocking {
        val cache = NodeStatusCache { 1L }
        var attempts = 0
        repeat(3) {
            assertEquals("", cache.warning("silent") { attempts++; withTimeout(1) { delay(100); JSONObject() } })
        }
        assertEquals(1, attempts)
    }

    @Test fun failingStatusBacksOffButRetriesAndCancellationIsNotCached() = runBlocking {
        var time = 1L
        val cache = NodeStatusCache { time }
        var attempts = 0
        repeat(3) { cache.warning("pc") { attempts++; throw RpcException("transport failed") } }
        assertEquals(1, attempts)
        time += 60_001
        cache.warning("pc") { attempts++; JSONObject() }
        assertEquals(2, attempts)
        try { cache.warning("cancel") { throw CancellationException("stopped") }; fail() } catch (_: CancellationException) {}
        cache.warning("cancel") { attempts++; JSONObject() }
        assertEquals(3, attempts)
    }
}
