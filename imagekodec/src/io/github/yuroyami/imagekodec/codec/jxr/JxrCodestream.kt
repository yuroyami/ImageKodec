/*
 * Decoding a JPEG XR codestream: a Kotlin port of jxrlib's ImageStrDecInit, ImageStrDecDecode,
 * readIndexTable, readPackets, processMacroblockDec, interpolateUV, outputMBRow, outputNChannel
 * and outputMBRowAlpha (image/decode/strdec.c), at 4creators/jxrlib commit 1ef17dd.
 *
 * Copyright (c) Microsoft Corp. All rights reserved.
 *
 * Use of this source code is governed by the BSD 2-Clause license that is reproduced in this
 * repository's NOTICE. Altered for Kotlin: the whole image decodes in one call, with no region of
 * interest, orientation or post-processing, and samples go to a [JxrRaster] in place of jxrlib's
 * output buffer.
 */
package io.github.yuroyami.imagekodec.codec.jxr

/**
 * Decoded samples as jxrlib writes them for a pixel format: [slots] values a pixel, each a
 * sample of the format's bit depth (an 8-bit or 16-bit value, a signed fixed-point value, the bits
 * of a half or a float), or a whole packed pixel for the 5, 5-6-5 and 10-bit formats.
 */
internal class JxrRaster(val width: Int, val height: Int, val slots: Int) {
    val samples = IntArray(width * height * slots)
}

/**
 * How the decoder writes a plane out (jxrlib's CWMImageInfo after WMPhotoValidate): the colour
 * format and bit depth of the samples, red or blue first, whether the interleaved alpha plane is
 * written, and the slot the first sample goes to.
 */
internal class JxrOutputFormat(
    val colorFormat: Int,
    val bitDepth: Int,
    val rgb: Boolean,
    val alpha: Boolean,
    val leadingPadding: Int,
)

internal object JxrCodestream {

    /**
     * Decodes the codestream from [start] to [end] of [data] into [raster], as [format] asks.
     * [cancel] runs before each row of macroblocks. A [scale] of 2, 4 or 8 decodes jxrlib's
     * thumbnail of that scale (its cThumbnailScale): every [scale]th sample of each row and
     * column, from the full transform at 2 and from the second stage alone at 4 and 8.
     *
     * jxrlib fills a thumbnail of a gray codestream in a BGR format from the blue slot, which it
     * never wrote, so the picture is black; [asJxrlib] keeps that, and without it the gray fills
     * all three as in a full decode.
     */
    fun decode(
        data: ByteArray, start: Int, end: Int, format: JxrOutputFormat, raster: JxrRaster, cancel: (() -> Unit)?,
        scale: Int = 1, asJxrlib: Boolean = true,
    ) {
        val image = JxrImageHeader.read(data, start, end)
        if (scale > 1 && (image.extraLeft != 0 || image.extraTop != 0)) jxrFail("a thumbnail of a windowed image")
        if ((image.width + scale - 1) / scale != raster.width || (image.height + scale - 1) / scale != raster.height) {
            jxrFail("a plane of ${image.width} by ${image.height} in an image of ${raster.width} by ${raster.height}")
        }
        val primary = JxrPlane(image, image.plane, secondary = false)
        if (primary.subband == JxrBands.ISOLATED) jxrFail("an isolated plane")
        if (primary.subband > JxrBands.ISOLATED) jxrFail("subband mode ${primary.subband}")
        val alpha = image.alpha?.let { JxrPlane(image, it, secondary = true) }
        // setROI runs on each plane in turn and both share its result, so the alpha plane's subbands rule.
        val ruling = alpha ?: primary
        for (plane in listOfNotNull(primary, alpha)) {
            plane.skipFlexbits = ruling.subband == JxrBands.NO_FLEXBITS
            plane.decodeHP = ruling.subband == JxrBands.ALL || ruling.subband == JxrBands.NO_FLEXBITS
            plane.decodeLP = ruling.subband != JxrBands.DC_ONLY
            // A thumbnail of a quarter or less samples the DCs alone, so a frequency order file skips its highpass.
            if (image.frequencyOrder && scale >= 4) plane.decodeHP = false
        }
        val bands = when (primary.subband) {
            JxrBands.DC_ONLY -> 1
            JxrBands.NO_HIGHPASS -> 2
            JxrBands.NO_FLEXBITS -> 3
            else -> 4
        }
        val packets = if (!image.indexTable) 0 else if (!image.frequencyOrder) primary.numV + 1 else (primary.numV + 1) * bands

        // readIndexTable: where each packet of each tile row starts, after the header.
        val io = JxrBits(data, image.end, end)
        // Each entry takes a byte at least, so a table longer than the data is damage, not an allocation.
        if (packets.toLong() * (primary.numH + 1) > end - image.end) jxrFail("an index table longer than the codestream")
        val index = LongArray(packets * (primary.numH + 1))
        if (packets > 0) {
            if (io.bits(16) != 1) jxrFail("no index table")
            for (i in index.indices) index[i] = vlWord(io)
        }
        var headerSize = vlWord(io)
        io.alignToByte()
        headerSize += io.bytePosition
        if (headerSize > end) jxrFail("the tiles start past the end of the codestream")

        val planes = listOfNotNull(primary, alpha)
        val overlap = image.overlap
        val uvResolution = format.colorFormat != JxrColor.Y_ONLY &&
            ((primary.cf == JxrColor.YUV_420 && format.colorFormat != JxrColor.YUV_420) ||
                (primary.cf == JxrColor.YUV_422 && format.colorFormat != JxrColor.YUV_422))
        val out = Output(image, primary, alpha, format, raster, uvResolution, scale)
        for (row in 0..primary.mbHeight) {
            cancel?.invoke()
            for (plane in planes) {
                plane.row = row
                plane.startRow()
            }
            for (column in 0..primary.mbWidth) {
                val bottomOrRight = row == primary.mbHeight || column == primary.mbWidth
                for (plane in planes) {
                    plane.column = column
                    if (!bottomOrRight) {
                        if (plane !== primary) {
                            plane.tileColumn = primary.tileColumn
                            plane.tileRow = primary.tileRow
                        }
                        plane.tilePosition(column, row)
                        if (plane === primary) {
                            readPackets(primary, alpha, image, data, end, index, packets, bands, headerSize)
                        } else if (column == 0 && row == plane.tileY[plane.tileRow]) {
                            for (ctx in plane.contexts) ctx.reset()
                        }
                        val ctx = plane.contexts[plane.tileColumn]
                        plane.decodeDC(ctx)
                        if (plane.decodeLP) plane.decodeLowpass(ctx)
                        plane.predictDCAC()
                        plane.dequantize()
                        if (plane.decodeHP) {
                            plane.decodeHighpass(ctx)
                            plane.predictAC()
                        }
                        plane.updatePrediction()
                    }
                    JxrTransform.macroblock(plane, overlap, scale)
                    plane.advance()
                }
            }
            if (row > 0) {
                if (scale < 2) out.row(row) else out.thumbnail(row)
            }
            for (plane in planes) plane.endRow()
        }
        if (format.colorFormat == JxrColor.RGB && primary.cf == JxrColor.Y_ONLY) {
            if (scale < 2 || !asJxrlib) out.grayToRgb() else out.grayToRgbThumbnail()
        }
    }

