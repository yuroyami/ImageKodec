package io.github.yuroyami.kiteimage

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
        "png" to KiteImage.encodePng(sample(9, 7)),
        "png-opaque" to KiteImage.encodePng(KiteBitmap(8, 8, IntArray(64) { argb(0xFF, it, 255 - it, 128) })),
        "jpeg" to KiteImage.encodeJpeg(sample(16, 16), quality = 70),
        "gif" to KiteImage.encodeGif(sample(12, 9)),
        "bmp24" to KiteImage.encodeBmp(KiteBitmap(7, 5, IntArray(35) { argb(0xFF, it * 3, it * 5, it * 7) })),
        "bmp32" to KiteImage.encodeBmp(sample(6, 4)),
        "gif-animated" to KiteImage.encodeGif(
            KiteAnimation(
                4, 4,
                List(3) { i -> KiteFrame(sample(4, 4), delayMillis = 40 + i * 10, delayRawCentiseconds = 4) },
                loopCount = 0,
            ),
        ),
        "webp-lossless" to hex(WEBP_LOSSLESS),
        "webp-animation" to hex(WEBP_ANIMATION),
        "tiff" to hex(TIFF_RGB),
    )

    private val WEBP_LOSSLESS = "524946462e000000574542505650384c220000002f0fc00200b93244f43f7651ffe87f8048dba622eedfeed8f13c4c404c005c07eb3f"
    private val WEBP_ANIMATION = "52494646f200000057454250565038580a00000002000000070000070000414e494d06000000ffffffff0000414e4d4638000000000000000000070000070000640000025650384c200000002f07c00100b93244f43f7611d1ff0061b65149ce1f74af23188f8809c01efa0f414e4d463e000000000000000000070000070000640000005650384c260000002f07c00100b93244f43f7611d1ff0061b65149ce1f74af231008a43892991ead9800971ee83f414e4d4640000000000000000000070000070000640000005650384c270000002f07c00100b93244f43f7611d1ff0061b65149ce1f74af2310082491cc3eead0c604b8f440ff0100"

    /** 4x3 uncompressed RGB TIFF, little-endian, one strip. */
    private val TIFF_RGB = buildTiff()

    private fun buildTiff(): String {
        val w = 4
        val h = 3
        val px = ByteArray(w * h * 3) { (it * 11).toByte() }
        val fields = listOf(
            intArrayOf(256, 4, 1, w), intArrayOf(257, 4, 1, h),
            intArrayOf(258, 3, 3, 0), intArrayOf(259, 3, 1, 1),
            intArrayOf(262, 3, 1, 2), intArrayOf(273, 4, 1, 0),
            intArrayOf(277, 3, 1, 3), intArrayOf(278, 4, 1, h),
            intArrayOf(279, 4, 1, px.size), intArrayOf(284, 3, 1, 1),
        )
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
                f[0] == 273 -> u32(pixelsAt)
                f[1] == 3 -> { u16(f[3]); u16(0) }
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
        mustFailCleanly("$label decode") { KiteImage.decode(bytes) }
        mustFailCleanly("$label decodeAnimation") { KiteImage.decodeAnimation(bytes) }
        mustFailCleanly("$label probe") { KiteImage.probe(bytes) }
        // probeOrNull promises never to throw on unreadable input at all.
        try {
            KiteImage.probeOrNull(bytes)
        } catch (t: Throwable) {
            fail("$label probeOrNull leaked ${t::class.simpleName}: ${t.message}")
        }
    }

    @Test
    fun everySeedFileStillDecodes() {
        for ((name, bytes) in corpus()) {
            val bm = KiteImage.decode(bytes)
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
