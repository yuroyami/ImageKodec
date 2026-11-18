package io.github.yuroyami.imagekodec.codec.jxl

/**
 * Elementary functions for the encoder's decisions, written with + - * and / alone. The
 * platform's `ln`, `exp`, `pow` and `cos` may differ in their last bit from one target to the
 * next, and a decision that turns on that bit changes the file. These give the same bits on
 * every target, which the committed output hashes check.
 */
internal object JxlEncMath {
    private const val LN2 = 0.6931471805599453
    private const val PI = 3.141592653589793

    /** The base 2 logarithm of [x], for x > 0; within a few units in the last place. */
    fun log2(x: Double): Double {
        if (!(x > 0.0)) return Double.NEGATIVE_INFINITY
        // x = m * 2^e with m in [sqrt(1/2), sqrt(2)).
        var m = x
        var e = 0
        while (m >= 1.4142135623730951) {
            m *= 0.5
            e++
        }
        while (m < 0.7071067811865476) {
            m *= 2.0
            e--
        }
        // ln(m) = 2 atanh(s), s = (m - 1) / (m + 1), |s| < 0.1716.
        val s = (m - 1.0) / (m + 1.0)
        val s2 = s * s
        var term = s
        var sum = 0.0
        var k = 1
        while (k < 40) {
            sum += term / k
            term *= s2
            k += 2
        }
        return e + 2.0 * sum / LN2
    }

    /** 2 to the power [x]. */
    fun exp2(x: Double): Double {
        if (x < -1074.0) return 0.0
        var n = kotlin.math.floor(x).toInt()
        var f = x - n
        if (f > 0.5) {
            f -= 1.0
            n++
        }
        // e^(f ln 2) by its series; |f ln 2| < 0.35.
        val y = f * LN2
        var term = 1.0
        var sum = 1.0
        for (k in 1 until 24) {
            term *= y / k
            sum += term
        }
        var r = sum
        while (n > 0) {
            r *= 2.0
            n--
        }
        while (n < 0) {
            r *= 0.5
            n++
        }
        return r
    }

    /** [x] to the power [y], for x >= 0. */
    fun pow(x: Double, y: Double): Double {
        if (x == 0.0) return if (y == 0.0) 1.0 else 0.0
        return exp2(y * log2(x))
    }

    /** The real cube root of [x], by Newton's method from a power estimate. */
    fun cbrt(x: Double): Double {
        if (x == 0.0) return 0.0
        val a = if (x < 0) -x else x
        var r = exp2(log2(a) / 3.0)
        for (i in 0 until 3) r -= (r * r * r - a) / (3.0 * r * r)
        return if (x < 0) -r else r
    }

    /** The cosine of [x], by reduction to [0, pi/4] and Taylor series. */
    fun cos(x: Double): Double {
        var a = if (x < 0) -x else x
        val twoPi = 2.0 * PI
        a -= kotlin.math.floor(a / twoPi) * twoPi
        // cos is even about pi.
        if (a > PI) a = twoPi - a
        var sign = 1.0
        if (a > PI / 2) {
            a = PI - a
            sign = -1.0
        }
        return sign * if (a > PI / 4) sinSeries(PI / 2 - a) else cosSeries(a)
    }

    private fun cosSeries(a: Double): Double {
        val a2 = a * a
        var term = 1.0
        var sum = 1.0
        for (k in 1 until 14) {
            term *= -a2 / ((2 * k - 1) * (2 * k))
            sum += term
        }
        return sum
    }

    private fun sinSeries(a: Double): Double {
        val a2 = a * a
        var term = a
        var sum = a
        for (k in 1 until 14) {
            term *= -a2 / ((2 * k) * (2 * k + 1))
            sum += term
        }
        return sum
    }

    /** The square root of [x]. IEEE 754 rounds it correctly, so every target gives the same bits. */
    fun sqrt(x: Double): Double = kotlin.math.sqrt(x)
}
