package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.BmpEncodeOptions
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.internal.flate.ByteArrayBuilder

/**
 * BMP encoder, from Microsoft's description of the DIB format (BITMAPFILEHEADER,
 * BITMAPINFOHEADER, BITMAPV4HEADER and the RLE8 and RLE4 compressions). By default it writes
 * one of two shapes, picked by whether the bitmap carries alpha:
 *
 *  - **opaque** → 24-bit BI_RGB under a plain BITMAPINFOHEADER. This is the
 *    lowest common denominator: everything since Windows 3.0 reads it.
 *  - **with alpha** → 32-bit BI_BITFIELDS under a BITMAPV4HEADER with explicit
 *    ARGB channel masks. 32-bit BI_RGB *also* stores four bytes per pixel, but
 *    the fourth is officially reserved, so half the readers in the world throw
 *    it away. Spelling the masks out is the only way to make alpha survive.
 *
 * [BmpEncodeOptions] adds palette depths of 1, 4 and 8 bits, which hold an opaque bitmap of few
 * colours exactly, with BI_RLE8 or BI_RLE4 on request, and 16 bits as 5-5-5 BI_RGB.
 *
 * Rows are written bottom-up (the format's default) and padded to a 4-byte boundary.
 */
internal object BmpEncoder {

    private const val FILE_HEADER = 14
    private const val INFO_HEADER = 40
    private const val V4_HEADER = 108

    private const val BI_RGB = 0
    private const val BI_RLE8 = 1
    private const val BI_RLE4 = 2

    fun encode(bitmap: KiteBitmap): ByteArray {
        val alpha = bitmap.hasTransparency()
        return if (alpha) encode32(bitmap) else encode24(bitmap)
    }

    fun encode(bitmap: KiteBitmap, options: BmpEncodeOptions): ByteArray {
        val bits = options.bitsPerPixel ?: return encode(bitmap)
        if (bits == 32) return encode32(bitmap)
        require(!bitmap.hasTransparency()) { "BMP: $bits bits a pixel cannot hold alpha, and the ${bitmap.width}x${bitmap.height} bitmap has some" }
        // A row of the largest depth is under 2^31 bytes for any bitmap, but its sum over the rows may not be.
        val stride = ((bitmap.width.toLong() * bits + 31) / 32) * 4
        require(stride * bitmap.height <= Int.MAX_VALUE - 1024L) {
            "BMP: ${bitmap.width}x${bitmap.height} pixels of $bits bits is more than a file can hold"
        }
        return when (bits) {
            24 -> encode24(bitmap)
            16 -> encode16(bitmap)
            else -> encodePalette(bitmap, bits, options.rle)
        }
    }

    private fun encode24(bitmap: KiteBitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val rowStride = (w * 3 + 3) and 3.inv()
        val pixelBytes = rowStride * h
        val offset = FILE_HEADER + INFO_HEADER
        val out = ByteArray(offset + pixelBytes)
        val c = Cursor(out)

        writeFileHeader(c, out.size, offset)
        writeInfoHeader(c, w, h, 24, BI_RGB, pixelBytes, 0)

        for (y in h - 1 downTo 0) {
            var i = y * w
            var written = 0
            for (x in 0 until w) {
                val p = bitmap.argb[i++]
                c.u8(p and 0xFF)                    // B
                c.u8((p ushr 8) and 0xFF)           // G
                c.u8((p ushr 16) and 0xFF)          // R
                written += 3
            }
            while (written < rowStride) { c.u8(0); written++ }
        }
        return out
    }

    private fun encode32(bitmap: KiteBitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixelBytes = w * 4 * h     // 32-bit rows are always 4-byte aligned
        val offset = FILE_HEADER + V4_HEADER
        val out = ByteArray(offset + pixelBytes)
        val c = Cursor(out)

        writeFileHeader(c, out.size, offset)
        c.u32(V4_HEADER)
        c.i32(w)
        c.i32(h)
        c.u16(1)
        c.u16(32)
        c.u32(3)                       // BI_BITFIELDS
        c.u32(pixelBytes)
        c.u32(2835); c.u32(2835)
        c.u32(0); c.u32(0)
        c.u32(0x00FF0000)              // red mask
        c.u32(0x0000FF00)              // green mask
        c.u32(0x000000FF)              // blue mask
        c.u32(0xFF shl 24)             // alpha mask
        c.u32(0x73524742)              // "sRGB" colour space
        repeat(9) { c.u32(0) }         // CIEXYZTRIPLE endpoints: unused for sRGB
        c.u32(0); c.u32(0); c.u32(0)   // gamma R/G/B

        for (y in h - 1 downTo 0) {
            var i = y * w
            for (x in 0 until w) {
                val p = bitmap.argb[i++]
                c.u8(p and 0xFF)                    // B
                c.u8((p ushr 8) and 0xFF)           // G
                c.u8((p ushr 16) and 0xFF)          // R
                c.u8((p ushr 24) and 0xFF)          // A
            }
        }
        return out
    }

