package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.KiteBitmap16
import io.github.yuroyami.imagekodec.internal.flate.ByteArrayBuilder
import io.github.yuroyami.imagekodec.internal.flate.Zlib

/**
 * TIFF encoder (TIFF 6.0, with the Adobe Deflate compression of the TIFF Technical Notes):
 * a little-endian file of one directory a page, each page in strips of about 8 KiB
 * compressed with Deflate after the horizontal differencing predictor, as libtiff writes
 * with `-c zip -p 2` (or `tiffcp -c zip:p2`).
 *
 * Samples are 8 or 16 bits, unsigned and interleaved: gray (photometric 1) when every
 * pixel of the page is gray, RGB (photometric 2) otherwise, each with an unassociated
 * alpha sample when the page has alpha. Pages after the first are chained, each marked
 * as a page of a multi-page file with its PageNumber.
 */
internal object TiffEncoder {
    private const val SHORT = 3
    private const val LONG = 4
    private const val RATIONAL = 5
    private const val STRIP_BYTES = 8192

    /** A page as the writer sees it: its rows of interleaved samples, little endian at 16 bits. */
    private class Page(
        val width: Int,
        val height: Int,
        val bits: Int,
        val gray: Boolean,
        val alpha: Boolean,
        val row: (y: Int, out: ByteArray) -> Unit,
    ) {
        val samplesPerPixel = (if (gray) 1 else 3) + (if (alpha) 1 else 0)
        val rowBytes = width * samplesPerPixel * (bits / 8)
    }

    fun encode(pages: List<KiteBitmap>): ByteArray = write(pages.map { page(it) })

    fun encode16(pages: List<KiteBitmap16>): ByteArray = write(pages.map { page(it) })

    private fun page(b: KiteBitmap): Page {
        val px = b.argb
        val gray = px.all { ((it ushr 16) and 0xFF) == ((it ushr 8) and 0xFF) && ((it ushr 8) and 0xFF) == (it and 0xFF) }
        val alpha = b.hasTransparency()
        return Page(b.width, b.height, 8, gray, alpha) { y, out ->
            var o = 0
            for (x in 0 until b.width) {
                val p = px[y * b.width + x]
                if (gray) {
                    out[o++] = p.toByte()
                } else {
                    out[o++] = (p ushr 16).toByte()
                    out[o++] = (p ushr 8).toByte()
                    out[o++] = p.toByte()
                }
                if (alpha) out[o++] = (p ushr 24).toByte()
            }
        }
    }

    private fun page(b: KiteBitmap16): Page {
        val ch = b.channels
        val s = b.samples
        return Page(b.width, b.height, 16, gray = ch <= 2, alpha = ch == 2 || ch == 4) { y, out ->
            val base = y * b.width * ch
            for (i in 0 until b.width * ch) {
                val v = s[base + i].toInt()
                out[2 * i] = v.toByte()
                out[2 * i + 1] = (v ushr 8).toByte()
            }
        }
    }

