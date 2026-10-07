package io.github.yuroyami.imagekodec

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The TIFF features no tool on hand can generate: YCbCr photometric with and
 * without chroma subsampling, separate planes, sub-byte depths and 16-bit
 * samples. The builder writes the IFD by hand, so each vector is the spec
 * restated rather than an opaque blob, and the expectations are computed from
 * the same colour maths the spec prescribes.
 *
 * The tiled and planar cases that libtiff *can* produce are cross-checked
 * against an independent reader in the jvmTest oracle instead.
 */
class TiffExtendedTest {

    @Test
    fun unsupportedSampleFormatsAreTypedRefusals() {
        for (format in listOf(2, 3, 5, 6, 65535)) {
            val stored = if (format == 3) intArrayOf(0, 0x3400, 0x3800, 0x3C00)
                else intArrayOf(0x8000, 0xC000, 0, 0x7FFF)
            val bytes = sampleFormatTiff(intArrayOf(format), stored = stored)
            val info = ImageKodec.probe(bytes)
            assertFalse(info.isDecodable)
            assertTrue("SampleFormat $format" in info.unsupportedReason.orEmpty())
            for (decode in listOf<() -> Unit>(
                { ImageKodec.decode(bytes) }, { ImageKodec.decodeReduced(bytes, 2) },
                { ImageKodec.decodeAnimation(bytes) },
            )) {
                val error = assertFailsWith<UnsupportedImageException> { decode() }
                assertTrue("SampleFormat $format" in error.message.orEmpty())
            }
        }
    }

    @Test
    fun aNonFirstSampleFormatIsRefused() {
        for (formats in listOf(intArrayOf(1, 1, 3), intArrayOf(1, 2, 1), intArrayOf(1, 1, 1, 3))) {
            val bytes = sampleFormatTiff(formats, samples = formats.size)
            assertFalse(ImageKodec.probe(bytes).isDecodable)
            assertFailsWith<UnsupportedImageException> { ImageKodec.decode(bytes) }
        }
    }

    @Test
    fun unsignedAndUndefinedSampleFormatsKeepTheirPixels() {
        for (samples in listOf(1, 3, 4)) {
            val baseline = ImageKodec.decode(sampleFormatTiff(samples = samples))
            for (format in listOf(1, 4)) {
                val bytes = sampleFormatTiff(IntArray(samples) { format }, samples = samples)
                assertTrue(ImageKodec.probe(bytes).isDecodable)
                val bitmap = ImageKodec.decode(bytes)
                for (i in bitmap.argb.indices) assertEquals(baseline.argb[i], bitmap.argb[i])
            }
        }
    }

    @Test
    fun malformedSampleFormatsAreDecodeFaults() {
        for (bytes in listOf(
            sampleFormatTiff(intArrayOf(1), type = 1), sampleFormatTiff(intArrayOf(1), type = 4),
            sampleFormatTiff(intArrayOf()), sampleFormatTiff(intArrayOf(1, 1)),
            sampleFormatTiff(intArrayOf(1), samples = 3),
        )) {
            assertFailsWith<ImageDecodeException> { ImageKodec.decode(bytes) }
            assertFailsWith<ImageDecodeException> { ImageKodec.probe(bytes) }
        }
    }

    internal fun sampleFormatTiff(
        formats: IntArray? = null,
        samples: Int = 1,
        type: Int = 3,
        stored: IntArray = intArrayOf(0x8000, 0xC000, 0, 0x7FFF),
    ): ByteArray {
        val raw = ByteArray(4 * samples * 2) {
            (stored[(it / 2) / samples] ushr (8 * (it % 2))).toByte()
        }
        val fields = mutableListOf(
            long(256, 4), long(257, 1), Field(258, 3, LongArray(samples) { 16 }),
            short(259, 1), short(262, if (samples >= 3) 2 else 1), short(277, samples),
            short(284, 1), long(273, 0), long(278, 1), long(279, raw.size),
        )
        if (formats != null) fields.add(Field(339, type, LongArray(formats.size) { formats[it].toLong() }))
        return tiff(fields, raw)
    }

    @Test
    fun deflateStripBoundariesAndInvalidCodesUseTheSameContract() {
        fun deflateTiff(width: Int, height: Int, compressed: ByteArray): ByteArray = tiff(
            listOf(long(256, width), long(257, height), short(258, 8), short(259, 8),
                short(262, 1), short(277, 1), long(278, height), long(273, 0), long(279, compressed.size)),
            compressed,
        )
        val expected = hex(FLUSHED_RAW)
        val decoded = ImageKodec.decode(deflateTiff(expected.size, 1, hex(FLUSHED_ZLIB)))
        for (x in expected.indices) assertEquals(gray(expected[x].toInt() and 255), decoded[x, 0])
        val damaged = deflateTiff(4, 4, hex(INVALID_DISTANCE_ZLIB))
        assertFailsWith<ImageDecodeException> { ImageKodec.decode(damaged) }
    }