    /** GetVLWordEsc: a byte-aligned size of 2, 4 or 8 bytes, or 0 for an escape. */
    private fun vlWord(io: JxrBits): Long {
        var s = io.bits32(8).toLong()
        if (s == 0xFDL || s == 0xFEL || s == 0xFFL) return 0
        if (s < 0xFB) return (s shl 8) or io.bits32(8).toLong()
        s -= 0xFB
        if (s != 0L) {
            // An 8-byte size: jxrlib keeps the low 32 bits of its high word, which no file this side of 2^32 bytes needs.
            io.bits32(16)
            io.bits32(16)
        }
        return (io.bits32(16).toLong() shl 16) or io.bits32(16).toLong()
    }

    /** readPacketHeader: the start code 00 00 01 and the packet's tile number. */
    private fun packetHeader(io: JxrBits): Boolean {
        val ok = io.bits(8) == 0 && io.bits(8) == 0 && io.bits(8) == 1
        if (!ok) return false
        io.bits(8)
        return true
    }

    /** readPackets: at the start of each tile row, the readers of its packets and the contexts they reset; at the start of each tile, its quantizers. */
    private fun readPackets(
        primary: JxrPlane, alpha: JxrPlane?, image: JxrImageHeader, data: ByteArray, end: Int,
        index: LongArray, packets: Int, bands: Int, headerSize: Long,
    ) {
        if (primary.column == 0 && primary.row == primary.tileY[primary.tileRow]) {
            fun reader(offset: Long): JxrBits {
                val at = headerSize + offset
                if (at < 0 || at >= end) jxrFail("a packet past the end of the codestream")
                return JxrBits(data, at.toInt(), end)
            }
            val readers = if (packets == 0) arrayOf(reader(0)) else Array(packets) { reader(index[packets * primary.tileRow + it]) }
            for (plane in listOfNotNull(primary, alpha)) {
                for (k in 0..primary.numV) {
                    val ctx = plane.contexts[k]
                    if (packets == 0 || !image.frequencyOrder) {
                        val r = if (packets == 0) readers[0] else readers[k]
                        ctx.ioDC = r
                        ctx.ioLP = r
                        ctx.ioAC = r
                        ctx.ioFL = r
                    } else {
                        ctx.ioDC = readers[k * bands]
                        if (bands > 1) ctx.ioLP = readers[k * bands + 1]
                        if (bands > 2) ctx.ioAC = readers[k * bands + 2]
                        if (bands > 3) ctx.ioFL = readers[k * bands + 3]
                    }
                }
            }
            for (k in 0..primary.numV) {
                val ctx = primary.contexts[k]
                if (!image.frequencyOrder) {
                    if (!packetHeader(ctx.ioDC)) jxrFail("a tile packet without its start code")
                    ctx.trimFlexbits = if (image.trimFlexbits) ctx.ioDC.bits(4) else 0
                } else {
                    if (!packetHeader(ctx.ioDC)) jxrFail("a DC packet without its start code")
                    if (bands > 1 && !packetHeader(ctx.ioLP)) jxrFail("a lowpass packet without its start code")
                    if (bands > 2 && !packetHeader(ctx.ioAC)) jxrFail("a highpass packet without its start code")
                    if (bands > 3) {
                        val fl = ctx.ioFL ?: jxrFail("no flexbits packet")
                        // jxrlib reads on after a flexbits packet whose start code is wrong.
                        packetHeader(fl)
                        ctx.trimFlexbits = if (image.trimFlexbits) fl.bits(4) else 0
                    }
                }
                ctx.reset()
            }
        }
        if (primary.ctxLeft && primary.ctxTop) {
            if (alpha != null) {
                alpha.tileColumn = primary.tileColumn
                alpha.tileRow = primary.tileRow
            }
            val ctx = primary.contexts[primary.tileColumn]
            primary.readTileHeaderDC(ctx.ioDC)
            alpha?.readTileHeaderDC(ctx.ioDC)
            if (bands > 1) {
                primary.readTileHeaderLP(ctx.ioLP)
                alpha?.readTileHeaderLP(ctx.ioLP)
            }
            if (bands > 2) {
                primary.readTileHeaderHP(ctx.ioAC)
                alpha?.readTileHeaderHP(ctx.ioAC)
            }
        }
    }

