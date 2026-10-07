package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.CcittFax
import io.github.yuroyami.imagekodec.codec.CcittOptions
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
    fun theOutputHoldsEveryDecodedRowWhetherOrNotTheCountIsDeclared() {
        // 25 bytes of V0 codes are 200 white rows. A row costs at least one bit, so the output is sized
        // once from a declared count the input can hold, and grows from a guess when none is declared
        // (#105). With 13 columns a set bit is white, and the 3 bits that pad a row to 2 bytes stay 0.
        val data = ByteArray(25) { 0xFF.toByte() }
        for (rows in listOf(0, 150, 200, 1000)) {
            val decoded = if (rows == 0 || rows > 200) 200 else rows
            val out = CcittFax.decode(data, k = -1, options = options(columns = 13, rows = rows))
            assertContentEquals(ByteArray(decoded * 2) { if (it % 2 == 0) 0xFF.toByte() else 0xF8.toByte() }, out, "$rows rows")
            val black = CcittFax.decode(data, k = -1, options = options(columns = 13, rows = rows).copy(blackIs1 = true))
            assertContentEquals(ByteArray(decoded * 2), black, "$rows rows with BlackIs1")
        }
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
