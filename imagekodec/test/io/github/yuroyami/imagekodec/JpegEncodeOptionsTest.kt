package io.github.yuroyami.imagekodec

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [ImageKodec.encodeJpeg] and [ImageKodec.encodeJpeg16] with [JpegEncodeOptions] (#183), on every
 * target. Progressive scans, arithmetic coding, built Huffman tables and restart markers change
 * the entropy coding only, so each must decode to exactly the pixels of the sequential file with
 * the same coefficients; lossless files must give back their samples; the frame header must name
 * the process asked for; and a few files are pinned by hash.
 */
class JpegEncodeOptionsTest {

    private fun fnv(d: ByteArray): Long {
        var h = -0x340d631b7bdddcdbL
        for (b in d) {
            h = h xor (b.toLong() and 0xFF)
            h *= 0x100000001b3L
        }
        return h
    }

    private fun photo(width: Int, height: Int, seed: Int, gray: Boolean = false): KiteBitmap {
        val random = Random(seed)
        return KiteBitmap(width, height, IntArray(width * height) {
            val x = it % width
            val y = it / width
            val edge = if ((x / 7 + y / 5) % 3 == 0) 70 else 0
            val r = (x * 255 / width + edge + random.nextInt(30)).coerceIn(0, 255)
            val g = if (gray) r else (y * 255 / height + random.nextInt(30)).coerceIn(0, 255)
            val b = if (gray) r else ((x + y) * 128 / (width + height) + edge + random.nextInt(30)).coerceIn(0, 255)
            0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        })
    }

    /** A picture of [channels] channels whose samples hold [bits] bits, widened as `decode16` widens them. */
    private fun deep(width: Int, height: Int, channels: Int, bits: Int, seed: Int): KiteBitmap16 {
        val random = Random(seed)
        val max = (1 shl bits) - 1
        val raw = IntArray(width * height * channels) {
            val c = it % channels
            val x = (it / channels) % width
            val y = (it / channels) / width
            val smooth = (x * max / maxOf(1, width - 1) + y * max / maxOf(1, height - 1)) / 2
            val edge = if ((x / 5 + y / 3 + c) % 4 == 0) max / 3 else 0
            (smooth + edge + random.nextInt(maxOf(2, max / 20)) - max / 40).coerceIn(0, max)
        }
        return KiteBitmap16(width, height, channels, ShortArray(raw.size) { widened(raw[it], bits).toShort() })
    }

    /** [v] of [bits] bits as `decode16` widens it. */
    private fun widened(v: Int, bits: Int): Int = when {
        bits >= 8 -> (v shl (16 - bits)) or (v shr (2 * bits - 16).coerceAtLeast(0))
        else -> v * 255 / ((1 shl bits) - 1) * 257
    }

    private class Frame(val marker: Int, val precision: Int, val sampling: List<Int>, val dac: Boolean, val restart: Boolean, val scans: Int)

    private fun frame(data: ByteArray): Frame {
        var at = 2
        var marker = -1
        var precision = 0
        var sampling = emptyList<Int>()
        var dac = false
        var restart = false
        var scans = 0
        while (at + 4 <= data.size) {
            val m = data[at + 1].toInt() and 0xFF
            if (m == 0xD9) break
            val length = ((data[at + 2].toInt() and 0xFF) shl 8) or (data[at + 3].toInt() and 0xFF)
            when {
                m == 0xCC -> dac = true
                m == 0xDD -> restart = true
                m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 -> {
                    marker = m
                    precision = data[at + 4].toInt() and 0xFF
                    val n = data[at + 9].toInt() and 0xFF
                    sampling = List(n) { data[at + 11 + 3 * it].toInt() and 0xFF }
                }
            }
            if (m == 0xDA) {
                scans++
                // Skip the entropy-coded data to the next marker that is not a restart.
                at += 2 + length
                while (at + 1 < data.size) {
                    if ((data[at].toInt() and 0xFF) == 0xFF) {
                        val next = data[at + 1].toInt() and 0xFF
                        if (next != 0 && next !in 0xD0..0xD7) break
                    }
                    at++
                }
                continue
            }
            at += 2 + length
        }
        return Frame(marker, precision, sampling, dac, restart, scans)
    }

    private val entropyModes = listOf(
        JpegEncodeOptions(optimizeHuffman = true),
        JpegEncodeOptions(progressive = true),
        JpegEncodeOptions(arithmetic = true),
        JpegEncodeOptions(arithmetic = true, progressive = true),
        JpegEncodeOptions(restartInterval = 3, optimizeHuffman = true),
        JpegEncodeOptions(restartInterval = 1, progressive = true),
        JpegEncodeOptions(restartInterval = 2, arithmetic = true, arithmeticDcLower = 2, arithmeticDcUpper = 5, arithmeticAcThreshold = 9),
        JpegEncodeOptions(restartInterval = 5, arithmetic = true, progressive = true, arithmeticDcLower = 0, arithmeticDcUpper = 0, arithmeticAcThreshold = 63),
    )

    /** [base] with the entropy coding of [mode]. */
    private fun with(base: JpegEncodeOptions, mode: JpegEncodeOptions) = JpegEncodeOptions(
        quality = base.quality, subsampling = base.subsampling, grayscale = base.grayscale, encoder = base.encoder, precision = base.precision,
        progressive = mode.progressive, optimizeHuffman = mode.optimizeHuffman, arithmetic = mode.arithmetic,
        arithmeticDcLower = mode.arithmeticDcLower, arithmeticDcUpper = mode.arithmeticDcUpper,
        arithmeticAcThreshold = mode.arithmeticAcThreshold, restartInterval = mode.restartInterval,
    )

    @Test
    fun theDefaultOptionsWriteWhatTheQualityCallWrites() {
        for ((i, q) in listOf(30, 75, 90, 95).withIndex()) {
            val bitmap = photo(37, 23, i)
            assertContentEquals(ImageKodec.encodeJpeg(bitmap, q), ImageKodec.encodeJpeg(bitmap, JpegEncodeOptions(quality = q)), "quality $q")
            assertContentEquals(
                ImageKodec.encodeJpeg(bitmap, q, JpegEncoderKind.Jpegli),
                ImageKodec.encodeJpeg(bitmap, JpegEncodeOptions(quality = q, encoder = JpegEncoderKind.Jpegli)), "Jpegli quality $q",
            )
        }
        val gray = photo(30, 20, 9, gray = true)
        assertContentEquals(ImageKodec.encodeJpeg(gray, 80), ImageKodec.encodeJpeg(gray, JpegEncodeOptions(quality = 80, grayscale = JpegGrayscale.Never)))
        assertContentEquals(
            ImageKodec.encodeJpeg(gray, JpegEncodeOptions(quality = 1)), ImageKodec.encodeJpeg(gray, JpegEncodeOptions(quality = 0)),
            "quality 0 writes what 1 writes",
        )
    }

    @Test
    fun everyEntropyCodingDecodesToThePixelsOfTheSequentialFile() {
        val sizes = listOf(45 to 31, 1 to 1, 7 to 9, 17 to 1, 1 to 13, 33 to 17)
        val bases = listOf(
            JpegEncodeOptions(quality = 75, subsampling = JpegSubsampling.S420),
            JpegEncodeOptions(quality = 85, subsampling = JpegSubsampling.S422),
            JpegEncodeOptions(quality = 60, subsampling = JpegSubsampling.S444),
            JpegEncodeOptions(quality = 92, grayscale = JpegGrayscale.Always),
            JpegEncodeOptions(quality = 70, encoder = JpegEncoderKind.Jpegli),
            JpegEncodeOptions(quality = 88, encoder = JpegEncoderKind.Jpegli, grayscale = JpegGrayscale.Always),
        )
        for ((w, h) in sizes) for ((b, base) in bases.withIndex()) {
            val bitmap = photo(w, h, w * 31 + h + b)
            val sequential = ImageKodec.encodeJpeg(bitmap, base)
            val pixels = ImageKodec.decode(sequential).argb
            for (mode in entropyModes) {
                val options = with(base, mode)
                val file = ImageKodec.encodeJpeg(bitmap, options)
                assertContentEquals(pixels, ImageKodec.decode(file).argb, "${w}x$h $options")
                val f = frame(file)
                val want = when {
                    mode.arithmetic && mode.progressive -> 0xCA
                    mode.arithmetic -> 0xC9
                    mode.progressive -> 0xC2
                    else -> 0xC0
                }
                assertEquals(want, f.marker, "${w}x$h $options")
                assertEquals(mode.arithmetic, f.dac, "DAC of $options")
                assertEquals(mode.restartInterval > 0, f.restart, "DRI of $options")
                val components = if (base.grayscale == JpegGrayscale.Always) 1 else 3
                assertEquals(if (mode.progressive) (if (components == 1) 6 else 10) else 1, f.scans, "scans of $options")
            }
        }
    }

    @Test
    fun theFrameHeaderNamesTheSamplingAskedFor() {
        val bitmap = photo(40, 24, 3)
        fun sampling(options: JpegEncodeOptions) = frame(ImageKodec.encodeJpeg(bitmap, options)).sampling
        assertEquals(listOf(0x22, 0x11, 0x11), sampling(JpegEncodeOptions(subsampling = JpegSubsampling.S420)))
        assertEquals(listOf(0x21, 0x11, 0x11), sampling(JpegEncodeOptions(subsampling = JpegSubsampling.S422)))
        assertEquals(listOf(0x11, 0x11, 0x11), sampling(JpegEncodeOptions(subsampling = JpegSubsampling.S444)))
        assertEquals(listOf(0x11, 0x11, 0x11), sampling(JpegEncodeOptions(quality = 95)))
        assertEquals(listOf(0x22, 0x11, 0x11), sampling(JpegEncodeOptions(quality = 90)))
        assertEquals(listOf(0x11), sampling(JpegEncodeOptions(grayscale = JpegGrayscale.Always)))
        assertEquals(listOf(0x22, 0x11, 0x11), sampling(JpegEncodeOptions(encoder = JpegEncoderKind.Jpegli, subsampling = JpegSubsampling.S420)))
    }

    @Test
    fun aGrayBitmapIsOneComponentAndSmaller() {
        for ((w, h) in listOf(64 to 48, 9 to 7)) {
            val gray = photo(w, h, w, gray = true)
            for (encoder in JpegEncoderKind.entries) {
                val one = ImageKodec.encodeJpeg(gray, JpegEncodeOptions(quality = 85, encoder = encoder))
                val three = ImageKodec.encodeJpeg(gray, JpegEncodeOptions(quality = 85, encoder = encoder, grayscale = JpegGrayscale.Never))
                assertEquals(1, frame(one).sampling.size, "$encoder")
                assertEquals(3, frame(three).sampling.size, "$encoder")
                assertTrue(one.size < three.size, "$encoder ${w}x$h: gray ${one.size} is not smaller than ${three.size}")
            }
        }
        // A colour bitmap made gray decodes to gray pixels near its luma.
        val colour = photo(32, 16, 4)
        val out = ImageKodec.decode(ImageKodec.encodeJpeg(colour, JpegEncodeOptions(quality = 100, grayscale = JpegGrayscale.Always)))
        for ((i, p) in out.argb.withIndex()) {
            val v = p and 0xFF
            assertTrue(((p ushr 16) and 0xFF) == v && ((p ushr 8) and 0xFF) == v, "pixel $i is not gray")
            val s = colour.argb[i]
            val luma = (299 * ((s ushr 16) and 0xFF) + 587 * ((s ushr 8) and 0xFF) + 114 * (s and 0xFF)) / 1000
            assertTrue(kotlin.math.abs(luma - v) <= 6, "pixel $i: $v, luma $luma")
        }
    }

    @Test
    fun aLosslessFileGivesBackItsSamples() {
        for (bits in 2..16) {
            for (channels in listOf(1, 3)) {
                val (w, h) = if (bits % 3 == 0) 1 to 5 else 13 to 7
                val source = deep(w, h, channels, bits, bits * 10 + channels)
                for (predictor in 1..7) {
                    val arithmetic = (predictor + bits) % 2 == 0
                    val pt = if (predictor == 3 && bits > 2) 1 else if (predictor == 6 && bits > 4) 3 else 0
                    val restart = if (predictor == 5) w * 2 else 0
                    val options = JpegEncodeOptions(lossless = true, precision = bits, predictor = predictor, pointTransform = pt, arithmetic = arithmetic, restartInterval = restart)
                    val file = ImageKodec.encodeJpeg16(source, options)
                    val f = frame(file)
                    assertEquals(if (arithmetic) 0xCB else 0xC3, f.marker, "$options")
                    assertEquals(bits, f.precision)
                    assertEquals(channels, f.sampling.size)
                    val back = ImageKodec.decode16(file)
                    val outChannels = if (bits > 8) channels else 3
                    assertEquals(outChannels, back.channels, "$options")
                    for (i in 0 until w * h) for (c in 0 until outChannels) {
                        val v = (source.samples[i * channels + minOf(c, channels - 1)].toInt() and 0xFFFF) ushr (16 - bits)
                        val want = widened((v shr pt) shl pt, bits)
                        assertEquals(want, back.samples[i * outChannels + c].toInt() and 0xFFFF, "$options: pixel $i channel $c")
                    }
                }
            }
        }
    }

    @Test
    fun sixteenBitLosslessKeepsEveryValue() {
        val random = Random(7)
        val w = 37
        val h = 11
        // Jumps of half the range and more, so that the difference -32768 of category 16 turns up.
        val samples = ShortArray(w * h * 3) { (if (it % 5 == 0) random.nextInt(2) * 65535 else random.nextInt(65536)).toShort() }
        val source = KiteBitmap16(w, h, 3, samples)
        for (arithmetic in listOf(false, true)) for (predictor in listOf(1, 4, 7)) {
            val file = ImageKodec.encodeJpeg16(source, JpegEncodeOptions(lossless = true, precision = 16, predictor = predictor, arithmetic = arithmetic))
            assertContentEquals(samples, ImageKodec.decode16(file).samples, "predictor $predictor arithmetic $arithmetic")
        }
        val gray = KiteBitmap16(w, h, 1, ShortArray(w * h) { random.nextInt(65536).toShort() })
        val file = ImageKodec.encodeJpeg16(gray, JpegEncodeOptions(lossless = true, precision = 16, restartInterval = w))
        assertContentEquals(gray.samples, ImageKodec.decode16(file).samples)
    }

    @Test
    fun anEightBitLosslessFileDecodesToTheBitmap() {
        for ((w, h) in listOf(1 to 1, 19 to 6, 1 to 9, 300 to 2)) {
            val bitmap = photo(w, h, w + h)
            val gray = photo(w, h, w + h, gray = true)
            for (arithmetic in listOf(false, true)) {
                val options = JpegEncodeOptions(lossless = true, predictor = 7, arithmetic = arithmetic)
                assertContentEquals(bitmap.argb, ImageKodec.decode(ImageKodec.encodeJpeg(bitmap, options)).argb, "${w}x$h $options")
                val one = ImageKodec.encodeJpeg(gray, options)
                assertEquals(1, frame(one).sampling.size)
                assertContentEquals(gray.argb, ImageKodec.decode(one).argb, "gray ${w}x$h $options")
            }
        }
    }

    @Test
    fun twelveBitFilesAgreeAcrossTheirProcessesAndStayNearTheSource() {
        for ((channels, base) in listOf(
            1 to JpegEncodeOptions(quality = 90, precision = 12),
            3 to JpegEncodeOptions(quality = 95, precision = 12, subsampling = JpegSubsampling.S444),
            3 to JpegEncodeOptions(quality = 80, precision = 12, subsampling = JpegSubsampling.S420),
        )) {
            for ((w, h) in listOf(29 to 21, 1 to 1, 8 to 3)) {
                val source = deep(w, h, channels, 12, w + channels)
                val first = ImageKodec.encodeJpeg16(source, base)
                assertEquals(0xC1, frame(first).marker)
                assertEquals(12, frame(first).precision)
                val samples = ImageKodec.decode16(first)
                for (mode in entropyModes.drop(1)) {
                    val options = with(base, mode)
                    assertContentEquals(samples.samples, ImageKodec.decode16(ImageKodec.encodeJpeg16(source, options)).samples, "${w}x$h $options")
                }
                if (w > 8 && channels == 1) {
                    // Gray at quality 90 and 12 bits: the error stays small against the 16-bit range.
                    var worst = 0
                    for (i in 0 until w * h) worst = maxOf(worst, kotlin.math.abs((source.samples[i].toInt() and 0xFFFF) - (samples.samples[i].toInt() and 0xFFFF)))
                    assertTrue(worst < 65536 / 40, "worst error $worst")
                }
            }
        }
    }

    @Test
    fun metadataComesAlongWithOptions() {
        val exif = byteArrayOf(0x4D, 0x4D, 0, 0x2A, 0, 0, 0, 8, 0, 0)
        val gainMapXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
            """<rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0" hdrgm:GainMapMax="2.3" """ +
            """hdrgm:HDRCapacityMax="2.3"/></rdf:RDF></x:xmpmeta>"""
        val iso = "urn:iso:std:iso:ts:21496:-1\u0000".encodeToByteArray() + byteArrayOf(0, 0, 0, 0)
        val gainMap = ImageKodec.encodeJpeg(photo(16, 12, 1, gray = true), 95, ImageMetadata(xmp = gainMapXmp))
        val metadata = ImageMetadata(exif = exif, gainMap = GainMap(gainMap, iso))
        for (options in listOf(
            JpegEncodeOptions(progressive = true),
            JpegEncodeOptions(arithmetic = true, encoder = JpegEncoderKind.Jpegli),
            JpegEncodeOptions(lossless = true),
        )) {
            val file = ImageKodec.encodeJpeg(photo(64, 48, 2), options, metadata)
            val read = ImageKodec.readMetadata(file)
            assertContentEquals(exif, read.exif, "$options")
            assertNotNull(read.gainMap, "$options")
            assertContentEquals(gainMap, read.gainMap!!.jpeg, "$options")
            assertEquals(64, ImageKodec.decode(file).width)
        }
        val deepFile = ImageKodec.encodeJpeg16(deep(20, 10, 3, 16, 1), JpegEncodeOptions(lossless = true, precision = 16), ImageMetadata(exif = exif))
        assertContentEquals(exif, ImageKodec.readMetadata(deepFile).exif)
    }

    @Test
    fun aCancellationCheckStopsTheEncode() {
        class Stop : RuntimeException()
        for (options in listOf(JpegEncodeOptions(), JpegEncodeOptions(progressive = true), JpegEncodeOptions(lossless = true, arithmetic = true))) {
            var calls = 0
            assertFailsWith<Stop>("$options") {
                ImageKodec.encodeJpeg(photo(64, 64, 1), options) { if (++calls > 3) throw Stop() }
            }
            var all = 0
            ImageKodec.encodeJpeg(photo(64, 64, 1), options) { all++ }
            assertTrue(all > 4, "$options checked $all times")
        }
    }

    @Test
    fun optionsThatCannotGoTogetherAreRefused() {
        val refused = listOf(
            { JpegEncodeOptions(quality = 101) },
            { JpegEncodeOptions(quality = -1) },
            { JpegEncodeOptions(restartInterval = 65536) },
            { JpegEncodeOptions(arithmetic = true, optimizeHuffman = true) },
            { JpegEncodeOptions(arithmeticDcLower = 2) },
            { JpegEncodeOptions(arithmetic = true, arithmeticDcLower = 4, arithmeticDcUpper = 3) },
            { JpegEncodeOptions(arithmetic = true, arithmeticAcThreshold = 0) },
            { JpegEncodeOptions(precision = 10) },
            { JpegEncodeOptions(lossless = true, precision = 1) },
            { JpegEncodeOptions(lossless = true, precision = 17) },
            { JpegEncodeOptions(lossless = true, progressive = true) },
            { JpegEncodeOptions(lossless = true, subsampling = JpegSubsampling.S420) },
            { JpegEncodeOptions(lossless = true, predictor = 0) },
            { JpegEncodeOptions(lossless = true, predictor = 8) },
            { JpegEncodeOptions(lossless = true, precision = 8, pointTransform = 8) },
            { JpegEncodeOptions(predictor = 2) },
            { JpegEncodeOptions(pointTransform = 1) },
            { JpegEncodeOptions(encoder = JpegEncoderKind.Jpegli, precision = 12) },
            { JpegEncodeOptions(encoder = JpegEncoderKind.Jpegli, lossless = true) },
            { JpegEncodeOptions(encoder = JpegEncoderKind.Jpegli, subsampling = JpegSubsampling.S444) },
        )
        for ((i, make) in refused.withIndex()) assertFailsWith<IllegalArgumentException>("case $i") { make() }
        val bitmap = photo(10, 4, 1)
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeJpeg(bitmap, JpegEncodeOptions(precision = 12)) }
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeJpeg(bitmap, JpegEncodeOptions(lossless = true, restartInterval = 15)) }
        ImageKodec.encodeJpeg(bitmap, JpegEncodeOptions(lossless = true, restartInterval = 20))
    }

    @Test
    fun theFilesOfEachProcessKeepTheirBytes() {
        val bitmap = photo(41, 23, 11)
        val files = listOf(
            "progressive 4:2:0" to ImageKodec.encodeJpeg(bitmap, JpegEncodeOptions(quality = 80, progressive = true)),
            "optimized 4:2:2 restart" to ImageKodec.encodeJpeg(bitmap, JpegEncodeOptions(quality = 85, subsampling = JpegSubsampling.S422, optimizeHuffman = true, restartInterval = 4)),
            "arithmetic 4:4:4" to ImageKodec.encodeJpeg(bitmap, JpegEncodeOptions(quality = 70, subsampling = JpegSubsampling.S444, arithmetic = true)),
            "arithmetic progressive DAC" to ImageKodec.encodeJpeg(bitmap, JpegEncodeOptions(quality = 90, progressive = true, arithmetic = true, arithmeticDcLower = 1, arithmeticDcUpper = 4, arithmeticAcThreshold = 12)),
            "gray progressive" to ImageKodec.encodeJpeg(bitmap, JpegEncodeOptions(quality = 75, progressive = true, grayscale = JpegGrayscale.Always)),
            "jpegli progressive" to ImageKodec.encodeJpeg(bitmap, JpegEncodeOptions(quality = 80, progressive = true, encoder = JpegEncoderKind.Jpegli)),
            "12-bit progressive" to ImageKodec.encodeJpeg16(deep(25, 19, 3, 12, 1), JpegEncodeOptions(quality = 85, precision = 12, progressive = true)),
            "12-bit arithmetic gray" to ImageKodec.encodeJpeg16(deep(25, 19, 1, 12, 2), JpegEncodeOptions(quality = 90, precision = 12, arithmetic = true)),
            "lossless 16 predictor 4" to ImageKodec.encodeJpeg16(deep(25, 19, 3, 16, 3), JpegEncodeOptions(lossless = true, precision = 16, predictor = 4)),
            "lossless 12 arithmetic restart" to ImageKodec.encodeJpeg16(deep(25, 19, 1, 12, 4), JpegEncodeOptions(lossless = true, precision = 12, predictor = 7, arithmetic = true, restartInterval = 50, pointTransform = 2)),
        )
        val want = mapOf(
            "progressive 4:2:0" to 0x18c83aefdbece03cuL.toLong(),
            "optimized 4:2:2 restart" to 0x1a87816429f53882uL.toLong(),
            "arithmetic 4:4:4" to 0x392ff8bad9015b15uL.toLong(),
            "arithmetic progressive DAC" to 0x5c17a468941c5657uL.toLong(),
            "gray progressive" to 0x6ba8e9dfff0f2754uL.toLong(),
            "jpegli progressive" to 0x2f5a494e9354be0auL.toLong(),
            "12-bit progressive" to 0xffc5f18f1237b692uL.toLong(),
            "12-bit arithmetic gray" to 0x36e0831d0ac8a40buL.toLong(),
            "lossless 16 predictor 4" to 0xdbb54cb00f987f1auL.toLong(),
            "lossless 12 arithmetic restart" to 0x504bf8a11722dfb1uL.toLong(),
        )
        for ((name, bytes) in files) assertEquals(want[name], fnv(bytes), name)
    }
}
