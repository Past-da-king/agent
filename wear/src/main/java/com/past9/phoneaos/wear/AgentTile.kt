package com.past9.phoneaos.wear

import androidx.concurrent.futures.ResolvableFuture
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.compose.ui.graphics.toArgb
import com.google.common.util.concurrent.ListenableFuture
import com.past9.phoneaos.wear.ui.WatchTokens

/**
 * The tile: the agent's name, what needs him, and one big Ask button that opens the talk screen. It reads the last
 * picture the phone sent (WatchStore), so it draws instantly, and the app asks the system to redraw it when that changes.
 */
class AgentTile : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val s = WatchStore.cached(this)
        val waiting = s?.pending?.size ?: 0
        val line = when {
            waiting == 1 -> getString(R.string.tile_one_waits)
            waiting > 1 -> getString(R.string.tile_many_wait, waiting)
            else -> getString(R.string.tile_nothing_waits)
        }
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(VERSION)
            .setFreshnessIntervalMillis(0)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(s?.agent?.ifBlank { null } ?: getString(R.string.agent_default), line, waiting > 0)))
            .build()
        return ResolvableFuture.create<TileBuilders.Tile>().also { it.set(tile) }
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        ResolvableFuture.create<ResourceBuilders.Resources>().also { it.set(ResourceBuilders.Resources.Builder().setVersion(VERSION).build()) }

    private fun launch(go: String): ModifiersBuilders.Clickable = ModifiersBuilders.Clickable.Builder()
        .setId(go)
        .setOnClick(ActionBuilders.LaunchAction.Builder().setAndroidActivity(
            ActionBuilders.AndroidActivity.Builder().setPackageName(packageName).setClassName(MainActivity::class.java.name)
                .addKeyToExtraMapping("go", ActionBuilders.AndroidStringExtra.Builder().setValue(go).build()).build()).build())
        .build()

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false): LayoutElementBuilders.LayoutElement =
        Text.Builder().setText(s).setMaxLines(1)
            .setFontStyle(FontStyle.Builder().setSize(sp(size)).setColor(argb(color)).setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL).build()).build()

    private fun layout(name: String, line: String, needsYou: Boolean): LayoutElementBuilders.LayoutElement {
        val button = LayoutElementBuilders.Box.Builder()
            .setWidth(expand()).setHeight(dp(52f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER).setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(launch("talk"))
                .setBackground(ModifiersBuilders.Background.Builder().setColor(argb(WatchTokens.irisContainer.toArgbInt()))
                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(26f)).build()).build()).build())
            .addContent(text(getString(R.string.tile_ask), 18f, WatchTokens.onIrisContainer.toArgbInt(), bold = true)).build()
        val status = LayoutElementBuilders.Box.Builder().setWidth(wrap()).setHeight(wrap())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(launch(if (needsYou) "approvals" else "talk")).build())
            .addContent(text(line, 14f, (if (needsYou) WatchTokens.amber else WatchTokens.onSurfaceVariant).toArgbInt())).build()
        return Column.Builder().setWidth(expand()).setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setPadding(ModifiersBuilders.Padding.Builder().setAll(dp(22f)).build()).build())
            .addContent(text(name, 15f, WatchTokens.onSurface.toArgbInt(), bold = true))
            .addContent(Spacer.Builder().setHeight(dp(4f)).build())
            .addContent(status)
            .addContent(Spacer.Builder().setHeight(dp(10f)).build())
            .addContent(button).build()
    }

    private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int = this.toArgb()

    private companion object { const val VERSION = "1" }
}
