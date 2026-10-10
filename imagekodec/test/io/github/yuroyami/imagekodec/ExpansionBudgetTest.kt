package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.internal.Budget
import io.github.yuroyami.imagekodec.internal.flate.Zlib
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** VP8L section 6.2.1: singleton trees need no per-pixel entropy bits. */
internal fun constantWebp(width: Int, height: Int, pixel: Int): ByteArray {
    val bits = ArrayList<Int>()
    fun put(value: Int, count: Int) { repeat(count) { bits.add((value ushr it) and 1) } }
    fun singleton(symbol: Int) {
        put(1, 1); put(0, 1); put(if (symbol < 2) 0 else 1, 1)
        put(symbol, if (symbol < 2) 1 else 8)
    }
    put(0x2f, 8); put(width - 1, 14); put(height - 1, 14)
    put(if (pixel ushr 24 == 255) 0 else 1, 1); put(0, 3)
    put(0, 1); put(0, 1); put(0, 1)
    for (shift in intArrayOf(8, 16, 0, 24)) singleton((pixel ushr shift) and 255)
    singleton(0)
    val payload = ByteArray((bits.size + 7) / 8)
    for (i in bits.indices) payload[i / 8] = (payload[i / 8].toInt() or (bits[i] shl (i % 8))).toByte()
    return losslessWebp(payload)
}

internal fun constantExtendedWebp(width: Int, height: Int, pixels: List<Int>, animated: Boolean): ByteArray {
    fun le(value: Int, count: Int) = ByteArray(count) { (value ushr (it * 8)).toByte() }
    fun chunk(name: String, body: ByteArray) = name.encodeToByteArray() + le(body.size, 4) + body +
        (if (body.size % 2 == 0) ByteArray(0) else byteArrayOf(0))
    val header = byteArrayOf(if (animated) 2 else 0, 0, 0, 0) + le(width - 1, 3) + le(height - 1, 3)
    var body = chunk("VP8X", header)
    if (animated) body += chunk("ANIM", byteArrayOf(0, 0, 0, 0, 2, 0))
    for (pixel in pixels) {
        val still = constantWebp(width.coerceAtMost(16384), height.coerceAtMost(16384), pixel)
        val image = still.copyOfRange(12, still.size)
        body += if (animated) chunk("ANMF", le(0, 3) + le(0, 3) + le(width - 1, 3) + le(height - 1, 3) + le(40, 3) + byteArrayOf(2) + image)
            else image
    }
    return "RIFF".encodeToByteArray() + le(body.size + 4, 4) + "WEBP".encodeToByteArray() + body
}

internal fun packedBlankPng(width: Int, height: Int, depth: Int): ByteArray {
    val raw = ByteArray(((width * depth + 7) / 8 + 1) * height)
    return PNG_SIGNATURE + pngHeader(width, height, depth, 0) + pngChunk("IDAT", Zlib.compress(raw)) + pngChunk("IEND", ByteArray(0))
}

class ExpansionBudgetTest {
    @Test
    fun constantWebpHasNoFiniteInputRatio() {
        for (pixel in intArrayOf(-1, 0)) {
            val bytes = constantWebp(2048, 1024, pixel)
            assertTrue(2048L * 1024 > bytes.size * 32768L)
            assertTrue(ImageKodec.probe(bytes).isDecodable)
            val bitmap = ImageKodec.decode(bytes)
            assertEquals(2048, bitmap.width)
            assertEquals(1024, bitmap.height)
            assertTrue(bitmap.argb.all { it == pixel })
        }
    }

    @Test
    fun blankFaxPagesUseTheAbsoluteOutputBudget() {
        val bytes = faxTiff(faxBits("1".repeat(1024)), 4, width = 2048, height = 1024)
        assertTrue(2048L * 1024 > bytes.size * 4096L)
        assertTrue(ImageKodec.probe(bytes).isDecodable)
        val bitmap = ImageKodec.decode(bytes)
        assertTrue(bitmap.argb.all { it == -1 })
    }

    @Test
    fun deflateBilevelTiffAccountsForPackedPixels() {
        val bytes = faxTiff(Zlib.compress(ByteArray(4096 / 8 * 1024)), 8, width = 4096, height = 1024)
        assertTrue(4096L * 1024 > bytes.size * 4096L)
        assertTrue(ImageKodec.probe(bytes).isDecodable)
        assertTrue(ImageKodec.decode(bytes).argb.all { it == -1 })
    }

