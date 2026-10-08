package io.github.yuroyami.imagekodec.codec.avif

import io.github.yuroyami.imagekodec.codec.avif.Av1.INTRA_FRAME
import io.github.yuroyami.imagekodec.codec.avif.Av1.MAX_REF_MV_STACK_SIZE
import kotlin.math.abs

/**
 * The motion vector prediction processes of specification section 7.10.2 for one block of
 * [d]: find_mv_stack, which builds RefStackMv and the contexts the inter mode syntax reads,
 * has_overlappable_candidates, and find_warp_samples (section 7.10.4).
 *
 * RefStackMv[ idx ][ list ][ comp ] is [stack] at idx * 4 + list * 2 + comp, and a motion
 * vector pair is held the same way in [globalMvs] and the extra search's arrays.
 */
internal class Av1MvStack(private val d: Av1FrameDecoder) {
    val stack = IntArray(MAX_REF_MV_STACK_SIZE * 4)
    private val weights = IntArray(MAX_REF_MV_STACK_SIZE)
    var numMvFound = 0
    private var newMvCount = 0
    val globalMvs = IntArray(4)
    private var foundMatch = false
    private var closeMatches = 0
    private var totalMatches = 0
    var zeroMvContext = 0
    var newMvContext = 0
    var refMvContext = 0
    val drlCtxStack = IntArray(MAX_REF_MV_STACK_SIZE)

    private val refIdMvs = IntArray(8)
    private val refIdCount = IntArray(2)
    private val refDiffMvs = IntArray(8)
    private val refDiffCount = IntArray(2)
    private val combinedMvs = IntArray(8)
    private val cand = IntArray(4)

    /** NumSamples and CandList of find_warp_samples, four values a sample. */
    var numSamples = 0
    private var numSamplesScanned = 0
    val candList = IntArray(LEAST_SQUARES_SAMPLES_MAX * 4)

    /** find_mv_stack( isCompound ). */
    fun find(isCompound: Boolean) {
        val bw4 = Av1.num4x4Wide[d.miSize]
        val bh4 = Av1.num4x4High[d.miSize]
        numMvFound = 0
        newMvCount = 0
        setupGlobalMv(0)
        if (isCompound) setupGlobalMv(1)
        foundMatch = false
        scanRow(-1, isCompound)
        var foundAboveMatch = foundMatch
        foundMatch = false
        scanCol(-1, isCompound)
        var foundLeftMatch = foundMatch
        foundMatch = false
        if (maxOf(bw4, bh4) <= 16) scanPoint(-1, bw4, isCompound)
        if (foundMatch) foundAboveMatch = true
        closeMatches = (if (foundAboveMatch) 1 else 0) + (if (foundLeftMatch) 1 else 0)
        val numNearest = numMvFound
        val numNew = newMvCount
        for (idx in 0 until numNearest) weights[idx] += REF_CAT_LEVEL
        zeroMvContext = 0
        if (d.fh.useRefFrameMvs) temporalScan(isCompound)
        scanPoint(-1, -1, isCompound)
        if (foundMatch) foundAboveMatch = true
        foundMatch = false
        scanRow(-3, isCompound)
        if (foundMatch) foundAboveMatch = true
        foundMatch = false
        scanCol(-3, isCompound)
        if (foundMatch) foundLeftMatch = true
        foundMatch = false
        if (bh4 > 1) scanRow(-5, isCompound)
        if (foundMatch) foundAboveMatch = true
        foundMatch = false
        if (bw4 > 1) scanCol(-5, isCompound)
        if (foundMatch) foundLeftMatch = true
        totalMatches = (if (foundAboveMatch) 1 else 0) + (if (foundLeftMatch) 1 else 0)
        sort(0, numNearest)
        sort(numNearest, numMvFound)
        if (numMvFound < 2) extraSearch(isCompound)
        contextAndClamping(isCompound, numNew)
    }

