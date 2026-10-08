package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.Jbig2Decoder
import io.github.yuroyami.imagekodec.codec.JpxDecoder
import io.github.yuroyami.imagekodec.internal.flate.Zlib
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every decoder here parses bytes it did not write, so the contract that matters
 * most is the one about *failure*: malformed input must surface as an
 * [ImageDecodeException] naming the problem, never as an index-out-of-bounds, a
 * negative-array-size, an arithmetic fault, or a hang.
 *
 * This harness takes a valid file of each format and corrupts it thousands of
 * ways: single-bit flips, whole-byte replacements, truncation at every scale,
 * length-field tampering, and splices of one format into another. The generator
 * is a seeded PRNG with no platform dependency, so a failure here reproduces
 * byte-for-byte on every target and in CI, which is what makes it a regression
 * test rather than a lottery.
 *
 * A decode that *succeeds* on corrupt input is fine: plenty of mutations land in
 * pixel data and simply produce a different picture. Only the escape of a wrong
 * exception type is a failure.
 */
class FuzzTest {

    private companion object {
        /** Far past what any mutant needs, so only a loop or a runaway allocation reaches it. */
        const val DEADLINE_MILLIS = 30_000L
    }

    /** xorshift32: tiny, deterministic, and identical on every Kotlin target. */
    private class Rng(private var state: Int) {
        fun next(): Int {
            var x = state
            x = x xor (x shl 13)
            x = x xor (x ushr 17)
            x = x xor (x shl 5)
            state = x
            return x
        }

        fun nextInt(bound: Int): Int = ((next().toLong() and 0xFFFFFFFFL) % bound).toInt()
    }

    private fun sample(w: Int, h: Int) = KiteBitmap(w, h, IntArray(w * h) { i ->
        argb(if (i % 5 == 0) 0x80 else 0xFF, (i * 7) and 0xFF, (i * 13) and 0xFF, (i * 29) and 0xFF)
    })

