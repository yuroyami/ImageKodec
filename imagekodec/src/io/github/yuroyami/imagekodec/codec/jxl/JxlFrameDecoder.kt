// Altered Kotlin port of parts of libjxl 0.11.1 (lib/jxl/dec_frame.cc and dec_cache.cc).
// Copyright (c) the JPEG XL Project Authors. All rights reserved.
// Use of the original source is governed by a BSD-style license; see NOTICE.

package io.github.yuroyami.imagekodec.codec.jxl

import io.github.yuroyami.imagekodec.internal.Budget

/**
 * A frame before it is blended with the frames under it: three colour planes in the frame's
 * coded colour space, and one plane for each extra channel. Every plane has the frame's size.
 */
internal class JxlFrame(
    val header: JxlFrameHeader,
    val color: Array<JxlPlane>,
    val extra: Array<JxlPlane>,
    /** Where the next frame starts in the codestream. */
    val end: Int,
)

/** What a later frame may ask for from the frames before it. */
internal class JxlFrameStore {
    /** The four slots a frame can be saved in, for blending and for patches. */
    val references = arrayOfNulls<JxlReference>(4)

    /** The frames that hold the LF image of a later frame, by level. */
    val lfFrames = arrayOfNulls<Array<JxlPlane>>(4)

    // How many frames were shown, and how many came since the last one shown. They seed the noise.
    var visibleFrames = 0
    var nonvisibleFrames = 0
}

/** A saved frame. [beforeColorTransform] is true when its samples are still in the frame's coded colour space. */
internal class JxlReference(val color: Array<JxlPlane>, val extra: Array<JxlPlane>, val beforeColorTransform: Boolean)

/**
 * Reads one frame: the header, the table of contents and the sections, which are the
 * frame's global data, its LF groups, its HF global data and one section for each group
 * and pass. A frame of one group and one pass has all of them in one section.
 *
 * It then runs the steps that restore the frame's samples: chroma upsampling, the two
 * smoothing filters, patches, splines, upsampling and noise, in the order libjxl has them.
 */
