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
import com.past9.phoneaos.wear.ui.ex.MascotKind
import com.past9.phoneaos.wear.ui.ex.Pal
import com.past9.phoneaos.wear.ui.ex.V2

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
            .setResourcesVersion(versionNow(waiting > 0))
            .setFreshnessIntervalMillis(0)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(s?.agent?.ifBlank { null } ?: getString(R.string.agent_default), line, waiting > 0)))
            .build()
        return ResolvableFuture.create<TileBuilders.Tile>().also { it.set(tile) }
    }

    /** The agent's mascot as the tile's picture: the phone's own shape and the synced accent, amber when something needs the user. */
    private fun mascotPng(needs: Boolean): ByteArray {
        WatchCustomisationState.load(this)
        val cust = WatchCustomisationState.state.value
        val pal = Pal.ofAccent(cust.accent)
        val bmp = MascotBitmap.make(MascotKind.of(cust.mascot), if (needs) V2.amber.toArgb() else pal.primary.toArgb(), if (needs) V2.inkAmber.toArgb() else pal.onPrimary.toArgb(), 160)
        return java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        val needs = (WatchStore.cached(this)?.pending?.size ?: 0) > 0
        val img = ResourceBuilders.ImageResource.Builder().setInlineResource(
            ResourceBuilders.InlineImageResource.Builder().setData(mascotPng(needs)).setWidthPx(160).setHeightPx(160).setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED).build()).build()
        return ResolvableFuture.create<ResourceBuilders.Resources>().also {
            it.set(ResourceBuilders.Resources.Builder().setVersion(versionNow(needs)).addIdToImageMapping("mascot", img).build())
        }
    }

    private fun versionNow(needs: Boolean): String { WatchCustomisationState.load(this); val c = WatchCustomisationState.state.value; return "$VERSION-${c.mascot}-${c.accent}-$needs" }

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
                .setBackground(ModifiersBuilders.Background.Builder().setColor(argb((if (needsYou) V2.amber else WatchTokens.iris).toArgbInt()))
                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(26f)).build()).build()).build())
            .addContent(text(getString(R.string.tile_ask), 18f, (if (needsYou) V2.inkAmber else WatchTokens.onIris).toArgbInt(), bold = true)).build()
        val status = LayoutElementBuilders.Box.Builder().setWidth(wrap()).setHeight(wrap())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(launch(if (needsYou) "approvals" else "talk")).build())
            .addContent(text(line, 14f, (if (needsYou) WatchTokens.amber else WatchTokens.onSurfaceVariant).toArgbInt())).build()
        return Column.Builder().setWidth(expand()).setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setPadding(ModifiersBuilders.Padding.Builder().setAll(dp(18f)).build()).build())
            .addContent(LayoutElementBuilders.Image.Builder().setResourceId("mascot").setWidth(dp(50f)).setHeight(dp(50f)).build())
            .addContent(Spacer.Builder().setHeight(dp(2f)).build())
            .addContent(text(name, 15f, WatchTokens.onSurface.toArgbInt(), bold = true))
            .addContent(Spacer.Builder().setHeight(dp(4f)).build())
            .addContent(status)
            .addContent(Spacer.Builder().setHeight(dp(10f)).build())
            .addContent(button).build()
    }

    private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int = this.toArgb()

    private companion object { const val VERSION = "1" }
}
