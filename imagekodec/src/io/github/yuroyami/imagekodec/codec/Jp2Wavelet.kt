package io.github.yuroyami.imagekodec.codec

/**
 * The forward discrete wavelet transforms of T.800 Annex F for the encoder: each lifting step of
 * JpxDecoder's synthesis undone in the opposite order, so that the decoder's `lift1d` returns the
 * samples of [forward53] exactly, and its `liftFixed` those of [forward97] up to its fixed point.
 * Both work on global coordinates, as the decoder does: a sample at an even position is low-pass,
 * one at an odd position high-pass, and the signal extends symmetrically past its ends (F.3.7).
 */
internal object Jp2Wavelet {

    /**
     * [samples], a tile-component over [x0, x1) by [y0, y1), decomposed [levels] times. Slot 0 holds
     * the LL band of resolution 0, and slot `3 * (r - 1) + orient` the band of orientation `orient`
     * (1 HL, 2 LH, 3 HH) of resolution r, each row by row over its own rectangle (B-15).
     */
    fun forward53(samples: IntArray, x0: Int, y0: Int, x1: Int, y1: Int, levels: Int): Array<IntArray> {
        val slots = arrayOfNulls<IntArray>(3 * levels + 1)
        var cur = samples
        var rx0 = x0; var ry0 = y0; var rx1 = x1; var ry1 = y1
        for (d in 1..levels) {
            val w = rx1 - rx0
            val h = ry1 - ry0
            if (w > 0 && h > 0) {
                // Columns first, then rows: the decoder runs rows first, then columns, and the integer
                // rounding makes the order matter.
                val col = IntArray(h)
                for (x in 0 until w) {
                    for (y in 0 until h) col[y] = cur[y * w + x]
                    lift53(col, h, ry0)
                    for (y in 0 until h) cur[y * w + x] = col[y]
                }
                val row = IntArray(w)
                for (y in 0 until h) {
                    cur.copyInto(row, 0, y * w, y * w + w)
                    lift53(row, w, rx0)
                    row.copyInto(cur, y * w)
                }
            }
            val r = levels - d + 1
            val parts = Array(4) { orient ->
                val bx0 = half(rx0, orient and 1); val bx1 = half(rx1, orient and 1)
                val by0 = half(ry0, orient shr 1); val by1 = half(ry1, orient shr 1)
                IntArray(maxOf(0, bx1 - bx0) * maxOf(0, by1 - by0))
            }
            split(w, h, rx0, ry0) { orient, at, from -> parts[orient][at] = cur[from] }
            for (orient in 1..3) slots[3 * (r - 1) + orient] = parts[orient]
            cur = parts[0]
            rx0 = half(rx0, 0); ry0 = half(ry0, 0); rx1 = half(rx1, 0); ry1 = half(ry1, 0)
        }
        slots[0] = cur
        @Suppress("UNCHECKED_CAST")
        return slots as Array<IntArray>
    }

    /** [forward53] for the 9/7 filter, in floating point. */
    fun forward97(samples: DoubleArray, x0: Int, y0: Int, x1: Int, y1: Int, levels: Int): Array<DoubleArray> {
        val slots = arrayOfNulls<DoubleArray>(3 * levels + 1)
        var cur = samples
        var rx0 = x0; var ry0 = y0; var rx1 = x1; var ry1 = y1
        for (d in 1..levels) {
            val w = rx1 - rx0
            val h = ry1 - ry0
            if (w > 0 && h > 0) {
                val col = DoubleArray(h)
                for (x in 0 until w) {
                    for (y in 0 until h) col[y] = cur[y * w + x]
                    lift97(col, h, ry0)
                    for (y in 0 until h) cur[y * w + x] = col[y]
                }
                val row = DoubleArray(w)
                for (y in 0 until h) {
                    cur.copyInto(row, 0, y * w, y * w + w)
                    lift97(row, w, rx0)
                    row.copyInto(cur, y * w)
                }
            }
            val r = levels - d + 1
            val parts = Array(4) { orient ->
                val bx0 = half(rx0, orient and 1); val bx1 = half(rx1, orient and 1)
                val by0 = half(ry0, orient shr 1); val by1 = half(ry1, orient shr 1)
                DoubleArray(maxOf(0, bx1 - bx0) * maxOf(0, by1 - by0))
            }
            split(w, h, rx0, ry0) { orient, at, from -> parts[orient][at] = cur[from] }
            for (orient in 1..3) slots[3 * (r - 1) + orient] = parts[orient]
            cur = parts[0]
            rx0 = half(rx0, 0); ry0 = half(ry0, 0); rx1 = half(rx1, 0); ry1 = half(ry1, 0)
        }
        slots[0] = cur
        @Suppress("UNCHECKED_CAST")
        return slots as Array<DoubleArray>
    }

    /** Where a band edge falls for the edge [v] of the resolution above: rounded up for low-pass, down for high-pass (B-15). */
    private fun half(v: Int, high: Int): Int = if (high == 0) (v + 1) shr 1 else v shr 1

    /** Hands each sample of a decomposed w-by-h resolution at ([x0], [y0]) to its band: [put] (orient, index in the band, index here). */
    private inline fun split(w: Int, h: Int, x0: Int, y0: Int, put: (Int, Int, Int) -> Unit) {
        if (w <= 0 || h <= 0) return
        val lowW = half(x0 + w, 0) - half(x0, 0)
        val highW = half(x0 + w, 1) - half(x0, 1)
        for (y in 0 until h) {
            val gy = y0 + y
            val vy = (gy shr 1) - half(y0, gy and 1)
            for (x in 0 until w) {
                val gx = x0 + x
                val orient = (gx and 1) or ((gy and 1) shl 1)
                val u = (gx shr 1) - half(x0, gx and 1)
                put(orient, vy * (if (gx and 1 == 0) lowW else highW) + u, y * w + x)
            }
        }
    }

