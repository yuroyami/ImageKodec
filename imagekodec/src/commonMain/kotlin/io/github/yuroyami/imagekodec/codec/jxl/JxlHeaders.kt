// Altered Kotlin port of parts of libjxl 0.11.1 (lib/jxl/headers.cc, image_metadata.cc,
// color_encoding_internal.cc, cms/opsin_params.h, quantizer.h).
// Copyright (c) the JPEG XL Project Authors. All rights reserved.
// Use of the original source is governed by a BSD-style license; see NOTICE.

package io.github.yuroyami.imagekodec.codec.jxl

/** How samples are stored: unsigned integers of [bits] bits, or floats with [exponentBits] of exponent. */
internal class JxlBitDepth(val floatingPoint: Boolean, val bits: Int, val exponentBits: Int) {
    companion object {
        fun read(br: JxlBitReader): JxlBitDepth {
            val float = br.bool()
            if (!float) {
                val bits = br.u32(fv(8), fv(10), fv(12), fo(6, 1))
                if (bits > 31) jxlFail("$bits bits a sample")
                return JxlBitDepth(false, bits, 0)
            }
            val bits = br.u32(fv(32), fv(16), fv(24), fo(6, 1))
            val exponent = br.bits(4) + 1
            if (exponent < 2 || exponent > 8) jxlFail("float samples with $exponent exponent bits")
            val mantissa = bits - exponent - 1
            if (mantissa < 2 || mantissa > 23) jxlFail("float samples of $bits bits")
            return JxlBitDepth(true, bits, exponent)
        }
    }
}

/** A channel beside the colour channels: alpha, depth, a spot colour, the black of CMYK and so on. */
internal class JxlExtraChannel(
    val type: Int,
    val bitDepth: JxlBitDepth,
    val dimShift: Int,
    val name: String,
    val alphaAssociated: Boolean,
    /** Red, green, blue and solidity of a spot colour. */
    val spotColor: DoubleArray,
    val cfaChannel: Int,
) {
    companion object {
        const val ALPHA = 0
        const val DEPTH = 1
        const val SPOT_COLOR = 2
        const val SELECTION_MASK = 3
        const val BLACK = 4
        const val CFA = 5
        const val THERMAL = 6
        const val OPTIONAL = 16

        fun read(br: JxlBitReader): JxlExtraChannel {
            if (br.bool()) return JxlExtraChannel(ALPHA, JxlBitDepth(false, 8, 0), 0, "", false, DoubleArray(4), 1)
            val type = br.enumValue()
            val depth = JxlBitDepth.read(br)
            val dimShift = br.u32(fv(0), fv(3), fv(4), fo(3, 1))
            if (dimShift > 3) jxlFail("extra channel subsampled by 1 shl $dimShift")
            val name = readName(br)
            val associated = type == ALPHA && br.bool()
            val spot = DoubleArray(4)
            if (type == SPOT_COLOR) for (i in 0 until 4) spot[i] = br.f16()
            val cfa = if (type == CFA) br.u32(fv(1), fb(2), fo(4, 3), fo(8, 19)) else 1
            // Types 7 to 15 are reserved or unknown; a reader that meets one cannot show the image.
            if (type !in ALPHA..THERMAL && type != OPTIONAL) jxlFail("extra channel of unknown type $type")
            return JxlExtraChannel(type, depth, dimShift, name, associated, spot, cfa)
        }
    }
}

/** A name of up to 1071 bytes, as frames and extra channels carry. */
internal fun readName(br: JxlBitReader): String {
    val length = br.u32(fv(0), fb(4), fo(5, 16), fo(10, 48))
    val bytes = ByteArray(length) { br.bits(8).toByte() }
    return bytes.decodeToString()
}

/**
 * The colour space the image's samples are in, or were in before an XYB image was encoded.
 * With [wantIcc] an embedded ICC profile describes it and the other fields do not.
 */
