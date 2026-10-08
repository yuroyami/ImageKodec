package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Frames this decoder recognizes and does not implement. Each refusal is an
 * [UnsupportedImageException] that names the feature, and `probe` reports the same reason, so a
 * caller such as the Coil factory can pass the file on instead of claiming it (#70).
 */
class JpegRefusalTest {

    /** A 16 by 16 frame of type [marker] after a quantization table of ones, with each component sampled [factors]. */
    private fun frame(factors: List<Int>, marker: Int = 0xC0, precision: Int = 8): ByteArray {
        val components = factors.withIndex().flatMap { (i, f) -> listOf((i + 1).toByte(), f.toByte(), 0) }
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            jpegSegment(0xDB, byteArrayOf(0) + ByteArray(64) { 1 }) +
            jpegSegment(marker, byteArrayOf(precision.toByte(), 0, 16, 0, 16, factors.size.toByte()) + components.toByteArray()) +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    private val refused = listOf(
        "2-component JPEG" to frame(listOf(0x11, 0x11)),
        "5-component JPEG" to frame(List(5) { 0x11 }),
        "JPEG with 3 components sampled 3x1, 2x1, 2x1 (factors must divide the largest)" to frame(listOf(0x31, 0x21, 0x21)),
        "JPEG with 3 components sampled 1x4, 1x3, 1x1 (factors must divide the largest)" to frame(listOf(0x14, 0x13, 0x11)),
        "12-bit JPEG (8-bit samples only)" to frame(listOf(0x11), precision = 12),
    )

    @Test
    fun featureRefusalsAreTypedAndProbeNamesTheSameFeature() {
        for ((reason, bytes) in refused) {
            val info = ImageKodec.probe(bytes)
            assertFalse(info.isDecodable, reason)
            assertEquals(reason, info.unsupportedReason)
            for (decode in listOf<(ByteArray) -> Unit>(
                { ImageKodec.decode(it) },
                { ImageKodec.decodeReduced(it, 2) },
                { ImageKodec.decodeJpegComponents(it, 4) },
            )) {
                val e = assertFailsWith<UnsupportedImageException>(reason) { decode(bytes) }
                assertTrue(e.message.orEmpty().startsWith(reason), "$reason: ${e.message}")
            }
        }
    }

    @Test
    fun factorsThatDivideTheLargestAreDecodable() {
        for (factors in listOf(listOf(0x41, 0x21, 0x11), listOf(0x22, 0x12, 0x21), listOf(0x11))) {
            val info = ImageKodec.probe(frame(factors))
            assertTrue(info.isDecodable, "$factors: ${info.unsupportedReason}")
        }
    }

    @Test
    fun aDifferentialFrameOutsideAHierarchyIsAFaultNotAFeature() {
        // T.81 Annex J: a differential frame refines a reference, which only a DHP and a frame before it give.
        for (marker in listOf(0xC5, 0xC6, 0xC7, 0xCD, 0xCE, 0xCF)) {
            val e = assertFailsWith<ImageDecodeException> { ImageKodec.decode(frame(listOf(0x11), marker = marker)) }
            assertFalse(e is UnsupportedImageException, "0x${marker.toString(16)}: ${e.message}")
        }
    }

    @Test
    fun aFactorOutsideOneToFourIsAFaultNotAFeature() {
        // T.81 allows sampling factors 1 to 4 only, so 0 or 5 is a broken file.
        for (factors in listOf(listOf(0x01, 0x11, 0x11), listOf(0x51, 0x11, 0x11))) {
            val e = assertFailsWith<ImageDecodeException> { ImageKodec.decode(frame(factors)) }
            assertFalse(e is UnsupportedImageException, "$factors: ${e.message}")
        }
    }
}
