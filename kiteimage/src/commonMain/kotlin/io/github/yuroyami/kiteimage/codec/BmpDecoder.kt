package io.github.yuroyami.kiteimage.codec

import io.github.yuroyami.kiteimage.ImageDecodeException
import io.github.yuroyami.kiteimage.KiteBitmap
import io.github.yuroyami.kiteimage.UnsupportedImageException
import io.github.yuroyami.kiteimage.internal.Budget
import io.github.yuroyami.kiteimage.internal.ByteReader

/**
 * BMP (Windows DIB) decoder. Ported against `stb_image.h`'s `stbi__bmp_load` and
 * the commons-imaging BMP parser, and extended past both to cover the whole
 * uncompressed-and-RLE family:
 *
 *  - headers: BITMAPCOREHEADER (12, OS/2 v1), BITMAPINFOHEADER (40), the V2/V3
 *    mask extensions (52/56), OS/2 v2 (64) and BITMAPV4/V5 (108/124)
 *  - depths 1, 2, 4, 8 (palette), 16, 24 and 32 (direct colour)
 *  - compressions: BI_RGB, BI_RLE8, BI_RLE4 (runs, absolute mode, line-end,
 *    delta skips), BI_BITFIELDS and BI_ALPHABITFIELDS with arbitrary channel
 *    masks (any width, any position, scaled to 8 bits by the standard
 *    max-value ratio rather than a bit shift, so 5-bit 31 lands on 255)
 *  - bottom-up (positive height, the norm) and top-down (negative) row order
 *  - rows padded to 4-byte boundaries
 *  - the stb pragmatism: a 32-bit image whose alpha plane is entirely zero is
 *    treated as opaque; countless real files write 0 there and mean "no alpha"
 *
 * Pixels an RLE stream never writes (a delta jump, or a row that ends early)
 * stay fully transparent. The format calls them undefined; leaving a hole is
 * what browsers do and it is the only choice that can't invent colour.
 *
 * BI_JPEG and BI_PNG (a whole other image embedded in the pixel array) are
 * rejected by name: they are a container trick, not a BMP encoding, and the
 * caller can simply hand the inner bytes back to `KiteImage.decode`.
 */
internal object BmpDecoder {

    private const val MAX_DIMENSION = 1 shl 24       // 16M px per side
    private const val MAX_PIXELS = 1L shl 28         // 268M px ≈ 1 GiB of ARGB; decompression-bomb guard

    private const val BI_RGB = 0
    private const val BI_RLE8 = 1
    private const val BI_RLE4 = 2
    private const val BI_BITFIELDS = 3
    private const val BI_JPEG = 4
    private const val BI_PNG = 5
    private const val BI_ALPHABITFIELDS = 6

