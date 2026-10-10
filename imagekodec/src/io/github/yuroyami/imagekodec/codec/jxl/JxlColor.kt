// Altered Kotlin port of parts of libjxl 0.11.1 (lib/jxl/dec_xyb.cc, dec_xyb-inl.h, opsin_params.cc,
// base/matrix_ops.h, cms/jxl_cms_internal.h, cms/color_encoding_cms.h, cms/opsin_params.h,
// cms/transfer_functions.h, cms/transfer_functions-inl.h, cms/tone_mapping.h and
// render_pipeline/stage_xyb.cc, stage_from_linear.cc, stage_ycbcr.cc, stage_spot.cc).
// Copyright (c) the JPEG XL Project Authors. All rights reserved.
// Use of the original source is governed by a BSD-style license; see NOTICE.

package io.github.yuroyami.imagekodec.codec.jxl

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.withSign

/**
 * The colour steps at the end of a frame: XYB to the image's colour encoding, JPEG's YCbCr to
 * RGB, and spot colours.
 *
 * XYB is the colour space JPEG XL codes lossy images in. X and Y are the difference and the
 * mean of the long and medium cone signals, and B is the short cone signal, each after a cube root.
 */
internal object JxlColor {

    /**
     * XYB to the colour encoding [outputEncoding] names. [xybToLinear] undoes the cube root and
     * applies the matrix for the target primaries and white point. Then the target's transfer
     * function turns linear light into samples, as [linearToEncoded] does.
     * Returns three new planes; the input is not changed.
     */
    fun xybToEncoded(planes: Array<JxlPlane>, image: JxlImageHeader): Array<JxlPlane> {
        val out = xybToLinear(planes, image)
        encodeInPlace(out, image)
        return out
    }

    /**
     * The colour encoding [xybToEncoded] writes. It is the encoding the header describes, with
     * three exceptions:
     * - The header gives only an ICC profile: sRGB, or gray sRGB. Nothing here applies a profile.
     *   libjxl without a colour management system writes linear sRGB in this case.
     * - The header describes gray with a white point other than D65: linear gray sRGB, as libjxl
     *   (`CanOutputToColorEncoding` in dec_xyb.cc).
     * - The transfer function is unknown: linear sRGB, as libjxl. A header without an ICC profile
     *   cannot say this, so only a hand-made header reaches it.
     *
     * When the header describes XYB itself, the samples are libjxl's scaled XYB (see [xybToLinear]).
     */
    fun outputEncoding(image: JxlImageHeader): JxlColorEncoding {
        val c = image.color
        if (c.wantIcc) return if (c.isGray) GRAY_SRGB else JxlColorEncoding.SRGB
        val unknownCurve = !c.haveGamma && c.transferFunction == JxlColorEncoding.TF_UNKNOWN
        if (unknownCurve || (c.isGray && c.whitePoint != JxlColorEncoding.WHITE_D65)) {
            return if (c.isGray) LINEAR_GRAY else LINEAR_SRGB
        }
        return c
    }

    /**
     * Fails for a colour encoding that libjxl rejects when it reads the header. libjxl writes an
     * ICC profile for every header that has none (`MaybeCreateProfile` in cms/jxl_cms_internal.h),
     * and the header is bad when the profile cannot be written: the white point is not a colour,
     * the primaries lie on one line, or a number does not fit the profile's fixed point.
     */
    fun checkEncoding(color: JxlColorEncoding) {
        if (color.wantIcc) return
        if (color.colorSpace == JxlColorEncoding.UNKNOWN_SPACE ||
            (!color.haveGamma && color.transferFunction == JxlColorEncoding.TF_UNKNOWN)
        ) {
            jxlFail("no ICC profile, and an unknown colour space or transfer function")
        }
        if (color.colorSpace == JxlColorEncoding.XYB) {
            // libjxl's XYB profile exists only for the perceptual intent, which is intent 0.
            if (color.renderingIntent != 0) jxlFail("XYB colour space with rendering intent ${color.renderingIntent}")
            return
        }
        val white = whiteXy(color)
        if (color.isGray) {
            // A gray profile stores only the white point's XYZ, so a white point off the usual range passes.
            if (abs(white[1]) < 1e-12) jxlFail("white point with no brightness")
            checkFixed(white[0] / white[1], "white point")
            checkFixed((1.0 - white[0] - white[1]) / white[1], "white point")
        } else {
            val adapt = adaptToD50(white[0], white[1])
            for (v in adapt) checkFixed(v, "white point")
            val toD50 = mul(adapt, primariesToXyz(primariesXy(color), white[0], white[1]))
            for (v in toD50) checkFixed(v, "primaries")
        }
        if (color.haveGamma) checkFixed(1.0 / (color.gamma * GAMMA_UNIT), "gamma")
    }