    /**
     * 5-5-5 BI_RGB, the 16-bit layout a BITMAPINFOHEADER implies without masks: the top bit unused,
     * then red, green and blue. Each channel is rounded to the nearest of its 32 levels, which the
     * decoder's `v * 255 / 31` scaling gives back exactly.
     */
    private fun encode16(bitmap: KiteBitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val rowStride = (w * 2 + 3) and 3.inv()
        val pixelBytes = rowStride * h
        val offset = FILE_HEADER + INFO_HEADER
        val out = ByteArray(offset + pixelBytes)
        val c = Cursor(out)
        writeFileHeader(c, out.size, offset)
        writeInfoHeader(c, w, h, 16, BI_RGB, pixelBytes, 0)
        fun five(v: Int) = (v * 31 + 127) / 255
        for (y in h - 1 downTo 0) {
            for (x in 0 until w) {
                val p = bitmap.argb[y * w + x]
                c.u16((five((p ushr 16) and 0xFF) shl 10) or (five((p ushr 8) and 0xFF) shl 5) or five(p and 0xFF))
            }
            repeat(rowStride - w * 2) { c.u8(0) }
        }
        return out
    }

    private fun encodePalette(bitmap: KiteBitmap, bits: Int, rle: Boolean): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val px = bitmap.argb
        val limit = 1 shl bits
        val index = HashMap<Int, Int>()
        val colours = ArrayList<Int>()
        val indices = IntArray(px.size)
        for (i in px.indices) {
            val p = px[i]
            indices[i] = index.getOrPut(p) {
                require(colours.size < limit) {
                    "BMP: $bits bits a pixel hold $limit colours, and the ${w}x$h bitmap has more"
                }
                colours += p
                colours.size - 1
            }
        }
        val pixels = if (rle) {
            if (bits == 8) rle8(indices, w, h) else rle4(indices, w, h)
        } else {
            val stride = ((w * bits + 31) / 32) * 4
            val data = ByteArray(stride * h)
            val perByte = 8 / bits
            for (y in 0 until h) {
                val base = (h - 1 - y) * stride
                for (x in 0 until w) {
                    val at = base + x / perByte
                    data[at] = (data[at].toInt() or (indices[y * w + x] shl (8 - bits * (x % perByte + 1)))).toByte()
                }
            }
            data
        }
        val offset = FILE_HEADER + INFO_HEADER + 4 * colours.size
        val out = ByteArray(offset + pixels.size)
        val c = Cursor(out)
        writeFileHeader(c, out.size, offset)
        writeInfoHeader(c, w, h, bits, if (!rle) BI_RGB else if (bits == 8) BI_RLE8 else BI_RLE4, pixels.size, colours.size)
        for (p in colours) {
            c.u8(p and 0xFF)
            c.u8((p ushr 8) and 0xFF)
            c.u8((p ushr 16) and 0xFF)
            c.u8(0)                    // RGBQUAD's reserved byte
        }
        pixels.copyInto(out, offset)
        return out
    }

    /**
     * BI_RLE8, bottom-up: a run of two or more equal indices as an encoded run (count, index), three
     * or more indices that do not repeat as an absolute run (0, count, the indices, padded to a
     * 16-bit boundary), and one or two left over as runs of one. Each row ends with end of line
     * (0, 0), and the bitmap with end of bitmap (0, 1).
     */
    private fun rle8(indices: IntArray, w: Int, h: Int): ByteArray {
        val out = ByteArrayBuilder(w * h / 2 + 16)
        fun put(v: Int) = out.append(v.toByte())
        for (y in h - 1 downTo 0) {
            val row = y * w
            var x = 0
            var literal = 0
            fun flush(until: Int) {
                var from = literal
                while (from < until) {
                    val n = minOf(255, until - from)
                    if (n >= 3) {
                        put(0)
                        put(n)
                        for (k in 0 until n) put(indices[row + from + k])
                        if (n and 1 != 0) put(0)
                    } else {
                        for (k in 0 until n) {
                            put(1)
                            put(indices[row + from + k])
                        }
                    }
                    from += n
                }
            }
            while (x < w) {
                var run = 1
                while (x + run < w && run < 255 && indices[row + x + run] == indices[row + x]) run++
                if (run >= 2) {
                    flush(x)
                    put(run)
                    put(indices[row + x])
                    x += run
                    literal = x
                } else {
                    x++
                }
            }
            flush(w)
            put(0)
            put(if (y == 0) 1 else 0)
        }
        return out.toByteArray()
    }

    /**
     * BI_RLE4, bottom-up. An encoded run (count, two indices) repeats its two indices in turn, so
     * it codes a stretch of one colour or of two alternating, as dithering leaves; three or more
     * indices of neither kind go as an absolute run, two a byte, padded to a 16-bit boundary.
     * Rows end as in [rle8].
     */
    private fun rle4(indices: IntArray, w: Int, h: Int): ByteArray {
        val out = ByteArrayBuilder(w * h / 4 + 16)
        fun put(v: Int) = out.append(v.toByte())
        for (y in h - 1 downTo 0) {
            val row = y * w
            var x = 0
            var literal = 0
            fun flush(until: Int) {
                var from = literal
                while (from < until) {
                    val n = minOf(255, until - from)
                    if (n >= 3) {
                        put(0)
                        put(n)
                        var k = 0
                        while (k < n) {
                            val hi = indices[row + from + k]
                            val lo = if (k + 1 < n) indices[row + from + k + 1] else 0
                            put((hi shl 4) or lo)
                            k += 2
                        }
                        if (((n + 1) / 2) and 1 != 0) put(0)
                    } else {
                        // One or two indices: a run of that length codes them exactly.
                        val hi = indices[row + from]
                        val lo = if (n == 2) indices[row + from + 1] else 0
                        put(n)
                        put((hi shl 4) or lo)
                    }
                    from += n
                }
            }
            while (x < w) {
                val a = indices[row + x]
                val b = if (x + 1 < w) indices[row + x + 1] else a
                var run = 1
                while (x + run < w && run < 255 && indices[row + x + run] == (if (run and 1 == 0) a else b)) run++
                // Inside an absolute run an index costs half a byte, so a run of two or three stays there.
                if (run >= 4) {
                    flush(x)
                    put(run)
                    put((a shl 4) or b)
                    x += run
                    literal = x
                } else {
                    x++
                }
            }
            flush(w)
            put(0)
            put(if (y == 0) 1 else 0)
        }
        return out.toByteArray()
    }

    private fun writeInfoHeader(c: Cursor, w: Int, h: Int, bits: Int, compression: Int, pixelBytes: Int, colours: Int) {
        c.u32(INFO_HEADER)
        c.i32(w)
        c.i32(h)                       // positive: bottom-up
        c.u16(1)
        c.u16(bits)
        c.u32(compression)
        c.u32(pixelBytes)
        c.u32(2835); c.u32(2835)       // 72 dpi in pixels per metre, the usual filler
        c.u32(colours); c.u32(0)       // clrUsed (0: none, or all 2^bits), clrImportant (0: all)
    }

    private fun writeFileHeader(c: Cursor, fileSize: Int, pixelOffset: Int) {
        c.u8('B'.code); c.u8('M'.code)
        c.u32(fileSize)
        c.u32(0)                       // both reserved words
        c.u32(pixelOffset)
    }

    /** Little-endian write cursor over a pre-sized array. */
    private class Cursor(private val out: ByteArray) {
        private var at = 0
        fun u8(v: Int) { out[at++] = (v and 0xFF).toByte() }
        fun u16(v: Int) { u8(v); u8(v ushr 8) }
        fun u32(v: Int) { u16(v); u16(v ushr 16) }
        fun i32(v: Int) = u32(v)
    }
}
