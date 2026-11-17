package io.github.yuroyami.imagekodec.heic

/** slice_type, Table 7-7. */
internal object SliceType {
    const val B = 0
    const val P = 1
    const val I = 2
}

/** The parameter sets a decoder holds, by id, as the NAL units of a stream bring them (7.4.2.4.2). */
internal class ParameterSetStore {
    val sps = arrayOfNulls<Sps>(16)
    val pps = arrayOfNulls<Pps>(64)

    /**
     * Reads the parameter set that [r] holds after the NAL unit header [nal], and returns it, or null
     * for a unit of another type. A VPS is read only as far as its id; a unit of another layer is ignored.
     */
    fun add(nal: NalHeader, r: BitReader): Any? {
        if (nal.layerId != 0) return null
        return when (nal.type) {
            NalType.VPS -> readVpsId(r)
            NalType.SPS -> readSps(r).also { sps[it.id] = it }
            NalType.PPS -> readPps(r).also { pps[it.id] = it }
            else -> null
        }
    }
}

/** A slice segment header (7.3.6.1) with its active parameter sets. */
internal class SliceHeader {
    var nalType = 0
    var temporalId = 0
    var firstSliceSegmentInPic = false
    var noOutputOfPriorPics = false
    lateinit var pps: Pps
    lateinit var sps: Sps
    var dependentSliceSegment = false
    var sliceSegmentAddress = 0
    /** SliceAddrRs (7.4.7.1): the address of the independent slice segment that starts the slice. */
    var sliceAddrRs = 0
    var sliceType = SliceType.I
    var picOutput = true
    var colourPlaneId = 0
    var picOrderCntLsb = 0
    var shortTermRpsSpsFlag = false
    var shortTermRpsIdx = 0
    /** The short-term RPS of the picture, null for an IDR picture. */
    var shortTermRps: ShortTermRps? = null
    var numLongTermSps = 0
    var numLongTermPics = 0
    var usedByCurrPicLt = BooleanArray(0)
    /** PocLsbLt (7.4.7.1), delta_poc_msb_present_flag and DeltaPocMsbCycleLt (7-52) of each long-term entry. */
    var pocLsbLt = IntArray(0)
    var deltaPocMsbPresent = BooleanArray(0)
    var deltaPocMsbCycleLt = LongArray(0)
    var temporalMvpEnabled = false
    var saoLuma = false
    var saoChroma = false
    var numRefIdxL0ActiveMinus1 = 0
    var numRefIdxL1ActiveMinus1 = 0
    var mvdL1Zero = false
    /** list_entry_l0 and list_entry_l1 of ref_pic_lists_modification( ), or null for a list that is not modified. */
    var listEntryL0: IntArray? = null
    var listEntryL1: IntArray? = null
    var cabacInit = false
    var collocatedFromL0 = true
    var collocatedRefIdx = 0
    var maxNumMergeCand = 5
    var useIntegerMv = false
    /** The explicit weights of pred_weight_table, or null when the slice predicts with the default weights. */
    var weights: PredWeights? = null
    /** The reference picture lists of a P or B slice, which the decoder builds after the header (8.3.4). */
    var refs: RefLists? = null
    var qpDelta = 0
    var cbQpOffset = 0
    var crQpOffset = 0
    var actYQpOffset = 0
    var actCbQpOffset = 0
    var actCrQpOffset = 0
    var cuChromaQpOffsetEnabled = false
    var deblockingFilterDisabled = false
    var betaOffsetDiv2 = 0
    var tcOffsetDiv2 = 0
    var loopFilterAcrossSlices = false
    /** entry_point_offset_minus1[ i ] + 1, in bytes of the slice segment data. */
    var entryPointOffsets = IntArray(0)
    /** The size of the header in bits of the RBSP, from the NAL unit header on. */
    var headerBits = 0

    /** SliceQpY, 7-54. */
    val sliceQpY: Int get() = 26 + pps.initQpMinus26 + qpDelta

    /** NumPicTotalCurr, 7-57. */
    val numPicTotalCurr: Int
        get() = (shortTermRps?.numUsed ?: 0) + usedByCurrPicLt.count { it } + (if (pps.currPicRefEnabled) 1 else 0)

    val isIrap: Boolean get() = nalType in NalType.BLA_W_LP..NalType.RSV_IRAP_VCL23

