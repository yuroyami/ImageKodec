package io.github.yuroyami.imagekodec.heic

/**
 * The tiles of a picture and the conversion between CTB raster scan and tile scan, 6.5.1, for the
 * active [sps] and [pps]. The PPS must have passed [Pps.checkAgainst] for this SPS.
 */
internal class CtbLayout(sps: Sps, pps: Pps) {
    val widthInCtbs: Int = sps.picWidthInCtbs
    val heightInCtbs: Int = sps.picHeightInCtbs
    val sizeInCtbs: Int = widthInCtbs * heightInCtbs
    val ctbLog2: Int = sps.ctbLog2
    val minTbLog2: Int = sps.minTbLog2

    /** colWidth and rowHeight, 6-3 and 6-4, in CTBs. */
    val colWidth: IntArray
    val rowHeight: IntArray

    /** colBd and rowBd, 6-5 and 6-6. */
    val colBd: IntArray
    val rowBd: IntArray

    /** CtbAddrRsToTs, CtbAddrTsToRs and TileId, 6-7 to 6-9. */
    val ctbAddrRsToTs: IntArray = IntArray(sizeInCtbs)
    val ctbAddrTsToRs: IntArray = IntArray(sizeInCtbs)
    val tileId: IntArray = IntArray(sizeInCtbs)

    init {
        val columns = if (pps.tilesEnabled) pps.numTileColumnsMinus1 + 1 else 1
        val rows = if (pps.tilesEnabled) pps.numTileRowsMinus1 + 1 else 1
        colWidth = spans(columns, widthInCtbs, pps.uniformSpacing, pps.columnWidthMinus1)
        rowHeight = spans(rows, heightInCtbs, pps.uniformSpacing, pps.rowHeightMinus1)
        colBd = IntArray(columns + 1).also { for (i in 0 until columns) it[i + 1] = it[i] + colWidth[i] }
        rowBd = IntArray(rows + 1).also { for (j in 0 until rows) it[j + 1] = it[j] + rowHeight[j] }
        // 6-7 walks every tile for every CTB. Walking the tiles in tile scan gives the same addresses,
        // each tile's CTBs following the CTBs of the tiles before it, in raster order within the tile.
        var ts = 0
        for (j in 0 until rows) for (i in 0 until columns) {
            val tile = j * columns + i
            for (y in rowBd[j] until rowBd[j + 1]) for (x in colBd[i] until colBd[i + 1]) {
                val rs = y * widthInCtbs + x
                ctbAddrRsToTs[rs] = ts
                ctbAddrTsToRs[ts] = rs
                tileId[ts] = tile
                ts++
            }
        }
    }

    /**
     * MinTbAddrZs[ x ][ y ], 6-10: the z-scan address of the minimum transform block at ([x], [y]) in
     * units of minimum transform blocks. Computed on demand, as the array would be as large as the picture.
     */
    fun minTbAddrZs(x: Int, y: Int): Int {
        val shift = ctbLog2 - minTbLog2
        val ctbAddrRs = widthInCtbs * (y shr shift) + (x shr shift)
        var p = 0
        // The interleaved bits of x and y within the CTB, x in the even bits: the loop of 6-10.
        for (i in 0 until shift) {
            val m = 1 shl i
            if (x and m != 0) p += m * m
            if (y and m != 0) p += 2 * m * m
        }
        return (ctbAddrRsToTs[ctbAddrRs] shl (shift * 2)) + p
    }

    private companion object {
        fun spans(count: Int, total: Int, uniform: Boolean, minus1: IntArray): IntArray = IntArray(count).also {
            if (uniform) {
                for (i in 0 until count) it[i] = ((i + 1) * total) / count - (i * total) / count
            } else {
                it[count - 1] = total
                for (i in 0 until count - 1) {
                    it[i] = minus1[i] + 1
                    it[count - 1] -= it[i]
                }
            }
        }
    }
}

/**
 * The scan orders of 6.5.3 to 6.5.5, ScanOrder[ log2BlockSize ][ scanIdx ] for block sizes 1 to 8
 * (7.4.3.2.1). Each entry packs the horizontal component in the low byte and the vertical one in the
 * next: x = e and 0xFF, y = e shr 8.
 */
internal object HevcScan {
    const val DIAGONAL = 0
    const val HORIZONTAL = 1
    const val VERTICAL = 2

    private val orders: Array<Array<IntArray>> = Array(4) { log2 ->
        val size = 1 shl log2
        arrayOf(diagonal(size), horizontal(size), vertical(size))
    }

    /** ScanOrder[ [log2BlockSize] ][ [scanIdx] ], for log2BlockSize 0 to 3 and scanIdx 0 to 2. */
    fun order(log2BlockSize: Int, scanIdx: Int): IntArray = orders[log2BlockSize][scanIdx]

    /** The up-right diagonal scan of 6.5.3, 6-11. */
    private fun diagonal(blkSize: Int): IntArray {
        val out = IntArray(blkSize * blkSize)
        var i = 0
        var x = 0
        var y = 0
        while (i < blkSize * blkSize) {
            while (y >= 0) {
                if (x < blkSize && y < blkSize) out[i++] = x or (y shl 8)
                y--
                x++
            }
            y = x
            x = 0
        }
        return out
    }

    /** The horizontal scan of 6.5.4, 6-12. */
    private fun horizontal(blkSize: Int) = IntArray(blkSize * blkSize) { i -> (i % blkSize) or ((i / blkSize) shl 8) }

    /** The vertical scan of 6.5.5, 6-13. */
    private fun vertical(blkSize: Int) = IntArray(blkSize * blkSize) { i -> (i / blkSize) or ((i % blkSize) shl 8) }
}
