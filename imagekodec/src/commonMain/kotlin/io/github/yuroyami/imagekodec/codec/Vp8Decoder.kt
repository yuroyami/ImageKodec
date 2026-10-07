/*
 * The lossy WebP codec: a Kotlin port of libwebp's VP8 key-frame decoder
 * (src/dec/vp8_dec.c, tree_dec.c, quant_dec.c, frame_dec.c, src/dsp/dec.c,
 * src/utils/bit_reader*) and of the fancy upsampling and YUV to RGB conversion
 * dwebp writes its output with (src/dsp/upsampling.c, yuv.h, src/dec/io_dec.c),
 * at libwebp v1.3.2.
 *
 * Copyright 2010 Google Inc. All Rights Reserved.
 *
 * Use of this source code is governed by a BSD-style license that can be found
 * in the COPYING file in the root of the libwebp source tree, reproduced in this
 * repository's NOTICE. Altered for Kotlin: whole-frame planes in place of
 * libwebp's row cache, with the same order of reconstruction and filtering.
 */
package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.UnsupportedImageException

/**
 * VP8 key frames, the lossy WebP codec (RFC 6386), decoded as libwebp decodes them, so the
 * pixels match dwebp's exactly (#11).
 *
 * A frame decodes into whole luma and chroma planes: every macroblock is predicted from the
 * unfiltered pixels of its neighbours and its residual added, then the loop filter runs over the
 * frame in macroblock order. libwebp does the same work a few rows at a time and keeps the
 * unfiltered row above for prediction, which is the same order.
 */
internal object Vp8Decoder {

    /** A decoded frame: [width] by [height], luma and half-size chroma planes padded to whole macroblocks. */
    class Yuv(
        val width: Int,
        val height: Int,
        val y: ByteArray,
        val u: ByteArray,
        val v: ByteArray,
        val yStride: Int,
        val uvStride: Int,
    )

    private fun err(msg: String): Nothing = throw ImageDecodeException("WebP lossy: $msg")

    // --- constants (src/dec/common_dec.h, vp8i_dec.h, dsp.h) ------------------------------------

    private const val B_DC_PRED = 0
    private const val B_TM_PRED = 1
    private const val B_VE_PRED = 2
    private const val B_HE_PRED = 3
    private const val B_RD_PRED = 4
    private const val B_VR_PRED = 5
    private const val B_LD_PRED = 6
    private const val B_VL_PRED = 7
    private const val B_HD_PRED = 8
    private const val B_HU_PRED = 9
    private const val DC_PRED = B_DC_PRED
    private const val TM_PRED = B_TM_PRED
    private const val V_PRED = B_VE_PRED
    private const val H_PRED = B_HE_PRED
    private const val B_DC_PRED_NOTOP = 4
    private const val B_DC_PRED_NOLEFT = 5
    private const val B_DC_PRED_NOTOPLEFT = 6

    private const val BPS = 32
    private const val YUV_SIZE = BPS * 17 + BPS * 9
    private const val Y_OFF = BPS * 1 + 8
    private const val U_OFF = Y_OFF + BPS * 16 + BPS
    private const val V_OFF = U_OFF + 16

    private val ZIGZAG = intArrayOf(0, 1, 4, 8, 5, 2, 3, 6, 9, 12, 13, 10, 7, 11, 14, 15)

    /** The band of each coefficient position, with a sentinel for the position after the last. */
    private val BANDS = intArrayOf(0, 1, 2, 3, 6, 4, 5, 6, 6, 6, 6, 6, 6, 6, 6, 7, 0)

    private val CAT3 = intArrayOf(173, 148, 140)
    private val CAT4 = intArrayOf(176, 155, 140, 135)
    private val CAT5 = intArrayOf(180, 157, 141, 134, 130)
    private val CAT6 = intArrayOf(254, 254, 243, 230, 196, 177, 153, 140, 133, 130, 129)
    private val CAT3456 = arrayOf(CAT3, CAT4, CAT5, CAT6)

    /** Offset of luma sub-block n in the work buffer. */
    private val SCAN = IntArray(16) { n -> (n and 3) * 4 + (n shr 2) * 4 * BPS }

    // --- boolean decoder (src/utils/bit_reader*) --------------------------------------------------

    /**
     * libwebp's boolean decoder, loading a byte at a time. Past the end of its data it reads one
     * byte of zeros and sets [eof], as libwebp does; a frame whose macroblocks need more is refused.
     */
    private class BoolReader(private val data: ByteArray, start: Int, size: Int) {
        private var pos = start
        private val end = start + size
        private var value = 0
        private var range = 255 - 1
        private var bits = -8
        var eof = false
            private set

        init {
            loadNewBytes()
        }

        private fun loadNewBytes() {
            if (pos < end) {
                value = (data[pos++].toInt() and 0xFF) or (value shl 8)
                bits += 8
            } else if (!eof) {
                value = value shl 8
                bits += 8
                eof = true
            } else {
                bits = 0
            }
        }

        fun getBit(prob: Int): Int {
            var range = this.range
            if (bits < 0) loadNewBytes()
            val pos = bits
            val split = (range * prob) ushr 8
            val value = this.value ushr pos
            val bit: Int
            if (value > split) {
                range -= split
                this.value -= (split + 1) shl pos
                bit = 1
            } else {
                range = split + 1
                bit = 0
            }
            val shift = 7 xor (31 - range.countLeadingZeroBits())
            range = range shl shift
            bits -= shift
            this.range = range - 1
            return bit
        }

        /** The sign of a coefficient of magnitude [v], at probability one half. */
        fun getSigned(v: Int): Int {
            if (bits < 0) loadNewBytes()
            val pos = bits
            val split = range ushr 1
            val value = this.value ushr pos
            val mask = (split - value) shr 31
            bits -= 1
            range += mask
            range = range or 1
            this.value -= ((split + 1) and mask) shl pos
            return (v xor mask) - mask
        }

        fun get(): Boolean = getBit(0x80) == 1

        fun value(bits: Int): Int {
            var v = 0
            for (b in bits - 1 downTo 0) v = v or (getBit(0x80) shl b)
            return v
        }

        fun signedValue(bits: Int): Int {
            val value = value(bits)
            return if (get()) -value else value
        }
    }

    // --- headers (src/dec/vp8_dec.c, quant_dec.c, tree_dec.c) -----------------------------------

    private class Headers(val width: Int, val height: Int) {
        val mbW = (width + 15) shr 4
        val mbH = (height + 15) shr 4
        var useSegment = false
        var updateMap = false
        var absoluteDelta = true
        val quantizer = IntArray(4)
        val filterStrength = IntArray(4)
        val segmentProbs = IntArray(3) { 255 }
        var simple = false
        var level = 0
        var sharpness = 0
        var useLfDelta = false
        val refLfDelta = IntArray(4)
        val modeLfDelta = IntArray(4)
        var filterType = 0
        lateinit var parts: Array<BoolReader>
        /** Per segment: y1 DC and AC, y2 DC and AC, uv DC and AC steps. */
        val dq = Array(4) { IntArray(6) }
        val proba = IntArray(4 * 8 * 3 * 11)
        var useSkipProba = false
        var skipProb = 0
    }

    private fun clip(v: Int, max: Int): Int = if (v < 0) 0 else if (v > max) max else v