    /** One small, valid file per format the library decodes. */
    private fun validCorpus(): List<Pair<String, ByteArray>> = listOf(
        "png" to ImageKodec.encodePng(sample(9, 7)),
        "png-opaque" to ImageKodec.encodePng(KiteBitmap(8, 8, IntArray(64) { argb(0xFF, it, 255 - it, 128) })),
        "png-flushed" to (PNG_SIGNATURE + pngHeader(16, 2, 8, 0) +
            pngChunk("IDAT", hex(FLUSHED_ZLIB)) + pngChunk("IEND", ByteArray(0))),
        "jpeg" to ImageKodec.encodeJpeg(sample(16, 16), quality = 70),
        "jpeg-exif" to jpegWithTiffHeader(
            ImageKodec.encodeJpeg(sample(16, 16), quality = 70),
            tiffHeaderWithOffset(8, true) + hex("010012010300010000000600000000000000"),
        ),
        "gif" to ImageKodec.encodeGif(sample(12, 9)),
        "bmp24" to ImageKodec.encodeBmp(KiteBitmap(7, 5, IntArray(35) { argb(0xFF, it * 3, it * 5, it * 7) })),
        "bmp32" to ImageKodec.encodeBmp(sample(6, 4)),
        "bmp-png" to embeddedBmp(5, ImageKodec.encodePng(sample(9, 7)), width = 3, height = 2),
        "bmp-jpeg" to embeddedBmp(4, ImageKodec.encodeJpeg(sample(16, 16), quality = 70)),
        "gif-animated" to ImageKodec.encodeGif(
            KiteAnimation(
                4, 4,
                List(3) { i -> KiteFrame(sample(4, 4), delayMillis = 40 + i * 10, delayRawCentiseconds = 4) },
                loopCount = 0,
            ),
        ),
        "webp-lossless" to hex(WEBP_LOSSLESS),
        "webp-lossy" to hex(WEBP_LOSSY),
        "webp-lossy-alpha" to hex(WEBP_LOSSY_ALPHA),
        "webp-lossy-libvpx" to hex(WEBP_LOSSY_LIBVPX),
        "webp-lossy-animation" to hex(WEBP_LOSSY_ANIMATION),
        "webp-singleton" to hex(WEBP_UNIFORM_HISTOGRAM),
        "webp-constant-extended" to constantExtendedWebp(8, 8, listOf(-1), false),
        "png-bilevel" to packedBlankPng(16, 8, 1),
        "tiff-bilevel-deflate" to faxTiff(Zlib.compress(ByteArray(8)), 8, width = 8, height = 8),
        "webp-normal-prefix" to prefixWebp(intArrayOf(1, 1)),
        "webp-animation" to hex(WEBP_ANIMATION),
        "webp-partial-animation" to Base64.decode(PARTIAL_WEBP_ANIMATION),
        "tiff" to hex(TIFF_RGB),
        "tiff-packbits" to tiffBlock(32773, byteArrayOf(31) + ByteArray(32) { it.toByte() }),
        "tiff-ycbcr" to hex(buildTiff(ycbcr = true)),
        "tiff-ycbcr-tiled" to TiffExtendedTest().tiledYcbcr(2, 2),
        "tiff-predictor-tiled" to predictorTiff(),
        "jp2" to hex(JP2),
        "jp2-tile-cod" to Jp2HeaderProbeTest().tileOverrideSeed(),
        // Seeds for the paths a mutation cannot reach from the ones above: each needs several chunks or
        // fields to agree, so a random edit of a plain file stops at the first check.
        "apng" to apngSeed(),
        "png-palette" to paletteSeed(),
        "gif-subrect" to hex(GIF_SUBRECT),
        "tiff-deflate" to hex(buildTiff(compression = 8)),
        "tiff-tiled" to hex(buildTiff(tiled = true)),
        "tiff-pages" to TiffPagesTest().let { it.tiff(it.pages) },
        "tiff-jpeg" to TiffJpegTest().seed(),
        "tiff-old-jpeg" to TiffOldJpegTest().interchangeFormat(),
        *ColorProfileTest().seeds().toTypedArray(),
        "tiff-cmyk-icc" to CmykTiffs.tiff(3, 2, IntArray(24) { it * 10 }, icc = ColorConversionTest().cmykV2),
        "jpeg-ycck-icc" to ColorProfileTest().withIcc(JpegCmykFixtures.blocksAdobeYcck, ColorConversionTest().cmykV4),
        "tiff-pages-be" to TiffPagesTest().let {
            it.tiff(listOf(TiffPagesTest.Page(3, 2, 40), TiffPagesTest.Page(2, 5, 90, orientation = 6)), littleEndian = false)
        },
        "jpeg-progressive" to hex(JPEG_PROGRESSIVE),
        "jpeg-restart" to restartIntervalJpeg(),
        "tiff-ccitt-g4" to faxTiff(faxBits("001000111010".repeat(4) + "11111111"), 4),
        "tiff-ccitt-g4-vertical" to faxTiff(faxBits("0011011011" + "0000010" + "1"), 4),
        "tiff-ccitt-g3" to faxTiff(group3EolStream(true), 3, width = 7, t4Options = 4),
        "tiff-sample-format" to TiffExtendedTest().sampleFormatTiff(intArrayOf(1, 1, 1), samples = 3),
        "tiff-fill-order" to withFillOrder(packedFillTiff(4, 8), 2),
        "tiff-associated-rgb" to alphaTiff(bits = 16),
        "tiff-associated-gray" to alphaTiff(photo = 0),
        "tiff-associated-palette" to alphaTiff(photo = 3),
        "tiff-reference-ycbcr" to withReferences(referenceTiff(),
            longArrayOf(1,2,511,2,257,2,511,2,257,2,511,2)),
        "tiff-reference-rgb" to withReferences(referenceTiff(ycbcr = false),
            longArrayOf(255,1,0,1,0,1,255,1,0,1,255,1)),
        "tiff-ccitt-mixed" to faxTiff(CcittMixedTest().stream(fill = 3), 3, height = 6, t4Options = 5),
        "jp2-small-irreversible" to Jp2SingletonTest().encoded("corner"),
        "jp2-partial-reversible" to Jp2ReconstructionTest().encoded(),
        "jp2-derived-quantization" to Jp2DerivedQuantTest().encoded(),
        "jp2-signed-gray" to Jp2SignedTest().gray(8),
        "jp2-signed-rgb" to Jp2SignedTest().rgb(mct = true, signedMask = 5),
        "jp2-signed-opacity" to Jp2SignedTest().grayAlpha(),
        "jp2-signed-irreversible" to Jp2SignedTest().irreversible("rgb-ict", 5),
        // Files other encoders wrote, for the features #102 found without a seed: ffmpeg's LZW TIFF with
        // a predictor, libtiff's tiled LZW with a 16-bit predictor, cjpeg's 4:2:2, libjpeg's YCCK,
        // OpenJPEG's multi-precinct RPCL and CPRL, and JP2 palette, CMYK and premultiplied opacity.
        "tiff-lzw-predictor" to hex(TIFF_LZW_PREDICTOR),
        "tiff-libtiff-tiled-lzw16" to hex(TIFF_LIBTIFF_TILED_LZW16),
        "jpeg-422" to JpegSamplingFixtures.all.first { it.first == "2x1" }.second,
        "jpeg-ycck" to JpegCmykFixtures.blocksAdobeYcck,
        "jpeg-arithmetic" to JpegArithmeticTest().sequential,
        "jpeg-arithmetic-progressive" to JpegArithmeticTest().progressive,
        "jpeg-lossless" to losslessJpeg(IntArray(48) { it * 5 }, 4, 4, 3, 8, predictor = 7, restartInterval = 8),
        "jpeg-lossless-arithmetic" to JpegLosslessTest().gray12,
        "jpeg-hierarchical-lossless" to hierarchicalLosslessJpeg(IntArray(7 * 5 * 3) { it * 11 % 256 }, 7, 5, 3, 8, levels = 2, refinements = 1),
        "jpeg-hierarchical-dct" to JpegHierarchicalTest().dctRgbProgressive,
        "jpeg-12bit" to JpegTwelveBitTest().progressiveArithmetic,
        "jp2-rpcl" to Jp2ProgressionTest().rpcl,
        "jp2-cprl" to Jp2ProgressionTest().cprl,
        "jp2-palette" to Jp2ColorFixtures.palette,
        "jp2-cmyk" to Jp2ColorFixtures.cmyk,
        "jp2-premultiplied" to Jp2ColorFixtures.premultiplied,
        // Every Part 1 code-block style, an ROI shift, POC with PPT, and PPM over interleaved tile-parts (#5).
        "jp2-styles" to Jp2FeatureFixtures.styles,
        "jp2-bypass-vsc" to Jp2FeatureFixtures.bypassVsc,
        "jp2-roi" to Jp2FeatureFixtures.roi,
        "jp2-poc-ppt" to Jp2FeatureFixtures.pocPpt,
        "jp2-ppm" to Jp2FeatureFixtures.ppm,
        // AVIF from libavif: alpha, premultiplied 10-bit, a grid, a crop with orientation, 12-bit gray and a sequence (#41).
        *AvifFixtures.all.map { (name, data) -> "avif-$name" to data }.toTypedArray(),
    )

