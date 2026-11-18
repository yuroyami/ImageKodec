package io.github.yuroyami.imagekodec.codec.avif

import io.github.yuroyami.imagekodec.ExperimentalHeifCodecApi
import io.github.yuroyami.imagekodec.HeifPlanes
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.KiteBitmap16

/**
 * The RGB and alpha of a [KiteBitmap] or a [KiteBitmap16], read at 16 bits a sample (an 8-bit `v` as
 * `v * 257`), with the picture's edges repeated outward, so a window past them reads the last row and
 * column again. That padding is what a grid's edge tiles and a subsampled picture of odd size need.
 */
internal class HeifRgbSource private constructor(private val bitmap: KiteBitmap?, private val bitmap16: KiteBitmap16?) {
    constructor(bitmap: KiteBitmap) : this(bitmap, null)
    constructor(bitmap16: KiteBitmap16) : this(null, bitmap16)

    val width: Int = bitmap?.width ?: bitmap16!!.width
    val height: Int = bitmap?.height ?: bitmap16!!.height

    /** True for 8-bit samples, which convert exactly at any depth. */
    val narrow: Boolean get() = bitmap != null

    /** Whether every pixel is gray, so the picture codes as luma alone. */
    val gray: Boolean = bitmap?.argb?.all { p -> ((p ushr 16) and 0xFF) == ((p ushr 8) and 0xFF) && ((p ushr 8) and 0xFF) == (p and 0xFF) }
        ?: bitmap16!!.let { b ->
            b.channels <= 2 || (0 until b.width * b.height).all { i ->
                val at = i * b.channels
                b.samples[at] == b.samples[at + 1] && b.samples[at + 1] == b.samples[at + 2]
            }
        }

    /** Whether any pixel is not opaque, so the picture needs an alpha plane. */
    val alpha: Boolean = bitmap?.argb?.any { (it ushr 24) != 0xFF }
        ?: bitmap16!!.let { b ->
            b.hasAlpha && (0 until b.width * b.height).any { i -> (b.samples[i * b.channels + b.channels - 1].toInt() and 0xFFFF) != 0xFFFF }
        }

    /**
     * Row [y] from column [x0], [n] pixels, into [r], [g], [b] and [a] at 16 bits; [y] and each column
     * are held inside the picture.
     */
    fun row(y: Int, x0: Int, n: Int, r: IntArray, g: IntArray, b: IntArray, a: IntArray) {
        val sy = y.coerceIn(0, height - 1)
        val src = bitmap
        if (src != null) {
            val base = sy * width
            for (i in 0 until n) {
                val p = src.argb[base + (x0 + i).coerceIn(0, width - 1)]
                r[i] = ((p ushr 16) and 0xFF) * 257
                g[i] = ((p ushr 8) and 0xFF) * 257
                b[i] = (p and 0xFF) * 257
                a[i] = (p ushr 24) * 257
            }
            return
        }
        val s = bitmap16!!
        val c = s.channels
        val base = sy * width
        for (i in 0 until n) {
            val at = (base + (x0 + i).coerceIn(0, width - 1)) * c
            val first = s.samples[at].toInt() and 0xFFFF
            if (c <= 2) {
                r[i] = first
                g[i] = first
                b[i] = first
            } else {
                r[i] = first
                g[i] = s.samples[at + 1].toInt() and 0xFFFF
                b[i] = s.samples[at + 2].toInt() and 0xFFFF
            }
            a[i] = if (c == 2 || c == 4) s.samples[at + c - 1].toInt() and 0xFFFF else 0xFFFF
        }
    }
}

/**
 * RGB to the planes of a HEIF picture (#174), in integer arithmetic.
 *
 * Lossy colour uses the matrix coefficients of BT.601 (ITU-T H.273 code point 6, Kr 0.299 and Kb 0.114)
 * in full range, as libavif and libheif write sRGB: E'Y = Kr R + Kg G + Kb B, E'Cb = (B - E'Y) / (2 - 2 Kb)
 * and E'Cr = (R - E'Y) / (2 - 2 Kr), each quantized as H.273 equations 23 to 25 do for full range,
 * `Round((2^D - 1) * E)` plus `2^(D-1)` for chroma. The products run at 16 bits with coefficients in
 * 1/32768. Subsampled chroma is the mean of the 2 by 2 (4:2:0) or 2 by 1 (4:2:2) block of full-size
 * chroma, which puts it at the centre of its luma samples, where the reader's bilinear upsampling
 * ([AvifColor.convert]) expects it.
 *
 * Lossless colour uses the identity matrix (code point 0) in 4:4:4: G in luma, B and R in the chroma
 * planes, so a decode gives the samples back unchanged. Gray codes luma alone, and alpha is a plane of
 * its own, always full range.
 *
 * A sample of 16 bits goes to [depth] bits by rounding, except in [lossless] coding of a [HeifRgbSource]
 * of 16 bits, where it goes to the coded value whose reading by `decode16` lies nearest, so each value a
 * file of [depth] bits can give comes back exactly.
 */
