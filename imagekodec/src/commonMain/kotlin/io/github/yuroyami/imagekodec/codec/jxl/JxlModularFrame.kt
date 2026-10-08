// Altered Kotlin port of parts of libjxl 0.11.1 (lib/jxl/dec_modular.cc).
// Copyright (c) the JPEG XL Project Authors. All rights reserved.
// Use of the original source is governed by a BSD-style license; see NOTICE.

package io.github.yuroyami.imagekodec.codec.jxl

/** One plane of samples of a frame, row by row, in the float range libjxl works in: 0 to 1 for a nominal sample. */
internal class JxlPlane(val w: Int, val h: Int, val data: FloatArray = FloatArray(w * h))

/**
 * The Modular part of one frame. A Modular frame keeps its colour here, and every frame
 * keeps its extra channels (alpha, depth and so on) here. The frame's first section
 * describes the whole image and holds the channels small enough for one group; the
 * group sections fill the rest, and [finish] undoes the transforms.
 */
internal class JxlModularFrame(private val fh: JxlFrameHeader, private val image: JxlImageHeader) {
    private val dim = fh.dim
    private val full = JxlModularImage(image.bitDepth.bits)
    private var globalCode: JxlModularCode? = null
    private var wp = JxlWpHeader()

    /** The number of colour channels the frame keeps here: 0 for a VarDCT frame, 1 for gray, else 3. */
    var colorChannels = 0
        private set

    /** The frame's shared tree and code, for the other Modular streams a VarDCT frame holds. */
    val code: JxlModularCode? get() = globalCode

    // The numbers libjxl gives the Modular streams of a frame, which a tree may test.
    fun lfCoefficientsStream(group: Int): Int = 1 + group
    private fun lfGroupStream(group: Int): Int = 1 + dim.numDcGroups + group
    fun hfMetadataStream(group: Int): Int = 1 + 2 * dim.numDcGroups + group
    fun quantTableStream(table: Int): Int = 1 + 3 * dim.numDcGroups + table
    private fun passGroupStream(group: Int, pass: Int): Int =
        1 + 3 * dim.numDcGroups + NUM_QUANT_TABLES + dim.numGroups * pass + group

    /** Reads the frame's shared tree and the first Modular stream, from the frame's first section. */
    fun readGlobal(br: JxlBitReader) {
        val gray = image.color.isGray && fh.colorTransform == JxlFrameHeader.NONE
        var chans = if (gray) 1 else 3
        val extras = image.extraChannels.size
        if (br.bool()) {
            val limit = minOf(JxlTree.MAX_SIZE.toLong(), 1024 + dim.xsize.toLong() * dim.ysize * (chans + extras) / 16)
            val tree = JxlTree.read(br, limit.toInt())
            globalCode = JxlModularCode(tree, JxlCode.read(br, tree.leaves))
        }
        if (!fh.modular) chans = 0
        colorChannels = chans
        val depth = image.bitDepth
        if (depth.bits >= 32 && chans != 0 && fh.colorTransform != JxlFrameHeader.XYB) {
            if (depth.bits > 32 || !depth.floatingPoint) jxlFail("integer samples of ${depth.bits} bits")
        }
        for (c in 0 until chans) {
            if (fh.colorTransform == JxlFrameHeader.YCBCR) {
                val hs = fh.hShift(c)
                val vs = fh.vShift(c)
                full.channels.add(JxlChannel(JxlFrameDim.ceilDiv(dim.xsize, 1 shl hs), JxlFrameDim.ceilDiv(dim.ysize, 1 shl vs), hs, vs))
            } else {
                full.channels.add(JxlChannel(dim.xsize, dim.ysize))
            }
        }
        for (ec in 0 until extras) {
            val up = fh.ecUpsampling[ec]
            val shift = ceilLog2(up) - ceilLog2(fh.upsampling)
            full.channels.add(
                JxlChannel(JxlFrameDim.ceilDiv(dim.xsizeUpsampled, up), JxlFrameDim.ceilDiv(dim.ysizeUpsampled, up), shift, shift),
            )
        }
        val header = JxlModular.decode(br, full, 0, globalCode, maxChanSize = dim.groupDim, groupDim = dim.groupDim)
        if (header != null) wp = header.wp
    }

    /** Reads the channels of LF group [group] that are an eighth of the frame or smaller. */
    fun readLfGroup(br: JxlBitReader, group: Int) {
        val size = dim.groupDim * 8
        readGroup(br, (group % dim.xsizeDcGroups) * size, (group / dim.xsizeDcGroups) * size, size, 3, 1000, lfGroupStream(group))
    }