internal class JxlColorEncoding(
    val wantIcc: Boolean,
    val colorSpace: Int,
    val whitePoint: Int,
    /** Custom white point, x then y, each times one million. */
    val white: IntArray,
    val primaries: Int,
    /** Custom primaries: red x, y, green x, y, blue x, y, each times one million. */
    val primariesXy: IntArray,
    val haveGamma: Boolean,
    /** The exponent of a pure gamma transfer function, times ten million. */
    val gamma: Int,
    val transferFunction: Int,
    val renderingIntent: Int,
) {
    val isGray: Boolean get() = colorSpace == GRAY

    companion object {
        const val RGB = 0
        const val GRAY = 1
        const val XYB = 2
        const val UNKNOWN_SPACE = 3

        const val WHITE_D65 = 1
        const val WHITE_CUSTOM = 2
        const val WHITE_E = 10
        const val WHITE_DCI = 11

        const val PRIMARIES_SRGB = 1
        const val PRIMARIES_CUSTOM = 2
        const val PRIMARIES_2100 = 9
        const val PRIMARIES_P3 = 11

        const val TF_709 = 1
        const val TF_UNKNOWN = 2
        const val TF_LINEAR = 8
        const val TF_SRGB = 13
        const val TF_PQ = 16
        const val TF_DCI = 17
        const val TF_HLG = 18

        private const val GAMMA_MUL = 10_000_000
        private const val MAX_GAMMA = 8192

        val SRGB = JxlColorEncoding(false, RGB, WHITE_D65, IntArray(2), PRIMARIES_SRGB, IntArray(6), false, 0, TF_SRGB, 1)

        private fun customXy(br: JxlBitReader, into: IntArray, at: Int) {
            for (i in 0 until 2) {
                into[at + i] = unpackSigned(br.u32(fb(19), fo(19, 524288), fo(20, 1048576), fo(21, 2097152)))
            }
        }

        fun read(br: JxlBitReader): JxlColorEncoding {
            if (br.bool()) return SRGB
            val wantIcc = br.bool()
            val space = br.enumValue()
            if (space > UNKNOWN_SPACE) jxlFail("colour space $space")
            var whitePoint = WHITE_D65
            val white = IntArray(2)
            var primaries = PRIMARIES_SRGB
            val xy = IntArray(6)
            var haveGamma = false
            var gamma = 0
            var tf = TF_SRGB
            var intent = 1
            if (!wantIcc) {
                if (space != XYB) {
                    whitePoint = br.enumValue()
                    if (whitePoint != WHITE_D65 && whitePoint != WHITE_CUSTOM && whitePoint != WHITE_E && whitePoint != WHITE_DCI) {
                        jxlFail("white point $whitePoint")
                    }
                    if (whitePoint == WHITE_CUSTOM) customXy(br, white, 0)
                }
                if (space != GRAY && space != XYB) {
                    primaries = br.enumValue()
                    if (primaries != PRIMARIES_SRGB && primaries != PRIMARIES_CUSTOM && primaries != PRIMARIES_2100 && primaries != PRIMARIES_P3) {
                        jxlFail("primaries $primaries")
                    }
                    if (primaries == PRIMARIES_CUSTOM) for (i in 0 until 3) customXy(br, xy, i * 2)
                }
                if (space == XYB) {
                    // An XYB image has an implicit gamma of one third.
                    haveGamma = true
                    gamma = GAMMA_MUL / 3
                } else {
                    haveGamma = br.bool()
                    if (haveGamma) {
                        gamma = br.bits(24)
                        if (gamma > GAMMA_MUL || gamma.toLong() * MAX_GAMMA < GAMMA_MUL) jxlFail("gamma $gamma")
                    } else {
                        tf = br.enumValue()
                        if (tf != TF_709 && tf != TF_UNKNOWN && tf != TF_LINEAR && tf != TF_SRGB && tf != TF_PQ && tf != TF_DCI && tf != TF_HLG) {
                            jxlFail("transfer function $tf")
                        }
                    }
                }
                intent = br.enumValue()
                if (intent > 3) jxlFail("rendering intent $intent")
                if (space == UNKNOWN_SPACE || (!haveGamma && tf == TF_UNKNOWN)) {
                    jxlFail("no ICC profile, and an unknown colour space or transfer function")
                }
            }
            return JxlColorEncoding(wantIcc, space, whitePoint, white, primaries, xy, haveGamma, gamma, tf, intent)
        }
    }
}

/** How fast an animation plays and how often it repeats. */
internal class JxlAnimation(val tpsNumerator: Int, val tpsDenominator: Int, val numLoops: Long, val haveTimecodes: Boolean)