    private fun malformedCorpus(): List<Pair<String, ByteArray>> = listOf(
        "jp2-short-cod" to jp2ShortCodSeed(),
        "jp2-oversize-siz" to jp2OversizeSeed(),
        // A BMP that declares BI_PNG and holds another BMP must be refused, not read recursively (#17).
        "bmp-nested" to embeddedBmp(5, ImageKodec.encodeBmp(sample(6, 4))),
    )

    private fun corpus(): List<Pair<String, ByteArray>> = validCorpus() + malformedCorpus()

    /** Two frames: the default image doubles as frame 0, then a 2 by 2 frame that disposes to background. */
    private fun apngSeed(): ByteArray {
        fun rgba(w: Int, h: Int, tint: Int): ByteArray {
            val raw = ByteArray(h * (1 + w * 4))
            var o = 0
            for (y in 0 until h) {
                raw[o++] = 0
                for (x in 0 until w) {
                    raw[o++] = (x * 40 + tint).toByte(); raw[o++] = (y * 60).toByte(); raw[o++] = tint.toByte(); raw[o++] = 0xFF.toByte()
                }
            }
            return Zlib.compress(raw)
        }
        fun fctl(seq: Int, w: Int, h: Int, x: Int, y: Int, dispose: Int, blend: Int) =
            pngChunk(
                "fcTL",
                beBytes(seq) + beBytes(w) + beBytes(h) + beBytes(x) + beBytes(y) +
                    byteArrayOf(0, 10, 0, 100, dispose.toByte(), blend.toByte()),
            )
        return PNG_SIGNATURE + pngHeader(4, 4, bitDepth = 8, colorType = 6) +
            pngChunk("acTL", beBytes(2) + beBytes(0)) +
            fctl(0, 4, 4, 0, 0, dispose = 0, blend = 0) + pngChunk("IDAT", rgba(4, 4, 50)) +
            fctl(1, 2, 2, 1, 1, dispose = 1, blend = 1) + pngChunk("fdAT", beBytes(2) + rgba(2, 2, 130)) +
            pngChunk("IEND", ByteArray(0))
    }

