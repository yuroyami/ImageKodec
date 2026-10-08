package io.github.yuroyami.imagekodec.internal.color

import kotlin.math.pow

/**
 * The parts of an ICC profile (ICC.1:2022, and v2's ICC.1:2001-04) a conversion to sRGB reads:
 * the header's version, class, data colour space and PCS, and its tag table. Tags are read on
 * demand and any damage reads as the tag being absent, so a broken profile falls back to the
 * next way of reading the image's colour, as browsers treat one.
 */
internal class IccProfile private constructor(
    private val data: ByteArray,
    /** The major version, 2 or 4 (5 for iccMAX, which is refused). */
    val version: Int,
    val deviceClass: String,
    val colorSpace: String,
    val pcs: String,
    private val tags: Map<String, IntArray>,
) {
    fun has(signature: String): Boolean = signature in tags

    private fun u16(at: Int): Int = ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)
    private fun u32(at: Int): Long = ((u16(at).toLong()) shl 16) or u16(at + 2).toLong()
    private fun s15f16(at: Int): Double = (u32(at).toInt()).toDouble() / 65536.0
    private fun type(at: Int): String = CharArray(4) { (data[at + it].toInt() and 0xFF).toChar() }.concatToString()

    /** The tag's offset and size, checked against the profile's length. */
    private fun span(signature: String): IntArray? = tags[signature]

    /** An XYZType tag's first value. */
    fun xyz(signature: String): DoubleArray? {
        val (at, size) = span(signature) ?: return null
        if (size < 20 || type(at) != "XYZ ") return null
        return doubleArrayOf(s15f16(at + 8), s15f16(at + 12), s15f16(at + 16))
    }

    /** An s15Fixed16ArrayType tag of 9 values, such as `chad`. */
    fun matrix(signature: String): DoubleArray? {
        val (at, size) = span(signature) ?: return null
        if (size < 8 + 36 || type(at) != "sf32") return null
        return DoubleArray(9) { s15f16(at + 8 + 4 * it) }
    }

    /** A curveType or parametricCurveType tag, or the one at [at] inside another tag. */
    fun curve(signature: String): IccCurve? {
        val (at, size) = span(signature) ?: return null
        return curveAt(at, at + size)?.first
    }

    /**
     * The curve at [at], ending before [end], and where it ends, padded to 4 bytes as
     * lutAToBType's curve sets are.
     */
    fun curveAt(at: Int, end: Int): Pair<IccCurve, Int>? {
        if (at < 0 || at + 12 > end) return null
        return when (type(at)) {
            "curv" -> {
                val count = u32(at + 8)
                if (count > (end - at - 12) / 2) return null
                val n = count.toInt()
                val curve = when (n) {
                    0 -> IccCurve.Identity
                    1 -> IccCurve.Gamma(u16(at + 12) / 256.0)
                    else -> IccCurve.Table(IntArray(n) { u16(at + 12 + 2 * it) })
                }
                curve to (at + 12 + 2 * n + 3) / 4 * 4
            }
            "para" -> {
                val function = u16(at + 8)
                val count = when (function) {
                    0 -> 1
                    1 -> 3
                    2 -> 4
                    3 -> 5
                    4 -> 7
                    else -> return null
                }
                if (at + 12 + 4 * count > end) return null
                IccCurve.Parametric(function, DoubleArray(count) { s15f16(at + 12 + 4 * it) }) to (at + 12 + 4 * count + 3) / 4 * 4
            }
            else -> null
        }
    }

    /** The raw bytes of a tag, for the LUT readers. */
    fun tag(signature: String): Pair<Int, Int>? = span(signature)?.let { it[0] to it[1] }

    internal fun bytes(): ByteArray = data

    companion object {
        /** [data] as a profile, or null when its header or tag table is damaged or it is not v2 or v4. */
        fun parse(data: ByteArray): IccProfile? {
            if (data.size < 132) return null
            fun u32(at: Int): Long = ((data[at].toLong() and 0xFF) shl 24) or ((data[at + 1].toLong() and 0xFF) shl 16) or
                ((data[at + 2].toLong() and 0xFF) shl 8) or (data[at + 3].toLong() and 0xFF)
            fun text(at: Int) = CharArray(4) { (data[at + it].toInt() and 0xFF).toChar() }.concatToString()
            if (text(36) != "acsp") return null
            val version = data[8].toInt() and 0xFF
            if (version != 2 && version != 4) return null
            val count = u32(128)
            if (count > (data.size - 132) / 12) return null
            val tags = HashMap<String, IntArray>()
            for (i in 0 until count.toInt()) {
                val entry = 132 + 12 * i
                val offset = u32(entry + 4)
                val size = u32(entry + 8)
                if (offset > data.size || size > data.size - offset) continue
                tags.getOrPut(text(entry)) { intArrayOf(offset.toInt(), size.toInt()) }
            }
            return IccProfile(data, version, text(12), text(16), text(20), tags)
        }
    }
}

/** A tone curve of ICC.1 10.6 or 10.18, evaluated over 0 to 1. */
internal sealed class IccCurve {
    abstract fun eval(x: Double): Double

    object Identity : IccCurve() {
        override fun eval(x: Double): Double = x
    }

    class Gamma(private val g: Double) : IccCurve() {
        override fun eval(x: Double): Double = if (x <= 0.0) 0.0 else x.pow(g)
    }

    /**
     * Samples over 0 to 1, read as lcms2 reads them: the input rounded to 16 bits and the two
     * samples around it interpolated in its 16.16 fixed point (cmsintrp.c LinLerp1D).
     */
    class Table(private val samples: IntArray) : IccCurve() {
        override fun eval(x: Double): Double {
            val v = (x * 65535.0 + 0.5).toInt().coerceIn(0, 65535)
            val domain = samples.size - 1
            if (v == 0xFFFF || domain == 0) return samples[domain] / 65535.0
            var fixed = domain * v
            fixed += (fixed + 0x7FFF) / 0xFFFF
            val cell = fixed ushr 16
            val rest = fixed and 0xFFFF
            val y0 = samples[cell]
            val y1 = samples[cell + 1]
            return ((((y1 - y0) * rest + 0x8000) shr 16) + y0) / 65535.0
        }
    }

    /** ICC.1 Table 68's five functions, parameters g, a, b, c, d, e, f as many as the type has. */
    class Parametric(val type: Int, val p: DoubleArray) : IccCurve() {
        override fun eval(x: Double): Double {
            val g = p[0]
            fun power(v: Double) = if (v <= 0.0) 0.0 else v.pow(g)
            return when (type) {
                0 -> power(x)
                1 -> if (x >= -p[2] / p[1]) power(p[1] * x + p[2]) else 0.0
                2 -> if (x >= -p[2] / p[1]) power(p[1] * x + p[2]) + p[3] else p[3]
                3 -> if (x >= p[4]) power(p[1] * x + p[2]) else p[3] * x
                else -> if (x >= p[4]) power(p[1] * x + p[2]) + p[5] else p[3] * x + p[6]
            }
        }
    }
}
