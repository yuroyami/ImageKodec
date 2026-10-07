package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Jp2BudgetTest {
    private fun refused(bytes: ByteArray, expectSizeError: Boolean = true) {
        val info = ImageKodec.probe(bytes)
        assertFalse(info.isDecodable)
        assertTrue(info.unsupportedReason.orEmpty().contains("safety limits"), info.unsupportedReason)
        val failure = assertFailsWith<ImageDecodeException> { ImageKodec.decode(bytes) }
        val reducedFailure = assertFailsWith<ImageDecodeException> { ImageKodec.decodeReduced(bytes, 8) }
        if (expectSizeError) {
            assertTrue(failure.message.orEmpty().contains(info.unsupportedReason.orEmpty()), failure.message)
            assertTrue(reducedFailure.message.orEmpty().contains(info.unsupportedReason.orEmpty()), reducedFailure.message)
        }
    }

    @Test
    fun theShortSizOnlyReproducerProbesUndecodable() {
        val bytes = jp2OversizeSeed()
        assertTrue(bytes.size < 60)
        val info = ImageKodec.probe(bytes)
        assertEquals(8000, info.width)
        assertEquals(8000, info.height)
        refused(bytes, expectSizeError = false)
    }

    @Test
    fun fullHeadersUseTheSameInputBudgetAtProbeAndDecode() {
        val bytes = fullHeader(4096, 4096)
        val reason = ImageKodec.probe(bytes).unsupportedReason.orEmpty()
        val failure = assertFailsWith<ImageDecodeException> { ImageKodec.decode(bytes) }
        assertTrue(reason.contains("4096x4096"), reason)
        assertTrue(failure.message.orEmpty().contains(reason), failure.message)
        refused(bytes)
    }

    @Test
    fun declarationsAboveTheSharedAbsoluteCeilingAreRefused() {
        refused(fullHeader(262145, 1024).copyOf(100_000))
    }

    @Test
    fun theOldDuplicateCeilingDoesNotOverrideTheSharedBudget() {
        // Header inspection has no pixel planes; decoding this deliberately incomplete
        // stream still fails at the missing COD before any raster allocation.
        val bytes = (jp2SizeHeader(8193, 8192) + byteArrayOf(0xff.toByte(), 0xd9.toByte())).copyOf(32_768)
        val info = ImageKodec.probe(bytes)
        assertTrue(info.isDecodable, info.unsupportedReason)
        assertEquals(8193, info.width)
        assertEquals(8192, info.height)
        val failure = assertFailsWith<ImageDecodeException> { ImageKodec.decode(bytes) }
        assertTrue(failure.message.orEmpty().contains("missing COD"), failure.message)
    }

    private fun fullHeader(width: Int, height: Int): ByteArray = hex(JP2).also { bytes ->
        val siz = bytes.indices.first { it + 1 < bytes.size && bytes[it] == 0xff.toByte() && bytes[it + 1] == 0x51.toByte() }
        for (field in intArrayOf(siz + 6, siz + 22)) jp2Put32(bytes, field, width)
        for (field in intArrayOf(siz + 10, siz + 26)) jp2Put32(bytes, field, height)
    }

    @Test
    fun referenceFilesStillProbeAndDecodeWithTheSameDimensions() {
        val boxed = hex(JP2)
        val soc = boxed.indices.first { it + 1 < boxed.size && boxed[it] == 0xff.toByte() && boxed[it + 1] == 0x4f.toByte() }
        for (bytes in listOf(boxed, boxed.copyOfRange(soc, boxed.size))) {
            val info = ImageKodec.probe(bytes)
            val pixels = ImageKodec.decode(bytes)
            assertTrue(info.isDecodable, info.unsupportedReason)
            assertEquals(pixels.width to pixels.height, info.width to info.height)
        }
    }
}

internal fun jp2OversizeSeed(): ByteArray = jp2SizeHeader(8000, 8000)

private fun jp2SizeHeader(width: Int, height: Int): ByteArray = ByteArray(45).also {
    it[0] = 0xff.toByte(); it[1] = 0x4f
    it[2] = 0xff.toByte(); it[3] = 0x51
    it[5] = 41
    jp2Put32(it, 8, width); jp2Put32(it, 12, height)
    jp2Put32(it, 24, width); jp2Put32(it, 28, height)
    it[41] = 1; it[42] = 7; it[43] = 1; it[44] = 1
}

private fun jp2Put32(data: ByteArray, at: Int, value: Int) {
    for (i in 0..3) data[at + i] = (value ushr (24 - 8 * i)).toByte()
}
