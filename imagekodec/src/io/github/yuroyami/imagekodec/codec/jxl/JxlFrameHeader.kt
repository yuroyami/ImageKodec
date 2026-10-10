// Altered Kotlin port of parts of libjxl 0.11.1 (lib/jxl/frame_header.cc, frame_header.h,
// loop_filter.cc, frame_dimensions.h, toc.cc).
// Copyright (c) the JPEG XL Project Authors. All rights reserved.
// Use of the original source is governed by a BSD-style license; see NOTICE.

package io.github.yuroyami.imagekodec.codec.jxl

/** How a frame, or one of its extra channels, is laid over what was decoded before it. */
internal class JxlBlendingInfo(val mode: Int, val alphaChannel: Int, val clamp: Boolean, val source: Int) {
    companion object {
        const val REPLACE = 0
        const val ADD = 1
        const val BLEND = 2
        const val ALPHA_WEIGHTED_ADD = 3
        const val MUL = 4

        val DEFAULT = JxlBlendingInfo(REPLACE, 0, false, 0)

        fun read(br: JxlBitReader, numExtraChannels: Int, partialFrame: Boolean): JxlBlendingInfo {
            val mode = br.u32(fv(REPLACE), fv(ADD), fv(BLEND), fo(2, 3))
            if (mode > MUL) jxlFail("blend mode $mode")
            val usesAlpha = numExtraChannels > 0 && (mode == BLEND || mode == ALPHA_WEIGHTED_ADD)
            var alpha = 0
            if (usesAlpha) {
                alpha = br.u32(fv(0), fv(1), fv(2), fo(3, 3))
                if (alpha >= numExtraChannels) jxlFail("blending with alpha channel $alpha of $numExtraChannels")
            }
            val clamp = (usesAlpha || mode == MUL) && br.bool()
            val source = if (mode != REPLACE || partialFrame) br.u32(fv(0), fv(1), fv(2), fv(3)) else 0
            return JxlBlendingInfo(mode, alpha, clamp, source)
        }
    }
}

/** The smoothing a frame asks for after decoding: Gaborish and the edge-preserving filter. */
internal class JxlLoopFilter(
    val gab: Boolean,
    /** Gaborish weights: for each of X, Y and B, the weight of the four nearest and of the four diagonal neighbours. */
    val gabWeights: DoubleArray,
    val epfIters: Int,
    val epfSharpLut: DoubleArray,
    val epfChannelScale: DoubleArray,
    val epfPass1Zeroflush: Double,
    val epfPass2Zeroflush: Double,
    val epfQuantMul: Double,
    val epfPass0SigmaScale: Double,
    val epfPass2SigmaScale: Double,
    val epfBorderSadMul: Double,
    val epfSigmaForModular: Double,
) {
    companion object {
        private const val GAB_W1 = 1.1 * 0.104699568
        private const val GAB_W2 = 1.1 * 0.055680538
        const val EPF_SHARP_ENTRIES = 8

        fun read(br: JxlBitReader, modular: Boolean): JxlLoopFilter {
            var gab = true
            val gabWeights = doubleArrayOf(GAB_W1, GAB_W2, GAB_W1, GAB_W2, GAB_W1, GAB_W2)
            var epfIters = 2
            val sharp = DoubleArray(EPF_SHARP_ENTRIES) { it / (EPF_SHARP_ENTRIES - 1.0) }
            val channelScale = doubleArrayOf(40.0, 5.0, 3.5)
            var pass1Zeroflush = 0.45
            var pass2Zeroflush = 0.6
            var quantMul = 0.46
            var pass0Sigma = 0.9
            var pass2Sigma = 6.5
            var borderSadMul = 2.0 / 3.0
            var sigmaForModular = 1.0
            if (!br.bool()) {
                gab = br.bool()
                if (gab && br.bool()) {
                    for (c in 0 until 3) {
                        gabWeights[c * 2] = br.f16()
                        gabWeights[c * 2 + 1] = br.f16()
                        val norm = 1.0 + (gabWeights[c * 2] + gabWeights[c * 2 + 1]) * 4
                        if (norm < 1e-8 && norm > -1e-8) jxlFail("Gaborish weights sum to zero")
                    }
                }
                epfIters = br.bits(2)
                if (epfIters > 0) {
                    if (!modular && br.bool()) for (i in sharp.indices) sharp[i] = br.f16()
                    if (br.bool()) {
                        for (c in 0 until 3) channelScale[c] = br.f16()
                        pass1Zeroflush = br.f16()
                        pass2Zeroflush = br.f16()
                    }
                    if (br.bool()) {
                        if (!modular) quantMul = br.f16()
                        pass0Sigma = br.f16()
                        pass2Sigma = br.f16()
                        borderSadMul = br.f16()
                    }
                    if (modular) {
                        sigmaForModular = br.f16()
                        if (sigmaForModular < 1e-8) jxlFail("edge-preserving filter sigma for modular is too small")
                    }
                }
                JxlImageHeader.skipExtensions(br)
            }
            return JxlLoopFilter(
                gab, gabWeights, epfIters, sharp, channelScale, pass1Zeroflush, pass2Zeroflush,
                quantMul, pass0Sigma, pass2Sigma, borderSadMul, sigmaForModular,
            )
        }
    }
}

