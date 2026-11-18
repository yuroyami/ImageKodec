package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.JpegDecoder
import io.github.yuroyami.imagekodec.internal.flate.Zlib
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TIFF samples of every depth and format (#189): unsigned integers of 1 to 32 bits, signed ones,
 * IEEE floats of 16, 24, 32 and 64 bits, both predictors over them, and 12-bit and separate-plane
 * JPEG. The files are built here from TIFF 6.0, Adobe's Technical Note 3 and the DNG
 * specification, and the expected samples are worked out from the stored values in the test.
 */
class TiffSamplesTest {

    internal class Field(val tag: Int, val type: Int, val values: LongArray)

    internal fun short(tag: Int, vararg v: Int) = Field(tag, 3, LongArray(v.size) { v[it].toLong() })
    internal fun long(tag: Int, vararg v: Long) = Field(tag, 4, v)

    /**
     * A TIFF of [fields] whose strips, or tiles with [tiled], are [blocks]: the offsets and counts
     * are filled in, in [littleEndian] or big-endian order.
     */
    internal fun tiff(fields: List<Field>, blocks: List<ByteArray>, littleEndian: Boolean = true, tiled: Boolean = false): ByteArray {
        val all = fields.filter { it.tag !in intArrayOf(273, 279, 324, 325) } + listOf(
            Field(if (tiled) 324 else 273, 4, LongArray(blocks.size)),
            Field(if (tiled) 325 else 279, 4, LongArray(blocks.size) { blocks[it].size.toLong() }),
        )
        val sorted = all.sortedBy { it.tag }
        fun size(f: Field) = f.values.size * if (f.type == 3) 2 else 4
        var at = 8 + 2 + 12 * sorted.size + 4
        val outside = HashMap<Int, Int>()
        for (f in sorted) if (size(f) > 4) { outside[f.tag] = at; at += size(f) }
        val blockAt = IntArray(blocks.size)
        for (i in blocks.indices) { blockAt[i] = at; at += blocks[i].size }
        val out = ByteArray(at)
        var p = 0
        fun u16(v: Int) {
            if (littleEndian) { out[p] = v.toByte(); out[p + 1] = (v ushr 8).toByte() } else { out[p] = (v ushr 8).toByte(); out[p + 1] = v.toByte() }
            p += 2
        }
        fun u32(v: Long) {
            if (littleEndian) { u16(v.toInt()); u16((v ushr 16).toInt()) } else { u16((v ushr 16).toInt()); u16(v.toInt()) }
        }
        fun values(f: Field) {
            val offsets = f.tag == 273 || f.tag == 324
            for ((i, v) in f.values.withIndex()) if (f.type == 3) u16(v.toInt()) else u32(if (offsets) blockAt[i].toLong() else v)
        }
        out[0] = (if (littleEndian) 'I' else 'M').code.toByte(); out[1] = out[0]
        p = 2; u16(42); u32(8); u16(sorted.size)
        for (f in sorted) {
            u16(f.tag); u16(f.type); u32(f.values.size.toLong())
            val slot = p
            if (size(f) > 4) u32(outside.getValue(f.tag).toLong()) else values(f)
            p = slot + 4
        }
        u32(0)
        for (f in sorted) outside[f.tag]?.let { p = it; values(f) }
        for (i in blocks.indices) blocks[i].copyInto(out, blockAt[i])
        return out
    }

    /** One strip of [width] by [height] samples of [bits] bits, [samples] a pixel, in SampleFormat [format]. */
    internal fun samplesTiff(
        width: Int, height: Int, samples: Int, bits: Int, format: Int, strip: ByteArray, littleEndian: Boolean = true,
        photometric: Int = if (samples >= 3) 2 else 1, compression: Int = 1, predictor: Int = 1, extra: List<Field> = emptyList(),
    ): ByteArray = tiff(
        listOf(
            long(256, width.toLong()), long(257, height.toLong()), short(258, *IntArray(samples) { bits }),
            short(259, compression), short(262, photometric), short(277, samples), long(278, height.toLong()),
            short(284, 1), short(339, *IntArray(samples) { format }),
        ) + (if (predictor != 1) listOf(short(317, predictor)) else emptyList()) + extra,
        listOf(strip), littleEndian,
    )

    /** [values], each of [bits] bits, packed from the most significant bit, each row padded to a byte. */
    internal fun packed(values: LongArray, perRow: Int, bits: Int): ByteArray {
        val rowBytes = (perRow * bits + 7) / 8
        val rows = values.size / perRow
        val out = ByteArray(rowBytes * rows)
        for (r in 0 until rows) for (i in 0 until perRow) {
            val v = values[r * perRow + i]
            for (b in 0 until bits) if ((v ushr (bits - 1 - b)) and 1L != 0L) {
                val bit = i * bits + b
                out[r * rowBytes + bit / 8] = (out[r * rowBytes + bit / 8].toInt() or (0x80 ushr (bit % 8))).toByte()
            }
        }
        return out
    }

    /** [values] as words of [size] bytes in the byte order [littleEndian] says. */
    internal fun words(values: LongArray, size: Int, littleEndian: Boolean): ByteArray = ByteArray(values.size * size) { i ->
        val k = i % size
        (values[i / size] ushr (8 * if (littleEndian) k else size - 1 - k)).toByte()
    }

    /** Small files of the layouts above for `FuzzTest`. */
    internal fun seeds(): List<Pair<String, ByteArray>> {
        val floats = LongArray(12) { (it / 11f).toRawBits().toLong() and 0xFFFFFFFFL }
        val row = words(floats, 4, true)
        val predicted = predictRow(row.copyOfRange(0, 24), 6, 4, 3, true) + predictRow(row.copyOfRange(24, 48), 6, 4, 3, true)
        return listOf(
            "tiff-float-predictor" to samplesTiff(2, 2, 3, 32, 3, Zlib.compress(predicted), compression = 8, predictor = 3),
            "tiff-12bit" to samplesTiff(3, 2, 3, 12, 1, packed(LongArray(18) { it * 227L }, 9, 12), littleEndian = false),
            "tiff-signed-half" to samplesTiff(4, 1, 2, 16, 2, words(LongArray(8) { it * 9000L }, 2, true), extra = listOf(short(338, 2))),
            "tiff-jpeg-12bit" to jpeg12(false).second,
            "tiff-jpeg-separate" to separateJpeg(2, strips = 2).bytes,
        )
    }

    private fun scaled(v: Long, bits: Int): Int {
        val max = (1L shl bits) - 1
        return ((v * 65535 + max / 2) / max).toInt()
    }

    private fun fraction(f: Double): Int = if (!(f > 0)) 0 else if (f >= 1) 65535 else (f * 65535 + 0.5).toInt()

    private fun assertSamples(expected: IntArray, bytes: ByteArray, name: String) {
        val wide = ImageKodec.decode16(bytes)
        val got = IntArray(wide.samples.size) { wide.samples[it].toInt() and 0xFFFF }
        assertContentEquals(expected, got, name)
        // decode gives the high byte of each sample, the rule for every sample deeper than 8 bits.
        val narrow = ImageKodec.decode(bytes)
        val channels = wide.channels
        for (i in 0 until wide.width * wide.height) {
            val p = narrow.argb[i]
            val color = if (channels >= 3) intArrayOf(p shr 16, p shr 8, p) else intArrayOf(p)
            for (c in 0 until minOf(3, channels)) assertEquals(expected[i * channels + c] ushr 8, color[c] and 0xFF, "$name pixel $i")
        }
        assertTrue(ImageKodec.probe(bytes).isDecodable, name)
    }

    @Test
    fun unsignedSamplesOfEveryDepthScaleRounded() {
        for (bits in listOf(3, 5, 7, 10, 12, 14, 24, 32)) for (le in listOf(true, false)) {
            val max = (1L shl bits) - 1
            val values = LongArray(5 * 3 * 2) { i -> (max * ((i * 37) % 31) / 30) xor (i.toLong() and 1) }
            val strip = if (bits % 8 == 0) words(values, bits / 8, le) else packed(values, 15, bits)
            val bytes = samplesTiff(5, 2, 3, bits, 1, strip, le)
            assertSamples(IntArray(values.size) { scaled(values[it], bits) }, bytes, "$bits-bit RGB, ${if (le) "II" else "MM"}")
        }
    }

    @Test
    fun signedSamplesMoveUpByHalfTheirRange() {
        for (bits in listOf(8, 12, 16, 32)) {
            val min = -(1L shl (bits - 1))
            val max = (1L shl (bits - 1)) - 1
            val values = longArrayOf(min, min + 1, -1, 0, 1, max)
            val stored = LongArray(values.size) { values[it] and ((1L shl bits) - 1) }
            val strip = if (bits % 8 == 0) words(stored, bits / 8, false) else packed(stored, 6, bits)
            val bytes = samplesTiff(6, 1, 1, bits, 2, strip, littleEndian = false)
            assertSamples(IntArray(values.size) { scaled(values[it] - min, bits) }, bytes, "$bits-bit signed gray")
        }
        // WhiteIsZero turns the scale over, after the move.
        val white = samplesTiff(2, 1, 1, 8, 2, byteArrayOf(-128, 127), photometric = 0)
        assertSamples(intArrayOf(65535, 0), white, "signed WhiteIsZero")
    }

    @Test
    fun floatingPointSamplesAreFractionsOfFullScale() {
        val doubles = doubleArrayOf(-1.0, 0.0, 0.25, 0.5, 1.0 / 3, 1.0, 2.0, Double.NaN, 1e-30, 0.999)
        for (le in listOf(true, false)) {
            val f32 = words(LongArray(doubles.size) { doubles[it].toFloat().toRawBits().toLong() and 0xFFFFFFFFL }, 4, le)
            assertSamples(IntArray(doubles.size) { fraction(doubles[it].toFloat().toDouble()) },
                samplesTiff(doubles.size, 1, 1, 32, 3, f32, le), "binary32")
            val f64 = words(LongArray(doubles.size) { doubles[it].toRawBits() }, 8, le)
            assertSamples(IntArray(doubles.size) { fraction(doubles[it]) }, samplesTiff(doubles.size, 1, 1, 64, 3, f64, le), "binary64")
        }
        // binary16: zero, a denormal, 0.25, 0.5, the largest below 1, 1, infinity, -2, NaN.
        val halves = longArrayOf(0x0000, 0x0001, 0x3400, 0x3800, 0x3BFF, 0x3C00, 0x7C00, 0xC000, 0x7E00)
        val halfValues = doubleArrayOf(0.0, 5.960464477539063E-8, 0.25, 0.5, 0.99951171875, 1.0, Double.POSITIVE_INFINITY, -2.0, Double.NaN)
        assertSamples(IntArray(halves.size) { fraction(halfValues[it]) }, samplesTiff(halves.size, 1, 1, 16, 3, words(halves, 2, true)), "binary16")
        // DNG's 24-bit float: sign, 7 exponent bits biased by 63, 16 fraction bits.
        val f24 = longArrayOf(0x3F0000, 0x3E0000, 0x3E8000, 0x3D0000, 0x000001, 0xBF0000)
        val f24Values = doubleArrayOf(1.0, 0.5, 0.75, 0.25, 0.0, -1.0)
        assertSamples(IntArray(f24.size) { fraction(f24Values[it]) }, samplesTiff(f24.size, 1, 1, 24, 3, words(f24, 3, false), false), "24-bit float")
    }

    /** The forward floating-point predictor of Technical Note 3 on one row of [samples] words of [size] bytes. */
    private fun predictRow(row: ByteArray, count: Int, size: Int, stride: Int, littleEndian: Boolean): ByteArray {
        val planes = ByteArray(count * size)
        for (i in 0 until count) for (b in 0 until size) {
            planes[b * count + i] = row[i * size + if (littleEndian) size - 1 - b else b]
        }
        for (i in planes.size - 1 downTo stride) planes[i] = (planes[i] - planes[i - stride]).toByte()
        return planes
    }

    @Test
    fun theFloatingPointPredictorRestoresEveryWidth() {
        for (bits in listOf(16, 24, 32, 64)) for (le in listOf(true, false)) {
            val size = bits / 8
            val w = 7
            val h = 3
            val raw = ByteArray(w * h * 3 * size) { ((it * 73) xor (it shr 3)).toByte() }
            val predicted = ByteArray(raw.size)
            for (y in 0 until h) {
                val rowBytes = w * 3 * size
                predictRow(raw.copyOfRange(y * rowBytes, (y + 1) * rowBytes), w * 3, size, 3, le).copyInto(predicted, y * rowBytes)
            }
            val plain = samplesTiff(w, h, 3, bits, 3, raw, le)
            val coded = samplesTiff(w, h, 3, bits, 3, Zlib.compress(predicted), le, compression = 8, predictor = 3)
            assertContentEquals(ImageKodec.decode16(plain).samples, ImageKodec.decode16(coded).samples, "$bits-bit predictor 3, ${if (le) "II" else "MM"}")
        }
    }

    @Test
    fun horizontalDifferencingRestoresWideWords() {
        for (bits in listOf(32, 64)) for (le in listOf(true, false)) {
            val size = bits / 8
            val values = LongArray(6 * 2) { i -> if (bits == 64) (0.1 * i).toRawBits() else (i * 0x1234567L + 0x0FFFFFFFL) and 0xFFFFFFFFL }
            val differenced = values.copyOf()
            for (r in 0 until 2) for (i in 5 downTo 1) differenced[r * 6 + i] = values[r * 6 + i] - values[r * 6 + i - 1]
            val format = if (bits == 64) 3 else 1
            val plain = samplesTiff(6, 2, 1, bits, format, words(values, size, le), le)
            val coded = samplesTiff(6, 2, 1, bits, format, Zlib.compress(words(differenced, size, le)), le, compression = 8, predictor = 2)
            assertContentEquals(ImageKodec.decode16(plain).samples, ImageKodec.decode16(coded).samples, "$bits-bit predictor 2")
        }
    }

    @Test
    fun aPaletteOfThreeBitsAndAssociatedAlphaOverFloats() {
        val map = IntArray(3 * 8) { (it * 2731) and 0xFFFF }
        val indexes = longArrayOf(0, 7, 3, 5, 1, 6)
        val palette = tiff(
            listOf(long(256, 6), long(257, 1), short(258, 3), short(259, 1), short(262, 3), short(277, 1),
                long(278, 1), Field(320, 3, LongArray(map.size) { map[it].toLong() })),
            listOf(packed(indexes, 6, 3)),
        )
        val wide = ImageKodec.decode16(palette)
        for (i in indexes.indices) for (c in 0 until 3) {
            assertEquals(map[c * 8 + indexes[i].toInt()], wide.samples[i * 3 + c].toInt() and 0xFFFF)
        }
        // Premultiplied RGBA floats: colour 0.25 under alpha 0.5 is 0.5 straight.
        val rgba = floatArrayOf(0.25f, 0.125f, 0f, 0.5f)
        val bytes = samplesTiff(1, 1, 4, 32, 3, words(LongArray(4) { rgba[it].toRawBits().toLong() and 0xFFFFFFFFL }, 4, true),
            photometric = 2, extra = listOf(short(338, 1)))
        val pixel = ImageKodec.decode16(bytes).samples.map { it.toInt() and 0xFFFF }
        assertEquals(listOf(32768, 16384, 0, 32768), pixel)
    }

    @Test
    fun whatNoReaderCanCheckStaysRefusedByName() {
        val cases = listOf(
            samplesTiff(2, 1, 1, 12, 3, ByteArray(3)) to "12-bit floating-point",
            samplesTiff(2, 1, 1, 8, 5, ByteArray(2)) to "complex",
            samplesTiff(1, 1, 1, 64, 1, ByteArray(8)) to "64-bit integer",
            samplesTiff(2, 1, 1, 16, 1, ByteArray(4), compression = 8, predictor = 3) to "predictor 3 with integer",
            samplesTiff(4, 1, 1, 12, 1, ByteArray(6), compression = 8, predictor = 2) to "predictor 2 with 12-bit",
            samplesTiff(2, 1, 1, 8, 2, ByteArray(2), photometric = 3) to "palette",
            samplesTiff(2, 1, 3, 16, 1, ByteArray(12), photometric = 6) to "YCbCr with 16-bit",
            tiff(listOf(long(256, 2), long(257, 1), short(258, 16, 16), short(259, 1), short(262, 1), short(277, 2),
                long(278, 1), short(338, 2), short(339, 1, 3)), listOf(ByteArray(8))) to "several SampleFormat",
        )
        for ((bytes, reason) in cases) {
            val info = ImageKodec.probe(bytes)
            assertFalse(info.isDecodable, reason)
            assertTrue(reason in info.unsupportedReason.orEmpty(), "${info.unsupportedReason}")
            val e = assertFailsWith<UnsupportedImageException>(reason) { ImageKodec.decode(bytes) }
            assertTrue(reason in e.message.orEmpty(), "${e.message}")
        }
        // A strip that ends early is damage, whatever its depth.
        for (bits in listOf(12, 32)) {
            assertFailsWith<ImageDecodeException> { ImageKodec.decode(samplesTiff(5, 2, 1, bits, 1, ByteArray(5))) }
        }
        assertFailsWith<ImageDecodeException> { ImageKodec.decode(samplesTiff(5, 2, 1, 16, 3, Zlib.compress(ByteArray(7)), compression = 8, predictor = 3)) }
    }

    /** A 12-bit gray and a 12-bit YCbCr JPEG of ImageKodec's encoder, each in one strip. */
    internal fun jpeg12(color: Boolean): Pair<ByteArray, ByteArray> {
        val w = 19
        val h = 13
        val channels = if (color) 3 else 1
        val samples = ShortArray(w * h * channels) { i -> (((i * 2654435761L) ushr 20) and 0xFFF0).toShort() }
        val jpeg = ImageKodec.encodeJpeg16(KiteBitmap16(w, h, channels, samples), JpegEncodeOptions(quality = 90, precision = 12))
        val tiff = tiff(
            listOf(long(256, w.toLong()), long(257, h.toLong()), short(258, *IntArray(channels) { 12 }), short(259, 7),
                short(262, if (color) 6 else 1), short(277, channels), long(278, h.toLong()), short(284, 1)) +
                (if (color) listOf(short(530, 1, 1)) else emptyList()),
            listOf(jpeg),
        )
        return jpeg to tiff
    }

    @Test
    fun twelveBitJpegStripsKeepTwelveBits() {
        for (color in listOf(false, true)) {
            val (jpeg, tiff) = jpeg12(color)
            val c = JpegDecoder.decodeWideComponents(jpeg, ycbcr = color)
            assertEquals(12, c.precision)
            assertSamples(IntArray(c.samples.size) { scaled(c.samples[it].toLong(), 12) }, tiff, "12-bit JPEG ${if (color) "YCbCr" else "gray"}")
        }
        // An 8-bit stream on a 12-bit page is damage.
        val eight = ImageKodec.encodeJpeg(KiteBitmap(8, 8, IntArray(64) { gray(it * 4) }), 90)
        val wrong = tiff(listOf(long(256, 8), long(257, 8), short(258, 12), short(259, 7), short(262, 1), short(277, 1), long(278, 8)), listOf(eight))
        assertFailsWith<ImageDecodeException> { ImageKodec.decode(wrong) }
    }

    /** The integer YCbCr conversion of TIFF 6.0 section 21 with full-range defaults, restated. */
    internal fun ycbcr(y: Int, cb: Int, cr: Int): Int {
        fun clamp(v: Int) = v.coerceIn(0, 255)
        val r = (y * 500 + 701 * (cr - 128) + 250) / 500
        val g = (y * 293500 - 101004 * (cb - 128) - 209599 * (cr - 128) + 146750) / 293500
        val b = (y * 250 + 443 * (cb - 128) + 125) / 250
        return argb(255, clamp(r), clamp(g), clamp(b))
    }

    /** Three gray JPEGs of ImageKodec's encoder as the planes of a YCbCr page [sub] times subsampled, in [strips] strips or [tile] tiles. */
    internal class Separate(val bytes: ByteArray, val width: Int, val height: Int, val planes: List<IntArray>)

    internal fun separateJpeg(sub: Int, strips: Int = 1, tile: Int = 0): Separate {
        val w = 37
        val h = 28
        val cw = (w + sub - 1) / sub
        val ch = (h + sub - 1) / sub
        fun plane(pw: Int, ph: Int, seed: Int) = KiteBitmap(pw, ph, IntArray(pw * ph) { i -> gray(((i % pw) * 5 + (i / pw) * seed) and 0xFF) })
        val planes = listOf(plane(w, h, 7), plane(cw, ch, 3), plane(cw, ch, 11))
        // Each plane as the JPEG decoder gives back its blocks, in the plane's own layout.
        val decoded = planes.map { IntArray(it.width * it.height) }
        fun jpeg(b: KiteBitmap) = ImageKodec.encodeJpeg(b, JpegEncodeOptions(quality = 95, grayscale = JpegGrayscale.Always))
        fun place(p: Int, block: ByteArray, x0: Int, y0: Int) {
            val back = ImageKodec.decode(block)
            for (y in 0 until back.height) for (x in 0 until back.width) {
                if (x0 + x < planes[p].width && y0 + y < planes[p].height) decoded[p][(y0 + y) * planes[p].width + x0 + x] = back[x, y] and 0xFF
            }
        }
        val blocks = ArrayList<ByteArray>()
        val geometry: List<Field>
        if (tile > 0) {
            for ((p, b) in planes.withIndex()) {
                val tw = if (p == 0) tile else tile / sub
                for (ty in 0 until (h + tile - 1) / tile) for (tx in 0 until (w + tile - 1) / tile) {
                    // A tile is padded to its full size by repeating the edge, as libjpeg pads.
                    blocks += jpeg(KiteBitmap(tw, tw, IntArray(tw * tw) { i ->
                        b[minOf(tx * tw + i % tw, b.width - 1), minOf(ty * tw + i / tw, b.height - 1)]
                    }))
                    place(p, blocks.last(), tx * tw, ty * tw)
                }
            }
            geometry = listOf(long(322, tile.toLong()), long(323, tile.toLong()))
        } else {
            val rows = (h + strips - 1) / strips
            for ((p, b) in planes.withIndex()) {
                val r = if (p == 0) rows else rows / sub
                for (s in 0 until strips) {
                    val y0 = s * r
                    val n = minOf(r, b.height - y0)
                    blocks += jpeg(KiteBitmap(b.width, n, b.argb.copyOfRange(y0 * b.width, (y0 + n) * b.width)))
                    place(p, blocks.last(), 0, y0)
                }
            }
            geometry = listOf(long(278, rows.toLong()))
        }
        val bytes = tiff(
            listOf(long(256, w.toLong()), long(257, h.toLong()), short(258, 8, 8, 8), short(259, 7), short(262, 6),
                short(277, 3), short(284, 2), short(530, sub, sub)) + geometry,
            blocks, tiled = tile > 0,
        )
        return Separate(bytes, w, h, decoded)
    }

    @Test
    fun jpegYcbcrInSeparatePlanesConvertsLikeUncompressedYcbcr() {
        for ((sub, strips, tile) in listOf(Triple(1, 1, 0), Triple(2, 1, 0), Triple(2, 2, 0), Triple(2, 1, 16), Triple(1, 3, 0))) {
            val f = separateJpeg(sub, strips, tile)
            assertTrue(ImageKodec.probe(f.bytes).isDecodable)
            val decoded = ImageKodec.decode(f.bytes)
            val cw = (f.width + sub - 1) / sub
            for (y in 0 until f.height) for (x in 0 until f.width) {
                val c = (y / sub) * cw + x / sub
                assertEquals(ycbcr(f.planes[0][y * f.width + x], f.planes[1][c], f.planes[2][c]), decoded[x, y], "$sub/$strips/$tile ($x, $y)")
            }
        }
    }
}
