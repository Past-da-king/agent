package com.past9.phoneaos.agent

import android.content.Context
import java.io.File

/** A small log of what went wrong in the background, so one failure is recorded and never takes the app down. */
object Crashes {
    private const val MAX = 64_000

    fun record(context: Context, what: String, t: Throwable? = null) {
        runCatching {
            android.util.Log.e("AgentCrash", what, t)
            val f = File(context.filesDir, "crashes.log")
            if (f.length() > MAX) f.writeText(f.readText().takeLast(MAX / 2))
            f.appendText("${java.time.ZonedDateTime.now()} $what\n${t?.stackTraceToString()?.lineSequence()?.take(12)?.joinToString("\n").orEmpty()}\n\n")
        }
    }
}
