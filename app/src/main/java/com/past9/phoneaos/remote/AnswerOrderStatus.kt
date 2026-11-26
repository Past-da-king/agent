package com.past9.phoneaos.remote

import android.content.Context
import com.past9.phoneaos.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

fun BrainChooser.answeringLine(context: Context, brains: List<BrainRef>, currentId: String?,
    ready: Set<String> = emptySet(), busy: Boolean = false): String {
    val current = brains.firstOrNull { it.id == currentId } ?: return context.getString(R.string.answer_order_none)
    val first = ordered(brains).first()
    val why = when {
        first.id == current.id -> context.getString(R.string.answer_order_first)
        first.id in ready -> context.getString(if (busy) R.string.answer_order_finishing else R.string.answer_order_handback, first.name)
        else -> context.getString(R.string.answer_order_unavailable, first.name)
    }
    val line = context.getString(R.string.answer_order_answering, current.name, why)
    return if (brains.groupBy { it.priority }.any { it.value.size > 1 })
        line + " " + context.getString(R.string.answer_order_tied) else line
}

/** A status RPC is optional on older brains. Cache misses/errors too, so refresh never
 * repeats an unsupported or failing request on every device-list read. */
internal class NodeStatusCache(private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    private data class Entry(val until: Long, val warning: String)
    private val entries = mutableMapOf<String, Entry>()
    private val mutex = Mutex()
    suspend fun warning(brain: String, fetch: suspend () -> JSONObject?): String = mutex.withLock {
        entries[brain]?.takeIf { now() < it.until }?.let { return@withLock it.warning }
        try {
            val value = warningFrom(fetch())
            entries[brain] = Entry(now() + 60_000L, value)
            value
        } catch (e: TimeoutCancellationException) {
            // A nested RPC timeout is an optional-method failure, not cancellation of
            // the surrounding device-list read. Preserve real parent cancellation.
            currentCoroutineContext().ensureActive()
            entries[brain] = Entry(now() + 60_000L, "")
            ""
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            val unsupported = Regex("unknown|not available|not supported|not found", RegexOption.IGNORE_CASE).containsMatchIn(e.message.orEmpty())
            entries[brain] = Entry(if (unsupported) Long.MAX_VALUE else now() + 60_000L, "")
            ""
        }
    }

    companion object {
        fun warningFrom(status: JSONObject?): String {
            // New nodes may expose an explicit availability field; current nodes include
            // the drive warning in answering and do not expose a structured disk code.
            val availability = status?.optString("availability").orEmpty()
            if (availability.isNotBlank()) return availability
            val line = status?.optString("answering").orEmpty()
            return line.takeIf { Regex("low (disk |storage )?space|drive.*[0-9].*GB free|free (disk |storage )?space", RegexOption.IGNORE_CASE).containsMatchIn(it) }.orEmpty()
        }
    }
}
