package com.past9.phoneaos.assist

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.past9.phoneaos.R

/** "Talk" on a long press of the app's icon: opens the assist sheet, listening. */
object AssistShortcuts {
    const val ID = "assist_talk"

    fun publish(context: Context) {
        runCatching {
            val s = ShortcutInfoCompat.Builder(context, ID)
                .setShortLabel("Talk")
                .setLongLabel("Talk to your agent")
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_assist_mic))
                .setIntent(AssistEntry.intent(context, AssistEntry.SHORTCUT))
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, s)
        }
    }
}

/** A quick settings tile that opens the assist sheet over whatever is on screen. */
class AssistTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }
    }

    override fun onClick() {
        super.onClick()
        // The phone's assistant can show its session from anywhere; otherwise the tile itself may start the sheet.
        if (AssistService.live != null && AssistRole.held(this)) { AssistEntry.open(this, AssistEntry.SHORTCUT); return }
        val i = AssistEntry.intent(this, AssistEntry.SHORTCUT)
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            else @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated") startActivityAndCollapse(i)
        }.onFailure { AssistEntry.open(this, AssistEntry.SHORTCUT) }
    }
}
