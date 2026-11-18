package io.github.yuroyami.imagekodec.heic

/**
 * The inverse transforms of 8.6.4 on blocks of 4 to 32 samples, kept in raster order (index y * n + x).
 * One instance holds the buffers of one decoder, so no block allocates.
 */
internal class InverseTransform {
    private val tmp = IntArray(32 * 32)
    /** The sums of each level of [dct]: 32 entries, then 16, 8 and 4 for the even halves. */
    private val work = IntArray(64)

    /** CoeffMaxY or CoeffMaxC of the block being transformed (7-29, 7-30); CoeffMin is -CoeffMax - 1. */
    private var coeffMax = 32767

    /**
     * Turns the scaled coefficients [d] of an n by n block, n = 1 shl [log2], into residual samples
     * [r] (8.6.4.1, then the bdShift of 8-299). Coefficients past column [maxX] or row [maxY] are 0,
     * so the sums stop there. [dst] selects the 4x4 DST of 8-316. The column pass leaves columns past
     * [maxX] unwritten, as the row pass reads none of them. [coeffMax] bounds the intermediate values
     * of 8-314; the sums stay in 32 bits while [fitsInt] holds.
     */
    fun inverse(d: IntArray, r: IntArray, log2: Int, dst: Boolean, maxX: Int, maxY: Int, bdShift: Int, coeffMax: Int) {
        val n = 1 shl log2
        val t = tmp
        this.coeffMax = coeffMax
        // The columns: e[ x ][ y ] = sum over j of transMatrix[ y ][ j ] * d[ x ][ j ], then 8-314.
        for (x in 0..maxX) pass(n, dst, d, x, n, maxY, t, x, n, 7, true)
        // The rows, of which only the first maxX + 1 entries can be other than 0.
        for (y in 0 until n) pass(n, dst, t, y * n, 1, maxX, r, y * n, 1, bdShift, false)
    }

    /**
     * [inverse] for a DCT whose sums can pass 32 bits: extended precision lets a coefficient reach
     * 2^22 (7-29), and 2^22 times the largest column sum of a 16-point basis, 940, does not fit. The
     * sums are kept in 64 bits only here, and only for the blocks [fitsInt] rejects.
     */
    fun inverseWide(d: IntArray, r: IntArray, log2: Int, maxX: Int, maxY: Int, bdShift: Int, coeffMax: Int) {
        val n = 1 shl log2
        val m = MATRICES[log2 - 2]
        val t = tmp
        val min = -coeffMax - 1
        for (x in 0..maxX) {
            for (i in 0 until n) {
                var s = 0L
                for (k in 0..maxY) s += m[k * n + i].toLong() * d[k * n + x]
                // 8-314: the clipped value fits in 32 bits again.
                val v = (s + 64) shr 7
                t[i * n + x] = if (v < min) min else if (v > coeffMax) coeffMax else v.toInt()
            }
        }
        val round = 1L shl (bdShift - 1)
        for (y in 0 until n) {
            for (i in 0 until n) {
                var s = 0L
                for (k in 0..maxX) s += m[k * n + i].toLong() * t[y * n + k]
                // 8-299 with a bdShift of at least 11 under extended precision brings it back into 32 bits.
                r[y * n + i] = ((s + round) shr bdShift).toInt()
            }
        }
    }

    /**
     * One 1-D transform of 8.6.4.2 on the coefficients of [src] at [from] + k * [step], 0 past index
     * [last], written to [dst] at [to] + i * [outStep], rounded and shifted right by [shift], and
     * clipped to coeffMin and coeffMax when [clip] (8-314).
     */
    private fun pass(n: Int, sine: Boolean, src: IntArray, from: Int, step: Int, last: Int, dst: IntArray, to: Int, outStep: Int, shift: Int, clip: Boolean) {
        val sums = work
        if (sine) {
            // 8-315.
            val m = HevcTables.DST_4
            for (i in 0 until 4) {
                var s = 0
                for (k in 0..last) s += m[k * 4 + i] * src[from + k * step]
                sums[i] = s
            }
        } else {
            dct(n, src, from, step, last, 0)
        }
        val round = 1 shl (shift - 1)
        val max = coeffMax
        val min = -max - 1
        for (i in 0 until n) {
            val v = (sums[i] + round) shr shift
            dst[to + i * outStep] = if (!clip) v else if (v < min) min else if (v > max) max else v
        }
    }

    /**
     * The sums of 8-317 for an n-point DCT into [work] from [at]. Basis k is even about the middle
     * for even k and odd for odd k, and the even bases of the n-point matrix restricted to its first
     * n / 2 samples are the n / 2-point matrix, so the even half is that transform of the even
     * coefficients and the odd half is added to and taken from it. The sums are those of 8-317.
     */
    private fun dct(n: Int, src: IntArray, from: Int, step: Int, last: Int, at: Int) {
        val out = work
        val m = MATRICES[n.countTrailingZeroBits() - 2]
        val half = n shr 1
        if (n == 4) {
            val x0 = src[from]
            val x1 = if (last >= 1) src[from + step] else 0
            val x2 = if (last >= 2) src[from + 2 * step] else 0
            val x3 = if (last >= 3) src[from + 3 * step] else 0
            for (i in 0 until 2) {
                val e = m[i] * x0 + m[8 + i] * x2
                val o = m[4 + i] * x1 + m[12 + i] * x3
                out[at + i] = e + o
                out[at + 3 - i] = e - o
            }
            return
        }
        // The even half from the coefficients 0, 2, 4 and on, into the n entries after this level's.
        dct(half, src, from, step * 2, last shr 1, at + n)
        for (i in 0 until half) {
            var o = 0
            var k = 1
            while (k <= last) {
                o += m[k * n + i] * src[from + k * step]
                k += 2
            }
            val e = out[at + n + i]
            out[at + i] = e + o
            out[at + n - 1 - i] = e - o
        }
    }

    companion object {
        /**
         * transMatrix of 8-317 for n = 4, 8, 16 and 32: row k is the k-th basis function over n
         * samples, taken from every (32 / n)-th row of the 32-point matrix.
         */
        val MATRICES: Array<IntArray> = Array(4) { s ->
            val n = 4 shl s
            IntArray(n * n) { i -> HevcTables.DCT_32[(i / n) * (32 / n) * 32 + i % n] }
        }

        /**
         * The largest sum of the magnitudes of one column of each matrix of [MATRICES] and of the DST:
         * a bound on a sum of 8.6.4.2 over inputs of magnitude 1, which the butterfly's partial sums keep too.
         */
        private val COLUMN_SUMS = IntArray(4) { s ->
            val n = 4 shl s
            var most = 0
            for (m in if (s == 0) listOf(MATRICES[0], HevcTables.DST_4) else listOf(MATRICES[s])) {
                for (i in 0 until n) {
                    var sum = 0
                    for (k in 0 until n) sum += kotlin.math.abs(m[k * n + i])
                    most = maxOf(most, sum)
                }
            }
            most
        }

        /**
         * Whether the sums of an n-point transform, n = 1 shl [log2], over values within [coeffMax] stay
         * in 32 bits with the largest rounding term of 8-299 added, 2^11 for a bdShift of 12.
         */
        fun fitsInt(log2: Int, coeffMax: Int): Boolean = coeffMax.toLong() * COLUMN_SUMS[log2 - 2] + 2048 <= Int.MAX_VALUE

        /** Clip3( CoeffMin, CoeffMax, v ) for the 16-bit range of 7.4.9.11 without extended precision. */
        fun clip16(v: Int): Int = if (v < -32768) -32768 else if (v > 32767) 32767 else v
    }
}
