package io.github.yuroyami.imagekodec.codec.avif

/**
 * The forward transforms of the AV1 encoder (#176). The specification defines only the inverse ones (section
 * 7.13.2); a forward transform is whatever the encoder likes, and this one is the transpose of the decoder's own
 * inverse, so the two agree on every basis vector. Each one-dimensional inverse of AV1 is orthogonal up to a gain of
 * n / 2 (its DC basis vector is 1 / sqrt(2) in each of n samples), so the transpose divided by that gain inverts it,
 * and the row and column shifts of the inverse are multiplied back.
 *
 * The DCT and the ADST of 8 and 16 points run as butterflies, the inverse's own steps transposed ([run]), which take
 * an eighth to a half of the products of a sum over the basis; the ADST of 4 points and the identity are sums over the
 * basis that [Av1Transform.basis] tabulates. The two agree within a few units of the last place, as the butterflies
 * round each rotation and the basis entries are rounded products of the same rotations.
 *
 * The output is in the units of the decoder's dequantized coefficients, the `Dequant` of section 7.12.3, which a
 * level times its quantizer step, divided by the size's `dqDenom`, reconstructs. Everything runs in `Int`: the row
 * pass sums at most 64 products of a 13-bit residual and a 4096-scaled basis entry, or rotates values that stay as
 * far below 2^31, and its result is shifted down by just enough that the column pass stays below 2^31 too.
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
            // s1 is log2W + log2H + bitDepth - 7 for every size and depth AV1 has, so toRow is log2H.
            val up = 19 - log2W - bitDepth
            val toRow = up - 12 + s1
            val down = 1 shl (toRow - 1)
            val graph = graph(rowKind, log2W)
            if (graph != null) {
                // The butterflies take the residual as large as an Int allows them, 2^(19 - log2W - bitDepth) times,
                // so their rounding stays below the row pass's own scale, 2^(12 - s1) times the unit-gain transform,
                // to which the result is then shifted. All h rows go through each step together, input j of row i at
                // buf[place * h + i].
                val buf = work
                val load = graph.load
                val negate = graph.negate
                for (j in 0 until w) {
                    val at = (if (load == null) j else load[j]) * h
                    val from = if (flipLR) w - 1 - j else j
                    if (negate != null && negate[j]) for (i in 0 until h) buf[at + i] = -(residual[i * w + from] shl up)
                    else for (i in 0 until h) buf[at + i] = residual[i * w + from] shl up
                }
                run(graph.steps, buf, h, h)
                for (k in 0 until tw) {
                    val at = graph.coef[k] * h
                    for (i in 0 until h) rs[i * 32 + k] = (buf[at + i] + down) shr toRow
                }
            } else for (i in 0 until h) {
                val src = i * w
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
        val graph = graph(colKind, log2H)
        if (graph != null) {
            // Every column through each step together, value i of column k at buf[place * 32 + k]. The butterflies'
            // unit gain is 2^12 below the basis sums'.
            val buf = work
            val load = graph.load
            val negate = graph.negate
            for (i in 0 until h) {
                val from = (if (flipUD) h - 1 - i else i) * 32
                val at = (if (load == null) i else load[i]) * 32
                if (negate != null && negate[i]) for (k in 0 until tw) buf[at + k] = -rs[from + k]
                else rs.copyInto(buf, at, from, from + tw)
            }
            run(graph.steps, buf, 32, tw)
            for (m in 0 until th) {
                val at = graph.coef[m] * 32
                if (rect) for (k in 0 until tw) out[m * tw + k] = roundShift(roundShift(buf[at + k], e - 13) * 181, 8)
                else for (k in 0 until tw) out[m * tw + k] = roundShift(buf[at + k], e - 12)
            }
            return
        }
        for (k in 0 until tw) {
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

    /** The values the butterflies of a block's rows or columns work on, all of them at once. */
    private val work = IntArray(64 * 64)

    /**
     * A one-dimensional forward transform as butterflies. Its [steps] take 7 values each: 0 and two places for a sum
     * and difference, or 1, two places and the four weights, in 1/4096, of a rotation. Input j goes first to place
     * [load][j] (j where it is null), negated where [negate] says, and coefficient m ends at place [coef][m].
     */
    private class Graph(val steps: IntArray, val load: IntArray?, val negate: BooleanArray?, val coef: IntArray)

    /** The butterflies of [kind] (0 DCT, 1 ADST, 2 identity) at 2^[log2n] points, or null where the basis sums serve. */
    private fun graph(kind: Int, log2n: Int): Graph? = when (kind) {
        0 -> DCT[log2n]
        1 -> if (log2n >= 3) ADST[log2n] else null
        else -> null
    }

    /**
     * The butterflies [steps] on [lanes] transforms at once, value p of transform l at [t][p * [stride] + l], in
     * place, at the unit gain of the decoder's inverse. Each rotation rounds to whole units, so a coefficient is the
     * sum over j of basis[k][j] x[j], divided by 4096, within a few units; no factoring gives that sum exactly, as the
     * basis entries are themselves rounded products of the rotations' cosines. Every value of the graph is a sum of
     * the inputs with weights whose magnitudes add up to at most 0.71 n (the DCT's DC row's), so inputs below
     * 2^(19 - log2n) keep each rotation's products within an Int.
     */
    private fun run(steps: IntArray, t: IntArray, stride: Int, lanes: Int) {
        var s = 0
        while (s < steps.size) {
            val a = steps[s + 1] * stride
            val b = steps[s + 2] * stride
            if (steps[s] == 0) {
                for (l in 0 until lanes) {
                    val p = t[a + l]
                    val q = t[b + l]
                    t[a + l] = p + q
                    t[b + l] = p - q
                }
            } else {
                val k1 = steps[s + 3]
                val k2 = steps[s + 4]
                val k3 = steps[s + 5]
                val k4 = steps[s + 6]
                for (l in 0 until lanes) {
                    val p = t[a + l]
                    val q = t[b + l]
                    t[a + l] = (p * k1 + q * k2 + 2048) shr 12
                    t[b + l] = (p * k3 + q * k4 + 2048) shr 12
                }
            }
            s += 7
        }
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

        /**
         * The forward DCT of 2^log2n points as butterflies: the steps of the inverse DCT of section 7.13.2.3, its
         * butterflies B and Hadamard steps H, in reverse order and transposed (H is its own transpose, and B's rotation
         * turns the other way after the swap its flip made), and its input permutation, by bit reversal, undone at the
         * end.
         */
        private val DCT: Array<Graph?> = Array(7) { log2n ->
            if (log2n < 2) {
                null
            } else {
                val order = IntArray(1 shl log2n) { i ->
                    var t = 0
                    for (b in 0 until log2n) t = t or (((i shr b) and 1) shl (log2n - 1 - b))
                    t
                }
                // Value k of the steps is coefficient order[k], and bit reversal is its own inverse.
                Graph(transposed(log2n, ::dctSteps), null, null, order)
            }
        }

        /**
         * The forward ADST of 8 and 16 points as butterflies: the inverse ADST of section 7.13.2.6 to 7.13.2.9
         * transposed, as [DCT] is. Its output permutation, signs included, comes first, undone, and its input
         * permutation last.
         */
        private val ADST: Array<Graph?> = Array(5) { log2n ->
            if (log2n < 3) {
                null
            } else {
                val n = 1 shl log2n
                val input = IntArray(n)
                val negate = BooleanArray(n)
                for (i in 0 until n) {
                    // The inverse's output permutation: output i is value idx, negated for an odd i.
                    val a = (i shr 3) and 1
                    val b = ((i shr 2) and 1) xor ((i shr 3) and 1)
                    val c = ((i shr 1) and 1) xor ((i shr 2) and 1)
                    val d = (i and 1) xor ((i shr 1) and 1)
                    input[i] = ((d shl 3) or (c shl 2) or (b shl 1) or a) shr (4 - log2n)
                    negate[i] = i and 1 != 0
                }
                // The inverse's input permutation, value k taking input k - 1 for an odd k and n - 1 - k for an even
                // one, undone: coefficient m is value m + 1 for an even m and n - 1 - m for an odd one.
                val coef = IntArray(n) { m -> if (m and 1 == 0) m + 1 else n - 1 - m }
                Graph(transposed(log2n, ::adstSteps), input, negate, coef)
            }
        }

        private fun cos128(angle: Int): Int {
            val a = angle and 255
            val t = Av1Tables.cos128Lookup
            return when {
                a <= 64 -> t[a]
                a <= 128 -> -t[128 - a]
                a <= 192 -> -t[a - 128]
                else -> t[256 - a]
            }
        }

        private fun brev(bits: Int, x: Int): Int {
            var t = 0
            for (b in 0 until bits) t = t or (((x shr b) and 1) shl (bits - 1 - b))
            return t
        }

        /**
         * The steps of an inverse transform of 2^[log2n] points that [inverse] lists through its B and H, reversed
         * and transposed, 7 values a step as [Graph] takes them.
         */
        private fun transposed(log2n: Int, inverse: (Int, (Int, Int, Int, Int) -> Unit, (Int, Int, Int) -> Unit) -> Unit): IntArray {
            val list = ArrayList<IntArray>()
            // B(a, b, angle, flip): the inverse rotates (T[a], T[b]) by angle, then swaps them when flip is 1. Its
            // transpose swaps first and rotates back: c T[a] + s T[b] and c T[b] - s T[a] of the swapped pair.
            val b = { a: Int, bb: Int, angle: Int, flip: Int ->
                val c = cos128(angle)
                val s = cos128(angle - 64)
                list += if (flip == 0) intArrayOf(1, a, bb, c, s, -s, c) else intArrayOf(1, a, bb, s, c, c, -s)
            }
            // H(a, b, flip): the sum into T[a] and the difference into T[b], their roles swapped when flip is 1.
            val h = { a: Int, bb: Int, flip: Int ->
                list += if (flip == 0) intArrayOf(0, a, bb, 0, 0, 0, 0) else intArrayOf(0, bb, a, 0, 0, 0, 0)
            }
            inverse(log2n, b, h)
            val out = IntArray(list.size * 7)
            for ((k, step) in list.asReversed().withIndex()) step.copyInto(out, k * 7)
            return out
        }

        /** The inverse ADST of 8 or 16 points of section 7.13.2.7 and 7.13.2.8, as calls of its B and H. */
        private fun adstSteps(n: Int, b: (Int, Int, Int, Int) -> Unit, h: (Int, Int, Int) -> Unit) {
            if (n == 3) {
                for (i in 0..3) b(2 * i, 2 * i + 1, 60 - 16 * i, 1)
                for (i in 0..3) h(i, 4 + i, 0)
                for (i in 0..1) b(4 + 3 * i, 5 + i, 48 - 32 * i, 1)
                for (i in 0..1) for (j in 0..1) h(4 * j + i, 2 + 4 * j + i, 0)
                for (i in 0..1) b(2 + 4 * i, 3 + 4 * i, 32, 1)
                return
            }
            for (i in 0..7) b(2 * i, 2 * i + 1, 62 - 8 * i, 1)
            for (i in 0..7) h(i, 8 + i, 0)
            for (i in 0..1) {
                b(8 + 2 * i, 9 + 2 * i, 56 - 32 * i, 1)
                b(13 + 2 * i, 12 + 2 * i, 8 + 32 * i, 1)
            }
            for (i in 0..3) for (j in 0..1) h(8 * j + i, 4 + 8 * j + i, 0)
            for (i in 0..1) for (j in 0..1) b(4 + 8 * j + 3 * i, 5 + 8 * j + i, 48 - 32 * i, 1)
            for (i in 0..1) for (j in 0..3) h(4 * j + i, 2 + 4 * j + i, 0)
            for (i in 0..3) b(2 + 4 * i, 3 + 4 * i, 32, 1)
        }

        /** The inverse DCT of section 7.13.2.3, as calls of its B and H. */
        private fun dctSteps(n: Int, b: (Int, Int, Int, Int) -> Unit, h: (Int, Int, Int) -> Unit) {
            if (n == 6) for (i in 0..15) b(32 + i, 63 - i, 63 - 4 * brev(4, i), 0)
            if (n >= 5) for (i in 0..7) b(16 + i, 31 - i, 6 + (brev(3, 7 - i) shl 3), 0)
            if (n == 6) for (i in 0..15) h(32 + i * 2, 33 + i * 2, i and 1)
            if (n >= 4) for (i in 0..3) b(8 + i, 15 - i, 12 + (brev(2, 3 - i) shl 4), 0)
            if (n >= 5) for (i in 0..7) h(16 + 2 * i, 17 + 2 * i, i and 1)
            if (n == 6) for (i in 0..3) for (j in 0..1) b(62 - i * 4 - j, 33 + i * 4 + j, 60 - 16 * brev(2, i) + 64 * j, 1)
            if (n >= 3) for (i in 0..1) b(4 + i, 7 - i, 56 - 32 * i, 0)
            if (n >= 4) for (i in 0..3) h(8 + 2 * i, 9 + 2 * i, i and 1)
            if (n >= 5) for (i in 0..1) for (j in 0..1) b(30 - 4 * i - j, 17 + 4 * i + j, 24 + (j shl 6) + ((1 - i) shl 5), 1)
            if (n == 6) for (i in 0..7) for (j in 0..1) h(32 + i * 4 + j, 35 + i * 4 - j, i and 1)
            for (i in 0..1) b(2 * i, 2 * i + 1, 32 + 16 * i, 1 - i)
            if (n >= 3) for (i in 0..1) h(4 + 2 * i, 5 + 2 * i, i)
            if (n >= 4) for (i in 0..1) b(14 - i, 9 + i, 48 + 64 * i, 1)
            if (n >= 5) for (i in 0..3) for (j in 0..1) h(16 + 4 * i + j, 19 + 4 * i - j, i and 1)
            if (n == 6) for (i in 0..1) for (j in 0..3) b(61 - i * 8 - j, 34 + i * 8 + j, 56 - i * 32 + (j shr 1) * 64, 1)
            for (i in 0..1) h(i, 3 - i, 0)
            if (n >= 3) b(6, 5, 32, 1)
            if (n >= 4) for (i in 0..1) for (j in 0..1) h(8 + 4 * i + j, 11 + 4 * i - j, i)
            if (n >= 5) for (i in 0..3) b(29 - i, 18 + i, 48 + (i shr 1) * 64, 1)
            if (n == 6) for (i in 0..3) for (j in 0..3) h(32 + 8 * i + j, 39 + 8 * i - j, i and 1)
            if (n >= 3) for (i in 0..3) h(i, 7 - i, 0)
            if (n >= 4) for (i in 0..1) b(13 - i, 10 + i, 32, 1)
            if (n >= 5) for (i in 0..1) for (j in 0..3) h(16 + i * 8 + j, 23 + i * 8 - j, i)
            if (n == 6) for (i in 0..7) b(59 - i, 36 + i, if (i < 4) 48 else 112, 1)
            if (n >= 4) for (i in 0..7) h(i, 15 - i, 0)
            if (n >= 5) for (i in 0..3) b(27 - i, 20 + i, 32, 1)
            if (n == 6) for (i in 0..7) {
                h(32 + i, 47 - i, 0)
                h(48 + i, 63 - i, 1)
            }
            if (n >= 5) for (i in 0..15) h(i, 31 - i, 0)
            if (n == 6) for (i in 0..7) b(55 - i, 40 + i, 32, 1)
            if (n == 6) for (i in 0..31) h(i, 63 - i, 0)
        }

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