    @Test
    fun malformedOptionalTagShapesNameTheTag() {
        val cases = listOf(
            short(530), short(530, 1), short(530, 1, 1, 1), long(530, 1, 1),
            long(292), long(292, 0, 0), short(292, 0),
        )
        for (field in cases) {
            val bytes = if (field.tag == 530) {
                greyTiff(short(262, 6), short(277, 3), field)
            } else {
                greyTiff(short(259, 3), field)
            }
            val error = assertFailsWith<ImageDecodeException> { ImageKodec.decode(bytes) }
            assertTrue(field.tag.toString() in error.message.orEmpty(), error.message)
        }
    }

    @Test
    fun absentAndValidSubsamplingKeepTheirSpecifiedMeanings() {
        val fields = listOf(
            long(256, 1), long(257, 1), short(258, 8, 8, 8),
            short(259, 1), short(262, 6), short(277, 3),
            long(278, 1), long(273, 0), long(279, 6),
        )
        val default = ImageKodec.decode(tiff(fields, bytes(100, 100, 100, 100, 128, 128)))
        assertEquals(gray(100), default[0, 0])
        val explicit = fields.filter { it.tag != 279 } + long(279, 3) + short(530, 1, 1)
        assertEquals(gray(100), ImageKodec.decode(tiff(explicit, bytes(100, 128, 128)))[0, 0])
    }

    // --- IFD builder --------------------------------------------------------------

    private class Field(val tag: Int, val type: Int, val values: LongArray)

    private fun short(tag: Int, vararg v: Int) = Field(tag, 3, LongArray(v.size) { v[it].toLong() })
    private fun long(tag: Int, vararg v: Int) = Field(tag, 4, LongArray(v.size) { v[it].toLong() })

