package io.github.yuroyami.imagekodec.heic

/**
 * The motion of a picture's prediction blocks in units of 4x4 luma samples, the smallest a prediction
 * block is made of: MvLX, RefIdxLX and the picture order count of the reference, at unit * 2 + X, with
 * RefIdxLX -1 and a zero vector for a list the block does not use, so two blocks compare field by field.
 * The picture order count lets the deblocking filter tell the reference pictures apart across slices
 * (8.7.2.4, NOTE 1). [skip] holds cu_skip_flag, whose neighbours choose its context (9.3.4.2.2), and
 * [collocated] the motion later pictures read (8.5.3.2.8). It is made for the first P or B slice of a
 * picture, so an intra picture carries none.
 */
internal class MotionField(width: Int, height: Int) {
    val w4: Int = width shr 2
    private val units = w4 * (height shr 2)
    val mv: IntArray = IntArray(2 * units)
    val ref: ByteArray = ByteArray(2 * units)
    val refPoc: IntArray = IntArray(2 * units)
    val skip: ByteArray = ByteArray(units)
    val collocated: CollocatedMotion = CollocatedMotion((width + 15) shr 4, ((width + 15) shr 4) * ((height + 15) shr 4))

    companion object {
        /** A motion vector in one Int: the horizontal component in the low 16 bits, the vertical one above. */
        fun pack(x: Int, y: Int): Int = (x and 0xFFFF) or (y shl 16)
        fun x(mv: Int): Int = (mv shl 16) shr 16
        fun y(mv: Int): Int = mv shr 16
    }
}

/** PartMode, Table 7-10. */
internal object PartMode {
    const val PART_2Nx2N = 0
    const val PART_2NxN = 1
    const val PART_Nx2N = 2
    const val PART_NxN = 3
    const val PART_2NxnU = 4
    const val PART_2NxnD = 5
    const val PART_nLx2N = 6
    const val PART_nRx2N = 7
}

/**
 * The motion vectors and reference indices of a prediction unit, 8.5.3.2: merge mode with its spatial,
 * temporal, combined bi-predictive and zero candidates, and the motion vector prediction of AMVP with its
 * spatial and temporal predictors. The result of [merge] is left in [ref0], [ref1], [mv0] and [mv1].
 */
internal class MotionPredictor(private val d: PictureDecoder) {
    private val log2ParMrgLevel = d.pps.log2ParallelMergeLevel
    private val ctbLog2 = d.sps.ctbLog2

    var ref0: Int = -1
    var ref1: Int = -1
    var mv0: Int = 0
    var mv1: Int = 0

    // The merging candidate list: refIdxL0, refIdxL1, mvL0 and mvL1 of each, at most five (MaxNumMergeCand).
    private val candRef = IntArray(10)
    private val candMv = IntArray(10)
    private var count = 0

    /** The output of the temporal process of 8.5.3.2.8, valid when it returns true. */
    private var colMv = 0

    private lateinit var refs: RefLists
    private lateinit var field: MotionField
    private lateinit var slice: SliceHeader

    /** Starts a slice: its header, its lists and the picture's motion. */
    fun startSlice(h: SliceHeader, lists: RefLists, motion: MotionField) {
        slice = h
        refs = lists
        field = motion
    }

    private fun unit(x: Int, y: Int): Int = (y shr 2) * field.w4 + (x shr 2)

    /** 6.4.2 for the neighbour at ([xN], [yN]), with the step that makes a block coded in intra mode unavailable. */
    private fun availablePb(xCb: Int, yCb: Int, nCbS: Int, xPb: Int, yPb: Int, nPbW: Int, nPbH: Int, partIdx: Int, xN: Int, yN: Int): Boolean {
        val sameCb = xCb <= xN && yCb <= yN && xCb + nCbS > xN && yCb + nCbS > yN
        val available = if (!sameCb) {
            d.available(xPb, yPb, xN, yN)
        } else {
            // The second block of an NxN unit cannot use the third, which comes after it.
            !((nPbW shl 1) == nCbS && (nPbH shl 1) == nCbS && partIdx == 1 && yCb + nPbH <= yN && xCb + nPbW > xN)
        }
        return available && d.flags[unit(xN, yN)].toInt() and PictureDecoder.FLAG_INTRA == 0
    }

    /** The prediction units covering two locations have the same motion vectors and reference indices. */
    private fun sameMotion(a: Int, b: Int): Boolean {
        val m = field
        return m.ref[2 * a] == m.ref[2 * b] && m.ref[2 * a + 1] == m.ref[2 * b + 1] && m.mv[2 * a] == m.mv[2 * b] && m.mv[2 * a + 1] == m.mv[2 * b + 1]
    }