    /** The setup global mv process for [refList], into [globalMvs]. */
    private fun setupGlobalMv(refList: Int) {
        val fh = d.fh
        val ref = d.refFrame[refList]
        var row = 0
        var col = 0
        val typ = if (ref != INTRA_FRAME) fh.gmType[ref] else Av1.IDENTITY
        if (ref == INTRA_FRAME || typ == Av1.IDENTITY) {
            row = 0
            col = 0
        } else if (typ == Av1.TRANSLATION) {
            row = fh.gmParams[ref][0] shr (Av1.WARPEDMODEL_PREC_BITS - 3)
            col = fh.gmParams[ref][1] shr (Av1.WARPEDMODEL_PREC_BITS - 3)
        } else {
            val gm = fh.gmParams[ref]
            val x = (d.miCol * Av1.MI_SIZE + Av1.blockWidth[d.miSize] / 2 - 1).toLong()
            val y = (d.miRow * Av1.MI_SIZE + Av1.blockHeight[d.miSize] / 2 - 1).toLong()
            val xc = (gm[2] - (1 shl Av1.WARPEDMODEL_PREC_BITS)) * x + gm[3] * y + gm[0]
            val yc = gm[4] * x + (gm[5] - (1 shl Av1.WARPEDMODEL_PREC_BITS)) * y + gm[1]
            if (fh.allowHighPrecisionMv) {
                row = round2Signed(yc, Av1.WARPEDMODEL_PREC_BITS - 3).toInt()
                col = round2Signed(xc, Av1.WARPEDMODEL_PREC_BITS - 3).toInt()
            } else {
                row = round2Signed(yc, Av1.WARPEDMODEL_PREC_BITS - 2).toInt() * 2
                col = round2Signed(xc, Av1.WARPEDMODEL_PREC_BITS - 2).toInt() * 2
            }
        }
        globalMvs[refList * 2] = lowerPrecision(row)
        globalMvs[refList * 2 + 1] = lowerPrecision(col)
    }

    private fun scanRow(deltaRow0: Int, isCompound: Boolean) {
        val bw4 = Av1.num4x4Wide[d.miSize]
        val end4 = minOf(minOf(bw4, d.miCols - d.miCol), 16)
        var deltaRow = deltaRow0
        var deltaCol = 0
        val useStep16 = bw4 >= 16
        if (abs(deltaRow) > 1) {
            deltaRow += d.miRow and 1
            deltaCol = 1 - (d.miCol and 1)
        }
        var i = 0
        while (i < end4) {
            val mvRow = d.miRow + deltaRow
            val mvCol = d.miCol + deltaCol + i
            if (!d.isInside(mvRow, mvCol)) break
            var len = minOf(bw4, Av1.num4x4Wide[d.miSizes[d.mi(mvRow, mvCol)]])
            if (abs(deltaRow) > 1) len = maxOf(2, len)
            if (useStep16) len = maxOf(4, len)
            addRefMvCandidate(mvRow, mvCol, isCompound, len * 2)
            i += len
        }
    }

    private fun scanCol(deltaCol0: Int, isCompound: Boolean) {
        val bh4 = Av1.num4x4High[d.miSize]
        val end4 = minOf(minOf(bh4, d.miRows - d.miRow), 16)
        var deltaRow = 0
        var deltaCol = deltaCol0
        val useStep16 = bh4 >= 16
        if (abs(deltaCol) > 1) {
            deltaRow = 1 - (d.miRow and 1)
            deltaCol += d.miCol and 1
        }
        var i = 0
        while (i < end4) {
            val mvRow = d.miRow + deltaRow + i
            val mvCol = d.miCol + deltaCol
            if (!d.isInside(mvRow, mvCol)) break
            var len = minOf(bh4, Av1.num4x4High[d.miSizes[d.mi(mvRow, mvCol)]])
            if (abs(deltaCol) > 1) len = maxOf(2, len)
            if (useStep16) len = maxOf(4, len)
            addRefMvCandidate(mvRow, mvCol, isCompound, len * 2)
            i += len
        }
    }

    /**
     * The scan point process. A location not decoded yet in this frame still has IsInters
     * equal to 0, so the add process leaves it out as the specification's check does.
     */
    private fun scanPoint(deltaRow: Int, deltaCol: Int, isCompound: Boolean) {
        val mvRow = d.miRow + deltaRow
        val mvCol = d.miCol + deltaCol
        if (d.isInside(mvRow, mvCol)) addRefMvCandidate(mvRow, mvCol, isCompound, 4)
    }

