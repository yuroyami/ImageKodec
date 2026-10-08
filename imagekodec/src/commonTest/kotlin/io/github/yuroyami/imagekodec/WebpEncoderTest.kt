package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The lossless WebP writer (#12): every image reads back exactly, through every path the encoder can choose. */
class WebpEncoderTest {

    private fun roundTrip(name: String, b: KiteBitmap): ByteArray {
        val data = ImageKodec.encodeWebp(b)
        assertEquals(ImageFormat.WEBP, ImageKodec.detect(data), name)
        assertContentEquals(b.argb, ImageKodec.decode(data).argb, name)
        assertEquals(b.width to b.height, ImageKodec.probe(data).let { it.width to it.height }, name)
        return data
    }

    private fun photo(w: Int, h: Int, alpha: Boolean): KiteBitmap {
        var state = 0x2545F491
        return KiteBitmap(w, h, IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            state = state xor (state shl 13); state = state xor (state ushr 17); state = state xor (state shl 5)
            val n = state and 7
            argb(
                if (alpha) (x * 5 + y * 3 + n) and 0xFF else 0xFF,
                (x * 255 / maxOf(1, w - 1) + n) and 0xFF, (y * 255 / maxOf(1, h - 1) + n) and 0xFF, ((x + y) * 2 + n) and 0xFF,
            )
        })
    }

    @Test
    fun photographsReadBackExactly() {
        roundTrip("opaque", photo(97, 61, alpha = false))
        roundTrip("alpha", photo(64, 80, alpha = true))
        roundTrip("wide", photo(700, 3, alpha = false))
        roundTrip("tall", photo(2, 300, alpha = true))
    }

    @Test
    fun palettesOfEverySizeReadBackExactly() {
        for (colors in listOf(1, 2, 3, 4, 5, 16, 17, 200, 256)) {
            val w = 37
            val h = 21
            val b = KiteBitmap(w, h, IntArray(w * h) { i ->
                val c = ((i % w) / 3 + (i / w) * 7) % colors
                argb(if (c % 3 == 0) 0x80 else 0xFF, c * 37 and 0xFF, c * 91 and 0xFF, c * 13 and 0xFF)
            })
            roundTrip("$colors colours", b)
        }
    }

    @Test
    fun tinyAndRepetitiveImagesReadBackExactly() {
        roundTrip("one pixel", KiteBitmap(1, 1, intArrayOf(argb(0x12, 0x34, 0x56, 0x78))))
        roundTrip("one row", KiteBitmap(5, 1, IntArray(5) { argb(0xFF, it * 50, 0, 0) }))
        roundTrip("one column", KiteBitmap(1, 9, IntArray(9) { argb(0xFF, 0, it * 20, 0) }))
        val flat = roundTrip("flat", KiteBitmap(300, 200, IntArray(300 * 200) { argb(0xFF, 10, 20, 30) }))
        assertTrue(flat.size < 100, "a flat image took ${flat.size} bytes")
        // Long runs and repeats far apart exercise copies at every distance code.
        val tile = photo(64, 1, alpha = false).argb
        roundTrip("repeating rows", KiteBitmap(64, 300, IntArray(64 * 300) { i -> tile[(i + (i / 64) * 3) % 64] }))
    }

    @Test
    fun aDetailedImageIsSmallerThanItsPng() {
        val b = photo(200, 150, alpha = false)
        assertTrue(roundTrip("detailed", b).size < ImageKodec.encodePng(b).size)
    }

    @Test
    fun aSideBeyondWebpsLimitIsRefused() {
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeWebp(KiteBitmap(16385, 1, IntArray(16385))) }
    }
}