    /** The fields a dependent slice segment takes from the slice segment before it (7.4.7.1). */
    fun copySliceFields(from: SliceHeader) {
        sliceAddrRs = from.sliceAddrRs
        sliceType = from.sliceType; picOutput = from.picOutput; colourPlaneId = from.colourPlaneId
        picOrderCntLsb = from.picOrderCntLsb; shortTermRpsSpsFlag = from.shortTermRpsSpsFlag
        shortTermRpsIdx = from.shortTermRpsIdx; shortTermRps = from.shortTermRps
        numLongTermSps = from.numLongTermSps; numLongTermPics = from.numLongTermPics
        usedByCurrPicLt = from.usedByCurrPicLt; temporalMvpEnabled = from.temporalMvpEnabled
        pocLsbLt = from.pocLsbLt; deltaPocMsbPresent = from.deltaPocMsbPresent; deltaPocMsbCycleLt = from.deltaPocMsbCycleLt
        weights = from.weights; refs = from.refs
        saoLuma = from.saoLuma; saoChroma = from.saoChroma
        numRefIdxL0ActiveMinus1 = from.numRefIdxL0ActiveMinus1; numRefIdxL1ActiveMinus1 = from.numRefIdxL1ActiveMinus1
        mvdL1Zero = from.mvdL1Zero; listEntryL0 = from.listEntryL0; listEntryL1 = from.listEntryL1; cabacInit = from.cabacInit; collocatedFromL0 = from.collocatedFromL0
        collocatedRefIdx = from.collocatedRefIdx; maxNumMergeCand = from.maxNumMergeCand; useIntegerMv = from.useIntegerMv
        qpDelta = from.qpDelta; cbQpOffset = from.cbQpOffset; crQpOffset = from.crQpOffset
        actYQpOffset = from.actYQpOffset; actCbQpOffset = from.actCbQpOffset; actCrQpOffset = from.actCrQpOffset
        cuChromaQpOffsetEnabled = from.cuChromaQpOffsetEnabled; deblockingFilterDisabled = from.deblockingFilterDisabled
        betaOffsetDiv2 = from.betaOffsetDiv2; tcOffsetDiv2 = from.tcOffsetDiv2; loopFilterAcrossSlices = from.loopFilterAcrossSlices
    }
}

/** Ceil( Log2( n ) ) for n of at least 1. */
internal fun ceilLog2(n: Int): Int = if (n <= 1) 0 else 32 - (n - 1).countLeadingZeroBits()

/**
 * slice_segment_header( ), 7.3.6.1, of a slice NAL unit [nal] whose RBSP [r] stands after the NAL unit
 * header. [store] holds the parameter sets; [previous] is the slice segment before this one in the
 * picture, which a dependent slice segment continues. [nalBytes] bounds the entry point offsets.
 */
