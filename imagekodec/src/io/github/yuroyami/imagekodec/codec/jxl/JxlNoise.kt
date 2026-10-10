// Altered Kotlin port of parts of libjxl 0.11.1 (lib/jxl/dec_noise.cc, noise.h,
// xorshift128plus-inl.h and render_pipeline/stage_noise.cc).
// Copyright (c) the JPEG XL Project Authors. All rights reserved.
// Use of the original source is governed by a BSD-style license; see NOTICE.

package io.github.yuroyami.imagekodec.codec.jxl

/**
 * Noise: synthetic film grain that the decoder makes from random numbers and adds to the
 * frame. The file holds only how strong the grain is at each brightness.
 */
internal class JxlNoise private constructor(
    /** The strength of the grain at 8 levels of brightness. Between two levels it follows a straight line. */
    private val lut: DoubleArray,
) {
    /** False when every strength is close to zero. [apply] then changes nothing. */
    val hasAny: Boolean = lut.any { it > NEGLIGIBLE }

    /**
     * Adds noise to the three planes X, Y and B in place. The planes have the frame's size
     * after upsampling. [yToX] and [yToB] are the frame's base colour correlations.
     *
     * libjxl seeds its random numbers for each square of [groupDim] by [groupDim] samples of
     * the upsampled frame from the square's position and from two counts of the frames
     * before this one: [visibleFrameIndex] and [nonvisibleFrameIndex].
     */
    fun apply(
        color: Array<JxlPlane>,
        yToX: Double,
        yToB: Double,
        groupDim: Int,
        visibleFrameIndex: Int,
        nonvisibleFrameIndex: Int,
    ) {
        if (!hasAny) return
        require(groupDim > 0)
        if (color.size != 3) jxlFail("noise needs three colour planes")
        val w = color[0].w
        val h = color[0].h
        if (color[1].w != w || color[1].h != h || color[2].w != w || color[2].h != h) {
            jxlFail("noise needs colour planes of one size")
        }
        if (w == 0 || h == 0) return
        val planeX = color[0].data
        val planeY = color[1].data
        val planeB = color[2].data
        val grain = Grain(w, h, groupDim, visibleFrameIndex, nonvisibleFrameIndex)
        val columns = DoubleArray(w + 2 * BORDER)
        val red = DoubleArray(w)
        val green = DoubleArray(w)
        val both = DoubleArray(w)
        val ytox = f32(yToX)
        val ytob = f32(yToB)

        for (y in 0 until h) {
            grain.generateThrough(minOf(h - 1, y + BORDER))
            grain.convolve(0, y, columns, red)
            grain.convolve(1, y, columns, green)
            grain.convolve(2, y, columns, both)
            var at = y * w
            for (x in 0 until w) {
                val vx = planeX[at].toDouble()
                val vy = planeY[at].toDouble()
                // X is half of red less green and Y half of their sum, so these are the two brightnesses.
                val strengthG = strength((vy - vx) * 0.5)
                val strengthR = strength((vy + vx) * 0.5)
                // Red and green share most of their noise, so the grain has little colour.
                val shared = RG_CORRELATION * (both[x] * NORMALIZER)
                val noiseR = strengthR * ((1.0 - RG_CORRELATION) * (red[x] * NORMALIZER) + shared)
                val noiseG = strengthG * ((1.0 - RG_CORRELATION) * (green[x] * NORMALIZER) + shared)
                val sum = noiseR + noiseG
                planeX[at] = (ytox * sum + (noiseR - noiseG) + vx).toFloat()
                planeY[at] = (vy + sum).toFloat()
                planeB[at] = (ytob * sum + planeB[at]).toFloat()
                at++
            }
        }
    }

    /** The strength of the noise at brightness [v], from 0 to 1. libjxl's StrengthEvalLut. */
    private fun strength(v: Double): Double {
        var scaled = v * (LUT_SIZE - 2)
        // This also turns a sample that is not a number into the first entry.
        if (!(scaled > 0.0)) scaled = 0.0
        val index: Int
        val frac: Double
        if (scaled >= LUT_SIZE - 1) {
            index = LUT_SIZE - 2
            frac = 1.0
        } else {
            index = scaled.toInt()
            frac = scaled - index
        }
        val s = (lut[index + 1] - lut[index]) * frac + lut[index]
        return if (s > 1.0) 1.0 else if (s > 0.0) s else 0.0
    }

    /**
     * The three planes of random numbers, a few rows at a time. Plane 0 is the noise of red,
     * plane 1 of green, plane 2 the noise both share. libjxl fills whole planes; this keeps
     * only the rows the 5 by 5 filter needs, and gives the same numbers.
     */
    private class Grain(
        private val w: Int,
        private val h: Int,
        private val groupDim: Int,
        visibleFrameIndex: Int,
        nonvisibleFrameIndex: Int,
    ) {
        private val tilesX = ((w.toLong() + groupDim - 1) / groupDim).toInt()
        private val frameSeed = ((visibleFrameIndex.toLong() and MASK32) shl 32) + (nonvisibleFrameIndex.toLong() and MASK32)

        /** A generator for each plane and each square of the row of squares being made. */
        private val state = LongArray(3 * tilesX * JxlXorshift.STATE_SIZE)
        private val batch = LongArray(JxlXorshift.LANES)

        /** The last rows made of each plane, row y in slot y modulo [RING], with [BORDER] mirrored samples at each end. */
        private val rows = Array(3 * RING) { DoubleArray(w + 2 * BORDER) }
        private var made = -1

        /** Makes every row up to row [last]. Rows before [last] - 4 are then gone. */
        fun generateThrough(last: Int) {
            while (made < last) generate(++made)
        }

        private fun generate(y: Int) {
            if (y % groupDim == 0) startSquares(y / groupDim)
            for (plane in 0 until 3) {
                val row = rows[plane * RING + y % RING]
                for (tx in 0 until tilesX) {
                    val x0 = tx * groupDim
                    val xsize = minOf(groupDim, w - x0)
                    val at = (plane * tilesX + tx) * JxlXorshift.STATE_SIZE
                    var x = 0
                    // libjxl draws 16 numbers at a time, and drops what is left of them at the end of a row.
                    while (x < xsize) {
                        JxlXorshift.fill(state, at, batch)
                        val n = minOf(16, xsize - x)
                        for (j in 0 until n) {
                            val bits = (batch[j ushr 1] ushr (32 * (j and 1))).toInt()
                            // 23 random bits under an exponent of zero: a float from 1 up to 2.
                            row[BORDER + x0 + x + j] = Float.fromBits((bits ushr 9) or 0x3F800000).toDouble()
                        }
                        x += 16
                    }
                }
                for (i in 1..BORDER) {
                    row[BORDER - i] = row[BORDER + mirror(-i, w)]
                    row[BORDER + w - 1 + i] = row[BORDER + mirror(w - 1 + i, w)]
                }
            }
        }

        /**
         * Seeds the generators of the row of squares [ty]. libjxl fills plane 0 of a square,
         * then plane 1, then plane 2 from one generator, so the generators of planes 1 and 2
         * start where the plane before ends.
         */
        private fun startSquares(ty: Int) {
            val ysize = minOf(groupDim, h - ty * groupDim)
            for (tx in 0 until tilesX) {
                val xsize = minOf(groupDim, w - tx * groupDim)
                val fills = ysize.toLong() * ((xsize + 15) / 16)
                val x0 = tx.toLong() * groupDim and MASK32
                val y0 = ty.toLong() * groupDim and MASK32
                JxlXorshift.seed(state, tx * JxlXorshift.STATE_SIZE, frameSeed, (x0 shl 32) + y0)
                for (plane in 1 until 3) {
                    val from = ((plane - 1) * tilesX + tx) * JxlXorshift.STATE_SIZE
                    val to = (plane * tilesX + tx) * JxlXorshift.STATE_SIZE
                    state.copyInto(state, to, from, from + JxlXorshift.STATE_SIZE)
                    JxlXorshift.skip(state, to, fills)
                }
            }
        }

        /**
         * Filters row [y] of [plane] into [out]: 0.16 times the sum of the 24 samples around
         * each sample, less 3.84 times the sample. The filter sums to zero, so the result is
         * spread around zero. Rows and columns outside the plane are its own, mirrored.
         */
        fun convolve(plane: Int, y: Int, columns: DoubleArray, out: DoubleArray) {
            val base = plane * RING
            val r0 = rows[base + mirror(y - 2, h) % RING]
            val r1 = rows[base + mirror(y - 1, h) % RING]
            val r2 = rows[base + y % RING]
            val r3 = rows[base + mirror(y + 1, h) % RING]
            val r4 = rows[base + mirror(y + 2, h) % RING]
            // Every sample is a multiple of 2^-23 under 2, so these sums are exact in a Double.
            for (x in 0 until w + 2 * BORDER) columns[x] = r0[x] + r1[x] + r3[x] + r4[x]
            for (x in 0 until w) {
                val others = columns[x] + columns[x + 1] + columns[x + 2] + columns[x + 3] + columns[x + 4] +
                    r2[x] + r2[x + 1] + r2[x + 3] + r2[x + 4]
                out[x] = f32(others * AROUND_WEIGHT + f32(r2[x + 2] * CENTER_WEIGHT))
            }
        }
    }

    companion object {
        private const val LUT_SIZE = 8
        private const val BORDER = 2
        private const val RING = 5
        private const val MASK32 = 0xFFFFFFFFL
        private const val RG_CORRELATION = 0.9921875

        private fun f32(x: Double): Double = x.toFloat().toDouble()

        // libjxl's constants, which are single precision.
        private val NEGLIGIBLE = f32(1e-3)
        private val NORMALIZER = f32(0.22)
        private val AROUND_WEIGHT = f32(0.16)
        private val CENTER_WEIGHT = f32(-3.84)

        /** The index inside 0 until [size] that [x] mirrors to, with the edge sample repeated. libjxl's Mirror. */
        private fun mirror(x: Int, size: Int): Int {
            var v = x
            while (v < 0 || v >= size) v = if (v < 0) -v - 1 else 2 * size - 1 - v
            return v
        }

        /** Reads the 8 strengths, each 10 bits. */
        fun read(br: JxlBitReader): JxlNoise = JxlNoise(DoubleArray(LUT_SIZE) { br.bits(10) / 1024.0 })
    }
}