    private fun temporalScan(isCompound: Boolean) {
        val bw4 = Av1.num4x4Wide[d.miSize]
        val bh4 = Av1.num4x4High[d.miSize]
        val stepW4 = if (bw4 >= 16) 4 else 2
        val stepH4 = if (bh4 >= 16) 4 else 2
        var deltaRow = 0
        while (deltaRow < minOf(bh4, 16)) {
            var deltaCol = 0
            while (deltaCol < minOf(bw4, 16)) {
                addTplRefMv(deltaRow, deltaCol, isCompound)
                deltaCol += stepW4
            }
            deltaRow += stepH4
        }
        val allowExtension = bh4 >= 2 && bh4 < 16 && bw4 >= 2 && bw4 < 16
        if (allowExtension) {
            for (i in 0 until 3) {
                val deltaRow2 = if (i == 2) bh4 - 2 else bh4
                val deltaCol2 = if (i == 0) -2 else bw4
                val row = (d.miRow and 15) + deltaRow2
                val col = (d.miCol and 15) + deltaCol2
                if (row in 0..15 && col in 0..15) addTplRefMv(deltaRow2, deltaCol2, isCompound)
            }
        }
    }

    /** The temporal sample process. */
    private fun addTplRefMv(deltaRow: Int, deltaCol: Int, isCompound: Boolean) {
        val mvRow = (d.miRow + deltaRow) or 1
        val mvCol = (d.miCol + deltaCol) or 1
        if (!d.isInside(mvRow, mvCol)) return
        val x8 = mvCol shr 1
        val y8 = mvRow shr 1
        if (deltaRow == 0 && deltaCol == 0) zeroMvContext = 1
        val field = d.motionField ?: return
        if (!isCompound) {
            if (!field.projected(d.fh, d.refFrame[0], y8, x8, cand, 0)) return
            d.toolUse?.add("temporal candidate")
            cand[0] = lowerPrecision(cand[0])
            cand[1] = lowerPrecision(cand[1])
            if (deltaRow == 0 && deltaCol == 0) {
                zeroMvContext = if (abs(cand[0] - globalMvs[0]) >= 16 || abs(cand[1] - globalMvs[1]) >= 16) 1 else 0
            }
            var idx = 0
            while (idx < numMvFound) {
                if (cand[0] == stack[idx * 4] && cand[1] == stack[idx * 4 + 1]) break
                idx++
            }
            if (idx < numMvFound) {
                weights[idx] += 2
            } else if (numMvFound < MAX_REF_MV_STACK_SIZE) {
                stack[numMvFound * 4] = cand[0]
                stack[numMvFound * 4 + 1] = cand[1]
                weights[numMvFound] = 2
                numMvFound++
            }
        } else {
            if (!field.projected(d.fh, d.refFrame[0], y8, x8, cand, 0)) return
            if (!field.projected(d.fh, d.refFrame[1], y8, x8, cand, 2)) return
            d.toolUse?.add("temporal candidate")
            for (i in 0 until 4) cand[i] = lowerPrecision(cand[i])
            if (deltaRow == 0 && deltaCol == 0) {
                zeroMvContext = if (abs(cand[0] - globalMvs[0]) >= 16 || abs(cand[1] - globalMvs[1]) >= 16 ||
                    abs(cand[2] - globalMvs[2]) >= 16 || abs(cand[3] - globalMvs[3]) >= 16
                ) 1 else 0
            }
            var idx = 0
            while (idx < numMvFound) {
                if (cand[0] == stack[idx * 4] && cand[1] == stack[idx * 4 + 1] &&
                    cand[2] == stack[idx * 4 + 2] && cand[3] == stack[idx * 4 + 3]
                ) break
                idx++
            }
            if (idx < numMvFound) {
                weights[idx] += 2
            } else if (numMvFound < MAX_REF_MV_STACK_SIZE) {
                cand.copyInto(stack, numMvFound * 4)
                weights[numMvFound] = 2
                numMvFound++
            }
        }
    }

