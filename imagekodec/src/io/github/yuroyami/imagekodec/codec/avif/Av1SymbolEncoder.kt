package io.github.yuroyami.imagekodec.codec.avif

/**
 * Where the AV1 encoder's symbols go (#176): an arithmetic coder that writes them ([Av1SymbolWriter]) or one
 * that only adds up what they would cost ([Av1SymbolCounter]). Both see the same CDFs as the decoder's
 * [Av1SymbolDecoder] and adapt them the same way when they adapt.
 */
internal abstract class Av1SymbolSink {

    /** Codes [v], one of [n] symbols, with the CDF at [offset] in [cdf], as `read_symbol` reads it. */
    abstract fun symbol(v: Int, cdf: IntArray, offset: Int, n: Int)

    /** Codes a bit of equal probability, as `read_bool` reads it. */
    abstract fun bool(v: Int)

    /** `read_literal(n)`: [n] bits of [v], high bit first. */
    fun literal(v: Int, n: Int) {
        for (i in n - 1 downTo 0) bool((v shr i) and 1)
    }

    /** `NS(n)` coded arithmetically: [v] in 0 until [n], the values below 2^w - n in one bit fewer. */
    fun ns(v: Int, n: Int) {
        val w = 32 - n.countLeadingZeroBits()
        val m = (1 shl w) - n
        if (v < m) {
            literal(v, w - 1)
        } else {
            val y = v + m
            literal(y shr 1, w - 1)
            literal(y and 1, 1)
        }
    }
}

/**
 * The arithmetic encoder that `init_symbol`, `read_symbol` and `exit_symbol` of section 8.2 invert, with the CDF
 * adaptation of [Av1SymbolDecoder] unless [adapt] is false (`disable_cdf_update`).
 *
 * The decoder keeps the code value V (the stream's first C bits) and a range R in [2^15, 2^16) and finds the symbol
 * whose interval [R - u, R - v) holds V - L, where u and v are the scaled CDF thresholds of the symbol and the one
 * before it. So the encoder adds R - u to the low end L and keeps u - v as R, then doubles both until R is 16 bits
 * again, each doubling one more bit of V. The bits of L above its lowest 16 can change only by a carry, so whole
 * bytes above those are written out, and a carry runs back through the bytes already written.
 */
internal class Av1SymbolWriter(private val adapt: Boolean) : Av1SymbolSink() {
    private var out = ByteArray(256)
    private var size = 0
    /** The bits of L not yet written: [bits] of them, the last 16 aligned with R. */
    private var low = 0
    private var bits = 15
    private var rng = 1 shl 15

    override fun symbol(v: Int, cdf: IntArray, offset: Int, n: Int) {
        val r = rng
        val u = if (v > 0) threshold(r, cdf[offset + v - 1], v - 1, n) else r
        val t = threshold(r, cdf[offset + v], v, n)
        encode(r - u, u - t)
        if (adapt) Av1SymbolDecoder.adapt(cdf, offset, n, v)
    }

    override fun bool(v: Int) {
        val r = rng
        // The fixed CDF {1 << 14, 1 << 15}: symbol 0 below the threshold of symbol 0, symbol 1 below it.
        val first = threshold(r, 1 shl 14, 0, 2)
        if (v == 0) encode(0, r - first) else encode(r - first, first)
    }

    private fun encode(add: Int, newRange: Int) {
        low += add
        if (low ushr bits != 0) {
            low -= 1 shl bits
            carry()
        }
        rng = newRange
        var d = 15 - (31 - rng.countLeadingZeroBits())
        rng = rng shl d
        while (d > 0) {
            val step = minOf(d, 8)
            low = low shl step
            bits += step
            d -= step
            while (bits >= 24) {
                put(low ushr (bits - 8))
                bits -= 8
                low = low and ((1 shl bits) - 1)
            }
        }
    }

    private fun put(b: Int) {
        if (size == out.size) out = out.copyOf(size * 2)
        out[size++] = b.toByte()
    }

    private fun carry() {
        var i = size - 1
        while ((out[i].toInt() and 0xFF) == 0xFF) {
            out[i] = 0
            i--
        }
        out[i] = (out[i] + 1).toByte()
    }

    /**
     * `exit_symbol`: the code value closest above L whose bits after the first C - 15 are a 1 and then zeros,
     * which the decoder's exit process requires, written up to that 1 and padded with zeros to a byte.
     */
    fun finish(): ByteArray {
        var v = ((low - (1 shl 14) + (1 shl 15) - 1) shr 15 shl 15) + (1 shl 14)
        if (v ushr bits != 0) {
            v -= 1 shl bits
            carry()
        }
        var keep = bits - 14
        while (keep > 0) {
            val take = minOf(8, keep)
            val byte = if (bits >= 8) (v ushr (bits - 8)) and 0xFF else (v shl (8 - bits)) and 0xFF
            put(byte)
            if (bits >= 8) {
                bits -= 8
                v = v and ((1 shl bits) - 1)
            } else {
                bits = 0
            }
            keep -= take
        }
        return out.copyOf(size)
    }

    companion object {
        /** The scaled threshold `read_symbol` compares with for symbol [s] of [n] whose CDF value is [c]. */
        fun threshold(r: Int, c: Int, s: Int, n: Int): Int {
            val f = (1 shl 15) - c
            return (((r ushr 8) * (f ushr 6)) ushr 1) + 4 * (n - s - 1)
        }
    }
}

/**
 * Adds up the cost of symbols in 1/256 bits, from the probabilities the CDFs give them, adapting the CDFs as the
 * writer would when [adapt] is set. The encoder counts a candidate with the CDFs frozen and a chosen block with
 * them adapting, so later decisions see the statistics the stream has built up.
 */
internal class Av1SymbolCounter(private val adapt: Boolean) : Av1SymbolSink() {
    var cost = 0

    override fun symbol(v: Int, cdf: IntArray, offset: Int, n: Int) {
        val hi = cdf[offset + v]
        val lo = if (v > 0) cdf[offset + v - 1] else 0
        cost += COST[maxOf(1, hi - lo)]
        if (adapt) Av1SymbolDecoder.adapt(cdf, offset, n, v)
    }

    override fun bool(v: Int) {
        cost += 256
    }

    companion object {
        /**
         * -log2(p / 32768) in 1/256 bits for p from 0 to 32768, in integer arithmetic: the integer part from the
         * leading zeros, and the fraction from repeated squaring of the normalized mantissa, one bit a squaring.
         */
        val COST: IntArray = IntArray(32769) { p -> if (p == 0) 15 * 256 else 15 * 256 - log2Fixed8(p) }

        /** log2([x]) in 1/256 for [x] >= 1. */
        fun log2Fixed8(x: Int): Int {
            val e = 31 - x.countLeadingZeroBits()
            // The mantissa in [1, 2) as 1.30 fixed point, squared eight times for eight fraction bits.
            var m = (x.toLong() shl 30) shr e
            var frac = 0
            for (i in 0 until 8) {
                m = (m * m) shr 30
                frac = frac shl 1
                if (m >= (2L shl 30)) {
                    m = m shr 1
                    frac = frac or 1
                }
            }
            return (e shl 8) + frac
        }
    }
}
