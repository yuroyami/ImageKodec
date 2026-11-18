package io.github.yuroyami.imagekodec.codec.avif

/**
 * The forward transforms of the AV1 encoder (#176). The specification defines only the inverse ones (section
 * 7.13.2); a forward transform is whatever the encoder likes, and this one is the transpose of the decoder's own
 * inverse, taken from [Av1Transform.basis], so the two agree on every basis vector. Each one-dimensional inverse
 * of AV1 is orthogonal up to a gain of n / 2 (its DC basis vector is 1 / sqrt(2) in each of n samples), so the
 * transpose divided by that gain inverts it, and the row and column shifts of the inverse are multiplied back.
 *
 * The output is in the units of the decoder's dequantized coefficients, the `Dequant` of section 7.12.3, which a
 * level times its quantizer step, divided by the size's `dqDenom`, reconstructs. Everything runs in `Int`: the row
 * pass sums at most 64 products of a 13-bit residual and a 4096-scaled basis entry, which stays below 2^31, and
 * its result is shifted down by just enough that the column pass stays below 2^31 too.
 */
internal class Av1ForwardTransform(private val bitDepth: Int) {

    /** The row pass of the current block under each of three row kinds and two horizontal flips, made when first asked. */
    private val rows = Array(6) { IntArray(64 * 32) }
    private val rowsReady = BooleanArray(6)

    /** Forgets the row passes kept for the last block: the next [forward] reads its residual afresh. */
    fun newBlock() {
        rowsReady.fill(false)
    }

    /**
     * The coefficients of [residual], `w` by `h` samples of [txSz] a row apart, under transform [type], into [out]:
     * `min(32, h)` rows of `min(32, w)`, laid out as [Av1FrameDecoder.quant] lays out levels. A 64-point transform
     * keeps its lowest 32 frequencies, as AV1 codes no others. The row pass is kept until [newBlock], so the types
     * of one block that share their rows' transform share its work.
     */
    fun forward(residual: IntArray, txSz: Int, type: Int, out: IntArray) {
        val log2W = Av1.txWidthLog2[txSz]
        val log2H = Av1.txHeightLog2[txSz]
        val w = 1 shl log2W
        val h = 1 shl log2H
        val tw = minOf(32, w)
        val th = minOf(32, h)
        val rowKind = if (Av1Transform.rowIsDct(type)) 0 else if (Av1Transform.rowIsAdst(type)) 1 else 2
        val colKind = if (Av1Transform.colIsDct(type)) 0 else if (Av1Transform.colIsAdst(type)) 1 else 2
        val flipUD = Av1Transform.flipsRows(type)
        val flipLR = Av1Transform.flipsColumns(type)
        // The worst row sum is w * 2^(bitDepth + 1) * 4096; the column pass multiplies by h * 4096 more.
        val s1 = maxOf(0, log2W + log2H + bitDepth - 7)
        val slot = rowKind * 2 + (if (flipLR) 1 else 0)
        val rs = rows[slot]
        if (!rowsReady[slot]) {
            rowsReady[slot] = true
            val rowBasis = basis(rowKind, log2W)
            val half1 = if (s1 == 0) 0 else 1 shl (s1 - 1)
            val x = vec[0]
            val y = res
            for (i in 0 until h) {
                val src = i * w
                if (rowKind == 0) {
                    if (flipLR) for (j in 0 until w) x[j] = residual[src + w - 1 - j] else residual.copyInto(x, 0, src, src + w)
                    dct(x, log2W, tw, y)
                    for (k in 0 until tw) rs[i * 32 + k] = (y[k] + half1) shr s1
                    continue
                }
                for (k in 0 until tw) {
                    var sum = 0
                    if (rowKind == 2) {
                        sum = rowBasis[k * w + k] * residual[src + (if (flipLR) w - 1 - k else k)]
                    } else {
                        val b = k * w
                        if (flipLR) for (j in 0 until w) sum += rowBasis[b + j] * residual[src + w - 1 - j]
                        else for (j in 0 until w) sum += rowBasis[b + j] * residual[src + j]
                    }
                    rs[i * 32 + k] = (sum + half1) shr s1
                }
            }
        }
        val colBasis = basis(colKind, log2H)
        // D = Z * 2^(rowShift + colShift + s1) / (2^24 * (w / 2) * (h / 2)), and sqrt(2) more for a 2:1 shape.
        val rowShift = Av1Tables.transformRowShift[txSz]
        val e = 24 + (log2W - 1) + (log2H - 1) - rowShift - 4 - s1
        val rect = kotlin.math.abs(log2W - log2H) == 1
        val x = vec[0]
        for (k in 0 until tw) {
            if (colKind == 0) {
                if (flipUD) for (i in 0 until h) x[i] = rs[(h - 1 - i) * 32 + k] else for (i in 0 until h) x[i] = rs[i * 32 + k]
                dct(x, log2H, th, res)
                for (m in 0 until th) {
                    val sum = res[m]
                    out[m * tw + k] = if (rect) roundShift(roundShift(sum, e - 1) * 181, 8) else roundShift(sum, e)
                }
                continue
            }
            for (m in 0 until th) {
                var sum = 0
                if (colKind == 2) {
                    sum = colBasis[m * h + m] * rs[(if (flipUD) h - 1 - m else m) * 32 + k]
                } else {
                    val b = m * h
                    if (flipUD) for (i in 0 until h) sum += colBasis[b + i] * rs[(h - 1 - i) * 32 + k]
                    else for (i in 0 until h) sum += colBasis[b + i] * rs[i * 32 + k]
                }
                out[m * tw + k] = if (rect) {
                    // sqrt(2) as 181 / 128, after keeping one fractional bit.
                    roundShift(roundShift(sum, e - 1) * 181, 8)
                } else {
                    roundShift(sum, e)
                }
            }
        }
    }

