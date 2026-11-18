package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.HeifPlanes

/**
 * What [HevcEncoder] makes of a picture.
 *
 * [quality] runs from 0 to 100, higher better, and sets the QP by [qpOf]. [lossless] codes every coding unit
 * with cu_transquant_bypass_flag instead, and ignores [quality]. [effort] trades time for size:
 * - 1: a coarse SATD search of the intra angles (planar, DC, every fourth angle, then the angles around the
 *   best two) with the best one or two tested in full, no transform skip, no residual quadtree search, no 64
 *   coding units, the derived chroma mode only, and quantisation by rounding with a dead zone;
 * - 2 (the default): every second angle and then the angles around the best two, the best three or four tested
 *   in full with the first most probable mode, 64 coding units, one level of residual quadtree split, transform
 *   skip for 4x4 blocks, the derived chroma mode and the best two others by SATD, and RDOQ; it skips the split
 *   search and NxN under a unit whose residual is empty;
 * - 3: all 35 modes ranked, five or eight tested in full, two levels of residual quadtree, all five chroma
 *   modes, and no early stops.
 * [hash] adds the decoded picture hash SEI message (MD5) after the slice. [qp] fixes the QP instead of
 * [quality], for tests. [adaptiveQp] varies the QP by quantization groups of 32 with the luma activity
 * (cu_qp_delta), at [aqStrength] / 256 QP steps per doubling of a group's energy. It is off by default: on the
 * benchmark of HevcEncoderBenchmarkTest at effort 2 it moved the size at equal SSIMULACRA 2 score by -0.4 percent
 * at quality 50 and +0.2 percent at quality 75 with a strength of 128, and made both worse at 256 (#175).
 */
internal class HevcEncoderSettings(
    val quality: Int = 50,
    val lossless: Boolean = false,
    val effort: Int = 2,
    val hash: Boolean = false,
    val qp: Int? = null,
    val adaptiveQp: Boolean = false,
    /** The strength of adaptive QP in 1/256: QP steps per doubling of a block's luma energy against the picture's mean. */
    val aqStrength: Int = 256,
) {
    init {
        require(quality in 0..100) { "HEVC quality $quality is not in 0..100" }
        require(effort in 1..3) { "HEVC effort $effort is not in 1..3" }
        require(qp == null || qp in 0..51) { "HEVC QP $qp is not in 0..51" }
    }

    /** The QP the picture is coded at. */
    val sliceQp: Int get() = qp ?: qpOf(quality)

    companion object {
        /**
         * The QP of a [quality]: 46 less half the quality, rounded up, and at least 1, so 46 at 0, 21 at 50, 6 at 80
         * and 1 from 90 on (#176). It gives about the SSIMULACRA 2 score of libheif's `heif-enc -q` (x265) at the same
         * number, as `encodeAvif`'s quality follows `avifenc -q`: `HeicQualityTest` measured, over the photo scenes,
         * the QP whose mean score equals heif-enc's at each tenth quality from 0 to 80 (46.4, 41.2, 36.4, 31.6, 26.2,
         * 20.9, 16.2, 11.7 and 7.1), and this line fits them to within 1.1. Above 80 both encoders' scores level off
         * near 91, where QP 1 matches. The mapping before #176, 51 less half the quality, gave a mean score 9 below
         * heif-enc's at quality 50.
         */
        fun qpOf(quality: Int): Int = maxOf(1, 46 - (quality + 1) / 2)
    }
}

/**
 * One encoded picture: [hvcC] is the payload of an `hvcC` box, [sample] its NAL units behind 4-byte lengths
 * (the slice, then the hash SEI if asked for), and [annexB] the whole stream with start codes, parameter sets
 * first. The picture decodes to [width] by [height]: the input size, rounded up to whole chroma samples in
 * 4:2:0 and 4:2:2, which a HEIF writer crops with `clap`.
 */
internal class HevcCoded(
    val hvcC: ByteArray,
    val sample: ByteArray,
    val annexB: ByteArray,
    val width: Int,
    val height: Int,
    val qp: Int,
    val profile: String,
)

/**
 * An encoder of single intra pictures, written from ITU-T H.265: one IDR picture of one I slice, CTBs of 64,
 * coding units of 64 down to 8, transform blocks of 32 down to 4. The picture's reconstruction runs through
 * the decoder's own prediction, scaling, inverse transforms, deblocking and SAO ([PictureDecoder]), so what the
 * encoder measured is what a decoder makes.
 */
