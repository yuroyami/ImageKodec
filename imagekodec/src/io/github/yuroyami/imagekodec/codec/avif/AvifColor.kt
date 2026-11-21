package io.github.yuroyami.imagekodec.codec.avif

import io.github.yuroyami.imagekodec.UnsupportedImageException
import io.github.yuroyami.imagekodec.internal.color.Cicp
import io.github.yuroyami.imagekodec.internal.color.ColorMath
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Planes of YUV samples, or of one gray plane: luma [width] by [height] at [depth] bits,
 * chroma subsampled by [subX] and [subY], each plane's rows [strides] apart, in full range or,
 * when [fullRange] is false, in the limited range of studio video. Samples are 16 bits wide,
 * unsigned.
 */
internal class AvifPlanes(
    val width: Int,
    val height: Int,
    val depth: Int,
    val subX: Int,
    val subY: Int,
    val planes: Array<ShortArray>,
    val strides: IntArray,
    val fullRange: Boolean,
) {
    /** The sample of [plane] at [index], read unsigned: a sample transform can fill all 16 bits. */
    fun sample(plane: Int, index: Int): Int = planes[plane][index].toInt() and 0xFFFF
    val monochrome: Boolean get() = planes.size == 1
}

/** The ITU-T H.273 code points a picture's samples are read with. */
internal class AvifCicp(val primaries: Int, val transfer: Int, val matrix: Int, val fullRange: Boolean) {
    fun toIntArray(): IntArray = intArrayOf(primaries, transfer, matrix, if (fullRange) 1 else 0)
}

/**
 * YUV to RGB as ITU-T H.273 defines it for each matrix, the way libavif 1.0's reference path
 * computes it (`avifImageYUVAnyToRGBAnySlow` in its reformat.c), in double precision: each
 * sample normalised by its range, 4:2:0 and 4:2:2 chroma upsampled bilinearly with the
 * chroma sample centred between its luma samples (weights 9, 3, 3 and 1 sixteenths), the
 * result clamped to [0, 1] and rounded once to the output depth.
 */
internal object AvifColor {

    /**
     * Why pictures with matrix coefficients [matrix] and transfer characteristics [transfer] cannot
     * be converted, or null. The constant luminance matrices (10 and 13) and ICtCp (14) are not
     * linear: they work in light, so they need the transfer function to undo, and zimg, which
     * converts them, takes them with the curves below. SMPTE ST 2085 (11), which neither libavif nor
     * zimg converts, has nothing to check a conversion against; 15 and above are reserved.
     */
    fun unsupported(matrix: Int, transfer: Int): String? = when (matrix) {
        10, 13 -> if (Camera.of(transfer) == null) "AVIF with constant luminance matrix coefficients ($matrix) and transfer characteristics $transfer" else null
        11 -> "AVIF with SMPTE ST 2085 matrix coefficients (11)"
        14 -> if (transfer != 16 && transfer != 18) "AVIF with ICtCp matrix coefficients (14) and transfer characteristics $transfer" else null
        in 15..255 -> "AVIF with matrix coefficients $matrix"
        else -> null
    }

    /**
     * A transfer function as a camera writes it and its inverse, what the constant luminance and
     * ICtCp conversions of H.273 and BT.2100 work through: light from 0 to 1 to the coded value.
     * [display] codes light for the picture the decoder gives, as zimg gives it: through BT.1886's
     * gamma of 2.4 for the SDR video curves, which a display of them assumes, and through the curve
     * itself for PQ and HLG.
     */
    private class Camera(val encode: (Double) -> Double, val decode: (Double) -> Double, val display: (Double) -> Double = encode) {
        companion object {
            // BT.2020's precise constants of the BT.709 curve, which BT.709, BT.601 and BT.2020 share
            // (H.273 transfer characteristics 1, 6, 14 and 15) and zimg uses for all four.
            private const val ALPHA = 1.09929682680944
            private const val BETA = 0.018053968510807

            private val bt709 = Camera(
                { l -> if (l < BETA) 4.5 * l else ALPHA * l.pow(0.45) - (ALPHA - 1) },
                { v -> if (v < 4.5 * BETA) v / 4.5 else ((v + ALPHA - 1) / ALPHA).pow(1 / 0.45) },
                { l -> l.pow(1 / 2.4) },
            )
            private val linear = Camera({ it }, { it })

            // SMPTE ST 2084, light as a fraction of 10000 cd/m2.
            private val pq = Camera({ l -> Cicp.nitsToPq(l * 10000.0) }, { v -> Cicp.pqToNits(v) / 10000.0 })

            // ARIB STD-B67 (BT.2100 table 5), scene light from 0 to 1.
            private const val HLG_A = 0.17883277
            private const val HLG_B = 1 - 4 * HLG_A
            private val HLG_C = 0.5 - HLG_A * ln(4 * HLG_A)
            private val hlg = Camera(
                { l -> if (l <= 1.0 / 12) sqrt(3 * maxOf(l, 0.0)) else HLG_A * ln(12 * l - HLG_B) + HLG_C },
                { v -> Cicp.hlgToScene(v) },
            )

            fun of(transfer: Int): Camera? = when (transfer) {
                1, 6, 14, 15 -> bt709
                8 -> linear
                16 -> pq
                18 -> hlg
                else -> null
            }
        }
    }