internal fun readSliceHeader(r: BitReader, nal: NalHeader, store: ParameterSetStore, previous: SliceHeader?, nalBytes: Int): SliceHeader {
    val h = SliceHeader()
    h.nalType = nal.type
    h.temporalId = nal.temporalId
    h.firstSliceSegmentInPic = r.flag("first_slice_segment_in_pic_flag")
    if (h.isIrap) h.noOutputOfPriorPics = r.flag("no_output_of_prior_pics_flag")
    val ppsId = r.ue("slice_pic_parameter_set_id", 0, 63)
    val pps = store.pps[ppsId] ?: hevcError("slice_pic_parameter_set_id $ppsId names no PPS")
    val sps = store.sps[pps.spsId] ?: hevcError("pps_seq_parameter_set_id ${pps.spsId} names no SPS")
    pps.checkAgainst(sps)
    h.pps = pps
    h.sps = sps
    if (nal.temporalId > sps.maxSubLayersMinus1) hevcError("TemporalId ${nal.temporalId} passes sps_max_sub_layers_minus1")
    if (!h.firstSliceSegmentInPic) {
        if (pps.dependentSliceSegmentsEnabled) h.dependentSliceSegment = r.flag("dependent_slice_segment_flag")
        h.sliceSegmentAddress = r.u(ceilLog2(sps.picSizeInCtbs), "slice_segment_address", 0, sps.picSizeInCtbs - 1)
    }
    if (h.dependentSliceSegment) {
        val from = previous ?: hevcError("a dependent slice segment has no slice segment before it")
        h.copySliceFields(from)
    } else {
        h.sliceAddrRs = h.sliceSegmentAddress
        readIndependentFields(r, h, nal, sps, pps)
    }
    if (pps.tilesEnabled || pps.entropyCodingSync) {
        // The bound of 7.4.7.1: one subset per tile, per CTB row, or per CTB row of each tile.
        val max = when {
            !pps.tilesEnabled -> sps.picHeightInCtbs - 1
            !pps.entropyCodingSync -> (pps.numTileColumnsMinus1 + 1) * (pps.numTileRowsMinus1 + 1) - 1
            else -> (pps.numTileColumnsMinus1 + 1) * sps.picHeightInCtbs - 1
        }
        val n = r.ue("num_entry_point_offsets", 0, max)
        if (n > 0) {
            val len = r.ue("offset_len_minus1", 0, 31) + 1
            // Checked before the array is made, so a large count in a short unit allocates nothing.
            if (n.toLong() * len > r.bitsLeft) hevcError("num_entry_point_offsets $n passes the end of the slice segment")
            h.entryPointOffsets = IntArray(n)
            var sum = 0L
            for (i in 0 until n) {
                val v = (if (len == 32) r.u32("entry_point_offset_minus1") else r.u(len, "entry_point_offset_minus1").toLong()) + 1
                sum += v
                if (sum > nalBytes) hevcError("entry_point_offset_minus1 passes the end of the slice segment")
                h.entryPointOffsets[i] = v.toInt()
            }
        }
    }
    if (pps.sliceSegmentHeaderExtensionPresent) {
        val n = r.ue("slice_segment_header_extension_length", 0, 256)
        r.skip(8 * n, "slice_segment_header_extension_data_byte")
    }
    r.byteAlignment()
    h.headerBits = r.pos
    return h
}

private fun readIndependentFields(r: BitReader, h: SliceHeader, nal: NalHeader, sps: Sps, pps: Pps) {
    repeat(pps.numExtraSliceHeaderBits) { r.flag("slice_reserved_flag") }
    h.sliceType = r.ue("slice_type", 0, 2)
    // 7.4.7.1: an IRAP picture of the base layer is intra unless it refers to itself.
    if (h.isIrap && !pps.currPicRefEnabled && h.sliceType != SliceType.I) hevcError("slice_type ${h.sliceType} in an IRAP picture")
    if (pps.outputFlagPresent) h.picOutput = r.flag("pic_output_flag")
    if (sps.separateColourPlane) h.colourPlaneId = r.u(2, "colour_plane_id", 0, 2)
    if (nal.type != NalType.IDR_W_RADL && nal.type != NalType.IDR_N_LP) {
        h.picOrderCntLsb = r.u(sps.log2MaxPocLsb, "slice_pic_order_cnt_lsb")
        h.shortTermRpsSpsFlag = r.flag("short_term_ref_pic_set_sps_flag")
        val count = sps.shortTermRps.size
        val dpb = sps.maxDecPicBufferingMinus1[sps.maxSubLayersMinus1]
        if (!h.shortTermRpsSpsFlag) {
            h.shortTermRps = readShortTermRps(r, count, sps.shortTermRps, count, dpb)
        } else {
            if (count == 0) hevcError("short_term_ref_pic_set_sps_flag is 1 where the SPS has no short-term RPS")
            if (count > 1) h.shortTermRpsIdx = r.u(ceilLog2(count), "short_term_ref_pic_set_idx", 0, count - 1)
            h.shortTermRps = sps.shortTermRps[h.shortTermRpsIdx]
        }
        if (sps.longTermRefPicsPresent) readLongTerm(r, h, sps, pps)
        if (sps.temporalMvpEnabled) h.temporalMvpEnabled = r.flag("slice_temporal_mvp_enabled_flag")
    }
    if (sps.saoEnabled) {
        h.saoLuma = r.flag("slice_sao_luma_flag")
        if (sps.chromaArrayType != 0) h.saoChroma = r.flag("slice_sao_chroma_flag")
    }
    if (h.sliceType != SliceType.I) readInterFields(r, h, sps, pps)
    h.qpDelta = r.se("slice_qp_delta", -(26 + 48) - 25 - 51, 51 + 26 + 48)
    // 7-54 bounds SliceQpY to -QpBdOffsetY..51.
    BitReader.check("slice_qp_delta", h.sliceQpY.toLong(), -sps.qpBdOffsetY.toLong(), 51)
    if (pps.sliceChromaQpOffsetsPresent) {
        h.cbQpOffset = r.se("slice_cb_qp_offset", -12, 12)
        BitReader.check("slice_cb_qp_offset", (pps.cbQpOffset + h.cbQpOffset).toLong(), -12, 12)
        h.crQpOffset = r.se("slice_cr_qp_offset", -12, 12)
        BitReader.check("slice_cr_qp_offset", (pps.crQpOffset + h.crQpOffset).toLong(), -12, 12)
    }
    if (pps.sliceActQpOffsetsPresent) {
        h.actYQpOffset = r.se("slice_act_y_qp_offset", -12, 12)
        h.actCbQpOffset = r.se("slice_act_cb_qp_offset", -12, 12)
        h.actCrQpOffset = r.se("slice_act_cr_qp_offset", -12, 12)
        BitReader.check("slice_act_y_qp_offset", (pps.actQpOffsetY + h.actYQpOffset).toLong(), -12, 12)
        BitReader.check("slice_act_cb_qp_offset", (pps.actQpOffsetCb + h.actCbQpOffset).toLong(), -12, 12)
        BitReader.check("slice_act_cr_qp_offset", (pps.actQpOffsetCr + h.actCrQpOffset).toLong(), -12, 12)
    }
    if (pps.chromaQpOffsetListEnabled) h.cuChromaQpOffsetEnabled = r.flag("cu_chroma_qp_offset_enabled_flag")
    val override = pps.deblockingFilterOverrideEnabled && r.flag("deblocking_filter_override_flag")
    h.deblockingFilterDisabled = pps.deblockingFilterDisabled
    h.betaOffsetDiv2 = pps.betaOffsetDiv2
    h.tcOffsetDiv2 = pps.tcOffsetDiv2
    if (override) {
        h.deblockingFilterDisabled = r.flag("slice_deblocking_filter_disabled_flag")
        if (!h.deblockingFilterDisabled) {
            h.betaOffsetDiv2 = r.se("slice_beta_offset_div2", -6, 6)
            h.tcOffsetDiv2 = r.se("slice_tc_offset_div2", -6, 6)
        }
    }
    h.loopFilterAcrossSlices = pps.loopFilterAcrossSlices
    if (pps.loopFilterAcrossSlices && (h.saoLuma || h.saoChroma || !h.deblockingFilterDisabled)) {
        h.loopFilterAcrossSlices = r.flag("slice_loop_filter_across_slices_enabled_flag")
    }
}

