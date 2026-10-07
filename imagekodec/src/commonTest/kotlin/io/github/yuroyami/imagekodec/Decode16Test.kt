package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.internal.flate.Crc32
import io.github.yuroyami.imagekodec.internal.flate.Zlib
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [ImageKodec.decode16] keeps every bit of a 16-bit PNG or TIFF (#10). The files here are
 * built from known samples whose low bytes are not zero, so every expected value is exact,
 * and the 8-bit decode must still be the high byte of each of them.
 */
class Decode16Test {

    private val width = 5
    private val height = 3

    /** A value for sample [c] of pixel [i] that uses its low byte, under [max]. */
    private fun value(i: Int, c: Int, max: Int = 65535) = ((i * 4099 + c * 12345 + 77) % (max + 1))

    // --- PNG ------------------------------------------------------------------------

    private fun chunk(type: String, body: ByteArray): ByteArray {
        val crc = Crc32()
        crc.update(type.encodeToByteArray())
        crc.update(body)
        val v = crc.value()
        return be32(body.size) + type.encodeToByteArray() + body + be32(v.toInt())
    }

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())

    /**
     * A PNG of color type [colorType] at [depth] bits whose sample [c] of pixel [i] is
     * `sample(i, c)`, each row with filter 0, or the seven Adam7 passes when [interlaced].
     */
    private fun png(colorType: Int, depth: Int, channels: Int, trns: ByteArray? = null, palette: ByteArray? = null,
        interlaced: Boolean = false, sample: (Int, Int) -> Int): ByteArray {
        fun rows(xs: List<Int>, ys: List<Int>): ByteArray {
            val out = ArrayList<Byte>()
            for (y in ys) {
                out += 0
                var acc = 0
                var bitsHeld = 0
                for (x in xs) for (c in 0 until channels) {
                    val v = sample(y * width + x, c)
                    if (depth == 16) { out += (v ushr 8).toByte(); out += v.toByte() }
                    else if (depth == 8) out += v.toByte()
                    else {
                        acc = (acc shl depth) or v
                        bitsHeld += depth
                        if (bitsHeld == 8) { out += acc.toByte(); acc = 0; bitsHeld = 0 }
                    }
                }
                if (bitsHeld > 0) { out += (acc shl (8 - bitsHeld)).toByte(); acc = 0; bitsHeld = 0 }
            }
            return out.toByteArray()
        }
        val raw = if (!interlaced) rows((0 until width).toList(), (0 until height).toList()) else {
            val starts = listOf(0 to 0, 4 to 0, 0 to 4, 2 to 0, 0 to 2, 1 to 0, 0 to 1)
            val steps = listOf(8 to 8, 8 to 8, 4 to 8, 4 to 4, 2 to 4, 2 to 2, 1 to 2)
            starts.indices.map { p ->
                val xs = (starts[p].first until width step steps[p].first).toList()
                val ys = (starts[p].second until height step steps[p].second).toList()
                if (xs.isEmpty() || ys.isEmpty()) ByteArray(0) else rows(xs, ys)
            }.reduce { a, b -> a + b }
        }
        val header = be32(width) + be32(height) + byteArrayOf(depth.toByte(), colorType.toByte(), 0, 0, if (interlaced) 1 else 0)
        return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            chunk("IHDR", header) +
            (palette?.let { chunk("PLTE", it) } ?: ByteArray(0)) +
            (trns?.let { chunk("tRNS", it) } ?: ByteArray(0)) +
            chunk("IDAT", Zlib.compress(raw)) + chunk("IEND", ByteArray(0))
    }

    private fun check16(bytes: ByteArray, channels: Int, expected: (Int, Int) -> Int) {
        val wide = ImageKodec.decode16(bytes)
        assertEquals(width, wide.width)
        assertEquals(height, wide.height)
        assertEquals(channels, wide.channels)
        for (i in 0 until width * height) for (c in 0 until channels) {
            assertEquals(expected(i, c), wide[i % width, i / width, c], "pixel $i channel $c")
        }
        assertContentEquals(ImageKodec.decode(bytes).argb, wide.toBitmap().argb, "the 8-bit decode is the high byte")
    }

    @Test
    fun sixteenBitPngKeepsEveryBitInEveryColorType() {
        for ((colorType, channels) in listOf(0 to 1, 4 to 2, 2 to 3, 6 to 4)) {
            for (interlaced in listOf(false, true)) {
                val bytes = png(colorType, 16, channels, interlaced = interlaced) { i, c -> value(i, c) }
                check16(bytes, channels) { i, c -> value(i, c) }
            }
        }
    }

    @Test
    fun aSixteenBitColorKeyBecomesAnAlphaChannel() {
        val key = value(7, 0)
        val gray = png(0, 16, 1, trns = be16(key)) { i, _ -> value(i, 0) }
        check16(gray, 2) { i, c -> if (c == 0) value(i, 0) else if (i == 7) 0 else 65535 }
        val rgbKey = (0 until 3).map { value(4, it) }
        val rgb = png(2, 16, 3, trns = rgbKey.map { be16(it) }.reduce { a, b -> a + b }) { i, c -> value(i, c) }
        check16(rgb, 4) { i, c -> if (c < 3) value(i, c) else if (i == 4) 0 else 65535 }
    }

    @Test
    fun narrowerSamplesReplicateUp() {
        for ((depth, factor) in listOf(1 to 65535, 2 to 21845, 4 to 4369, 8 to 257)) {
            val max = (1 shl depth) - 1
            val bytes = png(0, depth, 1) { i, _ -> value(i, 0, max) }
            check16(bytes, 1) { i, _ -> value(i, 0, max) * factor }
        }
        // A palette with alpha gives RGBA, each 8-bit entry times 257.
        val palette = ByteArray(4 * 3) { (it * 37 + 5).toByte() }
        val alpha = byteArrayOf(0, 90, 255.toByte(), 17)
        val bytes = png(3, 2, 1, trns = alpha, palette = palette) { i, _ -> i % 4 }
        check16(bytes, 4) { i, c ->
            val entry = i % 4
            if (c < 3) (palette[entry * 3 + c].toInt() and 0xFF) * 257 else (alpha[entry].toInt() and 0xFF) * 257
        }
    }

    // --- TIFF -----------------------------------------------------------------------

    private class Field(val tag: Int, val type: Int, val values: IntArray)

    private fun short(tag: Int, vararg v: Int) = Field(tag, 3, v)
    private fun long(tag: Int, vararg v: Int) = Field(tag, 4, v)

    /** A one-strip TIFF of [fields] whose strip is [strip], in either byte order. */
    private fun tiff(fields: List<Field>, strip: ByteArray, littleEndian: Boolean = true): ByteArray {
        val all = (fields + listOf(long(273, 0), long(279, strip.size))).sortedBy { it.tag }
        fun size(f: Field) = f.values.size * if (f.type == 3) 2 else 4
        var at = 8 + 2 + all.size * 12 + 4
        val outside = HashMap<Int, Int>()
        for (f in all) if (size(f) > 4) { outside[f.tag] = at; at += size(f) }
        val stripAt = at
        val out = ByteArray(at + strip.size)
        var p = 0
        fun u16(v: Int) { if (littleEndian) { out[p++] = v.toByte(); out[p++] = (v ushr 8).toByte() } else { out[p++] = (v ushr 8).toByte(); out[p++] = v.toByte() } }
        fun u32(v: Int) { if (littleEndian) { u16(v); u16(v ushr 16) } else { u16(v ushr 16); u16(v) } }
        fun put(f: Field) { for (v in f.values) if (f.type == 3) u16(v) else u32(if (f.tag == 273) stripAt else v) }
        val mark = if (littleEndian) 'I' else 'M'
        out[0] = mark.code.toByte(); out[1] = mark.code.toByte(); p = 2; u16(42); u32(8)
        u16(all.size)
        for (f in all) {
            u16(f.tag); u16(f.type); u32(f.values.size)
            val slot = p
            if (size(f) > 4) u32(outside.getValue(f.tag)) else put(f)
            p = slot + 4
        }
        u32(0)
        for (f in all) outside[f.tag]?.let { p = it; put(f) }
        strip.copyInto(out, stripAt)
        return out
    }

    /** 16-bit samples in the file's byte order, with horizontal differencing when [predictor]. */
    private fun samples16(channels: Int, littleEndian: Boolean, predictor: Boolean = false, sample: (Int, Int) -> Int): ByteArray {
        val out = ByteArray(width * height * channels * 2)
        for (y in 0 until height) for (x in 0 until width) for (c in 0 until channels) {
            val i = y * width + x
            var v = sample(i, c)
            if (predictor && x > 0) v = (v - sample(i - 1, c)) and 0xFFFF
            val at = (i * channels + c) * 2
            if (littleEndian) { out[at] = v.toByte(); out[at + 1] = (v ushr 8).toByte() }
            else { out[at] = (v ushr 8).toByte(); out[at + 1] = v.toByte() }
        }
        return out
    }

    @Test
    fun sixteenBitTiffKeepsEveryBitInBothByteOrdersAndWithAPredictor() {
        for (littleEndian in listOf(true, false)) for (predictor in listOf(false, true)) {
            val gray = tiff(listOf(long(256, width), long(257, height), short(258, 16), short(259, 1), short(262, 1),
                short(277, 1), long(278, height), short(317, if (predictor) 2 else 1)),
                samples16(1, littleEndian, predictor) { i, _ -> value(i, 0) }, littleEndian)
            check16(gray, 1) { i, _ -> value(i, 0) }
            val rgba = tiff(listOf(long(256, width), long(257, height), short(258, 16, 16, 16, 16), short(259, 1),
                short(262, 2), short(277, 4), long(278, height), short(317, if (predictor) 2 else 1), short(338, 2)),
                samples16(4, littleEndian, predictor) { i, c -> value(i, c) }, littleEndian)
            check16(rgba, 4) { i, c -> value(i, c) }
        }
    }

    @Test
    fun whiteIsZeroAndAssociatedAlphaWorkAtFullPrecision() {
        val white = tiff(listOf(long(256, width), long(257, height), short(258, 16), short(259, 1), short(262, 0),
            short(277, 1), long(278, height)), samples16(1, true) { i, _ -> value(i, 0) })
        check16(white, 1) { i, _ -> 65535 - value(i, 0) }
        // Associated alpha: color times opacity, which the decode divides back out.
        val opacity = { i: Int -> if (i == 3) 0 else 20000 + i * 1000 }
        val straight = { i: Int -> value(i, 0) }
        val premultiplied = { i: Int -> ((straight(i).toLong() * opacity(i) + 32767) / 65535).toInt() }
        val bytes = tiff(listOf(long(256, width), long(257, height), short(258, 16, 16), short(259, 1), short(262, 1),
            short(277, 2), long(278, height), short(338, 1)), samples16(2, true) { i, c -> if (c == 0) premultiplied(i) else opacity(i) })
        val wide = ImageKodec.decode16(bytes)
        assertEquals(2, wide.channels)
        for (i in 0 until width * height) {
            val o = opacity(i)
            val expected = if (o == 0) 0 else minOf(65535L, (premultiplied(i).toLong() * 65535 + o / 2) / o).toInt()
            assertEquals(expected, wide[i % width, i / width, 0], "pixel $i")
            assertEquals(o, wide[i % width, i / width, 1])
        }
    }

    @Test
    fun aPaletteKeepsItsSixteenBitColorMap() {
        val n = 16
        val map = IntArray(3 * n) { (it * 2731 + 11) and 0xFFFF }
        val strip = ByteArray(width * height) { (it % n).toByte() }
        val bytes = tiff(listOf(long(256, width), long(257, height), short(258, 8), short(259, 1), short(262, 3),
            short(277, 1), long(278, height), Field(320, 3, IntArray(3 * 256) { if (it % 256 < n) map[(it / 256) * n + it % 256] else 0 })), strip)
        val wide = ImageKodec.decode16(bytes)
        assertEquals(3, wide.channels)
        for (i in 0 until width * height) for (c in 0 until 3) {
            assertEquals(map[c * n + i % n], wide[i % width, i / width, c], "pixel $i channel $c")
        }
        assertContentEquals(ImageKodec.decode(bytes).argb, wide.toBitmap().argb)
    }

    @Test
    fun orientationAndPagesWorkAsTheyDoForDecode() {
        val bytes = tiff(listOf(long(256, width), long(257, height), short(258, 16), short(259, 1), short(262, 1),
            short(274, 6), short(277, 1), long(278, height)), samples16(1, true) { i, _ -> value(i, 0) })
        val turned = ImageKodec.decode16(bytes, applyOrientation = true)
        assertEquals(height, turned.width)
        assertEquals(width, turned.height)
        assertContentEquals(ImageKodec.decode(bytes, applyOrientation = true).argb, turned.toBitmap().argb)
        assertFailsWith<IllegalArgumentException> { ImageKodec.decode16(bytes, page = 1) }
        val pages = TiffPagesTest().let { it.tiff(it.pages) }
        assertContentEquals(ImageKodec.decodePage(pages, 2).argb, ImageKodec.decode16(pages, page = 2).toBitmap().argb)
    }

    @Test
    fun otherFormatsWidenExactly() {
        val jpeg = ImageKodec.encodeJpeg(KiteBitmap(9, 7, IntArray(63) { argb(0xFF, it * 3, 255 - it, it * 2) }), quality = 80)
        val wide = ImageKodec.decode16(jpeg)
        assertEquals(3, wide.channels)
        val narrow = ImageKodec.decode(jpeg)
        for (i in narrow.argb.indices) for ((c, shift) in intArrayOf(16, 8, 0).withIndex()) {
            assertEquals(((narrow.argb[i] ushr shift) and 0xFF) * 257, wide.samples[i * 3 + c].toInt() and 0xFFFF)
        }
        val gif = ImageKodec.encodeGif(KiteBitmap(4, 4, IntArray(16) { if (it == 5) 0 else argb(0xFF, 200, 10, 10) }))
        val transparent = ImageKodec.decode16(gif)
        assertEquals(4, transparent.channels)
        assertEquals(0, transparent[1, 1, 3])
        assertFailsWith<IllegalArgumentException> { ImageKodec.decode16(jpeg, page = 1) }
        assertFailsWith<ImageDecodeException> { ImageKodec.decode16(ByteArray(12)) }
    }

    @Test
    fun theTypeChecksItsShape() {
        assertFailsWith<IllegalArgumentException> { KiteBitmap16(2, 2, 5, ShortArray(20)) }
        assertFailsWith<IllegalArgumentException> { KiteBitmap16(2, 2, 3, ShortArray(11)) }
        assertFailsWith<IllegalArgumentException> { KiteBitmap16(0, 2, 1, ShortArray(0)) }
        val bitmap = KiteBitmap16(2, 1, 2, shortArrayOf(1, 2, 3, -1))
        assertEquals(65535, bitmap[1, 0, 1])
        assertTrue(bitmap.hasAlpha)
        assertFailsWith<IllegalArgumentException> { bitmap[0, 0, 2] }
        assertSame(bitmap, bitmap.oriented(Orientation.Normal))
        for (o in Orientation.entries) {
            val turned = KiteBitmap16(3, 2, 1, ShortArray(6) { it.toShort() }).oriented(o)
            val expected = KiteBitmap(3, 2, IntArray(6) { it }).oriented(o)
            assertEquals(expected.width, turned.width)
            assertContentEquals(expected.argb, IntArray(6) { turned.samples[it].toInt() }, "$o")
        }
    }
}
