package io.github.yuroyami.imagekodec

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal const val PARTIAL_WEBP_ANIMATION = "UklGRoQAAABXRUJQVlA4WAoAAAACAAAABAAAAAAAQU5JTQYAAAAA/wD/AABBTk1GKAAAAAEAAAAAAAAAAAAAAGQAAAJWUDhMDwAAAC8AAAAABxD9j/4HIqL/AQBBTk1GKAAAAAIAAAAAAAAAAAAAAGQAAAJWUDhMDwAAAC8AAAAABxDR//4HIqL/AQA="

internal fun partialFirstWebp(pixel: Int, flags: Int, brokenSecond: Boolean = false, manyFrames: Boolean = false): ByteArray {
    fun le(value: Int, count: Int) = ByteArray(count) { (value ushr (it * 8)).toByte() }
    fun chunk(name: String, body: ByteArray) = name.encodeToByteArray() + le(body.size, 4) + body +
        (if (body.size % 2 == 0) ByteArray(0) else byteArrayOf(0))
    val size = if (manyFrames) 512 else 5
    val height = if (manyFrames) 512 else 3
    val image = constantWebp(1, 1, pixel)
    val payload = image.copyOfRange(20, if (brokenSecond) 25 else image.size)
    val firstChunk = image.copyOfRange(12, image.size)
    fun frame(imageChunk: ByteArray) = chunk("ANMF", le(1, 3) + le(1, 3) + le(0, 3) + le(0, 3) + le(40, 3) + byteArrayOf(flags.toByte()) + imageChunk)
    var body = chunk("VP8X", byteArrayOf(0x12, 0, 0, 0) + le(size - 1, 3) + le(height - 1, 3)) +
        chunk("ANIM", byteArrayOf(0, -1, 0, -1, 2, 0)) + frame(firstChunk)
    if (manyFrames) repeat(1024) { body += frame(firstChunk) }
    else body += frame(chunk("VP8L", payload))
    return "RIFF".encodeToByteArray() + le(body.size + 4, 4) + "WEBP".encodeToByteArray() + body
}

class WebpFirstFrameTest {
    @Test
    fun reportedPartialFrameReturnsTheCompositedCanvas() {
        val bytes = Base64.decode(PARTIAL_WEBP_ANIMATION)
        val still = ImageKodec.decode(bytes)
        val first = ImageKodec.decodeAnimation(bytes).frames.first().bitmap
        assertEquals(5, still.width)
        assertEquals(1, still.height)
        assertContentEquals(intArrayOf(0, 0, 0xffff0000.toInt(), 0, 0), still.argb)
        assertContentEquals(first.argb, still.argb)
        assertTrue(ImageKodec.probe(bytes).isDecodable)
    }

    @Test
    fun offsetAlphaAndDisposalAgreeWithTheFirstAnimationFrame() {
        for (flags in 0..3) for (pixel in intArrayOf(-1, 0, 0x80ff0040.toInt(), 0x00ff0040)) {
            val bytes = partialFirstWebp(pixel, flags)
            val still = ImageKodec.decode(bytes)
            assertEquals(5, still.width)
            assertEquals(3, still.height)
            assertContentEquals(ImageKodec.decodeAnimation(bytes).frames.first().bitmap.argb, still.argb)
            for (y in 0..2) for (x in 0..4) {
                val expected = if (x == 2 && y == 2 && (flags and 2 != 0 || pixel ushr 24 != 0)) pixel else 0
                assertEquals(expected, still[x, y], "$flags/$pixel at $x/$y")
            }
        }
    }

    @Test
    fun reducedStillCountsTransparentCanvasPixelsInItsAverage() {
        for ((pixel, expected) in listOf(0xffff0000.toInt() to 0x11ff0000, 0x80ff0040.toInt() to 0x09ff0040,
            0x00ff0040 to 0)) {
            val bytes = partialFirstWebp(pixel, 2)
            val reduced = ImageKodec.decodeReduced(bytes, 8)
            assertEquals(1, reduced.width)
            assertEquals(1, reduced.height)
            assertEquals(expected, reduced[0, 0])
            assertEquals(expected, ImageKodec.decode(bytes).scaled(1, 1)[0, 0])
        }
    }

    @Test
    fun stillDoesNotDecodeTheLaterFrameEntropy() {
        val bytes = partialFirstWebp(-1, 2, brokenSecond = true)
        assertEquals(-1, ImageKodec.decode(bytes)[2, 2])
        assertFailsWith<ImageDecodeException> { ImageKodec.decodeAnimation(bytes) }
    }

    @Test
    fun stillDoesNotRetainEveryFullCanvas() {
        val bytes = partialFirstWebp(-1, 2, manyFrames = true)
        val still = ImageKodec.decode(bytes)
        assertEquals(512, still.width)
        assertEquals(-1, still[2, 2])
        val error = assertFailsWith<ImageDecodeException> { ImageKodec.decodeAnimation(bytes) }
        assertTrue("output safety limit" in error.message.orEmpty())
    }
}
