package io.github.yuroyami.imagekodec.codec.avif

import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.codec.avif.Av1Cdfs.Companion as C

/**
 * Intra block copy, which screen content encodes with: the motion vector stack of
 * find_mv_stack( 0 ) for a block whose reference is the frame itself (specification
 * section 7.10.2), assign_mv and read_mv (section 5.11.26), and the block inter prediction
 * from CurrFrame (section 7.11.3) with the bilinear filter it always uses.
 *
 * In an intra frame no candidate has an inter reference and use_ref_frame_mvs is 0, so the
 * temporal scan and the extra search add nothing; every vector is whole-sample, as
 * force_integer_mv is 1.
 */
internal object Av1IntraBlockCopy {
    private const val MV_INTRABC_CONTEXT = 1
    private const val INTRABC_DELAY_PIXELS = 256
    private const val INTRABC_DELAY_SB64 = 4
    private const val MAX_REF_MV_STACK_SIZE = 8
    private const val REF_CAT_LEVEL = 640
    private const val MV_BORDER = 128
    private const val BILINEAR = 3

    /** RefStackMv for list 0 and WeightStack, in place of the specification's globals. */
    private class Stack(val d: Av1FrameDecoder) {
        val mvs = IntArray(MAX_REF_MV_STACK_SIZE * 2)
        val weights = IntArray(MAX_REF_MV_STACK_SIZE)
        var found = 0
    }

    fun assignMv(d: Av1FrameDecoder) {
        val s = findMvStack(d)
        var predRow = s.mvs[0]
        var predCol = s.mvs[1]
        if (predRow == 0 && predCol == 0) {
            predRow = s.mvs[2]
            predCol = s.mvs[3]
        }
        if (predRow == 0 && predCol == 0) {
            val sbSize4 = Av1.num4x4High[if (d.sb128) Av1.BLOCK_128X128 else Av1.BLOCK_64X64]
            if (d.miRow - sbSize4 < d.miRowStart) {
                predRow = 0
                predCol = -(sbSize4 * Av1.MI_SIZE + INTRABC_DELAY_PIXELS) * 8
            } else {
                predRow = -(sbSize4 * Av1.MI_SIZE * 8)
                predCol = 0
            }
        }
        readMv(d, predRow, predCol)
        if (!isMvValid(d)) throw ImageDecodeException("AV1: an intra block copy vector reaches outside the decoded part of its tile")
    }

    private fun findMvStack(d: Av1FrameDecoder): Stack {
        val s = Stack(d)
        val bw4 = Av1.num4x4Wide[d.miSize]
        val bh4 = Av1.num4x4High[d.miSize]
        // The scans in the specification's order. FoundMatch and the match counts it builds only
        // steer the contexts of inter modes, which intra block copy never reads.
        scanRow(s, -1)
        scanCol(s, -1)
        if (maxOf(bw4, bh4) <= 16) scanPoint(s, -1, bw4)
        val numNearest = s.found
        for (i in 0 until numNearest) s.weights[i] += REF_CAT_LEVEL
        scanPoint(s, -1, -1)
        scanRow(s, -3)
        scanCol(s, -3)
        if (bh4 > 1) scanRow(s, -5)
        if (bw4 > 1) scanCol(s, -5)
        sort(s, 0, numNearest)
        sort(s, numNearest, s.found)
        // The extra search: the global vector of an intra reference is zero.
        for (idx in s.found until 2) {
            s.mvs[idx * 2] = 0
            s.mvs[idx * 2 + 1] = 0
        }
        // The context and clamping process.
        val bw = Av1.blockWidth[d.miSize]
        val bh = Av1.blockHeight[d.miSize]
        for (idx in 0 until s.found) {
            s.mvs[idx * 2] = clampMvRow(d, s.mvs[idx * 2], MV_BORDER + bh * 8)
            s.mvs[idx * 2 + 1] = clampMvCol(d, s.mvs[idx * 2 + 1], MV_BORDER + bw * 8)
        }
        return s
    }