    @Test
    fun deflateBilevelPngAccountsForPackedPixels() {
        val bytes = packedBlankPng(4096, 1024, 1)
        assertTrue(4096L * 1024 > bytes.size * 4096L)
        assertTrue(ImageKodec.probe(bytes).isDecodable)
        assertTrue(ImageKodec.decode(bytes).argb.all { it == gray(0) })
    }

    @Test
    fun bilevelApngFrameBudgetAccountsForPackedPixels() {
        val width = 4096
        val height = 1024
        val raw = Zlib.compress(ByteArray((width / 8 + 1) * height))
        fun control(sequence: Int) = pngChunk("fcTL", beBytes(sequence) + beBytes(width) + beBytes(height) +
            beBytes(0) + beBytes(0) + byteArrayOf(0, 1, 0, 10, 0, 0))
        val bytes = PNG_SIGNATURE + pngHeader(width, height, 1, 0) +
            pngChunk("acTL", beBytes(2) + beBytes(1)) + control(0) + pngChunk("IDAT", raw) +
            control(1) + pngChunk("fdAT", beBytes(2) + raw) + pngChunk("IEND", ByteArray(0))
        assertTrue(width.toLong() * height * 2 > bytes.size * 4096L)
        val animation = ImageKodec.decodeAnimation(bytes)
        assertEquals(2, animation.frames.size)
        for (frame in animation.frames) assertTrue(frame.bitmap.argb.all { it == gray(0) })
    }

    @Test
    fun flatWebpAnimationsKeepTheTotalOutputCeiling() {
        val bytes = constantExtendedWebp(2048, 1024, listOf(-1, 0xff008040.toInt()), true)
        val animation = ImageKodec.decodeAnimation(bytes)
        assertEquals(2, animation.frames.size)
        assertTrue(animation.frames[0].bitmap.argb.all { it == -1 })
        assertTrue(animation.frames[1].bitmap.argb.all { it == 0xff008040.toInt() })
        val oversized = constantExtendedWebp(16384, 16384, listOf(-1, -1), true)
        val error = assertFailsWith<ImageDecodeException> { ImageKodec.decodeAnimation(oversized) }
        assertTrue("output safety limit" in error.message.orEmpty())
    }

    @Test
    fun invalidWebpGeometryFailsBeforeTheCanvasAllocation() {
        val bytes = constantExtendedWebp(1 shl 20, 257, listOf(-1), false)
        assertFailsWith<ImageDecodeException> { ImageKodec.decode(bytes) }
        val mismatch = constantExtendedWebp(16384, 16384, listOf(-1), false)
        // Keep VP8X large, but replace the codec header with a 1x1 image.
        for (i in 39..41) mismatch[i] = 0
        mismatch[42] = 0
        val error = assertFailsWith<ImageDecodeException> { ImageKodec.decode(mismatch) }
        assertTrue("VP8L header" in error.message.orEmpty())
    }

    @Test
    fun nonconstantWebpStillNeedsEnoughEntropy() {
        val bytes = prefixWebp(intArrayOf(1, 1))
        // The 14-bit width/height fields are in bytes 21..24 of this RIFF.
        bytes[21] = -1; bytes[22] = -1; bytes[23] = -1; bytes[24] = 15
        val error = assertFailsWith<ImageDecodeException> { ImageKodec.decode(bytes) }
        assertTrue("insufficient entropy" in error.message.orEmpty())
    }

    @Test
    fun frameBudgetsCannotOverflowOrExceedTheAbsoluteCeiling() {
        assertTrue(Budget.fitsAbsolute(16384, 16384))
        assertTrue(!Budget.fitsAbsolute(16385, 16384))
        assertTrue(Budget.framesFitAbsolute(4096, 4096, 16))
        assertTrue(!Budget.framesFitAbsolute(4096, 4096, 17))
        assertTrue(!Budget.framesFitAbsolute(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
        assertTrue(!Budget.framesFit(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
        assertTrue(!Budget.framesFitAbsolute(0, 1, 1))
        assertTrue(!Budget.framesFitAbsolute(1, 1, 0))
    }

    @Test
    fun oversizedFaxPageStillFailsBeforeDecoding() {
        val bytes = faxTiff(byteArrayOf(-1), 4, width = 1 shl 20, height = 257)
        val error = assertFailsWith<ImageDecodeException> { ImageKodec.decode(bytes) }
        assertTrue("safety limits" in error.message.orEmpty())
    }
}
