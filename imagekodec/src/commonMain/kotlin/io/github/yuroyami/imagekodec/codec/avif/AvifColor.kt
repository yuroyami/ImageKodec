package io.github.yuroyami.imagekodec.codec.avif

import io.github.yuroyami.imagekodec.UnsupportedImageException
import io.github.yuroyami.imagekodec.internal.color.Cicp

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

    /** Why pictures with matrix coefficients [matrix] cannot be converted; libavif refuses the same ones. */
    fun unsupported(matrix: Int): String? = when (matrix) {
        10 -> "AVIF with BT.2020 constant luminance matrix coefficients (10)"
        11 -> "AVIF with SMPTE ST 2085 matrix coefficients (11)"
        13 -> "AVIF with chromaticity-derived constant luminance matrix coefficients (13)"
        14 -> "AVIF with ICtCp matrix coefficients (14)"
        in 15..255 -> "AVIF with matrix coefficients $matrix"
        else -> null
    }

    /** Primaries [code] as [Cicp.primaries] gives them, with CIE XYZ's, and BT.709's, as libavif assumes, for any it lacks. */
    private fun primaries(code: Int): DoubleArray = when (code) {
        10 -> doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 1.0 / 3, 1.0 / 3)
        else -> Cicp.primaries(code) ?: Cicp.primaries(1)!!
    }

    /** Kr and Kb of [cicp]'s matrix (H.273 table 4, or equations 32 and 33 for 12); BT.601 when unspecified. */
    fun coefficients(cicp: AvifCicp): DoubleArray = when (cicp.matrix) {
        1 -> doubleArrayOf(0.2126, 0.0722)
        4 -> doubleArrayOf(0.30, 0.11)
        7 -> doubleArrayOf(0.212, 0.087)
        9 -> doubleArrayOf(0.2627, 0.0593)
        12 -> {
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
     */
    fun convert(
        image: AvifPlanes,
        cicp: AvifCicp,
        alpha: AvifPlanes?,
        premultiplied: Boolean,
        out8: IntArray,
        out16: ShortArray?,
    ) {
        unsupported(cicp.matrix)?.let { throw UnsupportedImageException(it) }
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
        for (j in 0 until h) {
            val uvJ = j shr subY
            val adjRow = when {
                !subsampled || subY == 0 -> uvJ
                j == 0 || (j == h - 1 && j % 2 != 0) -> uvJ
                j % 2 != 0 -> uvJ + 1
                else -> uvJ - 1
            }
            for (i in 0 until w) {
                val yy = tableY[minOf(y0[j * sy + i].toInt() and 0xFFFF, max)]
                var r: Double
                var g: Double
                var b: Double
                if (color) {
                    val uvI = i shr subX
                    val cb: Double
                    val cr: Double
                    if (!subsampled) {
                        cb = tableUV[minOf(u0!![uvJ * suv + uvI].toInt() and 0xFFFF, max)]
                        cr = tableUV[minOf(v0!![uvJ * suv + uvI].toInt() and 0xFFFF, max)]
                    } else {
                        val adjCol = when {
                            i == 0 || (i == w - 1 && i % 2 != 0) -> uvI
                            i % 2 != 0 -> uvI + 1
                            else -> uvI - 1
                        }
                        val a = uvJ * suv + uvI
                        val c = uvJ * suv + adjCol
                        val rr = adjRow * suv + uvI
                        val d = adjRow * suv + adjCol
                        cb = tableUV[minOf(u0!![a].toInt() and 0xFFFF, max)] * (9.0 / 16) + tableUV[minOf(u0[c].toInt() and 0xFFFF, max)] * (3.0 / 16) +
                            tableUV[minOf(u0[rr].toInt() and 0xFFFF, max)] * (3.0 / 16) + tableUV[minOf(u0[d].toInt() and 0xFFFF, max)] * (1.0 / 16)
                        cr = tableUV[minOf(v0!![a].toInt() and 0xFFFF, max)] * (9.0 / 16) + tableUV[minOf(v0[c].toInt() and 0xFFFF, max)] * (3.0 / 16) +
                            tableUV[minOf(v0[rr].toInt() and 0xFFFF, max)] * (3.0 / 16) + tableUV[minOf(v0[d].toInt() and 0xFFFF, max)] * (1.0 / 16)
                    }
                    if (identity) {
                        g = yy
                        b = cb
                        r = cr
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
                    a = ((alphaPlane[j * alphaStride + i].toInt() and 0xFFFF) / alphaMax).coerceIn(0.0, 1.0)
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
                val at = j * w + i
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
