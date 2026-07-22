package io.github.yuroyami.kiteimage

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * BMP vectors are built programmatically: the format is uncompressed, so the
 * builder below IS the spec restated, and every expectation is knowable by eye.
 */
class BmpDecoderTest {

    /** Minimal BI_RGB BMP writer (the test's independent ground truth). */
    private fun bmp(
        width: Int,
        height: Int,          // negative = top-down, like the real field
        bpp: Int,
        palette: List<Int> = emptyList(),        // 0xRRGGBB
        pixels: List<Int>,                       // palette indices, 0xRRGGBB, or 0xAARRGGBB rows top-to-bottom
        compression: Int = 0,
    ): ByteArray {
        val absHeight = if (height < 0) -height else height
        val bytesPerPixel = bpp / 8
        val rowStride = (width * bytesPerPixel + 3) and 3.inv()
        val headerSize = 14 + 40 + palette.size * 4
        val out = ArrayList<Byte>(headerSize + rowStride * absHeight)

        fun u8(v: Int) = out.add((v and 0xFF).toByte())
        fun u16(v: Int) { u8(v); u8(v ushr 8) }
        fun u32(v: Int) { u16(v); u16(v ushr 16) }

        u8('B'.code); u8('M'.code)
        u32(headerSize + rowStride * absHeight)   // file size
        u32(0)                                    // reserved
        u32(headerSize)                           // pixel offset
        u32(40)                                   // BITMAPINFOHEADER
        u32(width)
        u32(height)
        u16(1)                                    // planes
        u16(bpp)
        u32(compression)
        u32(0); u32(0); u32(0)                    // image size, x/y ppm
        u32(palette.size)                         // clrUsed
        u32(0)                                    // clrImportant
        for (c in palette) { u8(c); u8(c ushr 8); u8(c ushr 16); u8(0) }   // BGRX

        // Rows: `pixels` is top-to-bottom; storage order depends on sign of height.
        val rowIndices = if (height < 0) 0 until absHeight else (absHeight - 1) downTo 0
        for (y in rowIndices) {
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                when (bpp) {
                    8 -> u8(p)
                    24 -> { u8(p); u8(p ushr 8); u8(p ushr 16) }             // BGR
                    32 -> { u8(p); u8(p ushr 8); u8(p ushr 16); u8(p ushr 24) }  // BGRA
                }
            }
            repeat(rowStride - width * bytesPerPixel) { u8(0) }
        }
        return out.toByteArray()
    }

    @Test
    fun bmp24BottomUpWithPadding() {
        // 3x2: width*3 = 9 bytes → 3 bytes padding per row. Colors distinct per pixel.
        val px = listOf(0xFF0000, 0x00FF00, 0x0000FF, 0x102030, 0x405060, 0x708090)
        val bm = KiteImage.decode(bmp(3, 2, 24, pixels = px))
        assertEquals(3, bm.width)
        assertEquals(2, bm.height)
        assertContentEquals(px.map { it or (0xFF shl 24) }.toIntArray(), bm.argb)
    }

    @Test
    fun bmp24TopDown() {
        val px = listOf(0x111111, 0x222222, 0x333333, 0x444444)
        val bm = KiteImage.decode(bmp(2, -2, 24, pixels = px))
        assertContentEquals(px.map { it or (0xFF shl 24) }.toIntArray(), bm.argb)
    }

    @Test
    fun bmp32KeepsRealAlpha() {
        val px = listOf(0x80FF0000.toInt(), 0x4000FF00, 0xFF0000FF.toInt(), 0x01FFFFFF)
        val bm = KiteImage.decode(bmp(2, 2, 32, pixels = px))
        assertContentEquals(px.toIntArray(), bm.argb)
        assertTrue(bm.hasTransparency())
    }

    @Test
    fun bmp32AllZeroAlphaBecomesOpaque() {
        // The stb rule: everyone writes 0 in the 4th byte and means "no alpha".
        val px = listOf(0x00FF0000, 0x0000FF00, 0x000000FF, 0x00ABCDEF)
        val bm = KiteImage.decode(bmp(2, 2, 32, pixels = px))
        assertContentEquals(px.map { it or (0xFF shl 24) }.toIntArray(), bm.argb)
    }

    @Test
    fun bmp8Palette() {
        val bm = KiteImage.decode(
            bmp(4, 1, 8, palette = listOf(0xFF0000, 0x00FF00), pixels = listOf(0, 1, 1, 0)),
        )
        val red = argb(0xFF, 0xFF, 0, 0)
        val green = argb(0xFF, 0, 0xFF, 0)
        assertContentEquals(intArrayOf(red, green, green, red), bm.argb)
    }

    @Test
    fun bmpPaletteIndexOutOfRangeThrows() {
        assertFailsWith<ImageDecodeException> {
            KiteImage.decode(bmp(1, 1, 8, palette = listOf(0xFF0000), pixels = listOf(5)))
        }
    }

    @Test
    fun bmpRleRejectedWithClearMessage() {
        val e = assertFailsWith<UnsupportedImageException> {
            KiteImage.decode(bmp(2, 2, 8, palette = listOf(0), pixels = listOf(0, 0, 0, 0), compression = 1))
        }
        assertTrue("BI_RLE8" in e.message!!)
    }

    @Test
    fun truncatedBmpThrowsDecodeNotCrash() {
        val whole = bmp(4, 4, 24, pixels = List(16) { 0x123456 })
        for (cut in intArrayOf(2, 10, 14, 30, 54, whole.size - 1)) {
            assertFailsWith<ImageDecodeException>("cut at $cut") {
                KiteImage.decode(whole.copyOf(cut))
            }
        }
    }
}