    // BT.2100 table 6: LMS from BT.2020 RGB, and ICtCp from L'M'S' for PQ and for HLG, which scales
    // Ct and Cp otherwise; then their inverses.
    private val LMS = doubleArrayOf(1688.0, 2146.0, 262.0, 683.0, 2951.0, 462.0, 99.0, 309.0, 3688.0).map { it / 4096 }.toDoubleArray()
    private val ICTCP_PQ = doubleArrayOf(2048.0, 2048.0, 0.0, 6610.0, -13613.0, 7003.0, 17933.0, -17390.0, -543.0).map { it / 4096 }.toDoubleArray()
    private val ICTCP_HLG = doubleArrayOf(2048.0, 2048.0, 0.0, 3625.0, -7465.0, 3840.0, 9500.0, -9212.0, -288.0).map { it / 4096 }.toDoubleArray()
    private val RGB_FROM_LMS = ColorMath.invert(LMS)!!
    private val LMS_FROM_ICTCP_PQ = ColorMath.invert(ICTCP_PQ)!!
    private val LMS_FROM_ICTCP_HLG = ColorMath.invert(ICTCP_HLG)!!

    /** BT.2100's HLG system gamma for a 1000 cd/m2 display, which zimg's OOTF applies to each channel. */
    private const val HLG_GAMMA = 1.2

    /**
     * The colour of a constant luminance picture (H.273 equations for matrix coefficients 10 and 13):
     * B' and R' are Y' plus their colour difference, scaled by N or P as the difference is negative or
     * positive, and G is what makes the luminance in light, Y = Kr R + Kg G + Kb B, hold. The light
     * is then coded for display, as zimg converts it.
     */
    private fun constantLuminance(y: Double, cb: Double, cr: Double, kr: Double, kb: Double, c: Camera, out: DoubleArray) {
        val nb = c.encode(1 - kb)
        val pb = 1 - c.encode(kb)
        val nr = c.encode(1 - kr)
        val pr = 1 - c.encode(kr)
        val b = y + 2 * cb * (if (cb <= 0) nb else pb)
        val r = y + 2 * cr * (if (cr <= 0) nr else pr)
        val lr = c.decode(r.coerceIn(0.0, 1.0))
        val lb = c.decode(b.coerceIn(0.0, 1.0))
        val lg = (c.decode(y.coerceIn(0.0, 1.0)) - kr * lr - kb * lb) / (1 - kr - kb)
        out[0] = c.display(lr)
        out[1] = c.display(lg.coerceIn(0.0, 1.0))
        out[2] = c.display(lb)
    }

    /**
     * R'G'B' of an ICtCp picture (BT.2100 table 6): L'M'S' from ICtCp, light, BT.2020 RGB, then the
     * curve again. For HLG, as zimg converts it, the light of L, M and S goes through the OOTF to the
     * display, one channel at a time, and RGB back through its inverse before the curve.
     */
    private fun ictcp(i: Double, ct: Double, cp: Double, c: Camera, hlg: Boolean, out: DoubleArray) {
        val lms = ColorMath.apply(if (hlg) LMS_FROM_ICTCP_HLG else LMS_FROM_ICTCP_PQ, doubleArrayOf(i, ct, cp))
        for (k in 0 until 3) {
            val light = c.decode(lms[k].coerceIn(0.0, 1.0))
            lms[k] = if (hlg) light.pow(HLG_GAMMA) else light
        }
        val rgb = ColorMath.apply(RGB_FROM_LMS, lms)
        for (k in 0 until 3) {
            val light = rgb[k].coerceIn(0.0, 1.0)
            out[k] = c.display(if (hlg) light.pow(1 / HLG_GAMMA) else light)
        }
    }

