package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.avif.Av1Tables
import io.github.yuroyami.imagekodec.internal.flate.Crc32
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The AV1 tables are the specification's own arrays, written into the tree only by
 * `tools/av1_tables.py`, which records the CRC-32 of each one as the specification holds it.
 * A value edited by hand, a table cut short, or a packing that decodes differently on one
 * platform no longer matches (#41).
 */
class Av1TablesTest {

    private fun crc(values: IntArray): Long {
        val bytes = ByteArray(values.size * 4)
        for ((i, v) in values.withIndex()) for (k in 0 until 4) bytes[i * 4 + k] = (v ushr (8 * k)).toByte()
        return Crc32().apply { update(bytes) }.value()
    }

    @Test
    fun everyTableIsTheOneTheSpecificationDeclares() {
        val tables = Av1Tables.tables
        assertEquals(Av1Tables.NAMES.size, tables.size)
        assertEquals(Av1Tables.CRC32.size, tables.size)
        for (i in tables.indices) assertEquals(Av1Tables.CRC32[i], crc(tables[i]), Av1Tables.NAMES[i])
    }

    @Test
    fun aFewEntriesReadAsTheSpecificationPrintsThem() {
        assertEquals(listOf(0, 840, 420, 280, 210, 168, 140, 120, 105), Av1Tables.divTable.toList())
        // Dc_Qlookup and Ac_Qlookup: three bit depths of 256 steps each.
        assertEquals(3 * 256, Av1Tables.dcQlookup.size)
        assertEquals(4 to 1336, Av1Tables.dcQlookup[0] to Av1Tables.dcQlookup[255])
        assertEquals(4 to 1828, Av1Tables.acQlookup[0] to Av1Tables.acQlookup[255])
        assertEquals(2048, Av1Tables.gaussianSequence.size)
        assertEquals(56, Av1Tables.gaussianSequence[0])
        assertEquals(listOf(0, 0, 0, 128, 0, 0, 0, 0), Av1Tables.upscaleFilter.copyOfRange(0, 8).toList())
        assertEquals(listOf(2, 12, 1, 4), Av1Tables.sgrParams.copyOfRange(0, 4).toList())
    }
}
