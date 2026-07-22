package io.github.yuroyami.kiteimage.codec

import io.github.yuroyami.kiteimage.ImageDecodeException
import io.github.yuroyami.kiteimage.KiteBitmap
import io.github.yuroyami.kiteimage.UnsupportedImageException
import io.github.yuroyami.kiteimage.internal.ByteReader

/**
 * BMP (Windows DIB) decoder. Ported against `stb_image.h`'s `stbi__bmp_load` and
 * the commons-imaging BMP parser; scope is the uncompressed BI_RGB core that
 * covers essentially every BMP found in the wild:
 *
 *  - BITMAPINFOHEADER (40), V4 (108) and V5 (124) headers: the V4/V5 extras
 *    (color space, gamma) don't affect BI_RGB pixel decoding and are skipped
 *  - 8-bit palette, 24-bit BGR, 32-bit BGRA
 *  - bottom-up (positive height, the norm) and top-down (negative) row order
 *  - rows padded to 4-byte boundaries
 *  - the stb pragmatism: a 32-bit image whose alpha plane is entirely zero is
 *    treated as opaque; countless real files write 0 there and mean "no alpha"
 *
 * BI_RLE4/RLE8/BITFIELDS compression, 1/2/4/16-bit depths and the ancient
 * BITMAPCOREHEADER are rejected with a message naming the exact unsupported
 * feature. They can land later if anyone actually hits them.
 */
internal object BmpDecoder {

    private const val MAX_DIMENSION = 1 shl 24       // 16M px per side
    private const val MAX_PIXELS = 1L shl 28         // 268M px ≈ 1 GiB of ARGB; decompression-bomb guard

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
        val dibSize = r.u32le().toInt()
        when (dibSize) {
            40, 108, 124 -> Unit
            12 -> throw UnsupportedImageException("BMP with BITMAPCOREHEADER (OS/2 v1) is not supported")
            else -> throw ImageDecodeException("BMP: unknown DIB header size $dibSize")
        }

        val width = r.i32le()
        val rawHeight = r.i32le()
        val topDown = rawHeight < 0
        val height = if (topDown) -rawHeight else rawHeight

        if (r.u16le() != 1) throw ImageDecodeException("BMP: planes must be 1")
        val bpp = r.u16le()
        val compression = r.u32le().toInt()

        if (width <= 0 || height == 0) throw ImageDecodeException("BMP: bad dimensions ${width}x$rawHeight")
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw ImageDecodeException("BMP: dimensions ${width}x$height exceed the $MAX_DIMENSION limit")
        }
        if (width.toLong() * height > MAX_PIXELS) {
            throw ImageDecodeException("BMP: ${width}x$height exceeds the $MAX_PIXELS-pixel safety limit")
        }

        if (compression != 0) {
            val name = when (compression) {
                1 -> "BI_RLE8"
                2 -> "BI_RLE4"
                3 -> "BI_BITFIELDS"
                else -> "compression=$compression"
            }
            throw UnsupportedImageException("BMP with $name is not supported (only uncompressed BI_RGB)")
        }
        if (bpp != 8 && bpp != 24 && bpp != 32) {
            throw UnsupportedImageException("BMP with $bpp bpp is not supported (8, 24 or 32)")
        }

        // Rest of BITMAPINFOHEADER: image size, resolution, palette counts. Only
        // biClrUsed matters (palette entry count; 0 means "full size for the depth").
        r.skip(12)
        val clrUsed = r.u32le().toInt()
        r.skip(4)
        // V4/V5 extensions don't affect BI_RGB decoding.
        if (dibSize > 40) r.skip(dibSize - 40)

        val palette: IntArray? = if (bpp == 8) {
            val entries = if (clrUsed != 0) clrUsed else 256
            if (entries > 256) throw ImageDecodeException("BMP: palette declares $entries entries (max 256)")
            IntArray(entries) {
                // Palette entries are BGRX little-endian quads; X is ignored.
                val b = r.u8(); val g = r.u8(); val rr = r.u8(); r.u8()
                (0xFF shl 24) or (rr shl 16) or (g shl 8) or b
            }
        } else null

        // Pixel rows start at pixelOffset regardless of where the palette ended.
        if (pixelOffset > data.size) {
            throw ImageDecodeException("BMP: pixel offset $pixelOffset beyond file size ${data.size}")
        }
        r.pos = pixelOffset.toInt()

        val bytesPerPixel = bpp / 8
        val rowStride = (width * bytesPerPixel + 3) and 3.inv()   // rows pad to 4 bytes
        val padding = rowStride - width * bytesPerPixel
        val argb = IntArray(width * height)
        var allAlpha = 0

        for (row in 0 until height) {
            // Bottom-up files store the last visual row first.
            val y = if (topDown) row else height - 1 - row
            var i = y * width
            when (bpp) {
                8 -> {
                    val pal = palette!!
                    for (x in 0 until width) {
                        val idx = r.u8()
                        if (idx >= pal.size) {
                            throw ImageDecodeException("BMP: palette index $idx out of ${pal.size} entries")
                        }
                        argb[i++] = pal[idx]
                    }
                }
                24 -> for (x in 0 until width) {
                    val b = r.u8(); val g = r.u8(); val rr = r.u8()
                    argb[i++] = (0xFF shl 24) or (rr shl 16) or (g shl 8) or b
                }
                32 -> for (x in 0 until width) {
                    val b = r.u8(); val g = r.u8(); val rr = r.u8(); val a = r.u8()
                    allAlpha = allAlpha or a
                    argb[i++] = (a shl 24) or (rr shl 16) or (g shl 8) or b
                }
            }
            if (padding > 0) r.skip(padding)
        }

        // The stb rule: all-zero alpha plane in a 32-bit BI_RGB file means opaque.
        if (bpp == 32 && allAlpha == 0) {
            for (j in argb.indices) argb[j] = argb[j] or (0xFF shl 24)
        }

        return KiteBitmap(width, height, argb)
    }
}
