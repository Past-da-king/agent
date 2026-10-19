package com.past9.phoneaos.handoff

import android.content.Context
import com.past9.phoneaos.data.SettingsStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A job the agent handed to one of the user's machines, and how it ended. */
data class Handoff(val seq: Long, val jobId: String, val ref: String?, val title: String, val state: String, val result: String, val host: String, val endedAt: Long) {
    /** What the main agent is told when it lands. */
    fun note(): String {
        val where = host.ifBlank { "your machine" }
        val about = "\"$title\"" + (ref?.let { " (ref $it)" } ?: "")
        return if (state == "done") "[A job you handed to $where finished: $about.]\nResult:\n${result.ifBlank { "(no summary)" }}\nTell the user briefly. If the ref names a routine, task or tracker of theirs, update it from this result."
        else "[A job you handed to $where did not finish: $about, it ${if (state == "stopped") "was stopped" else "failed"}.]\nWhy: ${result.ifBlank { "(no reason given)" }}\nTell the user briefly and say what you would do next."
    }
}

/**
 * The phone's sign-in to a sync service the user runs or chose. Nothing here is built in: with no address saved the phone
 * talks to no one, and every account lives on that user's own service.
 */
class HandoffStore(private val settings: SettingsStore) {
    val url: String? get() = settings.extra("sync_url")?.takeIf { it.isNotBlank() }
    val email: String? get() = settings.extra("sync_email")
    val token: String? get() = settings.secret("sync_token")
    val signedIn: Boolean get() = url != null && token != null
    val device: String get() = settings.extra("sync_device") ?: ("phone-" + java.util.UUID.randomUUID().toString().take(8)).also { settings.setExtra("sync_device", it) }
    var cursor: Long
        get() = settings.extra("handoff_cursor")?.toLongOrNull() ?: 0L
        set(v) = settings.setExtra("handoff_cursor", v.toString())

    fun save(url: String, email: String, token: String) { settings.setExtra("sync_url", url); settings.setExtra("sync_email", email); settings.setSecret("sync_token", token) }
    fun signOut() { settings.setSecret("sync_token", null); settings.setExtra("sync_url", null); settings.setExtra("sync_email", null); settings.setExtra("handoff_cursor", null); settings.setExtra("handoff_seen", null) }

    fun seen(jobId: String) = settings.extra("handoff_seen")?.split('\n')?.contains(jobId) == true
    fun markSeen(jobId: String) { settings.setExtra("handoff_seen", ((settings.extra("handoff_seen")?.split('\n') ?: emptyList()).filter { it.isNotBlank() } + jobId).takeLast(300).joinToString("\n")) }

    companion object {
        /** For a status line, without starting the whole app graph. */
        fun peek(context: Context): String? = context.getSharedPreferences("settings", Context.MODE_PRIVATE).let { p -> p.getString("x_sync_email", null)?.takeIf { p.getString("x_sync_url", null) != null } }
    }
}

class HandoffException(message: String) : Exception(message)

class HandoffClient(private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(75, TimeUnit.SECONDS).build()) {
    private val json = "application/json; charset=utf-8".toMediaType()

    /** Sign in (or create the account) and get this phone's token. */
    fun signIn(url: String, email: String, password: String, create: Boolean): String {
        val base = clean(url)
        val body = JSONObject().put("email", email.trim()).put("password", password).toString().toRequestBody(json)
        call(Request.Builder().url("$base/v1/auth/${if (create) "register" else "login"}").post(body).build()).let { return it.optString("token").ifBlank { throw HandoffException("The service did not give a sign-in token.") } }
    }

    data class Pull(val handoffs: List<Handoff>, val cursor: Long)

    /** Handoff rows after [since]. With [waitSec] the service holds the request open until something arrives. */
    fun pull(url: String, token: String, since: Long, waitSec: Int = 0): Pull {
        val o = call(Request.Builder().url("${clean(url)}/v1/sync/pull?since=$since&entity=handoff&wait=$waitSec").header("Authorization", "Bearer $token").build())
        val rows = o.optJSONArray("changes")
        val list = (0 until (rows?.length() ?: 0)).mapNotNull { i ->
            val r = rows!!.getJSONObject(i); val p = r.optJSONObject("payload") ?: return@mapNotNull null
            if (r.optBoolean("deleted")) return@mapNotNull null
            Handoff(r.optLong("seq"), r.optString("id").ifBlank { p.optString("jobId") }, p.optString("ref").takeIf { it.isNotBlank() && it != "null" },
                p.optString("title").ifBlank { "a job" }, p.optString("state", "done"), p.optString("result"), p.optString("host"), p.optLong("endedAt"))
        }
        return Pull(list, o.optLong("cursor", since))
    }

    private fun clean(url: String): String {
        val u = url.trim().trimEnd('/')
        if (!u.startsWith("https://") && !u.startsWith("http://localhost") && !u.startsWith("http://127.0.0.1")) throw HandoffException("The sync address must start with https:// (your own service, e.g. through Tailscale or a tunnel).")
        return u
    }

    private fun call(req: Request): JSONObject = try {
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val o = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!r.isSuccessful) throw HandoffException(o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: "The sync service said ${r.code}.")
            o
        }
    } catch (e: HandoffException) { throw e } catch (e: java.io.IOException) { throw HandoffException("Couldn't reach the sync service: ${e.message ?: e.javaClass.simpleName}") }
}

/** Picks up what finished and tells the main agent, once each. */
class HandoffInbox(private val store: HandoffStore, private val client: HandoffClient, private val deliver: (String) -> Unit) {
    /** How many new results were handed to the main agent. [waitSec] > 0 is a long wait for the next one. */
    fun check(waitSec: Int = 0): Int {
        val url = store.url ?: return 0; val token = store.token ?: return 0
        val got = client.pull(url, token, store.cursor, waitSec)
        var n = 0
        for (h in got.handoffs) {
            if (store.seen(h.jobId)) continue
            deliver(h.note()); store.markSeen(h.jobId); n++
        }
        if (got.cursor > store.cursor) store.cursor = got.cursor
        return n
    }
}
