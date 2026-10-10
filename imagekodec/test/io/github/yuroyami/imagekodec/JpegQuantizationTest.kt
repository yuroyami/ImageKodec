package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A single DC coefficient per component makes the table lifetime visible without IDCT variance. */
internal fun quantizationJpeg(
    progressive: Boolean,
    sharedSlot: Boolean = true,
    defineLuma: Boolean = true,
    defineChroma: Boolean = true,
    lateChroma: Boolean = false,
): ByteArray {
    fun table(slot: Int, value: Int) = jpegSegment(0xDB, byteArrayOf(slot.toByte()) + ByteArray(64) { value.toByte() })
    val chromaSlot = if (sharedSlot) 0 else 1
    val frame = jpegSegment(
        if (progressive) 0xC2 else 0xC0,
        byteArrayOf(8, 0, 8, 0, 8, 3, 1, 0x11, 0, 2, 0x11, chromaSlot.toByte(), 3, 0x11, chromaSlot.toByte()),
    )
    // One-bit DC code 0 means category 6; AC code 0 means EOB.
    val huffman = jpegSegment(0xC4, byteArrayOf(0, 1) + ByteArray(15) + byteArrayOf(6)) +
        jpegSegment(0xC4, byteArrayOf(0x10, 1) + ByteArray(15) + byteArrayOf(0))
    fun scan(id: Int): ByteArray = jpegSegment(0xDA, byteArrayOf(1, id.toByte(), 0, 0, if (progressive) 0 else 63, 0)) +
        // Code 0 + coefficient 111111 (+63); sequential also reads an EOB 0.
        byteArrayOf(if (progressive) 0x7F else 0x7E)
    return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
        (if (defineLuma) table(0, 8) else byteArrayOf()) + frame + huffman + scan(1) +
        (if (defineChroma && !lateChroma) table(chromaSlot, 2) else byteArrayOf()) + scan(2) +
        (if (defineChroma && lateChroma) table(chromaSlot, 2) else byteArrayOf()) + scan(3) +
        byteArrayOf(0xFF.toByte(), 0xD9.toByte())
}

class JpegQuantizationTest {
    @Test
    fun missingTableNamesItsSlot() {
        for (progressive in listOf(false, true)) {
            for ((slot, bytes) in listOf(
                0 to quantizationJpeg(progressive, defineLuma = false),
                1 to quantizationJpeg(progressive, sharedSlot = false, defineChroma = false),
                1 to quantizationJpeg(progressive, sharedSlot = false, lateChroma = true),
            )) {
                val e = assertFailsWith<ImageDecodeException> { ImageKodec.decode(bytes) }
                assertTrue(e.message!!.contains("quantization table $slot"), e.message)
            }
        }
    }

    @Test
    fun componentsKeepTheirTableWhenTheSlotIsReused() {
        for (progressive in listOf(false, true)) {
            val reused = ImageKodec.decode(quantizationJpeg(progressive))
            val separate = ImageKodec.decode(quantizationJpeg(progressive, sharedSlot = false))
            assertContentEquals(separate.argb, reused.argb)
            // Y = 191, Cb = Cr = 144: changing Y's quantizer to 2 would make it 144.
            assertEquals(0xFFD5AEDB.toInt(), reused[4, 4])
        }
    }
}
