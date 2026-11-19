package com.past9.phoneaos.voice

import android.content.Context
import android.util.Base64
import com.past9.phoneaos.App
import com.past9.phoneaos.remote.RemoteHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** The phone's one take queue (instant send, voice/Takes.kt), built on first use. */
object Takes {
    @Volatile private var flow: TakeFlow? = null
    private val watching = AtomicBoolean(false)

    fun of(context: Context): TakeFlow = flow ?: synchronized(this) { flow ?: build(context.applicationContext).also { flow = it } }

    /** At app start: takes that were waiting when the app closed carry on, and again every time a link comes up. */
    fun start(context: Context) {
        val f = of(context)
        f.resume()
        if (watching.getAndSet(true)) return
        val g = App.graph(context)
        g.scope.launch {
            RemoteHub.session.collect { s ->
                if (s != null) f.resume()
            }
        }
    }

    /**
     * The speech model for a pass written on this phone: its own setting (Settings extra [SpeechModel.SETTING_KEY]), else the
     * default, the same one the computer starts with. When linked the computer runs the pass with its own setting.
     */
    fun model(context: Context): String =
        App.graph(context).settings.extra(SpeechModel.SETTING_KEY)?.trim().takeIf { !it.isNullOrEmpty() } ?: SpeechModel.DEFAULT_MODEL

    private const val LINK_WAIT_MS = 8_000L

    private fun build(ctx: Context): TakeFlow {
        val g = App.graph(ctx)
        val env = object : TakeEnv {
            override fun linked() = RemoteHub.session.value != null || RemoteHub.link.value?.on == true

            override suspend fun handOver(take: Take, pcm: ByteArray): Boolean {
                val s = RemoteHub.session.value ?: withTimeoutOrNull(LINK_WAIT_MS) { RemoteHub.session.filterNotNull().first() } ?: return false
                // voice.takeAudio (agent-desktop-app docs/dictation-whole-audio.md): the whole take for this recording id; the computer
                // runs ONE pass, replaces the message in place and releases the agent's turn. Idempotent by id.
                val body = JSONObject().put("id", take.id).put("caption", take.live).put("audio", JSONObject().put("\$b64", Base64.encodeToString(pcm, Base64.NO_WRAP)))
                    .put("names", JSONArray(PieceTranscriber.ownNames(ctx))).put("languages", JSONArray(SpeechModel.languages()))
                take.chat?.let { body.put("chat", it) }
                val r = s.call("voice.takeAudio", listOf(body), 120_000) as? JSONObject ?: throw IOException("Your computer didn't answer.")
                if (r.optBoolean("ok")) return true
                throw IOException(r.optString("error").ifBlank { "Your computer couldn't take that." })
            }

            override suspend fun writeHere(pcm: ByteArray): String? {
                val key = g.settings.voiceKey("gemini") ?: return null
                return withContext(Dispatchers.IO) { SpeechModel.transcribe(key, pcm, PieceTranscriber.ownNames(ctx), model = model(ctx)) }
            }

            override suspend fun replaceHere(take: Take, text: String) {
                val row = g.db.chat().byMeta("%\"id\":\"${take.id}\"%") ?: return
                val meta = JSONObject(row.meta.ifBlank { "{}" })
                val d = meta.optJSONObject("dictation") ?: JSONObject().put("id", take.id)
                if (d.optString("state") == "done") return
                meta.put("dictation", d.put("state", "done"))
                g.db.chat().update(row.copy(text = text, meta = meta.toString()))
            }

            override suspend fun retryRemote(id: String): Boolean {
                val s = RemoteHub.session.value ?: return false
                val r = s.call("voice.takeAudio", listOf(JSONObject().put("id", id).put("retry", true)), 30_000) as? JSONObject
                return r?.optBoolean("ok") == true
            }
        }
        return TakeFlow(TakeQueue(File(ctx.filesDir, "takes")), g.scope, env)
    }
}

/** Carries the id of the take through a send, from the message box to wherever the message is delivered. */
class TakeContext(val id: String) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<TakeContext>
}
