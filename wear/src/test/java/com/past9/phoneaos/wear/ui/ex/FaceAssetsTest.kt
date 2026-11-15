package com.past9.phoneaos.wear.ui.ex

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The three Watch Face Format faces (watchface/still, orb, data): this draws what they show as previews (450 px) and the shape images the
 * faces use, from the same shapes and colours as the app. The faces themselves are XML (gen_faces.py); the agent on them is the Agent
 * watch app's MascotComplication. Output: wear/build/faces/.
 */
// ───────── faces ─────────

@Composable
fun ExFace(style: Int, t: Float, ambient: Boolean = false, pal: Pal = Pal.Iris) {
    ExStage { g ->
        val W = g.w; val H = g.h; val cx = W / 2
        val ink = if (ambient) V2.ambientInk else Color.White
        val m = if (ambient) Mood.Ambient else Mood.Idle
        fun fillOr(c: Color) = if (ambient) Color.Transparent else c
        fun DrawScope.blob(poly: RoundedPolygon, c: Offset, s: Float, fill: Color, rot: Float = 0f) {
            if (ambient) rotate(rot, c) { drawPath(polyPath(poly, c.x, c.y, s), V2.ambientInk.copy(alpha = 0.5f), style = Stroke(1.4f * density)) } else shapeBlob(poly, c, s, fill, rot)
        }
        when (style) {
            0 -> { // stacked bold clock
                TopLabel("THU  12  NOV", if (ambient) V2.ambientInk else pal.primary, 10.5f, FontWeight.Bold, 2.4f, y = 14f)
                Box(Modifier.offset(cx - g.dp(100f), H * 0.085f).width(g.dp(200f)), contentAlignment = Alignment.Center) { Txt("14", 84f, ink, FontWeight.Black, -3f) }
                Box(Modifier.offset(cx - g.dp(100f), H * 0.43f).width(g.dp(200f)), contentAlignment = Alignment.Center) { Txt("20", 84f, if (ambient) V2.ambientInk else pal.primary, FontWeight.Black, -3f) }
                Canvas(Modifier.fillMaxSize()) { blob(MaterialShapes.Cookie9Sided, Offset((cx + g.d * 0.33f).toPx(), (H * 0.5f).toPx()), g.d.toPx() * 0.2f, pal.container, t * 5f) }
                Orb(cx + g.d * 0.33f, H * 0.5f, g.d * 0.07f, m, t, 0f, glow = 0f)
            }
            1 -> { // the mascot is the face, bleeding off the bottom
                Box(Modifier.offset(cx - g.dp(100f), H * 0.05f).width(g.dp(200f)), contentAlignment = Alignment.Center) { Txt("14:20", 52f, ink, FontWeight.Black, -1.5f) }
                Canvas(Modifier.fillMaxSize()) { blob(MaterialShapes.Cookie12Sided, Offset(cx.toPx(), (H * 0.74f).toPx()), g.d.toPx() * 0.82f, pal.container, t * 4f) }
                Orb(cx, H * 0.72f, g.d * (if (ambient) 0.24f else 0.29f), m, t, 0f, glow = 0f)
                Box(Modifier.offset(cx - g.dp(70f), H * 0.275f).width(g.dp(140f)), contentAlignment = Alignment.Center) { Txt("THU 12", 10f, if (ambient) V2.ambientInk else pal.primary, FontWeight.Bold, 2.2f) }
                if (!ambient) Box(Modifier.at(cx + g.d * 0.28f, H * 0.5f, g.dp(30f), g.dp(30f)).clip(RoundedCornerShape(50)).background(pal.amber), contentAlignment = Alignment.Center) { Txt("2", 14f, pal.onAmber, FontWeight.Black) }
            }
            else -> { // data: shape-container complications
                TopLabel("THU 12 NOV", if (ambient) V2.ambientInk else pal.primary, 10f, FontWeight.Bold, 2.2f, y = 12f)
                Box(Modifier.offset(cx - g.dp(100f), H * 0.115f).width(g.dp(200f)), contentAlignment = Alignment.Center) { Txt("14:20", 52f, ink, FontWeight.Black, -1.5f) }
                val y = H * 0.55f; val s = g.d * 0.215f
                Canvas(Modifier.fillMaxSize()) {
                    blob(MaterialShapes.Cookie6Sided, Offset((cx - g.d * 0.29f).toPx(), y.toPx()), s.toPx(), pal.container, t * 5f)
                    blob(MaterialShapes.Cookie9Sided, Offset(cx.toPx(), y.toPx()), s.toPx() * 1.12f, pal.secC)
                    blob(MaterialShapes.Flower, Offset((cx + g.d * 0.29f).toPx(), y.toPx()), s.toPx(), pal.amberC, -t * 5f)
                }
                Box(Modifier.at(cx - g.d * 0.29f, y, s, s), contentAlignment = Alignment.Center) { Txt("3/5", 13f, if (ambient) V2.ambientInk else pal.onContainer, FontWeight.Black) }
                Orb(cx, y, g.d * 0.075f, m, t, 0f, glow = 0f)
                Box(Modifier.at(cx + g.d * 0.29f, y, s, s), contentAlignment = Alignment.Center) { Txt("2", 19f, if (ambient) V2.ambientInk else pal.onAmberC, FontWeight.Black) }
                if (!ambient) Box(Modifier.offset(cx - g.dp(70f), H * 0.78f).size(g.dp(140f), g.dp(30f)).clip(RoundedCornerShape(50)).background(pal.secC), contentAlignment = Alignment.Center) { Txt("15:00  Review", 12.5f, pal.onSecC, FontWeight.Bold) }
                else Box(Modifier.offset(cx - g.dp(84f), H * 0.80f).width(g.dp(168f)), contentAlignment = Alignment.Center) { Txt("15:00  Design review", 11.5f, V2.ambientInk.copy(alpha = 0.75f)) }
            }
        }
    }
}