    /**
     * XYB to linear light in the primaries and white point of [outputEncoding]
     * (render_pipeline/stage_xyb.cc, `XybToRgb` in dec_xyb-inl.h). A sample of 1.0 stands for
     * the intensity target, which is a brightness in nits that the header gives.
     * For a gray target the three planes hold the same value. For an XYB target the planes hold
     * libjxl's scaled XYB, not linear light. Returns three new planes; the input is not changed.
     */
    fun xybToLinear(planes: Array<JxlPlane>, image: JxlImageHeader): Array<JxlPlane> {
        val n = sampleCount(planes)
        checkEncoding(image.color)
        val target = outputEncoding(image)
        val out = Array(3) { JxlPlane(planes[it].w, planes[it].h, FloatArray(n)) }
        val inX = planes[0].data
        val inY = planes[1].data
        val inB = planes[2].data
        val out0 = out[0].data
        val out1 = out[1].data
        val out2 = out[2].data

        if (target.colorSpace == JxlColorEncoding.XYB) {
            // The image asks for XYB itself. libjxl then moves X, Y and B minus Y into the 0 to 1 range.
            for (i in 0 until n) {
                val y = inY[i].toDouble()
                out0[i] = ((inX[i].toDouble() + SCALED_XYB_OFFSET_X) * SCALED_XYB_SCALE_X).toFloat()
                out1[i] = (y * SCALED_XYB_SCALE_Y).toFloat()
                out2[i] = ((inB[i].toDouble() - y + SCALED_XYB_OFFSET_B) * SCALED_XYB_SCALE_B).toFloat()
            }
            return out
        }

        val m = opsinToTarget(image, target)
        // The encoder adds a bias to each cone signal before the cube root. The header holds the bias negated.
        val bias0 = image.opsinBiases[0]
        val bias1 = image.opsinBiases[1]
        val bias2 = image.opsinBiases[2]
        val root0 = cbrt(bias0)
        val root1 = cbrt(bias1)
        val root2 = cbrt(bias2)
        for (i in 0 until n) {
            val x = inX[i].toDouble()
            val y = inY[i].toDouble()
            val gammaL = y + x - root0
            val gammaM = y - x - root1
            val gammaS = inB[i].toDouble() - root2
            val mixedL = gammaL * gammaL * gammaL + bias0
            val mixedM = gammaM * gammaM * gammaM + bias1
            val mixedS = gammaS * gammaS * gammaS + bias2
            out0[i] = (m[0] * mixedL + m[1] * mixedM + m[2] * mixedS).toFloat()
            out1[i] = (m[3] * mixedL + m[4] * mixedM + m[5] * mixedS).toFloat()
            out2[i] = (m[6] * mixedL + m[7] * mixedM + m[8] * mixedS).toFloat()
        }
        return out
    }

    /**
     * Applies the transfer function of [outputEncoding] to the planes [xybToLinear] returns
     * (render_pipeline/stage_from_linear.cc). Returns three new planes; the input is not changed.
     */
    fun linearToEncoded(planes: Array<JxlPlane>, image: JxlImageHeader): Array<JxlPlane> {
        sampleCount(planes)
        val out = Array(3) { JxlPlane(planes[it].w, planes[it].h, planes[it].data.copyOf()) }
        encodeInPlace(out, image)
        return out
    }

