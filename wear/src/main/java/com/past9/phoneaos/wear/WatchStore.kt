package com.past9.phoneaos.wear

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.past9.phoneaos.wearproto.WatchState
import com.past9.phoneaos.wearproto.Wire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The newest picture the phone sent, kept in memory for the screens and on disk for the tile and the complication,
 * which may run while the app does not.
 */
object WatchStore {
    private const val PREFS = "watch_store"
    private val _state = MutableStateFlow<WatchState?>(null)
    val state: StateFlow<WatchState?> = _state

    fun cached(context: Context): WatchState? = _state.value ?: runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("state", null)?.let { Wire.parseState(it.toByteArray()) }
    }.getOrNull()?.also { _state.value = it }

    /** Unpaired: nothing of the old phone's picture stays on the watch. */
    fun clear(context: Context) {
        _state.value = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("state").apply()
        refreshSurfaces(context)
    }

    fun update(context: Context, s: WatchState) {
        _state.value = s
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("state", String(Wire.state(s))).apply()
        refreshSurfaces(context)
        // Approvals that wait are half of what rings the watch.
        runCatching { WatchRing.onState(context, s) }
    }

    fun refreshSurfaces(context: Context) {
        runCatching { TileService.getUpdater(context).requestUpdate(AgentTile::class.java) }
        // The combined complication and the four data sources a watch face can pick (FaceServices.kt).
        FaceSurfaces.refresh(context)
    }

    // The watch mic: when it was last found dead (0 = never), kept so the talk screen puts the fallbacks first.
    fun micDeadAt(context: Context): Long = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("mic_dead_at", 0L)
    fun setMicDead(context: Context, dead: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("mic_dead_at", if (dead) System.currentTimeMillis() else 0L).apply()
    }
}
