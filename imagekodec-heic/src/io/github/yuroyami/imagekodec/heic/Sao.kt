package io.github.yuroyami.imagekodec.heic

/**
 * Sample adaptive offset, 8.7.3, one CTB row at a time on the deblocked picture of [d]. SAO reads only
 * deblocked samples, so each row is first copied, with the deblocked line above and below it, into a
 * band buffer per component; the line above is the one kept from the row before, which SAO has since
 * changed in the picture.
 */
internal class SaoFilter(private val d: PictureDecoder) {
    private val sps = d.sps
    private val pps = d.pps
    private val params = d.sao!!
    private val ctbLog2 = sps.ctbLog2
    private val widthInCtbs = sps.picWidthInCtbs
    private val heightInCtbs = sps.picHeightInCtbs
    private val components = if (d.chromaArrayType == 0) 1 else 3
    private val w4 = d.width shr 2

    /** 8.7.3.2: units whose samples keep their deblocked values. */
    private val keepMask = PictureDecoder.FLAG_BYPASS or (if (sps.pcmLoopFilterDisabled) PictureDecoder.FLAG_PCM else 0)
    private val mayKeep = pps.transquantBypassEnabled || (sps.pcmEnabled && sps.pcmLoopFilterDisabled)

    private val planeWidth = IntArray(components) { c -> if (c == 0) d.width else d.width shr d.subWShift }
    private val planeHeight = IntArray(components) { c -> if (c == 0) d.height else d.height shr d.subHShift }
    private val ctbWidth = IntArray(components) { c -> if (c == 0) 1 shl ctbLog2 else (1 shl ctbLog2) shr d.subWShift }
    private val ctbHeight = IntArray(components) { c -> if (c == 0) 1 shl ctbLog2 else (1 shl ctbLog2) shr d.subHShift }

    /** Per component: the row's lines, the deblocked line above them first and the one below them last. */
    private val band = Array(components) { c -> ShortArray((ctbHeight[c] + 2) * planeWidth[c]) }

    /** Per component: the deblocked last line of the row before. */
    private val lastLine = Array(components) { c -> ShortArray(planeWidth[c]) }

    /** For the CTB being filtered and its eight neighbours, row by row: whether SAO may not read that CTB. */
    private val blocked = BooleanArray(9)
    private val edgeOffset = IntArray(5)
    private val bandTable = IntArray(32)

    fun filterRow(ry: Int) {
        for (c in 0 until components) filterComponent(c, ry)
    }

    private fun filterComponent(c: Int, ry: Int) {
        val plane = d.planes[c]
        val stride = d.strides[c]
        val w = planeWidth[c]
        val y0 = ry * ctbHeight[c]
        val y1 = minOf(y0 + ctbHeight[c], planeHeight[c])
        val lines = y1 - y0
        val buf = band[c]
        var used = false
        for (rx in 0 until widthInCtbs) if (params[(ry * widthInCtbs + rx) * 18 + c * 6] != 0) used = true
        if (used) {
            if (ry > 0) lastLine[c].copyInto(buf, 0, 0, w)
            for (j in 0 until lines) plane.copyInto(buf, (j + 1) * w, (y0 + j) * stride, (y0 + j) * stride + w)
            if (y1 < planeHeight[c]) plane.copyInto(buf, (lines + 1) * w, y1 * stride, y1 * stride + w)
        }
        // The next row reads this row's last line as deblocked, before SAO changes it below.
        plane.copyInto(lastLine[c], 0, (y1 - 1) * stride, (y1 - 1) * stride + w)
        if (!used) return
        val bitDepth = if (c == 0) sps.bitDepthY else sps.bitDepthC
        val scale = if (c == 0) pps.log2SaoOffsetScaleLuma else pps.log2SaoOffsetScaleChroma
        for (rx in 0 until widthInCtbs) {
            val rs = ry * widthInCtbs + rx
            val o = rs * 18 + c * 6
            val type = params[o]
            if (type == 0) continue
            val x0 = rx * ctbWidth[c]
            val cw = minOf(ctbWidth[c], w - x0)
            if (type == 1) bandOffset(plane, stride, buf, w, x0, y0, cw, lines, o, scale, bitDepth)
            else edgeOffset(plane, stride, buf, w, rs, x0, y0, cw, lines, o, scale, bitDepth)
            if (mayKeep) keep(c, plane, stride, buf, w, x0, y0, cw, lines)
        }
    }

    /** 8-414 and 8-415. */
    private fun bandOffset(plane: ShortArray, stride: Int, buf: ShortArray, w: Int, x0: Int, y0: Int, cw: Int, lines: Int, o: Int, scale: Int, bitDepth: Int) {
        val table = bandTable
        table.fill(0)
        val left = params[o + 1]
        for (k in 0 until 4) table[(k + left) and 31] = (params[o + 2 + k] shl scale)
        // A band with offset 0 changes nothing, so the table holds the offsets themselves.
        val shift = bitDepth - 5
        val max = (1 shl bitDepth) - 1
        for (j in 0 until lines) {
            val src = (j + 1) * w + x0
            val dst = (y0 + j) * stride + x0
            for (i in 0 until cw) {
                val v = buf[src + i].toInt()
                val off = table[v shr shift]
                if (off != 0) {
                    val r = v + off
                    plane[dst + i] = (if (r < 0) 0 else if (r > max) max else r).toShort()
                }
            }
        }
    }

