package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.JpegEncoder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The bytes the `encodeJpeg` overloads that take no [JpegEncodeOptions] wrote before #183, as
 * FNV-1a hashes taken from the encoder before the options came in. Those calls keep their output.
 */
class JpegLegacyHashTest {

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

    internal fun outputs(): List<Pair<String, ByteArray>> {
        val exif = byteArrayOf(0x4D, 0x4D, 0, 0x2A, 0, 0, 0, 8, 0, 0)
        return listOf(
            "q50 4:2:0" to ImageKodec.encodeJpeg(photo(45, 31, 1), 50),
            "q90 4:2:0" to ImageKodec.encodeJpeg(photo(37, 19, 2)),
            "q97 4:4:4" to ImageKodec.encodeJpeg(photo(23, 17, 3), 97),
            "q75 gray bitmap" to ImageKodec.encodeJpeg(photo(30, 22, 4, gray = true), 75),
            "1x1" to ImageKodec.encodeJpeg(photo(1, 1, 5), 90),
            "q85 exif" to ImageKodec.encodeJpeg(photo(17, 9, 6), 85, ImageMetadata(exif = exif)),
            "jpegli q80" to ImageKodec.encodeJpeg(photo(41, 27, 7), 80, JpegEncoderKind.Jpegli),
            "jpegli q60 exif" to ImageKodec.encodeJpeg(photo(19, 33, 8), 60, ImageMetadata(exif = exif), JpegEncoderKind.Jpegli),
            "gain map gray" to JpegEncoder.encode(photo(21, 13, 9), 95, null, gray = true),
        )
    }

    @Test
    fun theCallsWithoutOptionsWriteTheBytesTheyWroteBefore() {
        val want = mapOf(
            "q50 4:2:0" to 0x67e5e056ab1d9bbeuL.toLong(),
            "q90 4:2:0" to 0x9ab6a13ecc40dd9fuL.toLong(),
            "q97 4:4:4" to 0x44748df556c0535cuL.toLong(),
            "q75 gray bitmap" to 0xa7719dddf26503bfuL.toLong(),
            "1x1" to 0x1de5ff91563bebb0uL.toLong(),
            "q85 exif" to 0x6f7d093c1b741882uL.toLong(),
            "jpegli q80" to 0x4447649a8bc99185uL.toLong(),
            "jpegli q60 exif" to 0x0bbb15cb46baa50fuL.toLong(),
            "gain map gray" to 0xe6ca5b6fe411d3dfuL.toLong(),
        )
        for ((name, bytes) in outputs()) assertEquals(want[name], fnv(bytes), name)
    }
}