    private fun scanRow(s: Stack, deltaRow0: Int) {
        val d = s.d
        val bw4 = Av1.num4x4Wide[d.miSize]
        val end4 = minOf(minOf(bw4, d.miCols - d.miCol), 16)
        var deltaRow = deltaRow0
        var deltaCol = 0
        val useStep16 = bw4 >= 16
        if (kotlin.math.abs(deltaRow) > 1) {
            deltaRow += d.miRow and 1
            deltaCol = 1 - (d.miCol and 1)
        }
        var i = 0
        while (i < end4) {
            val mvRow = d.miRow + deltaRow
            val mvCol = d.miCol + deltaCol + i
            if (!d.isInside(mvRow, mvCol)) break
            var len = minOf(bw4, Av1.num4x4Wide[d.miSizes[d.mi(mvRow, mvCol)]])
            if (kotlin.math.abs(deltaRow) > 1) len = maxOf(2, len)
            if (useStep16) len = maxOf(4, len)
            addRefMvCandidate(s, mvRow, mvCol, len * 2)
            i += len
        }
    }

    private fun scanCol(s: Stack, deltaCol0: Int) {
        val d = s.d
        val bh4 = Av1.num4x4High[d.miSize]
        val end4 = minOf(minOf(bh4, d.miRows - d.miRow), 16)
        var deltaRow = 0
        var deltaCol = deltaCol0
        val useStep16 = bh4 >= 16
        if (kotlin.math.abs(deltaCol) > 1) {
            deltaRow = 1 - (d.miRow and 1)
            deltaCol += d.miCol and 1
        }
        var i = 0
        while (i < end4) {
            val mvRow = d.miRow + deltaRow + i
            val mvCol = d.miCol + deltaCol
            if (!d.isInside(mvRow, mvCol)) break
            var len = minOf(bh4, Av1.num4x4High[d.miSizes[d.mi(mvRow, mvCol)]])
            if (kotlin.math.abs(deltaCol) > 1) len = maxOf(2, len)
            if (useStep16) len = maxOf(4, len)
            addRefMvCandidate(s, mvRow, mvCol, len * 2)
            i += len
        }
    }

    /**
     * The scan point process. A location not yet decoded in this frame still reads
     * IsInters as 0, so the add process leaves it out as the specification's check does.
     */
    private fun scanPoint(s: Stack, deltaRow: Int, deltaCol: Int) {
        val d = s.d
        val mvRow = d.miRow + deltaRow
        val mvCol = d.miCol + deltaCol
        if (d.isInside(mvRow, mvCol)) addRefMvCandidate(s, mvRow, mvCol, 4)
    }

    private fun addRefMvCandidate(s: Stack, mvRow: Int, mvCol: Int, weight: Int) {
        val d = s.d
        val m = d.mi(mvRow, mvCol)
        if (!d.isInters[m]) return
        if (d.refFrames0[m] == Av1.INTRA_FRAME) searchStack(s, m, 0, weight)
        if (d.refFrames1[m] == Av1.INTRA_FRAME) searchStack(s, m, 1, weight)
    }

    private fun searchStack(s: Stack, m: Int, candList: Int, weight: Int) {
        val d = s.d
        // A candidate in an intra frame is never a global motion mode, nor a NEWMV one.
        val row = lowerPrecision(d, d.mvs[m * 4 + candList * 2])
        val col = lowerPrecision(d, d.mvs[m * 4 + candList * 2 + 1])
        for (idx in 0 until s.found) {
            if (s.mvs[idx * 2] == row && s.mvs[idx * 2 + 1] == col) {
                s.weights[idx] += weight
                return
            }
        }
        if (s.found < MAX_REF_MV_STACK_SIZE) {
            s.mvs[s.found * 2] = row
            s.mvs[s.found * 2 + 1] = col
            s.weights[s.found] = weight
            s.found++
        }
    }

    private fun lowerPrecision(d: Av1FrameDecoder, v: Int): Int {
        val fh = d.fh
        if (fh.allowHighPrecisionMv) return v
        if (fh.forceIntegerMv != 0) {
            val aInt = (kotlin.math.abs(v) + 3) shr 3
            return if (v > 0) aInt shl 3 else -(aInt shl 3)
        }
        if (v and 1 != 0) return if (v > 0) v - 1 else v + 1
        return v
    }

    /** The stable bubble sort of the sorting process, by descending weight. */
    private fun sort(s: Stack, start: Int, end0: Int) {
        var end = end0
        while (end > start) {
            var newEnd = start
            for (idx in start + 1 until end) {
                if (s.weights[idx - 1] < s.weights[idx]) {
                    val w = s.weights[idx - 1]
                    s.weights[idx - 1] = s.weights[idx]
                    s.weights[idx] = w
                    for (comp in 0 until 2) {
                        val t = s.mvs[(idx - 1) * 2 + comp]
                        s.mvs[(idx - 1) * 2 + comp] = s.mvs[idx * 2 + comp]
                        s.mvs[idx * 2 + comp] = t
                    }
                    newEnd = idx
                }
            }
            end = newEnd
        }
    }

