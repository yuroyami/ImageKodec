package io.github.yuroyami.imagekodec.heic

/**
 * The motion search of an inter prediction block: a pattern search over whole samples by SAD, then half and
 * quarter samples by SATD of the prediction the decoder's own interpolation makes ([InterPredictor.lumaSamples]),
 * each position weighed by the bits of its motion vector difference against the better of the two AMVP
 * predictors. Costs are in 1/256 of a SATD (or SAD) unit: the distortion times 256 plus sqrt( lambda ) times the
 * bits, as [IntraEncoder] ranks intra modes. Only luma takes part.
 */
internal class MotionSearch(private val rec: PictureDecoder, private val orig: Array<ShortArray>) {
    private val width = rec.width
    private val height = rec.height
    private val stride = rec.strides[0]
    private val bitDepth = rec.sps.bitDepthY
    private val predA = IntArray(64 * 64)
    private val predB = IntArray(64 * 64)
    private val diff = IntArray(64 * 64)
    private val satd = Satd()

    /** sqrt( lambda ) in 1/256 for the search being run. */
    var sqrtLambda: Int = 0

    /** The two AMVP predictors the bits of a vector are counted against. */
    private var mvpA = 0
    private var mvpB = 0

    /** The cost of the last [search], [refine] or [biCost]. */
    var cost: Long = 0
        private set

    /** The bits, in 1/256, of mvd_coding( ) for the difference [d] of one component (7.3.8.9, 9.3.3.3). */
    fun componentBits(d: Int): Int {
        if (d == 0) return 256
        val a = if (d < 0) -d else d
        // abs_mvd_greater0_flag, abs_mvd_greater1_flag and mvd_sign_flag, then abs_mvd_minus2 as EG1.
        if (a == 1) return 3 * 256
        var r = a - 2
        var k = 1
        var len = 0
        while (r >= (1 shl k)) {
            r -= 1 shl k
            k++
            len++
        }
        return (3 + len + 1 + k) * 256
    }

    /** The bits of [mv] against the better of the two predictors. */
    fun mvBits(mv: Int): Int {
        val a = componentBits(MotionField.x(mv) - MotionField.x(mvpA)) + componentBits(MotionField.y(mv) - MotionField.y(mvpA))
        val b = componentBits(MotionField.x(mv) - MotionField.x(mvpB)) + componentBits(MotionField.y(mv) - MotionField.y(mvpB))
        return minOf(a, b)
    }

    private fun rateCost(mv: Int): Long = ((sqrtLambda.toLong() * mvBits(mv)) shr 8)

    /** The SAD of the luma block at ([x], [y]) of [w] by [h] against [ref] displaced by whole samples ([dx], [dy]). */
    private fun sad(ref: ShortArray, x: Int, y: Int, w: Int, h: Int, dx: Int, dy: Int): Int {
        val src = orig[0]
        val rx = x + dx
        val ry = y + dy
        var sum = 0
        if (rx >= 0 && ry >= 0 && rx + w <= width && ry + h <= height) {
            for (j in 0 until h) {
                val o = (y + j) * stride + x
                val r = (ry + j) * stride + rx
                for (i in 0 until w) {
                    val d = (src[o + i].toInt() and 0xFFFF) - (ref[r + i].toInt() and 0xFFFF)
                    sum += if (d < 0) -d else d
                }
            }
            return sum
        }
        // Past the edge the reference repeats its border samples (8-228, 8-229).
        for (j in 0 until h) {
            val o = (y + j) * stride + x
            val r = clip(ry + j, height - 1) * stride
            for (i in 0 until w) {
                val d = (src[o + i].toInt() and 0xFFFF) - (ref[r + clip(rx + i, width - 1)].toInt() and 0xFFFF)
                sum += if (d < 0) -d else d
            }
        }
        return sum
    }

    private fun clip(v: Int, max: Int) = if (v < 0) 0 else if (v > max) max else v

    /** The SATD of the uni-predicted luma block from [ref] with the vector [mv], rounded to samples as 8-252 does. */
    fun uniSatd(ref: DecodedPicture, x: Int, y: Int, w: Int, h: Int, mv: Int): Int {
        rec.encoderInter.lumaSamples(ref, x, y, w, h, mv, predA)
        val shift = maxOf(2, 14 - bitDepth)
        val offset = 1 shl (shift - 1)
        val max = (1 shl bitDepth) - 1
        val src = orig[0]
        for (j in 0 until h) {
            val o = (y + j) * stride + x
            for (i in 0 until w) {
                val v = ((predA[j * w + i] + offset) shr shift).coerceIn(0, max)
                diff[j * w + i] = (src[o + i].toInt() and 0xFFFF) - v
            }
        }
        return satd.rect(diff, w, h)
    }

    /** The SATD of the bi-predicted luma block from [ref0] with [mv0] and [ref1] with [mv1] (8-264). */
    fun biSatd(ref0: DecodedPicture, mv0: Int, ref1: DecodedPicture, mv1: Int, x: Int, y: Int, w: Int, h: Int): Int {
        rec.encoderInter.lumaSamples(ref0, x, y, w, h, mv0, predA)
        rec.encoderInter.lumaSamples(ref1, x, y, w, h, mv1, predB)
        val shift = maxOf(3, 15 - bitDepth)
        val offset = 1 shl (shift - 1)
        val max = (1 shl bitDepth) - 1
        val src = orig[0]
        for (j in 0 until h) {
            val o = (y + j) * stride + x
            for (i in 0 until w) {
                val v = ((predA[j * w + i] + predB[j * w + i] + offset) shr shift).coerceIn(0, max)
                diff[j * w + i] = (src[o + i].toInt() and 0xFFFF) - v
            }
        }
        return satd.rect(diff, w, h)
    }