    private fun sameMergeRegion(xP: Int, yP: Int, xN: Int, yN: Int): Boolean =
        (xP shr log2ParMrgLevel) == (xN shr log2ParMrgLevel) && (yP shr log2ParMrgLevel) == (yN shr log2ParMrgLevel)

    private fun addUnit(u: Int) {
        val m = field
        candRef[2 * count] = m.ref[2 * u].toInt()
        candRef[2 * count + 1] = m.ref[2 * u + 1].toInt()
        candMv[2 * count] = m.mv[2 * u]
        candMv[2 * count + 1] = m.mv[2 * u + 1]
        count++
    }

    private fun add(r0: Int, m0: Int, r1: Int, m1: Int) {
        candRef[2 * count] = r0
        candRef[2 * count + 1] = r1
        candMv[2 * count] = m0
        candMv[2 * count + 1] = m1
        count++
    }

    /**
     * 8.5.3.2.2: the motion of the prediction block at ([xPb], [yPb]) of [nPbW] by [nPbH] in the coding
     * block at ([xCb], [yCb]) of [nCbS], its partition [partIdx] of [partMode], for merge_idx [mergeIdx].
     * The candidates after the one asked for are not derived, as none changes those before it.
     */
    fun merge(xCb: Int, yCb: Int, nCbS: Int, xPb: Int, yPb: Int, nPbW: Int, nPbH: Int, partIdx: Int, partMode: Int, mergeIdx: Int) {
        var xP = xPb
        var yP = yPb
        var w = nPbW
        var h = nPbH
        var pi = partIdx
        // 8-110 to 8-113: the units of an 8x8 coding block share the list of its 2Nx2N block.
        if (log2ParMrgLevel > 2 && nCbS == 8) {
            xP = xCb; yP = yCb; w = nCbS; h = nCbS; pi = 0
        }
        count = 0
        // 8.5.3.2.3: A1, B1, B0, A0 and B2.
        val xA1 = xP - 1
        val yA1 = yP + h - 1
        val availableA1 = availablePb(xCb, yCb, nCbS, xP, yP, w, h, pi, xA1, yA1) && !sameMergeRegion(xP, yP, xA1, yA1) &&
            !(pi == 1 && (partMode == PartMode.PART_Nx2N || partMode == PartMode.PART_nLx2N || partMode == PartMode.PART_nRx2N))
        val uA1 = unit(xA1, yA1)
        if (availableA1) addUnit(uA1)
        val xB1 = xP + w - 1
        val yB1 = yP - 1
        val availableB1 = availablePb(xCb, yCb, nCbS, xP, yP, w, h, pi, xB1, yB1) && !sameMergeRegion(xP, yP, xB1, yB1) &&
            !(pi == 1 && (partMode == PartMode.PART_2NxN || partMode == PartMode.PART_2NxnU || partMode == PartMode.PART_2NxnD))
        val uB1 = unit(xB1, yB1)
        if (availableB1 && !(availableA1 && sameMotion(uA1, uB1))) addUnit(uB1)
        val xB0 = xP + w
        val yB0 = yP - 1
        val availableB0 = availablePb(xCb, yCb, nCbS, xP, yP, w, h, pi, xB0, yB0) && !sameMergeRegion(xP, yP, xB0, yB0)
        if (availableB0 && !(availableB1 && sameMotion(uB1, unit(xB0, yB0)))) addUnit(unit(xB0, yB0))
        val xA0 = xP - 1
        val yA0 = yP + h
        val availableA0 = availablePb(xCb, yCb, nCbS, xP, yP, w, h, pi, xA0, yA0) && !sameMergeRegion(xP, yP, xA0, yA0)
        if (availableA0 && !(availableA1 && sameMotion(uA1, unit(xA0, yA0)))) addUnit(unit(xA0, yA0))
        val xB2 = xP - 1
        val yB2 = yP - 1
        if (count != 4 && availablePb(xCb, yCb, nCbS, xP, yP, w, h, pi, xB2, yB2) && !sameMergeRegion(xP, yP, xB2, yB2)) {
            val uB2 = unit(xB2, yB2)
            if (!(availableA1 && sameMotion(uA1, uB2)) && !(availableB1 && sameMotion(uB1, uB2))) addUnit(uB2)
        }
        val b = slice.sliceType == SliceType.B
        if (mergeIdx >= count) {
            // Steps 2 to 4: the temporal candidate, with reference index 0 in each list.
            val l0 = temporal(xP, yP, w, h, 0, 0)
            val m0 = colMv
            val l1 = b && temporal(xP, yP, w, h, 0, 1)
            if (l0 || l1) add(if (l0) 0 else -1, if (l0) m0 else 0, if (l1) 0 else -1, if (l1) colMv else 0)
        }
        val max = slice.maxNumMergeCand
        if (mergeIdx >= count && b) combined(max)
        if (mergeIdx >= count) zeros(max)
        ref0 = candRef[2 * mergeIdx]
        ref1 = candRef[2 * mergeIdx + 1]
        mv0 = candMv[2 * mergeIdx]
        mv1 = candMv[2 * mergeIdx + 1]
        // 8-124 and 8-125: whole samples under use_integer_mv_flag, or from the current picture.
        if (slice.useIntegerMv || (ref0 >= 0 && refs.isCurrent[0][ref0])) {
            mv0 = MotionField.pack((MotionField.x(mv0) shr 2) shl 2, (MotionField.y(mv0) shr 2) shl 2)
        }
        if (slice.useIntegerMv || (ref1 >= 0 && refs.isCurrent[1][ref1])) {
            mv1 = MotionField.pack((MotionField.x(mv1) shr 2) shl 2, (MotionField.y(mv1) shr 2) shl 2)
        }
        // 8-126 and 8-127: an 8x4 or 4x8 block predicts from one list only.
        if (ref0 >= 0 && ref1 >= 0 && nPbW + nPbH == 12) {
            ref1 = -1
            mv1 = 0
        }
    }