    /**
     * JPEG's YCbCr to RGB (render_pipeline/stage_ycbcr.cc). The planes come in libjxl's order
     * Cb, Y, Cr. Returns three new planes R, G, B.
     */
    fun ycbcrToRgb(planes: Array<JxlPlane>): Array<JxlPlane> {
        val n = sampleCount(planes)
        val out = Array(3) { JxlPlane(planes[it].w, planes[it].h, FloatArray(n)) }
        val inCb = planes[0].data
        val inY = planes[1].data
        val inCr = planes[2].data
        val outR = out[0].data
        val outG = out[1].data
        val outB = out[2].data
        for (i in 0 until n) {
            // Y is stored around zero, as a DCT needs it, so the level shift of 128 comes back here (T.81 F.1.1.3).
            val y = inY[i].toDouble() + 128.0 / 255.0
            val cb = inCb[i].toDouble()
            val cr = inCr[i].toDouble()
            outR[i] = (y + CR_TO_R * cr).toFloat()
            outG[i] = (y + CB_TO_G * cb + CR_TO_G * cr).toFloat()
            outB[i] = (y + CB_TO_B * cb).toFloat()
        }
        return out
    }

    /**
     * Mixes every spot colour channel into the colour (render_pipeline/stage_spot.cc). A spot
     * colour is an ink with its own colour; its channel says how much ink each pixel gets.
     * Returns three new planes; inputs are not changed.
     *
     * libjxl runs this step on linear light when the frame is XYB, is not blended and is not
     * saved after its colour transform. In every other case it runs on encoded samples.
     */
    fun renderSpotColors(color: Array<JxlPlane>, extra: Array<JxlPlane>, image: JxlImageHeader): Array<JxlPlane> {
        val n = sampleCount(color)
        val out = Array(3) { JxlPlane(color[it].w, color[it].h, color[it].data.copyOf()) }
        for (index in image.extraChannels.indices) {
            val channel = image.extraChannels[index]
            if (channel.type != JxlExtraChannel.SPOT_COLOR) continue
            if (index >= extra.size || extra[index].data.size != n) jxlFail("spot colour channel $index does not match the colour planes")
            val coverage = extra[index].data
            val solidity = channel.spotColor[3]
            for (c in 0 until 3) {
                val ink = channel.spotColor[c]
                val samples = out[c].data
                for (i in 0 until n) {
                    val mix = solidity * coverage[i].toDouble()
                    samples[i] = (mix * ink + (1.0 - mix) * samples[i].toDouble()).toFloat()
                }
            }
        }
        return out
    }

    // ---- The matrix from cone signals to linear light ----

    /**
     * The header's opsin inverse matrix, changed to give linear light in the primaries and white
     * point of [target] (`OutputEncodingInfo::SetColorEncoding` in dec_xyb.cc). Opsin is libjxl's
     * name for the cone signals, and the header's matrix turns them into linear sRGB.
     */
    private fun opsinToTarget(image: JxlImageHeader, target: JxlColorEncoding): DoubleArray {
        if (image.opsinInverseMatrix.size != 9 || image.opsinBiases.size != 3) jxlFail("opsin inverse matrix of a wrong size")
        if (!(image.intensityTarget > 0.0)) jxlFail("intensity target ${image.intensityTarget}")
        var m = image.opsinInverseMatrix
        if (target.isGray) {
            // Gray is the luminance of linear sRGB. Every row gets the same weights, so the planes are equal.
            val luma = DoubleArray(3) { BT709_LUMINANCES[0] * m[it] + BT709_LUMINANCES[1] * m[3 + it] + BT709_LUMINANCES[2] * m[6 + it] }
            m = DoubleArray(9) { luma[it % 3] }
        } else if (!isSrgb(target)) {
            // Both colour spaces meet in XYZ under the D50 white point, as two ICC profiles would.
            val white = whiteXy(target)
            val srgbToD50 = mul(adaptToD50(D65_X, D65_Y), primariesToXyz(SRGB_PRIMARIES, D65_X, D65_Y))
            val targetToD50 = mul(adaptToD50(white[0], white[1]), targetToXyz(target))
            val d50ToTarget = invert(targetToD50, "target primaries and white point")
            m = mul(mul(d50ToTarget, srgbToD50), m)
        }
        // XYB holds absolute brightness, where 1.0 is 255 nits. The output is relative to the intensity target.
        val scale = 255.0 / image.intensityTarget
        return DoubleArray(9) { m[it] * scale }
    }