private fun readLongTerm(r: BitReader, h: SliceHeader, sps: Sps, pps: Pps) {
    val numSps = sps.ltRefPicPocLsb.size
    if (numSps > 0) h.numLongTermSps = r.ue("num_long_term_sps", 0, numSps)
    val rps = h.shortTermRps!!
    // TwoVersionsOfCurrDecPicFlag, 7-40.
    val twoVersions = pps.currPicRefEnabled && (sps.saoEnabled || !pps.deblockingFilterDisabled || pps.deblockingFilterOverrideEnabled)
    val max = sps.maxDecPicBufferingMinus1[h.temporalId] - rps.numNegative - rps.numPositive - h.numLongTermSps - (if (twoVersions) 1 else 0)
    h.numLongTermPics = r.ue("num_long_term_pics", 0, maxOf(max, 0))
    val n = h.numLongTermSps + h.numLongTermPics
    h.usedByCurrPicLt = BooleanArray(n)
    h.pocLsbLt = IntArray(n)
    h.deltaPocMsbPresent = BooleanArray(n)
    h.deltaPocMsbCycleLt = LongArray(n)
    for (i in 0 until n) {
        if (i < h.numLongTermSps) {
            val idx = if (numSps > 1) r.u(ceilLog2(numSps), "lt_idx_sps", 0, numSps - 1) else 0
            h.pocLsbLt[i] = sps.ltRefPicPocLsb[idx]
            h.usedByCurrPicLt[i] = sps.usedByCurrPicLt[idx]
        } else {
            h.pocLsbLt[i] = r.u(sps.log2MaxPocLsb, "poc_lsb_lt")
            h.usedByCurrPicLt[i] = r.flag("used_by_curr_pic_lt_flag")
        }
        h.deltaPocMsbPresent[i] = r.flag("delta_poc_msb_present_flag")
        var cycle = 0L
        if (h.deltaPocMsbPresent[i]) {
            cycle = r.ueLong("delta_poc_msb_cycle_lt")
            BitReader.check("delta_poc_msb_cycle_lt", cycle, 0, 1L shl (32 - sps.log2MaxPocLsb))
        }
        // 7-52: the cycles add up within the entries of the SPS and within those of the slice header.
        h.deltaPocMsbCycleLt[i] = if (i == 0 || i == h.numLongTermSps) cycle else cycle + h.deltaPocMsbCycleLt[i - 1]
    }
}