    /** The mirror of global position [i] into [i0, i0 + n) (F.3.7). */
    private fun mirror(i: Int, i0: Int, n: Int): Int {
        var k = i
        val last = i0 + n - 1
        while (k < i0 || k > last) k = if (k < i0) 2 * i0 - k else 2 * last - k
        return k - i0
    }

    /**
     * The forward 5/3 lifting of [x], which holds global positions i0 to i0 + n - 1 (F.4.8.1): the
     * high-pass samples less the floor of half their neighbours' sum, then the low-pass samples plus a
     * quarter of theirs, rounded. A lone sample at an odd position doubles, as F.3.6 halves it.
     */
    fun lift53(x: IntArray, n: Int, i0: Int) {
        if (n == 1) {
            if (i0 and 1 != 0) x[0] = x[0] shl 1
            return
        }
        var i = if (i0 and 1 != 0) i0 else i0 + 1
        while (i < i0 + n) {
            x[i - i0] -= (x[mirror(i - 1, i0, n)] + x[mirror(i + 1, i0, n)]) shr 1
            i += 2
        }
        i = if (i0 and 1 == 0) i0 else i0 + 1
        while (i < i0 + n) {
            x[i - i0] += (x[mirror(i - 1, i0, n)] + x[mirror(i + 1, i0, n)] + 2) shr 2
            i += 2
        }
    }

    /**
     * The forward 9/7 lifting (F.4.8.2): the four lifting steps with the parameters of Table F.4, then
     * the high-pass samples times K and the low-pass ones over K, which JpxDecoder's liftFixed undoes.
     */
    fun lift97(x: DoubleArray, n: Int, i0: Int) {
        if (n == 1) {
            if (i0 and 1 != 0) x[0] = x[0] * 2.0
            return
        }
        fun step(parity: Int, factor: Double) {
            var i = if (i0 and 1 == parity) i0 else i0 + 1
            while (i < i0 + n) {
                x[i - i0] += factor * (x[mirror(i - 1, i0, n)] + x[mirror(i + 1, i0, n)])
                i += 2
            }
        }
        step(1, JpxDecoder.ALPHA_97)
        step(0, JpxDecoder.BETA_97)
        step(1, JpxDecoder.GAMMA_97)
        step(0, JpxDecoder.DELTA_97)
        var i = i0
        while (i < i0 + n) {
            x[i - i0] = if (i and 1 != 0) x[i - i0] * JpxDecoder.K_97 else x[i - i0] / JpxDecoder.K_97
            i++
        }
    }

    /**
     * The squared norm of the synthesis basis of one coefficient of band [orient] of resolution [rr]
     * after [levels] decompositions: the image-domain squared error that a unit error in that band
     * leaves, which the rate control weighs each band's distortion by (J.14.1). It comes from the
     * decoder's own synthesis run on a unit impulse, so it holds whatever normalisation that has.
     */
    fun bandNorm2(levels: Int, rr: Int, orient: Int, reversible: Boolean): Double {
        if (rr == 0) return norm2(levels, false, reversible).let { it * it }
        val d = levels - rr + 1
        return norm2(d, orient and 1 != 0, reversible) * norm2(d, orient and 2 != 0, reversible)
    }

    /** The squared norm of the 1-D synthesis basis of a coefficient [d] levels down, high-pass or not. */
    private fun norm2(d: Int, high: Boolean, reversible: Boolean): Double {
        if (d == 0) return 1.0
        val depth = minOf(d, NORM_DEPTH)
        val exact = synthesisEnergy(depth, high, reversible)
        if (depth == d) return exact
        // Past NORM_DEPTH every further level is one more low-pass synthesis, whose energy gain has
        // settled by then: carry it on at the rate of the last level measured.
        val rate = exact / synthesisEnergy(depth - 1, high, reversible)
        var v = exact
        repeat(d - depth) { v *= rate }
        return v
    }

    private fun synthesisEnergy(d: Int, high: Boolean, reversible: Boolean): Double {
        // A signal long enough that the basis never reaches its ends: 16 samples a band at the deepest level.
        var len = 32
        if (reversible) {
            var x = IntArray(len)
            x[len / 2 + if (high) 1 else 0] = IMPULSE_53
            JpxDecoder.lift1d(x, 0, len)
            repeat(d - 1) {
                val y = IntArray(len * 2)
                for (i in 0 until len) y[2 * i] = x[i]
                len *= 2
                JpxDecoder.lift1d(y, 0, len)
                x = y
            }
            var sum = 0.0
            for (v in x) sum += v.toDouble() * v
            return sum / (IMPULSE_53.toDouble() * IMPULSE_53)
        }
        var x = LongArray(len)
        x[len / 2 + if (high) 1 else 0] = IMPULSE_97
        JpxDecoder.liftFixed(x, 0, len)
        repeat(d - 1) {
            val y = LongArray(len * 2)
            for (i in 0 until len) y[2 * i] = x[i]
            len *= 2
            JpxDecoder.liftFixed(y, 0, len)
            x = y
        }
        var sum = 0.0
        for (v in x) sum += v.toDouble() * v
        return sum / (IMPULSE_97.toDouble() * IMPULSE_97)
    }

    private const val NORM_DEPTH = 10
    private const val IMPULSE_53 = 1 shl 16
    private const val IMPULSE_97 = 1L shl 30
}