    private fun clampMvRow(d: Av1FrameDecoder, mvec: Int, border: Int): Int {
        val bh4 = Av1.num4x4High[d.miSize]
        val mbToTopEdge = -((d.miRow * Av1.MI_SIZE) * 8)
        val mbToBottomEdge = ((d.miRows - bh4 - d.miRow) * Av1.MI_SIZE) * 8
        return mvec.coerceIn(mbToTopEdge - border, mbToBottomEdge + border)
    }

    private fun clampMvCol(d: Av1FrameDecoder, mvec: Int, border: Int): Int {
        val bw4 = Av1.num4x4Wide[d.miSize]
        val mbToLeftEdge = -((d.miCol * Av1.MI_SIZE) * 8)
        val mbToRightEdge = ((d.miCols - bw4 - d.miCol) * Av1.MI_SIZE) * 8
        return mvec.coerceIn(mbToLeftEdge - border, mbToRightEdge + border)
    }

    // ---- read_mv -----------------------------------------------------------------------

    private fun readMv(d: Av1FrameDecoder, predRow: Int, predCol: Int) {
        val joint = d.sd.symbol(d.cdf[C.MV_JOINT], MV_INTRABC_CONTEXT * 5, 4)
        // MV_JOINT_HZVNZ (2) and MV_JOINT_HNZVNZ (3) change the row, MV_JOINT_HNZVZ (1) and 3 the column.
        val diffRow = if (joint == 2 || joint == 3) readMvComponent(d, 0) else 0
        val diffCol = if (joint == 1 || joint == 3) readMvComponent(d, 1) else 0
        d.mv[0] = predRow + diffRow
        d.mv[1] = predCol + diffCol
    }

    private fun readMvComponent(d: Av1FrameDecoder, comp: Int): Int {
        val sd = d.sd
        val cdf = d.cdf
        val fh = d.fh
        val ctx = MV_INTRABC_CONTEXT * 2 + comp
        val sign = sd.symbol(cdf[C.MV_SIGN], ctx * 3, 2)
        val mvClass = sd.symbol(cdf[C.MV_CLASS], ctx * 12, 11)
        val mag: Int
        if (mvClass == 0) {
            val class0Bit = sd.symbol(cdf[C.MV_CLASS0_BIT], ctx * 3, 2)
            val fr = if (fh.forceIntegerMv != 0) 3 else sd.symbol(cdf[C.MV_CLASS0_FR], (ctx * 2 + class0Bit) * 5, 4)
            val hp = if (fh.allowHighPrecisionMv) sd.symbol(cdf[C.MV_CLASS0_HP], ctx * 3, 2) else 1
            mag = ((class0Bit shl 3) or (fr shl 1) or hp) + 1
        } else {
            var bits = 0
            for (i in 0 until mvClass) bits = bits or (sd.symbol(cdf[C.MV_BIT], (ctx * 10 + i) * 3, 2) shl i)
            val fr = if (fh.forceIntegerMv != 0) 3 else sd.symbol(cdf[C.MV_FR], ctx * 5, 4)
            val hp = if (fh.allowHighPrecisionMv) sd.symbol(cdf[C.MV_HP], ctx * 3, 2) else 1
            mag = (CLASS0_SIZE shl (mvClass + 2)) + ((bits shl 3) or (fr shl 1) or hp) + 1
        }
        return if (sign != 0) -mag else mag
    }

    private const val CLASS0_SIZE = 2