    /** 8.5.3.2.4, with l0CandIdx and l1CandIdx of Table 8-7. */
    private fun combined(max: Int) {
        val orig = count
        if (orig <= 1 || orig >= max) return
        var combIdx = 0
        while (true) {
            val l0 = COMB_L0[combIdx]
            val l1 = COMB_L1[combIdx]
            val r0 = candRef[2 * l0]
            val r1 = candRef[2 * l1 + 1]
            val m0 = candMv[2 * l0]
            val m1 = candMv[2 * l1 + 1]
            if (r0 >= 0 && r1 >= 0 && (refs.poc(0, r0) != refs.poc(1, r1) || m0 != m1)) add(r0, m0, r1, m1)
            combIdx++
            if (combIdx == orig * (orig - 1) || count == max) return
        }
    }

    /** 8.5.3.2.5. */
    private fun zeros(max: Int) {
        val b = slice.sliceType == SliceType.B
        val numRefIdx = if (b) minOf(slice.numRefIdxL0ActiveMinus1, slice.numRefIdxL1ActiveMinus1) + 1 else slice.numRefIdxL0ActiveMinus1 + 1
        var zeroIdx = 0
        while (count < max) {
            val r = if (zeroIdx < numRefIdx) zeroIdx else 0
            add(r, 0, if (b) r else -1, 0)
            zeroIdx++
        }
    }