    /** Primaries [code] as [Cicp.primaries] gives them, with CIE XYZ's, and BT.709's, as libavif assumes, for any it lacks. */
    private fun primaries(code: Int): DoubleArray = when (code) {
        10 -> doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 1.0 / 3, 1.0 / 3)
        else -> Cicp.primaries(code) ?: Cicp.primaries(1)!!
    }

    /** Kr and Kb of [cicp]'s matrix (H.273 table 4, or equations 32 and 33 for 12 and 13); BT.601 when unspecified. */
    fun coefficients(cicp: AvifCicp): DoubleArray = when (cicp.matrix) {
        1 -> doubleArrayOf(0.2126, 0.0722)
        4 -> doubleArrayOf(0.30, 0.11)
        7 -> doubleArrayOf(0.212, 0.087)
        9, 10 -> doubleArrayOf(0.2627, 0.0593)
        12, 13 -> {
            val p = primaries(cicp.primaries)
            val (rX, rY, gX, gY) = listOf(p[0], p[1], p[2], p[3])
            val (bX, bY, wX, wY) = listOf(p[4], p[5], p[6], p[7])
            val rZ = 1 - (rX + rY)
            val gZ = 1 - (gX + gY)
            val bZ = 1 - (bX + bY)
            val wZ = 1 - (wX + wY)
            val d = wY * (rX * (gY * bZ - bY * gZ) + gX * (bY * rZ - rY * bZ) + bX * (rY * gZ - gY * rZ))
            val kr = rY * (wX * (gY * bZ - bY * gZ) + wY * (bX * gZ - gX * bZ) + wZ * (gX * bY - bX * gY)) / d
            val kb = bY * (wX * (rY * gZ - gY * rZ) + wY * (gX * rZ - rX * gZ) + wZ * (rX * gY - gX * rY)) / d
            doubleArrayOf(kr, kb)
        }
        // 5 and 6 are BT.601; 2 is unspecified and 3 reserved, which libavif reads as BT.601 too.
        else -> doubleArrayOf(0.299, 0.114)
    }

    /** libavif's `avifLimitedToFullY`: a limited-range alpha sample stretched to full range, truncating. */
    fun limitedToFull(depth: Int, v: Int): Int {
        val lo = 16 shl (depth - 8)
        val hi = 235 shl (depth - 8)
        val max = (1 shl depth) - 1
        return (((v - lo) * max.toLong()) / (hi - lo)).toInt().coerceIn(0, max)
    }

    /**
     * Converts [image] to straight-alpha ARGB in [out8] and, when [out16] is given, to 16-bit
     * samples of [channels16] channels (3, or 4 with [alpha]) whose top byte is [out8]'s value.
     * [alpha] is a full-range plane the size of [image]; [premultiplied] divides the colour by it.
     *
     * Only luma rows [top] to [bottom] convert, into rows 0 to `bottom - top` of the output, so a
     * grid can convert one band of rows at a time (#151). The planes of [image] may then hold a
     * window of the picture: their row 0 is luma row [planeTop] (chroma row `planeTop shr subY`),
     * and row 0 of [alpha]'s plane is row [alphaTop]. The edges of the upsampling stay those of the
     * whole picture, [image]'s width and height.
     */
    fun convert(
        image: AvifPlanes,
        cicp: AvifCicp,
        alpha: AvifPlanes?,
        premultiplied: Boolean,
        out8: IntArray,
        out16: ShortArray?,
        top: Int = 0,
        bottom: Int = image.height,
        planeTop: Int = 0,
        alphaTop: Int = 0,
    ) {
        unsupported(cicp.matrix, cicp.transfer)?.let { throw UnsupportedImageException(it) }
        val camera = if (cicp.matrix == 10 || cicp.matrix == 13 || cicp.matrix == 14) Camera.of(cicp.transfer) else null
        val nonLinear = DoubleArray(3)
        val depth = image.depth
        val max = (1 shl depth) - 1
        val full = cicp.fullRange
        val biasY = if (full) 0.0 else (16 shl (depth - 8)).toDouble()
        val rangeY = if (full) max.toDouble() else (219 shl (depth - 8)).toDouble()
        val biasUV = (1 shl (depth - 1)).toDouble()
        val rangeUV = if (full) max.toDouble() else (224 shl (depth - 8)).toDouble()
        val identity = cicp.matrix == 0
        val ycgco = cicp.matrix == 8
        val tableY = DoubleArray(max + 1) { (it - biasY) / rangeY }
        val tableUV = if (identity) tableY else DoubleArray(max + 1) { (it - biasUV) / rangeUV }
        val (kr, kb) = coefficients(cicp).let { it[0] to it[1] }
        val kg = 1.0 - kr - kb
        val color = !image.monochrome
        val w = image.width
        val h = image.height
        val y0 = image.planes[0]
        val u0 = if (color) image.planes[1] else null
        val v0 = if (color) image.planes[2] else null
        val sy = image.strides[0]
        val suv = if (color) image.strides[1] else 0
        val subX = image.subX
        val subY = image.subY
        val subsampled = color && (subX != 0 || subY != 0)
        val alphaPlane = alpha?.planes?.get(0)
        val alphaStride = alpha?.strides?.get(0) ?: 0
        val alphaMax = alpha?.let { ((1 shl it.depth) - 1).toDouble() } ?: 1.0
        val channels16 = if (alpha != null) 4 else 3
        val chromaTop = planeTop shr subY
        for (j in top until bottom) {
            val uvJ = j shr subY
            val adjRow = when {
                !subsampled || subY == 0 -> uvJ
                j == 0 || (j == h - 1 && j % 2 != 0) -> uvJ
                j % 2 != 0 -> uvJ + 1
                else -> uvJ - 1
            }
            for (i in 0 until w) {
                val yy = tableY[minOf(y0[(j - planeTop) * sy + i].toInt() and 0xFFFF, max)]
                var r: Double
                var g: Double
                var b: Double
                if (color) {
                    val uvI = i shr subX
                    val cb: Double
                    val cr: Double
                    if (!subsampled) {
                        cb = tableUV[minOf(u0!![(uvJ - chromaTop) * suv + uvI].toInt() and 0xFFFF, max)]
                        cr = tableUV[minOf(v0!![(uvJ - chromaTop) * suv + uvI].toInt() and 0xFFFF, max)]
                    } else {
                        val adjCol = when {
                            i == 0 || (i == w - 1 && i % 2 != 0) -> uvI
                            i % 2 != 0 -> uvI + 1
                            else -> uvI - 1
                        }
                        val a = (uvJ - chromaTop) * suv + uvI
                        val c = (uvJ - chromaTop) * suv + adjCol
                        val rr = (adjRow - chromaTop) * suv + uvI
                        val d = (adjRow - chromaTop) * suv + adjCol
                        cb = tableUV[minOf(u0!![a].toInt() and 0xFFFF, max)] * (9.0 / 16) + tableUV[minOf(u0[c].toInt() and 0xFFFF, max)] * (3.0 / 16) +
                            tableUV[minOf(u0[rr].toInt() and 0xFFFF, max)] * (3.0 / 16) + tableUV[minOf(u0[d].toInt() and 0xFFFF, max)] * (1.0 / 16)
                        cr = tableUV[minOf(v0!![a].toInt() and 0xFFFF, max)] * (9.0 / 16) + tableUV[minOf(v0[c].toInt() and 0xFFFF, max)] * (3.0 / 16) +
                            tableUV[minOf(v0[rr].toInt() and 0xFFFF, max)] * (3.0 / 16) + tableUV[minOf(v0[d].toInt() and 0xFFFF, max)] * (1.0 / 16)
                    }
                    if (identity) {
                        g = yy
                        b = cb
                        r = cr
                    } else if (camera != null) {
                        if (cicp.matrix == 14) ictcp(yy, cb, cr, camera, cicp.transfer == 18, nonLinear) else constantLuminance(yy, cb, cr, kr, kb, camera, nonLinear)
                        r = nonLinear[0]
                        g = nonLinear[1]
                        b = nonLinear[2]
                    } else if (ycgco) {
                        val t = yy - cb
                        g = yy + cb
                        b = t - cr
                        r = t + cr
                    } else {
                        r = yy + (2 * (1 - kr)) * cr
                        b = yy + (2 * (1 - kb)) * cb
                        g = yy - ((2 * ((kr * (1 - kr) * cr) + (kb * (1 - kb) * cb))) / kg)
                    }
                } else {
                    r = yy
                    g = yy
                    b = yy
                }
                r = r.coerceIn(0.0, 1.0)
                g = g.coerceIn(0.0, 1.0)
                b = b.coerceIn(0.0, 1.0)
                var a = 1.0
                if (alphaPlane != null) {
                    a = ((alphaPlane[(j - alphaTop) * alphaStride + i].toInt() and 0xFFFF) / alphaMax).coerceIn(0.0, 1.0)
                    if (premultiplied) {
                        if (a == 0.0) {
                            r = 0.0
                            g = 0.0
                            b = 0.0
                        } else if (a < 1.0) {
                            r = minOf(r / a, 1.0)
                            g = minOf(g / a, 1.0)
                            b = minOf(b / a, 1.0)
                        }
                    }
                }
                val r8 = (0.5 + r * 255).toInt()
                val g8 = (0.5 + g * 255).toInt()
                val b8 = (0.5 + b * 255).toInt()
                val a8 = (0.5 + a * 255).toInt()
                val at = (j - top) * w + i
                out8[at] = (a8 shl 24) or (r8 shl 16) or (g8 shl 8) or b8
                if (out16 != null) {
                    val o = at * channels16
                    out16[o] = within(r, r8)
                    out16[o + 1] = within(g, g8)
                    out16[o + 2] = within(b, b8)
                    if (channels16 == 4) out16[o + 3] = within(a, a8)
                }
            }
        }
    }

    /** [v] at 16 bits, kept in the bucket whose top byte is [v8], so the 16-bit image narrows to the 8-bit one. */
    private fun within(v: Double, v8: Int): Short =
        (0.5 + v * 65535).toInt().coerceIn(v8 shl 8, (v8 shl 8) or 255).toShort()
}