    private fun isSrgb(c: JxlColorEncoding): Boolean =
        c.primaries == JxlColorEncoding.PRIMARIES_SRGB && c.whitePoint == JxlColorEncoding.WHITE_D65

    /** The white point of [c] as CIE xy. The named values are the ones libjxl uses (cms/color_encoding_cms.h). */
    private fun whiteXy(c: JxlColorEncoding): DoubleArray = when (c.whitePoint) {
        JxlColorEncoding.WHITE_D65 -> doubleArrayOf(D65_X, D65_Y)
        JxlColorEncoding.WHITE_CUSTOM -> doubleArrayOf(c.white[0] * XY_UNIT, c.white[1] * XY_UNIT)
        JxlColorEncoding.WHITE_E -> doubleArrayOf(1.0 / 3.0, 1.0 / 3.0)
        JxlColorEncoding.WHITE_DCI -> doubleArrayOf(0.314, 0.351)
        else -> jxlFail("white point ${c.whitePoint}")
    }

    /** The primaries of [c] as CIE xy: red x, y, green x, y, blue x, y. */
    private fun primariesXy(c: JxlColorEncoding): DoubleArray = when (c.primaries) {
        JxlColorEncoding.PRIMARIES_SRGB -> SRGB_PRIMARIES
        JxlColorEncoding.PRIMARIES_CUSTOM -> DoubleArray(6) { c.primariesXy[it] * XY_UNIT }
        JxlColorEncoding.PRIMARIES_2100 -> doubleArrayOf(0.708, 0.292, 0.170, 0.797, 0.131, 0.046)
        JxlColorEncoding.PRIMARIES_P3 -> doubleArrayOf(0.680, 0.320, 0.265, 0.690, 0.150, 0.060)
        else -> jxlFail("primaries ${c.primaries}")
    }

    private fun targetToXyz(target: JxlColorEncoding): DoubleArray {
        val white = whiteXy(target)
        return primariesToXyz(primariesXy(target), white[0], white[1])
    }

    /**
     * The brightness (CIE Y) of each primary of [target], which the HLG transfer function needs.
     * libjxl keeps the BT.709 weights for gray and for sRGB primaries with a D65 white point.
     */
    private fun luminances(target: JxlColorEncoding): DoubleArray {
        if (target.isGray || isSrgb(target)) return BT709_LUMINANCES
        val toXyz = targetToXyz(target)
        return doubleArrayOf(toXyz[3], toXyz[4], toXyz[5])
    }

    /** A white point as XYZ with Y = 1. Fails for a white point that is not a colour, as libjxl. */
    private fun whiteXyz(wx: Double, wy: Double): DoubleArray {
        if (!(wx >= 0.0 && wx <= 1.0 && wy > 0.0 && wy <= 1.0)) jxlFail("white point ($wx, $wy) is not a colour")
        val xyz = doubleArrayOf(wx / wy, 1.0, (1.0 - wx - wy) / wy)
        if (!xyz[0].isFinite() || !xyz[2].isFinite()) jxlFail("white point ($wx, $wy) is not a colour")
        return xyz
    }

