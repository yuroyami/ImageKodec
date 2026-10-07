package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.ImageDecodeException

/** TIFF 6.0 section 20: expand codes using the declared reference black and white. */
internal class TiffReferenceBlackWhite private constructor(private val pairs: LongArray) {
    companion object {
        fun read(data: ByteArray, le: Boolean, type: Int, count: Long, valueOffset: Int): TiffReferenceBlackWhite {
            fun err(message: String): Nothing = throw ImageDecodeException("TIFF: ReferenceBlackWhite $message")
            // RATIONAL is what TIFF 6.0 names, and libtiff reads SHORT and LONG values too, as
            // fractions over 1: old-style JPEG writers stored the studio range that way.
            if (count != 6L || (type != 3 && type != 4 && type != 5)) err("requires six RATIONAL values")
            fun unsigned(at: Int, bytes: Int): Long {
                if (at < 0 || at > data.size - bytes) err("is truncated")
                var value = 0L
                for (i in 0 until bytes) {
                    value = value or ((data[at + i].toLong() and 255) shl (if (le) i * 8 else (bytes - 1 - i) * 8))
                }
                return value
            }
            val size = when (type) { 3 -> 2; 4 -> 4; else -> 8 }
            val offset = unsigned(valueOffset, 4)
            if (offset > data.size.toLong() - 6 * size) err("values are truncated")
            val pairs = if (type == 5) {
                LongArray(12) { unsigned(offset.toInt() + it * 4, 4) }
            } else {
                LongArray(12) { if (it % 2 == 1) 1L else unsigned(offset.toInt() + it / 2 * size, size) }
            }
            for (i in 0..2) {
                if (pairs[i * 4 + 1] == 0L || pairs[i * 4 + 3] == 0L) err("has a zero denominator")
                val a = pairs[i * 4].toULong() * pairs[i * 4 + 3].toULong()
                val b = pairs[i * 4 + 2].toULong() * pairs[i * 4 + 1].toULong()
                if (a == b) err("has a zero reference range")
            }
            return TiffReferenceBlackWhite(pairs)
        }
    }

    internal fun expanded(code: Int, channel: Int, codingRange: Int): TiffFixed128 {
        val at = channel * 4
        val black = pairs[at]
        val blackDenominator = pairs[at + 1]
        val white = pairs[at + 2]
        val whiteDenominator = pairs[at + 3]
        val whiteCross = white.toULong() * blackDenominator.toULong()
        val blackCross = black.toULong() * whiteDenominator.toULong()
        val reversed = whiteCross < blackCross
        val range = if (reversed) blackCross - whiteCross else whiteCross - blackCross
        // Two unsigned 32-bit denominators, a <=16-bit sample, coding
        // range <=255 and Q16 precision need at most 104 magnitude bits.
        // YCbCr's <=8-bit samples leave room for the largest matrix factor.
        val base = code.toLong() * blackDenominator - black
        val safe = Long.MAX_VALUE / whiteDenominator / codingRange / 65536
        val value = if (base >= -safe && base <= safe && range <= Long.MAX_VALUE.toULong()) {
            TiffFixed128.of(base * whiteDenominator * codingRange * 65536 / range.toLong())
        } else {
            TiffFixed128.of(base).times(whiteDenominator).times(codingRange.toLong()).shift16().dividedBy(range)
        }
        return if (reversed) -value else value
    }

    fun rgbTables(bits: Int): Array<IntArray>? {
        val max = (1 shl bits) - 1
        val default = (0..2).all { c ->
            pairs[c * 4] == 0L && pairs[c * 4 + 2] == max.toLong() * pairs[c * 4 + 3]
        }
        if (default) return null
        return Array(3) { c -> IntArray(max + 1) { expanded(it, c, 255).roundedByte(16) } }
    }

    fun ycbcrTables(): Array<Array<TiffFixed128>>? {
        val default = (0..2).all { c ->
            pairs[c * 4] == (if (c == 0) 0L else 128L) * pairs[c * 4 + 1] &&
                pairs[c * 4 + 2] == 255L * pairs[c * 4 + 3]
        }
        if (default) return null
        return Array(3) { channel -> Array(256) { expanded(it, channel, if (channel == 0) 255 else 127) } }
    }

    fun ycbcrToArgb(y: Int, cb: Int, cr: Int, tables: Array<Array<TiffFixed128>>): Int {
        val luma = tables[0][y]
        val chromaB = tables[1][cb]
        val chromaR = tables[2][cr]
        // TIFF 6.0's 299/1000, 587/1000, 114/1000 coefficients reduce to
        // these fractions. Combine before division so large terms can cancel.
        val r = (luma.times(500) + chromaR.times(701)).dividedBy(500uL).roundedByte(16)
        val g = (luma.times(293500) - chromaB.times(101004) - chromaR.times(209599))
            .dividedBy(293500uL).roundedByte(16)
        val b = (luma.times(250) + chromaB.times(443)).dividedBy(250uL).roundedByte(16)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
