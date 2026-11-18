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
 *
 * In the P and B pictures of a sequence (#177), [effort] also sets the inter search:
 * - 1: the merging candidate of least SATD tested in full, skipped and with its residual; the AMVP 2Nx2N unit
 *   only where skipping lost; 2Nx2N alone; a pattern search from 8 samples; intra units only where inter
 *   prediction leaves a residual; one reference picture in each list;
 * - 2: the best two merging candidates; the AMVP unit always; 2NxN and Nx2N unless 2Nx2N was skipped; a search
 *   from 16 samples; intra units wherever the unit is not skipped;
 * - 3: the best three merging candidates; the asymmetric partitions too; a search from 32 samples; each list's
 *   vector of a bi-predicted unit refined against the other's prediction; two reference pictures in each list.
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
 * How [HevcEncoder.encodeSequence] orders and refers its pictures (#177). [gopSize] frames make a group: 1 codes
 * IPPP, each P picture referring to the pictures before it; from 2 the last frame of each group is a P picture
 * and the frames before it B pictures of a dyadic pyramid, coded after it. [irapPeriod] starts an IDR picture every
 * so many frames, so a reader can seek there; 0 codes the first frame alone as one. The QP of a picture grows
 * with its distance from an intra one: [pOffset] for a P picture, and [bOffset] plus the B picture's depth in the
 * pyramid for a B picture, so the pictures that others refer to keep more detail. See `docs/heic.md` for the
 * measurements behind the defaults.
 */
internal class HevcSequenceSettings(
    val gopSize: Int = 4,
    val irapPeriod: Int = 0,
    val pOffset: Int = 3,
    val bOffset: Int = 4,
) {
    init {
        require(gopSize in 1..16) { "HEVC: a group of $gopSize pictures is not in 1..16" }
        require(irapPeriod >= 0) { "HEVC: an IRAP period of $irapPeriod frames is negative" }
        require(pOffset in 0..12 && bOffset in 0..12) { "HEVC: QP offsets $pOffset and $bOffset are not in 0..12" }
    }
}

/**
 * One access unit of a coded sequence, in decoding order, as an ISO/IEC 14496-15 track holds it: [data] is the
 * sample, its NAL units behind 4-byte lengths (the slice, then the hash SEI if asked for); [frame] the index of
 * its frame in presentation order; [sync] marks an IDR picture (`stss`); [duration] is its decoding duration
 * (`stts`) and [compositionOffset] its composition time less its decoding time (`ctts`, never negative), both
 * in the units of the frame durations.
 */
internal class HevcSample(
    val data: ByteArray,
    val frame: Int,
    val sync: Boolean,
    val duration: Long,
    val compositionOffset: Long,
)

/**
 * A coded sequence: [hvcC] is the payload of an `hvcC` box, [samples] the access units in decoding order and
 * [annexB] the whole stream with start codes, parameter sets first. Every picture decodes to [width] by [height],
 * as [HevcCoded]'s does. [presentationOffset] is the composition time of the first frame, which reordering
 * delays: the media_time of an edit list that shows it at time 0, and 0 for a sequence without B pictures.
 * [reordered] says whether any sample's composition offset is not 0, so a writer needs a `ctts` box.
 */
internal class HevcSequence(
    val hvcC: ByteArray,
    val samples: List<HevcSample>,
    val annexB: ByteArray,
    val width: Int,
    val height: Int,
    val profile: String,
    val presentationOffset: Long,
) {
    val reordered: Boolean get() = samples.any { it.compositionOffset != 0L }
}

/**
 * An HEVC encoder written from ITU-T H.265: single intra pictures ([encode]), and sequences of P and B pictures
 * ([encodeSequence]). Each picture is one slice of CTBs of 64, coding units of 64 down to 8 and transform blocks
 * of 32 down to 4. Its reconstruction runs through the decoder's own prediction, scaling, inverse transforms,
 * deblocking and SAO ([PictureDecoder]), and in a sequence through the decoder's own decoded picture buffer,
 * reference lists, merging candidates, AMVP and interpolation, so what the encoder measured is what a decoder makes.
 */
internal object HevcEncoder {

    /** The checked layout of a picture: chroma_format_idc, the chroma shifts, and the decoded and coded sizes. */
    private class Layout(val chromaFormat: Int, val subW: Int, val subH: Int, val outW: Int, val outH: Int, val codedW: Int, val codedH: Int, val bitDepthC: Int)

    /** Checks [planes] and [colour] as every entry point takes them; throws IllegalArgumentException for what HEVC cannot hold. */
    private fun layout(planes: HeifPlanes, colour: IntArray?): Layout {
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
        val bitDepthC = if (chromaFormat == 0) planes.bitDepthLuma else planes.bitDepthChroma
        return Layout(chromaFormat, subW, subH, outW, outH, codedW, codedH, bitDepthC)
    }

    /**
     * Encodes [planes] (YCbCr or monochrome samples), signalling [fullRange] and the colour description
     * [colour] (colour_primaries, transfer_characteristics and matrix_coeffs of ITU-T H.273, or null) in the
     * VUI. [cancel] runs once per CTB row. Throws IllegalArgumentException for a picture HEVC or a level cannot hold.
     */
    fun encode(planes: HeifPlanes, fullRange: Boolean, colour: IntArray?, settings: HevcEncoderSettings, cancel: (() -> Unit)?): HevcCoded {
        val l = layout(planes, colour)
        val level = HevcProfile.level(l.codedW, l.codedH)
            ?: throw IllegalArgumentException("HEVC: a picture of ${planes.width} by ${planes.height} passes every level of Table A.8")
        checkPlanes(planes, l.subW, l.subH)
        val profile = HevcProfile.choose(l.chromaFormat, planes.bitDepthLuma, l.bitDepthC, level)
        val lossless = settings.lossless
        val qp = if (lossless) 26 else settings.sliceQp
        val plan = EffortPlan(settings.effort, lossless)
        val aq = settings.adaptiveQp && !lossless
        val config = HevcStreamConfig(
            width = l.codedW, height = l.codedH,
            confRight = (l.codedW - l.outW) shr l.subW, confBottom = (l.codedH - l.outH) shr l.subH,
            chromaFormat = l.chromaFormat, bitDepthY = planes.bitDepthLuma, bitDepthC = l.bitDepthC, profile = profile,
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
        val header = HevcHeaderWriter.sliceHeader(config, sao, sao && l.chromaFormat != 0, qp)
        val slice = BitReader(header).let { r -> readSliceHeader(r, readNalHeader(r), store, null, header.size) }
        val rec = PictureDecoder(slice.sps, slice.pps)
        rec.beginEncoding(slice)
        val orig = pad(planes, rec, l.chromaFormat, l.subW, l.subH)
        val offsets = if (aq) activityOffsets(orig[0], rec.width, rec.height, planes.bitDepthLuma, settings.aqStrength) else null
        val encoder = IntraEncoder(rec, orig, qp, lossless, plan, offsets, QG_LOG2)
        val units = ArrayList<ByteArray>()
        units += escapeNal(header + codeSlice(rec, encoder, orig, qp, sao, cancel))
        if (settings.hash) units += escapeNal(pictureHash(rec, l))
        val sample = lengthPrefixed(units)
        val annexB = startCodes(listOf(escapeNal(vps), escapeNal(sps), escapeNal(pps)) + units)
        return HevcCoded(HevcHeaderWriter.hvcC(config, vps, sps, pps), sample, annexB, l.outW, l.outH, qp, profile.name)
    }

    /**
     * Encodes [frames], each shown for its entry of [durations] (in any time unit, at least 1), as one HEVC sequence
     * ordered and referred by [sequence]: P and B pictures with motion search, merging and AMVP, skipped units,
     * the partitions [settings]' effort allows, and intra units where they cost less. Every frame has the first's
     * size, format and depth. [cancel] runs once per CTB row. Throws IllegalArgumentException for frames HEVC or
     * a level cannot hold.
     */
    fun encodeSequence(
        frames: List<HeifPlanes>, durations: LongArray, fullRange: Boolean, colour: IntArray?,
        settings: HevcEncoderSettings, sequence: HevcSequenceSettings, cancel: (() -> Unit)?,
    ): HevcSequence {
        require(frames.isNotEmpty()) { "HEVC: a sequence of no frames" }
        require(durations.size == frames.size) { "HEVC: ${durations.size} durations for ${frames.size} frames" }
        require(durations.all { it >= 1 }) { "HEVC: a frame duration is less than 1" }
        val first = frames[0]
        val l = layout(first, colour)
        for ((i, f) in frames.withIndex()) {
            require(
                f.width == first.width && f.height == first.height && f.planes.size == first.planes.size && f.subX == first.subX &&
                    f.subY == first.subY && f.bitDepthLuma == first.bitDepthLuma && (l.chromaFormat == 0 || f.bitDepthChroma == first.bitDepthChroma),
            ) { "HEVC: frame $i differs from the first frame's size, format or depth" }
            checkPlanes(f, l.subW, l.subH)
        }
        val lossless = settings.lossless
        val effort = EffortPlan(settings.effort, lossless)
        val plan = GopPlanner.plan(frames.size, sequence.gopSize, sequence.irapPeriod, effort.refsPerList)
        val level = HevcProfile.level(l.codedW, l.codedH, plan.maxDecPicBufferingMinus1 + 1)
            ?: throw IllegalArgumentException("HEVC: a sequence of ${first.width} by ${first.height} passes every level of Table A.8")
        val profile = HevcProfile.choose(l.chromaFormat, first.bitDepthLuma, l.bitDepthC, level, sequence = true)
        val baseQp = if (lossless) 26 else settings.sliceQp
        // The SPS holds each distinct reference picture set once, up to the 64 of 7.4.3.2.1; the others are coded in their slice.
        val sets = ArrayList<ShortTermRps>()
        val rpsIdx = IntArray(plan.pictures.size) { -1 }
        for ((k, p) in plan.pictures.withIndex()) {
            val rps = p.rps ?: continue
            var at = sets.indexOfFirst { sameRps(it, rps) }
            if (at < 0 && sets.size < 64) {
                sets += rps
                at = sets.size - 1
            }
            rpsIdx[k] = at
        }
        val config = HevcStreamConfig(
            width = l.codedW, height = l.codedH,
            confRight = (l.codedW - l.outW) shr l.subW, confBottom = (l.codedH - l.outH) shr l.subH,
            chromaFormat = l.chromaFormat, bitDepthY = first.bitDepthLuma, bitDepthC = l.bitDepthC, profile = profile,
            ctbLog2 = 6, minCbLog2 = 3, maxTbLog2 = 5, maxTransformHierarchyDepthIntra = effort.transformDepth,
            sao = !lossless, transformSkip = effort.transformSkip, signDataHiding = !lossless, transquantBypass = lossless,
            deblockingDisabled = lossless, cuQpDeltaDepth = -1, initQp = baseQp, fullRange = fullRange, colour = colour,
            maxDecPicBufferingMinus1 = plan.maxDecPicBufferingMinus1, maxNumReorder = plan.maxNumReorder,
            log2MaxPocLsb = plan.log2MaxPocLsb, shortTermRps = sets, temporalMvp = true, amp = effort.ampParts,
            maxTransformHierarchyDepthInter = effort.transformDepth,
        )
        val vps = HevcHeaderWriter.vps(config)
        val sps = HevcHeaderWriter.sps(config)
        val pps = HevcHeaderWriter.pps(config)
        val store = ParameterSetStore()
        for (unit in listOf(vps, sps, pps)) {
            val r = BitReader(unit)
            store.add(readNalHeader(r), r)
        }
        // The decoder's own buffer marks, keeps and lists the reference pictures, so the encoder predicts from what a decoder holds.
        val dpb = PictureBuffer(keepOutput = false)
        val sao = !lossless
        val accessUnits = ArrayList<List<ByteArray>>(plan.pictures.size)
        for ((k, p) in plan.pictures.withIndex()) {
            val qp = if (lossless || settings.qp != null) baseQp else (baseQp + pictureQpOffset(p, sequence)).coerceIn(0, 51)
            val header = HevcHeaderWriter.sequenceSliceHeader(
                config, p.sliceType, p.idr, p.poc, p.rps, rpsIdx[k], p.refL0.size, p.refL1.size, sao, sao && l.chromaFormat != 0, qp,
            )
            val slice = BitReader(header).let { r -> readSliceHeader(r, readNalHeader(r), store, null, header.size) }
            val poc = dpb.beginPicture(slice) ?: error("HEVC: picture ${p.frame} is not decoded")
            check(poc == p.poc) { "HEVC: picture ${p.frame} has PicOrderCntVal $poc, not ${p.poc}" }
            val rec = PictureDecoder(slice.sps, slice.pps)
            val current = DecodedPicture(slice.sps, rec.planes, rec.strides)
            current.poc = poc
            val inter = p.sliceType != SliceType.I
            if (inter) {
                val lists = dpb.refLists(slice, poc, current)
                check(lists.pictures[0].map { it.poc } == p.refL0.toList()) { "HEVC: picture ${p.frame} lists ${lists.pictures[0].map { it.poc }}, not ${p.refL0.toList()}" }
                slice.refs = lists
                rec.beginInterEncoding(slice)
            } else {
                rec.beginEncoding(slice)
            }
            val orig = pad(frames[p.frame], rec, l.chromaFormat, l.subW, l.subH)
            val encoder = IntraEncoder(rec, orig, qp, lossless, effort, interSlice = if (inter) slice else null)
            val units = ArrayList<ByteArray>()
            units += escapeNal(header + codeSlice(rec, encoder, orig, qp, sao, cancel))
            if (settings.hash) units += escapeNal(pictureHash(rec, l))
            current.motion = arrayOf(rec.collocatedMotion())
            dpb.endPicture(current)
            accessUnits += units
        }
        // Decoding times are the presentation times in order, and each sample's composition time is its frame's,
        // later by the most that reordering delays any frame, so no composition offset is negative.
        val n = frames.size
        val pts = LongArray(n)
        for (i in 1 until n) pts[i] = pts[i - 1] + durations[i - 1]
        var shift = 0L
        for ((j, p) in plan.pictures.withIndex()) shift = maxOf(shift, pts[j] - pts[p.frame])
        val samples = plan.pictures.mapIndexed { j, p ->
            HevcSample(lengthPrefixed(accessUnits[j]), p.frame, p.idr, durations[j], pts[p.frame] + shift - pts[j])
        }
        val annexB = startCodes(listOf(escapeNal(vps), escapeNal(sps), escapeNal(pps)) + accessUnits.flatten())
        return HevcSequence(HevcHeaderWriter.hvcC(config, vps, sps, pps), samples, annexB, l.outW, l.outH, profile.name, shift)
    }

    /** The QP offset of picture [p] from the sequence's QP: 0 for an IDR picture, [HevcSequenceSettings]' offsets for the others. */
    private fun pictureQpOffset(p: GopPicture, s: HevcSequenceSettings): Int = when (p.sliceType) {
        SliceType.I -> 0
        SliceType.P -> s.pOffset
        else -> s.bOffset + p.layer
    }

    private fun sameRps(a: ShortTermRps, b: ShortTermRps): Boolean =
        a.deltaPocS0.contentEquals(b.deltaPocS0) && a.usedS0.contentEquals(b.usedS0) &&
            a.deltaPocS1.contentEquals(b.deltaPocS1) && a.usedS1.contentEquals(b.usedS1)

    /**
     * Decides, reconstructs and writes the CTBs of the slice that [rec] and [encoder] code, at [qp] with [sao], and
     * returns its slice segment data after the header. Deblocking and SAO follow one and two CTB rows behind, as
     * intra prediction reads the row above unfiltered.
     */
    private fun codeSlice(rec: PictureDecoder, encoder: IntraEncoder, orig: Array<ShortArray>, qp: Int, sao: Boolean, cancel: (() -> Unit)?): ByteArray {
        val writer = CabacEncoder(false)
        Ctx.init(writer.states, encoder.initType, qp)
        writer.start()
        val saoEncoder = if (sao) SaoEncoder(rec, orig, qp, writer) else null
        val widthInCtbs = rec.sps.picWidthInCtbs
        val heightInCtbs = rec.sps.picHeightInCtbs
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
            rec.runLoopFilters(ry) { writeRow(it) }
        }
        rec.runLoopFilters(heightInCtbs) { writeRow(it) }
        writer.alignZero()
        return writer.toByteArray()
    }

    /** The decoded picture hash SEI (MD5) of the reconstruction [rec]. */
    private fun pictureHash(rec: PictureDecoder, l: Layout): ByteArray {
        val comps = if (l.chromaFormat == 0) 1 else 3
        val hashes = List(comps) { c ->
            val w = if (c == 0) l.codedW else l.codedW shr l.subW
            val h = if (c == 0) l.codedH else l.codedH shr l.subH
            Md5.digest(pictureData(rec.planes[c], rec.strides[c], w, h, if (c == 0) rec.sps.bitDepthY else l.bitDepthC))
        }
        return HevcHeaderWriter.pictureHash(hashes)
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