    /**
     * Decode the VP8 key frame at [offset], [length] bytes long, as the payload of a WebP `VP8 `
     * chunk holds it.
     */
    fun decode(data: ByteArray, offset: Int, length: Int): Yuv {
        if (length < 10) err("frame header is truncated")
        fun u8(i: Int) = data[offset + i].toInt() and 0xFF
        val tag = u8(0) or (u8(1) shl 8) or (u8(2) shl 16)
        val keyFrame = tag and 1 == 0
        val profile = (tag shr 1) and 7
        val show = (tag shr 4) and 1
        val partitionLength = tag ushr 5
        if (profile > 3) err("profile $profile is not one of 0 to 3")
        if (show == 0) throw UnsupportedImageException("WebP lossy: an invisible frame cannot be shown")
        if (!keyFrame) throw UnsupportedImageException("WebP lossy: an inter frame is not a key frame")
        if (u8(3) != 0x9D || u8(4) != 0x01 || u8(5) != 0x2A) err("bad start code")
        val width = (u8(6) or (u8(7) shl 8)) and 0x3FFF
        val height = (u8(8) or (u8(9) shl 8)) and 0x3FFF
        if (width == 0 || height == 0) err("a ${width}x$height frame has no pixels")
        var p = offset + 10
        val end = offset + length
        if (partitionLength > end - p) err("first partition of $partitionLength bytes runs past the frame")
        val h = Headers(width, height)
        // Every macroblock takes more than three bits of the first partition for its modes, so a
        // frame size the partition cannot hold is refused before its planes are allocated.
        if (h.mbW.toLong() * h.mbH > (partitionLength + 4L) * 3) {
            err("${h.mbW * h.mbH} macroblocks cannot come from a first partition of $partitionLength bytes")
        }
        val br = BoolReader(data, p, partitionLength)
        p += partitionLength
        br.get()  // colour space
        br.get()  // clamping type: libwebp always clamps
        parseSegmentHeader(br, h)
        parseFilterHeader(br, h)
        parsePartitions(br, h, data, p, end)
        parseQuant(br, h)
        br.get()  // update_proba is ignored for a key frame
        parseProba(br, h)
        return decodeFrame(br, h)
    }

    private fun parseSegmentHeader(br: BoolReader, h: Headers) {
        h.useSegment = br.get()
        if (h.useSegment) {
            h.updateMap = br.get()
            if (br.get()) {
                h.absoluteDelta = br.get()
                for (s in 0 until 4) h.quantizer[s] = if (br.get()) br.signedValue(7) else 0
                for (s in 0 until 4) h.filterStrength[s] = if (br.get()) br.signedValue(6) else 0
            }
            if (h.updateMap) for (s in 0 until 3) h.segmentProbs[s] = if (br.get()) br.value(8) else 255
        }
        if (br.eof) err("cannot parse the segment header")
    }

    private fun parseFilterHeader(br: BoolReader, h: Headers) {
        h.simple = br.get()
        h.level = br.value(6)
        h.sharpness = br.value(3)
        h.useLfDelta = br.get()
        if (h.useLfDelta && br.get()) {
            for (i in 0 until 4) if (br.get()) h.refLfDelta[i] = br.signedValue(6)
            for (i in 0 until 4) if (br.get()) h.modeLfDelta[i] = br.signedValue(6)
        }
        h.filterType = if (h.level == 0) 0 else if (h.simple) 1 else 2
        if (br.eof) err("cannot parse the filter header")
    }

    private fun parsePartitions(br: BoolReader, h: Headers, data: ByteArray, start: Int, end: Int) {
        val lastPart = (1 shl br.value(2)) - 1
        val size = end - start
        if (size < 3 * lastPart) err("the partition sizes run past the frame")
        var sz = start
        var partStart = start + lastPart * 3
        var sizeLeft = size - lastPart * 3
        h.parts = Array(lastPart + 1) { part ->
            if (part < lastPart) {
                var psize = (data[sz].toInt() and 0xFF) or ((data[sz + 1].toInt() and 0xFF) shl 8) or
                    ((data[sz + 2].toInt() and 0xFF) shl 16)
                if (psize > sizeLeft) psize = sizeLeft
                val reader = BoolReader(data, partStart, psize)
                partStart += psize
                sizeLeft -= psize
                sz += 3
                reader
            } else {
                BoolReader(data, partStart, sizeLeft)
            }
        }
        if (partStart >= end) err("the last token partition is empty")
    }

    private fun parseQuant(br: BoolReader, h: Headers) {
        val baseQ0 = br.value(7)
        val dqy1Dc = if (br.get()) br.signedValue(4) else 0
        val dqy2Dc = if (br.get()) br.signedValue(4) else 0
        val dqy2Ac = if (br.get()) br.signedValue(4) else 0
        val dquvDc = if (br.get()) br.signedValue(4) else 0
        val dquvAc = if (br.get()) br.signedValue(4) else 0
        val dcTable = Vp8Tables.DC_TABLE
        val acTable = Vp8Tables.AC_TABLE
        for (i in 0 until 4) {
            val q: Int
            if (h.useSegment) {
                q = h.quantizer[i] + if (!h.absoluteDelta) baseQ0 else 0
            } else if (i > 0) {
                h.dq[0].copyInto(h.dq[i])
                continue
            } else {
                q = baseQ0
            }
            val m = h.dq[i]
            m[0] = dcTable[clip(q + dqy1Dc, 127)]
            m[1] = acTable[clip(q, 127)]
            m[2] = dcTable[clip(q + dqy2Dc, 127)] * 2
            m[3] = maxOf(8, (acTable[clip(q + dqy2Ac, 127)] * 101581) shr 16)
            m[4] = dcTable[clip(q + dquvDc, 117)]
            m[5] = acTable[clip(q + dquvAc, 127)]
        }
    }

    private fun parseProba(br: BoolReader, h: Headers) {
        val update = Vp8Tables.COEFFS_UPDATE_PROBA
        val defaults = Vp8Tables.COEFFS_PROBA_0
        for (i in h.proba.indices) h.proba[i] = if (br.getBit(update[i]) == 1) br.value(8) else defaults[i]
        h.useSkipProba = br.get()
        if (h.useSkipProba) h.skipProb = br.value(8)
    }

    // --- macroblock parsing (tree_dec.c ParseIntraMode, vp8_dec.c ParseResiduals) ---------------

    /** Index of the 11 node probabilities of [type], [band] and [ctx] in [Headers.proba]. */
    private fun probaAt(type: Int, band: Int, ctx: Int): Int = ((type * 8 + band) * 3 + ctx) * 11

    private fun getLargeValue(br: BoolReader, proba: IntArray, p: Int): Int {
        var v: Int
        if (br.getBit(proba[p + 3]) == 0) {
            v = if (br.getBit(proba[p + 4]) == 0) 2 else 3 + br.getBit(proba[p + 5])
        } else if (br.getBit(proba[p + 6]) == 0) {
            if (br.getBit(proba[p + 7]) == 0) {
                v = 5 + br.getBit(159)
            } else {
                v = 7 + 2 * br.getBit(165)
                v += br.getBit(145)
            }
        } else {
            val bit1 = br.getBit(proba[p + 8])
            val bit0 = br.getBit(proba[p + 9 + bit1])
            val cat = 2 * bit1 + bit0
            v = 0
            for (prob in CAT3456[cat]) v += v + br.getBit(prob)
            v += 3 + (8 shl cat)
        }
        return v
    }

