package io.github.yuroyami.kiteimage.codec

import io.github.yuroyami.kiteimage.KiteBitmap

/**
 * BMP encoder. Two shapes, picked by whether the bitmap carries alpha:
 *
 *  - **opaque** → 24-bit BI_RGB under a plain BITMAPINFOHEADER. This is the
 *    lowest common denominator: everything since Windows 3.0 reads it.
 *  - **with alpha** → 32-bit BI_BITFIELDS under a BITMAPV4HEADER with explicit
 *    ARGB channel masks. 32-bit BI_RGB *also* stores four bytes per pixel, but
 *    the fourth is officially reserved, so half the readers in the world throw
 *    it away. Spelling the masks out is the only way to make alpha survive.
 *
 * Rows are written bottom-up (the format's default) and padded to a 4-byte
 * boundary. Output is uncompressed by design: BMP's RLE modes only pay off on
 * palette images, and quantising to a palette is the GIF encoder's job.
 */
internal object BmpEncoder {

    private const val FILE_HEADER = 14
    private const val INFO_HEADER = 40
    private const val V4_HEADER = 108

    fun encode(bitmap: KiteBitmap): ByteArray {
        val alpha = bitmap.hasTransparency()
        return if (alpha) encode32(bitmap) else encode24(bitmap)
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
        c.u32(INFO_HEADER)
        c.i32(w)
        c.i32(h)                       // positive: bottom-up
        c.u16(1)
        c.u16(24)
        c.u32(0)                       // BI_RGB
        c.u32(pixelBytes)
        c.u32(2835); c.u32(2835)       // 72 dpi in pixels per metre, the usual filler
        c.u32(0); c.u32(0)             // clrUsed, clrImportant

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