    /**
     * The matrix from linear RGB to XYZ for primaries [p] and white point ([wx], [wy]), as
     * libjxl's `PrimariesToXYZ`. Each primary is scaled so that RGB (1, 1, 1) gives the white point.
     */
    private fun primariesToXyz(p: DoubleArray, wx: Double, wy: Double): DoubleArray {
        val white = whiteXyz(wx, wy)
        val primaries = doubleArrayOf(
            p[0], p[2], p[4],
            p[1], p[3], p[5],
            1.0 - p[0] - p[1], 1.0 - p[2] - p[3], 1.0 - p[4] - p[5],
        )
        val scale = mulVector(invert(primaries, "primaries"), white)
        return DoubleArray(9) { primaries[it] * scale[it % 3] }
    }

    /**
     * The Bradford matrix that moves XYZ from white point ([wx], [wy]) to the D50 white point,
     * as libjxl's `AdaptToXYZD50`.
     */
    private fun adaptToD50(wx: Double, wy: Double): DoubleArray {
        val cone = mulVector(BRADFORD, whiteXyz(wx, wy))
        val cone50 = mulVector(BRADFORD, D50_XYZ)
        val scale = DoubleArray(9)
        for (i in 0 until 3) {
            if (cone[i] == 0.0) jxlFail("white point ($wx, $wy) cannot be adapted")
            scale[i * 4] = cone50[i] / cone[i]
            if (!scale[i * 4].isFinite()) jxlFail("white point ($wx, $wy) cannot be adapted")
        }
        return mul(BRADFORD_INVERSE, mul(scale, BRADFORD))
    }

    /** Fails when [v] does not fit the s15Fixed16 number of an ICC profile, the way libjxl checks it. */
    private fun checkFixed(v: Double, what: String) {
        if (!(v >= -32767.995 && v <= 32767.995)) jxlFail("$what out of range")
    }

    // ---- 3 by 3 matrices, row by row (base/matrix_ops.h) ----

