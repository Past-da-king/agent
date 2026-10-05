package com.past9.phoneaos

import com.past9.phoneaos.ui.Mascots
import com.past9.phoneaos.ui.theme.Accents
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Generates one launcher icon per mascot x colour (vector drawables + activity-aliases), so the
 * app icon on the home screen becomes the character the user picked, in their colour.
 * Run with -DgenIcons=true; outputs are committed. Not part of the normal test run.
 */
class IconGen {
    private fun hex(c: androidx.compose.ui.graphics.Color) = "#FF%06X".format(c.value.shr(32).toLong() and 0xFFFFFF)

    /** RoundedPolygon -> SVG path data in a 108x108 viewport, shape scaled into the safe zone. */
    private fun pathData(p: androidx.graphics.shapes.RoundedPolygon, size: Float = 60f, offset: Float = 24f): String {
        val n = p.normalized()
        val sb = StringBuilder()
        n.cubics.forEachIndexed { i, c ->
            val x = { v: Float -> "%.2f".format(offset + v * size) }
            val y = x
            if (i == 0) sb.append("M${x(c.anchor0X)},${y(c.anchor0Y)} ")
            sb.append("C${x(c.control0X)},${y(c.control0Y)} ${x(c.control1X)},${y(c.control1Y)} ${x(c.anchor1X)},${y(c.anchor1Y)} ")
        }
        return sb.append("Z").toString()
    }

    @Test fun generate() {
        assumeTrue(System.getProperty("genIcons") == "true" || System.getenv("GEN_ICONS") == "true")
        val res = File("src/main/res"); val draw = File(res, "drawable").apply { mkdirs() }; val mip = File(res, "mipmap-anydpi-v26").apply { mkdirs() }
        val colors = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n")
        val aliases = StringBuilder()
        val eyes = "M46,51 a3,4.6 0 1,0 6,0 a3,4.6 0 1,0 -6,0 Z M56,51 a3,4.6 0 1,0 6,0 a3,4.6 0 1,0 -6,0 Z"
        Mascots.forEach { m ->
            val shape = pathData(m.rest)
            // Monochrome (themed icons on Android 13+) and the status-bar glyph share one mask per mascot.
            File(draw, "ic_mono_${m.id}.xml").writeText("""<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FF000000" android:fillType="evenOdd" android:pathData="$shape $eyes"/>
</vector>
""")
            // Status-bar glyph: the same character, filling a 24dp box.
            File(draw, "ic_stat_${m.id}.xml").writeText("""<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData="${pathData(m.rest, 22f, 1f)} M9.9,11.4 a0.75,1.15 0 1,0 1.5,0 a0.75,1.15 0 1,0 -1.5,0 Z M12.6,11.4 a0.75,1.15 0 1,0 1.5,0 a0.75,1.15 0 1,0 -1.5,0 Z"/>
</vector>
""")
            Accents.forEach { a ->
                val id = "${m.id}_${a.id}"
                File(draw, "ic_fg_$id.xml").writeText("""<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="${hex(a.lP)}" android:pathData="$shape"/>
    <path android:fillColor="${hex(a.lOnP)}" android:pathData="$eyes"/>
</vector>
""")
                File(mip, "ic_app_$id.xml").writeText("""<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/icon_bg_${a.id}" />
    <foreground android:drawable="@drawable/ic_fg_$id" />
    <monochrome android:drawable="@drawable/ic_mono_${m.id}" />
</adaptive-icon>
""")
                val default = m.id == "scout" && a.id == "iris"
                aliases.append("""        <activity-alias android:name=".Icon_$id" android:targetActivity=".MainActivity" android:exported="true" android:enabled="$default" android:icon="@mipmap/ic_app_$id" android:roundIcon="@mipmap/ic_app_$id">
            <intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter>
        </activity-alias>
""")
            }
        }
        Accents.forEach { a -> colors.append("    <color name=\"icon_bg_${a.id}\">${hex(a.lPC)}</color>\n") }
        File(res, "values/icon_colors.xml").writeText(colors.append("</resources>\n").toString())
        // Splice the aliases into the manifest between markers.
        val mf = File("src/main/AndroidManifest.xml"); val txt = mf.readText()
        val start = "<!-- ICON ALIASES START -->"; val end = "<!-- ICON ALIASES END -->"
        mf.writeText(txt.substringBefore(start) + start + "\n" + aliases + "        " + end + txt.substringAfter(end))
    }
}