    /** is_mv_valid( 0 ): whole samples, inside the tile, and in a superblock decoded far enough back. */
    private fun isMvValid(d: Av1FrameDecoder): Boolean {
        val mvRow = d.mv[0]
        val mvCol = d.mv[1]
        if (kotlin.math.abs(mvRow) >= (1 shl 14) || kotlin.math.abs(mvCol) >= (1 shl 14)) return false
        val bw = Av1.blockWidth[d.miSize]
        val bh = Av1.blockHeight[d.miSize]
        if ((mvRow and 7) != 0 || (mvCol and 7) != 0) return false
        val deltaRow = mvRow shr 3
        val deltaCol = mvCol shr 3
        var srcTopEdge = d.miRow * Av1.MI_SIZE + deltaRow
        var srcLeftEdge = d.miCol * Av1.MI_SIZE + deltaCol
        val srcBottomEdge = srcTopEdge + bh
        val srcRightEdge = srcLeftEdge + bw
        if (d.hasChroma) {
            if (bw < 8 && d.subX != 0) srcLeftEdge -= 4
            if (bh < 8 && d.subY != 0) srcTopEdge -= 4
        }
        if (srcTopEdge < d.miRowStart * Av1.MI_SIZE || srcLeftEdge < d.miColStart * Av1.MI_SIZE ||
            srcBottomEdge > d.miRowEnd * Av1.MI_SIZE || srcRightEdge > d.miColEnd * Av1.MI_SIZE
        ) {
            return false
        }
        val sbH = Av1.blockHeight[if (d.sb128) Av1.BLOCK_128X128 else Av1.BLOCK_64X64]
        val activeSbRow = (d.miRow * Av1.MI_SIZE) / sbH
        val activeSb64Col = (d.miCol * Av1.MI_SIZE) shr 6
        val srcSbRow = (srcBottomEdge - 1) / sbH
        val srcSb64Col = (srcRightEdge - 1) shr 6
        val totalSb64PerRow = ((d.miColEnd - d.miColStart - 1) shr 4) + 1
        val activeSb64 = activeSbRow * totalSb64PerRow + activeSb64Col
        val srcSb64 = srcSbRow * totalSb64PerRow + srcSb64Col
        if (srcSb64 >= activeSb64 - INTRABC_DELAY_SB64) return false
        val gradient = 1 + INTRABC_DELAY_SB64 + (if (d.sb128) 1 else 0)
        val wfOffset = gradient * (activeSbRow - srcSbRow)
        return !(srcSbRow > activeSbRow || srcSb64Col >= activeSb64Col - INTRABC_DELAY_SB64 + wfOffset)
    }

    // ---- prediction ------------------------------------------------------------------

    /**
     * compute_prediction( ) for [plane] of an intra block copy block. Every block of an intra
     * frame has RefFrame[ 0 ] equal to INTRA_FRAME, so someUseIntra holds and the whole
     * block, chroma of a sub-8x8 one included, takes this block's vector.
     */
    fun predict(d: Av1FrameDecoder, plane: Int) {
        val subX = if (plane > 0) d.subX else 0
        val subY = if (plane > 0) d.subY else 0
        val planeSz = Av1.subsampledSize(d.miSize, subX, subY)
        val w = Av1.num4x4Wide[planeSz] * 4
        val h = Av1.num4x4High[planeSz] * 4
        val x = (d.miCol shr subX) * Av1.MI_SIZE
        val y = (d.miRow shr subY) * Av1.MI_SIZE
        val bitDepth = d.bitDepth
        val round0 = if (bitDepth == 12) 5 else 3
        val round1 = if (bitDepth == 12) 9 else 11
        // The motion vector scaling process with the reference the size of the frame: a step of
        // one sample, and the position in 1/1024 of a sample.
        val startX = (((x shl 4) + ((2 * d.mv[1]) shr subX)) shl 6) + 32
        val startY = (((y shl 4) + ((2 * d.mv[0]) shr subY)) shl 6) + 32
        val lastX = ((d.miCols * Av1.MI_SIZE + subX) shr subX) - 1
        val lastY = ((d.miRows * Av1.MI_SIZE + subY) shr subY) - 1
        val ref = d.frame[plane]
        val stride = d.planeWidth[plane]
        val filters = Av1Tables.subpelFilters
        val interH = h + 7
        val intermediate = IntArray(interH * w)
        val fx = ((startX shr 6) and 15) * 8 + BILINEAR * 128
        val baseX = startX shr 10
        val baseY = startY shr 10
        for (r in 0 until interH) {
            val row = (baseY + r - 3).coerceIn(0, lastY) * stride
            for (c in 0 until w) {
                var s = 0
                for (t in 0 until 8) s += filters[fx + t] * ref[row + (baseX + c + t - 3).coerceIn(0, lastX)]
                intermediate[r * w + c] = Av1.round2(s, round0)
            }
        }
        val fy = ((startY shr 6) and 15) * 8 + BILINEAR * 128
        val maxValue = (1 shl bitDepth) - 1
        for (r in 0 until h) {
            val out = (y + r) * stride + x
            for (c in 0 until w) {
                var s = 0
                for (t in 0 until 8) s += filters[fy + t] * intermediate[(r + t) * w + c]
                ref[out + c] = Av1.round2(s, round1).coerceIn(0, maxValue).toShort()
            }
        }
    }
}
