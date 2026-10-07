package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.ImageDecodeException

/** Fixed-width signed magnitude for TIFF's unsigned RATIONAL range expansion. */
internal class TiffFixed128 private constructor(
    private val high: ULong,
    private val low: ULong,
    private val negative: Boolean,
) {
    companion object {
        fun of(value: Long): TiffFixed128 = TiffFixed128(0uL,
            if (value < 0) 0uL - value.toULong() else value.toULong(), value < 0)

        private fun overflow(): Nothing = throw ImageDecodeException("TIFF: ReferenceBlackWhite arithmetic exceeds 128 bits")
    }

    operator fun unaryMinus(): TiffFixed128 = TiffFixed128(high, low, (high != 0uL || low != 0uL) && !negative)

    operator fun plus(other: TiffFixed128): TiffFixed128 {
        if (negative == other.negative) {
            val sumLow = low + other.low
            val sumHigh = high + other.high
            val carry = if (sumLow < low) 1uL else 0uL
            if (sumHigh < high || sumHigh + carry < sumHigh) overflow()
            return TiffFixed128(sumHigh + carry, sumLow, negative)
        }
        val larger = high > other.high || (high == other.high && low >= other.low)
        val a = if (larger) this else other
        val b = if (larger) other else this
        val differenceLow = a.low - b.low
        val differenceHigh = a.high - b.high - if (a.low < b.low) 1uL else 0uL
        return TiffFixed128(differenceHigh, differenceLow,
            (differenceHigh != 0uL || differenceLow != 0uL) && a.negative)
    }

    operator fun minus(other: TiffFixed128): TiffFixed128 = this + -other

    fun times(factor: Long): TiffFixed128 {
        if (factor !in 0..0xFFFFFFFFL) overflow()
        val f = factor.toULong()
        val mask = 0xFFFFFFFFuL
        val p0 = (low and mask) * f
        val p1 = (low shr 32) * f + (p0 shr 32)
        val p2 = (high and mask) * f + (p1 shr 32)
        val p3 = (high shr 32) * f + (p2 shr 32)
        if (p3 shr 32 != 0uL) overflow()
        return TiffFixed128((p2 and mask) or (p3 shl 32), (p0 and mask) or (p1 shl 32),
            f != 0uL && negative)
    }

    fun shift16(): TiffFixed128 {
        if (high shr 48 != 0uL) overflow()
        return TiffFixed128((high shl 16) or (low shr 48), low shl 16, negative)
    }

    fun dividedBy(divisor: ULong): TiffFixed128 {
        if (divisor == 0uL) throw ImageDecodeException("TIFF: ReferenceBlackWhite has a zero reference range")
        if (high == 0uL) return TiffFixed128(0uL, low / divisor, negative && low >= divisor)
        var remainder = 0uL
        var resultHigh = 0uL
        var resultLow = 0uL
        for (bit in 127 downTo 0) {
            val input = if (bit >= 64) (high shr (bit - 64)) and 1uL else (low shr bit) and 1uL
            // The carry retains the 65th bit before unsigned subtraction wraps.
            val carry = remainder shr 63
            remainder = (remainder shl 1) or input
            if (carry != 0uL || remainder >= divisor) {
                remainder -= divisor
                if (bit >= 64) resultHigh = resultHigh or (1uL shl (bit - 64))
                else resultLow = resultLow or (1uL shl bit)
            }
        }
        return TiffFixed128(resultHigh, resultLow,
            (resultHigh != 0uL || resultLow != 0uL) && negative)
    }

    fun roundedByte(fractionBits: Int): Int {
        val rounded = this + of(1L shl (fractionBits - 1))
        if (rounded.negative) return 0
        if (rounded.high != 0uL || rounded.low shr fractionBits > 255uL) return 255
        return (rounded.low shr fractionBits).toInt()
    }

    internal fun words(): List<ULong> = listOf(high, low, if (negative) 1uL else 0uL)
}