    /**
     * 8.5.3.2.6 and 8.5.3.2.7: the predictor mvpLX of list [x] for reference index [refIdx], the entry
     * [mvpFlag] of mvpListLX, packed.
     */
    fun predictor(x: Int, refIdx: Int, xCb: Int, yCb: Int, nCbS: Int, xPb: Int, yPb: Int, nPbW: Int, nPbH: Int, partIdx: Int, mvpFlag: Int): Int {
        val y = 1 - x
        val m = field
        val targetPoc = refs.poc(x, refIdx)
        val targetLt = refs.longTerm[x][refIdx]
        val currPoc = refs.currentPoc
        // A0 and A1.
        val xA0 = xPb - 1
        val yA0 = yPb + nPbH
        val xA1 = xA0
        val yA1 = yA0 - 1
        val availableA0 = availablePb(xCb, yCb, nCbS, xPb, yPb, nPbW, nPbH, partIdx, xA0, yA0)
        val availableA1 = availablePb(xCb, yCb, nCbS, xPb, yPb, nPbW, nPbH, partIdx, xA1, yA1)
        val isScaled = availableA0 || availableA1
        var foundA = false
        var mvA = 0
        for (k in 0..1) {
            val ok = if (k == 0) availableA0 else availableA1
            if (!ok || foundA) continue
            val u = if (k == 0) unit(xA0, yA0) else unit(xA1, yA1)
            val rx = m.ref[2 * u + x].toInt()
            val ry = m.ref[2 * u + y].toInt()
            if (rx >= 0 && refs.poc(x, rx) == targetPoc) {
                foundA = true; mvA = m.mv[2 * u + x]
            } else if (ry >= 0 && refs.poc(y, ry) == targetPoc) {
                foundA = true; mvA = m.mv[2 * u + y]
            }
        }
        if (!foundA) for (k in 0..1) {
            val ok = if (k == 0) availableA0 else availableA1
            if (!ok || foundA) continue
            val u = if (k == 0) unit(xA0, yA0) else unit(xA1, yA1)
            val rx = m.ref[2 * u + x].toInt()
            val ry = m.ref[2 * u + y].toInt()
            var list = -1
            if (rx >= 0 && refs.longTerm[x][rx] == targetLt) list = x else if (ry >= 0 && refs.longTerm[y][ry] == targetLt) list = y
            if (list >= 0) {
                foundA = true
                val r = m.ref[2 * u + list].toInt()
                mvA = m.mv[2 * u + list]
                if (refs.poc(list, r) != targetPoc && !refs.longTerm[list][r] && !targetLt) {
                    mvA = scale(mvA, currPoc - refs.poc(list, r), currPoc - targetPoc)
                }
            }
        }
        // B0, B1 and B2.
        val xs = intArrayOf(xPb + nPbW, xPb + nPbW - 1, xPb - 1)
        val ys = yPb - 1
        val availableB = BooleanArray(3) { k -> availablePb(xCb, yCb, nCbS, xPb, yPb, nPbW, nPbH, partIdx, xs[k], ys) }
        var foundB = false
        var mvB = 0
        for (k in 0..2) {
            if (!availableB[k] || foundB) continue
            val u = unit(xs[k], ys)
            val rx = m.ref[2 * u + x].toInt()
            val ry = m.ref[2 * u + y].toInt()
            if (rx >= 0 && refs.poc(x, rx) == targetPoc) {
                foundB = true; mvB = m.mv[2 * u + x]
            } else if (ry >= 0 && refs.poc(y, ry) == targetPoc) {
                foundB = true; mvB = m.mv[2 * u + y]
            }
        }
        if (!isScaled && foundB) {
            // 8-186.
            foundA = true
            mvA = mvB
        }
        if (!isScaled) {
            foundB = false
            for (k in 0..2) {
                if (!availableB[k] || foundB) continue
                val u = unit(xs[k], ys)
                val rx = m.ref[2 * u + x].toInt()
                val ry = m.ref[2 * u + y].toInt()
                var list = -1
                if (rx >= 0 && refs.longTerm[x][rx] == targetLt) list = x else if (ry >= 0 && refs.longTerm[y][ry] == targetLt) list = y
                if (list >= 0) {
                    foundB = true
                    val r = m.ref[2 * u + list].toInt()
                    mvB = m.mv[2 * u + list]
                    if (refs.poc(list, r) != targetPoc && !refs.longTerm[list][r] && !targetLt) {
                        mvB = scale(mvB, currPoc - refs.poc(list, r), currPoc - targetPoc)
                    }
                }
            }
        }
        // 8-170.
        var n = 0
        var first = 0
        var second = 0
        if (foundA) {
            first = mvA; n = 1
            if (foundB && mvA != mvB) { second = mvB; n = 2 }
        } else if (foundB) {
            first = mvB; n = 1
        }
        if (n < 2 && mvpFlag >= n) {
            // The temporal predictor only fills the list, so it is needed only when the flag points past it.
            if (!(foundA && foundB && mvA != mvB) && temporal(xPb, yPb, nPbW, nPbH, refIdx, x)) {
                if (n == 0) first = colMv else second = colMv
                n++
            }
        }
        return when {
            mvpFlag == 0 -> if (n > 0) first else 0
            n > 1 -> second
            else -> 0
        }
    }

    /**
     * 8.5.3.2.8 and 8.5.3.2.9: the temporal predictor of list [x] for [refIdx], from the bottom right
     * corner of the block or its centre in ColPic. Leaves the vector in [colMv].
     */
    private fun temporal(xPb: Int, yPb: Int, nPbW: Int, nPbH: Int, refIdx: Int, x: Int): Boolean {
        val col = refs.colPic ?: return false
        val motion = col.motion.getOrNull(d.colourPlane) ?: return false
        val xBr = xPb + nPbW
        val yBr = yPb + nPbH
        if ((yPb shr ctbLog2) == (yBr shr ctbLog2) && yBr < d.height && xBr < d.width) {
            if (collocated(col, motion, xBr, yBr, refIdx, x)) return true
        }
        return collocated(col, motion, xPb + (nPbW shr 1), yPb + (nPbH shr 1), refIdx, x)
    }

