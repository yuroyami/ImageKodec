package io.github.yuroyami.kiteimagecodec

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
        val img = ImageIO.read(KiteImageCodec.encodePng(src).inputStream())!!
        assertEquals(21, img.width)
        for (y in 0 until 13) for (x in 0 until 21) {
            assertEquals(src[x, y], img.getRGB(x, y), "($x,$y)")
        }
    }

    @Test
    fun imageIoReadsOurPngOfADetailedImage() {
        // The small cards above never build a Huffman tree deeper than the 15-bit
        // limit. A gradient with light noise does, and that is where the encoder
        // once wrote a stream that zlib rejected.
        val w = 1024
        val h = 768
        var state = 1
        fun noise(): Int {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            return state and 7
        }
        val src = KiteBitmap(w, h, IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            argb(0xFF, (x * 255 / w + noise()) and 0xFF, (y * 255 / h + noise()) and 0xFF, (x xor y) and 0xFF)
        })
        val img = ImageIO.read(KiteImageCodec.encodePng(src).inputStream())!!
        assertEquals(w, img.width)
        assertEquals(h, img.height)
        for (i in src.argb.indices) assertEquals(src.argb[i], img.getRGB(i % w, i / w), "pixel $i")
    }

    @Test
    fun imageIoReadsOurJpeg() {
        val src = card(40, 30, alpha = false)
        val img = ImageIO.read(KiteImageCodec.encodeJpeg(src, quality = 92).inputStream())!!
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