    private fun write(pages: List<Page>): ByteArray {
        require(pages.isNotEmpty()) { "a TIFF needs at least one page" }
        val out = ByteArrayBuilder(1024)
        out.append(byteArrayOf(0x49, 0x49, 42, 0))
        // The offset of the first directory, patched once it is known.
        val firstIfdAt = out.size
        out.append(ByteArray(4))
        var patchAt = firstIfdAt
        for ((index, page) in pages.withIndex()) {
            val strips = strips(page)
            val rowsPerStrip = rowsPerStrip(page)
            val offsets = IntArray(strips.size)
            for ((i, strip) in strips.withIndex()) {
                pad(out)
                offsets[i] = out.size
                out.append(strip)
            }
            pad(out)
            // Values that do not fit in an entry go before the directory.
            val bitsAt = if (page.samplesPerPixel > 2) out.size.also { for (k in 0 until page.samplesPerPixel) short(out, page.bits) } else -1
            val offsetsAt = if (strips.size > 1) out.size.also { for (o in offsets) long(out, o) } else -1
            val countsAt = if (strips.size > 1) out.size.also { for (s in strips) long(out, s.size) } else -1
            val resolutionAt = out.size
            long(out, 72)
            long(out, 1)
            pad(out)
            val ifd = out.size
            patch(out, patchAt, ifd)
            val entries = ArrayList<IntArray>()
            fun entry(tag: Int, type: Int, count: Int, value: Int) {
                entries += intArrayOf(tag, type, count, value)
            }
            entry(254, LONG, 1, if (pages.size > 1) 2 else 0)
            entry(256, LONG, 1, page.width)
            entry(257, LONG, 1, page.height)
            entry(258, SHORT, page.samplesPerPixel, if (bitsAt >= 0) bitsAt else page.bits or (if (page.samplesPerPixel == 2) page.bits shl 16 else 0))
            entry(259, SHORT, 1, 8)
            entry(262, SHORT, 1, if (page.gray) 1 else 2)
            entry(273, LONG, strips.size, if (offsetsAt >= 0) offsetsAt else offsets[0])
            entry(277, SHORT, 1, page.samplesPerPixel)
            entry(278, LONG, 1, rowsPerStrip)
            entry(279, LONG, strips.size, if (countsAt >= 0) countsAt else strips[0].size)
            entry(282, RATIONAL, 1, resolutionAt)
            entry(283, RATIONAL, 1, resolutionAt)
            entry(284, SHORT, 1, 1)
            entry(296, SHORT, 1, 2)
            if (pages.size > 1) entry(297, SHORT, 2, index or (pages.size shl 16))
            entry(317, SHORT, 1, 2)
            if (page.alpha) entry(338, SHORT, 1, 2)
            short(out, entries.size)
            for (e in entries) {
                short(out, e[0])
                short(out, e[1])
                long(out, e[2])
                long(out, e[3])
            }
            patchAt = out.size
            long(out, 0)
        }
        return out.toByteArray()
    }

    private fun rowsPerStrip(page: Page): Int = (STRIP_BYTES / maxOf(1, page.rowBytes)).coerceIn(1, page.height)

    /** Each strip's rows, differenced along the row sample by sample, then compressed. */
    private fun strips(page: Page): List<ByteArray> {
        val rows = rowsPerStrip(page)
        val spp = page.samplesPerPixel
        val row = ByteArray(page.rowBytes)
        val out = ArrayList<ByteArray>()
        var y = 0
        while (y < page.height) {
            val n = minOf(rows, page.height - y)
            val raw = ByteArray(n * page.rowBytes)
            for (r in 0 until n) {
                page.row(y + r, row)
                val o = r * page.rowBytes
                if (page.bits == 8) {
                    for (i in 0 until page.rowBytes) {
                        val prev = if (i >= spp) row[i - spp].toInt() else 0
                        raw[o + i] = (row[i] - prev).toByte()
                    }
                } else {
                    for (i in 0 until page.rowBytes / 2) {
                        val v = (row[2 * i].toInt() and 0xFF) or ((row[2 * i + 1].toInt() and 0xFF) shl 8)
                        val prev = if (i >= spp) (row[2 * (i - spp)].toInt() and 0xFF) or ((row[2 * (i - spp) + 1].toInt() and 0xFF) shl 8) else 0
                        val d = (v - prev) and 0xFFFF
                        raw[o + 2 * i] = d.toByte()
                        raw[o + 2 * i + 1] = (d ushr 8).toByte()
                    }
                }
            }
            out += Zlib.compress(raw)
            y += n
        }
        return out
    }

    private fun pad(out: ByteArrayBuilder) {
        if (out.size % 2 != 0) out.append(ByteArray(1))
    }

    private fun short(out: ByteArrayBuilder, v: Int) = out.append(byteArrayOf(v.toByte(), (v ushr 8).toByte()))

    private fun long(out: ByteArrayBuilder, v: Int) = out.append(byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte()))

    private fun patch(out: ByteArrayBuilder, at: Int, v: Int) {
        out[at] = v.toByte()
        out[at + 1] = (v ushr 8).toByte()
        out[at + 2] = (v ushr 16).toByte()
        out[at + 3] = (v ushr 24).toByte()
    }
}