    /** The add reference motion vector process. */
    private fun addRefMvCandidate(mvRow: Int, mvCol: Int, isCompound: Boolean, weight: Int) {
        val m = d.mi(mvRow, mvCol)
        if (!d.isInters[m]) return
        if (!isCompound) {
            if (d.refFrames0[m] == d.refFrame[0]) searchStack(m, 0, weight)
            if (d.refFrames1[m] == d.refFrame[0]) searchStack(m, 1, weight)
        } else if (d.refFrames0[m] == d.refFrame[0] && d.refFrames1[m] == d.refFrame[1]) {
            compoundSearchStack(m, weight)
        }
    }

    private fun searchStack(m: Int, candList: Int, weight: Int) {
        val candMode = d.yModes[m]
        val candSize = d.miSizes[m]
        val large = minOf(Av1.blockWidth[candSize], Av1.blockHeight[candSize]) >= 8
        var row: Int
        var col: Int
        if ((candMode == Av1.GLOBALMV || candMode == Av1.GLOBAL_GLOBALMV) && d.fh.gmType[d.refFrame[0]] > Av1.TRANSLATION && large) {
            row = globalMvs[0]
            col = globalMvs[1]
        } else {
            row = d.mvs[m * 4 + candList * 2]
            col = d.mvs[m * 4 + candList * 2 + 1]
        }
        row = lowerPrecision(row)
        col = lowerPrecision(col)
        if (hasNewmv(candMode)) newMvCount++
        foundMatch = true
        for (idx in 0 until numMvFound) {
            if (stack[idx * 4] == row && stack[idx * 4 + 1] == col) {
                weights[idx] += weight
                return
            }
        }
        if (numMvFound < MAX_REF_MV_STACK_SIZE) {
            stack[numMvFound * 4] = row
            stack[numMvFound * 4 + 1] = col
            weights[numMvFound] = weight
            numMvFound++
        }
    }

    private fun compoundSearchStack(m: Int, weight: Int) {
        for (i in 0 until 4) cand[i] = d.mvs[m * 4 + i]
        val candMode = d.yModes[m]
        if (candMode == Av1.GLOBAL_GLOBALMV) {
            for (refList in 0 until 2) {
                if (d.fh.gmType[d.refFrame[refList]] > Av1.TRANSLATION) {
                    cand[refList * 2] = globalMvs[refList * 2]
                    cand[refList * 2 + 1] = globalMvs[refList * 2 + 1]
                }
            }
        }
        for (i in 0 until 4) cand[i] = lowerPrecision(cand[i])
        foundMatch = true
        var idx = 0
        while (idx < numMvFound) {
            if (cand[0] == stack[idx * 4] && cand[1] == stack[idx * 4 + 1] &&
                cand[2] == stack[idx * 4 + 2] && cand[3] == stack[idx * 4 + 3]
            ) break
            idx++
        }
        if (idx < numMvFound) {
            weights[idx] += weight
        } else if (numMvFound < MAX_REF_MV_STACK_SIZE) {
            cand.copyInto(stack, numMvFound * 4)
            weights[numMvFound] = weight
            numMvFound++
        }
        if (hasNewmv(candMode)) newMvCount++
    }

    /** The lower precision process for one component. */
    fun lowerPrecision(v: Int): Int {
        val fh = d.fh
        if (fh.allowHighPrecisionMv) return v
        if (fh.forceIntegerMv != 0) {
            val aInt = (abs(v) + 3) shr 3
            return if (v > 0) aInt shl 3 else -(aInt shl 3)
        }
        if (v and 1 != 0) return if (v > 0) v - 1 else v + 1
        return v
    }

    /** The stable bubble sort of the sorting process, by descending weight. */
    private fun sort(start: Int, end0: Int) {
        var end = end0
        while (end > start) {
            var newEnd = start
            for (idx in start + 1 until end) {
                if (weights[idx - 1] < weights[idx]) {
                    val w = weights[idx - 1]
                    weights[idx - 1] = weights[idx]
                    weights[idx] = w
                    for (k in 0 until 4) {
                        val t = stack[(idx - 1) * 4 + k]
                        stack[(idx - 1) * 4 + k] = stack[idx * 4 + k]
                        stack[idx * 4 + k] = t
                    }
                    newEnd = idx
                }
            }
            end = newEnd
        }
    }

