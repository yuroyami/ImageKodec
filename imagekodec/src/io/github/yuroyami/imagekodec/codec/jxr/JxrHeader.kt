/*
 * JPEG XR codestream headers: a Kotlin port of jxrlib's ReadWMIHeader, ReadImagePlaneHeader and
 * readQuantizerSB (image/decode/strdec.c), at 4creators/jxrlib commit 1ef17dd.
 *
 * Copyright (c) Microsoft Corp. All rights reserved.
 *
 * Use of this source code is governed by the BSD 2-Clause license that is reproduced in this
 * repository's NOTICE.
 */
package io.github.yuroyami.imagekodec.codec.jxr

/** The subbands a plane holds (SUBBAND in windowsmediaphoto.h, BANDS_PRESENT of T.832 Table 9). */
internal object JxrBands {
    const val ALL = 0
    const val NO_FLEXBITS = 1
    const val NO_HIGHPASS = 2
    const val DC_ONLY = 3
    const val ISOLATED = 4
}

/** IMAGE_PLANE_HEADER of T.832 8.4: one plane's colour format, subbands and frame quantizers. */
internal class JxrPlaneHeader(
    val colorFormat: Int,
    val scaledArith: Boolean,
    val subband: Int,
    val channels: Int,
    /** SHIFT_BITS or LEN_MANTISSA, for 16- and 32-bit samples. */
    val lenMantissaOrShift: Int,
    val expBias: Int,
    /** jxrlib's uQPMode: bit 0, 1 and 2 for a DC, LP and HP quantizer per tile, bits 3 to 8 the channel modes, 0x200 and 0x400 a quantizer of their own for LP and HP. */
    val qpMode: Int,
    val qpIndexDC: IntArray,
    val qpIndexLP: IntArray,
    val qpIndexHP: IntArray,
)