internal class JxlFrameDecoder(
    private val image: JxlImageHeader,
    private val store: JxlFrameStore,
    private val data: ByteArray,
    private val limit: Int,
) {
    /** Reads the header of the frame at [at] and counts it, without its samples. Gives where the next frame starts. */
    fun skip(at: Int, preview: Boolean = false): Int {
        val br = JxlBitReader(data, at, limit)
        val fh = JxlFrameHeader.read(br, image, preview)
        count(fh, preview)
        return JxlToc.read(br, fh.numTocEntries).end
    }

    private fun count(fh: JxlFrameHeader, preview: Boolean) {
        val regular = fh.type == JxlFrameHeader.REGULAR_FRAME || fh.type == JxlFrameHeader.SKIP_PROGRESSIVE
        if (!preview && regular && (fh.isLast || fh.duration > 0)) {
            store.visibleFrames++
            store.nonvisibleFrames = 0
        } else {
            store.nonvisibleFrames++
        }
    }

    fun decode(at: Int): JxlFrame {
        val headerReader = JxlBitReader(data, at, limit)
        val fh = JxlFrameHeader.read(headerReader, image, false)
        val dim = fh.dim
        JxlDecoder.refusal(image, dim.xsizeUpsampled, dim.ysizeUpsampled)?.let { jxlFail("frame of $it") }
        // VarDCT pads a frame to whole blocks, which gives a frame a few pixels high many times its own area.
        if (dim.xsizePadded.toLong() * dim.ysizePadded > 2 * Budget.MAX_PIXELS) {
            jxlFail("frame padded to ${dim.xsizePadded} by ${dim.ysizePadded} passes the pixel ceiling")
        }
        count(fh, false)
        val toc = JxlToc.read(headerReader, fh.numTocEntries)
        if (toc.end > limit) jxlFail("data ends before the frame's sections")
        if (!fh.modular && !fh.is444 && !fh.skipAdaptiveLfSmoothing) {
            jxlFail("subsampled chroma with a smoothed LF image")
        }
        val single = fh.numTocEntries == 1
        fun section(i: Int) = JxlBitReader(data, toc.offsets[i], toc.offsets[i] + toc.sizes[i])

        // The frame's global data.
        val first = section(0)
        var patches: JxlPatches? = null
        var splines: JxlSplines? = null
        var noise: JxlNoise? = null
        if (fh.hasPatches) {
            patches = JxlPatches.read(first, dim.xsizePadded, dim.ysizePadded, image, store)
            if (patches.usesExtraChannels && fh.upsampling != 1 && fh.ecUpsampling.any { it != fh.upsampling }) {
                jxlFail("patches use extra channels that are not upsampled as the colour is")
            }
            // libjxl limits how many patches a frame has, not how much they cover, so a small file can ask for hours of work.
            if (patches.totalArea > PATCH_AREA_FACTOR * dim.xsize * dim.ysize + (1L shl 20)) {
                jxlFail("patches cover too large an area")
            }
        }
        if (fh.hasSplines) splines = JxlSplines.read(first, dim.xsize.toLong() * dim.ysize)
        if (fh.hasNoise) noise = JxlNoise.read(first)
        val lfQuant = readLfQuant(first)
        val modular = JxlModularFrame(fh, image)
        val vardct = if (fh.modular) null else JxlVarDct(fh, image, modular, lfQuant, store)
        vardct?.readGlobal(first)
        modular.readGlobal(first)

        for (g in 0 until dim.numDcGroups) {
            val br = if (single) first else section(1 + g)
            if (vardct != null && !fh.usesLfFrame) vardct.readLfCoefficients(br, g)
            modular.readLfGroup(br, g)
            vardct?.readHfMetadata(br, g)
        }
        vardct?.finishLf()
        vardct?.readHfGlobal(if (single) first else section(1 + dim.numDcGroups))

        val shifts = IntArray(fh.numPasses * 2)
        for (pass in 0 until fh.numPasses) passBracket(fh, pass, shifts)
        val passGroups = 2 + dim.numDcGroups
        for (g in 0 until dim.numGroups) {
            val readers = Array(fh.numPasses) { if (single) first else section(passGroups + it * dim.numGroups + g) }
            vardct?.readGroup(readers, g)
            for (pass in 0 until fh.numPasses) {
                modular.readPassGroup(readers[pass], g, pass, shifts[2 * pass], shifts[2 * pass + 1])
            }
        }
        modular.finish()

        val color = vardct?.planes() ?: modular.colorPlanes(lfQuant)
        val extra = Array(image.extraChannels.size) { modular.extraPlane(it) }
        // Chroma from luma at its base value; a Modular frame has the defaults.
        val yToX = vardct?.baseYToX ?: 0.0
        val yToB = vardct?.baseYToB ?: 1.0
        restore(fh, color, extra, vardct?.sigma, patches, splines, noise, yToX, yToB)
        return JxlFrame(fh, color, extra, toc.end)
    }

    /** The steps between the decoded samples and the colour transform, in libjxl's order. They replace the planes in the two arrays. */
    private fun restore(
        fh: JxlFrameHeader,
        color: Array<JxlPlane>,
        extra: Array<JxlPlane>,
        blockSigma: JxlPlane?,
        patches: JxlPatches?,
        splines: JxlSplines?,
        noise: JxlNoise?,
        yToX: Double,
        yToB: Double,
    ) {
        val dim = fh.dim
        val lf = fh.loopFilter
        for (c in 0 until 3) {
            if (fh.hShift(c) != 0) color[c] = JxlFilters.upsampleChroma(color[c], horizontal = true, outSize = dim.xsize)
            if (fh.vShift(c) != 0) color[c] = JxlFilters.upsampleChroma(color[c], horizontal = false, outSize = dim.ysize)
        }
        if (lf.gab) JxlFilters.gaborish(color, lf).copyInto(color)
        if (lf.epfIters > 0) {
            val bw = (dim.xsize + 7) shr 3
            val bh = (dim.ysize + 7) shr 3
            val sigma = JxlPlane(bw, bh)
            if (blockSigma != null) {
                for (y in 0 until bh) blockSigma.data.copyInto(sigma.data, y * bw, y * blockSigma.w, y * blockSigma.w + bw)
            } else {
                sigma.data.fill((INV_SIGMA_NUM / lf.epfSigmaForModular.toFloat().toDouble()).toFloat())
            }
            JxlFilters.epf(color, lf, sigma).copyInto(color)
        }

        // Extra channels are upsampled with the colour when all of them have the colour's factor, and before it when not.
        val late = fh.upsampling != 1 && fh.ecUpsampling.all { it == fh.upsampling }
        if (!late) {
            for (ec in extra.indices) {
                val up = fh.ecUpsampling[ec]
                if (up != 1) extra[ec] = JxlFilters.upsample(extra[ec], up, image, dim.xsizeUpsampled, dim.ysizeUpsampled)
            }
        }
        patches?.apply(color, extra)
        splines?.apply(color, yToX, yToB, dim.xsizeUpsampled, dim.ysizeUpsampled)
        if (fh.upsampling != 1) {
            for (c in 0 until 3) color[c] = JxlFilters.upsample(color[c], fh.upsampling, image, dim.xsizeUpsampled, dim.ysizeUpsampled)
            if (late) {
                for (ec in extra.indices) extra[ec] = JxlFilters.upsample(extra[ec], fh.upsampling, image, dim.xsizeUpsampled, dim.ysizeUpsampled)
            }
        }
        noise?.apply(color, yToX, yToB, dim.groupDim, store.visibleFrames, store.nonvisibleFrames)
        for (p in color) checkSize(p, dim)
        for (p in extra) checkSize(p, dim)
    }

    private fun checkSize(p: JxlPlane, dim: JxlFrameDim) {
        if (p.w != dim.xsizeUpsampled || p.h != dim.ysizeUpsampled) jxlFail("channel of ${p.w} by ${p.h} does not fit its frame")
    }

    /** The factors that turn the quantized LF of X, Y and B into samples. */
    private fun readLfQuant(br: JxlBitReader): FloatArray {
        val q = floatArrayOf(1f / 4096f, 1f / 512f, 1f / 256f)
        if (br.bool()) return q
        for (c in 0 until 3) {
            q[c] = (br.f16().toFloat().toDouble() * (1.0 / 128.0)).toFloat()
            if (q[c] < 1e-8f) jxlFail("LF quantization factor is too small")
        }
        return q
    }

    private companion object {
        /** The filter strength of a frame that has no quantizer for each block; see epf.h. */
        const val INV_SIGMA_NUM = -1.1715728752538099024

        /** How many times the frame's area the patches of a frame may cover. */
        const val PATCH_AREA_FACTOR = 16L

        /**
         * The channels a pass holds, as the smallest and largest halving count, written to
         * [out] at 2 * [pass]. Earlier passes hold the more squeezed channels.
         */
        fun passBracket(fh: JxlFrameHeader, pass: Int, out: IntArray) {
            var maxShift = 2
            var minShift = 3
            var i = 0
            while (true) {
                for (j in 0 until fh.numDownsample) {
                    if (i == fh.lastPass[j]) {
                        when (fh.downsample[j]) {
                            8 -> minShift = 3
                            4 -> minShift = 2
                            2 -> minShift = 1
                            1 -> minShift = 0
                        }
                    }
                }
                if (i == fh.numPasses - 1) minShift = 0
                if (i == pass) break
                maxShift = minShift - 1
                i++
            }
            out[2 * pass] = minShift
            out[2 * pass + 1] = maxShift
        }
    }
}
