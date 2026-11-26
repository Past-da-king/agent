package com.past9.phoneaos

import com.past9.phoneaos.remote.RemoteIds
import com.past9.phoneaos.remote.RemoteRows
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * His message shows the moment he sends it, and the computer's own row for it lands on the same line
 * (26 Nov 2026: nothing showed until a slow computer answered).
 */
class SentEchoTest {
    private val now = 1_795_000_000_000L

    @Test fun theEchoCarriesTheIdTheComputerWillGiveTheRow() {
        val ids = RemoteIds()
        val echo = RemoteRows.sentEcho("abcd1234-0001", "Order the paper", now, ids)
        assertEquals("user", echo.kind)
        assertEquals("Order the paper", echo.text)
        assertEquals("send-abcd1234-0001", ids.remote(echo.id))
        assertTrue(JSONObject(echo.meta).optBoolean("pending"))
    }

    @Test fun theComputersRowReplacesTheEchoInPlaceEvenWithAWrongClock() {
        val ids = RemoteIds()
        val echo = RemoteRows.sentEcho("abcd1234-0001", "Order the paper", now, ids)
        val real = JSONObject().put("id", "send-abcd1234-0001").put("createdAt", now - 35 * 60_000L).put("kind", "user").put("text", "Order the paper").put("meta", "{}")
        val shown = RemoteRows.chat(real, ids)
        assertEquals(echo.id, shown.id)
        assertTrue(!JSONObject(shown.meta).optBoolean("pending"))
    }

    @Test fun twoEchoesStayInSendOrder() {
        val ids = RemoteIds()
        val a = RemoteRows.sentEcho("aaaa1111-0001", "one", now, ids)
        val b = RemoteRows.sentEcho("bbbb2222-0002", "two", now + 1, ids)
        assertTrue(b.id > a.id)
    }
}
