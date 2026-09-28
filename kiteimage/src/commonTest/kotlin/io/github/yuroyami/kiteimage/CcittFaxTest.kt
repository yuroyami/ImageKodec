package io.github.yuroyami.kiteimage

import io.github.yuroyami.kiteimage.codec.CcittFax
import io.github.yuroyami.kiteimage.codec.CcittOptions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Fax data carries no size of its own, so the caller passes the columns and rows. In a PDF those come
 * straight out of the file being read, and the decoder must treat them like any other header field.
 */
class CcittFaxTest {

    private fun options(columns: Int, rows: Int = 0) = CcittOptions(
        columns = columns, rows = rows, endOfBlock = false,
        blackIs1 = false, encodedByteAlign = false, endOfLine = false,
    )

    @Test
    fun aPageOfWhiteRowsDecodes() {
        // Group 4: a white row under a white row is one V0 code, the bit 1. Two rows are the bits 11.
        val out = CcittFax.decode(byteArrayOf(0xC0.toByte()), k = -1, options = options(columns = 8, rows = 2))
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0xFF.toByte()), out)
    }

    @Test
    fun aColumnCountOutsideTheSupportedRangeIsRejected() {
        // -1 gave NegativeArraySizeException, and 0 made the row loop run out of memory on one byte.
        for (columns in intArrayOf(-1, 0, (1 shl 24) + 1)) {
            for (k in intArrayOf(-1, 0)) {
                val e = assertFailsWith<ImageDecodeException>("columns=$columns k=$k") {
                    CcittFax.decode(byteArrayOf(0x55), k, options(columns))
                }
                assertTrue(e.message!!.contains("columns"), "message should name the problem: ${e.message}")
            }
        }
    }

    @Test
    fun aRowCountThatCannotFitTheCeilingIsRejectedAtOnce() {
        val e = assertFailsWith<ImageDecodeException> {
            CcittFax.decode(byteArrayOf(0xFF.toByte()), k = -1, options = options(columns = 1 shl 20, rows = 1 shl 20))
        }
        assertTrue(e.message!!.contains("pixels"), "message should name the problem: ${e.message}")
        assertFailsWith<ImageDecodeException> { CcittFax.decode(byteArrayOf(0xFF.toByte()), k = 0, options = options(columns = 8, rows = -1)) }
    }

    @Test
    fun aPageOfUnknownHeightStopsAtTheCeiling() {
        // Each set bit is one more white row of 2^20 pixels, so 33 bytes ask for 264 rows: more than
        // the 256 that fit under 2^28 pixels. The check has to fire while rows are still being read.
        val data = ByteArray(33) { 0xFF.toByte() }
        val e = assertFailsWith<ImageDecodeException> { CcittFax.decode(data, k = -1, options = options(columns = 1 shl 20)) }
        assertTrue(e.message!!.contains("pixels"), "message should name the problem: ${e.message}")
    }
}