internal object HevcEncoder {

    /**
     * Encodes [planes] (YCbCr or monochrome samples), signalling [fullRange] and the colour description
     * [colour] (colour_primaries, transfer_characteristics and matrix_coeffs of ITU-T H.273, or null) in the
     * VUI. [cancel] runs once per CTB row. Throws IllegalArgumentException for a picture HEVC or a level cannot hold.
     */
    fun encode(planes: HeifPlanes, fullRange: Boolean, colour: IntArray?, settings: HevcEncoderSettings, cancel: (() -> Unit)?): HevcCoded {
        val chromaFormat = when {
            planes.planes.size == 1 -> 0
            planes.planes.size != 3 -> throw IllegalArgumentException("HEVC: ${planes.planes.size} planes is neither 1 nor 3")
            planes.subX == 1 && planes.subY == 1 -> 1
            planes.subX == 1 && planes.subY == 0 -> 2
            planes.subX == 0 && planes.subY == 0 -> 3
            else -> throw IllegalArgumentException("HEVC: chroma subsampled by ${planes.subX} and ${planes.subY} has no chroma_format_idc")
        }
        require(planes.width >= 1 && planes.height >= 1) { "HEVC: a picture of ${planes.width} by ${planes.height} is empty" }
        require(planes.bitDepthLuma in 8..12) { "HEVC: a luma depth of ${planes.bitDepthLuma} bits is not in 8..12" }
        require(chromaFormat == 0 || planes.bitDepthChroma in 8..12) { "HEVC: a chroma depth of ${planes.bitDepthChroma} bits is not in 8..12" }
        require(colour == null || (colour.size == 3 && colour.all { it in 0..255 })) { "HEVC: a colour description is three code points of 0 to 255" }
        require(planes.width.toLong() * planes.height <= MAX_PIXELS) { "HEVC: a picture of ${planes.width} by ${planes.height} passes the size ceiling" }
        val subW = if (chromaFormat == 1 || chromaFormat == 2) 1 else 0
        val subH = if (chromaFormat == 1) 1 else 0
        // The decoded size: whole chroma samples, as the conformance window counts in them (7-19).
        val outW = ((planes.width + subW) shr subW) shl subW
        val outH = ((planes.height + subH) shr subH) shl subH
        // pic_width_in_luma_samples and pic_height_in_luma_samples are multiples of MinCbSizeY, 8 here.
        val codedW = (outW + 7) and 7.inv()
        val codedH = (outH + 7) and 7.inv()
        val level = HevcProfile.level(codedW, codedH)
            ?: throw IllegalArgumentException("HEVC: a picture of ${planes.width} by ${planes.height} passes every level of Table A.8")
        checkPlanes(planes, subW, subH)
        val bitDepthC = if (chromaFormat == 0) planes.bitDepthLuma else planes.bitDepthChroma
        val profile = HevcProfile.choose(chromaFormat, planes.bitDepthLuma, bitDepthC, level)
        val lossless = settings.lossless
        val qp = if (lossless) 26 else settings.sliceQp
        val plan = EffortPlan(settings.effort, lossless)
        val aq = settings.adaptiveQp && !lossless
        val config = HevcStreamConfig(
            width = codedW, height = codedH,
            confRight = (codedW - outW) shr subW, confBottom = (codedH - outH) shr subH,
            chromaFormat = chromaFormat, bitDepthY = planes.bitDepthLuma, bitDepthC = bitDepthC, profile = profile,
            ctbLog2 = 6, minCbLog2 = 3, maxTbLog2 = 5, maxTransformHierarchyDepthIntra = plan.transformDepth,
            sao = !lossless, transformSkip = plan.transformSkip, signDataHiding = !lossless, transquantBypass = lossless,
            deblockingDisabled = lossless, cuQpDeltaDepth = if (aq) 6 - QG_LOG2 else -1, initQp = qp, fullRange = fullRange, colour = colour,
        )
        val vps = HevcHeaderWriter.vps(config)
        val sps = HevcHeaderWriter.sps(config)
        val pps = HevcHeaderWriter.pps(config)
        // The decoder's own reading of the parameter sets and the slice header drives the reconstruction.
        val store = ParameterSetStore()
        for (unit in listOf(vps, sps, pps)) {
            val r = BitReader(unit)
            store.add(readNalHeader(r), r)
        }
        val sao = !lossless
        val header = HevcHeaderWriter.sliceHeader(config, sao, sao && chromaFormat != 0, qp)
        val slice = BitReader(header).let { r -> readSliceHeader(r, readNalHeader(r), store, null, header.size) }
        val rec = PictureDecoder(slice.sps, slice.pps)
        rec.beginEncoding(slice)
        val orig = pad(planes, rec, chromaFormat, subW, subH)
        val offsets = if (aq) activityOffsets(orig[0], rec.width, rec.height, planes.bitDepthLuma, settings.aqStrength) else null
        val encoder = IntraEncoder(rec, orig, qp, lossless, plan, offsets, QG_LOG2)
        val writer = CabacEncoder(false)
        Ctx.init(writer.states, 0, qp)
        writer.start()
        val saoEncoder = if (sao) SaoEncoder(rec, orig, qp, writer) else null
        val widthInCtbs = slice.sps.picWidthInCtbs
        val heightInCtbs = slice.sps.picHeightInCtbs
        val total = widthInCtbs * heightInCtbs
        fun writeRow(ry: Int) {
            saoEncoder?.decideRow(ry)
            for (rx in 0 until widthInCtbs) {
                val rs = ry * widthInCtbs + rx
                encoder.writeCtb(writer, rs, sao)
                writer.terminate(if (rs == total - 1) 1 else 0)
            }
        }
        for (ry in 0 until heightInCtbs) {
            cancel?.invoke()
            for (rx in 0 until widthInCtbs) encoder.analyseCtb(ry * widthInCtbs + rx)
            // Deblocking and SAO follow one and two rows behind, as intra prediction reads the row above unfiltered.
            rec.runLoopFilters(ry) { writeRow(it) }
        }
        rec.runLoopFilters(heightInCtbs) { writeRow(it) }
        writer.alignZero()
        val sliceNal = header + writer.toByteArray()
        val units = ArrayList<ByteArray>()
        units += escapeNal(sliceNal)
        if (settings.hash) {
            val comps = if (chromaFormat == 0) 1 else 3
            val hashes = List(comps) { c ->
                val w = if (c == 0) codedW else codedW shr subW
                val h = if (c == 0) codedH else codedH shr subH
                Md5.digest(pictureData(rec.planes[c], rec.strides[c], w, h, if (c == 0) planes.bitDepthLuma else bitDepthC))
            }
            units += escapeNal(HevcHeaderWriter.pictureHash(hashes))
        }
        val sample = lengthPrefixed(units)
        val annexB = startCodes(listOf(escapeNal(vps), escapeNal(sps), escapeNal(pps)) + units)
        return HevcCoded(HevcHeaderWriter.hvcC(config, vps, sps, pps), sample, annexB, outW, outH, qp, profile.name)
    }