    /** idxCC of strcodec.c: where sample (row, column) of a macroblock sits among its 16 blocks of 16. */
    private val IDX_CC = IntArray(256) { at -> blockOrder(at shr 4, at and 15, 6) }

    /** idxCC_420: the same for the 8 by 8 chroma of a 4:2:0 macroblock. */
    private val IDX_CC_420 = IntArray(64) { at -> blockOrder(at shr 3, at and 7, 5) }

    private fun blockOrder(r: Int, c: Int, columnShift: Int): Int {
        val inBlock = ((c xor (c shr 1)) and 1) or ((c shr 1 and 1) shl 2) or
            (((r xor (r shr 1)) and 1) shl 1) or ((r shr 1 and 1) shl 3)
        return ((c shr 2) shl columnShift) + ((r shr 2) shl 4) + inBlock
    }

    private fun cc(column: Int, row: Int): Int = ((column shr 4) shl 8) + IDX_CC[(row shl 4) + (column and 15)]

    private fun clip(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else if (v > hi) hi else v

    /** backwardHalf: a half from the decoder's sign and magnitude. */
    private fun backwardHalf(h: Int): Int {
        val s = h shr 31
        return (((h and 0x7FFF) xor s) - s) and 0xFFFF
    }

    /** pixel2float: the bits of a float from a sample of [lm] mantissa bits and exponent bias [c]. */
    private fun pixel2float(h: Int, c: Int, lm: Int): Int {
        val lmShift = 1 shl lm
        val s = h shr 31
        val t = (h xor s) - s
        var e = t ushr lm
        var m = (t and (lmShift - 1)) or lmShift
        if (e == 0) {
            m = m xor lmShift
            e = 1
        }
        e += 127 - c
        while (m < lmShift && e > 1 && m > 0) {
            e--
            m = m shl 1
        }
        if (m < lmShift) e = 0 else m = m xor lmShift
        m = m shl (23 - lm)
        return (s and Int.MIN_VALUE) or (e shl 23) or m
    }

    /** outputMBRow and outputMBRowAlpha, writing finished macroblock rows to the raster. */
    private class Output(
        val image: JxrImageHeader,
        val plane: JxrPlane,
        val alpha: JxrPlane?,
        val format: JxrOutputFormat,
        val raster: JxrRaster,
        val uvResolution: Boolean,
        val scale: Int,
    ) {
        val shift = if (plane.scaled) JxrPlane.SHIFT_ZERO + JxrPlane.QP_FRAC_BITS else 0
        val bias2 = if (plane.scaled) (1 shl (JxrPlane.SHIFT_ZERO + JxrPlane.QP_FRAC_BITS - 1)) - 1 else 0
        val len = plane.header.lenMantissaOrShift
        val expBias = plane.header.expBias
        val resU = if (uvResolution) IntArray(256 * plane.mbWidth) else null
        val resV = if (uvResolution) IntArray(256 * plane.mbWidth) else null
        val cfExt = if (plane.cf == JxrColor.Y_ONLY) JxrColor.Y_ONLY else format.colorFormat

        init {
            if (format.bitDepth == JxrDepth.BD_32F && len > 23) jxrFail("float samples of $len mantissa bits")
            if (len > 31) jxrFail("a shift of $len")
        }

        /** Outputs macroblock row [row] - 1, which the transform at row [row] finished. */
        fun row(row: Int) {
            val mbRow = row - 1
            val y0 = maxOf(mbRow * 16, image.extraTop)
            val y1 = minOf(mbRow * 16 + 16, image.extraTop + raster.height)
            if (uvResolution) interpolateUV(row)
            if (y0 < y1) {
                pixels(mbRow, y0 - mbRow * 16, y1 - mbRow * 16)
                if (format.alpha && alpha != null) alphaRow(mbRow, y0 - mbRow * 16, y1 - mbRow * 16)
            }
        }

        private fun interpolateUV(row: Int) {
            val dU = resU!!
            val dV = resV!!
            val su = plane.buf[1]
            val sv = plane.buf[2]
            val u0 = plane.a0(1)
            val width = plane.mbWidth * 16
            if (plane.cf == JxrColor.YUV_422) {
                for (r in 0 until 16) {
                    var c = 0
                    var d = 0
                    while (c < width) {
                        val s = ((c shr 4) shl 7) + IDX_CC[(r shl 4) + ((c shr 1) and 7)]
                        d = ((c shr 4) shl 8) + IDX_CC[(r shl 4) + (c and 15)]
                        dU[d] = su[u0 + s]
                        dV[d] = sv[u0 + s]
                        if (c > 0) {
                            val l = c - 2
                            val il = ((l shr 4) shl 8) + IDX_CC[(r shl 4) + (l and 15)]
                            val m = c - 1
                            val im = ((m shr 4) shl 8) + IDX_CC[(r shl 4) + (m and 15)]
                            dU[im] = (dU[il] + dU[d] + 1) shr 1
                            dV[im] = (dV[il] + dV[d] + 1) shr 1
                        }
                        c += 2
                    }
                    val last = (((c - 1) shr 4) shl 8) + IDX_CC[(r shl 4) + ((c - 1) and 15)]
                    dU[last] = dU[d]
                    dV[last] = dV[d]
                }
                return
            }
            // 4:2:0 to 4:4:4: first down each column, then along each row.
            val below = plane.a1(1)
            var c = 0
            while (c < width) {
                val mb = (c shr 4) shl 8
                val pix = c and 15
                var d = 0
                var r = 0
                while (r < 16) {
                    val s = ((c shr 4) shl 6) + IDX_CC_420[((r shr 1) shl 3) + ((c shr 1) and 7)]
                    d = mb + IDX_CC[(r shl 4) + pix]
                    dU[d] = su[u0 + s]
                    dV[d] = sv[u0 + s]
                    if (r > 0) {
                        val it = mb + IDX_CC[((r - 2) shl 4) + pix]
                        val ic = mb + IDX_CC[((r - 1) shl 4) + pix]
                        dU[ic] = (dU[it] + dU[d] + 1) shr 1
                        dV[ic] = (dV[it] + dV[d] + 1) shr 1
                    }
                    r += 2
                }
                val last = mb + IDX_CC[(15 shl 4) + pix]
                if (row == plane.mbHeight) {
                    dU[last] = dU[d]
                    dV[last] = dV[d]
                } else {
                    val b = ((c shr 4) shl 6) + IDX_CC_420[(c shr 1) and 7]
                    dU[last] = (su[below + b] + dU[d] + 1) shr 1
                    dV[last] = (sv[below + b] + dV[d] + 1) shr 1
                }
                c += 2
            }
            for (r in 0 until 16) {
                var s = 0
                c = 1
                while (c < width - 2) {
                    val il = (((c - 1) shr 4) shl 8) + IDX_CC[(r shl 4) + ((c - 1) and 15)]
                    val d = ((c shr 4) shl 8) + IDX_CC[(r shl 4) + (c and 15)]
                    s = (((c + 1) shr 4) shl 8) + IDX_CC[(r shl 4) + ((c + 1) and 15)]
                    dU[d] = (dU[s] + dU[il] + 1) shr 1
                    dV[d] = (dV[s] + dV[il] + 1) shr 1
                    c += 2
                }
                val d = (((width - 1) shr 4) shl 8) + IDX_CC[(r shl 4) + ((width - 1) and 15)]
                dU[d] = dU[s]
                dV[d] = dV[s]
            }
        }

        private fun pixels(mbRow: Int, r0: Int, r1: Int) {
            val out = raster.samples
            val slots = raster.slots
            val y = plane.buf[0]
            val yAt = plane.a0(0)
            val bd = format.bitDepth
            val x0 = image.extraLeft
            val x1 = image.extraLeft + raster.width
            if (cfExt == JxrColor.RGB) {
                val u: IntArray
                val v: IntArray
                val uAt: Int
                val vAt: Int
                if (uvResolution) {
                    u = resU!!
                    v = resV!!
                    uAt = 0
                    vAt = 0
                } else {
                    u = plane.buf[1]
                    v = plane.buf[2]
                    uAt = plane.a0(1)
                    vAt = plane.a0(2)
                }
                val iB = if (format.rgb) 2 else 0
                val iR = 2 - iB
                val bias = when (bd) {
                    JxrDepth.BD_8 -> (128 shl shift) + bias2
                    JxrDepth.BD_16 -> (((1 shl 15) shr len) shl shift) + (if (shift == 0) 0 else 1 shl (shift - 1))
                    JxrDepth.BD_5 -> (16 shl shift) + bias2
                    JxrDepth.BD_565 -> (32 shl shift) + bias2
                    JxrDepth.BD_10 -> (512 shl shift) + bias2
                    else -> bias2
                }
                for (r in r0 until r1) {
                    var o = (((mbRow * 16 + r - image.extraTop) * raster.width)) * slots + format.leadingPadding
                    for (c in x0 until x1) {
                        val i = cc(c, r)
                        var g = y[yAt + i] + bias
                        var rr = -u[uAt + i]
                        var b = v[vAt + i]
                        g -= rr shr 1
                        rr -= ((b + 1) shr 1) - g
                        b += rr
                        when (bd) {
                            JxrDepth.BD_8 -> {
                                out[o + iR] = clip(rr shr shift, 0, 255)
                                out[o + 1] = clip(g shr shift, 0, 255)
                                out[o + iB] = clip(b shr shift, 0, 255)
                            }
                            JxrDepth.BD_16 -> {
                                out[o] = clip((rr shr shift) shl len, 0, 65535)
                                out[o + 1] = clip((g shr shift) shl len, 0, 65535)
                                out[o + 2] = clip((b shr shift) shl len, 0, 65535)
                            }
                            JxrDepth.BD_16S -> {
                                out[o] = clip((rr shr shift) shl len, -32768, 32767)
                                out[o + 1] = clip((g shr shift) shl len, -32768, 32767)
                                out[o + 2] = clip((b shr shift) shl len, -32768, 32767)
                            }
                            JxrDepth.BD_16F -> {
                                out[o] = backwardHalf(rr shr shift)
                                out[o + 1] = backwardHalf(g shr shift)
                                out[o + 2] = backwardHalf(b shr shift)
                            }
                            JxrDepth.BD_32S -> {
                                out[o] = (rr shr shift) shl len
                                out[o + 1] = (g shr shift) shl len
                                out[o + 2] = (b shr shift) shl len
                            }
                            JxrDepth.BD_32F -> {
                                out[o] = pixel2float(rr shr shift, expBias, len)
                                out[o + 1] = pixel2float(g shr shift, expBias, len)
                                out[o + 2] = pixel2float(b shr shift, expBias, len)
                            }
                            JxrDepth.BD_5 -> {
                                val R = clip(rr shr shift, 0, 31)
                                val G = clip(g shr shift, 0, 31)
                                val B = clip(b shr shift, 0, 31)
                                out[o] = if (image.rbSwapped) B + (G shl 5) + (R shl 10) else R + (G shl 5) + (B shl 10)
                            }
                            JxrDepth.BD_565 -> {
                                val R = clip(rr shr (shift + 1), 0, 31)
                                val G = clip(g shr shift, 0, 63)
                                val B = clip(b shr (shift + 1), 0, 31)
                                out[o] = if (image.rbSwapped) B + (G shl 5) + (R shl 11) else R + (G shl 5) + (B shl 11)
                            }
                            JxrDepth.BD_10 -> {
                                val R = clip(rr shr shift, 0, 1023)
                                val G = clip(g shr shift, 0, 1023)
                                val B = clip(b shr shift, 0, 1023)
                                out[o] = if (image.rbSwapped) B + (G shl 10) + (R shl 20) else R + (G shl 10) + (B shl 20)
                            }
                            else -> jxrFail("RGB samples of bit depth $bd")
                        }
                        o += slots
                    }
                }
                return
            }
            if (bd == JxrDepth.BD_1) {
                val threshold = if (shift > 0) 1 shl (shift - 1) else 1
                for (r in r0 until r1) {
                    var o = ((mbRow * 16 + r - image.extraTop) * raster.width) * slots + format.leadingPadding
                    for (c in x0 until x1) {
                        out[o] = if (y[yAt + cc(c, r)] >= threshold) 1 else 0
                        o += slots
                    }
                }
                return
            }
            // outputNChannel.
            val channels = if (format.colorFormat == JxrColor.Y_ONLY) 1 else plane.channels
            val bias = when (bd) {
                JxrDepth.BD_8 -> (128 shl shift) + bias2
                JxrDepth.BD_16 -> (((1 shl 15) shr len) shl shift) + (if (shift == 0) 0 else 1 shl (shift - 1))
                JxrDepth.BD_16S, JxrDepth.BD_16F, JxrDepth.BD_32S, JxrDepth.BD_32F -> bias2
                else -> jxrFail("samples of bit depth $bd in colour format $cfExt")
            }
            for (ch in 0 until channels) {
                val src = plane.buf[ch]
                val at = plane.a0(ch)
                for (r in r0 until r1) {
                    var o = ((mbRow * 16 + r - image.extraTop) * raster.width) * slots + format.leadingPadding + ch
                    for (c in x0 until x1) {
                        val p = (src[at + cc(c, r)] + bias) shr shift
                        out[o] = when (bd) {
                            JxrDepth.BD_8 -> clip(p, 0, 255)
                            JxrDepth.BD_16 -> clip(p shl len, 0, 65535)
                            JxrDepth.BD_16S -> clip(p shl len, -32768, 32767)
                            JxrDepth.BD_16F -> backwardHalf(p)
                            JxrDepth.BD_32S -> p shl len
                            else -> pixel2float(p, expBias, len)
                        }
                        o += slots
                    }
                }
            }
        }

        /** outputMBRowAlpha. */
        private fun alphaRow(mbRow: Int, r0: Int, r1: Int) {
            val a = alpha!!
            if (format.colorFormat != JxrColor.RGB && format.colorFormat != JxrColor.CMYK) jxrFail("interleaved alpha in colour format ${format.colorFormat}")
            val src = a.buf[0]
            val at = a.a0(0)
            val out = raster.samples
            val slots = raster.slots
            val pos = format.leadingPadding + if (format.colorFormat == JxrColor.CMYK) 4 else 3
            val bd = format.bitDepth
            val half = if (shift == 0) 0 else 1 shl (shift - 1)
            val bias = when (bd) {
                JxrDepth.BD_8 -> (1 shl (shift + 7)) + half
                JxrDepth.BD_16 -> (1 shl (shift + 15)) + half
                JxrDepth.BD_16S, JxrDepth.BD_16F, JxrDepth.BD_32S, JxrDepth.BD_32F -> half
                else -> jxrFail("alpha of bit depth $bd")
            }
            for (r in r0 until r1) {
                var o = ((mbRow * 16 + r - image.extraTop) * raster.width) * slots + pos
                for (c in image.extraLeft until image.extraLeft + raster.width) {
                    val p = (src[at + cc(c, r)] + bias) shr shift
                    out[o] = when (bd) {
                        JxrDepth.BD_8 -> clip(p, 0, 255)
                        JxrDepth.BD_16 -> clip(p shl len, 0, 65535)
                        JxrDepth.BD_16S -> clip(p shl len, -32768, 32767)
                        JxrDepth.BD_16F -> backwardHalf(p)
                        JxrDepth.BD_32S -> p shl len
                        else -> pixel2float(p, expBias, len)
                    }
                    o += slots
                }
            }
        }

        /**
         * decodeThumbnail, outputNChannelThumbnail and decodeThumbnailAlpha: every [scale]th sample of
         * macroblock row [row] - 1, scaled by the gain the skipped transform stages leave (cMul).
         */
        fun thumbnail(row: Int) {
            val mbRow = row - 1
            val t = scale
            if ((mbRow * 16) % t != 0) return
            val cHeight = minOf(image.height - mbRow * 16, 16)
            val cWidth = image.width
            val overlap = image.overlap
            val mul = if (t >= 16) (if (overlap == 0) 16 else if (overlap == 1) 23 else 34) else if (t >= 4) (if (overlap == 0) 64 else 93) else 258
            val shiftY = 8 + shift
            val shiftUV = shiftY - if (plane.scaled && t >= 16) (if (plane.cf == JxrColor.YUV_420 || plane.cf == JxrColor.YUV_422) 2 else 1) else 0
            val out = raster.samples
            val slots = raster.slots
            val y = plane.buf[0]
            val yAt = plane.a0(0)
            var u: IntArray? = null
            var v: IntArray? = null
            var uAt = 0
            var vAt = 0
            if ((plane.cf == JxrColor.YUV_422 || plane.cf == JxrColor.YUV_420) && cfExt != JxrColor.Y_ONLY) {
                val dU = resU ?: jxrFail("chroma of colour format ${plane.cf} in format ${format.colorFormat}")
                val dV = resV!!
                val su = plane.buf[1]
                val sv = plane.buf[2]
                val s0 = plane.a0(1)
                for (r in 0 until 16 step t) {
                    var c = 0
                    while (c < cWidth) {
                        val i1 = if (plane.cf == JxrColor.YUV_422) ((c shr 4) shl 7) + IDX_CC[(r shl 4) + ((c shr 1) and 7)]
                        else ((c shr 4) shl 6) + IDX_CC_420[((r shr 1) shl 3) + ((c shr 1) and 7)]
                        val i2 = cc(c, r)
                        dU[i2] = su[s0 + i1]
                        dV[i2] = sv[s0 + i1]
                        c += t
                    }
                }
                if (t == 4) {
                    var i3 = 0
                    if (plane.cf == JxrColor.YUV_420) {
                        var c = 0
                        while (c < cWidth) {
                            val i1 = cc(c, 0)
                            val i2 = cc(c, 4)
                            i3 = cc(c, 8)
                            dU[i2] = (dU[i1] + dU[i3] + 1) shr 1
                            dV[i2] = (dV[i1] + dV[i3] + 1) shr 1
                            val i4 = cc(c, 12)
                            dU[i4] = dU[i3]
                            dV[i4] = dV[i3]
                            c += 8
                        }
                    }
                    // jxrlib counts this loop in an unsigned size, which an image narrower than 8 wraps round.
                    if (cWidth < 8) jxrFail("a quarter thumbnail of subsampled chroma narrower than 8")
                    for (r in 0 until 16 step 4) {
                        var c = 0
                        while (c < cWidth - 8) {
                            val i1 = cc(c, r)
                            val i2 = ((c shr 4) shl 8) + IDX_CC[(r shl 4) + ((c + 4) and 15)]
                            i3 = ((c shr 4) shl 8) + IDX_CC[(r shl 4) + ((c + 8) and 15)]
                            dU[i2] = (dU[i1] + dU[i3] + 1) shr 1
                            dV[i2] = (dV[i1] + dV[i3] + 1) shr 1
                            c += 8
                        }
                        val i2 = ((c shr 4) shl 8) + IDX_CC[(r shl 4) + ((c + 4) and 15)]
                        dU[i2] = dU[i3]
                        dV[i2] = dV[i3]
                    }
                }
                u = dU
                v = dV
            } else if (plane.channels >= 3) {
                u = plane.buf[1]
                v = plane.buf[2]
                uAt = plane.a0(1)
                vAt = plane.a0(2)
            }
            val bd = format.bitDepth
            fun at(r: Int, c: Int) = ((((mbRow * 16 + r) / t) * raster.width) + c / t) * slots + format.leadingPadding
            if (cfExt == JxrColor.RGB) {
                val uu = u ?: jxrFail("RGB from one channel")
                val vv = v!!
                val iB = if (format.rgb) 2 else 0
                val iR = 2 - iB
                val offset = when (bd) {
                    JxrDepth.BD_8 -> (128 shl shiftY) / mul
                    JxrDepth.BD_16 -> (((1 shl 15) shr len) shl shiftY) / mul
                    JxrDepth.BD_5 -> (16 shl shiftY) / mul
                    JxrDepth.BD_565 -> (32 shl shiftY) / mul
                    JxrDepth.BD_10 -> (512 shl shiftY) / mul
                    else -> 0
                }
                for (r in 0 until cHeight step t) {
                    var c = 0
                    while (c < cWidth) {
                        val i = cc(c, r)
                        var g = ((y[yAt + i] + offset) * mul) shr shiftY
                        var rr = (-(uu[uAt + i] * mul)) shr shiftUV
                        var b = (vv[vAt + i] * mul) shr shiftUV
                        g -= rr shr 1
                        rr -= ((b + 1) shr 1) - g
                        b += rr
                        val o = at(r, c)
                        when (bd) {
                            JxrDepth.BD_8 -> {
                                out[o + iB] = clip(b, 0, 255)
                                out[o + 1] = clip(g, 0, 255)
                                out[o + iR] = clip(rr, 0, 255)
                            }
                            JxrDepth.BD_16 -> {
                                out[o] = clip(rr shl len, 0, 65535)
                                out[o + 1] = clip(g shl len, 0, 65535)
                                out[o + 2] = clip(b shl len, 0, 65535)
                            }
                            JxrDepth.BD_16S -> {
                                out[o] = clip(rr shl len, -32768, 32767)
                                out[o + 1] = clip(g shl len, -32768, 32767)
                                out[o + 2] = clip(b shl len, -32768, 32767)
                            }
                            JxrDepth.BD_16F -> {
                                out[o] = backwardHalf(rr)
                                out[o + 1] = backwardHalf(g)
                                out[o + 2] = backwardHalf(b)
                            }
                            JxrDepth.BD_32S -> {
                                out[o] = rr shl len
                                out[o + 1] = g shl len
                                out[o + 2] = b shl len
                            }
                            JxrDepth.BD_32F -> {
                                out[o] = pixel2float(rr, expBias, len)
                                out[o + 1] = pixel2float(g, expBias, len)
                                out[o + 2] = pixel2float(b, expBias, len)
                            }
                            // The thumbnails pack red low whatever the codestream's red-blue flag says.
                            JxrDepth.BD_5 -> out[o] = clip(rr, 0, 31) + (clip(g, 0, 31) shl 5) + (clip(b, 0, 31) shl 10)
                            JxrDepth.BD_565 -> out[o] = clip(rr / 2, 0, 31) + (clip(g, 0, 63) shl 5) + (clip(b / 2, 0, 31) shl 11)
                            JxrDepth.BD_10 -> out[o] = clip(rr, 0, 1023) + (clip(g, 0, 1023) shl 10) + (clip(b, 0, 1023) shl 20)
                            else -> jxrFail("RGB samples of bit depth $bd")
                        }
                        c += t
                    }
                }
            } else if (bd == JxrDepth.BD_1) {
                for (r in 0 until cHeight step t) {
                    var c = 0
                    while (c < cWidth) {
                        out[at(r, c)] = if (y[yAt + cc(c, r)] > 0) 1 else 0
                        c += t
                    }
                }
            } else {
                val channels = minOf(plane.channels, slots - format.leadingPadding)
                val offset = when (bd) {
                    JxrDepth.BD_8 -> (128 shl shiftY) / mul
                    JxrDepth.BD_16 -> (32768 shl shiftY) / mul
                    JxrDepth.BD_16S, JxrDepth.BD_16F, JxrDepth.BD_32S, JxrDepth.BD_32F -> 0
                    else -> jxrFail("samples of bit depth $bd in colour format $cfExt")
                }
                for (ch in 0 until channels) {
                    val src = plane.buf[ch]
                    val a0 = plane.a0(ch)
                    for (r in 0 until cHeight step t) {
                        var c = 0
                        while (c < cWidth) {
                            val p = ((src[a0 + cc(c, r)] + offset) * mul) shr shiftY
                            out[at(r, c) + ch] = when (bd) {
                                JxrDepth.BD_8 -> clip(p, 0, 255)
                                JxrDepth.BD_16 -> clip(p shl len, 0, 65535)
                                JxrDepth.BD_16S -> clip(p shl len, -32768, 32767)
                                JxrDepth.BD_16F -> backwardHalf(p)
                                JxrDepth.BD_32S -> p shl len
                                else -> pixel2float(p, expBias, len)
                            }
                            c += t
                        }
                    }
                }
            }
            val a = alpha
            if (format.alpha && a != null) {
                if (format.colorFormat != JxrColor.RGB && format.colorFormat != JxrColor.CMYK) jxrFail("interleaved alpha in colour format ${format.colorFormat}")
                val src = a.buf[0]
                val a0 = a.a0(0)
                val pos = 3
                val aLen = a.header.lenMantissaOrShift
                val aBias = a.header.expBias
                val offset = when (bd) {
                    JxrDepth.BD_8 -> (128 shl shiftY) / mul
                    JxrDepth.BD_16 -> (32768 shl shiftY) / mul
                    JxrDepth.BD_16S, JxrDepth.BD_16F, JxrDepth.BD_32S, JxrDepth.BD_32F -> 0
                    else -> jxrFail("alpha of bit depth $bd")
                }
                for (r in 0 until cHeight step t) {
                    var c = 0
                    while (c < cWidth) {
                        val p = ((src[a0 + cc(c, r)] + offset) * mul) shr shiftY
                        out[at(r, c) + pos] = when (bd) {
                            JxrDepth.BD_8 -> clip(p, 0, 255)
                            JxrDepth.BD_16 -> clip(p shl aLen, 0, 65535)
                            JxrDepth.BD_16S -> clip(p shl aLen, -32768, 32767)
                            JxrDepth.BD_16F -> backwardHalf(p)
                            JxrDepth.BD_32S -> p shl aLen
                            else -> pixel2float(p, aBias, aLen)
                        }
                        c += t
                    }
                }
            }
        }

        /** fixupThumb: a thumbnail of a gray codestream in an RGB format copies the red slot to the others, as jxrlib does. */
        fun grayToRgbThumbnail() {
            if (format.bitDepth == JxrDepth.BD_5 || format.bitDepth == JxrDepth.BD_10 || format.bitDepth == JxrDepth.BD_565) return
            val out = raster.samples
            val iB = if (format.rgb) 2 else 0
            val iR = 2 - iB
            var o = 0
            repeat(raster.width * raster.height) {
                out[o + 1] = out[o + iR]
                out[o + iB] = out[o + 1]
                o += raster.slots
            }
        }

        /** fixup_Y_ONLY_to_Others: a gray codestream written to an RGB format fills all three. */
        fun grayToRgb() {
            val out = raster.samples
            val slots = raster.slots
            if (format.bitDepth == JxrDepth.BD_5 || format.bitDepth == JxrDepth.BD_10 || format.bitDepth == JxrDepth.BD_565) return
            var o = 0
            repeat(raster.width * raster.height) {
                out[o + 1] = out[o]
                out[o + 2] = out[o]
                o += slots
            }
        }
    }
}
