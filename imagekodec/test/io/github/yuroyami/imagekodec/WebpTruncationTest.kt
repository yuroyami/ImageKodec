package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.Vp8lDecoder
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal fun truncatedWebp(percentage: Int): ByteArray {
    val original = hex(WEBP_UNIFORM_HISTOGRAM)
    val length = (0..3).sumOf { (original[16 + it].toInt() and 255) shl (it * 8) }
    return losslessWebp(original.copyOfRange(20, 20 + length * percentage / 100))
}

class WebpTruncationTest {
    @Test
    fun truncatedPayloadWithConsistentContainerLengthsFails() {
        for (percentage in listOf(90, 50, 10)) {
            val bytes = truncatedWebp(percentage)
            val error = assertFailsWith<ImageDecodeException>("$percentage percent") { ImageKodec.decode(bytes) }
            assertTrue("bitstream ends early" in error.message.orEmpty(), error.message.orEmpty())
            assertFailsWith<ImageDecodeException> { ImageKodec.decodeAnimation(bytes) }
            assertFailsWith<ImageDecodeException> { ImageKodec.decodeReduced(bytes, 2) }
        }
    }

    @Test
    fun chunkRangeIsCheckedBeforeReading() {
        val data = ByteArray(10)
        for ((offset, length) in listOf(-1 to 1, 0 to -1, 11 to 0, 9 to 2,
            Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE)) {
            assertFailsWith<ImageDecodeException> { Vp8lDecoder.decode(data, offset, length) }
        }
        val error = assertFailsWith<ImageDecodeException> { Vp8lDecoder.decode(data, 10, 0) }
        assertTrue("bitstream ends early" in error.message.orEmpty(), error.message.orEmpty())
    }
}