    /** Log2MinCuQpDeltaSize under adaptive QP: quantization groups of 32. */
    private const val QG_LOG2 = 5

    /** log2 of [v] of at least 1 in 1/256, the fraction read linearly from the bits below the leading one. */
    private fun log2Q8(v: Long): Int {
        val e = 63 - v.countLeadingZeroBits()
        val frac = if (e >= 8) (v ushr (e - 8)).toInt() and 0xFF else ((v shl (8 - e)).toInt() and 0xFF)
        return (e shl 8) + frac
    }

    /**
     * Adaptive QP by block activity: each quantization group of the luma [luma] gets the QP offset [strength] /
     * 256 times the log2 of its energy (the sum of squared differences from its mean) less the mean of that log2
     * over the picture, rounded and held to -12..12. A flat block, where a coding error shows, gets a finer step,
     * and a busy one, which hides it, a coarser one; the mean offset stays near 0.
     */
    private fun activityOffsets(luma: ShortArray, width: Int, height: Int, bitDepth: Int, strength: Int): IntArray {
        val size = 1 shl QG_LOG2
        val cols = (width + size - 1) shr QG_LOG2
        val rows = (height + size - 1) shr QG_LOG2
        val logs = IntArray(cols * rows)
        var total = 0L
        for (by in 0 until rows) for (bx in 0 until cols) {
            val x0 = bx * size
            val y0 = by * size
            val w = minOf(size, width - x0)
            val h = minOf(size, height - y0)
            var sum = 0L
            var sq = 0L
            for (y in y0 until y0 + h) {
                var rs = 0
                var rq = 0L
                for (x in x0 until x0 + w) {
                    val v = luma[y * width + x].toInt() and 0xFFFF
                    rs += v
                    rq += (v * v).toLong()
                }
                sum += rs
                sq += rq
            }
            val n = (w * h).toLong()
            // The energy at 8 bits: n times the variance, scaled down from deeper samples.
            val energy = (sq - sum * sum / n) shr (2 * (bitDepth - 8))
            val l = log2Q8(energy + 1)
            logs[by * cols + bx] = l
            total += l
        }
        val mean = total / logs.size
        return IntArray(logs.size) { i ->
            val d = (logs[i] - mean) * strength.toLong()
            val q = if (d >= 0) ((d + 32768) shr 16).toInt() else -((-d + 32768) shr 16).toInt()
            q.coerceIn(-12, 12)
        }
    }

