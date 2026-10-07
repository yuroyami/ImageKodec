package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.internal.flate.Zlib
import kotlin.test.Test
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
    private fun corpus(): List<Pair<String, ByteArray>> = listOf(
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
        "gif-animated" to ImageKodec.encodeGif(
            KiteAnimation(
                4, 4,
                List(3) { i -> KiteFrame(sample(4, 4), delayMillis = 40 + i * 10, delayRawCentiseconds = 4) },
                loopCount = 0,
            ),
        ),
        "webp-lossless" to hex(WEBP_LOSSLESS),
        "webp-singleton" to hex(WEBP_UNIFORM_HISTOGRAM),
        "webp-normal-prefix" to prefixWebp(intArrayOf(1, 1)),
        "webp-animation" to hex(WEBP_ANIMATION),
        "tiff" to hex(TIFF_RGB),
        "tiff-packbits" to tiffBlock(32773, byteArrayOf(31) + ByteArray(32) { it.toByte() }),
        "tiff-ycbcr" to hex(buildTiff(ycbcr = true)),
        "tiff-ycbcr-tiled" to TiffExtendedTest().tiledYcbcr(2, 2),
        "tiff-predictor-tiled" to predictorTiff(),
        "jp2" to hex(JP2),
        // Seeds for the paths a mutation cannot reach from the ones above: each needs several chunks or
        // fields to agree, so a random edit of a plain file stops at the first check.
        "apng" to apngSeed(),
        "png-palette" to paletteSeed(),
        "gif-subrect" to hex(GIF_SUBRECT),
        "tiff-deflate" to hex(buildTiff(compression = 8)),
        "tiff-tiled" to hex(buildTiff(tiled = true)),
        "jpeg-progressive" to hex(JPEG_PROGRESSIVE),
        "jpeg-restart" to restartIntervalJpeg(),
        "tiff-ccitt-g4" to faxTiff(faxBits("001000111010".repeat(4) + "11111111"), 4),
        "tiff-ccitt-g3" to faxTiff(group3EolStream(true), 3, width = 7, t4Options = 4),
        "tiff-sample-format" to TiffExtendedTest().sampleFormatTiff(intArrayOf(1, 1, 1), samples = 3),
    )

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

    private fun exercise(label: String, bytes: ByteArray) {
        mustFailCleanly("$label decode") { ImageKodec.decode(bytes, applyOrientation = true) }
        // The reduced JPEG path allocates and indexes its own planes, so it gets the same abuse.
        mustFailCleanly("$label decodeReduced") { ImageKodec.decodeReduced(bytes, 8) }
        mustFailCleanly("$label decodeAnimation") { ImageKodec.decodeAnimation(bytes, applyOrientation = true) }
        mustFailCleanly("$label probe") { ImageKodec.probe(bytes) }
        // probeOrNull promises never to throw on unreadable input at all.
        try {
            ImageKodec.probeOrNull(bytes)
        } catch (t: Throwable) {
            fail("$label probeOrNull leaked ${t::class.simpleName}: ${t.message}")
        }
    }

    @Test
    fun everySeedFileStillDecodes() {
        for ((name, bytes) in corpus()) {
            val bm = ImageKodec.decode(bytes)
            assertTrue(bm.width > 0 && bm.height > 0, "$name seed should decode")
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