    /**
     * libwebp's GetCoeffs: the tokens of one block from position [first], into [out] at [at] in
     * zigzag order, each times its step. Returns the position after the last non-zero token. The
     * coefficients are int16 in libwebp, so they wrap as there.
     */
    private fun getCoeffs(
        br: BoolReader, proba: IntArray, type: Int, ctx: Int, dc: Int, ac: Int, first: Int, out: IntArray, at: Int,
    ): Int {
        var n = first
        var p = probaAt(type, BANDS[n], ctx)
        while (n < 16) {
            if (br.getBit(proba[p]) == 0) return n
            while (br.getBit(proba[p + 1]) == 0) {
                p = probaAt(type, BANDS[++n], 0)
                if (n == 16) return 16
            }
            val v: Int
            if (br.getBit(proba[p + 2]) == 0) {
                v = 1
                p = probaAt(type, BANDS[n + 1], 1)
            } else {
                v = getLargeValue(br, proba, p)
                p = probaAt(type, BANDS[n + 1], 2)
            }
            out[at + ZIGZAG[n]] = (br.getSigned(v) * (if (n > 0) ac else dc)).toShort().toInt()
            n++
        }
        return 16
    }

    private fun nzCodeBits(nzCoeffs: Int, nz: Int, dcNz: Int): Int =
        (nzCoeffs shl 2) or if (nz > 3) 3 else if (nz > 1) 2 else dcNz

    /** The state that carries from one macroblock to the next, and the decoded one's own. */
    private class Mb(mbW: Int) {
        val intraTop = IntArray(4 * mbW)
        val intraLeft = IntArray(4)
        val nzTop = IntArray(mbW)
        val nzDcTop = IntArray(mbW)
        var nzLeft = 0
        var nzDcLeft = 0
        // The macroblocks of the current row: modes are read for the whole row first, as libwebp does.
        val segment = IntArray(mbW)
        val skip = IntArray(mbW)
        val isI4x4 = BooleanArray(mbW)
        val imodes = Array(mbW) { IntArray(16) }
        val uvMode = IntArray(mbW)
        // The macroblock being reconstructed.
        val coeffs = IntArray(384)
        var nonZeroY = 0
        var nonZeroUv = 0
    }

    private fun parseIntraModeRow(br: BoolReader, h: Headers, mb: Mb) {
        for (mbX in 0 until h.mbW) {
            mb.segment[mbX] = if (h.updateMap) {
                if (br.getBit(h.segmentProbs[0]) == 0) br.getBit(h.segmentProbs[1]) else br.getBit(h.segmentProbs[2]) + 2
            } else {
                0
            }
            mb.skip[mbX] = if (h.useSkipProba) br.getBit(h.skipProb) else 0
            val isI4x4 = br.getBit(145) == 0
            mb.isI4x4[mbX] = isI4x4
            val top = 4 * mbX
            val modes = mb.imodes[mbX]
            if (!isI4x4) {
                val ymode = if (br.getBit(156) == 1) {
                    if (br.getBit(128) == 1) TM_PRED else H_PRED
                } else {
                    if (br.getBit(163) == 1) V_PRED else DC_PRED
                }
                modes[0] = ymode
                mb.intraTop.fill(ymode, top, top + 4)
                mb.intraLeft.fill(ymode)
            } else {
                val bmodes = Vp8Tables.BMODES_PROBA
                for (y in 0 until 4) {
                    var ymode = mb.intraLeft[y]
                    for (x in 0 until 4) {
                        val p = (mb.intraTop[top + x] * 10 + ymode) * 9
                        ymode = when {
                            br.getBit(bmodes[p]) == 0 -> B_DC_PRED
                            br.getBit(bmodes[p + 1]) == 0 -> B_TM_PRED
                            br.getBit(bmodes[p + 2]) == 0 -> B_VE_PRED
                            br.getBit(bmodes[p + 3]) == 0 ->
                                if (br.getBit(bmodes[p + 4]) == 0) B_HE_PRED
                                else if (br.getBit(bmodes[p + 5]) == 0) B_RD_PRED else B_VR_PRED
                            br.getBit(bmodes[p + 6]) == 0 -> B_LD_PRED
                            br.getBit(bmodes[p + 7]) == 0 -> B_VL_PRED
                            br.getBit(bmodes[p + 8]) == 0 -> B_HD_PRED
                            else -> B_HU_PRED
                        }
                        mb.intraTop[top + x] = ymode
                    }
                    mb.intraTop.copyInto(modes, y * 4, top, top + 4)
                    mb.intraLeft[y] = ymode
                }
            }
            mb.uvMode[mbX] = when {
                br.getBit(142) == 0 -> DC_PRED
                br.getBit(114) == 0 -> V_PRED
                br.getBit(183) == 1 -> TM_PRED
                else -> H_PRED
            }
        }
        if (br.eof) err("the first partition ends before its macroblocks")
    }

    /** libwebp's ParseResiduals; returns whether every coefficient is zero. */
    private fun parseResiduals(br: BoolReader, h: Headers, mb: Mb, mbX: Int): Boolean {
        val proba = h.proba
        val q = h.dq[mb.segment[mbX]]
        val dst = mb.coeffs
        dst.fill(0)
        var nonZeroY = 0
        var nonZeroUv = 0
        val first: Int
        val acType: Int
        if (!mb.isI4x4[mbX]) {
            val dc = IntArray(16)
            val ctx = mb.nzDcTop[mbX] + mb.nzDcLeft
            val nz = getCoeffs(br, proba, 1, ctx, q[2], q[3], 0, dc, 0)
            val nzDc = if (nz > 0) 1 else 0
            mb.nzDcTop[mbX] = nzDc
            mb.nzDcLeft = nzDc
            if (nz > 1) {
                transformWht(dc, dst)
            } else {
                val dc0 = ((dc[0] + 3) shr 3).toShort().toInt()
                for (i in 0 until 16 * 16 step 16) dst[i] = dc0
            }
            first = 1
            acType = 0
        } else {
            first = 0
            acType = 3
        }

        var tnz = mb.nzTop[mbX] and 0x0F
        var lnz = mb.nzLeft and 0x0F
        var at = 0
        for (y in 0 until 4) {
            var l = lnz and 1
            var nzCoeffs = 0
            for (x in 0 until 4) {
                val ctx = l + (tnz and 1)
                val nz = getCoeffs(br, proba, acType, ctx, q[0], q[1], first, dst, at)
                l = if (nz > first) 1 else 0
                tnz = (tnz shr 1) or (l shl 7)
                nzCoeffs = nzCodeBits(nzCoeffs, nz, if (dst[at] != 0) 1 else 0)
                at += 16
            }
            tnz = tnz shr 4
            lnz = (lnz shr 1) or (l shl 7)
            nonZeroY = (nonZeroY shl 8) or nzCoeffs
        }
        var outTnz = tnz
        var outLnz = lnz shr 4

        for (ch in 0 until 4 step 2) {
            var nzCoeffs = 0
            tnz = mb.nzTop[mbX] shr (4 + ch)
            lnz = mb.nzLeft shr (4 + ch)
            for (y in 0 until 2) {
                var l = lnz and 1
                for (x in 0 until 2) {
                    val ctx = l + (tnz and 1)
                    val nz = getCoeffs(br, proba, 2, ctx, q[4], q[5], 0, dst, at)
                    l = if (nz > 0) 1 else 0
                    tnz = (tnz shr 1) or (l shl 3)
                    nzCoeffs = nzCodeBits(nzCoeffs, nz, if (dst[at] != 0) 1 else 0)
                    at += 16
                }
                tnz = tnz shr 2
                lnz = (lnz shr 1) or (l shl 5)
            }
            nonZeroUv = nonZeroUv or (nzCoeffs shl (4 * ch))
            outTnz = outTnz or ((tnz shl 4) shl ch)
            outLnz = outLnz or ((lnz and 0xF0) shl ch)
        }
        mb.nzTop[mbX] = outTnz
        mb.nzLeft = outLnz
        mb.nonZeroY = nonZeroY
        mb.nonZeroUv = nonZeroUv
        return (nonZeroY or nonZeroUv) == 0
    }

