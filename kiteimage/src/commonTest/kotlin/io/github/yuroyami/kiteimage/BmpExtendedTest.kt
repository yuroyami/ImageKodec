package io.github.yuroyami.kiteimage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The BMP corners past plain BI_RGB: the OS/2 core header, sub-byte palette
 * depths, 16-bit and masked direct colour, and both run-length encodings.
 *
 * The builder below writes the raw pixel array itself, so each test states the
 * exact bytes the format defines and the expectation is readable next to them.
 */
class BmpExtendedTest {

    /**
     * Low-level DIB writer. [pixelData] goes into the file verbatim, which is what
     * lets the RLE tests hand-write a run stream.
     */
    private fun dib(
        dibSize: Int,
        width: Int,
        height: Int,                       // negative = top-down
        bpp: Int,
        compression: Int = 0,
        masks: List<Int> = emptyList(),    // inline masks, only for a 40-byte header
        headerMasks: List<Int> = emptyList(),  // in-header masks, for V2/V3/V4/V5
        palette: List<Int> = emptyList(),  // 0xRRGGBB
        clrUsed: Int = palette.size,
        pixelData: ByteArray,
    ): ByteArray {
        val out = ArrayList<Byte>()
        fun u8(v: Int) = out.add((v and 0xFF).toByte())
        fun u16(v: Int) { u8(v); u8(v ushr 8) }
        fun u32(v: Int) { u16(v); u16(v ushr 16) }

        val entrySize = if (dibSize == 12) 3 else 4
        val pixelOffset = 14 + dibSize + masks.size * 4 + palette.size * entrySize

        u8('B'.code); u8('M'.code)
        u32(pixelOffset + pixelData.size)
        u32(0)
        u32(pixelOffset)

        u32(dibSize)
        if (dibSize == 12) {
            u16(width); u16(height); u16(1); u16(bpp)
        } else {
            u32(width); u32(height); u16(1); u16(bpp)
            u32(compression)
            u32(pixelData.size)
            u32(0); u32(0)
            u32(clrUsed)
            u32(0)
            // Header-resident masks (V2 onwards) then whatever padding the header
            // version still owes.
            var written = 40
            for (m in headerMasks) { u32(m); written += 4 }
            while (written < dibSize) { u8(0); written++ }
        }
        for (m in masks) u32(m)
        for (c in palette) {
            u8(c and 0xFF); u8((c ushr 8) and 0xFF); u8((c ushr 16) and 0xFF)
            if (entrySize == 4) u8(0)
        }
        pixelData.forEach { out.add(it) }
        return out.toByteArray()
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { (v[it] and 0xFF).toByte() }

    private val red = argb(0xFF, 0xFF, 0, 0)
    private val green = argb(0xFF, 0, 0xFF, 0)
    private val blue = argb(0xFF, 0, 0, 0xFF)
    private val white = argb(0xFF, 0xFF, 0xFF, 0xFF)
    private val black = argb(0xFF, 0, 0, 0)

    // --- headers and sub-byte depths --------------------------------------------

    @Test
    fun os2CoreHeaderWithThreeBytePalette() {
        // 12-byte header, unsigned dimensions, 3-byte palette entries, bottom-up.
        val bm = KiteImage.decode(
            dib(
                dibSize = 12, width = 2, height = 2, bpp = 8,
                palette = listOf(0xFF0000, 0x00FF00),
                // Row stride pads to 4 bytes. Bottom row first.
                pixelData = bytes(1, 0, 0, 0, 0, 1, 0, 0),
            ),
        )
        assertEquals(2, bm.width)
        assertEquals(2, bm.height)
        assertEquals(red, bm[0, 0]); assertEquals(green, bm[1, 0])   // top row = second in file
        assertEquals(green, bm[0, 1]); assertEquals(red, bm[1, 1])
    }

    @Test
    fun oneBitPalette() {
        // 8 pixels in one byte, MSB first.
        val bm = KiteImage.decode(
            dib(
                dibSize = 40, width = 8, height = 1, bpp = 1,
                palette = listOf(0x000000, 0xFFFFFF),
                pixelData = bytes(0b10110001, 0, 0, 0),
            ),
        )
        val expect = intArrayOf(white, black, white, white, black, black, black, white)
        for (x in 0 until 8) assertEquals(expect[x], bm[x, 0], "pixel $x")
    }

    @Test
    fun twoBitPalette() {
        // Four indices per byte: 0, 1, 2, 3.
        val bm = KiteImage.decode(
            dib(
                dibSize = 40, width = 4, height = 1, bpp = 2,
                palette = listOf(0x000000, 0xFF0000, 0x00FF00, 0x0000FF),
                pixelData = bytes(0b00_01_10_11, 0, 0, 0),
            ),
        )
        assertEquals(black, bm[0, 0])
        assertEquals(red, bm[1, 0])
        assertEquals(green, bm[2, 0])
        assertEquals(blue, bm[3, 0])
    }

    @Test
    fun fourBitPalette() {
        val bm = KiteImage.decode(
            dib(
                dibSize = 40, width = 3, height = 1, bpp = 4,
                palette = listOf(0x000000, 0xFF0000, 0x00FF00),
                pixelData = bytes(0x12, 0x00, 0, 0),
            ),
        )
        assertEquals(red, bm[0, 0])
        assertEquals(green, bm[1, 0])
        assertEquals(black, bm[2, 0])
    }

    // --- 16-bit and masked colour ------------------------------------------------

    @Test
    fun sixteenBitDefaultsToFiveFiveFive() {
        // 0x7C00 = pure red at full 5-bit intensity; 31 must scale to 255, not 248.
        val bm = KiteImage.decode(
            dib(
                dibSize = 40, width = 2, height = 1, bpp = 16,
                pixelData = bytes(0x00, 0x7C, 0x1F, 0x00),
            ),
        )
        assertEquals(red, bm[0, 0], "5-bit max must reach 255")
        assertEquals(blue, bm[1, 0])
    }

    @Test
    fun sixteenBitBitfieldsFiveSixFive() {
        // Inline masks after a 40-byte header: R=0xF800, G=0x07E0, B=0x001F.
        val bm = KiteImage.decode(
            dib(
                dibSize = 40, width = 2, height = 1, bpp = 16, compression = 3,
                masks = listOf(0xF800, 0x07E0, 0x001F),
                pixelData = bytes(0xE0, 0x07, 0x00, 0xF8),
            ),
        )
        assertEquals(green, bm[0, 0], "6-bit max must reach 255")
        assertEquals(red, bm[1, 0])
    }

    @Test
    fun thirtyTwoBitBitfieldsWithAlphaFromAV4Header() {
        // BITMAPV4HEADER carries its masks as header fields, ARGB order here.
        val half = 0x80
        val bm = KiteImage.decode(
            dib(
                dibSize = 108, width = 1, height = 1, bpp = 32, compression = 3,
                headerMasks = listOf(0x00FF0000, 0x0000FF00, 0x000000FF, 0xFF shl 24),
                pixelData = bytes(0x40, 0x20, 0x10, half),   // little-endian BGRA
            ),
        )
        val p = bm[0, 0]
        assertEquals(half, p ushr 24, "alpha")
        assertEquals(0x10, (p ushr 16) and 0xFF, "red")
        assertEquals(0x20, (p ushr 8) and 0xFF, "green")
        assertEquals(0x40, p and 0xFF, "blue")
    }

    @Test
    fun allZeroAlphaPlaneStillMeansOpaque() {
        val bm = KiteImage.decode(
            dib(
                dibSize = 40, width = 2, height = 1, bpp = 32,
                pixelData = bytes(0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0x00, 0x00),
            ),
        )
        assertEquals(red, bm[0, 0])
        assertEquals(blue, bm[1, 0])
    }

    // --- run-length encodings ----------------------------------------------------

    @Test
    fun rle8EncodedRuns() {
        // Two runs per row, bottom-up: [3×idx1][1×idx2] then [4×idx0].
        val bm = KiteImage.decode(
            dib(
                dibSize = 40, width = 4, height = 2, bpp = 8, compression = 1,
                palette = listOf(0x000000, 0xFF0000, 0x00FF00),
                pixelData = bytes(
                    4, 0, 0, 0,              // bottom row: 4 × index 0, end of line
                    3, 1, 1, 2, 0, 0,        // top row: 3 × index 1, 1 × index 2, end of line
                    0, 1,                    // end of bitmap
                ),
            ),
        )
        assertEquals(red, bm[0, 0]); assertEquals(red, bm[1, 0])
        assertEquals(red, bm[2, 0]); assertEquals(green, bm[3, 0])
        for (x in 0 until 4) assertEquals(black, bm[x, 1], "bottom row pixel $x")
    }

    @Test
    fun rle8AbsoluteModePadsToWordBoundary() {
        // Absolute run of 3 literals needs a pad byte; a mis-handled pad shifts
        // everything after it.
        val bm = KiteImage.decode(
            dib(
                dibSize = 40, width = 4, height = 1, bpp = 8, compression = 1,
                palette = listOf(0x000000, 0xFF0000, 0x00FF00, 0x0000FF),
                pixelData = bytes(
                    0, 3, 1, 2, 3, 0,        // absolute: indices 1,2,3 + pad
                    1, 0,                    // then one more pixel of index 0
                    0, 1,
                ),
            ),
        )
        assertEquals(red, bm[0, 0])
        assertEquals(green, bm[1, 0])
        assertEquals(blue, bm[2, 0])
        assertEquals(black, bm[3, 0])
    }

    @Test
    fun rle8DeltaLeavesSkippedPixelsTransparent() {
        val bm = KiteImage.decode(
            dib(
                dibSize = 40, width = 4, height = 2, bpp = 8, compression = 1,
                palette = listOf(0x000000, 0xFF0000),
                pixelData = bytes(
                    1, 1,                    // bottom row: one red pixel at x=0
                    0, 2, 2, 1,              // delta: +2 right, +1 up
                    1, 1,                    // one red pixel there
                    0, 1,
                ),
            ),
        )
        // Bottom row (y = 1): red at x=0, everything else untouched.
        assertEquals(red, bm[0, 1])
        assertEquals(0, bm[1, 1], "delta-skipped pixels stay transparent")
        // The delta moved to x=3 of the row above (y = 0).
        assertEquals(red, bm[3, 0])
        assertEquals(0, bm[0, 0])
    }

    @Test
    fun rle4AlternatesNibblesInAnEncodedRun() {
        val bm = KiteImage.decode(
            dib(
                dibSize = 40, width = 4, height = 1, bpp = 4, compression = 2,
                palette = listOf(0x000000, 0xFF0000, 0x00FF00),
                pixelData = bytes(
                    4, 0x12,                 // 4 pixels alternating index 1, 2
                    0, 1,
                ),
            ),
        )
        assertEquals(red, bm[0, 0])
        assertEquals(green, bm[1, 0])
        assertEquals(red, bm[2, 0])
        assertEquals(green, bm[3, 0])
    }

    @Test
    fun rle4AbsoluteModePacksTwoIndicesPerByte() {
        val bm = KiteImage.decode(
            dib(
                dibSize = 40, width = 3, height = 1, bpp = 4, compression = 2,
                palette = listOf(0x000000, 0xFF0000, 0x00FF00, 0x0000FF),
                pixelData = bytes(
                    0, 3, 0x12, 0x30, 0,     // absolute: 1, 2, 3 (packed) + pad byte
                    0, 1,
                ),
            ),
        )
        assertEquals(red, bm[0, 0])
        assertEquals(green, bm[1, 0])
        assertEquals(blue, bm[2, 0])
    }

    @Test
    fun rleRunOvershootingTheRowIsClippedNotFatal() {
        val bm = KiteImage.decode(
            dib(
                dibSize = 40, width = 2, height = 1, bpp = 8, compression = 1,
                palette = listOf(0x000000, 0xFF0000),
                pixelData = bytes(9, 1, 0, 1),   // run of 9 into a 2-wide row
            ),
        )
        assertEquals(red, bm[0, 0])
        assertEquals(red, bm[1, 0])
    }

    @Test
    fun truncatedRleKeepsWhatDecoded() {
        val whole = dib(
            dibSize = 40, width = 4, height = 1, bpp = 8, compression = 1,
            palette = listOf(0x000000, 0xFF0000),
            pixelData = bytes(2, 1, 2, 1, 0, 1),
        )
        val bm = KiteImage.decode(whole.copyOf(whole.size - 3))
        assertEquals(red, bm[0, 0])
        assertEquals(red, bm[1, 0])
    }

    // --- probe agreement ---------------------------------------------------------

    @Test
    fun probeAgreesWithTheseDecodes() {
        val samples = listOf(
            "core" to dib(12, 2, 2, 8, palette = listOf(0xFF0000, 0x00FF00), pixelData = bytes(1, 0, 0, 0, 0, 1, 0, 0)),
            "1-bit" to dib(40, 8, 1, 1, palette = listOf(0, 0xFFFFFF), pixelData = bytes(0xAA, 0, 0, 0)),
            "16-bit" to dib(40, 2, 1, 16, pixelData = bytes(0x00, 0x7C, 0x1F, 0x00)),
            "rle8" to dib(
                40, 4, 1, 8, compression = 1, palette = listOf(0, 0xFF0000),
                pixelData = bytes(4, 1, 0, 1),
            ),
        )
        for ((name, bytes) in samples) {
            val info = KiteImage.probe(bytes)
            val bm = KiteImage.decode(bytes)
            assertEquals(bm.width, info.width, "$name width")
            assertEquals(bm.height, info.height, "$name height")
            assertTrue(info.isDecodable, "$name decodable")
        }
    }
}
