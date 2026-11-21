package io.github.yuroyami.imagekodec.heic

/**
 * The forward transforms of the encoder: the transposes of the matrices of 8.6.4.2 that the decoder inverts,
 * with shifts that keep every intermediate value in 16 bits for samples of up to 12 bits.
 *
 * The rows of transMatrix are its basis functions scaled by 64 * Sqrt( nTbS ), so the two passes multiply a
 * block by 4096 * nTbS. The first pass shifts right by Log2( nTbS ) + BitDepth - 9 and the second by
 * Log2( nTbS ) + 6, which leaves each coefficient at its orthonormal value times 2^( 15 - BitDepth -
 * Log2( nTbS ) ). The decoder's scaling (8.6.3) and inverse transform (8.6.4.2 with 8-299) bring a coefficient
 * of that scale back to the sample domain exactly when the scaled level equals it, so the quantizer can
 * measure its error in this domain.
 */
internal class ForwardTransform {
    private val tmp = IntArray(32 * 32)
    private val line = IntArray(32)
    private val out = IntArray(64)
    /** The sums and differences of each level of [dct], by log2 of its size. */
    private val evens = Array(6) { IntArray(16) }
    private val odds = Array(6) { IntArray(16) }

    /**
     * Transforms the residual [r] of an n by n block, n = 1 shl [log2], into [c] (both raster order, row
     * y * n + x; c holds the vertical frequency in the row). [dst] selects the 4x4 DST of 8-315.
     */
    fun forward(r: IntArray, c: IntArray, log2: Int, bitDepth: Int, dst: Boolean) {
        val n = 1 shl log2
        val t = tmp
        val s1 = log2 + bitDepth - 9
        val s2 = log2 + 6
        val l = line
        // The rows: tmp[ u ][ y ] = sum over x of M[ u ][ x ] * r[ y ][ x ], kept transposed.
        for (y in 0 until n) {
            for (x in 0 until n) l[x] = r[y * n + x]
            transform1d(l, n, dst)
            val round = 1 shl (s1 - 1)
            for (u in 0 until n) t[u * n + y] = (out[u] + round) shr s1
        }
        // The columns: c[ v ][ u ] = sum over y of M[ v ][ y ] * tmp[ u ][ y ].
        val round = 1 shl (s2 - 1)
        for (u in 0 until n) {
            for (y in 0 until n) l[y] = t[u * n + y]
            transform1d(l, n, dst)
            for (v in 0 until n) c[v * n + u] = (out[v] + round) shr s2
        }
    }

    /** The n-point forward transform of [x] into [out]. */
    private fun transform1d(x: IntArray, n: Int, dst: Boolean) {
        if (dst) {
            val m = HevcTables.DST_4
            for (k in 0 until 4) out[k] = m[k * 4] * x[0] + m[k * 4 + 1] * x[1] + m[k * 4 + 2] * x[2] + m[k * 4 + 3] * x[3]
            return
        }
        dct(x, 0, n, out, 0, 1)
    }

    /**
     * The n-point DCT of x[ from ] .. x[ from + n - 1 ] into o[ at + k * step ]. Even bases are symmetric
     * about the middle and odd ones antisymmetric, so the even outputs are the n / 2-point transform of the
     * sums x[ i ] + x[ n - 1 - i ] and the odd ones a product with the differences; the inverse in
     * [InverseTransform] uses the same split.
     */
    private fun dct(x: IntArray, from: Int, n: Int, o: IntArray, at: Int, step: Int) {
        val m = InverseTransform.MATRICES[n.countTrailingZeroBits() - 2]
        if (n == 4) {
            val e0 = x[from] + x[from + 3]
            val e1 = x[from + 1] + x[from + 2]
            val o0 = x[from] - x[from + 3]
            val o1 = x[from + 1] - x[from + 2]
            o[at] = m[0] * e0 + m[1] * e1
            o[at + 2 * step] = m[8] * e0 + m[9] * e1
            o[at + step] = m[4] * o0 + m[5] * o1
            o[at + 3 * step] = m[12] * o0 + m[13] * o1
            return
        }
        val half = n shr 1
        val level = n.countTrailingZeroBits()
        val e = evens[level]
        val d = odds[level]
        for (i in 0 until half) {
            e[i] = x[from + i] + x[from + n - 1 - i]
            d[i] = x[from + i] - x[from + n - 1 - i]
        }
        dct(e, 0, half, o, at, step * 2)
        var k = 1
        while (k < n) {
            var s = 0
            val row = k * n
            for (i in 0 until half) s += m[row + i] * d[i]
            o[at + k * step] = s
            k += 2
        }
    }
}