    private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) {
        val row = it / 3 * 3
        val col = it % 3
        a[row] * b[col] + a[row + 1] * b[3 + col] + a[row + 2] * b[6 + col]
    }

    private fun mulVector(a: DoubleArray, v: DoubleArray): DoubleArray =
        DoubleArray(3) { a[it * 3] * v[0] + a[it * 3 + 1] * v[1] + a[it * 3 + 2] * v[2] }

    /** The inverse of [m]. Fails below the determinant libjxl's `Inv3x3Matrix` accepts. */
    private fun invert(m: DoubleArray, what: String): DoubleArray {
        val t = doubleArrayOf(
            m[4] * m[8] - m[5] * m[7], m[2] * m[7] - m[1] * m[8], m[1] * m[5] - m[2] * m[4],
            m[5] * m[6] - m[3] * m[8], m[0] * m[8] - m[2] * m[6], m[2] * m[3] - m[0] * m[5],
            m[3] * m[7] - m[4] * m[6], m[1] * m[6] - m[0] * m[7], m[0] * m[4] - m[1] * m[3],
        )
        val det = m[0] * t[0] + m[1] * t[3] + m[2] * t[6]
        // A NaN determinant fails here too.
        if (!(abs(det) >= 1e-10)) jxlFail("$what give a matrix that cannot be inverted")
        return DoubleArray(9) { t[it] / det }
    }

    // ---- Transfer functions, from linear light to encoded samples ----

    /** Applies the transfer function of the output encoding to the three planes. */
    private fun encodeInPlace(planes: Array<JxlPlane>, image: JxlImageHeader) {
        val target = outputEncoding(image)
        if (target.colorSpace == JxlColorEncoding.XYB) return
        if (target.haveGamma || target.transferFunction == JxlColorEncoding.TF_DCI) {
            // DCI is a pure gamma of 2.6 (SMPTE RP 431-2).
            val exponent = if (target.haveGamma) target.gamma * GAMMA_UNIT else 1.0 / 2.6
            eachSample(planes) { gammaFromLinear(it, exponent) }
            return
        }
        when (target.transferFunction) {
            JxlColorEncoding.TF_LINEAR -> {}
            JxlColorEncoding.TF_SRGB -> eachSample(planes) { srgbFromLinear(it) }
            JxlColorEncoding.TF_709 -> eachSample(planes) { bt709FromLinear(it) }
            JxlColorEncoding.TF_PQ -> {
                // PQ codes absolute brightness: 1.0 is 10000 nits (BT.2100 table 4).
                val toPqRange = image.intensityTarget / 10000.0
                eachSample(planes) { pqFromLinear(it * toPqRange) }
            }
            JxlColorEncoding.TF_HLG -> hlgInPlace(planes, luminances(target), image.intensityTarget)
            else -> jxlFail("transfer function ${target.transferFunction}")
        }
    }

    private inline fun eachSample(planes: Array<JxlPlane>, curve: (Double) -> Double) {
        for (c in 0 until 3) {
            val data = planes[c].data
            for (i in data.indices) data[i] = curve(data[i].toDouble()).toFloat()
        }
    }

    /** IEC 61966-2-1. libjxl mirrors the curve for a negative sample, so that f(-x) = -f(x). */
    private fun srgbFromLinear(x: Double): Double {
        val a = abs(x)
        val e = if (a > 0.0031308) 1.055 * a.pow(1.0 / 2.4) - 0.055 else 12.92 * a
        return e.withSign(x)
    }

    /** BT.709 item 1.2. libjxl does not mirror this curve: a negative sample stays on the straight part. */
    private fun bt709FromLinear(x: Double): Double =
        if (x <= 0.018) 4.5 * x else 1.099 * x.pow(0.45) - 0.099

    /** A pure power curve. libjxl writes zero at and below 1e-5, which includes every negative sample. */
    private fun gammaFromLinear(x: Double, exponent: Double): Double =
        if (x <= 1e-5) 0.0 else x.pow(exponent)

    /** The PQ inverse EOTF of BT.2100 table 4, for [x] where 1.0 is 10000 nits. Mirrored for a negative sample. */
    private fun pqFromLinear(x: Double): Double {
        // libjxl's exact form (TF_PQ_Base) gives zero for zero. The formula alone gives 7e-7.
        if (x == 0.0) return 0.0
        val p = abs(x).pow(PQ_M1)
        return ((PQ_C1 + PQ_C2 * p) / (1.0 + PQ_C3 * p)).pow(PQ_M2).withSign(x)
    }

    /** The HLG OETF of BT.2100 table 5, from scene light to the signal. Mirrored for a negative sample. */
    private fun hlgFromScene(x: Double): Double {
        val a = abs(x)
        val e = if (a <= 1.0 / 12.0) sqrt(3.0 * a) else HLG_A * ln(12.0 * a - HLG_B) + HLG_C
        return e.withSign(x)
    }

    /**
     * HLG codes scene light, and the planes hold display light. The inverse OOTF of BT.2100
     * table 5 goes back to scene light, for a display whose peak is the intensity target
     * (`HlgOOTF::ToSceneLight` in cms/tone_mapping-inl.h). Then the OETF gives the signal.
     */
    private fun hlgInPlace(planes: Array<JxlPlane>, luminances: DoubleArray, intensityTarget: Double) {
        // The system gamma is 1.2 at 1000 nits and changes with the display's peak. This is libjxl's formula.
        val exponent = (1.0 / 1.2) * 1.111.pow(-log2(intensityTarget / 1000.0)) - 1.0
        // libjxl skips the OOTF when it would change almost nothing.
        val applyOotf = exponent < -0.01 || exponent > 0.01
        val red = planes[0].data
        val green = planes[1].data
        val blue = planes[2].data
        for (i in red.indices) {
            var r = red[i].toDouble()
            var g = green[i].toDouble()
            var b = blue[i].toDouble()
            if (applyOotf) {
                val luminance = luminances[0] * r + luminances[1] * g + luminances[2] * b
                // libjxl's fast power is undefined for a negative base. Zero keeps such a pixel away from NaN.
                val ratio = min(max(luminance, 0.0).pow(exponent), 1e9)
                r *= ratio
                g *= ratio
                b *= ratio
            }
            red[i] = hlgFromScene(r).toFloat()
            green[i] = hlgFromScene(g).toFloat()
            blue[i] = hlgFromScene(b).toFloat()
        }
    }

    /** The number of samples in each of the three planes. Fails when the planes differ, before a loop can run off one. */
    private fun sampleCount(planes: Array<JxlPlane>): Int {
        if (planes.size < 3) jxlFail("${planes.size} colour planes")
        val n = planes[0].data.size
        if (planes[1].data.size != n || planes[2].data.size != n) jxlFail("colour planes of different sizes")
        return n
    }

    // ---- Constants ----

    /** Custom xy values are stored times one million. */
    private const val XY_UNIT = 1.0 / 1_000_000.0

    /** A custom gamma is stored times ten million. */
    private const val GAMMA_UNIT = 1.0 / 10_000_000.0

    private const val D65_X = 0.3127
    private const val D65_Y = 0.3290

    // libjxl's own xy values for the BT.709 primaries. They differ from 0.64, 0.33 and so on by up to 0.00001.
    private val SRGB_PRIMARIES = doubleArrayOf(0.639998686, 0.330010138, 0.300003784, 0.600003357, 0.150002046, 0.059997204)

    /** The brightness of the BT.709 primaries (BT.709 item 3.2). */
    private val BT709_LUMINANCES = doubleArrayOf(0.2126, 0.7152, 0.0722)

    /** The D50 white point of an ICC profile, as XYZ. */
    private val D50_XYZ = doubleArrayOf(0.96422, 1.0, 0.82521)

    /** The Bradford matrix, from XYZ to the cone signals a white point change scales. */
    private val BRADFORD = doubleArrayOf(
        0.8951, 0.2664, -0.1614,
        -0.7502, 1.7135, 0.0367,
        0.0389, -0.0685, 1.0296,
    )
    private val BRADFORD_INVERSE = invert(BRADFORD, "Bradford matrix")

    // Full-range BT.601 as JFIF uses it (T.871 clause 7): Kr = 0.299, Kg = 0.587, Kb = 0.114.
    private const val CR_TO_R = 1.402
    private const val CB_TO_B = 1.772
    private const val CB_TO_G = -0.114 * 1.772 / 0.587
    private const val CR_TO_G = -0.299 * 1.402 / 0.587

    // PQ, BT.2100 table 4.
    private const val PQ_M1 = 2610.0 / 16384.0
    private const val PQ_M2 = 2523.0 / 4096.0 * 128.0
    private const val PQ_C1 = 3424.0 / 4096.0
    private const val PQ_C2 = 2413.0 / 4096.0 * 32.0
    private const val PQ_C3 = 2392.0 / 4096.0 * 32.0

    // HLG, BT.2100 table 5.
    private const val HLG_A = 0.17883277
    private const val HLG_B = 1.0 - 4.0 * HLG_A
    private const val HLG_C = 0.5599107295

    // libjxl's scaled XYB (cms/opsin_params.h), which its XYB ICC profile describes.
    private const val SCALED_XYB_OFFSET_X = 0.015386134
    private const val SCALED_XYB_OFFSET_B = 0.27770459
    private const val SCALED_XYB_SCALE_X = 22.995788804
    private const val SCALED_XYB_SCALE_Y = 1.183000077
    private const val SCALED_XYB_SCALE_B = 1.502141333

    private val GRAY_SRGB = fallback(JxlColorEncoding.GRAY, JxlColorEncoding.TF_SRGB)
    private val LINEAR_SRGB = fallback(JxlColorEncoding.RGB, JxlColorEncoding.TF_LINEAR)
    private val LINEAR_GRAY = fallback(JxlColorEncoding.GRAY, JxlColorEncoding.TF_LINEAR)

    /** An encoding with sRGB primaries and a D65 white point. */
    private fun fallback(colorSpace: Int, transferFunction: Int) = JxlColorEncoding(
        false, colorSpace, JxlColorEncoding.WHITE_D65, IntArray(2), JxlColorEncoding.PRIMARIES_SRGB, IntArray(6),
        false, 0, transferFunction, 1,
    )
}
