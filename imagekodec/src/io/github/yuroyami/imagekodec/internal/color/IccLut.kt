package io.github.yuroyami.imagekodec.internal.color

/**
 * A device-to-PCS lookup table of ICC.1 (10.10 lut8Type, 10.11 lut16Type and 10.12
 * lutAToBType), as lcms2 reads one into a floating-point pipeline: every stage works on values
 * from 0 to 1, a table is interpolated as cmsintrp.c interpolates it, and the result is PCS
 * Lab or XYZ in lcms2's own normalized encoding, which [toXyz] decodes.
 */
internal class IccLut private constructor(
    val inputs: Int,
    private val stages: List<(DoubleArray) -> DoubleArray>,
    /** The PCS is Lab, and with [legacyLab], in v2's 16-bit encoding (L* 100 at 0xFF00). */
    private val lab: Boolean,
    private val legacyLab: Boolean,
) {
    /** [device], 0 to 1 a channel, as D50 XYZ. */
    fun toXyz(device: DoubleArray): DoubleArray {
        var v = device
        for (stage in stages) v = stage(v)
        if (!lab) {
            // u1Fixed15, as lcms2's MAX_ENCODEABLE_XYZ normalizes it.
            val s = 1.0 + 32767.0 / 32768.0
            return doubleArrayOf(v[0] * s, v[1] * s, v[2] * s)
        }
        // _cmsStageAllocLabV2ToV4 for lut16Type, then the v4 encoding: L* over 0 to 100, a* and b* over -128 to 127.
        val f = if (legacyLab) 65535.0 / 65280.0 else 1.0
        return ColorMath.labToXyz(doubleArrayOf(v[0] * f * 100.0, v[1] * f * 255.0 - 128.0, v[2] * f * 255.0 - 128.0))
    }

    companion object {
        /** cmsintrp.c's 16.16 fixed point: a cell index times the grid's domain, spread over 0x10000 a cell. */
        private fun toFixedDomain(a: Int) = a + (a + 0x7FFF) / 0xFFFF

        /** LinearInterp: [l] to [h] at [a] of 0x10000. */
        private fun lerp(a: Int, l: Int, h: Int) = ((((h - l) * a + 0x8000) shr 16) + l) and 0xFFFF

        /**
         * The colour lookup table of [grid] points along each input, [outputs] 16-bit values a
         * node, first input slowest, interpolated as lcms2 interpolates every table an ICC
         * profile holds, even in its floating-point pipeline (EvaluateCLUTfloatIn16): the inputs
         * rounded to 16 bits, then in fixed point linear in one dimension, bilinear in two,
         * tetrahedral in three, and in more, linear between two tables of one dimension fewer
         * along the first.
         */
        fun clut(grid: IntArray, outputs: Int, table: IntArray): (DoubleArray) -> DoubleArray {
            val inputs = grid.size
            // The stride of each input in the table, the last input fastest (lcms2's opta, reversed).
            val stride = IntArray(inputs)
            var s = outputs
            for (k in inputs - 1 downTo 0) {
                stride[k] = s
                s *= grid[k]
            }
            /**
             * TetrahedralInterp16 over the three inputs from [first], or, with [exact] off, the
             * three-dimensional step of Eval4Inputs, which rounds its sum another way.
             */
            fun tetrahedral(input: IntArray, first: Int, base: Int, out: IntArray, exact: Boolean) {
                val fx = toFixedDomain(input[first] * (grid[first] - 1))
                val fy = toFixedDomain(input[first + 1] * (grid[first + 1] - 1))
                val fz = toFixedDomain(input[first + 2] * (grid[first + 2] - 1))
                val rx = fx and 0xFFFF
                val ry = fy and 0xFFFF
                val rz = fz and 0xFFFF
                val origin = base + stride[first] * (fx shr 16) + stride[first + 1] * (fy shr 16) + stride[first + 2] * (fz shr 16)
                val x1 = if (input[first] == 0xFFFF) 0 else stride[first]
                val y1 = if (input[first + 1] == 0xFFFF) 0 else stride[first + 1]
                val z1 = if (input[first + 2] == 0xFFFF) 0 else stride[first + 2]
                for (o in 0 until outputs) {
                    fun d(x: Int, y: Int, z: Int) = table[origin + x + y + z + o]
                    val c0 = d(0, 0, 0)
                    val c1: Int
                    val c2: Int
                    val c3: Int
                    when {
                        rx >= ry && ry >= rz -> {
                            c1 = d(x1, 0, 0) - c0; c2 = d(x1, y1, 0) - d(x1, 0, 0); c3 = d(x1, y1, z1) - d(x1, y1, 0)
                        }
                        rx >= rz && rz >= ry -> {
                            c1 = d(x1, 0, 0) - c0; c2 = d(x1, y1, z1) - d(x1, 0, z1); c3 = d(x1, 0, z1) - d(x1, 0, 0)
                        }
                        rz >= rx && rx >= ry -> {
                            c1 = d(x1, 0, z1) - d(0, 0, z1); c2 = d(x1, y1, z1) - d(x1, 0, z1); c3 = d(0, 0, z1) - c0
                        }
                        ry >= rx && rx >= rz -> {
                            c1 = d(x1, y1, 0) - d(0, y1, 0); c2 = d(0, y1, 0) - c0; c3 = d(x1, y1, z1) - d(x1, y1, 0)
                        }
                        ry >= rz && rz >= rx -> {
                            c1 = d(x1, y1, z1) - d(0, y1, z1); c2 = d(0, y1, 0) - c0; c3 = d(0, y1, z1) - d(0, y1, 0)
                        }
                        else -> {
                            c1 = d(x1, y1, z1) - d(0, y1, z1); c2 = d(0, y1, z1) - d(0, 0, z1); c3 = d(0, 0, z1) - c0
                        }
                    }
                    val rest = c1 * rx + c2 * ry + c3 * rz
                    out[o] = if (exact) {
                        val t = rest + 0x8001
                        (c0 + ((t + (t shr 16)) shr 16)) and 0xFFFF
                    } else {
                        (c0 + ((toFixedDomain(rest) + 0x8000) shr 16)) and 0xFFFF
                    }
                }
            }
            fun eval(input: IntArray, first: Int, base: Int, out: IntArray) {
                when (inputs - first) {
                    1 -> {
                        // LinLerp1D and Eval1Input
                        val v = input[first]
                        val domain = grid[first] - 1
                        if (v == 0xFFFF) {
                            for (o in 0 until outputs) out[o] = table[base + domain * stride[first] + o]
                        } else {
                            val f = toFixedDomain(v * domain)
                            val k0 = base + (f shr 16) * stride[first]
                            val k1 = k0 + stride[first]
                            for (o in 0 until outputs) out[o] = lerp(f and 0xFFFF, table[k0 + o], table[k1 + o])
                        }
                    }
                    2 -> {
                        // BilinearInterp16
                        val fx = toFixedDomain(input[first] * (grid[first] - 1))
                        val fy = toFixedDomain(input[first + 1] * (grid[first + 1] - 1))
                        val rx = fx and 0xFFFF
                        val ry = fy and 0xFFFF
                        val x0 = base + stride[first] * (fx shr 16)
                        val x1 = x0 + if (input[first] == 0xFFFF) 0 else stride[first]
                        val y0 = stride[first + 1] * (fy shr 16)
                        val y1 = y0 + if (input[first + 1] == 0xFFFF) 0 else stride[first + 1]
                        fun l(a: Int, lo: Int, hi: Int) = (lo + (((hi - lo) * a + 0x8000) shr 16)) and 0xFFFF
                        for (o in 0 until outputs) {
                            val dx0 = l(rx, table[x0 + y0 + o], table[x1 + y0 + o])
                            val dx1 = l(rx, table[x0 + y1 + o], table[x1 + y1 + o])
                            out[o] = l(ry, dx0, dx1)
                        }
                    }
                    3 -> tetrahedral(input, first, base, out, exact = first == 0)
                    else -> {
                        // Eval4Inputs and up: linear along the first input between two tables.
                        val f = toFixedDomain(input[first] * (grid[first] - 1))
                        val k0 = base + stride[first] * (f shr 16)
                        val k1 = k0 + if (input[first] == 0xFFFF) 0 else stride[first]
                        val a = IntArray(outputs)
                        val b = IntArray(outputs)
                        eval(input, first + 1, k0, a)
                        eval(input, first + 1, k1, b)
                        for (o in 0 until outputs) out[o] = lerp(f and 0xFFFF, a[o], b[o])
                    }
                }
            }
            return { device ->
                val input = IntArray(inputs) { quickSaturateWord(device[it] * 65535.0) }
                val out = IntArray(outputs)
                eval(input, 0, 0, out)
                DoubleArray(outputs) { out[it] / 65535.0 }
            }
        }

        /** _cmsQuickSaturateWord: [d] rounded to 16 bits, its fraction first rounded to 16 bits as lcms2's magic-number floor does. */
        fun quickSaturateWord(d: Double): Int {
            val v = d + 0.5
            if (v <= 0.0) return 0
            if (v >= 65535.0) return 0xFFFF
            val fixed = kotlin.math.round(v * 65536.0).toLong()
            return (fixed shr 16).toInt()
        }

        private fun curves(curves: List<IccCurve>): (DoubleArray) -> DoubleArray =
            { v -> DoubleArray(v.size) { curves[it].eval(v[it]) } }

        /** A 3 by 3 matrix and an offset, applied to the values from 0 to 1. */
        private fun matrix(m: DoubleArray, offset: DoubleArray): (DoubleArray) -> DoubleArray = { v ->
            DoubleArray(3) { r -> m[3 * r] * v[0] + m[3 * r + 1] * v[1] + m[3 * r + 2] * v[2] + offset[r] }
        }

        /** The A2B tag [signature] of [profile], or null when it is absent, damaged or not device to PCS. */
        fun read(profile: IccProfile, signature: String): IccLut? {
            val (at, size) = profile.tag(signature) ?: return null
            val d = profile.bytes()
            val end = at + size
            if (size < 32) return null
            fun u8(i: Int) = d[i].toInt() and 0xFF
            fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
            fun s15f16(i: Int) = (((u16(i) shl 16) or u16(i + 2))).toDouble() / 65536.0
            fun u32(i: Int) = ((u16(i).toLong() shl 16) or u16(i + 2).toLong())
            val type = CharArray(4) { u8(at + it).toChar() }.concatToString()
            val lab = profile.pcs == "Lab "
            val expectedInputs = when (profile.colorSpace) {
                "GRAY" -> 1
                "RGB ", "Lab ", "XYZ ", "YCbr", "HSV ", "HLS ", "Yxy ", "Luv ", "CMY " -> 3
                "CMYK" -> 4
                else -> profile.colorSpace.takeIf { it.endsWith("CLR") }?.substring(0, 1)?.toIntOrNull(16) ?: return null
            }
            return when (type) {
                "mft1", "mft2" -> {
                    val sixteen = type == "mft2"
                    val inputs = u8(at + 8)
                    val outputs = u8(at + 9)
                    val grid = u8(at + 10)
                    // No grid points means no table, which only a space of three to three can do without.
                    if (inputs != expectedInputs || outputs != 3 || grid == 1 || (grid == 0 && inputs != 3)) return null
                    val e = DoubleArray(9) { s15f16(at + 12 + 4 * it) }
                    val inEntries = if (sixteen) u16(at + 48) else 256
                    val outEntries = if (sixteen) u16(at + 50) else 256
                    // Type_LUT16_Read's bounds.
                    if (inEntries < 2 || outEntries < 2 || inEntries > 0x7FFF || outEntries > 0x7FFF) return null
                    var p = at + if (sixteen) 52 else 48
                    val width = if (sixteen) 2 else 1
                    fun value(i: Int): Int = if (sixteen) u16(i) else u8(i) * 257
                    var nodes = 1L
                    // Fifteen inputs of 255 points pass a Long, so stop at what a tag could hold.
                    repeat(inputs) {
                        nodes *= grid
                        if (nodes > Int.MAX_VALUE) return null
                    }
                    val need = (inputs.toLong() * inEntries + nodes * outputs + outputs.toLong() * outEntries) * width
                    if (need > end - p) return null
                    val stages = ArrayList<(DoubleArray) -> DoubleArray>()
                    // lcms2 applies the matrix of a three-input table that is not the identity (Type_LUT16_Read).
                    val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
                    if (inputs == 3 && !e.contentEquals(identity)) stages += matrix(e, DoubleArray(3))
                    val inCurves = List(inputs) { k -> IccCurve.Table(IntArray(inEntries) { value(p + (k * inEntries + it) * width) }) }
                    p += inputs * inEntries * width
                    val table = IntArray((nodes * outputs).toInt()) { if (sixteen) u16(p + 2 * it) else u8(p + it) * 257 }
                    p += (nodes * outputs).toInt() * width
                    val outCurves = List(outputs) { k -> IccCurve.Table(IntArray(outEntries) { value(p + (k * outEntries + it) * width) }) }
                    stages += curves(inCurves)
                    if (grid > 0) stages += clut(IntArray(inputs) { grid }, outputs, table)
                    stages += curves(outCurves)
                    IccLut(inputs, stages, lab, legacyLab = lab && sixteen)
                }
                "mAB " -> {
                    val inputs = u8(at + 8)
                    val outputs = u8(at + 9)
                    if (inputs != expectedInputs || outputs != 3) return null
                    val offB = u32(at + 12)
                    val offMatrix = u32(at + 16)
                    val offM = u32(at + 20)
                    val offClut = u32(at + 24)
                    val offA = u32(at + 28)
                    // Every element lies inside the tag, so an offset past its end is damage.
                    if (listOf(offB, offMatrix, offM, offClut, offA).any { it > size }) return null
                    fun curveSet(offset: Long, n: Int): List<IccCurve>? {
                        var p = at + offset.toInt()
                        return List(n) {
                            val (curve, next) = profile.curveAt(p, end) ?: return null
                            p = next
                            curve
                        }
                    }
                    val stages = ArrayList<(DoubleArray) -> DoubleArray>()
                    if (offA != 0L) stages += curves(curveSet(offA, inputs) ?: return null)
                    if (offClut != 0L) {
                        val c = at + offClut.toInt()
                        if (c + 20 > end) return null
                        val grid = IntArray(inputs) { u8(c + it) }
                        if (grid.any { it < 2 }) return null
                        val precision = u8(c + 16)
                        if (precision != 1 && precision != 2) return null
                        var nodes = 1L
                        for (g in grid) {
                            nodes *= g
                            if (nodes > Int.MAX_VALUE) return null
                        }
                        if (nodes * outputs * precision > end - c - 20) return null
                        val table = IntArray((nodes * outputs).toInt()) {
                            if (precision == 2) u16(c + 20 + 2 * it) else u8(c + 20 + it) * 257
                        }
                        stages += clut(grid, outputs, table)
                    } else if (inputs != outputs) {
                        return null
                    }
                    if (offM != 0L) stages += curves(curveSet(offM, outputs) ?: return null)
                    if (offMatrix != 0L) {
                        val m = at + offMatrix.toInt()
                        if (m + 48 > end) return null
                        stages += matrix(DoubleArray(9) { s15f16(m + 4 * it) }, DoubleArray(3) { s15f16(m + 36 + 4 * it) })
                    }
                    if (offB == 0L) return null
                    stages += curves(curveSet(offB, outputs) ?: return null)
                    IccLut(inputs, stages, lab, legacyLab = false)
                }
                else -> null
            }
        }
    }
}
