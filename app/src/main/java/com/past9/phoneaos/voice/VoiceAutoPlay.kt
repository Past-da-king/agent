package com.past9.phoneaos.voice

import com.past9.phoneaos.data.ChatItem
import org.json.JSONObject

/**
 * Which new agent voice note plays by itself, and which ones still need their audio fetched first.
 *
 * 27 Nov 2026, "what happened to the auto play VN feature": linked to a computer, a voice note row lands on the phone
 * before its audio does (and on mobile data the audio was only fetched on a tap). The old check looked at the newest
 * row once, marked it heard, found no file and never tried again, so a computer's voice note never played by itself.
 * It also only ran with earphones in.
 *
 * Now a note stays "not yet heard" until its audio is on the phone; meanwhile its audio is asked for once ([Step.Fetch]).
 * Only notes that arrived after the chat was opened count, never the history; a call's own rows never play.
 */
object VoiceAutoPlay {
    sealed interface Step {
        data class Play(val id: Long, val path: String) : Step
        data class Fetch(val id: Long) : Step
    }

    /**
     * [heard]: ids already played or skipped (the history when the chat opened). [asked]: ids whose audio was already
     * requested. [hasFile] says whether a path holds the audio. Returns at most one Play (the newest playable note; any
     * older unplayed ones are skipped so notes never pile up into a queue) and a Fetch for each note still waiting.
     */
    fun steps(items: List<ChatItem>, heard: Set<Long>, asked: Set<Long>, on: Boolean, hasFile: (String) -> Boolean): List<Step> {
        if (!on) return emptyList()
        val fresh = items.filter { it.kind == "voice" && it.id !in heard && !isCall(it) }
        if (fresh.isEmpty()) return emptyList()
        val out = mutableListOf<Step>()
        val playable = fresh.lastOrNull { path(it).let { p -> p.isNotBlank() && hasFile(p) } }
        if (playable != null) out += Step.Play(playable.id, path(playable))
        fresh.filter { it.id != playable?.id && (playable == null || it.id > playable.id) && it.id !in asked && waiting(it) }
            .forEach { out += Step.Fetch(it.id) }
        return out
    }

    /** Ids of unplayed notes older than the one that plays: they are skipped, never played late over a newer one. */
    fun skipped(items: List<ChatItem>, heard: Set<Long>, played: Long): List<Long> =
        items.filter { it.kind == "voice" && it.id !in heard && it.id < played }.map { it.id }

    private fun meta(i: ChatItem) = runCatching { JSONObject(i.meta) }.getOrDefault(JSONObject())
    private fun path(i: ChatItem) = meta(i).optString("path")
    private fun isCall(i: ChatItem) = meta(i).optBoolean("call")

    /** The note's audio was left on the computer to save data: ask for it. ("loading" is already on its way.) */
    private fun waiting(i: ChatItem): Boolean = meta(i).optString("audio") == "remote"
}
