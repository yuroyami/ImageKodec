package io.github.yuroyami.imagekodec.heic

import kotlin.test.Test
import kotlin.test.assertEquals

/** The derived scans of 6.5.1 to 6.5.5, held to the equations of the specification as written. */
class HevcScanTest {

    private fun xy(e: Int) = (e and 0xFF) to (e shr 8)

    @Test
    fun diagonalScanOf4x4() {
        // 6-11 by hand: each anti-diagonal from bottom-left to top-right.
        val expected = listOf(0 to 0, 0 to 1, 1 to 0, 0 to 2, 1 to 1, 2 to 0, 0 to 3, 1 to 2, 2 to 1, 3 to 0, 1 to 3, 2 to 2, 3 to 1, 2 to 3, 3 to 2, 3 to 3)
        assertEquals(expected, HevcScan.order(2, HevcScan.DIAGONAL).map(::xy))
        assertEquals(listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1), HevcScan.order(1, HevcScan.HORIZONTAL).map(::xy))
        assertEquals(listOf(0 to 0, 0 to 1, 1 to 0, 1 to 1), HevcScan.order(1, HevcScan.VERTICAL).map(::xy))
        for (log2 in 0..3) for (scan in 0..2) {
            val order = HevcScan.order(log2, scan)
            assertEquals(1 shl (2 * log2), order.toSet().size, "every position once, size ${1 shl log2}, scan $scan")
        }
    }

    private fun layout(width: Int, height: Int, ctbLog2: Int, columns: IntArray?, rows: IntArray?, uniform: Pair<Int, Int>? = null): CtbLayout {
        val s = Sps().apply { this.width = width; this.height = height; this.ctbLog2 = ctbLog2; minCbLog2 = 3; minTbLog2 = 2 }
        val p = Pps().apply {
            if (columns != null || uniform != null) {
                tilesEnabled = true
                if (uniform != null) {
                    numTileColumnsMinus1 = uniform.first - 1
                    numTileRowsMinus1 = uniform.second - 1
                } else {
                    uniformSpacing = false
                    numTileColumnsMinus1 = columns!!.size
                    numTileRowsMinus1 = rows!!.size
                    columnWidthMinus1 = IntArray(columns.size) { columns[it] - 1 }
                    rowHeightMinus1 = IntArray(rows.size) { rows[it] - 1 }
                }
            }
        }
        p.checkAgainst(s)
        return CtbLayout(s, p)
    }

    /** CtbAddrRsToTs as 6-7 writes it, loop for loop. */
    private fun literal(l: CtbLayout): IntArray = IntArray(l.sizeInCtbs) { ctbAddrRs ->
        val tbX = ctbAddrRs % l.widthInCtbs
        val tbY = ctbAddrRs / l.widthInCtbs
        var tileX = 0
        var tileY = 0
        for (i in 0 until l.colWidth.size) if (tbX >= l.colBd[i]) tileX = i
        for (j in 0 until l.rowHeight.size) if (tbY >= l.rowBd[j]) tileY = j
        var v = 0
        for (i in 0 until tileX) v += l.rowHeight[tileY] * l.colWidth[i]
        for (j in 0 until tileY) v += l.widthInCtbs * l.rowHeight[j]
        v + (tbY - l.rowBd[tileY]) * l.colWidth[tileX] + tbX - l.colBd[tileX]
    }

    @Test
    fun tileScanMatches6dash7() {
        val cases = listOf(
            layout(100, 60, 4, null, null),
            layout(200, 120, 4, null, null, uniform = 3 to 2),
            layout(208, 144, 4, intArrayOf(2, 7), intArrayOf(1)),
            layout(1000, 500, 6, null, null, uniform = 5 to 4),
        )
        for (l in cases) {
            assertEquals(literal(l).toList(), l.ctbAddrRsToTs.toList())
            for (ts in 0 until l.sizeInCtbs) assertEquals(ts, l.ctbAddrRsToTs[l.ctbAddrTsToRs[ts]])
        }
        // Uniform spacing of 7 CTB columns in 3 tiles, 6-3: 2, 2 and 3.
        val u = layout(112, 16, 4, null, null, uniform = 3 to 1)
        assertEquals(listOf(2, 2, 3), u.colWidth.toList())
        assertEquals(listOf(0, 0, 1, 1, 2, 2, 2), (0 until 7).map { u.tileId[u.ctbAddrRsToTs[it]] })
        // Explicit widths 2 and 7 of 13 columns leave 4 for the last.
        assertEquals(listOf(2, 7, 4), cases[2].colWidth.toList())
    }

    @Test
    fun zScanMatches6dash10() {
        val l = layout(48, 32, 4, null, null, uniform = 2 to 1)
        // Within one 16x16 CTB of 4x4 blocks: the z order.
        val z = (0 until 4).map { y -> (0 until 4).map { x -> l.minTbAddrZs(x, y) } }
        assertEquals(listOf(listOf(0, 1, 4, 5), listOf(2, 3, 6, 7), listOf(8, 9, 12, 13), listOf(10, 11, 14, 15)), z)
        for (y in 0 until 8) for (x in 0 until 12) {
            val ctbAddrRs = l.widthInCtbs * (y shr 2) + (x shr 2)
            var p = 0
            for (i in 0 until 2) {
                val m = 1 shl i
                p += (if (m and x != 0) m * m else 0) + (if (m and y != 0) 2 * m * m else 0)
            }
            assertEquals((l.ctbAddrRsToTs[ctbAddrRs] shl 4) + p, l.minTbAddrZs(x, y), "($x, $y)")
        }
    }
}
