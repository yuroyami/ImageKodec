package io.github.yuroyami.imagekodec.codec

/**
 * ARGB to the YUV 4:2:0 planes of a VP8 frame (#178), with the BT.601 studio-range matrix that
 * RFC 6386 section 9.2 names: Y = 16 + 219 (0.299 R + 0.587 G + 0.114 B) / 255, and U and V the
 * blue and red differences scaled to 224 levels around 128, in 16-bit fixed point.
 *
 * A chroma sample is the alpha-weighted mean of its four pixels, so a transparent pixel lends no
 * colour to an opaque neighbour, and a fully transparent pixel first takes the colour of the
 * nearest visible one to its left or above, so that hidden areas cost few bits.
 *
 * With sharp YUV, Y, U and V are then refined, a few rounds, against the picture the decoder will
 * show: each round upsamples the chroma as the decoder does, converts back to RGB, and moves every
 * sample by the error that falls to it.
 */
internal object Vp8Import {

    private const val YR = 16829
    private const val YG = 33039
    private const val YB = 6416
    private const val UR = -9714
    private const val UG = -19070
    private const val UB = 28784
    private const val VR = 28784
    private const val VG = -24103
    private const val VB = -4681

    private fun clip(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    fun luma(r: Int, g: Int, b: Int): Int = (YR * r + YG * g + YB * b + (16 shl 16) + (1 shl 15)) shr 16

    /** U and V of the sum of four pixels' [r], [g], [b]. */
    private fun u4(r: Int, g: Int, b: Int): Int = (UR * r + UG * g + UB * b + (128 shl 18) + (1 shl 17)) shr 18
    private fun v4(r: Int, g: Int, b: Int): Int = (VR * r + VG * g + VB * b + (128 shl 18) + (1 shl 17)) shr 18

    fun planes(argb: IntArray, width: Int, height: Int, sharp: Boolean): Vp8Planes {
        val p = Vp8Planes(width, height)
        val rgb = visibleColours(argb, width, height)
        for (y in 0 until p.mbH * 16) {
            val sy = minOf(y, height - 1)
            for (x in 0 until p.yStride) {
                val c = rgb[sy * width + minOf(x, width - 1)]
                p.y[y * p.yStride + x] = luma((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF).toByte()
            }
        }
        val cw = (width + 1) shr 1
        val ch = (height + 1) shr 1
        for (cy in 0 until p.mbH * 8) {
            val y0 = minOf(cy, ch - 1) * 2
            for (cx in 0 until p.uvStride) {
                val x0 = minOf(cx, cw - 1) * 2
                var rs = 0; var gs = 0; var bs = 0; var aSum = 0
                var rw = 0; var gw = 0; var bw = 0
                for (j in 0 until 2) for (i in 0 until 2) {
                    val at = minOf(y0 + j, height - 1) * width + minOf(x0 + i, width - 1)
                    val c = rgb[at]
                    val a = argb[at] ushr 24
                    val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                    rs += r; gs += g; bs += b
                    rw += a * r; gw += a * g; bw += a * b
                    aSum += a
                }
                if (aSum in 1 until 4 * 255) {
                    rs = (rw * 4 + aSum / 2) / aSum
                    gs = (gw * 4 + aSum / 2) / aSum
                    bs = (bw * 4 + aSum / 2) / aSum
                }
                p.u[cy * p.uvStride + cx] = clip(u4(rs, gs, bs)).toByte()
                p.v[cy * p.uvStride + cx] = clip(v4(rs, gs, bs)).toByte()
            }
        }
        if (sharp) sharpen(p, rgb, argb, width, height)
        return p
    }

    /** [argb] with each fully transparent pixel given the colour of the visible one before it. */
    private fun visibleColours(argb: IntArray, width: Int, height: Int): IntArray {
        var any = false
        for (c in argb) if (c ushr 24 == 0) { any = true; break }
        if (!any) return argb
        val out = argb.copyOf()
        for (y in 0 until height) {
            var last = -1
            for (x in 0 until width) {
                val i = y * width + x
                if (argb[i] ushr 24 != 0) {
                    last = argb[i]
                } else {
                    out[i] = if (last != -1) last else if (y > 0) out[i - width] else 0xFF808080.toInt()
                }
            }
        }
        return out
    }

    // --- sharp YUV ------------------------------------------------------------------------------

    /**
     * The decoder's chroma upsampling (the "fancy" upsampler of WebP): each output sample weighs
     * the nearest of the four surrounding chroma samples 9, the two beside it 3 and the far one 1,
     * in sixteenths.
     */
    private fun upsample(c: ByteArray, stride: Int, cw: Int, ch: Int, x: Int, y: Int): Int {
        // The near sample is the one whose 2x2 block holds the pixel, the far one diagonal to it.
        val ax = x shr 1
        val ay = y shr 1
        val fx = if (x and 1 == 1) ax + 1 else ax - 1
        val fy = if (y and 1 == 1) ay + 1 else ay - 1
        fun at(px: Int, py: Int): Int = c[py.coerceIn(0, ch - 1) * stride + px.coerceIn(0, cw - 1)].toInt() and 0xFF
        return (9 * at(ax, ay) + 3 * at(fx, ay) + 3 * at(ax, fy) + at(fx, fy) + 8) shr 4
    }

    /** RGB of a Y, U and V sample, by the inverse of the matrix above in 1/65536. */
    private fun toRgb(y: Int, u: Int, v: Int, out: IntArray) {
        val yy = (y - 16) * 76309
        out[0] = clip((yy + 104597 * (v - 128) + (1 shl 15)) shr 16)
        out[1] = clip((yy - 25675 * (u - 128) - 53279 * (v - 128) + (1 shl 15)) shr 16)
        out[2] = clip((yy + 132201 * (u - 128) + (1 shl 15)) shr 16)
    }

    private fun sharpen(p: Vp8Planes, rgb: IntArray, argb: IntArray, width: Int, height: Int) {
        val cw = (width + 1) shr 1
        val ch = (height + 1) shr 1
        val errU = IntArray(cw * ch)
        val errV = IntArray(cw * ch)
        val weight = IntArray(cw * ch)
        val out = IntArray(3)
        repeat(SHARP_ROUNDS) {
            errU.fill(0); errV.fill(0); weight.fill(0)
            for (y in 0 until height) for (x in 0 until width) {
                val i = y * width + x
                val a = argb[i] ushr 24
                val yi = y * p.yStride + x
                val ys = p.y[yi].toInt() and 0xFF
                val us = upsample(p.u, p.uvStride, cw, ch, x, y)
                val vs = upsample(p.v, p.uvStride, cw, ch, x, y)
                toRgb(ys, us, vs, out)
                val c = rgb[i]
                val dr = ((c shr 16) and 0xFF) - out[0]
                val dg = ((c shr 8) and 0xFF) - out[1]
                val db = (c and 0xFF) - out[2]
                // Luma takes its own share of the error at once.
                val dy = (YR * dr + YG * dg + YB * db + (1 shl 15)) shr 16
                p.y[yi] = clip(ys + dy).toByte()
                // Chroma gathers the error of the pixels it covers, weighted by their alpha.
                val w = if (a == 0) 1 else a
                val ci = (y shr 1) * cw + (x shr 1)
                errU[ci] += w * ((UR * dr + UG * dg + UB * db) shr 8)
                errV[ci] += w * ((VR * dr + VG * dg + VB * db) shr 8)
                weight[ci] += w
            }
            for (cy in 0 until ch) for (cx in 0 until cw) {
                val ci = cy * cw + cx
                val w = weight[ci]
                if (w == 0) continue
                val at = cy * p.uvStride + cx
                val du = (errU[ci] / w + 128) shr 8
                val dv = (errV[ci] / w + 128) shr 8
                p.u[at] = clip((p.u[at].toInt() and 0xFF) + du).toByte()
                p.v[at] = clip((p.v[at].toInt() and 0xFF) + dv).toByte()
            }
        }
        // The padding repeats the refined edge again.
        for (y in 0 until p.mbH * 16) for (x in 0 until p.yStride) {
            if (x < width && y < height) continue
            p.y[y * p.yStride + x] = p.y[minOf(y, height - 1) * p.yStride + minOf(x, width - 1)]
        }
        for (cy in 0 until p.mbH * 8) for (cx in 0 until p.uvStride) {
            if (cx < cw && cy < ch) continue
            val from = minOf(cy, ch - 1) * p.uvStride + minOf(cx, cw - 1)
            p.u[cy * p.uvStride + cx] = p.u[from]
            p.v[cy * p.uvStride + cx] = p.v[from]
        }
    }

    private const val SHARP_ROUNDS = 4
}