    // --- frame (frame_dec.c) ---------------------------------------------------------------------

    private class FilterInfo(val limit: Int, val ilevel: Int, val inner: Boolean, val hevThresh: Int)

    /** libwebp's PrecomputeFilterStrengths: [segment][is i4x4]. */
    private fun filterStrengths(h: Headers): Array<Array<FilterInfo>> = Array(4) { s ->
        var baseLevel: Int
        if (h.useSegment) {
            baseLevel = h.filterStrength[s]
            if (!h.absoluteDelta) baseLevel += h.level
        } else {
            baseLevel = h.level
        }
        Array(2) { i4x4 ->
            var level = baseLevel
            if (h.useLfDelta) {
                level += h.refLfDelta[0]
                if (i4x4 == 1) level += h.modeLfDelta[0]
            }
            level = clip(level, 63)
            if (level > 0) {
                var ilevel = level
                if (h.sharpness > 0) {
                    ilevel = if (h.sharpness > 4) ilevel shr 2 else ilevel shr 1
                    if (ilevel > 9 - h.sharpness) ilevel = 9 - h.sharpness
                }
                if (ilevel < 1) ilevel = 1
                FilterInfo(2 * level + ilevel, ilevel, i4x4 == 1, if (level >= 40) 2 else if (level >= 15) 1 else 0)
            } else {
                FilterInfo(0, 0, i4x4 == 1, 0)
            }
        }
    }

    private fun checkMode(mbX: Int, mbY: Int, mode: Int): Int {
        if (mode == B_DC_PRED) {
            return if (mbX == 0) {
                if (mbY == 0) B_DC_PRED_NOTOPLEFT else B_DC_PRED_NOLEFT
            } else {
                if (mbY == 0) B_DC_PRED_NOTOP else B_DC_PRED
            }
        }
        return mode
    }

    private fun decodeFrame(br: BoolReader, h: Headers): Yuv {
        val mbW = h.mbW
        val mbH = h.mbH
        val yStride = mbW * 16
        val uvStride = mbW * 8
        val yPlane = ByteArray(yStride * mbH * 16)
        val uPlane = ByteArray(uvStride * mbH * 8)
        val vPlane = ByteArray(uvStride * mbH * 8)
        val topY = IntArray(mbW * 16)
        val topU = IntArray(mbW * 8)
        val topV = IntArray(mbW * 8)
        val strengths = filterStrengths(h)
        val filters = if (h.filterType > 0) arrayOfNulls<FilterInfo>(mbW * mbH) else null
        val work = IntArray(YUV_SIZE)
        val mb = Mb(mbW)

        for (mbY in 0 until mbH) {
            val tokens = h.parts[mbY and (h.parts.size - 1)]
            parseIntraModeRow(br, h, mb)
            // ReconstructRow's left border, reset for each row.
            for (j in 0 until 16) work[Y_OFF + j * BPS - 1] = 129
            for (j in 0 until 8) {
                work[U_OFF + j * BPS - 1] = 129
                work[V_OFF + j * BPS - 1] = 129
            }
            if (mbY > 0) {
                work[Y_OFF - 1 - BPS] = 129
                work[U_OFF - 1 - BPS] = 129
                work[V_OFF - 1 - BPS] = 129
            } else {
                work.fill(127, Y_OFF - BPS - 1, Y_OFF - BPS - 1 + 16 + 4 + 1)
                work.fill(127, U_OFF - BPS - 1, U_OFF - BPS - 1 + 8 + 1)
                work.fill(127, V_OFF - BPS - 1, V_OFF - BPS - 1 + 8 + 1)
            }
            for (mbX in 0 until mbW) {
                // VP8DecodeMB
                var skip = if (h.useSkipProba) mb.skip[mbX] == 1 else false
                if (!skip) {
                    skip = parseResiduals(tokens, h, mb, mbX)
                } else {
                    mb.nzLeft = 0
                    mb.nzTop[mbX] = 0
                    if (!mb.isI4x4[mbX]) {
                        mb.nzDcLeft = 0
                        mb.nzDcTop[mbX] = 0
                    }
                    mb.nonZeroY = 0
                    mb.nonZeroUv = 0
                }
                if (filters != null) {
                    val info = strengths[mb.segment[mbX]][if (mb.isI4x4[mbX]) 1 else 0]
                    filters[mbY * mbW + mbX] = if (!skip && !info.inner) FilterInfo(info.limit, info.ilevel, true, info.hevThresh) else info
                }
                if (tokens.eof) err("a token partition ends before its macroblocks")
                reconstruct(work, mb, mbX, mbY, mbW, mbH, topY, topU, topV)
                // Out to the frame planes.
                val yAt = mbY * 16 * yStride + mbX * 16
                for (j in 0 until 16) for (i in 0 until 16) yPlane[yAt + j * yStride + i] = work[Y_OFF + j * BPS + i].toByte()
                val uvAt = mbY * 8 * uvStride + mbX * 8
                for (j in 0 until 8) for (i in 0 until 8) {
                    uPlane[uvAt + j * uvStride + i] = work[U_OFF + j * BPS + i].toByte()
                    vPlane[uvAt + j * uvStride + i] = work[V_OFF + j * BPS + i].toByte()
                }
            }
            // VP8InitScanline
            mb.nzLeft = 0
            mb.nzDcLeft = 0
            mb.intraLeft.fill(B_DC_PRED)
        }

        if (filters != null) {
            for (mbY in 0 until mbH) for (mbX in 0 until mbW) {
                filterMacroblock(h.filterType, filters[mbY * mbW + mbX]!!, yPlane, uPlane, vPlane, yStride, uvStride, mbX, mbY)
            }
        }
        return Yuv(h.width, h.height, yPlane, uPlane, vPlane, yStride, uvStride)
    }

