package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.Vp8Tables
import io.github.yuroyami.imagekodec.internal.flate.Crc32
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The VP8 tables are data from libwebp, written into the tree only by `tools/vp8_tables.py`, which
 * records the CRC-32 of each table as libwebp holds it. A value edited by hand, or a table cut short,
 * no longer matches (#11).
 */
class Vp8TablesTest {

    private fun crc(values: IntArray, bytesPerValue: Int): Long {
        val bytes = ByteArray(values.size * bytesPerValue)
        for ((i, v) in values.withIndex()) {
            if (bytesPerValue == 2) {
                bytes[2 * i] = (v ushr 8).toByte()
                bytes[2 * i + 1] = v.toByte()
            } else {
                bytes[i] = v.toByte()
            }
        }
        return Crc32().apply { update(bytes) }.value()
    }

    @Test
    fun everyTableIsTheOneLibwebpHolds() {
        assertEquals(4 * 8 * 3 * 11, Vp8Tables.COEFFS_PROBA_0.size)
        assertEquals(Vp8Tables.COEFFS_PROBA_0_CRC32, crc(Vp8Tables.COEFFS_PROBA_0, 1))
        assertEquals(4 * 8 * 3 * 11, Vp8Tables.COEFFS_UPDATE_PROBA.size)
        assertEquals(Vp8Tables.COEFFS_UPDATE_PROBA_CRC32, crc(Vp8Tables.COEFFS_UPDATE_PROBA, 1))
        assertEquals(10 * 10 * 9, Vp8Tables.BMODES_PROBA.size)
        assertEquals(Vp8Tables.BMODES_PROBA_CRC32, crc(Vp8Tables.BMODES_PROBA, 1))
        assertEquals(128, Vp8Tables.DC_TABLE.size)
        assertEquals(Vp8Tables.DC_TABLE_CRC32, crc(Vp8Tables.DC_TABLE, 1))
        assertEquals(128, Vp8Tables.AC_TABLE.size)
        assertEquals(Vp8Tables.AC_TABLE_CRC32, crc(Vp8Tables.AC_TABLE, 2))
    }

    @Test
    fun theQuantizerStepsRiseAsRfc6386Requires() {
        // RFC 6386 section 14.1 gives the steps as non-decreasing, from 4 to 157 for DC and to 284 for AC.
        for (table in listOf(Vp8Tables.DC_TABLE, Vp8Tables.AC_TABLE)) {
            for (i in 1 until table.size) assertEquals(true, table[i] >= table[i - 1], "step $i")
        }
        assertEquals(4 to 157, Vp8Tables.DC_TABLE.first() to Vp8Tables.DC_TABLE.last())
        assertEquals(4 to 284, Vp8Tables.AC_TABLE.first() to Vp8Tables.AC_TABLE.last())
    }
}
