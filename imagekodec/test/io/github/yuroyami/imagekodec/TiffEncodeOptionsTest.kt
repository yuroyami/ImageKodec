package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.TiffDecoder
import io.github.yuroyami.imagekodec.codec.bilevelRows
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The TIFF writer's options (#184): every compression, layout and kind of sample reads back through
 * ImageKodec's decoder, exactly where it is lossless and within bounds under JPEG, and the calls
 * without options keep the bytes they wrote before. `TiffEncodeOptionsOracleTest` holds the same
 * files to libtiff and ImageIO.
 */
class TiffEncodeOptionsTest {

    private val lossless = listOf(TiffCompression.None, TiffCompression.PackBits, TiffCompression.Lzw, TiffCompression.Deflate)

    /** Strips of the default size, short strips, and tiles that leave a ragged edge. */
    private fun layouts(compression: TiffCompression, predictor: Boolean = true) = listOf(
        TiffEncodeOptions(compression = compression, predictor = predictor),
        TiffEncodeOptions(compression = compression, predictor = predictor, rowsPerStrip = 7),
        TiffEncodeOptions(compression = compression, predictor = predictor, tileWidth = 16, tileLength = 32),
    )

    /** A picture of [colours] colours, in runs and noise, so that every coder meets both. */
    private fun fewColours(w: Int, h: Int, colours: Int, seed: Long = 1): KiteBitmap {
        val random = BilevelPages.Random(seed)
        val palette = IntArray(colours) { argb(255, (it * 97) and 0xFF, (it * 31 + 7) and 0xFF, (it * 13) and 0xFF) }
        return KiteBitmap(w, h, IntArray(w * h) { i ->
            val x = i % w
            if (x < w / 2) palette[(i / w / 3) % colours] else palette[random.next(colours)]
        })
    }

    private val sizes = listOf(1 to 1, 37 to 1, 1 to 29, 37 to 23, 70 to 41)

    @Test
    fun theCallsWithoutOptionsKeepTheirBytes() {
        assertEquals(LEGACY_HASHES, EncoderFixtures.legacyHashes().map { it.toULong() })
        val card = EncoderFixtures.card(300, 211, alpha = true, gray = false)
        assertContentEquals(ImageKodec.encodeTiff(card), ImageKodec.encodeTiff(card, TiffEncodeOptions()))
        val pages = listOf(card, EncoderFixtures.card(16, 16, alpha = false, gray = true))
        assertContentEquals(ImageKodec.encodeTiff(pages), ImageKodec.encodeTiff(pages, TiffEncodeOptions()))
        val wide = EncoderFixtures.wide(29, 19, 4)
        assertContentEquals(ImageKodec.encodeTiff16(wide), ImageKodec.encodeTiff16(wide, TiffEncodeOptions()))
    }

    @Test
    fun everyLosslessCompressionAndLayoutReadsBackExactly() {
        for (compression in lossless) for (predictor in listOf(true, false)) for (options in layouts(compression, predictor)) {
            for ((w, h) in sizes) for (alpha in listOf(false, true)) for (gray in listOf(false, true)) {
                val src = EncoderFixtures.card(w, h, alpha, gray, seed = w + h)
                val name = "$options ${w}x$h alpha $alpha gray $gray"
                val data = ImageKodec.encodeTiff(src, options)
                assertContentEquals(src.argb, ImageKodec.decode(data).argb, name)
                assertEquals(alpha, ImageKodec.probe(data).hasAlpha, name)
            }
            // A fully transparent page keeps its colour under alpha 0, unassociated.
            val clear = KiteBitmap(9, 5, IntArray(45) { argb(0, it * 5, 255 - it, 7) })
            assertContentEquals(clear.argb, ImageKodec.decode(ImageKodec.encodeTiff(clear, options)).argb, "$options transparent")
        }
    }

