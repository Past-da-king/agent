package com.past9.phoneaos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.camera.CameraPhoto
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CameraPhotoTest {
    @Test fun `a big landscape photo is cut to a 1600 long edge`() = assertEquals(1600 to 1200, CameraPhoto.scaledSize(4000, 3000))
    @Test fun `a big portrait photo is cut to a 1600 long edge`() = assertEquals(1200 to 1600, CameraPhoto.scaledSize(3000, 4000))
    @Test fun `a small photo is never upscaled`() = assertEquals(800 to 600, CameraPhoto.scaledSize(800, 600))
    @Test fun `a photo exactly at the limit is untouched`() = assertEquals(1600 to 900, CameraPhoto.scaledSize(1600, 900))
    @Test fun `aspect ratio is kept for odd sizes`() {
        val (w, h) = CameraPhoto.scaledSize(4032, 3024)
        assertEquals(1600, w); assertEquals(1200, h)
        val (w2, h2) = CameraPhoto.scaledSize(5000, 17)
        assertEquals(1600, w2); assertTrue(h2 >= 1)
    }
    @Test fun `degenerate sizes do not crash`() = assertEquals(1 to 1, CameraPhoto.scaledSize(0, 0))

    @Test fun `compress writes a smaller jpeg under uploads and removes the raw shot`() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val raw = File(ctx.cacheDir, "raw.jpg")
        Bitmap.createBitmap(3200, 2400, Bitmap.Config.ARGB_8888).also { it.eraseColor(0xFF3366CC.toInt()) }
            .let { b -> raw.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) } }
        val out = CameraPhoto.compress(ctx, raw)
        assertNotNull(out); assertFalse(raw.exists())
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(out, o)
        assertEquals(1600, o.outWidth); assertEquals(1200, o.outHeight)
        assertTrue(File(out!!).parentFile!!.name == "uploads")
    }
    @Test fun `compress of a missing file gives null`() {
        assertNull(CameraPhoto.compress(ApplicationProvider.getApplicationContext(), File("/nonexistent/x.jpg")))
    }
}