/**
 * The sizes of a frame in every unit the decoder counts in: samples, 8 by 8 blocks, groups
 * of [groupDim] samples and LF groups of [groupDim] blocks. libjxl's FrameDimensions.
 */
internal class JxlFrameDim(
    xsizeUpsampled: Int,
    ysizeUpsampled: Int,
    groupSizeShift: Int,
    maxHShift: Int,
    maxVShift: Int,
    modular: Boolean,
    upsampling: Int,
) {
    val groupDim = 128 shl groupSizeShift
    val xsizeUpsampled = xsizeUpsampled
    val ysizeUpsampled = ysizeUpsampled

    /** The frame's size as coded, before upsampling. */
    val xsize = ceilDiv(xsizeUpsampled, upsampling)
    val ysize = ceilDiv(ysizeUpsampled, upsampling)
    val xsizeBlocks = ceilDiv(xsize, 8 shl maxHShift) shl maxHShift
    val ysizeBlocks = ceilDiv(ysize, 8 shl maxVShift) shl maxVShift

    /** The coded size rounded up to whole blocks for VarDCT; a modular frame has no padding. */
    val xsizePadded = if (modular) xsize else xsizeBlocks * 8
    val ysizePadded = if (modular) ysize else ysizeBlocks * 8
    val xsizeGroups = ceilDiv(xsize, groupDim)
    val ysizeGroups = ceilDiv(ysize, groupDim)
    val xsizeDcGroups = ceilDiv(xsizeBlocks, groupDim)
    val ysizeDcGroups = ceilDiv(ysizeBlocks, groupDim)
    val numGroups = count(xsizeGroups, ysizeGroups)
    val numDcGroups = count(xsizeDcGroups, ysizeDcGroups)

    companion object {
        fun ceilDiv(a: Int, b: Int): Int = ((a.toLong() + b - 1) / b).toInt()

        /** A frame at the pixel ceiling has 2^21 groups at most. Past this many, the counts that follow overflow. */
        private const val MAX_GROUPS = 1L shl 24

        private fun count(across: Int, down: Int): Int {
            val n = across.toLong() * down
            if (n > MAX_GROUPS) jxlFail("frame of $n groups")
            return n.toInt()
        }
    }
}