    /** pictureData of D-22: one byte a sample, or two, low first, above 8 bits. */
    private fun pictureData(p: ShortArray, stride: Int, w: Int, h: Int, depth: Int): ByteArray {
        val wide = depth > 8
        val out = ByteArray(w * h * (if (wide) 2 else 1))
        var n = 0
        for (y in 0 until h) for (x in 0 until w) {
            val v = p[y * stride + x].toInt() and 0xFFFF
            out[n++] = v.toByte()
            if (wide) out[n++] = (v ushr 8).toByte()
        }
        return out
    }

    private fun lengthPrefixed(units: List<ByteArray>): ByteArray {
        val out = ByteArray(units.sumOf { it.size + 4 })
        var p = 0
        for (u in units) {
            for (i in 0 until 4) out[p++] = (u.size ushr (24 - 8 * i)).toByte()
            u.copyInto(out, p)
            p += u.size
        }
        return out
    }

    private fun startCodes(units: List<ByteArray>): ByteArray {
        val out = ByteArray(units.sumOf { it.size + 4 })
        var p = 0
        for (u in units) {
            out[p + 3] = 1
            p += 4
            u.copyInto(out, p)
            p += u.size
        }
        return out
    }

    /** Checks that each plane of [planes] holds its samples, before anything is allocated from them. */
    private fun checkPlanes(planes: HeifPlanes, subW: Int, subH: Int) {
        require(planes.strides.size >= planes.planes.size) { "HEVC: ${planes.strides.size} strides for ${planes.planes.size} planes" }
        for (c in planes.planes.indices) {
            val w = if (c == 0) planes.width else (planes.width + subW) shr subW
            val h = if (c == 0) planes.height else (planes.height + subH) shr subH
            val stride = planes.strides[c]
            require(stride >= w) { "HEVC: plane $c has a stride of $stride for $w samples" }
            require(planes.planes[c].size.toLong() >= stride.toLong() * (h - 1) + w) { "HEVC: plane $c holds ${planes.planes[c].size} samples, fewer than $w by $h" }
            val depth = if (c == 0) planes.bitDepthLuma else planes.bitDepthChroma
            val max = (1 shl depth) - 1
            val p = planes.planes[c]
            for (y in 0 until h) for (x in 0 until w) {
                val v = p[y * stride + x].toInt() and 0xFFFF
                require(v <= max) { "HEVC: sample $v of plane $c passes $depth bits" }
            }
        }
    }

    /** The source planes at the coded size, laid out as [rec]'s, the last row and column repeated into the padding. */
    private fun pad(planes: HeifPlanes, rec: PictureDecoder, chromaFormat: Int, subW: Int, subH: Int): Array<ShortArray> =
        Array(if (chromaFormat == 0) 1 else 3) { c ->
            val w = if (c == 0) planes.width else (planes.width + subW) shr subW
            val h = if (c == 0) planes.height else (planes.height + subH) shr subH
            val cw = if (c == 0) rec.width else rec.width shr subW
            val ch = if (c == 0) rec.height else rec.height shr subH
            val src = planes.planes[c]
            val stride = planes.strides[c]
            val out = ShortArray(cw * ch)
            for (y in 0 until ch) {
                val sy = minOf(y, h - 1)
                src.copyInto(out, y * cw, sy * stride, sy * stride + w)
                val last = src[sy * stride + w - 1]
                for (x in w until cw) out[y * cw + x] = last
            }
            out
        }
}