    /** Reads the part of group [group] that pass [pass] holds: the channels whose halving count is between the two shifts. */
    fun readPassGroup(br: JxlBitReader, group: Int, pass: Int, minShift: Int, maxShift: Int) {
        val size = dim.groupDim
        readGroup(br, (group % dim.xsizeGroups) * size, (group / dim.xsizeGroups) * size, size, minShift, maxShift, passGroupStream(group, pass))
    }

    private fun readGroup(br: JxlBitReader, x0: Int, y0: Int, size: Int, minShift: Int, maxShift: Int, stream: Int) {
        val channels = full.channels
        // The first section already holds every channel before the first one that is larger than a group.
        var begin = full.metaChannels
        while (begin < channels.size && channels[begin].w <= dim.groupDim && channels[begin].h <= dim.groupDim) begin++
        val part = JxlModularImage(full.bitDepth)
        val targets = ArrayList<JxlChannel>()
        val rects = ArrayList<IntArray>()
        for (c in begin until channels.size) {
            val fc = channels[c]
            val shift = minOf(fc.hshift, fc.vshift)
            if (shift > maxShift || shift < minShift) continue
            val rx = x0 shr fc.hshift
            val ry = y0 shr fc.vshift
            val rw = clampedSize(rx, size shr fc.hshift, fc.w)
            val rh = clampedSize(ry, size shr fc.vshift, fc.h)
            if (rw == 0 || rh == 0) continue
            part.channels.add(JxlChannel(rw, rh, fc.hshift, fc.vshift))
            targets.add(fc)
            rects.add(intArrayOf(rx, ry, rw, rh))
        }
        if (part.channels.isEmpty()) return
        decodeStream(br, part, stream)
        for (i in targets.indices) {
            val from = part.channels[i].data
            val to = targets[i]
            val out = to.data
            val r = rects[i]
            for (y in 0 until r[3]) from.copyInto(out, (r[1] + y) * to.w + r[0], y * r[2], (y + 1) * r[2])
        }
    }

    /**
     * Reads a whole Modular stream into [part] and undoes its transforms. The channels of
     * [part] must come back with the sizes they had, or the stream is not what the frame said.
     */
    fun decodeStream(br: JxlBitReader, part: JxlModularImage, stream: Int) {
        val sizes = IntArray(part.channels.size * 2) { val ch = part.channels[it shr 1]; if (it and 1 == 0) ch.w else ch.h }
        val header = JxlModular.decode(br, part, stream, globalCode)
        part.undoTransforms(header?.wp ?: JxlWpHeader())
        if (part.channels.size * 2 != sizes.size) jxlFail("Modular transforms change the number of channels")
        for (i in part.channels.indices) {
            val ch = part.channels[i]
            if (ch.w != sizes[2 * i] || ch.h != sizes[2 * i + 1]) jxlFail("Modular transforms change the size of a channel")
        }
    }

    /** Undoes the transforms of the whole image. Call it once every section is read. */
    fun finish() {
        full.undoTransforms(wp)
        if (full.channels.size != colorChannels + image.extraChannels.size) jxlFail("Modular transforms change the number of channels")
    }

    /**
     * The colour of a Modular frame as three planes of floats. [lfQuant] scales the three
     * channels of an XYB frame, whose samples are quantized the way a VarDCT frame's LF is.
     */
    fun colorPlanes(lfQuant: FloatArray): Array<JxlPlane> {
        val xyb = fh.colorTransform == JxlFrameHeader.XYB
        val depth = image.bitDepth
        val float = depth.floatingPoint && !xyb
        val bits = full.bitDepth
        val factor = if (bits < 32) 1.0 / ((1L shl bits) - 1) else 0.0
        val planes = arrayOfNulls<JxlPlane>(3)
        for (c in 0 until 3) {
            // An XYB frame keeps Y first; a gray frame keeps one channel for all three.
            val cIn = if (xyb) (if (c < 2) 1 - c else c) else if (colorChannels == 1) 0 else c
            val ch = full.channels[cIn]
            if (colorChannels == 1 && c > 0) {
                planes[c] = JxlPlane(ch.w, ch.h, planes[0]!!.data.copyOf())
                continue
            }
            val hs = if (fh.colorTransform == JxlFrameHeader.YCBCR) fh.hShift(c) else 0
            val vs = if (fh.colorTransform == JxlFrameHeader.YCBCR) fh.vShift(c) else 0
            if (ch.w != JxlFrameDim.ceilDiv(dim.xsize, 1 shl hs) || ch.h != JxlFrameDim.ceilDiv(dim.ysize, 1 shl vs)) {
                jxlFail("Modular channel of ${ch.w} by ${ch.h} does not fit its frame")
            }
            val plane = JxlPlane(ch.w, ch.h)
            val src = ch.data
            val out = plane.data
            when {
                xyb -> {
                    val f = lfQuant[c].toDouble()
                    if (c == 2) {
                        // B is stored as its difference from Y.
                        val y = full.channels[0].data
                        for (i in out.indices) out[i] = ((src[i] + y[i]).toFloat().toDouble() * f).toFloat()
                    } else {
                        for (i in out.indices) out[i] = (src[i].toFloat().toDouble() * f).toFloat()
                    }
                }
                float -> intToFloat(src, out, depth.bits, depth.exponentBits)
                else -> scale(src, out, factor, bits)
            }
            planes[c] = plane
        }
        @Suppress("UNCHECKED_CAST")
        return planes as Array<JxlPlane>
    }