    /** One macroblock of libwebp's ReconstructRow, in its work buffer. */
    private fun reconstruct(
        work: IntArray, mb: Mb, mbX: Int, mbY: Int, mbW: Int, mbH: Int, topY: IntArray, topU: IntArray, topV: IntArray,
    ) {
        if (mbX > 0) {
            for (j in -1 until 16) work.copyInto(work, Y_OFF + j * BPS - 4, Y_OFF + j * BPS + 12, Y_OFF + j * BPS + 16)
            for (j in -1 until 8) {
                work.copyInto(work, U_OFF + j * BPS - 4, U_OFF + j * BPS + 4, U_OFF + j * BPS + 8)
                work.copyInto(work, V_OFF + j * BPS - 4, V_OFF + j * BPS + 4, V_OFF + j * BPS + 8)
            }
        }
        val coeffs = mb.coeffs
        var bits = mb.nonZeroY
        if (mbY > 0) {
            topY.copyInto(work, Y_OFF - BPS, mbX * 16, mbX * 16 + 16)
            topU.copyInto(work, U_OFF - BPS, mbX * 8, mbX * 8 + 8)
            topV.copyInto(work, V_OFF - BPS, mbX * 8, mbX * 8 + 8)
        }
        if (mb.isI4x4[mbX]) {
            val topRight = Y_OFF - BPS + 16
            if (mbY > 0) {
                if (mbX >= mbW - 1) {
                    work.fill(topY[mbX * 16 + 15], topRight, topRight + 4)
                } else {
                    topY.copyInto(work, topRight, mbX * 16 + 16, mbX * 16 + 20)
                }
            }
            // The right column of sub-blocks takes its top-right pixels from the macroblock's.
            for (r in 1..3) work.copyInto(work, topRight + r * 4 * BPS, topRight, topRight + 4)
            val modes = mb.imodes[mbX]
            for (n in 0 until 16) {
                val dst = Y_OFF + SCAN[n]
                predLuma4(modes[n], work, dst)
                doTransform(bits, coeffs, n * 16, work, dst)
                bits = bits shl 2
            }
        } else {
            predLuma16(checkMode(mbX, mbY, mb.imodes[mbX][0]), work, Y_OFF)
            if (bits != 0) {
                for (n in 0 until 16) {
                    doTransform(bits, coeffs, n * 16, work, Y_OFF + SCAN[n])
                    bits = bits shl 2
                }
            }
        }
        val bitsUv = mb.nonZeroUv
        val mode = checkMode(mbX, mbY, mb.uvMode[mbX])
        predChroma8(mode, work, U_OFF)
        predChroma8(mode, work, V_OFF)
        doUvTransform(bitsUv, coeffs, 16 * 16, work, U_OFF)
        doUvTransform(bitsUv shr 8, coeffs, 20 * 16, work, V_OFF)
        if (mbY < mbH - 1) {
            work.copyInto(topY, mbX * 16, Y_OFF + 15 * BPS, Y_OFF + 15 * BPS + 16)
            work.copyInto(topU, mbX * 8, U_OFF + 7 * BPS, U_OFF + 7 * BPS + 8)
            work.copyInto(topV, mbX * 8, V_OFF + 7 * BPS, V_OFF + 7 * BPS + 8)
        }
    }

    // --- transforms (src/dsp/dec.c) --------------------------------------------------------------

    private fun clip8(v: Int): Int = if (v and 0xFF.inv() == 0) v else if (v < 0) 0 else 255

    private fun mul1(a: Int): Int = ((a * 20091) shr 16) + a
    private fun mul2(a: Int): Int = (a * 35468) shr 16

    private fun store(dst: IntArray, at: Int, x: Int, y: Int, v: Int) {
        val i = at + x + y * BPS
        dst[i] = clip8(dst[i] + (v shr 3))
    }

    private fun transformOne(input: IntArray, from: Int, dst: IntArray, at: Int) {
        val c = IntArray(16)
        var t = 0
        for (i in 0 until 4) {
            val inp = from + i
            val a = input[inp] + input[inp + 8]
            val b = input[inp] - input[inp + 8]
            val cc = mul2(input[inp + 4]) - mul1(input[inp + 12])
            val d = mul1(input[inp + 4]) + mul2(input[inp + 12])
            c[t] = a + d
            c[t + 1] = b + cc
            c[t + 2] = b - cc
            c[t + 3] = a - d
            t += 4
        }
        var row = at
        for (i in 0 until 4) {
            val dc = c[i] + 4
            val a = dc + c[i + 8]
            val b = dc - c[i + 8]
            val cc = mul2(c[i + 4]) - mul1(c[i + 12])
            val d = mul1(c[i + 4]) + mul2(c[i + 12])
            store(dst, row, 0, 0, a + d)
            store(dst, row, 1, 0, b + cc)
            store(dst, row, 2, 0, b - cc)
            store(dst, row, 3, 0, a - d)
            row += BPS
        }
    }

    private fun transformAc3(input: IntArray, from: Int, dst: IntArray, at: Int) {
        val a = input[from] + 4
        val c4 = mul2(input[from + 4])
        val d4 = mul1(input[from + 4])
        val c1 = mul2(input[from + 1])
        val d1 = mul1(input[from + 1])
        fun store2(y: Int, dc: Int) {
            store(dst, at, 0, y, dc + d1)
            store(dst, at, 1, y, dc + c1)
            store(dst, at, 2, y, dc - c1)
            store(dst, at, 3, y, dc - d1)
        }
        store2(0, a + d4)
        store2(1, a + c4)
        store2(2, a - c4)
        store2(3, a - d4)
    }

    private fun transformDc(input: IntArray, from: Int, dst: IntArray, at: Int) {
        val dc = input[from] + 4
        for (j in 0 until 4) for (i in 0 until 4) store(dst, at, i, j, dc)
    }

    private fun doTransform(bits: Int, coeffs: IntArray, from: Int, dst: IntArray, at: Int) {
        when (bits ushr 30) {
            3 -> transformOne(coeffs, from, dst, at)
            2 -> transformAc3(coeffs, from, dst, at)
            1 -> transformDc(coeffs, from, dst, at)
        }
    }

    private fun doUvTransform(bits: Int, coeffs: IntArray, from: Int, dst: IntArray, at: Int) {
        if (bits and 0xFF == 0) return
        if (bits and 0xAA != 0) {
            transformOne(coeffs, from, dst, at)
            transformOne(coeffs, from + 16, dst, at + 4)
            transformOne(coeffs, from + 32, dst, at + 4 * BPS)
            transformOne(coeffs, from + 48, dst, at + 4 * BPS + 4)
        } else {
            if (coeffs[from] != 0) transformDc(coeffs, from, dst, at)
            if (coeffs[from + 16] != 0) transformDc(coeffs, from + 16, dst, at + 4)
            if (coeffs[from + 32] != 0) transformDc(coeffs, from + 32, dst, at + 4 * BPS)
            if (coeffs[from + 48] != 0) transformDc(coeffs, from + 48, dst, at + 4 * BPS + 4)
        }
    }

    /** The inverse Walsh-Hadamard transform of the 16 DC terms into each luma block's first coefficient. */
    private fun transformWht(input: IntArray, out: IntArray) {
        val tmp = IntArray(16)
        for (i in 0 until 4) {
            val a0 = input[i] + input[12 + i]
            val a1 = input[4 + i] + input[8 + i]
            val a2 = input[4 + i] - input[8 + i]
            val a3 = input[i] - input[12 + i]
            tmp[i] = a0 + a1
            tmp[8 + i] = a0 - a1
            tmp[4 + i] = a3 + a2
            tmp[12 + i] = a3 - a2
        }
        var o = 0
        for (i in 0 until 4) {
            val dc = tmp[i * 4] + 3
            val a0 = dc + tmp[3 + i * 4]
            val a1 = tmp[1 + i * 4] + tmp[2 + i * 4]
            val a2 = tmp[1 + i * 4] - tmp[2 + i * 4]
            val a3 = dc - tmp[3 + i * 4]
            out[o] = ((a0 + a1) shr 3).toShort().toInt()
            out[o + 16] = ((a3 + a2) shr 3).toShort().toInt()
            out[o + 32] = ((a0 - a1) shr 3).toShort().toInt()
            out[o + 48] = ((a3 - a2) shr 3).toShort().toInt()
            o += 64
        }
    }