    /** 8-409 to 8-413, with the neighbours of Table 8-13. */
    private fun edgeOffset(plane: ShortArray, stride: Int, buf: ShortArray, w: Int, rs: Int, x0: Int, y0: Int, cw: Int, lines: Int, o: Int, scale: Int, bitDepth: Int) {
        val eoClass = params[o + 1]
        val h0 = H_POS[eoClass]
        val v0 = V_POS[eoClass]
        // Table 8-13: the second neighbour mirrors the first.
        val n0 = v0 * w + h0
        val n1 = -n0
        // 8-412 reorders edgeIdx 0, 1 and 2 to 1, 2 and 0; SaoOffsetVal[ 0 ] is 0.
        val eo = edgeOffset
        eo[0] = params[o + 2] shl scale
        eo[1] = params[o + 3] shl scale
        eo[2] = 0
        eo[3] = params[o + 4] shl scale
        eo[4] = params[o + 5] shl scale
        neighbours(rs)
        val max = (1 shl bitDepth) - 1
        for (j in 0 until lines) {
            // The CTB row each neighbour of this line falls in: 0 above, 1 the same, 2 below.
            val r0 = if (j + v0 < 0) 0 else if (j + v0 >= lines) 2 else 1
            val r1 = if (j - v0 < 0) 0 else if (j - v0 >= lines) 2 else 1
            val src = (j + 1) * w + x0
            val dst = (y0 + j) * stride + x0 - src
            // Between the first and the last column both neighbours lie in this CTB's column.
            if (!blocked[r0 * 3 + 1] && !blocked[r1 * 3 + 1]) {
                for (k in src + 1 until src + cw - 1) sample(plane, buf, k, dst, n0, n1, max)
            }
            // The first and the last column may reach into the CTBs to the left and right; cw is at least 4.
            for (end in 0..1) {
                val i = end * (cw - 1)
                val c0 = if (i + h0 < 0) 0 else if (i + h0 >= cw) 2 else 1
                val c1 = if (i - h0 < 0) 0 else if (i - h0 >= cw) 2 else 1
                if (!blocked[r0 * 3 + c0] && !blocked[r1 * 3 + c1]) sample(plane, buf, src + i, dst, n0, n1, max)
            }
        }
    }

    /** 8-411 to 8-413 for the band sample at [k], written at [k] + [dst] of [plane]. */
    private fun sample(plane: ShortArray, buf: ShortArray, k: Int, dst: Int, n0: Int, n1: Int, max: Int) {
        val v = buf[k].toInt()
        val a = buf[k + n0].toInt()
        val b = buf[k + n1].toInt()
        val off = edgeOffset[2 + (if (v > a) 1 else if (v < a) -1 else 0) + (if (v > b) 1 else if (v < b) -1 else 0)]
        if (off != 0) {
            val r = v + off
            plane[k + dst] = (if (r < 0) 0 else if (r > max) max else r).toShort()
        }
    }

    /**
     * Fills [blocked] for the CTB [rs]: a neighbouring CTB outside the picture, in another tile without
     * loop_filter_across_tiles_enabled_flag, or in another slice whose later one, in decoding order,
     * has slice_loop_filter_across_slices_enabled_flag 0 (8.7.3.2). Slices hold whole CTBs, so the
     * MinTbAddrZs comparison of the samples is that of their CTBs.
     */
    private fun neighbours(rs: Int) {
        val rx = rs % widthInCtbs
        val ry = rs / widthInCtbs
        val ts = d.layout.ctbAddrRsToTs
        for (dy in -1..1) for (dx in -1..1) {
            val x = rx + dx
            val y = ry + dy
            blocked[(dy + 1) * 3 + dx + 1] = if (x < 0 || y < 0 || x >= widthInCtbs || y >= heightInCtbs) {
                true
            } else {
                val n = y * widthInCtbs + x
                if (n == rs) {
                    false
                } else if (!pps.loopFilterAcrossTiles && d.tileIdRs[n] != d.tileIdRs[rs]) {
                    true
                } else if (d.ctbSlice[n] != d.ctbSlice[rs]) {
                    val later = if (ts[n] > ts[rs]) n else rs
                    !d.ctbHeader[later]!!.loopFilterAcrossSlices
                } else {
                    false
                }
            }
        }
    }

    /** Puts back the deblocked samples of the PCM and bypass blocks of a CTB, which SAO leaves (8.7.3.2). */
    private fun keep(c: Int, plane: ShortArray, stride: Int, buf: ShortArray, w: Int, x0: Int, y0: Int, cw: Int, lines: Int) {
        val sw = if (c == 0) 0 else d.subWShift
        val sh = if (c == 0) 0 else d.subHShift
        for (j in 0 until lines) {
            val yl = (y0 + j) shl sh
            for (i in 0 until cw) {
                val xl = (x0 + i) shl sw
                if (d.flags[(yl shr 2) * w4 + (xl shr 2)].toInt() and keepMask != 0) plane[(y0 + j) * stride + x0 + i] = buf[(j + 1) * w + x0 + i]
            }
        }
    }

    private companion object {
        /** Table 8-13: hPos[ 0 ] and vPos[ 0 ] of each SaoEoClass; hPos[ 1 ] and vPos[ 1 ] are their negatives. */
        val H_POS = intArrayOf(-1, 0, -1, 1)
        val V_POS = intArrayOf(0, -1, -1, -1)
    }
}
