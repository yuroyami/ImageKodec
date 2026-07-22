package io.github.yuroyami.kiteimage

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EncoderTest {

    private fun testCard(w: Int, h: Int, alpha: Boolean): KiteBitmap {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val a = if (alpha) ((x + y) * 7) and 0xFF else 0xFF
            px[y * w + x] = argb(a, (x * 255 / maxOf(1, w - 1)), (y * 255 / maxOf(1, h - 1)), ((x + y) * 3) and 0xFF)
        }
        return KiteBitmap(w, h, px)
    }

    // --- PNG: lossless, so round-trips must be pixel-exact --------------------

    @Test
    fun pngRoundTripOpaqueExact() {
        for ((w, h) in listOf(1 to 1, 3 to 2, 16 to 16, 31 to 7)) {
            val src = testCard(w, h, alpha = false)
            val bytes = KiteImage.encodePng(src)
            assertEquals(ImageFormat.PNG, KiteImage.detect(bytes))
            val back = KiteImage.decode(bytes)
            assertContentEquals(src.argb, back.argb, "${w}x$h")
        }
    }

    @Test
    fun pngRoundTripAlphaExact() {
        val src = testCard(9, 5, alpha = true)
        val back = KiteImage.decode(KiteImage.encodePng(src))
        assertContentEquals(src.argb, back.argb)
    }

    @Test
    fun pngFiltersActuallyChosen() {
        // A smooth gradient compresses far better filtered than raw; sanity-check
        // that the encoder isn't emitting filter 0 everywhere by size.
        val smooth = testCard(64, 64, alpha = false)
        val bytes = KiteImage.encodePng(smooth)
        assertTrue(bytes.size < 64 * 64 * 3 / 2, "png ${bytes.size}B suggests no filtering/compression")
    }

    // --- JPEG: lossy, so round-trips are judged by error bounds ---------------

    private fun psnrDb(a: IntArray, b: IntArray): Double {
        var mse = 0.0
        for (i in a.indices) {
            for (shift in intArrayOf(16, 8, 0)) {
                val d = (((a[i] shr shift) and 0xFF) - ((b[i] shr shift) and 0xFF)).toDouble()
                mse += d * d
            }
        }
        mse /= a.size * 3
        if (mse == 0.0) return 99.0
        return 10 * kotlin.math.log10(255.0 * 255.0 / mse)
    }

    @Test
    fun jpegRoundTripHighQuality() {
        val src = testCard(32, 24, alpha = false)
        val bytes = KiteImage.encodeJpeg(src, quality = 95)   // 4:4:4 path
        assertEquals(ImageFormat.JPEG, KiteImage.detect(bytes))
        val back = KiteImage.decode(bytes)
        assertEquals(32, back.width)
        val psnr = psnrDb(src.argb, back.argb)
        assertTrue(psnr > 35, "q95 PSNR $psnr dB too low")
    }

    @Test
    fun jpegRoundTripSubsampled() {
        val src = testCard(33, 17, alpha = false)   // odd dims through the 4:2:0 path
        val back = KiteImage.decode(KiteImage.encodeJpeg(src, quality = 75))
        assertEquals(33, back.width)
        assertEquals(17, back.height)
        val psnr = psnrDb(src.argb, back.argb)
        assertTrue(psnr > 28, "q75 4:2:0 PSNR $psnr dB too low")
    }

    @Test
    fun jpegQualityOrdering() {
        val src = testCard(48, 48, alpha = false)
        val q30 = KiteImage.decode(KiteImage.encodeJpeg(src, quality = 30))
        val q90 = KiteImage.decode(KiteImage.encodeJpeg(src, quality = 90))
        assertTrue(psnrDb(src.argb, q90.argb) > psnrDb(src.argb, q30.argb), "higher quality must fit better")
        assertTrue(
            KiteImage.encodeJpeg(src, quality = 30).size < KiteImage.encodeJpeg(src, quality = 90).size,
            "lower quality must be smaller",
        )
    }

    @Test
    fun jpegSolidColorNearExact() {
        val solid = KiteBitmap(24, 24, IntArray(576) { argb(0xFF, 0x33, 0x66, 0x99) })
        val back = KiteImage.decode(KiteImage.encodeJpeg(solid, quality = 90))
        for (i in solid.argb.indices) {
            for (shift in intArrayOf(16, 8, 0)) {
                val d = abs(((solid.argb[i] shr shift) and 0xFF) - ((back.argb[i] shr shift) and 0xFF))
                assertTrue(d <= 2, "solid color drifted by $d")
            }
        }
    }
}