@OptIn(ExperimentalHeifCodecApi::class)
internal class HeifPlaneMaker(
    private val source: HeifRgbSource,
    val depth: Int,
    val lossless: Boolean,
    /** 0 or 1 each: the chroma subsampling of a colour picture. */
    val subX: Int,
    val subY: Int,
    /** Whether to code luma alone: the source's own grayness, or a sequence's, which every frame shares (#177). */
    private val gray: Boolean = source.gray,
) {
    private val max = (1 shl depth) - 1
    private val table: IntArray? = if (lossless && !source.narrow) nearestDecode16(depth) else null

    /** [v], a 16-bit sample, at [depth] bits. */
    private fun quantize(v: Int): Int = table?.get(v) ?: ((2 * v * max + 65535) / 131070)

    /** Planes of the colour of the window of [w] by [h] at ([x0], [y0]). In subsampled chroma, [w] and [h] are even. */
    fun colour(x0: Int, y0: Int, w: Int, h: Int): HeifPlanes {
        val sx = if (gray) 0 else subX
        val sy = if (gray) 0 else subY
        require(w % (1 shl sx) == 0 && h % (1 shl sy) == 0) { "a window of $w by $h in subsampled chroma" }
        val r = IntArray(w)
        val g = IntArray(w)
        val b = IntArray(w)
        val a = IntArray(w)
        val luma = ShortArray(w * h)
        if (gray) {
            for (y in 0 until h) {
                source.row(y0 + y, x0, w, r, g, b, a)
                for (x in 0 until w) luma[y * w + x] = quantize(g[x]).toShort()
            }
            return HeifPlanes(w, h, depth, depth, 0, 0, arrayOf(luma), intArrayOf(w), true)
        }
        val cw = w shr sx
        val ch = h shr sy
        val cb = ShortArray(cw * ch)
        val cr = ShortArray(cw * ch)
        if (lossless) {
            for (y in 0 until h) {
                source.row(y0 + y, x0, w, r, g, b, a)
                for (x in 0 until w) {
                    val at = y * w + x
                    luma[at] = quantize(g[x]).toShort()
                    cb[at] = quantize(b[x]).toShort()
                    cr[at] = quantize(r[x]).toShort()
                }
            }
            return HeifPlanes(w, h, depth, depth, 0, 0, arrayOf(luma, cb, cr), intArrayOf(w, w, w), true)
        }
        // Chroma at 16 bits, signed, summed over each block until its last row is in.
        val sumCb = IntArray(cw)
        val sumCr = IntArray(cw)
        val n = 1 shl (sx + sy)
        val half = 1 shl (depth - 1)
        for (y in 0 until h) {
            source.row(y0 + y, x0, w, r, g, b, a)
            for (x in 0 until w) {
                val y16 = (KR * r[x] + KG * g[x] + KB * b[x] + 16384) shr 15
                luma[y * w + x] = quantize(y16).toShort()
                sumCb[x shr sx] += ((b[x] - y16) * CB + 16384) shr 15
                sumCr[x shr sx] += ((r[x] - y16) * CR + 16384) shr 15
            }
            if (((y + 1) and ((1 shl sy) - 1)) == 0) {
                val row = (y shr sy) * cw
                for (x in 0 until cw) {
                    cb[row + x] = (half + roundDiv(sumCb[x] * max, n * 65535)).coerceIn(0, max).toShort()
                    cr[row + x] = (half + roundDiv(sumCr[x] * max, n * 65535)).coerceIn(0, max).toShort()
                }
                sumCb.fill(0)
                sumCr.fill(0)
            }
        }
        return HeifPlanes(w, h, depth, depth, sx, sy, arrayOf(luma, cb, cr), intArrayOf(w, cw, cw), true)
    }

    /** The alpha of the window of [w] by [h] at ([x0], [y0]), one full-range plane. */
    fun alpha(x0: Int, y0: Int, w: Int, h: Int): HeifPlanes {
        val r = IntArray(w)
        val g = IntArray(w)
        val b = IntArray(w)
        val a = IntArray(w)
        val plane = ShortArray(w * h)
        for (y in 0 until h) {
            source.row(y0 + y, x0, w, r, g, b, a)
            for (x in 0 until w) plane[y * w + x] = quantize(a[x]).toShort()
        }
        return HeifPlanes(w, h, depth, depth, 0, 0, arrayOf(plane), intArrayOf(w), true)
    }

    companion object {
        // BT.601's Kr, Kg and Kb in 1/32768, summing to 32768 so gray stays gray.
        private const val KR = 9798
        private const val KG = 19235
        private const val KB = 3735

        // 32768 / (2 - 2 Kb) and 32768 / (2 - 2 Kr): 1.772 and 1.402.
        private const val CB = 18492
        private const val CR = 23372

        /** [a] / [b] rounded half up, for a positive [b] and any sign of [a]. */
        private fun roundDiv(a: Int, b: Int): Int = (2 * a + b).floorDiv(2 * b)

        /**
         * For each 16-bit value, the sample of [depth] bits whose reading at 16 bits lies nearest. The
         * reading restates [AvifColor.convert] for a full-range sample `s` of the identity matrix, of gray or
         * of alpha: `v = s / max`, the 8-bit value `round(v * 255)`, and `round(v * 65535)` held to that
         * 8-bit value's bucket. It rises with `s`, so one pass finds every nearest sample.
         */
        fun nearestDecode16(depth: Int): IntArray {
            val max = (1 shl depth) - 1
            val read = IntArray(max + 1) { s ->
                val v = s.toDouble() / max
                val v8 = (0.5 + v * 255).toInt()
                (0.5 + v * 65535).toInt().coerceIn(v8 shl 8, (v8 shl 8) or 255)
            }
            val out = IntArray(65536)
            var s = 0
            for (x in 0..65535) {
                while (s < max && read[s + 1] <= x) s++
                out[x] = if (s < max && read[s + 1] - x < x - read[s]) s + 1 else s
            }
            return out
        }
    }
}