/**
 * libjxl's Xorshift128Plus: 8 xorshift128+ generators side by side, which give 8 times 64
 * random bits a step. A state is 16 values in a LongArray: s0 of each generator, then s1.
 */
internal object JxlXorshift {
    const val LANES = 8
    const val STATE_SIZE = 2 * LANES

    // 0x9E3779B97F4A7C15, 0xBF58476D1CE4E5B9 and 0x94D049BB133111EB as signed values.
    private const val GOLDEN = -0x61C8864680B583EBL
    private const val MIX1 = -0x40A7B892E31B1A47L
    private const val MIX2 = -0x6B2FB644ECCEEE15L

    fun splitMix64(z0: Long): Long {
        var z = (z0 xor (z0 ushr 30)) * MIX1
        z = (z xor (z ushr 27)) * MIX2
        return z xor (z ushr 31)
    }

    /** Sets the state at [at] from two 64-bit seeds, as the constructor of four 32-bit seeds does. */
    fun seed(state: LongArray, at: Int, seed0: Long, seed1: Long) {
        state[at] = splitMix64(seed0 + GOLDEN)
        state[at + LANES] = splitMix64(seed1 + GOLDEN)
        for (i in 1 until LANES) {
            state[at + i] = splitMix64(state[at + i - 1])
            state[at + LANES + i] = splitMix64(state[at + LANES + i - 1])
        }
    }

    /** Steps the state at [at] once and writes its 8 values to [out]. */
    fun fill(state: LongArray, at: Int, out: LongArray) {
        for (i in 0 until LANES) {
            var s1 = state[at + i]
            val s0 = state[at + LANES + i]
            out[i] = s1 + s0
            state[at + i] = s0
            s1 = s1 xor (s1 shl 23)
            state[at + LANES + i] = s1 xor s0 xor (s1 ushr 18) xor (s0 ushr 5)
        }
    }

    /** Steps the state at [at] [steps] times and drops the values. */
    fun skip(state: LongArray, at: Int, steps: Long) {
        for (i in 0 until LANES) {
            var a = state[at + i]
            var b = state[at + LANES + i]
            var left = steps
            while (left-- > 0) {
                var s1 = a
                a = b
                s1 = s1 xor (s1 shl 23)
                b = s1 xor a xor (s1 ushr 18) xor (a ushr 5)
            }
            state[at + i] = a
            state[at + LANES + i] = b
        }
    }
}