    fun decode(data: ByteArray): KiteBitmap {
        val r = ByteReader(data)

        // BITMAPFILEHEADER: "BM", file size (unreliable, ignored), 2×u16 reserved,
        // u32 offset from file start to the pixel array.
        if (r.u8() != 'B'.code || r.u8() != 'M'.code) {
            throw ImageDecodeException("not a BMP: missing 'BM' magic")
        }
        r.skip(8)
        val pixelOffset = r.u32le()

        // DIB header. Its first field is its own size, which selects the version.
        val dibStart = r.pos
        val dibSize = r.u32le().toInt()
        val core = dibSize == 12
        when (dibSize) {
            12, 40, 52, 56, 64, 108, 124 -> Unit
            else -> throw ImageDecodeException("BMP: unknown DIB header size $dibSize")
        }

        val width: Int
        val height: Int
        val topDown: Boolean
        val bpp: Int
        var compression = BI_RGB
        var clrUsed = 0

        if (core) {
            // OS/2 v1: 16-bit unsigned dimensions, no compression field at all.
            width = r.u16le()
            height = r.u16le()
            topDown = false
            if (r.u16le() != 1) throw ImageDecodeException("BMP: planes must be 1")
            bpp = r.u16le()
        } else {
            width = r.i32le()
            val rawHeight = r.i32le()
            topDown = rawHeight < 0
            height = if (topDown) -rawHeight else rawHeight
            if (r.u16le() != 1) throw ImageDecodeException("BMP: planes must be 1")
            bpp = r.u16le()
            compression = r.u32le().toInt()
            r.skip(12)                                   // image size, x/y pixels per metre
            clrUsed = r.u32le().toInt()
            r.skip(4)                                    // biClrImportant
        }

        if (width <= 0 || height <= 0) throw ImageDecodeException("BMP: bad dimensions ${width}x$height")
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw ImageDecodeException("BMP: dimensions ${width}x$height exceed the $MAX_DIMENSION limit")
        }
        if (width.toLong() * height > MAX_PIXELS) {
            throw ImageDecodeException("BMP: ${width}x$height exceeds the $MAX_PIXELS-pixel safety limit")
        }
        if (!Budget.fits(width, height, data.size)) {
            throw ImageDecodeException("BMP: ${width}x$height cannot come from ${data.size} bytes")
        }
        when (compression) {
            BI_JPEG -> throw UnsupportedImageException("BMP wrapping a JPEG (BI_JPEG) is not a BMP encoding")
            BI_PNG -> throw UnsupportedImageException("BMP wrapping a PNG (BI_PNG) is not a BMP encoding")
            BI_RGB, BI_RLE8, BI_RLE4, BI_BITFIELDS, BI_ALPHABITFIELDS -> Unit
            else -> throw UnsupportedImageException("BMP compression $compression is not supported")
        }
        if (bpp !in intArrayOf(1, 2, 4, 8, 16, 24, 32)) {
            throw UnsupportedImageException("BMP with $bpp bpp is not supported (1, 2, 4, 8, 16, 24 or 32)")
        }
        if (compression == BI_RLE8 && bpp != 8) throw ImageDecodeException("BMP: BI_RLE8 needs 8 bpp, got $bpp")
        if (compression == BI_RLE4 && bpp != 4) throw ImageDecodeException("BMP: BI_RLE4 needs 4 bpp, got $bpp")
        if ((compression == BI_RLE8 || compression == BI_RLE4) && topDown) {
            throw ImageDecodeException("BMP: RLE rows cannot be top-down")
        }
        if ((compression == BI_BITFIELDS || compression == BI_ALPHABITFIELDS) && bpp != 16 && bpp != 32) {
            throw ImageDecodeException("BMP: BI_BITFIELDS needs 16 or 32 bpp, got $bpp")
        }

        // --- channel masks ------------------------------------------------------
        // V2 and later store them in the header; a plain BITMAPINFOHEADER puts them
        // straight after it, before the palette.
        var maskR = 0; var maskG = 0; var maskB = 0; var maskA = 0
        val bitfields = compression == BI_BITFIELDS || compression == BI_ALPHABITFIELDS
        // V2/V3/V4/V5 carry the masks as header fields at offset 40. A plain
        // BITMAPINFOHEADER (and OS/2 v2, whose offset-40 fields are something else
        // entirely) puts them straight after the header instead, before the palette.
        val masksInHeader = dibSize == 52 || dibSize == 56 || dibSize >= 108
        var inlineMaskBytes = 0
        if (masksInHeader) {
            r.pos = dibStart + 40
            maskR = r.u32le().toInt(); maskG = r.u32le().toInt(); maskB = r.u32le().toInt()
            if (dibSize != 52) maskA = r.u32le().toInt()
        } else if (bitfields) {
            r.pos = dibStart + dibSize
            maskR = r.u32le().toInt(); maskG = r.u32le().toInt(); maskB = r.u32le().toInt()
            inlineMaskBytes = 12
            if (compression == BI_ALPHABITFIELDS) {
                maskA = r.u32le().toInt()
                inlineMaskBytes = 16
            }
        }
        if ((maskR or maskG or maskB) == 0) {
            // Defaults for BI_RGB (and for a BI_BITFIELDS file that left them blank).
            when (bpp) {
                16 -> { maskR = 0x7C00; maskG = 0x03E0; maskB = 0x001F }
                32 -> { maskR = 0x00FF0000; maskG = 0x0000FF00; maskB = 0x000000FF; maskA = 0xFF shl 24 }
            }
        }