    private fun extraSearch(isCompound: Boolean) {
        refIdCount[0] = 0
        refIdCount[1] = 0
        refDiffCount[0] = 0
        refDiffCount[1] = 0
        var w4 = minOf(16, Av1.num4x4Wide[d.miSize])
        var h4 = minOf(16, Av1.num4x4High[d.miSize])
        w4 = minOf(w4, d.miCols - d.miCol)
        h4 = minOf(h4, d.miRows - d.miRow)
        val num4x4 = minOf(w4, h4)
        for (pass in 0 until 2) {
            var idx = 0
            while (idx < num4x4 && numMvFound < 2) {
                val mvRow: Int
                val mvCol: Int
                if (pass == 0) {
                    mvRow = d.miRow - 1
                    mvCol = d.miCol + idx
                } else {
                    mvRow = d.miRow + idx
                    mvCol = d.miCol - 1
                }
                if (!d.isInside(mvRow, mvCol)) break
                addExtraMvCandidate(mvRow, mvCol, isCompound)
                val size = d.miSizes[d.mi(mvRow, mvCol)]
                idx += if (pass == 0) Av1.num4x4Wide[size] else Av1.num4x4High[size]
            }
        }
        if (isCompound) {
            for (list in 0 until 2) {
                var compCount = 0
                for (idx in 0 until refIdCount[list]) {
                    combinedMvs[compCount * 4 + list * 2] = refIdMvs[list * 4 + idx * 2]
                    combinedMvs[compCount * 4 + list * 2 + 1] = refIdMvs[list * 4 + idx * 2 + 1]
                    compCount++
                }
                var idx = 0
                while (idx < refDiffCount[list] && compCount < 2) {
                    combinedMvs[compCount * 4 + list * 2] = refDiffMvs[list * 4 + idx * 2]
                    combinedMvs[compCount * 4 + list * 2 + 1] = refDiffMvs[list * 4 + idx * 2 + 1]
                    compCount++
                    idx++
                }
                while (compCount < 2) {
                    combinedMvs[compCount * 4 + list * 2] = globalMvs[list * 2]
                    combinedMvs[compCount * 4 + list * 2 + 1] = globalMvs[list * 2 + 1]
                    compCount++
                }
            }
            if (numMvFound == 1) {
                val same = combinedMvs[0] == stack[0] && combinedMvs[1] == stack[1] &&
                    combinedMvs[2] == stack[2] && combinedMvs[3] == stack[3]
                combinedMvs.copyInto(stack, numMvFound * 4, if (same) 4 else 0, if (same) 8 else 4)
                weights[numMvFound] = 2
                numMvFound++
            } else {
                for (idx in 0 until 2) {
                    combinedMvs.copyInto(stack, numMvFound * 4, idx * 4, idx * 4 + 4)
                    weights[numMvFound] = 2
                    numMvFound++
                }
            }
        } else {
            for (idx in numMvFound until 2) {
                stack[idx * 4] = globalMvs[0]
                stack[idx * 4 + 1] = globalMvs[1]
            }
        }
    }

    private fun addExtraMvCandidate(mvRow: Int, mvCol: Int, isCompound: Boolean) {
        val m = d.mi(mvRow, mvCol)
        val bias = d.fh.refFrameSignBias
        for (candList in 0 until 2) {
            val candRef = if (candList == 0) d.refFrames0[m] else d.refFrames1[m]
            if (candRef <= INTRA_FRAME) continue
            if (isCompound) {
                for (list in 0 until 2) {
                    var row = d.mvs[m * 4 + candList * 2]
                    var col = d.mvs[m * 4 + candList * 2 + 1]
                    if (candRef == d.refFrame[list] && refIdCount[list] < 2) {
                        refIdMvs[list * 4 + refIdCount[list] * 2] = row
                        refIdMvs[list * 4 + refIdCount[list] * 2 + 1] = col
                        refIdCount[list]++
                    } else if (refDiffCount[list] < 2) {
                        if (bias[candRef] != bias[d.refFrame[list]]) {
                            row = -row
                            col = -col
                        }
                        refDiffMvs[list * 4 + refDiffCount[list] * 2] = row
                        refDiffMvs[list * 4 + refDiffCount[list] * 2 + 1] = col
                        refDiffCount[list]++
                    }
                }
            } else {
                var row = d.mvs[m * 4 + candList * 2]
                var col = d.mvs[m * 4 + candList * 2 + 1]
                if (bias[candRef] != bias[d.refFrame[0]]) {
                    row = -row
                    col = -col
                }
                var idx = 0
                while (idx < numMvFound) {
                    if (row == stack[idx * 4] && col == stack[idx * 4 + 1]) break
                    idx++
                }
                if (idx == numMvFound) {
                    stack[idx * 4] = row
                    stack[idx * 4 + 1] = col
                    weights[idx] = 2
                    numMvFound++
                }
            }
        }
    }