    /** Scratch of the DCT's recursion: the sums handed down, the differences, and the even outputs handed up, by level. */
    private val vec = Array(7) { IntArray(64) }
    private val odd = Array(7) { IntArray(32) }
    private val evenOut = Array(7) { IntArray(64) }
    private val res = IntArray(64)

    /**
     * The first [count] outputs of the DCT basis of 2^[log2n] points applied to [x] (destroyed), into [out]: the sum
     * over j of basis[k][j] x[j]. Even and odd outputs split (a partial butterfly): the even ones are the half-size
     * DCT of x[j] + x[n-1-j], whose basis is the even rows of this one, and the odd ones take the differences
     * x[j] - x[n-1-j] against the first half of their rows. About a third of the products of the direct sum, and
     * the same Int bound, as each level doubles the values and halves their count.
     */
    private fun dct(x: IntArray, log2n: Int, count: Int, out: IntArray) {
        val n = 1 shl log2n
        val b = basis(0, log2n)
        if (log2n == 2) {
            for (k in 0 until count) out[k] = b[k * 4] * x[0] + b[k * 4 + 1] * x[1] + b[k * 4 + 2] * x[2] + b[k * 4 + 3] * x[3]
            return
        }
        val half = n shr 1
        val e = vec[log2n - 1]
        val o = odd[log2n]
        for (j in 0 until half) {
            e[j] = x[j] + x[n - 1 - j]
            o[j] = x[j] - x[n - 1 - j]
        }
        var k = 1
        while (k < count) {
            var sum = 0
            val row = k * n
            for (j in 0 until half) sum += b[row + j] * o[j]
            out[k] = sum
            k += 2
        }
        val evens = (count + 1) shr 1
        val up = evenOut[log2n]
        dct(e, log2n - 1, evens, up)
        for (m in 0 until evens) out[2 * m] = up[m]
    }

    /**
     * The lossless transform: the inverse of the Walsh-Hadamard transform of section 7.13.2.10, exactly. Each
     * lifting step of the inverse is undone in reverse order, so the decoder gets [residual] back sample for sample.
     */
    fun forwardWht(residual: IntArray, out: IntArray) {
        val t = IntArray(16)
        for (j in 0 until 4) {
            whtInverseOfInverse(residual[j], residual[4 + j], residual[8 + j], residual[12 + j], t, j, 4)
        }
        val tmp = IntArray(16)
        t.copyInto(tmp)
        for (i in 0 until 4) {
            whtInverseOfInverse(tmp[i * 4], tmp[i * 4 + 1], tmp[i * 4 + 2], tmp[i * 4 + 3], out, i * 4, 1)
        }
    }

    /**
     * Inputs of the inverse WHT whose outputs are [o0] to [o3], into [out] at [at] with [step] between them. The
     * inverse reads a, c, d, b; adds c to a and subtracts b from d; halves their difference into e; and leaves
     * a - (e - b), e - b, e - c and d + (e - c).
     */
    private fun whtInverseOfInverse(o0: Int, o1: Int, o2: Int, o3: Int, out: IntArray, at: Int, step: Int) {
        val b1 = o1
        val c1 = o2
        val a1 = o0 + b1
        val d1 = o3 - c1
        val e = (a1 - d1) shr 1
        val b = e - b1
        val c = e - c1
        val a = a1 - c
        val d = d1 + b
        out[at] = a
        out[at + step] = c
        out[at + 2 * step] = d
        out[at + 3 * step] = b
    }

    companion object {
        /** The bases by kind and size, made once when the class loads: DCT of 4 to 64 points, ADST of 4 to 16, identity of 4 to 32. */
        private val BASES: Array<IntArray?> = run {
            val t = Av1Transform()
            Array(3 * 7) { i ->
                val kind = i / 7
                val log2n = i % 7
                val top = when (kind) {
                    0 -> 6
                    1 -> 4
                    else -> 5
                }
                if (log2n in 2..top) t.basis(kind, log2n) else null
            }
        }

        fun basis(kind: Int, log2n: Int): IntArray = BASES[kind * 7 + log2n]!!

        /** [x] / 2^[n], rounded half away from zero. */
        fun roundShift(x: Int, n: Int): Int {
            if (n <= 0) return x shl -n
            val half = 1 shl (n - 1)
            return if (x >= 0) (x + half) shr n else -((-x + half) shr n)
        }

        /**
         * The factor, in 1/2^16, that turns the squared error of dequantized coefficients of [txSz] into the squared
         * error of samples: the inverse's gains (w / 2)(h / 2), its shifts, and a half for a 2:1 shape.
         */
        fun distortionScale(txSz: Int): Int {
            val log2W = Av1.txWidthLog2[txSz]
            val log2H = Av1.txHeightLog2[txSz]
            val rowShift = Av1Tables.transformRowShift[txSz]
            val rect = if (kotlin.math.abs(log2W - log2H) == 1) 1 else 0
            val shift = 2 * (rowShift + 4) + rect - (log2W - 1) - (log2H - 1)
            return 1 shl (16 - shift)
        }
    }
}
