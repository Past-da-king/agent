package com.past9.phoneaos.assist

import com.past9.phoneaos.data.ChatItem

/** STUB (red phase): the rules that keep Live from inventing outcomes. Replaced by the real thing in the next commit. */
object LiveTruth {
    const val LATE_MS = 20_000L
    fun claimsOutcome(said: String): Boolean = false
    fun ack(tracked: Boolean, where: String): String = ""
    fun lateLine(): String = ""
    fun correctionLine(said: String): String = ""
    fun missed(rows: List<ChatItem>, after: Long, told: Set<Long>): List<ChatItem> = emptyList()
}