    /** Extra channel [ec] as a plane of floats, at the size the frame codes it. */
    fun extraPlane(ec: Int): JxlPlane {
        val ch = full.channels[colorChannels + ec]
        val up = fh.ecUpsampling[ec]
        if (ch.w != JxlFrameDim.ceilDiv(dim.xsizeUpsampled, up) || ch.h != JxlFrameDim.ceilDiv(dim.ysizeUpsampled, up)) {
            jxlFail("Modular channel of ${ch.w} by ${ch.h} does not fit its frame")
        }
        val depth = image.extraChannels[ec].bitDepth
        val plane = JxlPlane(ch.w, ch.h)
        if (depth.floatingPoint) {
            intToFloat(ch.data, plane.data, depth.bits, depth.exponentBits)
        } else {
            if (depth.bits >= 32) jxlFail("integer samples of ${depth.bits} bits")
            scale(ch.data, plane.data, 1.0 / ((1L shl depth.bits) - 1), full.bitDepth)
        }
        return plane
    }

    private companion object {
        const val NUM_QUANT_TABLES = 17

        /** The part of a run of [size] samples from [begin] that lies inside a channel [end] samples long. */
        fun clampedSize(begin: Int, size: Int, end: Int): Int =
            if (begin.toLong() + size <= end) size else if (end > begin) end - begin else 0

        /**
         * Whole numbers to floats, with the rounding libjxl has: single precision below 23 bits,
         * where every sample survives it, and double precision above.
         */
        fun scale(src: IntArray, out: FloatArray, factor: Double, bits: Int) {
            if (bits < 23) {
                val f = factor.toFloat().toDouble()
                for (i in out.indices) out[i] = (src[i].toFloat().toDouble() * f).toFloat()
            } else {
                for (i in out.indices) out[i] = (src[i] * factor).toFloat()
            }
        }

        /** Samples that hold the bits of a float of [bits] bits, [expBits] of them the exponent, to 32-bit floats. */
        fun intToFloat(src: IntArray, out: FloatArray, bits: Int, expBits: Int) {
            if (bits == 32) {
                if (expBits != 8) jxlFail("32-bit float samples with $expBits exponent bits")
                for (i in out.indices) out[i] = Float.fromBits(src[i])
                return
            }
            val expBias = (1 shl (expBits - 1)) - 1
            val signShift = bits - 1
            val mantBits = bits - expBits - 1
            val mantShift = 23 - mantBits
            for (i in out.indices) {
                var f = src[i]
                val sign = (f ushr signShift) != 0
                f = f and ((1 shl signShift) - 1)
                if (f == 0) {
                    out[i] = if (sign) -0f else 0f
                    continue
                }
                var exp = f ushr mantBits
                var mantissa = (f and ((1 shl mantBits) - 1)) shl mantShift
                if (exp == 0 && expBits < 8) {
                    // A subnormal of the narrow format is a normal number at 32 bits.
                    while (mantissa and 0x800000 == 0) {
                        mantissa = mantissa shl 1
                        exp--
                    }
                    exp++
                    mantissa = mantissa and 0x7FFFFF
                }
                exp = exp - expBias + 127
                if (exp < 0) jxlFail("float sample with an exponent below the range")
                out[i] = Float.fromBits((if (sign) 1 shl 31 else 0) or (exp shl 23) or mantissa)
            }
        }
    }
}
