package io.github.yuroyami.imagekodec.codec.avif

import kotlin.math.abs

/**
 * The motion field estimation process (specification section 7.9): the motion vectors of
 * earlier frames projected onto this one on an 8x8 grid, for the temporal candidates of the
 * motion vector stack.
 *
 * The specification keeps MotionFieldMvs for each of the seven references, each the same
 * stored vector projected with that reference's distance. This keeps the stored vector and
 * its distance once per position instead, and [projected] projects it for the reference the
 * block asks about, which gives the same values.
 */
internal class Av1MotionField private constructor(private val w8: Int, private val h8: Int) {
    private val mvs = ShortArray(w8 * h8 * 2)
    /** refOffset of the vector at each position, 0 where the projection left a hole. */
    private val offsets = ByteArray(w8 * h8)

    /**
     * MotionFieldMvs[ [ref] ][ [y8] ][ [x8] ] into [out] at [at], or false where it holds the
     * invalid vector.
     */
    fun projected(fh: Av1FrameHeader, ref: Int, y8: Int, x8: Int, out: IntArray, at: Int): Boolean {
        val i = y8 * w8 + x8
        val offset = offsets[i].toInt()
        if (offset == 0) return false
        val refToDst = fh.relativeDist(fh.orderHint, fh.orderHints[ref])
        out[at] = projection(mvs[i * 2].toInt(), refToDst, offset)
        out[at + 1] = projection(mvs[i * 2 + 1].toInt(), refToDst, offset)
        return true
    }

    /** The projection process for [src], or false where [src] holds nothing to project. */
    private fun project(fh: Av1FrameHeader, refs: Av1RefSlots, src: Int, dstSign: Int): Boolean {
        val srcIdx = fh.refFrameIdx[src - Av1.LAST_FRAME]
        val miRows = fh.miRows
        val miCols = fh.miCols
        if (refs.miRows[srcIdx] != miRows || refs.miCols[srcIdx] != miCols ||
            refs.frameType[srcIdx] == Av1FrameHeader.INTRA_ONLY_FRAME || refs.frameType[srcIdx] == Av1FrameHeader.KEY_FRAME
        ) {
            return false
        }
        val savedRefs = refs.savedRefFrames[srcIdx] ?: return false
        val savedMvs = refs.savedMvs[srcIdx] ?: return false
        val refToCur = fh.relativeDist(fh.orderHints[src], fh.orderHint)
        for (y8 in 0 until h8) {
            for (x8 in 0 until w8) {
                val i = y8 * w8 + x8
                val srcRef = savedRefs[i].toInt()
                if (srcRef <= Av1.INTRA_FRAME) continue
                val refOffset = fh.relativeDist(fh.orderHints[src], refs.savedOrderHints[srcIdx][srcRef])
                if (abs(refToCur) > Av1.MAX_FRAME_DISTANCE || abs(refOffset) > Av1.MAX_FRAME_DISTANCE || refOffset <= 0) continue
                val mvRow = savedMvs[i * 2].toInt()
                val mvCol = savedMvs[i * 2 + 1].toInt()
                val projRow = projection(mvRow, refToCur * dstSign, refOffset)
                val projCol = projection(mvCol, refToCur * dstSign, refOffset)
                val posY8 = position(y8, projRow, dstSign, h8, MAX_OFFSET_HEIGHT)
                val posX8 = position(x8, projCol, dstSign, w8, MAX_OFFSET_WIDTH)
                if (posY8 < 0 || posX8 < 0) continue
                val at = posY8 * w8 + posX8
                mvs[at * 2] = mvRow.toShort()
                mvs[at * 2 + 1] = mvCol.toShort()
                offsets[at] = refOffset.toByte()
            }
        }
        return true
    }

    /** project( ) of the get block position process: the new position, or -1 where posValid is 0. */
    private fun position(v8: Int, delta: Int, dstSign: Int, max8: Int, maxOff8: Int): Int {
        val base8 = (v8 shr 3) shl 3
        val offset8 = if (delta >= 0) delta shr (3 + 1 + Av1.MI_SIZE_LOG2) else -((-delta) shr (3 + 1 + Av1.MI_SIZE_LOG2))
        val v = v8 + dstSign * offset8
        if (v < 0 || v >= max8 || v < base8 - maxOff8 || v >= base8 + 8 + maxOff8) return -1
        return v
    }

    companion object {
        private const val MFMV_STACK_SIZE = 3
        private const val MAX_OFFSET_WIDTH = 8
        private const val MAX_OFFSET_HEIGHT = 0

        /** motion_field_estimation( ) for [fh], from the vectors [refs] saved. */
        fun estimate(fh: Av1FrameHeader, refs: Av1RefSlots): Av1MotionField {
            val field = Av1MotionField(fh.miCols shr 1, fh.miRows shr 1)
            val lastIdx = fh.refFrameIdx[0]
            val curGoldOrderHint = fh.orderHints[Av1.GOLDEN_FRAME]
            val lastAltOrderHint = refs.savedOrderHints[lastIdx][Av1.ALTREF_FRAME]
            if (lastAltOrderHint != curGoldOrderHint) field.project(fh, refs, Av1.LAST_FRAME, -1)
            var refStamp = MFMV_STACK_SIZE - 2
            if (fh.relativeDist(fh.orderHints[Av1.BWDREF_FRAME], fh.orderHint) > 0) {
                if (field.project(fh, refs, Av1.BWDREF_FRAME, 1)) refStamp--
            }
            if (fh.relativeDist(fh.orderHints[Av1.ALTREF2_FRAME], fh.orderHint) > 0) {
                if (field.project(fh, refs, Av1.ALTREF2_FRAME, 1)) refStamp--
            }
            if (fh.relativeDist(fh.orderHints[Av1.ALTREF_FRAME], fh.orderHint) > 0 && refStamp >= 0) {
                if (field.project(fh, refs, Av1.ALTREF_FRAME, 1)) refStamp--
            }
            if (refStamp >= 0) field.project(fh, refs, Av1.LAST2_FRAME, -1)
            return field
        }

        /** The get mv projection process for one component. */
        fun projection(v: Int, numerator: Int, denominator: Int): Int {
            val clippedDenominator = minOf(Av1.MAX_FRAME_DISTANCE, denominator)
            val clippedNumerator = numerator.coerceIn(-Av1.MAX_FRAME_DISTANCE, Av1.MAX_FRAME_DISTANCE)
            val scaled = Av1MvStack.round2Signed(v.toLong() * clippedNumerator * Av1Tables.divMult[clippedDenominator], 14)
            return scaled.coerceIn(-(1L shl 14) + 1, (1L shl 14) - 1).toInt()
        }
    }
}