    /**
     * Searches [ref] for the block at ([x], [y]) of [w] by [h] whose AMVP predictors are [a] and [b]: the best
     * of the predictors and the zero vector as whole samples, a square pattern search from [step] samples down to
     * one, then the eight half-sample and the eight quarter-sample positions around the best. Returns the vector,
     * and leaves its cost in [cost].
     */
    fun search(ref: DecodedPicture, x: Int, y: Int, w: Int, h: Int, a: Int, b: Int, step: Int): Int {
        mvpA = a
        mvpB = b
        val plane = ref.planes[rec.colourPlane]
        // Whole-sample vectors that keep the block within 8 samples of the picture and the vector within 16 bits.
        val minX = maxOf(-x - w - 8, -8190)
        val maxX = minOf(width - x + 8, 8190)
        val minY = maxOf(-y - h - 8, -8190)
        val maxY = minOf(height - y + 8, 8190)
        fun intCost(dx: Int, dy: Int): Long =
            (sad(plane, x, y, w, h, dx, dy).toLong() shl 8) + rateCost(MotionField.pack(dx shl 2, dy shl 2))
        var bx = 0
        var by = 0
        var best = intCost(0, 0)
        for (p in intArrayOf(a, b)) {
            val dx = ((MotionField.x(p) + 2) shr 2).coerceIn(minX, maxX)
            val dy = ((MotionField.y(p) + 2) shr 2).coerceIn(minY, maxY)
            if (dx == bx && dy == by) continue
            val c = intCost(dx, dy)
            if (c < best) {
                best = c
                bx = dx
                by = dy
            }
        }
        var s = step
        while (s >= 1) {
            var moves = 0
            var moved = true
            while (moved && moves < 8) {
                moved = false
                val cx = bx
                val cy = by
                for (k in 0 until 8) {
                    val dx = cx + SQUARE_X[k] * s
                    val dy = cy + SQUARE_Y[k] * s
                    if (dx < minX || dx > maxX || dy < minY || dy > maxY) continue
                    val c = intCost(dx, dy)
                    if (c < best) {
                        best = c
                        bx = dx
                        by = dy
                        moved = true
                    }
                }
                moves++
            }
            s = s shr 1
        }
        return refine(ref, x, y, w, h, MotionField.pack(bx shl 2, by shl 2), 2)
    }

    /**
     * Refines [start] by SATD over the eight positions [first] quarter samples around it, then half as far, down to
     * one quarter sample. Returns the vector and leaves its cost in [cost].
     */
    fun refine(ref: DecodedPicture, x: Int, y: Int, w: Int, h: Int, start: Int, first: Int): Int {
        var bestMv = start
        var best = (uniSatd(ref, x, y, w, h, start).toLong() shl 8) + rateCost(start)
        var s = first
        while (s >= 1) {
            val cx = MotionField.x(bestMv)
            val cy = MotionField.y(bestMv)
            for (k in 0 until 8) {
                val mx = cx + SQUARE_X[k] * s
                val my = cy + SQUARE_Y[k] * s
                if (mx < -32760 || mx > 32760 || my < -32760 || my > 32760) continue
                val mv = MotionField.pack(mx, my)
                val c = (uniSatd(ref, x, y, w, h, mv).toLong() shl 8) + rateCost(mv)
                if (c < best) {
                    best = c
                    bestMv = mv
                }
            }
            s = s shr 1
        }
        cost = best
        return bestMv
    }

    /**
     * Refines the list-[list] vector [start] of a bi-predicted block, the other list's prediction held at [fixed]
     * from [fixedRef]: the eight positions one and then a quarter sample around it by the SATD of the bi
     * prediction. [a] and [b] are the AMVP predictors of the refined list. Returns the vector, its cost (with the
     * bits of this list's vector only) in [cost].
     */
    fun refineBi(ref: DecodedPicture, fixedRef: DecodedPicture, fixed: Int, list: Int, x: Int, y: Int, w: Int, h: Int, start: Int, a: Int, b: Int): Int {
        mvpA = a
        mvpB = b
        fun bi(mv: Int): Long {
            val s = if (list == 0) biSatd(ref, mv, fixedRef, fixed, x, y, w, h) else biSatd(fixedRef, fixed, ref, mv, x, y, w, h)
            return (s.toLong() shl 8) + rateCost(mv)
        }
        var bestMv = start
        var best = bi(start)
        for (s in intArrayOf(4, 2, 1)) {
            val cx = MotionField.x(bestMv)
            val cy = MotionField.y(bestMv)
            for (k in 0 until 8) {
                val mx = cx + SQUARE_X[k] * s
                val my = cy + SQUARE_Y[k] * s
                if (mx < -32760 || mx > 32760 || my < -32760 || my > 32760) continue
                val mv = MotionField.pack(mx, my)
                val c = bi(mv)
                if (c < best) {
                    best = c
                    bestMv = mv
                }
            }
        }
        cost = best
        return bestMv
    }

    /** Sets the predictors that [mvBits] counts against, for a caller that weighs a vector it did not search. */
    fun predictors(a: Int, b: Int) {
        mvpA = a
        mvpB = b
    }

    private companion object {
        val SQUARE_X = intArrayOf(0, -1, 1, 0, -1, 1, -1, 1)
        val SQUARE_Y = intArrayOf(-1, 0, 0, 1, -1, -1, 1, 1)
    }
}
