package com.past9.phoneaos.voice

import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * Stage timings of one live voice start, logged under the tag LiveTiming as "stage +Nms" from the first mark of that
 * start. Each stage is logged once per start; a mark more than 30 s after the last one begins a new start. No audio or
 * text is ever logged here.
 */
object LiveTiming {
    private const val TAG = "LiveTiming"
    private var t0 = 0L
    private var last = 0L
    private val seen = HashSet<String>()

    @Synchronized fun mark(stage: String) {
        val now = SystemClock.elapsedRealtime()
        if (t0 == 0L || now - last > 30_000 || stage == "entry") {
            t0 = now; seen.clear()
            Log.i(TAG, "begin processAge=${now - Process.getStartElapsedRealtime()}ms")
        }
        last = now
        if (seen.add(stage)) Log.i(TAG, "$stage +${now - t0}ms")
    }
}
