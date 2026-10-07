package io.github.yuroyami.imagekodec.codec.avif

/**
 * The AV1 symbol decoder (specification section 8.2), over the [size] bytes of a tile at
 * [start] in [data]: `init_symbol`, `read_symbol` with its CDF adaptation, `read_bool`,
 * `read_literal` and `NS(n)`. It never reads past the tile: once its bits run out it feeds
 * the zero padding the specification describes, so a damaged tile decodes to something
 * rather than reading the next one.
 */
internal class Av1SymbolDecoder(private val data: ByteArray, start: Int, size: Int, private val disableCdfUpdate: Boolean) {
    private var bitPosition: Long = start.toLong() * 8
    private var symbolValue: Int
    private var symbolRange: Int
    private var symbolMaxBits: Int

    init {
        val numBits = minOf(size.toLong() * 8, 15L).toInt()
        val buf = readBits(numBits)
        val paddedBuf = buf shl (15 - numBits)
        symbolValue = ((1 shl 15) - 1) xor paddedBuf
        symbolRange = 1 shl 15
        symbolMaxBits = 8 * size - 15
    }

    private fun readBits(n: Int): Int {
        var x = 0
        for (i in 0 until n) {
            val byte = (bitPosition ushr 3).toInt()
            x = (x shl 1) or ((data[byte].toInt() shr (7 - (bitPosition and 7).toInt())) and 1)
            bitPosition++
        }
        return x
    }

    /**
     * `read_symbol` on the CDF of [n] symbols at [offset] in [cdf]: n cumulative values, the
     * last 32768, then the adaptation counter.
     */
    fun symbol(cdf: IntArray, offset: Int, n: Int): Int {
        var cur = symbolRange
        var symbol = -1
        var prev: Int
        do {
            symbol++
            prev = cur
            val f = (1 shl 15) - cdf[offset + symbol]
            cur = ((symbolRange ushr 8) * (f ushr EC_PROB_SHIFT)) ushr (7 - EC_PROB_SHIFT)
            cur += EC_MIN_PROB * (n - symbol - 1)
        } while (symbolValue < cur)
        symbolRange = prev - cur
        symbolValue -= cur
        renormalize()
        if (!disableCdfUpdate) {
            val count = cdf[offset + n]
            val rate = 3 + (if (count > 15) 1 else 0) + (if (count > 31) 1 else 0) + minOf(floorLog2(n), 2)
            var tmp = 0
            for (i in 0 until n - 1) {
                if (i == symbol) tmp = 1 shl 15
                val c = cdf[offset + i]
                cdf[offset + i] = if (tmp < c) c - ((c - tmp) shr rate) else c + ((tmp - c) shr rate)
            }
            if (count < 32) cdf[offset + n] = count + 1
        }
        return symbol
    }

    private fun renormalize() {
        val bits = 15 - floorLog2(symbolRange)
        if (bits == 0) return
        symbolRange = symbolRange shl bits
        val numBits = minOf(bits, maxOf(0, symbolMaxBits))
        val newData = readBits(numBits)
        val paddedData = newData shl (bits - numBits)
        symbolValue = paddedData xor (((symbolValue + 1) shl bits) - 1)
        symbolMaxBits -= bits
    }

    /** `read_bool`: a symbol of the fixed CDF {1 << 14, 1 << 15}, which never adapts. */
    fun bool(): Int {
        // read_symbol's loop for that CDF: below the first threshold is symbol 1, at or above it 0.
        val first = (((symbolRange ushr 8) * ((1 shl 14) ushr EC_PROB_SHIFT)) ushr (7 - EC_PROB_SHIFT)) + EC_MIN_PROB
        val symbol: Int
        if (symbolValue < first) {
            symbol = 1
            symbolRange = first
        } else {
            symbol = 0
            symbolRange -= first
            symbolValue -= first
        }
        renormalize()
        return symbol
    }

    /** `read_literal(n)`: n booleans, high bit first. */
    fun literal(n: Int): Int {
        var x = 0
        for (i in 0 until n) x = 2 * x + bool()
        return x
    }

    /** `NS(n)`: [ns] coded arithmetically. */
    fun ns(n: Int): Int {
        val w = floorLog2(n) + 1
        val m = (1 shl w) - n
        val v = literal(w - 1)
        if (v < m) return v
        val extraBit = literal(1)
        return (v shl 1) - m + extraBit
    }

    /** How many bits the tile's data had left when it ended; negative once padding was used. */
    val remainingBits: Int get() = symbolMaxBits

    private companion object {
        const val EC_PROB_SHIFT = 6
        const val EC_MIN_PROB = 4

        fun floorLog2(x: Int): Int = 31 - x.countLeadingZeroBits()
    }
}