    /** A 4 by 2 palette image whose two first entries are partly transparent. */
    private fun paletteSeed(): ByteArray =
        PNG_SIGNATURE + pngHeader(4, 2, bitDepth = 8, colorType = 3) +
            pngChunk("PLTE", byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1, -1, -1, 0)) +
            pngChunk("tRNS", byteArrayOf(0, 128.toByte())) +
            pngChunk("IDAT", Zlib.compress(byteArrayOf(0, 0, 1, 2, 3, 0, 3, 2, 1, 0))) +
            pngChunk("IEND", ByteArray(0))

    /** From GifDecoderTest: a 2 by 2 screen, with frames that are 2 by 2 and 1 by 1 sub-rectangles and disposal 3. */
    private val GIF_SUBRECT = "47494638396102000200910000ff000000ff000000ffffff0021f904040a0000002c0000000002000200000204044110050021f9040c0a0000002c000000000100010000020254010021f904040a0000002c01000100010001000002024c01003b"

    /** From JpegProgressiveTest: a 9 by 7 progressive JPEG with spectral selection and refinement scans. */
    private val JPEG_PROGRESSIVE = "ffd8ffe000104a46494600010200000100010000ffdb004300100b0c0e0c0a100e0d0e1211101318281a181616183123251d283a333d3c3933383740485c4e404457453738506d51575f626768673e4d71797064785c656763ffdb0043011112121815182f1a1a2f634238426363636363636363636363636363636363636363636363636363636363636363636363636363636363636363636363636363ffc20011080007000903012200021101031101ffc400160001010100000000000000000000000000000205ffc4001501010100000000000000000000000000000104ffda000c03010002100310000001d6b223ffc400161001010100000000000000000000000000000111ffda000801010001050295afffc40017110003010000000000000000000000000000010321ffda0008010301013f019ac3ffc4001811000203000000000000000000000000000001020311ffda0008010201013f01b64f4fffc40014100100000000000000000000000000000010ffda0008010100063f023fffc400161001010100000000000000000000000000000111ffda0008010100013f21830fffda000c030100020003000000108fffc4001511010100000000000000000000000000000001ffda0008010301013f1093ffc4001511010100000000000000000000000000000041ffda0008010201013f10a2ffc40017100100030000000000000000000000000000113161ffda0008010100013f108b4c5fffd9"

    private val WEBP_LOSSLESS = "524946462e000000574542505650384c220000002f0fc00200b93244f43f7651ffe87f8048dba622eedfeed8f13c4c404c005c07eb3f"
    private val WEBP_ANIMATION = "52494646f200000057454250565038580a00000002000000070000070000414e494d06000000ffffffff0000414e4d4638000000000000000000070000070000640000025650384c200000002f07c00100b93244f43f7611d1ff0061b65149ce1f74af23188f8809c01efa0f414e4d463e000000000000000000070000070000640000005650384c260000002f07c00100b93244f43f7611d1ff0061b65149ce1f74af231008a43892991ead9800971ee83f414e4d4640000000000000000000070000070000640000005650384c270000002f07c00100b93244f43f7611d1ff0061b65149ce1f74af2310082491cc3eead0c604b8f440ff0100"

    /** 4x3 uncompressed RGB TIFF, little-endian, one strip. */
    private val TIFF_RGB = buildTiff()

    /** [compression] is the TIFF code: 1 raw, 8 deflate. [tiled] stores one padded 16 by 16 tile in place of a strip. */
    private fun buildTiff(compression: Int = 1, tiled: Boolean = false, ycbcr: Boolean = false): String {
        val w = 4
        val h = 3
        val raw = ByteArray((if (tiled) 16 * 16 else w * h) * 3) { (it * 11).toByte() }
        val px = if (compression == 8) Zlib.compress(raw) else raw
        val fields = mutableListOf(
            intArrayOf(256, 4, 1, w), intArrayOf(257, 4, 1, h),
            intArrayOf(258, 3, 3, 0), intArrayOf(259, 3, 1, compression),
            intArrayOf(262, 3, 1, if (ycbcr) 6 else 2), intArrayOf(277, 3, 1, 3), intArrayOf(284, 3, 1, 1),
        )
        if (ycbcr) fields += intArrayOf(530, 3, 2, 1 or (1 shl 16))
        if (tiled) {
            fields += listOf(intArrayOf(322, 4, 1, 16), intArrayOf(323, 4, 1, 16), intArrayOf(324, 4, 1, 0), intArrayOf(325, 4, 1, px.size))
        } else {
            fields += listOf(intArrayOf(273, 4, 1, 0), intArrayOf(278, 4, 1, h), intArrayOf(279, 4, 1, px.size))
        }
        fields.sortBy { it[0] }                            // a TIFF directory lists its tags in ascending order
        val ifdAt = 8
        val bitsAt = ifdAt + 2 + fields.size * 12 + 4
        val pixelsAt = bitsAt + 6
        val out = ByteArray(pixelsAt + px.size)
        var at = 0
        fun u8(v: Int) { out[at++] = (v and 0xFF).toByte() }
        fun u16(v: Int) { u8(v); u8(v ushr 8) }
        fun u32(v: Int) { u16(v); u16(v ushr 16) }
        u8('I'.code); u8('I'.code); u16(42); u32(ifdAt)
        u16(fields.size)
        for (f in fields) {
            u16(f[0]); u16(f[1]); u32(f[2])
            val slot = at
            when {
                f[0] == 258 -> u32(bitsAt)
                f[0] == 273 || f[0] == 324 -> u32(pixelsAt)
                f[1] == 3 -> { u16(f[3]); u16(f[3] ushr 16) }
                else -> u32(f[3])
            }
            at = slot + 4
        }
        u32(0)
        at = bitsAt
        u16(8); u16(8); u16(8)
        at = pixelsAt
        px.copyInto(out, at)
        return out.joinToString("") { b ->
            val v = b.toInt() and 0xFF
            "0123456789abcdef"[v shr 4].toString() + "0123456789abcdef"[v and 15]
        }
    }

    /**
     * Run [body] and fail the test unless it either returns or throws an
     * [ImageDecodeException]. Anything else means a decoder let a raw runtime
     * fault out of the library.
     */
    private fun mustFailCleanly(label: String, body: () -> Unit) {
        try {
            body()
        } catch (_: ImageDecodeException) {
            // The contract: malformed input is a decode error.
        } catch (t: Throwable) {
            fail("$label leaked ${t::class.simpleName}: ${t.message}")
        }
    }

    /**
     * One of three variants for [label], the same on every target: Kotlin's own string hash is
     * not promised to be.
     */
    private fun variantOf(label: String): Int {
        var hash = 0
        for (c in label) hash = hash * 31 + c.code
        return (hash and 0x7FFFFFFF) % 3
    }

    private fun exercise(label: String, bytes: ByteArray) = withDeadline(label, DEADLINE_MILLIS) {
        mustFailCleanly("$label decode") { ImageKodec.decode(bytes, applyOrientation = true) }
        // The reduced paths allocate and index their own planes, so they get the same abuse. Each
        // mutant takes one reduction, and every third one the sized decode, so every path sees
        // thousands of mutants while the suite costs about what it did.
        val variant = variantOf(label)
        val reduction = 2 shl variant
        if (ImageFormat.sniff(bytes) == ImageFormat.JP2) {
            // The facade reports any exception from the JPEG 2000 decoder as a decode error, so only
            // the decoder's own entry point shows a fault as a fault (#102).
            mustFailCleanly("$label JpxDecoder 1/$reduction") { JpxDecoder.decodeChecked(bytes, reduction) }
        } else {
            mustFailCleanly("$label decodeReduced 1/$reduction") { ImageKodec.decodeReduced(bytes, reduction) }
        }
        if (variant == 0) mustFailCleanly("$label decodeScaled") { ImageKodec.decodeScaled(bytes, 7, 5, applyOrientation = true) }
        mustFailCleanly("$label decodeAnimation") { ImageKodec.decodeAnimation(bytes, applyOrientation = true) }
        mustFailCleanly("$label probe") { ImageKodec.probe(bytes) }
        // The 16-bit paths read their own samples; the others widen what decode gives.
        val format = ImageFormat.sniff(bytes)
        if (format == ImageFormat.PNG || format == ImageFormat.TIFF || format == ImageFormat.AVIF) {
            mustFailCleanly("$label decode16") { ImageKodec.decode16(bytes, applyOrientation = true) }
        }
        // A file with a profile converts through it when asked, so the profile's tables take the damage too.
        if (ImageKodec.probeOrNull(bytes)?.colorProfile?.icc != null) {
            mustFailCleanly("$label decode to sRGB") { ImageKodec.decode(bytes, colorTarget = ColorTarget.Srgb()) }
            if (format == ImageFormat.PNG || format == ImageFormat.TIFF || format == ImageFormat.JPEG) {
                mustFailCleanly("$label decode16 to sRGB") { ImageKodec.decode16(bytes, colorTarget = ColorTarget.Srgb()) }
            }
        }
        // A damaged chain of TIFF pages: the last page the probe counts must be reachable.
        val pages = ImageKodec.probeOrNull(bytes)?.pageCount ?: 1
        if (pages > 1) {
            mustFailCleanly("$label decodePage") { ImageKodec.decodePage(bytes, pages - 1, applyOrientation = true) }
            mustFailCleanly("$label probePage") { ImageKodec.probePage(bytes, pages - 1) }
        }
        // probeOrNull promises never to throw on unreadable input at all.
        try {
            ImageKodec.probeOrNull(bytes)
        } catch (t: Throwable) {
            fail("$label probeOrNull leaked ${t::class.simpleName}: ${t.message}")
        }
    }

    /**
     * JBIG2 never comes through the facade, and its public entry point returns null for any
     * exception, so its checked entry point takes the same kinds of damage here (#102).
     */
    @Test
    fun jbig2MutantsFailOnlyByRefusal() {
        val rng = Rng(0x7B16_2002)
        val streams = listOf(
            Triple("jbig2-generic", hex(GENERIC), null),
            Triple("jbig2-generic-tpgd", hex(GENERIC_TPGD), null),
            Triple("jbig2-text", hex(TEXT_PAGE), hex(SYMBOL_DICT)),
            Triple("jbig2-mmr", hex(JBIG2_MMR), null),
        )
        for ((name, data, globals) in streams) {
            val (width, height) = if (name == "jbig2-mmr") 64 to 24 else Jbig2Page.WIDTH to Jbig2Page.HEIGHT
            fun run(label: String, stream: ByteArray, dictionary: ByteArray?) = withDeadline(label, DEADLINE_MILLIS) {
                mustFailCleanly(label) { Jbig2Decoder.decodeChecked(stream, dictionary, width, height) }
            }
            run("$name whole", data, globals)
            for (cut in 0 until data.size step maxOf(1, data.size / 150)) run("$name cut@$cut", data.copyOf(cut), globals)
            fun damaged(source: ByteArray, i: Int): ByteArray = source.copyOf().also { bytes ->
                when (i % 3) {
                    0 -> bytes[rng.nextInt(bytes.size)] = (rng.next() and 0xFF).toByte()
                    1 -> rng.nextInt(bytes.size).let { at -> bytes[at] = (bytes[at].toInt() xor (1 shl rng.nextInt(8))).toByte() }
                    else -> repeat(1 + rng.nextInt(8)) { bytes[rng.nextInt(bytes.size)] = (rng.next() and 0xFF).toByte() }
                }
            }
            repeat(600) { run("$name mutant $it", damaged(data, it), globals) }
            if (globals != null) repeat(300) { run("$name dictionary mutant $it", data, damaged(globals, it)) }
        }
    }

    @Test
    fun everySeedFileStillDecodes() {
        for ((name, bytes) in validCorpus()) {
            val bm = ImageKodec.decode(bytes)
            assertTrue(bm.width > 0 && bm.height > 0, "$name seed should decode")
        }
    }

    @Test
    fun malformedSeedsHaveTypedFailures() {
        for ((name, bytes) in malformedCorpus()) {
            assertFailsWith<ImageDecodeException>("$name decode") { ImageKodec.decode(bytes) }
            assertFailsWith<ImageDecodeException>("$name decodeReduced") { ImageKodec.decodeReduced(bytes, 8) }
            assertFailsWith<ImageDecodeException>("$name decodeAnimation") { ImageKodec.decodeAnimation(bytes) }
            exercise(name, bytes)
        }
    }

    @Test
    fun singleByteCorruptionNeverLeaksARuntimeFault() {
        val rng = Rng(0x5EED_1234)
        for ((name, seed) in corpus()) {
            repeat(400) {
                val bytes = seed.copyOf()
                val at = rng.nextInt(bytes.size)
                bytes[at] = (rng.next() and 0xFF).toByte()
                exercise("$name byte@$at", bytes)
            }
        }
    }

    @Test
    fun bitFlipsNeverLeakARuntimeFault() {
        val rng = Rng(0x1BADB002)
        for ((name, seed) in corpus()) {
            repeat(300) {
                val bytes = seed.copyOf()
                val at = rng.nextInt(bytes.size)
                bytes[at] = (bytes[at].toInt() xor (1 shl rng.nextInt(8))).toByte()
                exercise("$name bit@$at", bytes)
            }
        }
    }

    @Test
    fun truncationAtEveryOffsetNeverLeaksARuntimeFault() {
        for ((name, seed) in corpus()) {
            // Every offset for small files, a stride for larger ones, and always
            // the pathological tail.
            val step = maxOf(1, seed.size / 200)
            var cut = 0
            while (cut < seed.size) {
                exercise("$name cut@$cut", seed.copyOf(cut))
                cut += step
            }
            exercise("$name empty", ByteArray(0))
            exercise("$name seed-1", seed.copyOf(seed.size - 1))
        }
    }

    @Test
    fun multiByteCorruptionNeverLeaksARuntimeFault() {
        val rng = Rng(0x0DEFACED)
        for ((name, seed) in corpus()) {
            repeat(300) {
                val bytes = seed.copyOf()
                repeat(1 + rng.nextInt(8)) {
                    bytes[rng.nextInt(bytes.size)] = (rng.next() and 0xFF).toByte()
                }
                exercise("$name multi", bytes)
            }
        }
    }

    @Test
    fun corruptedLengthAndDimensionFieldsNeverLeakARuntimeFault() {
        // Header fields are where a decoder is most likely to trust the file:
        // sizes, counts and offsets all live in the first bytes.
        val rng = Rng(0x600DF00D)
        val nasty = intArrayOf(0x00, 0x01, 0x7F, 0x80, 0xFE, 0xFF)
        for ((name, seed) in corpus()) {
            val headerEnd = minOf(seed.size, 64)
            repeat(400) {
                val bytes = seed.copyOf()
                val at = rng.nextInt(headerEnd)
                bytes[at] = nasty[rng.nextInt(nasty.size)].toByte()
                if (at + 1 < bytes.size) bytes[at + 1] = nasty[rng.nextInt(nasty.size)].toByte()
                exercise("$name header@$at", bytes)
            }
        }
    }

    @Test
    fun extremeFourByteHeaderFieldsNeverLeakARuntimeFault() {
        val values = intArrayOf(0, 1, 0x7FFFFFF7, 0x7FFFFFF8, 0x7FFFFFF9, 0x7FFFFFFE, Int.MAX_VALUE, Int.MIN_VALUE, -1)
        for ((name, seed) in corpus().filter { it.first == "jpeg-exif" || it.first == "tiff" }) {
            for (at in 0 until minOf(seed.size - 3, 64)) {
                for (value in values) {
                    for (littleEndian in listOf(true, false)) {
                        val bytes = seed.copyOf()
                        for (i in 0..3) bytes[at + i] = (value ushr (8 * if (littleEndian) i else 3 - i)).toByte()
                        exercise("$name u32@$at=$value", bytes)
                    }
                }
            }
        }
    }

    @Test
    fun splicedFilesNeverLeakARuntimeFault() {
        // A header of one format followed by the body of another: the classic way
        // to walk a decoder into someone else's data.
        val files = corpus()
        for ((nameA, a) in files) {
            for ((nameB, b) in files) {
                if (nameA == nameB) continue
                val cut = minOf(a.size / 2, b.size)
                exercise("$nameA+$nameB", a.copyOf(cut) + b.copyOfRange(0, minOf(b.size, 512)))
            }
        }
    }

    @Test
    fun repeatedAndZeroFilledInputNeverLeaksARuntimeFault() {
        // Degenerate inputs that are valid magic followed by nothing useful.
        val magics = listOf(
            hex("89504e470d0a1a0a"),
            hex("ffd8ff"),
            hex("474946383961"),
            hex("424d"),
            hex("49492a00"),
            hex("52494646000000005745425000000000"),
            hex("0000000c6a5020200d0a870a"),
        )
        for (magic in magics) {
            for (tail in intArrayOf(0, 1, 16, 256)) {
                exercise("magic+$tail", magic + ByteArray(tail))
                exercise("magic+ff$tail", magic + ByteArray(tail) { 0xFF.toByte() })
            }
        }
    }
}