private fun readInterFields(r: BitReader, h: SliceHeader, sps: Sps, pps: Pps) {
    val b = h.sliceType == SliceType.B
    h.numRefIdxL0ActiveMinus1 = pps.numRefIdxL0DefaultActiveMinus1
    h.numRefIdxL1ActiveMinus1 = if (b) pps.numRefIdxL1DefaultActiveMinus1 else 0
    if (r.flag("num_ref_idx_active_override_flag")) {
        h.numRefIdxL0ActiveMinus1 = r.ue("num_ref_idx_l0_active_minus1", 0, 14)
        if (b) h.numRefIdxL1ActiveMinus1 = r.ue("num_ref_idx_l1_active_minus1", 0, 14)
    }
    val total = h.numPicTotalCurr
    // 8.3.4 fills each list from these pictures; with none, a P or B slice has nothing to refer to.
    if (total == 0) hevcError("a ${if (b) "B" else "P"} slice has no reference picture (NumPicTotalCurr is 0)")
    var entriesL0: IntArray? = null
    var entriesL1: IntArray? = null
    if (pps.listsModificationPresent && total > 1) {
        // ref_pic_lists_modification( ), 7.3.6.2.
        val bits = ceilLog2(total)
        if (r.flag("ref_pic_list_modification_flag_l0")) {
            entriesL0 = IntArray(h.numRefIdxL0ActiveMinus1 + 1) { r.u(bits, "list_entry_l0", 0, total - 1) }
        }
        if (b && r.flag("ref_pic_list_modification_flag_l1")) {
            entriesL1 = IntArray(h.numRefIdxL1ActiveMinus1 + 1) { r.u(bits, "list_entry_l1", 0, total - 1) }
        }
    }
    h.listEntryL0 = entriesL0
    h.listEntryL1 = entriesL1
    if (b) h.mvdL1Zero = r.flag("mvd_l1_zero_flag")
    if (pps.cabacInitPresent) h.cabacInit = r.flag("cabac_init_flag")
    if (h.temporalMvpEnabled) {
        if (b) h.collocatedFromL0 = r.flag("collocated_from_l0_flag")
        val max = if (h.collocatedFromL0) h.numRefIdxL0ActiveMinus1 else h.numRefIdxL1ActiveMinus1
        if (max > 0) h.collocatedRefIdx = r.ue("collocated_ref_idx", 0, max)
    }
    if ((pps.weightedPred && !b) || (pps.weightedBipred && b)) {
        val l0 = currentPictureEntries(h, 0, entriesL0)
        val l1 = if (b) currentPictureEntries(h, 1, entriesL1) else null
        h.weights = readPredWeightTable(r, sps, l0, l1)
    }
    h.maxNumMergeCand = 5 - r.ue("five_minus_max_num_merge_cand", 0, 4)
    if (sps.motionVectorResolutionControlIdc == 2) h.useIntegerMv = r.flag("use_integer_mv_flag")
    else h.useIntegerMv = sps.motionVectorResolutionControlIdc != 0
}

/**
 * Which entries of RefPicList0 or RefPicList1 ([list]) are the current picture itself, as 8.3.4 builds
 * the lists. Only the kind of each entry matters, so no picture is needed. pred_weight_table skips the
 * weights of those entries, since two pictures of one layer never share a picture order count.
 */