/**
 * Everything a codestream says before its first frame: the image's size, what its samples
 * are, its extra channels, its colour, and the constants a decoder may be told to replace.
 */
internal class JxlImageHeader(
    val width: Int,
    val height: Int,
    val orientation: Int,
    val intrinsicWidth: Int,
    val intrinsicHeight: Int,
    val previewWidth: Int,
    val previewHeight: Int,
    val animation: JxlAnimation?,
    val bitDepth: JxlBitDepth,
    val modular16BitBuffers: Boolean,
    val extraChannels: List<JxlExtraChannel>,
    val xybEncoded: Boolean,
    val color: JxlColorEncoding,
    val intensityTarget: Double,
    val minNits: Double,
    val relativeToMaxDisplay: Boolean,
    val linearBelow: Double,
    /** The matrix from the XYB cone responses to linear RGB, row by row. */
    val opsinInverseMatrix: DoubleArray,
    val opsinBiases: DoubleArray,
    val quantBiases: DoubleArray,
    /** Replacement upsampling kernels for factors 2, 4 and 8, or null to use the defaults. */
    val upsampling2Weights: DoubleArray?,
    val upsampling4Weights: DoubleArray?,
    val upsampling8Weights: DoubleArray?,
) {
    val havePreview: Boolean get() = previewWidth != 0

    /** The index of the first alpha channel among the extra channels, or -1. */
    val alphaChannel: Int get() = extraChannels.indexOfFirst { it.type == JxlExtraChannel.ALPHA }

    val colorChannels: Int get() = if (color.isGray) 1 else 3

    companion object {
        val DEFAULT_OPSIN_INVERSE = doubleArrayOf(
            11.031566901960783, -9.866943921568629, -0.16462299647058826,
            -3.254147380392157, 4.418770392156863, -0.16462299647058826,
            -3.6588512862745097, 2.7129230470588235, 1.9459282392156863,
        )
        private const val OPSIN_BIAS = -0.0037930732552754493
        val DEFAULT_QUANT_BIAS = doubleArrayOf(
            1.0 - 0.05465007330715401, 1.0 - 0.07005449891748593, 1.0 - 0.049935103337343655, 0.145,
        )
        const val DEFAULT_INTENSITY_TARGET = 255.0

        private val RATIO_NUM = intArrayOf(1, 12, 4, 3, 16, 5, 2)
        private val RATIO_DEN = intArrayOf(1, 10, 3, 2, 9, 4, 1)

        /** A side given by the other side and one of seven fixed aspect ratios, as an unsigned 32-bit value. */
        private fun fromRatio(ratio: Int, ysize: Int): Int =
            ((ysize.toLong() and 0xFFFFFFFFL) * RATIO_NUM[ratio - 1] / RATIO_DEN[ratio - 1]).toInt()

        /** Width then height of a SizeHeader. */
        private fun readSize(br: JxlBitReader): IntArray {
            val small = br.bool()
            val ysize = if (small) (br.bits(5) + 1) * 8 else br.u32(fo(9, 1), fo(13, 1), fo(18, 1), fo(30, 1))
            val ratio = br.bits(3)
            val xsize = when {
                ratio != 0 -> fromRatio(ratio, ysize)
                small -> (br.bits(5) + 1) * 8
                else -> br.u32(fo(9, 1), fo(13, 1), fo(18, 1), fo(30, 1))
            }
            return intArrayOf(xsize, ysize)
        }

        private fun readPreviewSize(br: JxlBitReader): IntArray {
            val div8 = br.bool()
            val ysize = if (div8) br.u32(fv(16), fv(32), fo(5, 1), fo(9, 33)) * 8 else br.u32(fo(6, 1), fo(8, 65), fo(10, 321), fo(12, 1345))
            val ratio = br.bits(3)
            val xsize = when {
                ratio != 0 -> fromRatio(ratio, ysize)
                div8 -> br.u32(fv(16), fv(32), fo(5, 1), fo(9, 33)) * 8
                else -> br.u32(fo(6, 1), fo(8, 65), fo(10, 321), fo(12, 1345))
            }
            return intArrayOf(xsize, ysize)
        }

        /** Skips the extensions of a bundle: none is defined, so each is only a length to pass over. */
        fun skipExtensions(br: JxlBitReader) {
            var extensions = br.u64()
            if (extensions == 0L) return
            var total = 0L
            while (extensions != 0L) {
                val bits = br.u64()
                if (bits < 0 || total + bits < total) jxlFail("extension sizes overflow")
                total += bits
                extensions = extensions and (extensions - 1)
            }
            br.skip(total)
        }

        /**
         * Reads the headers at the start of a codestream, after its two signature bytes. The
         * reader stops before the ICC profile, when [JxlColorEncoding.wantIcc] says one follows.
         */
        fun read(br: JxlBitReader): JxlImageHeader {
            val size = readSize(br)
            if (size[0] <= 0 || size[1] <= 0) jxlFail("image of ${size[0].toUInt()} by ${size[1].toUInt()}")

            var orientation = 1
            var intrinsic = intArrayOf(0, 0)
            var preview = intArrayOf(0, 0)
            var animation: JxlAnimation? = null
            var depth = JxlBitDepth(false, 8, 0)
            var modular16 = true
            var extra: List<JxlExtraChannel> = emptyList()
            var xyb = true
            var color = JxlColorEncoding.SRGB
            var intensityTarget = DEFAULT_INTENSITY_TARGET
            var minNits = 0.0
            var relative = false
            var linearBelow = 0.0

            if (!br.bool()) {
                val extraFields = br.bool()
                if (extraFields) {
                    orientation = br.bits(3) + 1
                    if (br.bool()) intrinsic = readSize(br)
                    if (br.bool()) {
                        preview = readPreviewSize(br)
                        if (preview[0] <= 0 || preview[1] <= 0) jxlFail("empty preview")
                    }
                    if (br.bool()) {
                        val numerator = br.u32(fv(100), fv(1000), fo(10, 1), fo(30, 1))
                        val denominator = br.u32(fv(1), fv(1001), fo(8, 1), fo(10, 1))
                        val loops = br.u32(fv(0), fb(3), fb(16), fb(32)).toLong() and 0xFFFFFFFFL
                        animation = JxlAnimation(numerator, denominator, loops, br.bool())
                    }
                }
                depth = JxlBitDepth.read(br)
                modular16 = br.bool()
                val numExtra = br.u32(fv(0), fv(1), fo(4, 2), fo(12, 1))
                extra = List(numExtra) { JxlExtraChannel.read(br) }
                xyb = br.bool()
                color = JxlColorEncoding.read(br)
                if (extraFields && !br.bool()) {
                    intensityTarget = br.f16()
                    if (intensityTarget <= 0.0) jxlFail("intensity target $intensityTarget")
                    minNits = br.f16()
                    if (minNits < 0.0 || minNits > intensityTarget) jxlFail("minimum of $minNits nits")
                    relative = br.bool()
                    linearBelow = br.f16()
                    if (linearBelow < 0.0 || (relative && linearBelow > 1.0)) jxlFail("linear below $linearBelow")
                }
                skipExtensions(br)
            }

            // The transform data: constants an encoder may replace.
            var opsin = DEFAULT_OPSIN_INVERSE
            var opsinBiases = doubleArrayOf(OPSIN_BIAS, OPSIN_BIAS, OPSIN_BIAS)
            var quantBiases = DEFAULT_QUANT_BIAS
            var up2: DoubleArray? = null
            var up4: DoubleArray? = null
            var up8: DoubleArray? = null
            if (!br.bool()) {
                if (xyb && !br.bool()) {
                    opsin = DoubleArray(9) { br.f16() }
                    opsinBiases = DoubleArray(3) { br.f16() }
                    quantBiases = DoubleArray(4) { br.f16() }
                }
                val mask = br.bits(3)
                if (mask and 1 != 0) up2 = DoubleArray(15) { br.f16() }
                if (mask and 2 != 0) up4 = DoubleArray(55) { br.f16() }
                if (mask and 4 != 0) up8 = DoubleArray(210) { br.f16() }
            }
            return JxlImageHeader(
                size[0], size[1], orientation, intrinsic[0], intrinsic[1], preview[0], preview[1], animation,
                depth, modular16, extra, xyb, color, intensityTarget, minNits, relative, linearBelow,
                opsin, opsinBiases, quantBiases, up2, up4, up8,
            )
        }
    }
}
