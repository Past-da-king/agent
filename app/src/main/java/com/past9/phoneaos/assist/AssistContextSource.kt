package com.past9.phoneaos.assist

import android.content.Context
import com.past9.phoneaos.Graph
import com.past9.phoneaos.remote.LiveCard
import com.past9.phoneaos.remote.RemoteHub
import com.past9.phoneaos.remote.RemoteSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where the assistant's context comes from, in both modes. On a phone with its own agent: its own chat rows, status and
 * helper rows. Linked to a computer (remote mode): the mirrored chat rows (they hold every open question and approval,
 * from the main agent, helpers and project agents), the computer's live helper and project cards, and its status, all
 * already on the phone over the existing link. Nothing is fetched that the user's agent does not already hold, and
 * nothing here needs a new call on the computer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object AssistContextSource {
    /** A cold start: the link to the computer may still be opening. Wait for it this long rather than read this phone's empty chat. */
    const val LINK_WAIT_MS = 3_000L
    /** The computer's helper cards may still be on their way once the link is up. */
    const val CARDS_WAIT_MS = 1_200L

    /**
     * The context right now, read once (the sheet or the call is opening). Linked: it waits for the link, then for the
     * computer's cards, then falls back to the computer's own list of who is at work (voice.callTargets), and only if
     * none of that answers does it say the agents are unknown. It is never silently empty.
     */
    suspend fun snapshot(context: Context, g: Graph, refs: AskRefs = AskRefs(), now: Long = System.currentTimeMillis()): AssistContext {
        var r: RemoteSession? = RemoteHub.session.value
        val linkOn = RemoteHub.link.value?.on == true
        if (r == null && linkOn) r = withTimeoutOrNull(LINK_WAIT_MS) { RemoteHub.session.filterNotNull().first() }
        val rows = (r?.db ?: g.db).chat().all().first()
        val st = (r?.status ?: g.runtime.status).value
        var cards = r?.cards?.value
        if (r != null && cards == null) cards = withTimeoutOrNull(CARDS_WAIT_MS) { r.cards.filterNotNull().first() }
        var extra: List<CtxAgent>? = null
        if (r != null && cards == null) extra = runCatching { AssistContextBuilder.fromTargets(r.call("voice.callTargets", emptyList(), 4_000) as? JSONArray) }.getOrNull()
        // On its own brain the helper rows are the list; linked, the list is known only if the computer answered one way or the other.
        val known = (r == null && !linkOn) || cards != null || extra != null
        return AssistContextBuilder.build(rows, cards, NotifyLog.recent(context, now), st.working, st.label, now, refs, name(g), extra, known)
    }

    /** The context as it changes: the chat, the cards or the status moving rebuilds it. Same inputs as [snapshot]. */
    fun updates(context: Context, g: Graph, refs: AskRefs = AskRefs()): Flow<AssistContext> {
        val rows = RemoteHub.session.flatMapLatest { (it?.db ?: g.db).chat().all() }
        val status = RemoteHub.session.flatMapLatest { it?.status ?: g.runtime.status }
        val cards: Flow<List<LiveCard>?> = RemoteHub.session.flatMapLatest { it?.cards ?: flowOf(null) }
        return combine(rows, status, cards) { r, s, c ->
            val linkedNoCards = c == null && RemoteHub.link.value?.on == true
            AssistContextBuilder.build(r, c, NotifyLog.recent(context, System.currentTimeMillis()), s.working, s.label, System.currentTimeMillis(), refs, name(g), null, !linkedNoCards)
        }
    }

    private fun name(g: Graph): String = g.settings.state.value.agentName.takeIf { it.isNotBlank() && it != "Your agent" }.orEmpty()
}

/**
 * The few notifications the agent sent lately (its own notify tool and the app's, not the questions: those are asks), kept
 * on the phone so the assistant can say "I just told you about the invoice". Small and short-lived: 12 entries, six hours.
 */
object NotifyLog {
    private const val PREFS = "notify_log"
    private const val KEY = "items"
    private const val KEEP = 12

    fun record(context: Context, title: String, body: String, at: Long = System.currentTimeMillis()) {
        if (title.isBlank() && body.isBlank()) return
        runCatching {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val old = read(p.getString(KEY, null))
            val next = (old + CtxNote(AssistContextBuilder.clip(title, 80), AssistContextBuilder.clip(body, 160), at)).takeLast(KEEP)
            p.edit().putString(KEY, write(next)).apply()
        }
    }

    fun recent(context: Context, now: Long): List<CtxNote> = runCatching {
        read(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)).filter { now - it.at in 0..AssistContext.NOTE_MS }
    }.getOrDefault(emptyList())

    fun read(json: String?): List<CtxNote> = runCatching {
        val a = JSONArray(json ?: "[]")
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { CtxNote(it.optString("t"), it.optString("b"), it.optLong("at")) }
    }.getOrDefault(emptyList())

    fun write(items: List<CtxNote>): String = JSONArray().apply { items.forEach { put(JSONObject().put("t", it.title).put("b", it.body).put("at", it.at)) } }.toString()
}