    /** Little-endian TIFF with one IFD, its fields, and one pixel blob. */
    private fun tiff(fields: List<Field>, pixels: ByteArray, stripOffsetTag: Int = 273): ByteArray {
        val sorted = fields.sortedBy { it.tag }
        fun typeSize(t: Int) = when (t) { 3 -> 2; 4 -> 4; else -> 1 }

        val ifdOffset = 8
        val ifdBytes = 2 + sorted.size * 12 + 4
        var overflowAt = ifdOffset + ifdBytes
        // Fields whose values do not fit the 4-byte slot live after the IFD.
        val overflow = ArrayList<Pair<Field, Int>>()
        for (f in sorted) {
            val size = typeSize(f.type) * f.values.size
            if (size > 4) {
                overflow.add(f to overflowAt)
                overflowAt += size
            }
        }
        val pixelsAt = overflowAt
        val total = pixelsAt + pixels.size
        val out = ByteArray(total)
        var at = 0
        fun u8(v: Int) { out[at++] = (v and 0xFF).toByte() }
        fun u16(v: Int) { u8(v); u8(v ushr 8) }
        fun u32(v: Int) { u16(v); u16(v ushr 16) }

        // Strip offsets are written relative to the pixel blob, so a test can say
        // "plane 1 starts here" without knowing the header size.
        fun valueOf(f: Field, i: Int): Int =
            if (f.tag == stripOffsetTag) pixelsAt + f.values[i].toInt() else f.values[i].toInt()

        u8('I'.code); u8('I'.code); u16(42); u32(ifdOffset)
        u16(sorted.size)
        for (f in sorted) {
            u16(f.tag)
            u16(f.type)
            u32(f.values.size)
            val size = typeSize(f.type) * f.values.size
            val slot = at
            if (size > 4) {
                u32(overflow.first { it.first === f }.second)
            } else {
                // Inline: values are packed from the low end of the slot.
                var written = 0
                for (i in f.values.indices) {
                    when (f.type) {
                        3 -> { u16(valueOf(f, i)); written += 2 }
                        4 -> { u32(valueOf(f, i)); written += 4 }
                        else -> { u8(valueOf(f, i)); written += 1 }
                    }
                }
                while (written < 4) { u8(0); written++ }
            }
            at = slot + 4
        }
        u32(0)                                             // no next IFD

        for ((f, offset) in overflow) {
            at = offset
            for (i in f.values.indices) {
                when (f.type) {
                    3 -> u16(valueOf(f, i))
                    4 -> u32(valueOf(f, i))
                    else -> u8(valueOf(f, i))
                }
            }
        }
        at = pixelsAt
        pixels.copyInto(out, at)
        return out
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { (v[it] and 0xFF).toByte() }

    /** The conversion the TIFF spec prescribes, as the expectation side of the test. */
    private fun expectRgb(y: Int, cb: Int, cr: Int): Triple<Int, Int, Int> {
        val fixed = (y shl 16) + (1 shl 15)
        fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v
        return Triple(
            clamp((fixed + 91881 * (cr - 128)) shr 16),
            clamp((fixed - 22554 * (cb - 128) - 46802 * (cr - 128)) shr 16),
            clamp((fixed + 116130 * (cb - 128)) shr 16),
        )
    }

    // --- YCbCr ---------------------------------------------------------------------

    @Test
    fun ycbcrWithoutSubsamplingConverts() {
        val w = 2
        val h = 2
        val px = bytes(
            200, 100, 150, /**/ 60, 200, 90,
            128, 128, 128, /**/ 255, 0, 255,
        )
        val bytes = tiff(
            listOf(
                long(256, w), long(257, h),
                short(258, 8, 8, 8),
                short(259, 1),                 // no compression
                short(262, 6),                 // YCbCr
                short(277, 3),
                short(530, 1, 1),              // no subsampling
                short(284, 1),
                long(273, 0), long(279, px.size),
                long(278, h),
            ),
            px,
        )
        val bm = ImageKodec.decode(bytes)
        assertEquals(2, bm.width)
        val samples = listOf(
            Triple(200, 100, 150), Triple(60, 200, 90),
            Triple(128, 128, 128), Triple(255, 0, 255),
        )
        for (i in samples.indices) {
            val (y, cb, cr) = samples[i]
            val (r, g, b) = expectRgb(y, cb, cr)
            val p = bm[i % 2, i / 2]
            assertEquals(argb(0xFF, r, g, b), p, "pixel $i")
        }
    }

    @Test
    fun ycbcrWithTwoByTwoSubsamplingExpandsChroma() {
        // One 2x2 unit: four luma samples, then one Cb and one Cr for all of them.
        val w = 2
        val h = 2
        val px = bytes(30, 90, 150, 210, /* Cb */ 90, /* Cr */ 200)
        val bytes = tiff(
            listOf(
                long(256, w), long(257, h),
                short(258, 8, 8, 8),
                short(259, 1),
                short(262, 6),
                short(277, 3),
                short(530, 2, 2),
                short(284, 1),
                long(273, 0), long(279, px.size),
                long(278, h),
            ),
            px,
        )
        val bm = ImageKodec.decode(bytes)
        val luma = intArrayOf(30, 90, 150, 210)
        for (i in 0 until 4) {
            val (r, g, b) = expectRgb(luma[i], 90, 200)
            assertEquals(argb(0xFF, r, g, b), bm[i % 2, i / 2], "pixel $i shares the unit chroma")
        }
    }

    @Test
    fun ycbcrGreyMapsToGrey() {
        // Neutral chroma must come back as a pure grey: the classic sanity check
        // that the -128 offsets are on the right side.
        val px = bytes(64, 128, 128, 192, 128, 128)
        val bytes = tiff(
            listOf(
                long(256, 2), long(257, 1),
                short(258, 8, 8, 8), short(259, 1), short(262, 6), short(277, 3),
                short(530, 1, 1), short(284, 1),
                long(273, 0), long(279, px.size), long(278, 1),
            ),
            px,
        )
        val bm = ImageKodec.decode(bytes)
        for (x in 0 until 2) {
            val p = bm[x, 0]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            assertTrue(abs(r - g) <= 1 && abs(g - b) <= 1, "expected grey, got $r/$g/$b")
        }
        assertTrue(((bm[0, 0] ushr 16) and 0xFF) < ((bm[1, 0] ushr 16) and 0xFF))
    }

    internal fun tiledYcbcr(subH: Int, subV: Int, defaultSampling: Boolean = false): ByteArray {
        val width = 19; val height = 17
        val tileSize = (16 / subH) * (16 / subV) * (subH * subV + 2)
        val pixels = ByteArray(tileSize * 4)
        var at = 0
        for (ty in 0..1) for (tx in 0..1) {
            for (uy in 0 until 16 step subV) for (ux in 0 until 16 step subH) {
                for (dy in 0 until subV) for (dx in 0 until subH) {
                    pixels[at++] = ((tx * 16 + ux + dx) * 5 + (ty * 16 + uy + dy) * 3).toByte()
                }
                pixels[at++] = 128.toByte(); pixels[at++] = 128.toByte()
            }
        }
        return tiff(
            listOfNotNull(long(256, width), long(257, height), short(258, 8, 8, 8),
                short(259, 1), short(262, 6), short(277, 3), short(284, 1),
                if (defaultSampling) null else short(530, subH, subV),
                long(322, 16), long(323, 16), long(324, 0, tileSize, tileSize * 2, tileSize * 3),
                long(325, tileSize, tileSize, tileSize, tileSize)), pixels, stripOffsetTag = 324,
        )
    }

    @Test
    fun subsampledYcbcrTilesUseUnitRowsAndPreserveEdgePadding() {
        val sampling = listOf(1 to 1, 2 to 1, 2 to 2, 4 to 1, 4 to 2, 4 to 4)
        for ((h, v) in sampling) {
            val bitmap = ImageKodec.decode(tiledYcbcr(h, v))
            for (y in 0 until 17) for (x in 0 until 19) assertEquals(gray(x * 5 + y * 3), bitmap[x, y], "$h x $v at $x, $y")
        }
        val bitmap = ImageKodec.decode(tiledYcbcr(2, 2, defaultSampling = true))
        for (y in 0 until 17) for (x in 0 until 19) assertEquals(gray(x * 5 + y * 3), bitmap[x, y])
    }

    // --- planar configuration --------------------------------------------------------

    @Test
    fun separatePlanesInterleaveBackCorrectly() {
        val w = 3
        val h = 2
        // One strip per plane: all reds, then all greens, then all blues.
        val reds = bytes(10, 20, 30, 40, 50, 60)
        val greens = bytes(70, 80, 90, 100, 110, 120)
        val blues = bytes(130, 140, 150, 160, 170, 180)
        val px = reds + greens + blues
        val bytes = tiff(
            listOf(
                long(256, w), long(257, h),
                short(258, 8, 8, 8), short(259, 1), short(262, 2), short(277, 3),
                short(284, 2),                                     // separate planes
                long(278, h),
                // One strip per plane, at increasing offsets into the pixel blob.
                long(273, 0, reds.size, reds.size + greens.size),
                long(279, reds.size, greens.size, blues.size),
            ),
            px,
        )
        val bm = ImageKodec.decode(bytes)
        assertEquals(w, bm.width)
        assertEquals(h, bm.height)
        for (i in 0 until 6) {
            val expect = argb(
                0xFF,
                reds[i].toInt() and 0xFF,
                greens[i].toInt() and 0xFF,
                blues[i].toInt() and 0xFF,
            )
            assertEquals(expect, bm[i % w, i / w], "pixel $i")
        }
    }

    // --- bit depths -------------------------------------------------------------------

    @Test
    fun sixteenBitSamplesNarrowToTheirHighByte() {
        val w = 2
        val h = 1
        // Little-endian 16-bit RGB: 0x1234, 0x5678, 0x9ABC then 0xFF00, 0x00FF, 0x8080.
        val px = bytes(
            0x34, 0x12, 0x78, 0x56, 0xBC, 0x9A,
            0x00, 0xFF, 0xFF, 0x00, 0x80, 0x80,
        )
        val bytes = tiff(
            listOf(
                long(256, w), long(257, h),
                short(258, 16, 16, 16), short(259, 1), short(262, 2), short(277, 3),
                short(284, 1), long(278, h),
                long(273, 0), long(279, px.size),
            ),
            px,
        )
        val bm = ImageKodec.decode(bytes)
        assertEquals(argb(0xFF, 0x12, 0x56, 0x9A), bm[0, 0])
        assertEquals(argb(0xFF, 0xFF, 0x00, 0x80), bm[1, 0])
        assertEquals(16, ImageKodec.probe(bytes).bitDepth)
    }

    @Test
    fun fourBitGreyScalesByReplication() {
        val px = bytes(0x0F, 0x80)                 // pixels 0, 15, 8, 0
        val bytes = tiff(
            listOf(
                long(256, 4), long(257, 1),
                short(258, 4), short(259, 1), short(262, 1), short(277, 1),
                short(284, 1), long(278, 1),
                long(273, 0), long(279, px.size),
            ),
            px,
        )
        val bm = ImageKodec.decode(bytes)
        assertEquals(argb(0xFF, 0, 0, 0), bm[0, 0])
        assertEquals(argb(0xFF, 255, 255, 255), bm[1, 0], "15 must reach 255")
        assertEquals(argb(0xFF, 136, 136, 136), bm[2, 0], "8 * 17")
        assertEquals(argb(0xFF, 0, 0, 0), bm[3, 0])
    }

    @Test
    fun greyWithAlphaKeepsTheSecondSample() {
        val px = bytes(200, 64, 100, 255)
        val bytes = tiff(
            listOf(
                long(256, 2), long(257, 1),
                short(258, 8, 8), short(259, 1), short(262, 1), short(277, 2),
                short(338, 2), short(284, 1), long(278, 1),
                long(273, 0), long(279, px.size),
            ),
            px,
        )
        val bm = ImageKodec.decode(bytes)
        assertEquals(argb(64, 200, 200, 200), bm[0, 0])
        assertEquals(argb(255, 100, 100, 100), bm[1, 0])
        assertTrue(ImageKodec.probe(bytes).hasAlpha)
    }

    // --- damaged and unusual files ----------------------------------------------------

    @Test
    fun aCorruptDeflateStripIsADecodeError() {
        // The strip bytes 0x40 0xC0 are not a zlib header. PNG converts that failure
        // into an ImageDecodeException, and TIFF has to do the same.
        for (compression in intArrayOf(8, 32946)) {
            val e = assertFailsWith<ImageDecodeException>("compression $compression") {
                ImageKodec.decode(greyTiff(short(259, compression)))
            }
            assertTrue(e.message!!.contains("inflate"), "message should name the problem: ${e.message}")
        }
    }

    @Test
    fun aMissingRowsPerStripMeansOneStrip() {
        // TIFF 6.0 defaults RowsPerStrip to 2^32 - 1, which means the whole image is one
        // strip. The value must be clamped before it is narrowed to an Int.
        val px = bytes(0x11, 0x22)
        for (rowsPerStrip in listOf<Field?>(null, long(278, -1))) {     // absent, and stated
            val fields = listOfNotNull(
                long(256, 2), long(257, 1), short(258, 8), short(259, 1), short(262, 1),
                short(277, 1), long(273, 0), long(279, px.size), rowsPerStrip,
            )
            val bitmap = ImageKodec.decode(tiff(fields, px))
            assertEquals(gray(0x11), bitmap[0, 0])
            assertEquals(gray(0x22), bitmap[1, 0])
        }
    }

    // --- probe agrees with decode ---------------------------------------------------

    /**
     * A 2x1 uncompressed 8-bit greyscale file, with [overrides] replacing fields
     * by tag. The base is fully supported, so each override isolates exactly one
     * feature.
     */
    private fun greyTiff(vararg overrides: Field): ByteArray {
        val px = bytes(0x40, 0xC0)
        val fields = mutableListOf(
            long(256, 2), long(257, 1),
            short(258, 8), short(259, 1), short(262, 1), short(277, 1),
            short(284, 1), long(278, 1),
            long(273, 0), long(279, px.size),
        )
        for (o in overrides) {
            fields.removeAll { it.tag == o.tag }
            fields.add(o)
        }
        return tiff(fields, px)
    }

    @Test
    fun supportedTiffProbesDecodable() {
        val bytes = greyTiff()
        val info = ImageKodec.probe(bytes)
        assertTrue(info.isDecodable, "reason: ${info.unsupportedReason}")
        assertNull(info.unsupportedReason)
        assertEquals(2, ImageKodec.decode(bytes).width)
    }

    /**
     * Every TIFF feature the decoder throws [UnsupportedImageException] for has to
     * be visible from the IFD alone, or `probe` is promising pixels that `decode`
     * will not deliver.
     */
    @Test
    fun probeRefusesTheTiffFeaturesTheDecoderRefuses() {
        val cases = listOf(
            "JPEG-in-TIFF" to greyTiff(short(259, 7)),
            "32-bit samples" to greyTiff(short(258, 32)),
            "floating-point predictor" to greyTiff(short(317, 3)),
            "predictor 2 at 4 bits" to greyTiff(short(317, 2), short(258, 4)),
            "CMYK photometric" to greyTiff(short(262, 5)),
            "CCITT G3 two-dimensional" to greyTiff(short(259, 3), long(292, 1)),
            "16-bit YCbCr" to greyTiff(short(262, 6), short(258, 16), short(277, 3)),
        )
        for ((name, bytes) in cases) {
            val info = ImageKodec.probe(bytes)
            assertFalse(info.isDecodable, "$name should probe undecodable")
            assertNotNull(info.unsupportedReason, "$name reason")
            assertFailsWith<UnsupportedImageException>(name) { ImageKodec.decode(bytes) }
        }
    }
}