    private fun contextAndClamping(isCompound: Boolean, numNew: Int) {
        val bw = Av1.blockWidth[d.miSize]
        val bh = Av1.blockHeight[d.miSize]
        for (idx in 0 until numMvFound) {
            var z = 0
            if (idx + 1 < numMvFound) {
                val w0 = weights[idx]
                val w1 = weights[idx + 1]
                if (w0 >= REF_CAT_LEVEL) {
                    if (w1 < REF_CAT_LEVEL) z = 1
                } else {
                    z = 2
                }
            }
            drlCtxStack[idx] = z
        }
        for (list in 0 until (if (isCompound) 2 else 1)) {
            for (idx in 0 until numMvFound) {
                stack[idx * 4 + list * 2] = clampMvRow(stack[idx * 4 + list * 2], MV_BORDER + bh * 8)
                stack[idx * 4 + list * 2 + 1] = clampMvCol(stack[idx * 4 + list * 2 + 1], MV_BORDER + bw * 8)
            }
        }
        when (closeMatches) {
            0 -> {
                newMvContext = minOf(totalMatches, 1)
                refMvContext = totalMatches
            }
            1 -> {
                newMvContext = 3 - minOf(numNew, 1)
                refMvContext = 2 + totalMatches
            }
            else -> {
                newMvContext = 5 - minOf(numNew, 1)
                refMvContext = 5
            }
        }
    }

    fun clampMvRow(mvec: Int, border: Int): Int {
        val bh4 = Av1.num4x4High[d.miSize]
        val mbToTopEdge = -((d.miRow * Av1.MI_SIZE) * 8)
        val mbToBottomEdge = ((d.miRows - bh4 - d.miRow) * Av1.MI_SIZE) * 8
        return mvec.coerceIn(mbToTopEdge - border, mbToBottomEdge + border)
    }

    fun clampMvCol(mvec: Int, border: Int): Int {
        val bw4 = Av1.num4x4Wide[d.miSize]
        val mbToLeftEdge = -((d.miCol * Av1.MI_SIZE) * 8)
        val mbToRightEdge = ((d.miCols - bw4 - d.miCol) * Av1.MI_SIZE) * 8
        return mvec.coerceIn(mbToLeftEdge - border, mbToRightEdge + border)
    }

    /** has_overlappable_candidates( ): an inter block above or to the left, looked at every 8 samples. */
    fun hasOverlappableCandidates(): Boolean {
        if (d.availU) {
            val w4 = Av1.num4x4Wide[d.miSize]
            var x4 = d.miCol
            while (x4 < minOf(d.miCols, d.miCol + w4)) {
                if (d.refFrames0[d.mi(d.miRow - 1, x4 or 1)] > INTRA_FRAME) return true
                x4 += 2
            }
        }
        if (d.availL) {
            val h4 = Av1.num4x4High[d.miSize]
            var y4 = d.miRow
            while (y4 < minOf(d.miRows, d.miRow + h4)) {
                if (d.refFrames0[d.mi(y4 or 1, d.miCol - 1)] > INTRA_FRAME) return true
                y4 += 2
            }
        }
        return false
    }