    /** 8.5.3.2.9 for the block that covers ( ( [xCol] >> 4 ) << 4, ( [yCol] >> 4 ) << 4 ) in [col]. */
    private fun collocated(col: DecodedPicture, motion: CollocatedMotion, xCol: Int, yCol: Int, refIdx: Int, x: Int): Boolean {
        val e = (yCol shr 4) * motion.width16 + (xCol shr 4)
        val f = motion.flags[e].toInt()
        // A block coded in intra mode.
        if (f and 3 == 0) return false
        val listCol = when {
            f and 1 == 0 -> 1
            f and 2 == 0 -> 0
            refs.noBackwardPred -> x
            // N is the value of collocated_from_l0_flag.
            else -> if (slice.collocatedFromL0) 1 else 0
        }
        val colLt = f and (4 shl listCol) != 0
        if (refs.longTerm[x][refIdx] != colLt) return false
        val mvCol = motion.mv[2 * e + listCol]
        val colPocDiff = col.poc - motion.refPoc[2 * e + listCol]
        val currPocDiff = refs.currentPoc - refs.poc(x, refIdx)
        colMv = if (refs.longTerm[x][refIdx] || colPocDiff == currPocDiff || colPocDiff == 0) mvCol else scale(mvCol, colPocDiff, currPocDiff)
        return true
    }

    /**
     * Writes the motion of the block at ([xPb], [yPb]) of [w] by [h] into the picture's field, and the
     * corners of the 16x16 grid it covers into the motion kept for later pictures.
     */
    fun store(xPb: Int, yPb: Int, w: Int, h: Int, r0: Int, m0: Int, r1: Int, m1: Int) {
        val m = field
        val v0 = if (r0 >= 0) m0 else 0
        val v1 = if (r1 >= 0) m1 else 0
        val p0 = if (r0 >= 0) refs.poc(0, r0) else 0
        val p1 = if (r1 >= 0) refs.poc(1, r1) else 0
        for (y in (yPb shr 2) until ((yPb + h) shr 2)) {
            for (xu in (xPb shr 2) until ((xPb + w) shr 2)) {
                val u = y * m.w4 + xu
                m.ref[2 * u] = r0.toByte()
                m.ref[2 * u + 1] = r1.toByte()
                m.mv[2 * u] = v0
                m.mv[2 * u + 1] = v1
                m.refPoc[2 * u] = p0
                m.refPoc[2 * u + 1] = p1
            }
        }
        val c = m.collocated
        val flags = (if (r0 >= 0) 1 else 0) or (if (r1 >= 0) 2 else 0) or
            (if (r0 >= 0 && refs.longTerm[0][r0]) 4 else 0) or (if (r1 >= 0 && refs.longTerm[1][r1]) 8 else 0)
        var y = (yPb + 15) and 15.inv()
        while (y < yPb + h) {
            var x = (xPb + 15) and 15.inv()
            while (x < xPb + w) {
                val e = (y shr 4) * c.width16 + (x shr 4)
                c.flags[e] = flags.toByte()
                c.mv[2 * e] = v0
                c.mv[2 * e + 1] = v1
                c.refPoc[2 * e] = p0
                c.refPoc[2 * e + 1] = p1
                x += 16
            }
            y += 16
        }
    }

    companion object {
        /** Table 8-7. */
        private val COMB_L0 = intArrayOf(0, 1, 0, 2, 1, 2, 0, 3, 1, 3, 2, 3)
        private val COMB_L1 = intArrayOf(1, 0, 2, 0, 2, 1, 3, 0, 3, 1, 3, 2)

        /** 8-179 to 8-181 (and 8-205 to 8-207) for each component of [mv], with td and tb clipped to 8 bits. */
        fun scale(mv: Int, tdPoc: Int, tbPoc: Int): Int {
            val td = clip(-128, 127, tdPoc)
            val tb = clip(-128, 127, tbPoc)
            if (td == 0) return mv
            val tx = (16384 + (abs(td) shr 1)) / td
            val factor = clip(-4096, 4095, (tb * tx + 32) shr 6)
            return MotionField.pack(scaleComponent(factor, MotionField.x(mv)), scaleComponent(factor, MotionField.y(mv)))
        }

        private fun scaleComponent(factor: Int, v: Int): Int {
            val p = factor * v
            val s = (abs(p) + 127) shr 8
            return clip(-32768, 32767, if (p < 0) -s else s)
        }

        private fun clip(lo: Int, hi: Int, v: Int): Int = if (v < lo) lo else if (v > hi) hi else v
        private fun abs(v: Int): Int = if (v < 0) -v else v
    }
}
