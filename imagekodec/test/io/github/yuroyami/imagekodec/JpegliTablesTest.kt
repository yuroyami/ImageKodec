package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.jpegli.JpegliTables
import io.github.yuroyami.imagekodec.internal.flate.Crc32
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Jpegli tables are fitted data from jpegli's `lib/jpegli/quant.cc`, written into the tree only
 * by `tools/jpegli_tables.py`, which records the CRC-32 of each table's float32 values. A value
 * edited by hand, or a table cut short, no longer matches (#133).
 */
class JpegliTablesTest {

    private fun crc(values: DoubleArray): Long {
        val bytes = ByteArray(values.size * 4)
        for ((i, v) in values.withIndex()) {
            val bits = v.toFloat().toBits()
            for (b in 0 until 4) bytes[4 * i + b] = (bits ushr (24 - 8 * b)).toByte()
        }
        return Crc32().apply { update(bytes) }.value()
    }

    @Test
    fun every_table_is_the_one_jpegli_holds() {
        val tables = listOf(
            Triple(JpegliTables.BASE_QUANT_MATRIX_YCBCR, 192, JpegliTables.BASE_QUANT_MATRIX_YCBCR_CRC32),
            Triple(JpegliTables.RESCALE_420, 64, JpegliTables.RESCALE_420_CRC32),
            Triple(JpegliTables.EXPONENT, 64, JpegliTables.EXPONENT_CRC32),
            Triple(JpegliTables.ZERO_BIAS_MUL_LQ, 192, JpegliTables.ZERO_BIAS_MUL_LQ_CRC32),
            Triple(JpegliTables.ZERO_BIAS_MUL_HQ, 192, JpegliTables.ZERO_BIAS_MUL_HQ_CRC32),
            Triple(JpegliTables.ZERO_BIAS_OFFSET_AC, 3, JpegliTables.ZERO_BIAS_OFFSET_AC_CRC32),
        )
        for ((i, t) in tables.withIndex()) {
            val (values, size, expected) = t
            assertEquals(size, values.size, "table $i")
            assertEquals(expected, crc(values), "table $i")
        }
    }
}
