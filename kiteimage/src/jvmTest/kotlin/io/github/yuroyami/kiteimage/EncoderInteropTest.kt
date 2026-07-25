package io.github.yuroyami.kiteimage

import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Our encoded files must open in another implementation's decoder. ImageIO is
 * the one that is always present, so it is the one the suite is built on: no
 * external binary, no gate, and therefore no test that reports a pass because it
 * quietly skipped.
 */
class EncoderInteropTest {

    private fun card(w: Int, h: Int, alpha: Boolean): KiteBitmap {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            px[y * w + x] = argb(
                if (alpha) ((x * 11 + y * 3) and 0xFF) else 0xFF,
                (x * 255 / maxOf(1, w - 1)), (y * 255 / maxOf(1, h - 1)), ((x * y) and 0xFF),
            )
        }
        return KiteBitmap(w, h, px)
    }

    @Test
    fun imageIoReadsOurPngPixelExact() {
        val src = card(21, 13, alpha = true)
        val img = ImageIO.read(KiteImage.encodePng(src).inputStream())!!
        assertEquals(21, img.width)
        for (y in 0 until 13) for (x in 0 until 21) {
            assertEquals(src[x, y], img.getRGB(x, y), "($x,$y)")
        }
    }

    @Test
    fun imageIoReadsOurJpeg() {
        val src = card(40, 30, alpha = false)
        val img = ImageIO.read(KiteImage.encodeJpeg(src, quality = 92).inputStream())!!
        assertEquals(40, img.width)
        // Sanity on content, not exactness (two decoders, lossy format).
        var maxDiff = 0
        for (y in 0 until 30) for (x in 0 until 40) {
            for (shift in intArrayOf(16, 8, 0)) {
                val d = kotlin.math.abs(((src[x, y] shr shift) and 0xFF) - ((img.getRGB(x, y) shr shift) and 0xFF))
                if (d > maxDiff) maxDiff = d
            }
        }
        assertTrue(maxDiff < 40, "q92 encode drifted $maxDiff; structurally broken output?")
    }
}
