package io.github.yuroyami.imagekodec.heic

import kotlin.test.Test
import kotlin.test.assertEquals

/** The constant tables hold the CRC-32 that tools/hevc_tables.py wrote with them, so an edit by hand fails. */
class HevcTablesTest {

    private fun crc32(values: IntArray): Long {
        var c = 0xFFFFFFFFL
        for (v in values) {
            c = c xor (v.toLong() and 0xFF)
            repeat(8) { c = if (c and 1L != 0L) (c ushr 1) xor 0xEDB88320L else c ushr 1 }
        }
        return c xor 0xFFFFFFFFL
    }

    @Test
    fun defaultScalingLists() {
        assertEquals(0xF4A79674L, crc32(HevcTables.DEFAULT_SCALING_INTRA))
        assertEquals(0x1620423AL, crc32(HevcTables.DEFAULT_SCALING_INTER))
        // Table 7-5: the default 4x4 list is flat; the larger ones start at 16 and end where Table 7-6 does.
        assertEquals(List(16) { 16 }, ScalingLists.DEFAULT.lists[0].toList())
        assertEquals(115, ScalingLists.DEFAULT.lists[6 + 2].last())
        assertEquals(91, ScalingLists.DEFAULT.lists[18 + 3].last())
    }
}