@RunWith(RobolectricTestRunner::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w225dp-h225dp-round-xhdpi")
class FaceAssetsTest {
    @get:Rule val rule = createComposeRule()

    private fun preview(name: String, style: Int, ambient: Boolean) {
        rule.mainClock.autoAdvance = false
        rule.setContent { Box(Modifier.fillMaxSize().background(Color.Black).clip(CircleShape)) { ExFace(style, 1.9f, ambient) } }
        rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeBy(500)
        rule.onRoot().captureRoboImage(File("build/faces/preview/$name.png").path)
    }

    @Test fun previewStill() { preview("still-active", 0, false) }
    @Test fun previewStillAmbient() { preview("still-ambient", 0, true) }
    @Test fun previewOrb() { preview("orb-active", 1, false) }
    @Test fun previewOrbAmbient() { preview("orb-ambient", 1, true) }
    @Test fun previewData() { preview("data-active", 2, false) }
    @Test fun previewDataAmbient() { preview("data-ambient", 2, true) }

    private fun png(name: String, poly: RoundedPolygon, px: Int, fill: Int?, stroke: Int?) {
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val inset = if (stroke != null) 4f else 0f
        val path = polyPath(poly, px / 2f, px / 2f, px - 2 * inset).asAndroidPath()
        if (fill != null) c.drawPath(path, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = fill; style = android.graphics.Paint.Style.FILL })
        if (stroke != null) c.drawPath(path, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = stroke; style = android.graphics.Paint.Style.STROKE; strokeWidth = 4f })
        val f = File("build/faces/drawable/$name.png"); f.parentFile!!.mkdirs()
        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun shapeImages() {
        val container = 0xFF3A2FB0.toInt(); val sec = 0xFF45435A.toInt(); val amberC = 0xFF6A3D00.toInt(); val line = 0xFF8C8A96.toInt()
        png("blob12_container", MaterialShapes.Cookie12Sided, 420, container, null); png("blob12_outline", MaterialShapes.Cookie12Sided, 420, null, line)
        png("blob9_container", MaterialShapes.Cookie9Sided, 120, container, null); png("blob9_secc", MaterialShapes.Cookie9Sided, 130, sec, null); png("blob9_outline", MaterialShapes.Cookie9Sided, 130, null, line)
        png("blob6_container", MaterialShapes.Cookie6Sided, 120, container, null); png("blob6_outline", MaterialShapes.Cookie6Sided, 120, null, line)
        png("flower_amber", MaterialShapes.Flower, 120, amberC, null); png("flower_outline", MaterialShapes.Flower, 120, null, line)
    }
}
