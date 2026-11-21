package io.github.yuroyami.imagekodec

import io.github.yuroyami.archivekodec.codec.deflate.Deflate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Apple's CgBI PNG (#189), built here by hand as `pngcrush -iphone` writes one: a `CgBI` chunk
 * before `IHDR`, raw deflate in `IDAT`, blue first, and colour premultiplied by alpha.
 */
class PngCgbiTest {

    /** A CgBI file of [rows], each already filtered (its filter byte first), stored with [colorType]. */
    private fun cgbi(width: Int, height: Int, colorType: Int, rows: ByteArray, bitDepth: Int = 8, interlace: Int = 0): ByteArray =
        PNG_SIGNATURE +
            pngChunk("CgBI", byteArrayOf(0x50, 0x00, 0x20, 0x06)) +
            pngChunk("IHDR", beBytes(width) + beBytes(height) + byteArrayOf(bitDepth.toByte(), colorType.toByte(), 0, 0, interlace.toByte())) +
            pngChunk("IDAT", Deflate.encode(rows)) +
            pngChunk("IEND", ByteArray(0))

    private fun source(width: Int, height: Int): IntArray = IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        val a = if (x == 0) 0 else (37 * x + 61 * y) and 0xFF
        argb(a, (x * 53 + 7) and 0xFF, (y * 91 + 3) and 0xFF, (x * y * 11) and 0xFF)
    }

    /** Premultiplied BGRA rows with filter None on even rows and Up on odd ones. */
    private fun bgraRows(px: IntArray, width: Int, height: Int): ByteArray {
        val stride = 1 + 4 * width
        val raw = ByteArray(stride * height)
        for (y in 0 until height) for (x in 0 until width) {
            val p = px[y * width + x]
            val a = p ushr 24
            fun pre(shift: Int) = (((p shr shift) and 0xFF) * a + 127) / 255
            val at = y * stride + 1 + 4 * x
            raw[at] = pre(0).toByte()
            raw[at + 1] = pre(8).toByte()
            raw[at + 2] = pre(16).toByte()
            raw[at + 3] = a.toByte()
        }
        for (y in height - 1 downTo 1) if (y % 2 == 1) {
            raw[y * stride] = 2
            for (i in 1 until stride) raw[y * stride + i] = (raw[y * stride + i] - raw[(y - 1) * stride + i]).toByte()
        }
        return raw
    }

    /** A small CgBI file for `FuzzTest`. */
    fun seed(): ByteArray = cgbi(4, 3, 6, bgraRows(source(4, 3), 4, 3))

    @Test
    fun premultipliedBgraDecodesToStraightRgba() {
        val w = 9
        val h = 6
        val px = source(w, h)
        val decoded = ImageKodec.decode(cgbi(w, h, 6, bgraRows(px, w, h)))
        for (i in px.indices) {
            val p = px[i]
            val a = p ushr 24
            val got = decoded.argb[i]
            assertEquals(a, got ushr 24, "alpha of pixel $i")
            for (shift in intArrayOf(16, 8, 0)) {
                val want = if (a == 0) 0 else (p shr shift) and 0xFF
                // A premultiplied sample keeps a/255 of its levels, so dividing it back is
                // within half a step of 255/a of the source.
                val bound = if (a == 0) 0 else (255 + a - 1) / a
                val d = kotlin.math.abs(want - ((got shr shift) and 0xFF))
                assertTrue(d <= bound, "pixel $i channel $shift: $d past $bound")
            }
        }
        val info = ImageKodec.probe(cgbi(w, h, 6, bgraRows(px, w, h)))
        assertTrue(info.isDecodable)
        assertTrue(info.hasAlpha)
    }

    @Test
    fun opaqueBgrAndGrayDecodeExactly() {
        val w = 5
        val h = 3
        val rgb = ByteArray((1 + 3 * w) * h)
        val gray = ByteArray((1 + w) * h)
        for (y in 0 until h) for (x in 0 until w) {
            val at = y * (1 + 3 * w) + 1 + 3 * x
            rgb[at] = (x * 40).toByte()          // blue
            rgb[at + 1] = (y * 70).toByte()      // green
            rgb[at + 2] = (200 - x * 9).toByte() // red
            gray[y * (1 + w) + 1 + x] = (x * 50 + y).toByte()
        }
        val color = ImageKodec.decode(cgbi(w, h, 2, rgb))
        val wide = ImageKodec.decode16(cgbi(w, h, 2, rgb))
        val g = ImageKodec.decode(cgbi(w, h, 0, gray))
        for (y in 0 until h) for (x in 0 until w) {
            assertEquals(argb(255, 200 - x * 9, (y * 70) and 0xFF, (x * 40) and 0xFF), color[x, y])
            assertEquals((200 - x * 9) * 257, wide.samples[(y * w + x) * 3].toInt() and 0xFFFF)
            val v = x * 50 + y
            assertEquals(argb(255, v, v, v), g[x, y])
        }
    }

    @Test
    fun interlacedCgbiMatchesTheProgressiveLayout() {
        val w = 11
        val h = 7
        val px = IntArray(w * h) { i -> argb(255, i * 3 and 0xFF, i * 5 and 0xFF, i * 7 and 0xFF) }
        // Adam7 passes in order, each row with filter None.
        val xs = intArrayOf(0, 4, 0, 2, 0, 1, 0); val ys = intArrayOf(0, 0, 4, 0, 2, 0, 1)
        val dx = intArrayOf(8, 8, 4, 4, 2, 2, 1); val dy = intArrayOf(8, 8, 8, 4, 4, 2, 2)
        val out = ArrayList<Byte>()
        for (p in 0 until 7) {
            val pw = if (w > xs[p]) (w - xs[p] + dx[p] - 1) / dx[p] else 0
            val ph = if (h > ys[p]) (h - ys[p] + dy[p] - 1) / dy[p] else 0
            if (pw == 0 || ph == 0) continue
            for (r in 0 until ph) {
                out.add(0)
                for (c in 0 until pw) {
                    val v = px[(ys[p] + r * dy[p]) * w + xs[p] + c * dx[p]]
                    out.add(v.toByte()); out.add((v shr 8).toByte()); out.add((v shr 16).toByte()); out.add(-1)
                }
            }
        }
        val decoded = ImageKodec.decode(cgbi(w, h, 6, out.toByteArray(), interlace = 1))
        for (i in px.indices) assertEquals(px[i], decoded.argb[i], "pixel $i")
    }

    @Test
    fun damagedOrUnwrittenCgbiFilesAreRefused() {
        val rows = bgraRows(source(4, 4), 4, 4)
        val good = cgbi(4, 4, 6, rows)
        // A zlib header where raw deflate belongs is no stream at all: 0x78 starts a reserved block type.
        val zlib = PNG_SIGNATURE + pngChunk("CgBI", byteArrayOf(0x50, 0, 0x20, 6)) + pngHeader(4, 4, 8, 6) +
            pngChunk("IDAT", io.github.yuroyami.imagekodec.internal.flate.Zlib.compress(rows)) + pngChunk("IEND", ByteArray(0))
        assertFailsWith<ImageDecodeException> { ImageKodec.decode(zlib) }
        val idat = Deflate.encode(rows)
        val cut = PNG_SIGNATURE + pngChunk("CgBI", byteArrayOf(0x50, 0, 0x20, 6)) + pngHeader(4, 4, 8, 6) +
            pngChunk("IDAT", idat.copyOf(idat.size / 2)) + pngChunk("IEND", ByteArray(0))
        assertFailsWith<ImageDecodeException> { ImageKodec.decode(cut) }
        ImageKodec.decode(good)
        val deep = cgbi(2, 2, 6, ByteArray(2 * 17), bitDepth = 16)
        assertFailsWith<UnsupportedImageException> { ImageKodec.decode(deep) }
        assertFalse(ImageKodec.probe(deep).isDecodable)
    }
}