/** IMAGE_HEADER of T.832 8.3 and the header of the first plane, with where the plane data starts. */
internal class JxrImageHeader(
    val subVersion: Int,
    val hardTiles: Boolean,
    val frequencyOrder: Boolean,
    val orientation: Int,
    val indexTable: Boolean,
    val overlap: Int,
    val trimFlexbits: Boolean,
    val rbSwapped: Boolean,
    val alphaPlane: Boolean,
    val sourceColorFormat: Int,
    val sourceBitDepth: Int,
    val blackIsOne: Boolean,
    /** The size of the image, without the windowing margins. */
    val width: Int,
    val height: Int,
    val extraTop: Int,
    val extraLeft: Int,
    val extraBottom: Int,
    val extraRight: Int,
    /** The macroblock column and row each tile starts at. */
    val tileX: IntArray,
    val tileY: IntArray,
    val plane: JxrPlaneHeader,
    /** The plane header of the alpha plane that follows, when [alphaPlane]. */
    val alpha: JxrPlaneHeader?,
    /** The byte after the plane headers, where the index table starts. */
    val end: Int,
) {
    val mbWidth: Int get() = (width + extraLeft + extraRight + 15) / 16
    val mbHeight: Int get() = (height + extraTop + extraBottom + 15) / 16

    companion object {
        private const val CODEC_VERSION = 1

        /** Reads the header of the codestream at [start] of [data], up to [end]. */
        fun read(data: ByteArray, start: Int, end: Int): JxrImageHeader {
            if (end - start < 8 || "WMPHOTO".encodeToByteArray().withIndex().any { (i, b) -> data[start + i] != b }) {
                jxrFail("the codestream does not start with WMPHOTO")
            }
            val sb = JxrBits(data, start + 8, end)
            val version = sb.bits(4)
            if (version != CODEC_VERSION) jxrFail("codec version $version")
            // Subversion 0 is HD Photo's scaling; 1 and 9 the scaling of JPEG XR, with soft and hard tile edges.
            val subVersion = sb.bits(4)
            if (subVersion != 0 && subVersion != 1 && subVersion != 9) jxrFail("codec subversion $subVersion")
            val tiling = sb.bool() == 1
            val frequency = sb.bool() == 1
            val orientation = sb.bits(3)
            val indexTable = sb.bool() == 1
            val overlap = sb.bits(2)
            if (overlap == 3) jxrFail("overlap mode 3")
            val short = sb.bool() == 1
            sb.bool() // LONG_WORD_FLAG, which jxrlib ignores
            val windowed = sb.bool() == 1
            val trimFlexbits = sb.bool() == 1
            val tileStretch = sb.bool() == 1
            val rbSwapped = sb.bool() == 1
            sb.bool() // PREMULTIPLIED_ALPHA_FLAG, which the pixel format also says
            val alphaPlane = sb.bool() == 1
            val sourceColor = sb.bits(4)
            var sourceDepth = sb.bits(4)
            var blackIsOne = false
            if (sourceDepth == JxrDepth.BD_1_ALT) {
                sourceDepth = JxrDepth.BD_1
                blackIsOne = true
            }
            var width = sb.bits32(if (short) 16 else 32).toLong().and(0xFFFFFFFFL) + 1
            var height = sb.bits32(if (short) 16 else 32).toLong().and(0xFFFFFFFFL) + 1
            if (width > MAX_SIDE || height > MAX_SIDE) jxrFail("an image of $width by $height")
            var extraTop = 0
            var extraLeft = 0
            var extraBottom = 0
            var extraRight = if (!windowed && width % 16 != 0L) (16 - width % 16).toInt() else 0
            if (!windowed && height % 16 != 0L) extraBottom = (16 - height % 16).toInt()
            var numV = 0
            var numH = 0
            if (tiling) {
                numV = sb.bits(12)
                numH = sb.bits(12)
            }
            if (!indexTable && (frequency || numV + numH > 0)) jxrFail("a frequency order or tiled codestream without an index table")
            val tileX = IntArray(numV + 1)
            val tileY = IntArray(numH + 1)
            for (i in 0 until numV) tileX[i + 1] = sb.bits(if (short) 8 else 16) + tileX[i]
            for (i in 0 until numH) tileY[i + 1] = sb.bits(if (short) 8 else 16) + tileY[i]
            if (tileStretch) repeat((numV + 1) * (numH + 1)) { sb.bits(8) }
            if (windowed) {
                extraTop = sb.bits(6)
                extraLeft = sb.bits(6)
                extraBottom = sb.bits(6)
                extraRight = sb.bits(6)
            }
            if (((width + extraLeft + extraRight) and 15) + ((height + extraTop + extraBottom) and 15) != 0L) {
                if ((width and 15) + (height and 15) + extraLeft + extraTop != 0L) jxrFail("a window that leaves part of a macroblock")
                if (width <= extraRight || height <= extraBottom) jxrFail("a window wider than the image")
                width -= extraRight
                height -= extraBottom
            }
            val mbWidth = ((width + extraLeft + extraRight + 15) / 16).toInt()
            val mbHeight = ((height + extraTop + extraBottom + 15) / 16).toInt()
            // jxrlib walks into a tile only at its first macroblock, so a tile that starts nowhere is damage.
            for (i in 1..numV) if (tileX[i] <= tileX[i - 1] || tileX[i] >= mbWidth) jxrFail("tile column $i starts at macroblock ${tileX[i]}")
            for (i in 1..numH) if (tileY[i] <= tileY[i - 1] || tileY[i] >= mbHeight) jxrFail("tile row $i starts at macroblock ${tileY[i]}")
            sb.alignToByte()
            val plane = readPlane(sb, sourceDepth)
            val alpha = if (alphaPlane) readPlane(sb, sourceDepth) else null
            if ((sourceDepth == JxrDepth.BD_5 || sourceDepth == JxrDepth.BD_10 || sourceDepth == JxrDepth.BD_565) &&
                plane.colorFormat !in JxrColor.Y_ONLY..JxrColor.YUV_444
            ) {
                jxrFail("packed samples in colour format ${plane.colorFormat}")
            }
            return JxrImageHeader(
                subVersion, subVersion == 9, frequency, orientation, indexTable, overlap, trimFlexbits, rbSwapped,
                alphaPlane, sourceColor, sourceDepth, blackIsOne, width.toInt(), height.toInt(),
                extraTop, extraLeft, extraBottom, extraRight, tileX, tileY, plane, alpha, sb.bytePosition,
            )
        }

        /** The largest side a 32-bit size field can give that the pixel ceiling could still allow. */
        private const val MAX_SIDE = 1L shl 28

        /** ReadImagePlaneHeader. */
        private fun readPlane(sb: JxrBits, bitDepth: Int): JxrPlaneHeader {
            val cf = sb.bits(3)
            if (cf == 5 || cf == 7) jxrFail("internal colour format $cf")
            val scaled = sb.bool() == 1
            val subband = sb.bits(4)
            val channels = when (cf) {
                JxrColor.Y_ONLY -> 1
                JxrColor.YUV_420 -> {
                    sb.bits(1); sb.bits(3); sb.bits(1); sb.bits(3) // chroma centring, which jxrlib does not use
                    3
                }
                JxrColor.YUV_422 -> {
                    sb.bits(1); sb.bits(3); sb.bits(4)
                    3
                }
                JxrColor.YUV_444 -> {
                    sb.bits(4); sb.bits(4)
                    3
                }
                JxrColor.NCOMPONENT -> {
                    val n = sb.bits(4) + 1
                    sb.bits(4)
                    n
                }
                else -> 4
            }
            var len = 0
            var bias = 0
            when (bitDepth) {
                JxrDepth.BD_16, JxrDepth.BD_16S, JxrDepth.BD_32, JxrDepth.BD_32S -> len = sb.bits(8)
                JxrDepth.BD_32F -> {
                    len = sb.bits(8)
                    bias = sb.bits(8).toByte().toInt()
                }
            }
            val dc = IntArray(16)
            val lp = IntArray(16)
            val hp = IntArray(16)
            var mode = 0
            if (sb.bool() == 1) mode += readQuantizer(dc, sb, channels) shl 3 else mode++
            if (subband != JxrBands.DC_ONLY) {
                if (sb.bool() == 0) {
                    mode += 0x200
                    if (sb.bool() == 1) mode += readQuantizer(lp, sb, channels) shl 5 else mode += 2
                } else {
                    mode += ((mode and 1) shl 1) + ((mode and 0x18) shl 2)
                }
                if (subband != JxrBands.NO_HIGHPASS) {
                    if (sb.bool() == 0) {
                        mode += 0x400
                        if (sb.bool() == 1) mode += readQuantizer(hp, sb, channels) shl 7 else mode += 4
                    } else {
                        mode += ((mode and 2) shl 1) + ((mode and 0x60) shl 2)
                    }
                }
            }
            if (subband == JxrBands.DC_ONLY) mode = mode or 0x200 else if (subband == JxrBands.NO_HIGHPASS) mode = mode or 0x400
            if (mode and 0x600 == 0) jxrFail("frame quantizers that depend on each other")
            sb.alignToByte()
            return JxrPlaneHeader(cf, scaled, subband, channels, len, bias, mode, dc, lp, hp)
        }

        /** readQuantizerSB: a quantizer index per channel, and how the channels share them. */
        private fun readQuantizer(index: IntArray, sb: JxrBits, channels: Int): Int {
            if (channels >= 16) return 0
            val mode = if (channels > 1) sb.bits(2) else 0
            index[0] = sb.bits(8)
            if (mode == 1) {
                index[1] = sb.bits(8)
            } else if (mode > 0) {
                for (i in 1 until channels) index[i] = sb.bits(8)
            }
            return mode
        }
    }
}