private fun currentPictureEntries(h: SliceHeader, list: Int, entries: IntArray?): BooleanArray {
    val rps = h.shortTermRps
    val before = rps?.usedS0?.count { it } ?: 0
    val after = rps?.usedS1?.count { it } ?: 0
    val lt = h.usedByCurrPicLt.count { it }
    val active = (if (list == 0) h.numRefIdxL0ActiveMinus1 else h.numRefIdxL1ActiveMinus1) + 1
    val total = h.numPicTotalCurr
    val num = maxOf(active, total)
    // 8-8 and 8-10: the temporary list repeats the RPS, then the current picture, until it is full.
    val temp = BooleanArray(num + 1)
    var idx = 0
    while (idx < num) {
        idx += minOf(before + after + lt, num - idx)
        if (h.pps.currPicRefEnabled) temp[idx++] = true
    }
    val out = BooleanArray(active) { i -> temp[entries?.get(i) ?: i] }
    // 8-9 puts the current picture last in list 0 when the list is not modified and is cut short.
    if (list == 0 && h.pps.currPicRefEnabled && entries == null && num > active) out[active - 1] = true
    return out
}

/**
 * The weights and offsets of pred_weight_table( ) (7.4.7.3) as 8.5.3.3.4.3 uses them, per list and
 * reference index: LumaWeightLX, luma_offset_lX, ChromaWeightLX and ChromaOffsetLX, with the offsets not
 * yet shifted by WpOffsetBdShiftY or WpOffsetBdShiftC.
 */
internal class PredWeights(val lumaLog2Denom: Int, val chromaLog2Denom: Int, lists: Int) {
    val lumaWeight: Array<IntArray> = Array(lists) { IntArray(MAX_REFS) { 1 shl lumaLog2Denom } }
    val lumaOffset: Array<IntArray> = Array(lists) { IntArray(MAX_REFS) }
    /** At refIdx * 2 + j, j 0 for Cb and 1 for Cr. */
    val chromaWeight: Array<IntArray> = Array(lists) { IntArray(2 * MAX_REFS) { 1 shl chromaLog2Denom } }
    val chromaOffset: Array<IntArray> = Array(lists) { IntArray(2 * MAX_REFS) }

    companion object {
        /** num_ref_idx_lX_active_minus1 is at most 14 (7.4.7.1). */
        const val MAX_REFS = 15
    }
}

/** pred_weight_table( ), 7.3.6.3, with the ranges and the derived variables of 7.4.7.3. */
private fun readPredWeightTable(r: BitReader, sps: Sps, l0: BooleanArray, l1: BooleanArray?): PredWeights {
    val lumaDenom = r.ue("luma_log2_weight_denom", 0, 7)
    var chromaDenom = lumaDenom
    if (sps.chromaArrayType != 0) {
        chromaDenom = lumaDenom + r.se("delta_chroma_log2_weight_denom", -7, 7)
        BitReader.check("delta_chroma_log2_weight_denom", chromaDenom.toLong(), 0, 7)
    }
    val w = PredWeights(lumaDenom, chromaDenom, if (l1 == null) 1 else 2)
    // WpOffsetHalfRangeY and WpOffsetHalfRangeC, 7-33 and 7-34.
    val halfY = 1 shl (if (sps.highPrecisionOffsets) sps.bitDepthY - 1 else 7)
    val halfC = 1 shl (if (sps.highPrecisionOffsets) sps.bitDepthC - 1 else 7)
    for ((k, isCurrent) in listOfNotNull(l0, l1).withIndex()) {
        val x = if (k == 0) "l0" else "l1"
        val luma = BooleanArray(isCurrent.size) { i -> !isCurrent[i] && r.flag("luma_weight_${x}_flag") }
        val chroma = BooleanArray(isCurrent.size)
        if (sps.chromaArrayType != 0) for (i in isCurrent.indices) chroma[i] = !isCurrent[i] && r.flag("chroma_weight_${x}_flag")
        for (i in isCurrent.indices) {
            if (luma[i]) {
                w.lumaWeight[k][i] = (1 shl lumaDenom) + r.se("delta_luma_weight_$x", -128, 127)
                w.lumaOffset[k][i] = r.se("luma_offset_$x", -halfY, halfY - 1)
            }
            if (chroma[i]) for (j in 0..1) {
                val weight = (1 shl chromaDenom) + r.se("delta_chroma_weight_$x", -128, 127)
                val delta = r.se("delta_chroma_offset_$x", -4 * halfC, 4 * halfC - 1)
                w.chromaWeight[k][2 * i + j] = weight
                // 7-58.
                val offset = halfC + delta - ((halfC * weight) shr chromaDenom)
                w.chromaOffset[k][2 * i + j] = if (offset < -halfC) -halfC else if (offset > halfC - 1) halfC - 1 else offset
            }
        }
    }
    return w
}