    /** find_warp_samples( ): the neighbouring blocks that share this block's single reference. */
    fun findWarpSamples() {
        numSamples = 0
        numSamplesScanned = 0
        val w4 = Av1.num4x4Wide[d.miSize]
        val h4 = Av1.num4x4High[d.miSize]
        var doTopLeft = true
        var doTopRight = true
        if (d.availU) {
            val srcW = Av1.num4x4Wide[d.miSizes[d.mi(d.miRow - 1, d.miCol)]]
            if (w4 <= srcW) {
                val colOffset = -(d.miCol and (srcW - 1))
                if (colOffset < 0) doTopLeft = false
                if (colOffset + srcW > w4) doTopRight = false
                addSample(-1, 0)
            } else {
                var i = 0
                while (i < minOf(w4, d.miCols - d.miCol)) {
                    val w = Av1.num4x4Wide[d.miSizes[d.mi(d.miRow - 1, d.miCol + i)]]
                    addSample(-1, i)
                    i += minOf(w4, w)
                }
            }
        }
        if (d.availL) {
            val srcH = Av1.num4x4High[d.miSizes[d.mi(d.miRow, d.miCol - 1)]]
            if (h4 <= srcH) {
                val rowOffset = -(d.miRow and (srcH - 1))
                if (rowOffset < 0) doTopLeft = false
                addSample(0, -1)
            } else {
                var i = 0
                while (i < minOf(h4, d.miRows - d.miRow)) {
                    val h = Av1.num4x4High[d.miSizes[d.mi(d.miRow + i, d.miCol - 1)]]
                    addSample(i, -1)
                    i += minOf(h4, h)
                }
            }
        }
        if (doTopLeft) addSample(-1, -1)
        if (doTopRight && maxOf(w4, h4) <= 16) addSample(-1, w4)
        if (numSamples == 0 && numSamplesScanned > 0) numSamples = 1
    }

    /** The add sample process. A location not decoded yet reads RefFrames[ 0 ] as INTRA_FRAME, which never matches. */
    private fun addSample(deltaRow: Int, deltaCol: Int) {
        if (numSamplesScanned >= LEAST_SQUARES_SAMPLES_MAX) return
        val mvRow = d.miRow + deltaRow
        val mvCol = d.miCol + deltaCol
        if (!d.isInside(mvRow, mvCol)) return
        val m = d.mi(mvRow, mvCol)
        if (d.refFrames0[m] != d.refFrame[0]) return
        if (d.refFrames1[m] != Av1.NONE) return
        val candSz = d.miSizes[m]
        val candW4 = Av1.num4x4Wide[candSz]
        val candH4 = Av1.num4x4High[candSz]
        val candRow = mvRow and (candH4 - 1).inv()
        val candCol = mvCol and (candW4 - 1).inv()
        val midY = candRow * 4 + candH4 * 2 - 1
        val midX = candCol * 4 + candW4 * 2 - 1
        val threshold = maxOf(Av1.blockWidth[d.miSize], Av1.blockHeight[d.miSize]).coerceIn(16, 112)
        val c = d.mi(candRow, candCol)
        val candMvRow = d.mvs[c * 4]
        val candMvCol = d.mvs[c * 4 + 1]
        val mvDiffRow = abs(candMvRow - d.mv[0])
        val mvDiffCol = abs(candMvCol - d.mv[1])
        val valid = mvDiffRow + mvDiffCol <= threshold
        numSamplesScanned++
        if (!valid && numSamplesScanned > 1) return
        val at = numSamples * 4
        candList[at] = midY * 8
        candList[at + 1] = midX * 8
        candList[at + 2] = midY * 8 + candMvRow
        candList[at + 3] = midX * 8 + candMvCol
        if (valid) numSamples++
    }

    companion object {
        const val REF_CAT_LEVEL = 640
        const val MV_BORDER = 128
        const val LEAST_SQUARES_SAMPLES_MAX = 8

        fun hasNewmv(mode: Int): Boolean = mode == Av1.NEWMV || mode == Av1.NEW_NEWMV || mode == Av1.NEAR_NEWMV ||
            mode == Av1.NEW_NEARMV || mode == Av1.NEAREST_NEWMV || mode == Av1.NEW_NEARESTMV

        fun round2Signed(x: Long, n: Int): Long = if (x >= 0) Av1.round2(x, n) else -Av1.round2(-x, n)
    }
}