/** Everything a frame says about itself before its data. libjxl's FrameHeader. */
internal class JxlFrameHeader(
    val type: Int,
    val modular: Boolean,
    val flags: Long,
    val colorTransform: Int,
    /** The chroma subsampling mode of channels 0, 1 and 2, which are Cb, Y and Cr. */
    val channelMode: IntArray,
    val upsampling: Int,
    val ecUpsampling: IntArray,
    val groupSizeShift: Int,
    val xQmScale: Int,
    val bQmScale: Int,
    val numPasses: Int,
    val numDownsample: Int,
    val downsample: IntArray,
    val lastPass: IntArray,
    val passShift: IntArray,
    val dcLevel: Int,
    val customSizeOrOrigin: Boolean,
    val x0: Int,
    val y0: Int,
    /** The frame's own size, before the division an LF frame applies. */
    val width: Int,
    val height: Int,
    val blending: JxlBlendingInfo,
    val ecBlending: List<JxlBlendingInfo>,
    /** Duration in ticks, as an unsigned 32-bit value. */
    val duration: Long,
    val timecode: Int,
    val isLast: Boolean,
    val saveAsReference: Int,
    val saveBeforeColorTransform: Boolean,
    val name: String,
    val loopFilter: JxlLoopFilter,
) {
    val hasNoise: Boolean get() = flags and FLAG_NOISE != 0L
    val hasPatches: Boolean get() = flags and FLAG_PATCHES != 0L
    val hasSplines: Boolean get() = flags and FLAG_SPLINES != 0L
    val usesLfFrame: Boolean get() = flags and FLAG_USE_DC_FRAME != 0L
    val skipAdaptiveLfSmoothing: Boolean get() = flags and FLAG_SKIP_ADAPTIVE_DC_SMOOTHING != 0L

    val maxHShift: Int = channelMode.maxOf { H_SHIFT[it] }
    val maxVShift: Int = channelMode.maxOf { V_SHIFT[it] }

    /** How many times channel [c] is halved across, relative to the frame. */
    fun hShift(c: Int): Int = maxHShift - H_SHIFT[channelMode[c]]
    fun vShift(c: Int): Int = maxVShift - V_SHIFT[channelMode[c]]
    val is444: Boolean get() = maxHShift == 0 && maxVShift == 0

    val canBeReferenced: Boolean get() = !isLast && type != LF_FRAME && (duration == 0L || saveAsReference != 0)

    /** Whether the frame's samples are converted to the output colour space. */
    val needsColorTransform: Boolean
        get() = !saveBeforeColorTransform || type == REGULAR_FRAME || type == SKIP_PROGRESSIVE

    val dim: JxlFrameDim = JxlFrameDim(
        if (dcLevel != 0) JxlFrameDim.ceilDiv(width, 1 shl (3 * dcLevel)) else width,
        if (dcLevel != 0) JxlFrameDim.ceilDiv(height, 1 shl (3 * dcLevel)) else height,
        groupSizeShift, maxHShift, maxVShift, modular, upsampling,
    )

    /** How many sections the table of contents of this frame lists. */
    val numTocEntries: Int
        get() = if (dim.numGroups == 1 && numPasses == 1) 1 else 2 + dim.numDcGroups + dim.numGroups * numPasses

    companion object {
        const val REGULAR_FRAME = 0
        const val LF_FRAME = 1
        const val REFERENCE_ONLY = 2
        const val SKIP_PROGRESSIVE = 3

        const val XYB = 0
        const val NONE = 1
        const val YCBCR = 2

        const val FLAG_NOISE = 1L
        const val FLAG_PATCHES = 2L
        const val FLAG_SPLINES = 16L
        const val FLAG_USE_DC_FRAME = 32L
        const val FLAG_SKIP_ADAPTIVE_DC_SMOOTHING = 128L

        const val MAX_PASSES = 11

        private val H_SHIFT = intArrayOf(0, 1, 1, 0)
        private val V_SHIFT = intArrayOf(0, 1, 0, 1)

        /**
         * Reads the header of the next frame of [image]. A preview frame takes its size from
         * the image header's preview size.
         */
        fun read(br: JxlBitReader, image: JxlImageHeader, preview: Boolean = false): JxlFrameHeader {
            val numExtra = image.extraChannels.size
            val defaultWidth = if (preview) image.previewWidth else image.width
            val defaultHeight = if (preview) image.previewHeight else image.height
            val allDefault = br.bool()

            var type = REGULAR_FRAME
            var modular = false
            var flags = 0L
            var transform = if (image.xybEncoded) XYB else NONE
            val channelMode = IntArray(3)
            var upsampling = 1
            val ecUpsampling = IntArray(numExtra) { 1 shl image.extraChannels[it].dimShift }
            var groupSizeShift = 1
            var xQm = if (image.xybEncoded) 3 else 2
            var bQm = 2
            var numPasses = 1
            var numDownsample = 0
            val downsample = IntArray(MAX_PASSES)
            val lastPass = IntArray(MAX_PASSES)
            val shift = IntArray(MAX_PASSES)
            var dcLevel = 0
            var custom = false
            var x0 = 0
            var y0 = 0
            var width = defaultWidth
            var height = defaultHeight
            var blending = JxlBlendingInfo.DEFAULT
            var ecBlending = List(numExtra) { JxlBlendingInfo.DEFAULT }
            var duration = 0L
            var timecode = 0
            var isLast = true
            var saveAsReference = 0
            var saveBefore = false
            var name = ""
            var loopFilter: JxlLoopFilter? = null

            if (!allDefault) {
                type = br.u32(fv(REGULAR_FRAME), fv(LF_FRAME), fv(REFERENCE_ONLY), fv(SKIP_PROGRESSIVE))
                if (preview && type != REGULAR_FRAME) jxlFail("a preview must be a regular frame")
                modular = br.bool()
                flags = br.u64()
                if (!image.xybEncoded) transform = if (br.bool()) YCBCR else NONE
                val useLfFrame = flags and FLAG_USE_DC_FRAME != 0L
                if (transform == YCBCR && !useLfFrame) for (c in 0 until 3) channelMode[c] = br.bits(2)
                if (!useLfFrame) {
                    upsampling = br.u32(fv(1), fv(2), fv(4), fv(8))
                    for (i in 0 until numExtra) {
                        val up = br.u32(fv(1), fv(2), fv(4), fv(8)) shl image.extraChannels[i].dimShift
                        if (up < upsampling) jxlFail("extra channel upsampled by $up, less than the colour's $upsampling")
                        if (up > 8) jxlFail("extra channel upsampled by $up")
                        ecUpsampling[i] = up
                    }
                }
                if (modular) groupSizeShift = br.bits(2)
                if (!modular && transform == XYB) {
                    xQm = br.bits(3)
                    bQm = br.bits(3)
                } else {
                    xQm = 2
                    bQm = 2
                }
                if (type != REFERENCE_ONLY) {
                    numPasses = br.u32(fv(1), fv(2), fv(3), fo(3, 4))
                    if (numPasses != 1) {
                        numDownsample = br.u32(fv(0), fv(1), fv(2), fo(1, 3))
                        if (numDownsample > numPasses) jxlFail("$numDownsample downsampling steps in $numPasses passes")
                        for (i in 0 until numPasses - 1) shift[i] = br.bits(2)
                        for (i in 0 until numDownsample) {
                            downsample[i] = br.u32(fv(1), fv(2), fv(4), fv(8))
                            if (i > 0 && downsample[i] >= downsample[i - 1]) jxlFail("downsampling steps must decrease")
                        }
                        for (i in 0 until numDownsample) {
                            lastPass[i] = br.u32(fv(0), fv(1), fv(2), fb(3))
                            if (i > 0 && lastPass[i] <= lastPass[i - 1]) jxlFail("last passes must increase")
                            if (lastPass[i] >= numPasses) jxlFail("last pass ${lastPass[i]} of $numPasses")
                        }
                    }
                }
                if (type == LF_FRAME) dcLevel = br.u32(fv(1), fv(2), fv(3), fv(4))
                var partial = false
                if (type != LF_FRAME) {
                    custom = br.bool()
                    if (custom) {
                        val d0 = fb(8)
                        val d1 = fo(11, 256)
                        val d2 = fo(14, 2304)
                        val d3 = fo(30, 18688)
                        val positioned = type == REGULAR_FRAME || type == SKIP_PROGRESSIVE
                        if (positioned) {
                            x0 = unpackSigned(br.u32(d0, d1, d2, d3))
                            y0 = unpackSigned(br.u32(d0, d1, d2, d3))
                        }
                        width = br.u32(d0, d1, d2, d3)
                        height = br.u32(d0, d1, d2, d3)
                        if (width <= 0 || height <= 0) jxlFail("frame of ${width.toUInt()} by ${height.toUInt()}")
                        if (positioned) {
                            partial = x0 > 0 || y0 > 0 ||
                                width.toLong() + x0 < defaultWidth || height.toLong() + y0 < defaultHeight
                        }
                    }
                }
                if (type == REGULAR_FRAME || type == SKIP_PROGRESSIVE) {
                    blending = JxlBlendingInfo.read(br, numExtra, partial)
                    ecBlending = List(numExtra) { JxlBlendingInfo.read(br, numExtra, partial) }
                    if (preview && (custom || blending.mode != JxlBlendingInfo.REPLACE || ecBlending.any { it.mode != JxlBlendingInfo.REPLACE })) {
                        jxlFail("a preview cannot blend")
                    }
                    val animation = image.animation
                    if (animation != null) {
                        duration = br.u32(fv(0), fv(1), fb(8), fb(32)).toLong() and 0xFFFFFFFFL
                        if (animation.haveTimecodes) timecode = br.bits(32)
                    }
                    isLast = br.bool()
                } else {
                    isLast = false
                }
                if (type != LF_FRAME && !isLast) saveAsReference = br.u32(fv(0), fv(1), fv(2), fv(3))
                if (type != LF_FRAME) {
                    val referenced = !isLast && (duration == 0L || saveAsReference != 0)
                    if (referenced && blending.mode == JxlBlendingInfo.REPLACE && !partial &&
                        (type == REGULAR_FRAME || type == SKIP_PROGRESSIVE)
                    ) {
                        saveBefore = br.bool()
                    } else if (type == REFERENCE_ONLY) {
                        saveBefore = br.bool()
                        if (!saveBefore && (width < image.width || height < image.height || x0 != 0 || y0 != 0)) {
                            jxlFail("a reference frame smaller than the image is saved after its colour transform")
                        }
                    }
                } else {
                    saveBefore = true
                }
                name = readName(br)
                loopFilter = JxlLoopFilter.read(br, modular)
                JxlImageHeader.skipExtensions(br)
            }
            return JxlFrameHeader(
                type, modular, flags, transform, channelMode, upsampling, ecUpsampling, groupSizeShift, xQm, bQm,
                numPasses, numDownsample, downsample, lastPass, shift, dcLevel, custom, x0, y0, width, height,
                blending, ecBlending, duration, timecode, isLast, saveAsReference, saveBefore, name,
                loopFilter ?: JxlLoopFilter.read(ALL_DEFAULT_BITS(), modular),
            )
        }

        /** A reader over one set bit, which every bundle reads as "all default". */
        @Suppress("FunctionName")
        private fun ALL_DEFAULT_BITS() = JxlBitReader(byteArrayOf(1), 0, 1)
    }
}