    // --- intra prediction (src/dsp/dec.c) --------------------------------------------------------

    private fun trueMotion(d: IntArray, at: Int, size: Int) {
        val top = at - BPS
        val topLeft = d[top - 1]
        var row = at
        for (y in 0 until size) {
            val left = d[row - 1]
            for (x in 0 until size) d[row + x] = clip255(d[top + x] + left - topLeft)
            row += BPS
        }
    }

    private fun clip255(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    private fun fill(d: IntArray, at: Int, size: Int, value: Int) {
        for (j in 0 until size) d.fill(value, at + j * BPS, at + j * BPS + size)
    }

    private fun predLuma16(mode: Int, d: IntArray, at: Int) {
        when (mode) {
            0 -> {
                var dc = 16
                for (j in 0 until 16) dc += d[at - 1 + j * BPS] + d[at + j - BPS]
                fill(d, at, 16, dc shr 5)
            }
            1 -> trueMotion(d, at, 16)
            2 -> for (j in 0 until 16) d.copyInto(d, at + j * BPS, at - BPS, at - BPS + 16)
            3 -> for (j in 0 until 16) d.fill(d[at + j * BPS - 1], at + j * BPS, at + j * BPS + 16)
            4 -> {
                var dc = 8
                for (j in 0 until 16) dc += d[at - 1 + j * BPS]
                fill(d, at, 16, dc shr 4)
            }
            5 -> {
                var dc = 8
                for (i in 0 until 16) dc += d[at + i - BPS]
                fill(d, at, 16, dc shr 4)
            }
            else -> fill(d, at, 16, 0x80)
        }
    }

    private fun predChroma8(mode: Int, d: IntArray, at: Int) {
        when (mode) {
            0 -> {
                var dc = 8
                for (i in 0 until 8) dc += d[at + i - BPS] + d[at - 1 + i * BPS]
                fill(d, at, 8, dc shr 4)
            }
            1 -> trueMotion(d, at, 8)
            2 -> for (j in 0 until 8) d.copyInto(d, at + j * BPS, at - BPS, at - BPS + 8)
            3 -> for (j in 0 until 8) d.fill(d[at + j * BPS - 1], at + j * BPS, at + j * BPS + 8)
            4 -> {
                var dc = 4
                for (i in 0 until 8) dc += d[at - 1 + i * BPS]
                fill(d, at, 8, dc shr 3)
            }
            5 -> {
                var dc = 4
                for (i in 0 until 8) dc += d[at + i - BPS]
                fill(d, at, 8, dc shr 3)
            }
            else -> fill(d, at, 8, 0x80)
        }
    }

    private fun avg3(a: Int, b: Int, c: Int): Int = (a + 2 * b + c + 2) shr 2
    private fun avg2(a: Int, b: Int): Int = (a + b + 1) shr 1

    private fun predLuma4(mode: Int, d: IntArray, at: Int) {
        fun set(x: Int, y: Int, v: Int) {
            d[at + x + y * BPS] = v
        }
        val top = at - BPS
        when (mode) {
            B_DC_PRED -> {
                var dc = 4
                for (i in 0 until 4) dc += d[top + i] + d[at - 1 + i * BPS]
                fill(d, at, 4, dc shr 3)
            }
            B_TM_PRED -> trueMotion(d, at, 4)
            B_VE_PRED -> {
                val v0 = avg3(d[top - 1], d[top], d[top + 1])
                val v1 = avg3(d[top], d[top + 1], d[top + 2])
                val v2 = avg3(d[top + 1], d[top + 2], d[top + 3])
                val v3 = avg3(d[top + 2], d[top + 3], d[top + 4])
                for (i in 0 until 4) {
                    set(0, i, v0); set(1, i, v1); set(2, i, v2); set(3, i, v3)
                }
            }
            B_HE_PRED -> {
                val a = d[at - 1 - BPS]
                val b = d[at - 1]
                val c = d[at - 1 + BPS]
                val dd = d[at - 1 + 2 * BPS]
                val e = d[at - 1 + 3 * BPS]
                d.fill(avg3(a, b, c), at, at + 4)
                d.fill(avg3(b, c, dd), at + BPS, at + BPS + 4)
                d.fill(avg3(c, dd, e), at + 2 * BPS, at + 2 * BPS + 4)
                d.fill(avg3(dd, e, e), at + 3 * BPS, at + 3 * BPS + 4)
            }
            B_RD_PRED -> {
                val i = d[at - 1]; val j = d[at - 1 + BPS]; val k = d[at - 1 + 2 * BPS]; val l = d[at - 1 + 3 * BPS]
                val x = d[top - 1]; val a = d[top]; val b = d[top + 1]; val c = d[top + 2]; val dd = d[top + 3]
                set(0, 3, avg3(j, k, l))
                avg3(i, j, k).let { set(1, 3, it); set(0, 2, it) }
                avg3(x, i, j).let { set(2, 3, it); set(1, 2, it); set(0, 1, it) }
                avg3(a, x, i).let { set(3, 3, it); set(2, 2, it); set(1, 1, it); set(0, 0, it) }
                avg3(b, a, x).let { set(3, 2, it); set(2, 1, it); set(1, 0, it) }
                avg3(c, b, a).let { set(3, 1, it); set(2, 0, it) }
                set(3, 0, avg3(dd, c, b))
            }
            B_VR_PRED -> {
                val i = d[at - 1]; val j = d[at - 1 + BPS]; val k = d[at - 1 + 2 * BPS]
                val x = d[top - 1]; val a = d[top]; val b = d[top + 1]; val c = d[top + 2]; val dd = d[top + 3]
                avg2(x, a).let { set(0, 0, it); set(1, 2, it) }
                avg2(a, b).let { set(1, 0, it); set(2, 2, it) }
                avg2(b, c).let { set(2, 0, it); set(3, 2, it) }
                set(3, 0, avg2(c, dd))
                set(0, 3, avg3(k, j, i))
                set(0, 2, avg3(j, i, x))
                avg3(i, x, a).let { set(0, 1, it); set(1, 3, it) }
                avg3(x, a, b).let { set(1, 1, it); set(2, 3, it) }
                avg3(a, b, c).let { set(2, 1, it); set(3, 3, it) }
                set(3, 1, avg3(b, c, dd))
            }
            B_LD_PRED -> {
                val a = d[top]; val b = d[top + 1]; val c = d[top + 2]; val dd = d[top + 3]
                val e = d[top + 4]; val f = d[top + 5]; val g = d[top + 6]; val hh = d[top + 7]
                set(0, 0, avg3(a, b, c))
                avg3(b, c, dd).let { set(1, 0, it); set(0, 1, it) }
                avg3(c, dd, e).let { set(2, 0, it); set(1, 1, it); set(0, 2, it) }
                avg3(dd, e, f).let { set(3, 0, it); set(2, 1, it); set(1, 2, it); set(0, 3, it) }
                avg3(e, f, g).let { set(3, 1, it); set(2, 2, it); set(1, 3, it) }
                avg3(f, g, hh).let { set(3, 2, it); set(2, 3, it) }
                set(3, 3, avg3(g, hh, hh))
            }
            B_VL_PRED -> {
                val a = d[top]; val b = d[top + 1]; val c = d[top + 2]; val dd = d[top + 3]
                val e = d[top + 4]; val f = d[top + 5]; val g = d[top + 6]; val hh = d[top + 7]
                set(0, 0, avg2(a, b))
                avg2(b, c).let { set(1, 0, it); set(0, 2, it) }
                avg2(c, dd).let { set(2, 0, it); set(1, 2, it) }
                avg2(dd, e).let { set(3, 0, it); set(2, 2, it) }
                set(0, 1, avg3(a, b, c))
                avg3(b, c, dd).let { set(1, 1, it); set(0, 3, it) }
                avg3(c, dd, e).let { set(2, 1, it); set(1, 3, it) }
                avg3(dd, e, f).let { set(3, 1, it); set(2, 3, it) }
                set(3, 2, avg3(e, f, g))
                set(3, 3, avg3(f, g, hh))
            }
            B_HD_PRED -> {
                val i = d[at - 1]; val j = d[at - 1 + BPS]; val k = d[at - 1 + 2 * BPS]; val l = d[at - 1 + 3 * BPS]
                val x = d[top - 1]; val a = d[top]; val b = d[top + 1]; val c = d[top + 2]
                avg2(i, x).let { set(0, 0, it); set(2, 1, it) }
                avg2(j, i).let { set(0, 1, it); set(2, 2, it) }
                avg2(k, j).let { set(0, 2, it); set(2, 3, it) }
                set(0, 3, avg2(l, k))
                set(3, 0, avg3(a, b, c))
                set(2, 0, avg3(x, a, b))
                avg3(i, x, a).let { set(1, 0, it); set(3, 1, it) }
                avg3(j, i, x).let { set(1, 1, it); set(3, 2, it) }
                avg3(k, j, i).let { set(1, 2, it); set(3, 3, it) }
                set(1, 3, avg3(l, k, j))
            }
            else -> {  // B_HU_PRED
                val i = d[at - 1]; val j = d[at - 1 + BPS]; val k = d[at - 1 + 2 * BPS]; val l = d[at - 1 + 3 * BPS]
                set(0, 0, avg2(i, j))
                avg2(j, k).let { set(2, 0, it); set(0, 1, it) }
                avg2(k, l).let { set(2, 1, it); set(0, 2, it) }
                set(1, 0, avg3(i, j, k))
                avg3(j, k, l).let { set(3, 0, it); set(1, 1, it) }
                avg3(k, l, l).let { set(3, 1, it); set(1, 2, it) }
                set(3, 2, l); set(2, 2, l); set(0, 3, l); set(1, 3, l); set(2, 3, l); set(3, 3, l)
            }
        }
    }

    // --- loop filter (src/dsp/dec.c, frame_dec.c DoFilter) ---------------------------------------

    private fun u(p: ByteArray, i: Int): Int = p[i].toInt() and 0xFF
    private fun sclip1(v: Int): Int = if (v < -128) -128 else if (v > 127) 127 else v
    private fun sclip2(v: Int): Int = if (v < -16) -16 else if (v > 15) 15 else v
    private fun abs(v: Int): Int = if (v < 0) -v else v

    private fun doFilter2(p: ByteArray, at: Int, step: Int) {
        val p1 = u(p, at - 2 * step); val p0 = u(p, at - step); val q0 = u(p, at); val q1 = u(p, at + step)
        val a = 3 * (q0 - p0) + sclip1(p1 - q1)
        val a1 = sclip2((a + 4) shr 3)
        val a2 = sclip2((a + 3) shr 3)
        p[at - step] = clip255(p0 + a2).toByte()
        p[at] = clip255(q0 - a1).toByte()
    }

    private fun doFilter4(p: ByteArray, at: Int, step: Int) {
        val p1 = u(p, at - 2 * step); val p0 = u(p, at - step); val q0 = u(p, at); val q1 = u(p, at + step)
        val a = 3 * (q0 - p0)
        val a1 = sclip2((a + 4) shr 3)
        val a2 = sclip2((a + 3) shr 3)
        val a3 = (a1 + 1) shr 1
        p[at - 2 * step] = clip255(p1 + a3).toByte()
        p[at - step] = clip255(p0 + a2).toByte()
        p[at] = clip255(q0 - a1).toByte()
        p[at + step] = clip255(q1 - a3).toByte()
    }

    private fun doFilter6(p: ByteArray, at: Int, step: Int) {
        val p2 = u(p, at - 3 * step); val p1 = u(p, at - 2 * step); val p0 = u(p, at - step)
        val q0 = u(p, at); val q1 = u(p, at + step); val q2 = u(p, at + 2 * step)
        val a = sclip1(3 * (q0 - p0) + sclip1(p1 - q1))
        val a1 = (27 * a + 63) shr 7
        val a2 = (18 * a + 63) shr 7
        val a3 = (9 * a + 63) shr 7
        p[at - 3 * step] = clip255(p2 + a3).toByte()
        p[at - 2 * step] = clip255(p1 + a2).toByte()
        p[at - step] = clip255(p0 + a1).toByte()
        p[at] = clip255(q0 - a1).toByte()
        p[at + step] = clip255(q1 - a2).toByte()
        p[at + 2 * step] = clip255(q2 - a3).toByte()
    }

    private fun hev(p: ByteArray, at: Int, step: Int, thresh: Int): Boolean {
        val p1 = u(p, at - 2 * step); val p0 = u(p, at - step); val q0 = u(p, at); val q1 = u(p, at + step)
        return abs(p1 - p0) > thresh || abs(q1 - q0) > thresh
    }

    private fun needsFilter(p: ByteArray, at: Int, step: Int, t: Int): Boolean {
        val p1 = u(p, at - 2 * step); val p0 = u(p, at - step); val q0 = u(p, at); val q1 = u(p, at + step)
        return 4 * abs(p0 - q0) + abs(p1 - q1) <= t
    }

    private fun needsFilter2(p: ByteArray, at: Int, step: Int, t: Int, it: Int): Boolean {
        val p3 = u(p, at - 4 * step); val p2 = u(p, at - 3 * step); val p1 = u(p, at - 2 * step)
        val p0 = u(p, at - step); val q0 = u(p, at)
        val q1 = u(p, at + step); val q2 = u(p, at + 2 * step); val q3 = u(p, at + 3 * step)
        if (4 * abs(p0 - q0) + abs(p1 - q1) > t) return false
        return abs(p3 - p2) <= it && abs(p2 - p1) <= it && abs(p1 - p0) <= it &&
            abs(q3 - q2) <= it && abs(q2 - q1) <= it && abs(q1 - q0) <= it
    }

    private fun simpleFilter16(p: ByteArray, at: Int, step: Int, along: Int, thresh: Int) {
        val thresh2 = 2 * thresh + 1
        for (i in 0 until 16) {
            val q = at + i * along
            if (needsFilter(p, q, step, thresh2)) doFilter2(p, q, step)
        }
    }

    private fun filterLoop(
        p: ByteArray, at: Int, hstride: Int, vstride: Int, size: Int, thresh: Int, ithresh: Int, hevThresh: Int, outer: Boolean,
    ) {
        val thresh2 = 2 * thresh + 1
        var q = at
        for (n in 0 until size) {
            if (needsFilter2(p, q, hstride, thresh2, ithresh)) {
                if (hev(p, q, hstride, hevThresh)) doFilter2(p, q, hstride)
                else if (outer) doFilter6(p, q, hstride)
                else doFilter4(p, q, hstride)
            }
            q += vstride
        }
    }

    private fun filterMacroblock(
        type: Int, f: FilterInfo, y: ByteArray, u: ByteArray, v: ByteArray, yStride: Int, uvStride: Int, mbX: Int, mbY: Int,
    ) {
        val limit = f.limit
        if (limit == 0) return
        val yAt = mbY * 16 * yStride + mbX * 16
        if (type == 1) {
            if (mbX > 0) simpleFilter16(y, yAt, 1, yStride, limit + 4)
            if (f.inner) for (k in 1..3) simpleFilter16(y, yAt + 4 * k, 1, yStride, limit)
            if (mbY > 0) simpleFilter16(y, yAt, yStride, 1, limit + 4)
            if (f.inner) for (k in 1..3) simpleFilter16(y, yAt + 4 * k * yStride, yStride, 1, limit)
        } else {
            val uvAt = mbY * 8 * uvStride + mbX * 8
            val il = f.ilevel
            val hv = f.hevThresh
            if (mbX > 0) {
                filterLoop(y, yAt, 1, yStride, 16, limit + 4, il, hv, outer = true)
                filterLoop(u, uvAt, 1, uvStride, 8, limit + 4, il, hv, outer = true)
                filterLoop(v, uvAt, 1, uvStride, 8, limit + 4, il, hv, outer = true)
            }
            if (f.inner) {
                for (k in 1..3) filterLoop(y, yAt + 4 * k, 1, yStride, 16, limit, il, hv, outer = false)
                filterLoop(u, uvAt + 4, 1, uvStride, 8, limit, il, hv, outer = false)
                filterLoop(v, uvAt + 4, 1, uvStride, 8, limit, il, hv, outer = false)
            }
            if (mbY > 0) {
                filterLoop(y, yAt, yStride, 1, 16, limit + 4, il, hv, outer = true)
                filterLoop(u, uvAt, uvStride, 1, 8, limit + 4, il, hv, outer = true)
                filterLoop(v, uvAt, uvStride, 1, 8, limit + 4, il, hv, outer = true)
            }
            if (f.inner) {
                for (k in 1..3) filterLoop(y, yAt + 4 * k * yStride, yStride, 1, 16, limit, il, hv, outer = false)
                filterLoop(u, uvAt + 4 * uvStride, uvStride, 1, 8, limit, il, hv, outer = false)
                filterLoop(v, uvAt + 4 * uvStride, uvStride, 1, 8, limit, il, hv, outer = false)
            }
        }
    }

    // --- output: fancy upsampling and YUV to RGB (src/dsp/upsampling.c, yuv.h, io_dec.c) --------

    private fun multHi(v: Int, coeff: Int): Int = (v * coeff) shr 8

    private fun clip8Fix(v: Int): Int = if (v and (256 shl 6) - 1 == v) v shr 6 else if (v < 0) 0 else 255

    private fun yuvToArgb(y: Int, u: Int, v: Int, alpha: Int): Int {
        val r = clip8Fix(multHi(y, 19077) + multHi(v, 26149) - 14234)
        val g = clip8Fix(multHi(y, 19077) - multHi(u, 6419) - multHi(v, 13320) + 8708)
        val b = clip8Fix(multHi(y, 19077) + multHi(u, 33050) - 17685)
        return (alpha shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * The frame as ARGB, with [alpha] as its opacity or opaque without it: chroma upsampled as
     * libwebp's fancy upsampler does, which is what dwebp writes by default.
     */
    fun toArgb(f: Yuv, alpha: ByteArray?): IntArray {
        val w = f.width
        val h = f.height
        val out = IntArray(w * h)
        fun pair(topRow: Int, bottomRow: Int, topUv: Int, curUv: Int) {
            upsampleLinePair(f, topRow, bottomRow, topUv, curUv, out, alpha)
        }
        pair(0, -1, 0, 0)
        var y = 1
        while (y + 1 < h) {
            pair(y, y + 1, (y - 1) shr 1, (y + 1) shr 1)
            y += 2
        }
        if (h and 1 == 0) pair(h - 1, -1, (h - 2) shr 1, (h - 2) shr 1)
        return out
    }

    /** libwebp's UPSAMPLE_FUNC for one pair of rows; [bottomRow] is -1 for a single row. */
    private fun upsampleLinePair(f: Yuv, topRow: Int, bottomRow: Int, topUv: Int, curUv: Int, out: IntArray, alpha: ByteArray?) {
        val len = f.width
        val yp = f.y
        val us = f.u
        val vs = f.v
        val ty = topRow * f.yStride
        val by = bottomRow * f.yStride
        val tuv = topUv * f.uvStride
        val cuv = curUv * f.uvStride
        fun px(row: Int, rowAt: Int, x: Int, u: Int, v: Int) {
            val a = if (alpha == null) 0xFF else alpha[row * len + x].toInt() and 0xFF
            out[row * len + x] = yuvToArgb(yp[rowAt + x].toInt() and 0xFF, u, v, a)
        }
        val lastPixelPair = (len - 1) shr 1
        var tlU = us[tuv].toInt() and 0xFF
        var tlV = vs[tuv].toInt() and 0xFF
        var lU = us[cuv].toInt() and 0xFF
        var lV = vs[cuv].toInt() and 0xFF
        px(topRow, ty, 0, (3 * tlU + lU + 2) shr 2, (3 * tlV + lV + 2) shr 2)
        if (bottomRow >= 0) px(bottomRow, by, 0, (3 * lU + tlU + 2) shr 2, (3 * lV + tlV + 2) shr 2)
        for (x in 1..lastPixelPair) {
            val tU = us[tuv + x].toInt() and 0xFF
            val tV = vs[tuv + x].toInt() and 0xFF
            val cU = us[cuv + x].toInt() and 0xFF
            val cV = vs[cuv + x].toInt() and 0xFF
            val avgU = tlU + tU + lU + cU + 8
            val avgV = tlV + tV + lV + cV + 8
            val diag12U = (avgU + 2 * (tU + lU)) shr 3
            val diag12V = (avgV + 2 * (tV + lV)) shr 3
            val diag03U = (avgU + 2 * (tlU + cU)) shr 3
            val diag03V = (avgV + 2 * (tlV + cV)) shr 3
            px(topRow, ty, 2 * x - 1, (diag12U + tlU) shr 1, (diag12V + tlV) shr 1)
            px(topRow, ty, 2 * x, (diag03U + tU) shr 1, (diag03V + tV) shr 1)
            if (bottomRow >= 0) {
                px(bottomRow, by, 2 * x - 1, (diag03U + lU) shr 1, (diag03V + lV) shr 1)
                px(bottomRow, by, 2 * x, (diag12U + cU) shr 1, (diag12V + cV) shr 1)
            }
            tlU = tU; tlV = tV; lU = cU; lV = cV
        }
        if (len and 1 == 0) {
            px(topRow, ty, len - 1, (3 * tlU + lU + 2) shr 2, (3 * tlV + lV + 2) shr 2)
            if (bottomRow >= 0) px(bottomRow, by, len - 1, (3 * lU + tlU + 2) shr 2, (3 * lV + tlV + 2) shr 2)
        }
    }
}