    @Test
    fun grayAndRgbAreWrittenAsAsked() {
        val gray = EncoderFixtures.card(37, 23, alpha = true, gray = true)
        val rgb = TiffEncodeOptions(photometric = TiffPhotometric.Rgb)
        assertContentEquals(gray.argb, ImageKodec.decode(ImageKodec.encodeTiff(gray, rgb)).argb)
        assertEquals(4, ImageKodec.decode16(ImageKodec.encodeTiff(gray, rgb)).channels)
        // Gray from colour is each pixel's luma, in 256ths of BT.601's weights.
        val colour = EncoderFixtures.card(37, 23, alpha = true, gray = false)
        val back = ImageKodec.decode(ImageKodec.encodeTiff(colour, TiffEncodeOptions(photometric = TiffPhotometric.Gray)))
        for (i in colour.argb.indices) {
            val p = colour.argb[i]
            val l = (77 * (p ushr 16 and 0xFF) + 150 * (p ushr 8 and 0xFF) + 29 * (p and 0xFF) + 128) ushr 8
            assertEquals(argb(p ushr 24, l, l, l), back.argb[i], "pixel $i")
        }
    }

    @Test
    fun palettePagesReadBackExactlyAtEveryDepth() {
        for (colours in listOf(1, 2, 3, 4, 5, 16, 17, 256)) for ((w, h) in sizes + (300 to 9)) {
            val src = fewColours(w, h, colours, seed = colours.toLong())
            for (compression in lossless) for (options in layouts(compression)) {
                val o = TiffEncodeOptions(compression, TiffPhotometric.Palette, options.predictor, rowsPerStrip = options.rowsPerStrip, tileWidth = options.tileWidth, tileLength = options.tileLength)
                assertContentEquals(src.argb, ImageKodec.decode(ImageKodec.encodeTiff(src, o)).argb, "$o $colours colours ${w}x$h")
            }
        }
        val palette = TiffEncodeOptions(photometric = TiffPhotometric.Palette)
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeTiff(KiteBitmap(257, 1, IntArray(257) { argb(255, it and 0xFF, it shr 8, 0) }), palette) }
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeTiff(EncoderFixtures.card(37, 23, alpha = false, gray = false), palette) }
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeTiff(KiteBitmap(2, 1, intArrayOf(0x7F000000, -1)), palette) }
    }

    /** What the bi-level writer stores: black where [bilevelRows] sets a bit. */
    private fun thresholded(src: KiteBitmap): IntArray {
        val rows = bilevelRows(src, blackIs1 = true)
        val stride = (src.width + 7) / 8
        return IntArray(src.width * src.height) { i ->
            val x = i % src.width
            val bit = rows[(i / src.width) * stride + (x shr 3)].toInt() and (0x80 ushr (x and 7))
            if (bit != 0) 0xFF000000.toInt() else -1
        }
    }

    @Test
    fun biLevelPagesReadBackInEveryCompression() {
        val compressions = listOf(TiffCompression.CcittG4, TiffCompression.CcittG3) + lossless
        for (page in BilevelPages.edges) for (compression in compressions) {
            val src = page.bitmap()
            val strips = TiffEncodeOptions(compression = compression, photometric = TiffPhotometric.BiLevel, rowsPerStrip = 16)
            val tiles = TiffEncodeOptions(compression = compression, photometric = TiffPhotometric.BiLevel, tileWidth = 64, tileLength = 16)
            for (options in listOf(TiffEncodeOptions(compression = compression, photometric = TiffPhotometric.BiLevel), strips, tiles)) {
                if (options.tiled && page.width > 2000) continue
                assertContentEquals(src.argb, ImageKodec.decode(ImageKodec.encodeTiff(src, options)).argb, "${page.name} $options")
            }
        }
        // Auto picks bi-level under fax compression, thresholded as encodeCcitt does.
        val card = EncoderFixtures.card(70, 41, alpha = true, gray = false)
        for (compression in listOf(TiffCompression.CcittG4, TiffCompression.CcittG3)) {
            val data = ImageKodec.encodeTiff(card, TiffEncodeOptions(compression = compression))
            assertContentEquals(thresholded(card), ImageKodec.decode(data).argb, "$compression")
            assertEquals(1, ImageKodec.probe(data).bitDepth, "$compression")
        }
        val g4 = ImageKodec.encodeTiff(card, TiffEncodeOptions(compression = TiffCompression.CcittG4))
        assertContentEquals(ImageKodec.encodeCcitt(card), stripOf(g4), "the strip is encodeCcitt's data")
    }

    /** The first strip of [tiff]'s first page, by its StripOffsets and StripByteCounts. */
    private fun stripOf(tiff: ByteArray): ByteArray {
        fun u16(at: Int) = (tiff[at].toInt() and 0xFF) or ((tiff[at + 1].toInt() and 0xFF) shl 8)
        fun u32(at: Int) = u16(at) or (u16(at + 2) shl 16)
        val ifd = u32(4)
        var offset = -1
        var count = -1
        for (e in 0 until u16(ifd)) {
            val at = ifd + 2 + 12 * e
            when (u16(at)) {
                273 -> offset = u32(at + 8)
                279 -> count = u32(at + 8)
            }
        }
        return tiff.copyOfRange(offset, offset + count)
    }

    @Test
    fun jpegPagesReadBackWithinBounds() {
        val photo = PhotoScenes.bokeh(160, 120).bitmap
        for (quality in listOf(50, 90, 95)) {
            // One strip holds the same stream encodeJpeg writes, so it decodes to the same pixels.
            val whole = ImageKodec.encodeTiff(photo, TiffEncodeOptions(compression = TiffCompression.Jpeg, quality = quality, rowsPerStrip = photo.height))
            assertContentEquals(ImageKodec.decode(ImageKodec.encodeJpeg(photo, quality)).argb, ImageKodec.decode(whole).argb, "quality $quality")
            for (options in listOf(
                TiffEncodeOptions(compression = TiffCompression.Jpeg, quality = quality),
                TiffEncodeOptions(compression = TiffCompression.Jpeg, quality = quality, rowsPerStrip = 16),
                TiffEncodeOptions(compression = TiffCompression.Jpeg, quality = quality, tileWidth = 32, tileLength = 48),
            )) {
                val back = ImageKodec.decode(ImageKodec.encodeTiff(photo, options))
                val (mean, worst) = error(photo, back)
                assertTrue(mean < (if (quality >= 90) 2.0 else 4.0) && worst < 100, "$options: mean $mean, worst $worst")
            }
        }
        for ((w, h) in sizes) {
            val gray = EncoderFixtures.card(w, h, alpha = false, gray = true)
            val back = ImageKodec.decode(ImageKodec.encodeTiff(gray, TiffEncodeOptions(compression = TiffCompression.Jpeg, tileWidth = 16, tileLength = 16)))
            assertTrue(error(gray, back).second <= 24, "gray ${w}x$h: ${error(gray, back)}")
            assertEquals(8, ImageKodec.probe(ImageKodec.encodeTiff(gray, TiffEncodeOptions(compression = TiffCompression.Jpeg))).bitDepth)
        }
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeTiff(EncoderFixtures.card(9, 9, true, false), TiffEncodeOptions(compression = TiffCompression.Jpeg)) }
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeTiff(photo, TiffEncodeOptions(compression = TiffCompression.Jpeg, rowsPerStrip = 12)) }
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeTiff(photo, TiffEncodeOptions(compression = TiffCompression.Jpeg, photometric = TiffPhotometric.Palette)) }
    }

    /** The mean and worst difference of the colour channels of two bitmaps of one size. */
    private fun error(a: KiteBitmap, b: KiteBitmap): Pair<Double, Int> {
        var sum = 0L
        var worst = 0
        for (i in a.argb.indices) for (shift in intArrayOf(0, 8, 16)) {
            val d = kotlin.math.abs(((a.argb[i] ushr shift) and 0xFF) - ((b.argb[i] ushr shift) and 0xFF))
            sum += d
            worst = maxOf(worst, d)
        }
        return sum.toDouble() / (a.argb.size * 3) to worst
    }

    @Test
    fun sixteenBitPagesReadBackExactly() {
        for (compression in lossless) for (options in layouts(compression)) for (channels in 1..4) for ((w, h) in sizes) {
            val src = EncoderFixtures.wide(w, h, channels)
            val back = ImageKodec.decode16(ImageKodec.encodeTiff16(src, options))
            assertEquals(channels, back.channels, "$options $channels channels ${w}x$h")
            assertContentEquals(src.samples, back.samples, "$options $channels channels ${w}x$h")
        }
        val rgb = EncoderFixtures.wide(9, 7, 4)
        val gray = ImageKodec.decode16(ImageKodec.encodeTiff16(rgb, TiffEncodeOptions(photometric = TiffPhotometric.Gray)))
        assertEquals(2, gray.channels)
        for (p in 0 until 63) {
            fun s(c: Int) = rgb.samples[p * 4 + c].toInt() and 0xFFFF
            assertEquals((77 * s(0) + 150 * s(1) + 29 * s(2) + 128) ushr 8, gray.samples[p * 2].toInt() and 0xFFFF, "luma $p")
            assertEquals(s(3), gray.samples[p * 2 + 1].toInt() and 0xFFFF, "alpha $p")
        }
        val one = EncoderFixtures.wide(9, 7, 1)
        val spread = ImageKodec.decode16(ImageKodec.encodeTiff16(one, TiffEncodeOptions(photometric = TiffPhotometric.Rgb)))
        assertEquals(3, spread.channels)
        for (p in 0 until 63) for (c in 0 until 3) assertEquals(one.samples[p], spread.samples[p * 3 + c])
        for (bad in listOf(TiffEncodeOptions(compression = TiffCompression.Jpeg), TiffEncodeOptions(compression = TiffCompression.CcittG4), TiffEncodeOptions(photometric = TiffPhotometric.Palette))) {
            assertFailsWith<IllegalArgumentException>("$bad") { ImageKodec.encodeTiff16(one, bad) }
        }
    }

    @Test
    fun cmykInkReadsBackExactly() {
        val random = BilevelPages.Random(42)
        for ((w, h) in sizes) {
            val ink = ByteArray(w * h * 4) { if (it % 7 == 0) 0 else random.next(256).toByte() }
            val ink16 = ShortArray(w * h * 4) { random.next(65536).toShort() }
            for (compression in lossless) for (options in layouts(compression)) {
                val data = ImageKodec.encodeTiffCmyk(w, h, ink, options)
                assertContentEquals(ink, TiffDecoder.decodeInk(data)!!.ink, "$options ${w}x$h")
                val data16 = ImageKodec.encodeTiffCmyk(w, h, ink16, options)
                assertContentEquals(ink16, TiffDecoder.decodeInk(data16, wide = true)!!.ink16, "16-bit $options ${w}x$h")
            }
        }
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeTiffCmyk(2, 2, ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeTiffCmyk(2, 2, ByteArray(16), TiffEncodeOptions(compression = TiffCompression.Jpeg)) }
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeTiffCmyk(2, 2, ByteArray(16), TiffEncodeOptions(photometric = TiffPhotometric.Rgb)) }
    }

    @Test
    fun manyPagesAndMetadataGoWhereAsked() {
        val icc = ByteArray(300) { (it * 7).toByte() }
        val pages = listOf(BilevelPages.edges[10].bitmap(), BilevelPages.edges[11].bitmap())
        val data = ImageKodec.encodeTiff(pages, TiffEncodeOptions(compression = TiffCompression.CcittG4), ImageMetadata(icc = icc, xmp = "<x:xmpmeta/>"))
        assertEquals(2, ImageKodec.probe(data).pageCount)
        for (i in 0..1) {
            assertContentEquals(pages[i].argb, ImageKodec.decodePage(data, i).argb, "page $i")
            assertContentEquals(icc, ImageKodec.probePage(data, i).colorProfile?.icc, "profile of page $i")
        }
        assertTrue("<x:xmpmeta/>".encodeToByteArray().let { x -> (0..data.size - x.size).any { at -> x.indices.all { data[at + it] == x[it] } } })
    }

    @Test
    fun aCancelStopsTheEncode() {
        class Stop : RuntimeException()
        val card = EncoderFixtures.card(300, 211, alpha = false, gray = false)
        for (options in listOf(TiffEncodeOptions(), TiffEncodeOptions(compression = TiffCompression.Jpeg, tileWidth = 64, tileLength = 64), TiffEncodeOptions(compression = TiffCompression.CcittG4))) {
            var calls = 0
            assertFailsWith<Stop>("$options") { ImageKodec.encodeTiff(card, options, cancellationCheck = { if (++calls == 3) throw Stop() }) }
        }
    }

    @Test
    fun badOptionsAreRefused() {
        assertFailsWith<IllegalArgumentException> { TiffEncodeOptions(quality = 101) }
        assertFailsWith<IllegalArgumentException> { TiffEncodeOptions(rowsPerStrip = -1) }
        assertFailsWith<IllegalArgumentException> { TiffEncodeOptions(tileWidth = 24, tileLength = 16) }
        assertFailsWith<IllegalArgumentException> { TiffEncodeOptions(tileWidth = 16) }
        assertFailsWith<IllegalArgumentException> { TiffEncodeOptions(rowsPerStrip = 8, tileWidth = 16, tileLength = 16) }
        val card = EncoderFixtures.card(9, 9, alpha = false, gray = false)
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeTiff(card, TiffEncodeOptions(compression = TiffCompression.CcittG4, photometric = TiffPhotometric.Rgb)) }
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeTiff(card, TiffEncodeOptions(compression = TiffCompression.Jpeg, photometric = TiffPhotometric.BiLevel)) }
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeTiff(emptyList(), TiffEncodeOptions()) }
    }

    @Test
    fun theOutputIsTheSameOnEveryTarget() {
        val card = EncoderFixtures.card(70, 41, alpha = true, gray = false)
        val opaque = EncoderFixtures.card(70, 41, alpha = false, gray = false)
        val hashes = listOf(
            ImageKodec.encodeTiff(card, TiffEncodeOptions(compression = TiffCompression.Lzw)),
            ImageKodec.encodeTiff(card, TiffEncodeOptions(compression = TiffCompression.PackBits, tileWidth = 32, tileLength = 16)),
            ImageKodec.encodeTiff(opaque, TiffEncodeOptions(compression = TiffCompression.Jpeg, quality = 75)),
            ImageKodec.encodeTiff(opaque, TiffEncodeOptions(compression = TiffCompression.Jpeg, quality = 95, photometric = TiffPhotometric.Gray, tileWidth = 32, tileLength = 32)),
            ImageKodec.encodeTiff(BilevelPages.edges[10].bitmap(), TiffEncodeOptions(compression = TiffCompression.CcittG4)),
            ImageKodec.encodeTiff(BilevelPages.edges[11].bitmap(), TiffEncodeOptions(compression = TiffCompression.CcittG3, rowsPerStrip = 20)),
            ImageKodec.encodeTiff(fewColours(70, 41, 12), TiffEncodeOptions(compression = TiffCompression.Lzw, photometric = TiffPhotometric.Palette)),
            ImageKodec.encodeTiffCmyk(5, 3, ByteArray(60) { (it * 17).toByte() }, TiffEncodeOptions(compression = TiffCompression.None)),
        ).map { BilevelPages.fnv(it).toULong() }
        assertEquals(EXPECTED_HASHES, hashes)
    }

    private companion object {
        /** The calls that existed before #184, recorded after Deflate moved to ArchiveKodec. */
        val LEGACY_HASHES = listOf(
            0x79e7f416bfbc72a6uL, 0xba42a2c5738ce626uL, 0x82239a5559da37fcuL, 0x8d64f5aae642f783uL, 0x737291565d872d81uL,
            0xb52e8052c4bf0c01uL, 0x7c4b2092cf723fe5uL, 0xe0a9eb217b3cc8b8uL, 0xa46b5643c29e73c8uL,
        )

        val EXPECTED_HASHES = listOf(
            0xbb4b70f62d7b56a2uL, 0xc06e80c54bdd20d7uL, 0x646272f78dd37883uL, 0x0568ddcfcc116498uL,
            0x5ff890ba953d20ebuL, 0x10923221e353e38duL, 0x921b4ef0534e8e01uL, 0xcc6fc184aa6c6cdfuL,
        )
    }
}