/**
 * The table of contents of a frame: where each of its sections starts in the data and how
 * long it is, in the order the decoder reads them.
 */
internal class JxlToc(val offsets: IntArray, val sizes: IntArray, val end: Int) {
    companion object {
        /** Reads the table of [entries] sections; the sections start where the table ends. */
        fun read(br: JxlBitReader, entries: Int): JxlToc {
            if (entries < 1) jxlFail("table of contents of $entries sections")
            // An entry takes 12 bits at least, so the data left bounds how many there can be.
            fun checkBudget(n: Int) {
                if (n.toLong() * 12 > br.bitsLeft) jxlFail("data ends inside the table of contents")
            }
            var permutation: IntArray? = null
            if (br.bool()) {
                checkBudget(entries)
                permutation = IntArray(entries)
                jxlDecodePermutation(br, 0, entries, permutation)
            }
            br.zeroPadToByte()
            checkBudget(entries)
            val stored = IntArray(entries) {
                val size = br.u32(fb(10), fo(14, 1024), fo(22, 17408), fo(30, 4211712))
                if (size < 0) jxlFail("section of ${size.toUInt()} bytes")
                size
            }
            br.zeroPadToByte()
            val base = br.bytePosition
            val storedOffsets = IntArray(entries)
            var offset = base.toLong()
            for (i in 0 until entries) {
                storedOffsets[i] = offset.toInt()
                offset += stored[i]
                if (offset > Int.MAX_VALUE) jxlFail("sections run past 2 GiB")
            }
            if (permutation == null) return JxlToc(storedOffsets, stored, offset.toInt())
            return JxlToc(IntArray(entries) { storedOffsets[permutation[it]] }, IntArray(entries) { stored[permutation[it]] }, offset.toInt())
        }
    }
}
