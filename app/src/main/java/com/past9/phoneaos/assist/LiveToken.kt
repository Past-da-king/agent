package com.past9.phoneaos.assist

import android.content.Context
import com.past9.phoneaos.App
import com.past9.phoneaos.remote.RemoteHub
import com.past9.phoneaos.voice.LiveAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How a live start gets its way into Gemini Live: the linked computer's one-use token, or this phone's own Google key.
 * The token is asked for as early as the sheet starts to open ([warm]), so by the time the microphone is up it is
 * usually already here, instead of the request starting only after the sheet is on screen.
 */
object LiveToken {
    /** A one-use token is good for a short while only: an older prepared one is never used. */
    const val TTL_MS = 40_000L
    /** On a cold start the link to the computer may still be opening: wait for it this long before using the phone's own key. */
    const val LINK_WAIT_MS = 4_000L

    private val prepared = FreshOnce<Deferred<LiveAuth.Token>>(TTL_MS)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The way in for one start, asked for now. */
    suspend fun mint(app: Context): LiveAuth.Token {
        RemoteHub.init(app)
        val key = App.graph(app).settings.voiceKey("gemini")
        var r = RemoteHub.session.value
        // Cold start: the link is on but not up yet. Wait for it rather than falling through to "no key".
        if (r == null && RemoteHub.link.value?.on == true) r = withTimeoutOrNull(LINK_WAIT_MS) { RemoteHub.session.filterNotNull().first() }
        if (r != null) {
            val t = try { LiveAuth.parse(r.call("voice.liveToken", emptyList(), 20_000)) } catch (e: Exception) {
                LiveAuth.Token.No(if (LiveAuth.isOldComputer(e.message)) LiveAuth.OLD_COMPUTER else e.message ?: "Your computer didn't answer.", false)
            }
            if (t is LiveAuth.Token.Ok || key == null) return t
        }
        if (key == null) return LiveAuth.Token.No("Live voice runs on Gemini Live. Add a Google (Gemini) key in Settings, Voice, or link a computer that has one.", true)
        return LiveAuth.Token.Ok(LiveAuth.keyUrl(key), LiveAssistant.DEFAULT_MODEL, "")
    }

    /** The sheet is starting to open: get the token now, in parallel. Does nothing when Live voice is off or one is already on its way. */
    fun warm(app: Context) {
        if (!AssistPrefs.liveVoice(app) || prepared.fresh()) return
        prepared.put(scope.async { mint(app) })
    }

    /** The prepared token if there is a fresh good one, else a new one. */
    suspend fun take(app: Context): LiveAuth.Token {
        val early = prepared.take()?.let { runCatching { it.await() }.getOrNull() }
        return if (early is LiveAuth.Token.Ok) early else mint(app)
    }
}