        // --- palette ------------------------------------------------------------
        val paletteEntry = if (core) 3 else 4
        val palette: IntArray? = if (bpp <= 8) {
            val maxEntries = 1 shl bpp
            // Palette starts right after the header (plus any inline masks) and runs
            // until the pixel array. A BITMAPCOREHEADER has no biClrUsed field at
            // all, and plenty of writers short-change the table, so the gap before
            // the pixels is the honest upper bound.
            val paletteStart = dibStart + dibSize + inlineMaskBytes
            val room = (pixelOffset.toInt() - paletteStart) / paletteEntry
            val entries = when {
                clrUsed in 1..maxEntries -> clrUsed
                room in 1 until maxEntries -> room
                else -> maxEntries
            }
            r.pos = paletteStart
            if (room <= 0) {
                // No table at all: a grayscale ramp is the only defensible guess,
                // and it is what a 1-bit file without a palette always means.
                IntArray(maxEntries) { i ->
                    val v = if (maxEntries == 1) 0 else i * 255 / (maxEntries - 1)
                    (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
            } else {
                IntArray(entries) {
                    // Entries are BGR(X); the X byte is reserved, not alpha.
                    val b = r.u8(); val g = r.u8(); val rr = r.u8()
                    if (paletteEntry == 4) r.u8()
                    (0xFF shl 24) or (rr shl 16) or (g shl 8) or b
                }
            }
        } else null

        // Pixel rows start at pixelOffset regardless of where the palette ended.
        if (pixelOffset > data.size) {
            throw ImageDecodeException("BMP: pixel offset $pixelOffset beyond file size ${data.size}")
        }
        r.pos = pixelOffset.toInt()

        val argb = IntArray(width * height)
        return when (compression) {
            BI_RLE8 -> { decodeRle(r, argb, width, height, palette!!, fourBit = false); KiteBitmap(width, height, argb) }
            BI_RLE4 -> { decodeRle(r, argb, width, height, palette!!, fourBit = true); KiteBitmap(width, height, argb) }
            else -> {
                decodeUncompressed(r, argb, width, height, topDown, bpp, palette, maskR, maskG, maskB, maskA)
                KiteBitmap(width, height, argb)
            }
        }
    }

    // --- uncompressed / bitfield rows -------------------------------------------

    private fun decodeUncompressed(
        r: ByteReader,
        argb: IntArray,
        width: Int,
        height: Int,
        topDown: Boolean,
        bpp: Int,
        palette: IntArray?,
        maskR: Int, maskG: Int, maskB: Int, maskA: Int,
    ) {
        // Rows are padded to a 4-byte boundary; the padding is part of the stride.
        val rowBits = width * bpp
        val rowStride = ((rowBits + 31) / 32) * 4
        val rowBytes = (rowBits + 7) / 8
        val padding = rowStride - rowBytes

        val shiftR = lowestSetBit(maskR); val scaleR = channelScale(maskR)
        val shiftG = lowestSetBit(maskG); val scaleG = channelScale(maskG)
        val shiftB = lowestSetBit(maskB); val scaleB = channelScale(maskB)
        val shiftA = lowestSetBit(maskA); val scaleA = channelScale(maskA)
        var alphaSeen = 0

        for (y0 in 0 until height) {
            val y = if (topDown) y0 else height - 1 - y0
            var i = y * width
            val row = r.bytes(rowBytes)

            when (bpp) {
                1, 2, 4, 8 -> {
                    val pal = palette!!
                    for (x in 0 until width) {
                        val bitPos = x * bpp
                        val byte = row[bitPos ushr 3].toInt() and 0xFF
                        val idx = (byte ushr (8 - bpp - (bitPos and 7))) and ((1 shl bpp) - 1)
                        if (idx >= pal.size) {
                            throw ImageDecodeException("BMP: palette index $idx out of ${pal.size} entries")
                        }
                        argb[i++] = pal[idx]
                    }
                }
                16 -> for (x in 0 until width) {
                    val v = (row[x * 2].toInt() and 0xFF) or ((row[x * 2 + 1].toInt() and 0xFF) shl 8)
                    val a = if (maskA != 0) scale((v and maskA) ushr shiftA, scaleA) else 0xFF
                    alphaSeen = alphaSeen or a
                    argb[i++] = (a shl 24) or
                        (scale((v and maskR) ushr shiftR, scaleR) shl 16) or
                        (scale((v and maskG) ushr shiftG, scaleG) shl 8) or
                        scale((v and maskB) ushr shiftB, scaleB)
                }
                24 -> for (x in 0 until width) {
                    val b = row[x * 3].toInt() and 0xFF
                    val g = row[x * 3 + 1].toInt() and 0xFF
                    val rr = row[x * 3 + 2].toInt() and 0xFF
                    argb[i++] = (0xFF shl 24) or (rr shl 16) or (g shl 8) or b
                }
                32 -> for (x in 0 until width) {
                    val v = (row[x * 4].toInt() and 0xFF) or
                        ((row[x * 4 + 1].toInt() and 0xFF) shl 8) or
                        ((row[x * 4 + 2].toInt() and 0xFF) shl 16) or
                        ((row[x * 4 + 3].toInt() and 0xFF) shl 24)
                    val a = if (maskA != 0) scale((v ushr shiftA) and maskLow(maskA, shiftA), scaleA) else 0xFF
                    alphaSeen = alphaSeen or a
                    argb[i++] = (a shl 24) or
                        (scale((v ushr shiftR) and maskLow(maskR, shiftR), scaleR) shl 16) or
                        (scale((v ushr shiftG) and maskLow(maskG, shiftG), scaleG) shl 8) or
                        scale((v ushr shiftB) and maskLow(maskB, shiftB), scaleB)
                }
            }
            if (padding > 0) r.skip(padding)
        }

        // The stb rule: an all-zero alpha plane means the writer meant "opaque".
        if ((bpp == 32 || bpp == 16) && maskA != 0 && alphaSeen == 0) {
            for (j in argb.indices) argb[j] = argb[j] or (0xFF shl 24)
        }
    }

    /** Bit index of a mask's lowest set bit, 0 for an empty mask. */
    private fun lowestSetBit(mask: Int): Int {
        if (mask == 0) return 0
        var n = 0
        var m = mask
        while (m and 1 == 0) { m = m ushr 1; n++ }
        return n
    }

    /** The mask shifted down to bit 0, so a shifted value can be re-masked. */
    private fun maskLow(mask: Int, shift: Int): Int = mask ushr shift

    /** Maximum value a mask's field can hold, i.e. `2^width - 1`. */
    private fun channelScale(mask: Int): Int {
        if (mask == 0) return 0
        var m = mask ushr lowestSetBit(mask)
        var bits = 0
        while (m != 0) { m = m ushr 1; bits++ }
        return (1 shl bits) - 1
    }

    /**
     * Scale a field value to 0..255 by ratio, not by bit shift: a 5-bit channel's
     * maximum 31 must become 255, and `v shl 3` would stop at 248 (visibly grey
     * whites). This is the same `v * 255 / max` every reference decoder uses.
     */
    private fun scale(value: Int, max: Int): Int =
        if (max <= 0) 0 else if (max == 255) value else (value * 255 + max / 2) / max

    // --- run-length rows ---------------------------------------------------------

    /**
     * BI_RLE8 / BI_RLE4. Both are a stream of (count, value) pairs with a shared
     * escape vocabulary: end-of-line, end-of-bitmap, a delta jump, and absolute
     * runs of literal indices padded to a 16-bit boundary. Rows fill bottom-up.
     */
    private fun decodeRle(
        r: ByteReader,
        argb: IntArray,
        width: Int,
        height: Int,
        palette: IntArray,
        fourBit: Boolean,
    ) {
        var x = 0
        var y = height - 1                     // bottom-up

        fun put(index: Int) {
            if (x >= width || y < 0) return    // runs that overshoot are clipped, not fatal
            if (index >= palette.size) {
                throw ImageDecodeException("BMP: RLE palette index $index out of ${palette.size} entries")
            }
            argb[y * width + x] = palette[index]
            x++
        }

        while (true) {
            if (r.remaining < 2) return        // truncated stream: keep what decoded
            val count = r.u8()
            val value = r.u8()

            if (count > 0) {
                // Encoded run. RLE4 alternates the two nibbles of `value`.
                if (fourBit) {
                    val hi = (value ushr 4) and 0x0F
                    val lo = value and 0x0F
                    for (n in 0 until count) put(if (n and 1 == 0) hi else lo)
                } else {
                    repeat(count) { put(value) }
                }
                continue
            }

            when (value) {
                0 -> { x = 0; y-- }                       // end of line
                1 -> return                               // end of bitmap
                2 -> {                                    // delta: skip right and down
                    if (r.remaining < 2) return
                    x += r.u8()
                    y -= r.u8()
                    if (y < 0) return
                }
                else -> {                                 // absolute mode: `value` literals
                    if (fourBit) {
                        // Two indices per byte, and the run pads to a 16-bit boundary.
                        val bytes = (value + 1) / 2
                        if (r.remaining < bytes) return
                        var n = 0
                        while (n < value) {
                            val byte = r.u8()
                            put((byte ushr 4) and 0x0F)
                            n++
                            if (n < value) {
                                put(byte and 0x0F)
                                n++
                            }
                        }
                        if (bytes and 1 != 0 && r.remaining >= 1) r.skip(1)
                    } else {
                        if (r.remaining < value) return
                        repeat(value) { put(r.u8()) }
                        if (value and 1 != 0 && r.remaining >= 1) r.skip(1)
                    }
                }
            }
        }
    }
}
