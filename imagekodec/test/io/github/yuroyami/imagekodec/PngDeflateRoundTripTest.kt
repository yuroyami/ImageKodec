package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PNG compresses with ArchiveKodec's DEFLATE encoder, which limits Huffman code lengths to 15 bits.
 * A detailed image has a wide residual histogram, which once pushed the literal code past that
 * limit, so this round trip guards the whole PNG path.
 */
class PngDeflateRoundTripTest {

    @Test
    fun aDetailedImageSurvivesAPngRoundTrip() {
        // A gradient with light noise has a wide residual histogram, which is what
        // pushed the literal tree past the limit. 448 by 448 was the smallest failure.
        val w = 448
        val h = 448
        // xorshift32 in plain Int arithmetic, so every target draws the same noise.
        var state = 1
        fun noise(): Int {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            return state and 7
        }
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val r = (x * 255 / w + noise()) and 0xFF
            val g = (y * 255 / h + noise()) and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or ((x xor y) and 0xFF)
        }
        val decoded = ImageKodec.decode(ImageKodec.encodePng(KiteBitmap(w, h, px)))
        assertEquals(w, decoded.width)
        assertEquals(h, decoded.height)
        for (i in px.indices) assertEquals(px[i], decoded.argb[i], "pixel $i")
    }
}
