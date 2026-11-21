package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.UnsupportedImageException

/** The size ceiling of the core (its Budget.MAX_PIXELS and the side limit of its HEIF reader). */
internal const val MAX_PIXELS: Long = 1L shl 28
internal const val MAX_SIDE: Int = 65536

/** The general part of profile_tier_level (7.3.3), which the decoder reports and never decides on. */
internal class ProfileTierLevel(
    val profileSpace: Int,
    val tierFlag: Boolean,
    val profileIdc: Int,
    /** general_profile_compatibility_flag[ j ] in bit 31 - j. */
    val compatibilityFlags: Int,
    val levelIdc: Int,
) {
    fun compatible(j: Int): Boolean = (compatibilityFlags ushr (31 - j)) and 1 == 1
}

/** profile_tier_level( 1, maxNumSubLayersMinus1 ), 7.3.3. Only the general fields are kept. */
internal fun readProfileTierLevel(r: BitReader, maxNumSubLayersMinus1: Int): ProfileTierLevel {
    val general = readProfile(r, "general_")
    val level = r.u(8, "general_level_idc")
    val profilePresent = BooleanArray(maxNumSubLayersMinus1)
    val levelPresent = BooleanArray(maxNumSubLayersMinus1)
    for (i in 0 until maxNumSubLayersMinus1) {
        profilePresent[i] = r.flag("sub_layer_profile_present_flag")
        levelPresent[i] = r.flag("sub_layer_level_present_flag")
    }
    if (maxNumSubLayersMinus1 > 0) for (i in maxNumSubLayersMinus1 until 8) r.u(2, "reserved_zero_2bits")
    for (i in 0 until maxNumSubLayersMinus1) {
        if (profilePresent[i]) readProfile(r, "sub_layer_")
        if (levelPresent[i]) r.u(8, "sub_layer_level_idc")
    }
    return ProfileTierLevel(general[0], general[1] == 1, general[2], general[3], level)
}

/**
 * The 88 bits of one profile in 7.3.3, with [prefix] "general_" or "sub_layer_". Returns the profile
 * space, tier, profile and compatibility flags. The constraint flags are read as the syntax names them.
 */
private fun readProfile(r: BitReader, prefix: String): IntArray {
    val space = r.u(2, prefix + "profile_space")
    val tier = r.u(1, prefix + "tier_flag")
    val idc = r.u(5, prefix + "profile_idc")
    var compat = 0
    for (j in 0 until 32) compat = (compat shl 1) or r.u(1, prefix + "profile_compatibility_flag")
    fun p(j: Int) = idc == j || (compat ushr (31 - j)) and 1 == 1
    r.flag(prefix + "progressive_source_flag")
    r.flag(prefix + "interlaced_source_flag")
    r.flag(prefix + "non_packed_constraint_flag")
    r.flag(prefix + "frame_only_constraint_flag")
    // The 43 bits that follow keep their size; only their names depend on the profile.
    if ((4..11).any { p(it) }) {
        for (n in listOf("max_12bit", "max_10bit", "max_8bit", "max_422chroma", "max_420chroma", "max_monochrome", "intra", "one_picture_only", "lower_bit_rate")) {
            r.flag(prefix + n + "_constraint_flag")
        }
        if (p(5) || p(9) || p(10) || p(11)) {
            r.flag(prefix + "max_14bit_constraint_flag")
            r.skip(33, prefix + "reserved_zero_33bits")
        } else {
            r.skip(34, prefix + "reserved_zero_34bits")
        }
    } else if (p(2)) {
        r.skip(7, prefix + "reserved_zero_7bits")
        r.flag(prefix + "one_picture_only_constraint_flag")
        r.skip(35, prefix + "reserved_zero_35bits")
    } else {
        r.skip(43, prefix + "reserved_zero_43bits")
    }
    if (p(1) || p(2) || p(3) || p(4) || p(5) || p(9) || p(11)) r.flag(prefix + "inbld_flag") else r.skip(1, prefix + "reserved_zero_bit")
    return intArrayOf(space, tier, idc, compat)
}

/**
 * The scaling lists of scaling_list_data (7.3.4, 7.4.5), resolved: [lists] holds ScalingList[ sizeId ]
 * [ matrixId ] at index sizeId * 6 + matrixId, in up-right diagonal order, and [dc] the DC value of
 * the 16x16 and 32x32 lists at the same index. 32x32 chroma lists, coded only from 4:4:4 range
 * extensions on, take the 16x16 ones as 7-50 and 7-51 say.
 */
internal class ScalingLists(val lists: Array<IntArray>, val dc: IntArray) {
    companion object {
        /** The default lists of Tables 7-5 and 7-6, with the DC value 16 that 7.4.5 infers. */
        val DEFAULT: ScalingLists = ScalingLists(
            Array(24) { k ->
                val sizeId = k / 6
                val matrixId = k % 6
                when {
                    // Table 7-5: every entry of the 4x4 default list is 16.
                    sizeId == 0 -> IntArray(16) { 16 }
                    matrixId < 3 -> HevcTables.DEFAULT_SCALING_INTRA.copyOf()
                    else -> HevcTables.DEFAULT_SCALING_INTER.copyOf()
                }
            },
            IntArray(24) { 16 },
        )
    }
}

/** scaling_list_data( ), 7.3.4, resolved with the semantics of 7.4.5. */
internal fun readScalingLists(r: BitReader): ScalingLists {
    val lists = arrayOfNulls<IntArray>(24)
    val dc = IntArray(24) { 16 }
    for (sizeId in 0 until 4) {
        val coefNum = minOf(64, 1 shl (4 + (sizeId shl 1)))
        var matrixId = 0
        while (matrixId < 6) {
            val k = sizeId * 6 + matrixId
            if (!r.flag("scaling_list_pred_mode_flag")) {
                val step = if (sizeId == 3) 3 else 1
                val delta = r.ue("scaling_list_pred_matrix_id_delta", 0, matrixId / step)
                if (delta == 0) {
                    lists[k] = ScalingLists.DEFAULT.lists[k].copyOf()
                    dc[k] = 16
                } else {
                    // 7-42: the reference list is an earlier one of the same size.
                    val ref = sizeId * 6 + matrixId - delta * step
                    lists[k] = lists[ref]!!.copyOf()
                    dc[k] = dc[ref]
                }
            } else {
                var next = 8
                if (sizeId > 1) {
                    val dcMinus8 = r.se("scaling_list_dc_coef_minus8", -7, 247)
                    next = dcMinus8 + 8
                    dc[k] = next
                }
                val list = IntArray(coefNum)
                for (i in 0 until coefNum) {
                    val delta = r.se("scaling_list_delta_coef", -128, 127)
                    next = (next + delta + 256) % 256
                    // 7.4.5: every entry of a scaling list is greater than 0.
                    if (next == 0) hevcError("scaling_list_delta_coef makes an entry of ScalingList 0")
                    list[i] = next
                }
                lists[k] = list
            }
            matrixId += if (sizeId == 3) 3 else 1
        }
    }
    for (m in intArrayOf(1, 2, 4, 5)) {
        lists[18 + m] = lists[12 + m]!!.copyOf()
        dc[18 + m] = dc[12 + m]
    }
    return ScalingLists(Array(24) { lists[it]!! }, dc)
}

/** A short-term reference picture set (7.3.7), as the variables of 7.4.8 give it. */
internal class ShortTermRps(
    val deltaPocS0: IntArray,
    val usedS0: BooleanArray,
    val deltaPocS1: IntArray,
    val usedS1: BooleanArray,
) {
    val numNegative: Int get() = deltaPocS0.size
    val numPositive: Int get() = deltaPocS1.size
    val numDeltaPocs: Int get() = deltaPocS0.size + deltaPocS1.size

    /** The pictures the current picture uses, which NumPicTotalCurr (7-57) counts. */
    val numUsed: Int get() = usedS0.count { it } + usedS1.count { it }
}

/**
 * st_ref_pic_set( [idx] ), 7.3.7. [sets] holds the candidate sets of the SPS read so far, and [count]
 * is num_short_term_ref_pic_sets. [maxDecPicBufferingMinus1] bounds the number of pictures (7.4.8).
 */
internal fun readShortTermRps(r: BitReader, idx: Int, sets: List<ShortTermRps>, count: Int, maxDecPicBufferingMinus1: Int): ShortTermRps {
    val interRps = idx != 0 && r.flag("inter_ref_pic_set_prediction_flag")
    if (!interRps) {
        val neg = r.ue("num_negative_pics", 0, maxDecPicBufferingMinus1)
        val pos = r.ue("num_positive_pics", 0, maxDecPicBufferingMinus1 - neg)
        val s0 = IntArray(neg)
        val u0 = BooleanArray(neg)
        var poc = 0
        for (i in 0 until neg) {
            poc -= r.ue("delta_poc_s0_minus1", 0, 32767) + 1
            s0[i] = poc
            u0[i] = r.flag("used_by_curr_pic_s0_flag")
        }
        val s1 = IntArray(pos)
        val u1 = BooleanArray(pos)
        poc = 0
        for (i in 0 until pos) {
            poc += r.ue("delta_poc_s1_minus1", 0, 32767) + 1
            s1[i] = poc
            u1[i] = r.flag("used_by_curr_pic_s1_flag")
        }
        return ShortTermRps(s0, u0, s1, u1)
    }
    val deltaIdxMinus1 = if (idx == count) r.ue("delta_idx_minus1", 0, idx - 1) else 0
    val ref = sets[idx - (deltaIdxMinus1 + 1)]
    val sign = r.u(1, "delta_rps_sign")
    val deltaRps = (1 - 2 * sign) * (r.ue("abs_delta_rps_minus1", 0, 32767) + 1)
    val n = ref.numDeltaPocs
    val used = BooleanArray(n + 1)
    val useDelta = BooleanArray(n + 1)
    for (j in 0..n) {
        used[j] = r.flag("used_by_curr_pic_flag")
        // 7.4.8 infers use_delta_flag 1 when it is absent.
        useDelta[j] = used[j] || r.flag("use_delta_flag")
    }
    // 7-61 and 7-62.
    val s0 = ArrayList<Int>()
    val u0 = ArrayList<Boolean>()
    for (j in ref.numPositive - 1 downTo 0) {
        val d = ref.deltaPocS1[j] + deltaRps
        if (d < 0 && useDelta[ref.numNegative + j]) { s0 += d; u0 += used[ref.numNegative + j] }
    }
    if (deltaRps < 0 && useDelta[n]) { s0 += deltaRps; u0 += used[n] }
    for (j in 0 until ref.numNegative) {
        val d = ref.deltaPocS0[j] + deltaRps
        if (d < 0 && useDelta[j]) { s0 += d; u0 += used[j] }
    }
    val s1 = ArrayList<Int>()
    val u1 = ArrayList<Boolean>()
    for (j in ref.numNegative - 1 downTo 0) {
        val d = ref.deltaPocS0[j] + deltaRps
        if (d > 0 && useDelta[j]) { s1 += d; u1 += used[j] }
    }
    if (deltaRps > 0 && useDelta[n]) { s1 += deltaRps; u1 += used[n] }
    for (j in 0 until ref.numPositive) {
        val d = ref.deltaPocS1[j] + deltaRps
        if (d > 0 && useDelta[ref.numNegative + j]) { s1 += d; u1 += used[ref.numNegative + j] }
    }
    // The bounds of num_negative_pics and num_positive_pics hold for a predicted set too (7.4.8).
    if (s0.size > maxDecPicBufferingMinus1 || s1.size > maxDecPicBufferingMinus1 - s0.size) {
        hevcError("a predicted short-term RPS holds ${s0.size} + ${s1.size} pictures, past sps_max_dec_pic_buffering_minus1")
    }
    return ShortTermRps(s0.toIntArray(), u0.toBooleanArray(), s1.toIntArray(), u1.toBooleanArray())
}

/** The fields of the VUI (Annex E.2.1) that describe the picture. */
internal class Vui(
    val videoSignalTypePresent: Boolean,
    val videoFormat: Int,
    val fullRange: Boolean,
    val colourDescriptionPresent: Boolean,
    val colourPrimaries: Int,
    val transferCharacteristics: Int,
    val matrixCoeffs: Int,
    val chromaLocTypeTop: Int,
    val chromaLocTypeBottom: Int,
    val fieldSeq: Boolean,
    /** def_disp_win_*_offset: left, right, top, bottom. */
    val defaultDisplayWindow: IntArray?,
)

/** vui_parameters( ), E.2.1. */
internal fun readVui(r: BitReader, maxSubLayersMinus1: Int): Vui {
    if (r.flag("aspect_ratio_info_present_flag")) {
        // EXTENDED_SAR, Table E.1.
        if (r.u(8, "aspect_ratio_idc") == 255) {
            r.u(16, "sar_width")
            r.u(16, "sar_height")
        }
    }
    if (r.flag("overscan_info_present_flag")) r.flag("overscan_appropriate_flag")
    val signalType = r.flag("video_signal_type_present_flag")
    var videoFormat = 5
    var fullRange = false
    var colourDescription = false
    var primaries = 2
    var transfer = 2
    var matrix = 2
    if (signalType) {
        videoFormat = r.u(3, "video_format")
        fullRange = r.flag("video_full_range_flag")
        colourDescription = r.flag("colour_description_present_flag")
        if (colourDescription) {
            primaries = r.u(8, "colour_primaries")
            transfer = r.u(8, "transfer_characteristics")
            matrix = r.u(8, "matrix_coeffs")
        }
    }
    var locTop = 0
    var locBottom = 0
    if (r.flag("chroma_loc_info_present_flag")) {
        locTop = r.ue("chroma_sample_loc_type_top_field", 0, 5)
        locBottom = r.ue("chroma_sample_loc_type_bottom_field", 0, 5)
    }
    r.flag("neutral_chroma_indication_flag")
    val fieldSeq = r.flag("field_seq_flag")
    r.flag("frame_field_info_present_flag")
    var window: IntArray? = null
    if (r.flag("default_display_window_flag")) {
        window = IntArray(4)
        for ((i, n) in listOf("def_disp_win_left_offset", "def_disp_win_right_offset", "def_disp_win_top_offset", "def_disp_win_bottom_offset").withIndex()) {
            window[i] = r.ue(n, 0, MAX_SIDE)
        }
    }
    if (r.flag("vui_timing_info_present_flag")) {
        r.u32("vui_num_units_in_tick")
        r.u32("vui_time_scale")
        if (r.flag("vui_poc_proportional_to_timing_flag")) {
            BitReader.check("vui_num_ticks_poc_diff_one_minus1", r.ueLong("vui_num_ticks_poc_diff_one_minus1"), 0, 0xFFFFFFFEL)
        }
        if (r.flag("vui_hrd_parameters_present_flag")) skipHrdParameters(r, true, maxSubLayersMinus1)
    }
    if (r.flag("bitstream_restriction_flag")) {
        r.flag("tiles_fixed_structure_flag")
        r.flag("motion_vectors_over_pic_boundaries_flag")
        r.flag("restricted_ref_pic_lists_flag")
        r.ue("min_spatial_segmentation_idc", 0, 4095)
        r.ue("max_bytes_per_pic_denom", 0, 16)
        r.ue("max_bits_per_min_cu_denom", 0, 16)
        r.ue("log2_max_mv_length_horizontal", 0, 15)
        r.ue("log2_max_mv_length_vertical", 0, 15)
    }
    return Vui(signalType, videoFormat, fullRange, colourDescription, primaries, transfer, matrix, locTop, locBottom, fieldSeq, window)
}

/** hrd_parameters( ), E.2.2, read and dropped: a decoder of still pictures has no use for timing. */
private fun skipHrdParameters(r: BitReader, commonInfPresent: Boolean, maxSubLayersMinus1: Int) {
    var nal = false
    var vcl = false
    var subPic = false
    if (commonInfPresent) {
        nal = r.flag("nal_hrd_parameters_present_flag")
        vcl = r.flag("vcl_hrd_parameters_present_flag")
        if (nal || vcl) {
            subPic = r.flag("sub_pic_hrd_params_present_flag")
            if (subPic) {
                r.u(8, "tick_divisor_minus2")
                r.u(5, "du_cpb_removal_delay_increment_length_minus1")
                r.flag("sub_pic_cpb_params_in_pic_timing_sei_flag")
                r.u(5, "dpb_output_delay_du_length_minus1")
            }
            r.u(4, "bit_rate_scale")
            r.u(4, "cpb_size_scale")
            if (subPic) r.u(4, "cpb_size_du_scale")
            r.u(5, "initial_cpb_removal_delay_length_minus1")
            r.u(5, "au_cpb_removal_delay_length_minus1")
            r.u(5, "dpb_output_delay_length_minus1")
        }
    }
    for (i in 0..maxSubLayersMinus1) {
        val general = r.flag("fixed_pic_rate_general_flag")
        // E.3.2 infers fixed_pic_rate_within_cvs_flag 1 when the general flag is 1.
        val withinCvs = general || r.flag("fixed_pic_rate_within_cvs_flag")
        var lowDelay = false
        if (withinCvs) r.ue("elemental_duration_in_tc_minus1", 0, 2047) else lowDelay = r.flag("low_delay_hrd_flag")
        val cpbCnt = if (!lowDelay) r.ue("cpb_cnt_minus1", 0, 31) + 1 else 1
        repeat((if (nal) 1 else 0) + (if (vcl) 1 else 0)) {
            // sub_layer_hrd_parameters( i ), E.2.3.
            repeat(cpbCnt) {
                for (n in if (subPic) SUB_LAYER_HRD_DU else SUB_LAYER_HRD) {
                    BitReader.check(n, r.ueLong(n), 0, 0xFFFFFFFEL)
                }
                r.flag("cbr_flag")
            }
        }
    }
}

private val SUB_LAYER_HRD = listOf("bit_rate_value_minus1", "cpb_size_value_minus1")
private val SUB_LAYER_HRD_DU = listOf("bit_rate_value_minus1", "cpb_size_value_minus1", "cpb_size_du_value_minus1", "bit_rate_du_value_minus1")

/** The video parameter set, read only as far as its id: a single-layer decoder needs nothing else. */
internal fun readVpsId(r: BitReader): Int = r.u(4, "vps_video_parameter_set_id")

/** A sequence parameter set (7.3.2.2) and the variables that 7.4.3.2 derives from it. */
internal class Sps {
    var vpsId = 0
    var maxSubLayersMinus1 = 0
    var temporalIdNesting = false
    lateinit var ptl: ProfileTierLevel
    var id = 0
    var chromaFormatIdc = 0
    var separateColourPlane = false
    var width = 0
    var height = 0
    /** conf_win_*_offset: left, right, top, bottom, in chroma units. */
    val confWin = IntArray(4)
    var bitDepthY = 8
    var bitDepthC = 8
    var log2MaxPocLsb = 4
    val maxDecPicBufferingMinus1 = IntArray(7)
    val maxNumReorderPics = IntArray(7)
    val maxLatencyIncreasePlus1 = LongArray(7)
    var minCbLog2 = 3
    var ctbLog2 = 4
    var minTbLog2 = 2
    var maxTbLog2 = 5
    var maxTransformHierarchyDepthInter = 0
    var maxTransformHierarchyDepthIntra = 0
    var scalingListEnabled = false
    /** The lists of the SPS: its own, or the defaults when it carries none. Null when scaling lists are off. */
    var scalingLists: ScalingLists? = null
    var ampEnabled = false
    var saoEnabled = false
    var pcmEnabled = false
    var pcmBitDepthY = 0
    var pcmBitDepthC = 0
    var log2MinPcmCb = 0
    var log2MaxPcmCb = 0
    var pcmLoopFilterDisabled = false
    var shortTermRps: List<ShortTermRps> = emptyList()
    var longTermRefPicsPresent = false
    var ltRefPicPocLsb = IntArray(0)
    var usedByCurrPicLt = BooleanArray(0)
    var temporalMvpEnabled = false
    var strongIntraSmoothing = false
    var vui: Vui? = null

    // sps_range_extension( ), 7.3.2.2.2.
    var transformSkipRotation = false
    var transformSkipContext = false
    var implicitRdpcm = false
    var explicitRdpcm = false
    var extendedPrecision = false
    var intraSmoothingDisabled = false
    var highPrecisionOffsets = false
    var persistentRiceAdaptation = false
    var cabacBypassAlignment = false

    // sps_scc_extension( ), 7.3.2.2.3.
    var currPicRefEnabled = false
    var paletteMode = false
    var paletteMaxSize = 0
    var paletteMaxPredictorSize = 0
    /** sps_palette_predictor_initializer[ comp ], or null when the SPS carries none. */
    var palettePredictorInitializers: Array<IntArray>? = null
    var motionVectorResolutionControlIdc = 0
    var intraBoundaryFilteringDisabled = false

    var multilayerExtension = false
    var extension3d = false

    /** ChromaArrayType, 7.4.3.2.1. */
    val chromaArrayType: Int get() = if (separateColourPlane) 0 else chromaFormatIdc

    /**
     * The bit depth of plane [c] of a decoded picture. Three separate colour planes are each decoded as the
     * luma of a monochrome picture (8.1.3), and 7.4.3.2.1 leaves BitDepthC unused without chroma arrays.
     */
    fun planeBitDepth(c: Int): Int = if (c == 0 || separateColourPlane) bitDepthY else bitDepthC
    /** SubWidthC and SubHeightC of Table 6-1. */
    val subWidthC: Int get() = if (chromaFormatIdc == 1 || chromaFormatIdc == 2) 2 else 1
    val subHeightC: Int get() = if (chromaFormatIdc == 1) 2 else 1
    val ctbSize: Int get() = 1 shl ctbLog2
    val picWidthInCtbs: Int get() = (width + ctbSize - 1) shr ctbLog2
    val picHeightInCtbs: Int get() = (height + ctbSize - 1) shr ctbLog2
    val picSizeInCtbs: Int get() = picWidthInCtbs * picHeightInCtbs
    val qpBdOffsetY: Int get() = 6 * (bitDepthY - 8)
    val qpBdOffsetC: Int get() = 6 * (bitDepthC - 8)

    /** The size of the conformance cropping window, the picture a decoder outputs (7.4.3.2.1). */
    val croppedWidth: Int get() = width - subWidthC * (confWin[0] + confWin[1])
    val croppedHeight: Int get() = height - subHeightC * (confWin[2] + confWin[3])
}

/** seq_parameter_set_rbsp( ), 7.3.2.2, of a NAL unit with nuh_layer_id 0, from the RBSP after its header. */
internal fun readSps(r: BitReader): Sps {
    val s = Sps()
    s.vpsId = r.u(4, "sps_video_parameter_set_id")
    s.maxSubLayersMinus1 = r.u(3, "sps_max_sub_layers_minus1", 0, 6)
    s.temporalIdNesting = r.flag("sps_temporal_id_nesting_flag")
    s.ptl = readProfileTierLevel(r, s.maxSubLayersMinus1)
    s.id = r.ue("sps_seq_parameter_set_id", 0, 15)
    s.chromaFormatIdc = r.ue("chroma_format_idc", 0, 3)
    if (s.chromaFormatIdc == 3) s.separateColourPlane = r.flag("separate_colour_plane_flag")
    s.width = r.ue("pic_width_in_luma_samples", 1, MAX_SIDE)
    s.height = r.ue("pic_height_in_luma_samples", 1, MAX_SIDE)
    if (s.width.toLong() * s.height > MAX_PIXELS) hevcError("a picture of ${s.width} by ${s.height} passes the size ceiling")
    if (r.flag("conformance_window_flag")) {
        s.confWin[0] = r.ue("conf_win_left_offset", 0, s.width)
        s.confWin[1] = r.ue("conf_win_right_offset", 0, s.width)
        s.confWin[2] = r.ue("conf_win_top_offset", 0, s.height)
        s.confWin[3] = r.ue("conf_win_bottom_offset", 0, s.height)
        // 7.4.3.2.1: the window keeps at least one sample in each direction.
        if (s.subWidthC * (s.confWin[0] + s.confWin[1]) >= s.width) hevcError("conf_win_left_offset and conf_win_right_offset leave no picture")
        if (s.subHeightC * (s.confWin[2] + s.confWin[3]) >= s.height) hevcError("conf_win_top_offset and conf_win_bottom_offset leave no picture")
    }
    s.bitDepthY = r.ue("bit_depth_luma_minus8", 0, 8) + 8
    s.bitDepthC = r.ue("bit_depth_chroma_minus8", 0, 8) + 8
    s.log2MaxPocLsb = r.ue("log2_max_pic_order_cnt_lsb_minus4", 0, 12) + 4
    val orderingInfo = r.flag("sps_sub_layer_ordering_info_present_flag")
    for (i in (if (orderingInfo) 0 else s.maxSubLayersMinus1)..s.maxSubLayersMinus1) {
        // MaxDpbSize of A.4.2 is at most 16 pictures.
        s.maxDecPicBufferingMinus1[i] = r.ue("sps_max_dec_pic_buffering_minus1", if (i > 0 && orderingInfo) s.maxDecPicBufferingMinus1[i - 1] else 0, 15)
        s.maxNumReorderPics[i] = r.ue("sps_max_num_reorder_pics", if (i > 0 && orderingInfo) s.maxNumReorderPics[i - 1] else 0, s.maxDecPicBufferingMinus1[i])
        s.maxLatencyIncreasePlus1[i] = r.ueLong("sps_max_latency_increase_plus1").also { BitReader.check("sps_max_latency_increase_plus1", it, 0, 0xFFFFFFFEL) }
    }
    if (!orderingInfo) for (i in 0 until s.maxSubLayersMinus1) {
        s.maxDecPicBufferingMinus1[i] = s.maxDecPicBufferingMinus1[s.maxSubLayersMinus1]
        s.maxNumReorderPics[i] = s.maxNumReorderPics[s.maxSubLayersMinus1]
        s.maxLatencyIncreasePlus1[i] = s.maxLatencyIncreasePlus1[s.maxSubLayersMinus1]
    }
    // A coding block is 8 to 64 samples (7.4.3.2.1 with A.4.1, whose profiles allow CTBs of 16 to 64).
    s.minCbLog2 = r.ue("log2_min_luma_coding_block_size_minus3", 0, 3) + 3
    s.ctbLog2 = s.minCbLog2 + r.ue("log2_diff_max_min_luma_coding_block_size", 0, 6 - s.minCbLog2)
    s.minTbLog2 = r.ue("log2_min_luma_transform_block_size_minus2", 0, s.minCbLog2 - 3) + 2
    s.maxTbLog2 = s.minTbLog2 + r.ue("log2_diff_max_min_luma_transform_block_size", 0, minOf(s.ctbLog2, 5) - s.minTbLog2)
    if (s.width % (1 shl s.minCbLog2) != 0) hevcError("pic_width_in_luma_samples ${s.width} is no multiple of MinCbSizeY")
    if (s.height % (1 shl s.minCbLog2) != 0) hevcError("pic_height_in_luma_samples ${s.height} is no multiple of MinCbSizeY")
    s.maxTransformHierarchyDepthInter = r.ue("max_transform_hierarchy_depth_inter", 0, s.ctbLog2 - s.minTbLog2)
    s.maxTransformHierarchyDepthIntra = r.ue("max_transform_hierarchy_depth_intra", 0, s.ctbLog2 - s.minTbLog2)
    s.scalingListEnabled = r.flag("scaling_list_enabled_flag")
    if (s.scalingListEnabled) {
        s.scalingLists = if (r.flag("sps_scaling_list_data_present_flag")) readScalingLists(r) else ScalingLists.DEFAULT
    }
    s.ampEnabled = r.flag("amp_enabled_flag")
    s.saoEnabled = r.flag("sample_adaptive_offset_enabled_flag")
    s.pcmEnabled = r.flag("pcm_enabled_flag")
    if (s.pcmEnabled) {
        s.pcmBitDepthY = r.u(4, "pcm_sample_bit_depth_luma_minus1", 0, s.bitDepthY - 1) + 1
        // 7.4.3.2.1 has a decoder ignore the chroma PCM depth of a picture without chroma arrays.
        s.pcmBitDepthC = r.u(4, "pcm_sample_bit_depth_chroma_minus1", 0, if (s.chromaArrayType == 0) 15 else s.bitDepthC - 1) + 1
        s.log2MinPcmCb = r.ue("log2_min_pcm_luma_coding_block_size_minus3", minOf(s.minCbLog2, 5) - 3, minOf(s.ctbLog2, 5) - 3) + 3
        s.log2MaxPcmCb = s.log2MinPcmCb + r.ue("log2_diff_max_min_pcm_luma_coding_block_size", 0, minOf(s.ctbLog2, 5) - s.log2MinPcmCb)
        s.pcmLoopFilterDisabled = r.flag("pcm_loop_filter_disabled_flag")
    }
    val numRps = r.ue("num_short_term_ref_pic_sets", 0, 64)
    val rps = ArrayList<ShortTermRps>(numRps)
    val dpb = s.maxDecPicBufferingMinus1[s.maxSubLayersMinus1]
    for (i in 0 until numRps) rps += readShortTermRps(r, i, rps, numRps, dpb)
    s.shortTermRps = rps
    s.longTermRefPicsPresent = r.flag("long_term_ref_pics_present_flag")
    if (s.longTermRefPicsPresent) {
        val n = r.ue("num_long_term_ref_pics_sps", 0, 32)
        s.ltRefPicPocLsb = IntArray(n)
        s.usedByCurrPicLt = BooleanArray(n)
        for (i in 0 until n) {
            s.ltRefPicPocLsb[i] = r.u(s.log2MaxPocLsb, "lt_ref_pic_poc_lsb_sps")
            s.usedByCurrPicLt[i] = r.flag("used_by_curr_pic_lt_sps_flag")
        }
    }
    s.temporalMvpEnabled = r.flag("sps_temporal_mvp_enabled_flag")
    s.strongIntraSmoothing = r.flag("strong_intra_smoothing_enabled_flag")
    if (r.flag("vui_parameters_present_flag")) s.vui = readVui(r, s.maxSubLayersMinus1)
    var range = false
    var scc = false
    var ext4 = 0
    if (r.flag("sps_extension_present_flag")) {
        range = r.flag("sps_range_extension_flag")
        s.multilayerExtension = r.flag("sps_multilayer_extension_flag")
        s.extension3d = r.flag("sps_3d_extension_flag")
        scc = r.flag("sps_scc_extension_flag")
        ext4 = r.u(4, "sps_extension_4bits")
    }
    if (range) {
        s.transformSkipRotation = r.flag("transform_skip_rotation_enabled_flag")
        s.transformSkipContext = r.flag("transform_skip_context_enabled_flag")
        s.implicitRdpcm = r.flag("implicit_rdpcm_enabled_flag")
        s.explicitRdpcm = r.flag("explicit_rdpcm_enabled_flag")
        s.extendedPrecision = r.flag("extended_precision_processing_flag")
        s.intraSmoothingDisabled = r.flag("intra_smoothing_disabled_flag")
        s.highPrecisionOffsets = r.flag("high_precision_offsets_enabled_flag")
        s.persistentRiceAdaptation = r.flag("persistent_rice_adaptation_enabled_flag")
        s.cabacBypassAlignment = r.flag("cabac_bypass_alignment_enabled_flag")
    }
    // sps_multilayer_extension( ), F.7.3.2.2.4: one flag that only inter-layer prediction uses.
    if (s.multilayerExtension) r.flag("inter_view_mv_vert_constraint_flag")
    if (s.extension3d) {
        // Annex I's extension sits before the SCC one; without the SCC one, the rest is for other layers.
        if (scc) throw UnsupportedImageException("HEVC: sps_3d_extension_flag with sps_scc_extension_flag is not supported")
        r.skipToStopBit()
    } else {
        if (scc) readSpsScc(r, s)
        if (ext4 != 0) r.skipToStopBit()
    }
    r.trailingBits("SPS")
    return s
}

/** sps_scc_extension( ), 7.3.2.2.3, with the palette limits of A.3.7. */
private fun readSpsScc(r: BitReader, s: Sps) {
    s.currPicRefEnabled = r.flag("sps_curr_pic_ref_enabled_flag")
    s.paletteMode = r.flag("palette_mode_enabled_flag")
    if (s.paletteMode) {
        s.paletteMaxSize = r.ue("palette_max_size", 0, 64)
        s.paletteMaxPredictorSize = s.paletteMaxSize + r.ue("delta_palette_max_predictor_size", 0, 128 - s.paletteMaxSize)
        if (r.flag("sps_palette_predictor_initializers_present_flag")) {
            val n = r.ue("sps_num_palette_predictor_initializers_minus1", 0, s.paletteMaxPredictorSize - 1) + 1
            val comps = if (s.chromaFormatIdc == 0) 1 else 3
            s.palettePredictorInitializers = Array(comps) { comp ->
                IntArray(n) { r.u(if (comp == 0) s.bitDepthY else s.bitDepthC, "sps_palette_predictor_initializer") }
            }
        }
    }
    s.motionVectorResolutionControlIdc = r.u(2, "motion_vector_resolution_control_idc", 0, 2)
    s.intraBoundaryFilteringDisabled = r.flag("intra_boundary_filtering_disabled_flag")
}

/**
 * A picture parameter set (7.3.2.3). Its syntax does not depend on the SPS, so it is read alone;
 * the ranges that depend on the SPS are checked by [checkAgainst] when a slice activates both.
 */
internal class Pps {
    var id = 0
    var spsId = 0
    var dependentSliceSegmentsEnabled = false
    var outputFlagPresent = false
    var numExtraSliceHeaderBits = 0
    var signDataHiding = false
    var cabacInitPresent = false
    var numRefIdxL0DefaultActiveMinus1 = 0
    var numRefIdxL1DefaultActiveMinus1 = 0
    var initQpMinus26 = 0
    var constrainedIntraPred = false
    var transformSkipEnabled = false
    var cuQpDeltaEnabled = false
    var diffCuQpDeltaDepth = 0
    var cbQpOffset = 0
    var crQpOffset = 0
    var sliceChromaQpOffsetsPresent = false
    var weightedPred = false
    var weightedBipred = false
    var transquantBypassEnabled = false
    var tilesEnabled = false
    var entropyCodingSync = false
    var numTileColumnsMinus1 = 0
    var numTileRowsMinus1 = 0
    var uniformSpacing = true
    var columnWidthMinus1 = IntArray(0)
    var rowHeightMinus1 = IntArray(0)
    var loopFilterAcrossTiles = true
    var loopFilterAcrossSlices = false
    var deblockingFilterControlPresent = false
    var deblockingFilterOverrideEnabled = false
    var deblockingFilterDisabled = false
    var betaOffsetDiv2 = 0
    var tcOffsetDiv2 = 0
    /** The lists of the PPS, or null when the PPS carries none and the SPS's apply. */
    var scalingLists: ScalingLists? = null
    var listsModificationPresent = false
    var log2ParallelMergeLevel = 2
    var sliceSegmentHeaderExtensionPresent = false

    // pps_range_extension( ), 7.3.2.3.2.
    var log2MaxTransformSkipSize = 2
    var crossComponentPrediction = false
    var chromaQpOffsetListEnabled = false
    var diffCuChromaQpOffsetDepth = 0
    var cbQpOffsetList = IntArray(0)
    var crQpOffsetList = IntArray(0)
    var log2SaoOffsetScaleLuma = 0
    var log2SaoOffsetScaleChroma = 0

    // pps_scc_extension( ), 7.3.2.3.3.
    var currPicRefEnabled = false
    var residualAdaptiveColourTransform = false
    var sliceActQpOffsetsPresent = false
    var actQpOffsetY = 0
    var actQpOffsetCb = 0
    var actQpOffsetCr = 0
    var palettePredictorInitializersPresent = false
    var monochromePalette = false
    var lumaBitDepthEntry = 8
    var chromaBitDepthEntry = 8
    var palettePredictorInitializers: Array<IntArray>? = null

    var multilayerExtension = false
    var extension3d = false

    /**
     * TwoVersionsOfCurrDecPicFlag, 7-40: a picture that refers to itself and is loop filtered predicts from its
     * samples before the loop filters, which the decoded picture buffer keeps as a picture of its own.
     */
    fun twoVersionsOfCurrDecPic(s: Sps): Boolean =
        currPicRefEnabled && (s.saoEnabled || !deblockingFilterDisabled || deblockingFilterOverrideEnabled)

    /**
     * Checks the ranges that 7.4.3.3 states in terms of the active SPS [s]. Throws an
     * ImageDecodeException naming the field.
     */
    fun checkAgainst(s: Sps) {
        BitReader.check("init_qp_minus26", initQpMinus26.toLong(), -(26L + s.qpBdOffsetY), 25)
        BitReader.check("diff_cu_qp_delta_depth", diffCuQpDeltaDepth.toLong(), 0, (s.ctbLog2 - s.minCbLog2).toLong())
        BitReader.check("log2_parallel_merge_level_minus2", log2ParallelMergeLevel - 2L, 0, s.ctbLog2 - 2L)
        if (tilesEnabled) {
            BitReader.check("num_tile_columns_minus1", numTileColumnsMinus1.toLong(), 0, s.picWidthInCtbs - 1L)
            BitReader.check("num_tile_rows_minus1", numTileRowsMinus1.toLong(), 0, s.picHeightInCtbs - 1L)
            if (!uniformSpacing) {
                // 6-3 and 6-4 leave the last column and row the rest, which must not be empty.
                if (columnWidthMinus1.sumOf { it + 1L } >= s.picWidthInCtbs) hevcError("column_width_minus1 passes the picture width")
                if (rowHeightMinus1.sumOf { it + 1L } >= s.picHeightInCtbs) hevcError("row_height_minus1 passes the picture height")
            }
        }
        BitReader.check("log2_max_transform_skip_block_size_minus2", log2MaxTransformSkipSize - 2L, 0, s.maxTbLog2 - 2L)
        BitReader.check("diff_cu_chroma_qp_offset_depth", diffCuChromaQpOffsetDepth.toLong(), 0, (s.ctbLog2 - s.minCbLog2).toLong())
        BitReader.check("log2_sao_offset_scale_luma", log2SaoOffsetScaleLuma.toLong(), 0, maxOf(0, s.bitDepthY - 10).toLong())
        BitReader.check("log2_sao_offset_scale_chroma", log2SaoOffsetScaleChroma.toLong(), 0, maxOf(0, s.bitDepthC - 10).toLong())
        if (crossComponentPrediction && s.chromaArrayType != 3) hevcError("cross_component_prediction_enabled_flag is 1 without 4:4:4")
        if (residualAdaptiveColourTransform && s.chromaArrayType != 3) hevcError("residual_adaptive_colour_transform_enabled_flag is 1 without 4:4:4")
        if (currPicRefEnabled && !s.currPicRefEnabled) hevcError("pps_curr_pic_ref_enabled_flag is 1 where sps_curr_pic_ref_enabled_flag is 0")
        if (scalingLists != null && !s.scalingListEnabled) hevcError("pps_scaling_list_data_present_flag is 1 where scaling_list_enabled_flag is 0")
        palettePredictorInitializers?.takeIf { it[0].isNotEmpty() }?.let { p ->
            BitReader.check("pps_num_palette_predictor_initializers", p[0].size.toLong(), 0, s.paletteMaxPredictorSize.toLong())
            if (lumaBitDepthEntry != s.bitDepthY) hevcError("luma_bit_depth_entry_minus8 differs from bit_depth_luma_minus8")
            if (!monochromePalette && chromaBitDepthEntry != s.bitDepthC) hevcError("chroma_bit_depth_entry_minus8 differs from bit_depth_chroma_minus8")
            if (monochromePalette != (s.chromaFormatIdc == 0)) hevcError("monochrome_palette_flag does not match chroma_format_idc")
        }
    }
}

/** Bounds of a tile column and row count that hold for any SPS: a CTB is at least 8 samples. */
private const val MAX_CTBS_PER_SIDE = MAX_SIDE / 8

/** pic_parameter_set_rbsp( ), 7.3.2.3, of a NAL unit with nuh_layer_id 0, from the RBSP after its header. */
internal fun readPps(r: BitReader): Pps {
    val p = Pps()
    p.id = r.ue("pps_pic_parameter_set_id", 0, 63)
    p.spsId = r.ue("pps_seq_parameter_set_id", 0, 15)
    p.dependentSliceSegmentsEnabled = r.flag("dependent_slice_segments_enabled_flag")
    p.outputFlagPresent = r.flag("output_flag_present_flag")
    // 7.4.3.3.1 has a decoder allow any value of num_extra_slice_header_bits.
    p.numExtraSliceHeaderBits = r.u(3, "num_extra_slice_header_bits")
    p.signDataHiding = r.flag("sign_data_hiding_enabled_flag")
    p.cabacInitPresent = r.flag("cabac_init_present_flag")
    p.numRefIdxL0DefaultActiveMinus1 = r.ue("num_ref_idx_l0_default_active_minus1", 0, 14)
    p.numRefIdxL1DefaultActiveMinus1 = r.ue("num_ref_idx_l1_default_active_minus1", 0, 14)
    // The bound of 7.4.3.3.1 depends on the SPS's depth; this is the widest, at 16 bits.
    p.initQpMinus26 = r.se("init_qp_minus26", -(26 + 48), 25)
    p.constrainedIntraPred = r.flag("constrained_intra_pred_flag")
    p.transformSkipEnabled = r.flag("transform_skip_enabled_flag")
    p.cuQpDeltaEnabled = r.flag("cu_qp_delta_enabled_flag")
    if (p.cuQpDeltaEnabled) p.diffCuQpDeltaDepth = r.ue("diff_cu_qp_delta_depth", 0, 3)
    p.cbQpOffset = r.se("pps_cb_qp_offset", -12, 12)
    p.crQpOffset = r.se("pps_cr_qp_offset", -12, 12)
    p.sliceChromaQpOffsetsPresent = r.flag("pps_slice_chroma_qp_offsets_present_flag")
    p.weightedPred = r.flag("weighted_pred_flag")
    p.weightedBipred = r.flag("weighted_bipred_flag")
    p.transquantBypassEnabled = r.flag("transquant_bypass_enabled_flag")
    p.tilesEnabled = r.flag("tiles_enabled_flag")
    p.entropyCodingSync = r.flag("entropy_coding_sync_enabled_flag")
    if (p.tilesEnabled) {
        p.numTileColumnsMinus1 = r.ue("num_tile_columns_minus1", 0, MAX_CTBS_PER_SIDE - 1)
        p.numTileRowsMinus1 = r.ue("num_tile_rows_minus1", 0, MAX_CTBS_PER_SIDE - 1)
        if (p.numTileColumnsMinus1 == 0 && p.numTileRowsMinus1 == 0) hevcError("num_tile_columns_minus1 and num_tile_rows_minus1 are both 0")
        p.uniformSpacing = r.flag("uniform_spacing_flag")
        if (!p.uniformSpacing) {
            p.columnWidthMinus1 = IntArray(p.numTileColumnsMinus1) { r.ue("column_width_minus1", 0, MAX_CTBS_PER_SIDE - 1) }
            p.rowHeightMinus1 = IntArray(p.numTileRowsMinus1) { r.ue("row_height_minus1", 0, MAX_CTBS_PER_SIDE - 1) }
        }
        p.loopFilterAcrossTiles = r.flag("loop_filter_across_tiles_enabled_flag")
    }
    p.loopFilterAcrossSlices = r.flag("pps_loop_filter_across_slices_enabled_flag")
    p.deblockingFilterControlPresent = r.flag("deblocking_filter_control_present_flag")
    if (p.deblockingFilterControlPresent) {
        p.deblockingFilterOverrideEnabled = r.flag("deblocking_filter_override_enabled_flag")
        p.deblockingFilterDisabled = r.flag("pps_deblocking_filter_disabled_flag")
        if (!p.deblockingFilterDisabled) {
            p.betaOffsetDiv2 = r.se("pps_beta_offset_div2", -6, 6)
            p.tcOffsetDiv2 = r.se("pps_tc_offset_div2", -6, 6)
        }
    }
    if (r.flag("pps_scaling_list_data_present_flag")) p.scalingLists = readScalingLists(r)
    p.listsModificationPresent = r.flag("lists_modification_present_flag")
    p.log2ParallelMergeLevel = r.ue("log2_parallel_merge_level_minus2", 0, 4) + 2
    p.sliceSegmentHeaderExtensionPresent = r.flag("slice_segment_header_extension_present_flag")
    var range = false
    var scc = false
    var ext4 = 0
    if (r.flag("pps_extension_present_flag")) {
        range = r.flag("pps_range_extension_flag")
        p.multilayerExtension = r.flag("pps_multilayer_extension_flag")
        p.extension3d = r.flag("pps_3d_extension_flag")
        scc = r.flag("pps_scc_extension_flag")
        ext4 = r.u(4, "pps_extension_4bits")
    }
    if (range) readPpsRange(r, p)
    if (p.multilayerExtension || p.extension3d) {
        // Annex F and I extensions describe other layers and sit before the SCC one.
        if (scc) throw UnsupportedImageException("HEVC: pps_scc_extension_flag after a multilayer or 3D extension is not supported")
        r.skipToStopBit()
    } else {
        if (scc) readPpsScc(r, p)
        if (ext4 != 0) r.skipToStopBit()
    }
    r.trailingBits("PPS")
    return p
}

/** pps_range_extension( ), 7.3.2.3.2. The bounds that depend on the SPS are checked on activation. */
private fun readPpsRange(r: BitReader, p: Pps) {
    if (p.transformSkipEnabled) p.log2MaxTransformSkipSize = r.ue("log2_max_transform_skip_block_size_minus2", 0, 3) + 2
    p.crossComponentPrediction = r.flag("cross_component_prediction_enabled_flag")
    p.chromaQpOffsetListEnabled = r.flag("chroma_qp_offset_list_enabled_flag")
    if (p.chromaQpOffsetListEnabled) {
        p.diffCuChromaQpOffsetDepth = r.ue("diff_cu_chroma_qp_offset_depth", 0, 3)
        val n = r.ue("chroma_qp_offset_list_len_minus1", 0, 5) + 1
        p.cbQpOffsetList = IntArray(n)
        p.crQpOffsetList = IntArray(n)
        for (i in 0 until n) {
            p.cbQpOffsetList[i] = r.se("cb_qp_offset_list", -12, 12)
            p.crQpOffsetList[i] = r.se("cr_qp_offset_list", -12, 12)
        }
    }
    p.log2SaoOffsetScaleLuma = r.ue("log2_sao_offset_scale_luma", 0, 6)
    p.log2SaoOffsetScaleChroma = r.ue("log2_sao_offset_scale_chroma", 0, 6)
}

/** pps_scc_extension( ), 7.3.2.3.3. */
private fun readPpsScc(r: BitReader, p: Pps) {
    p.currPicRefEnabled = r.flag("pps_curr_pic_ref_enabled_flag")
    p.residualAdaptiveColourTransform = r.flag("residual_adaptive_colour_transform_enabled_flag")
    if (p.residualAdaptiveColourTransform) {
        p.sliceActQpOffsetsPresent = r.flag("pps_slice_act_qp_offsets_present_flag")
        // 7.4.3.3.3: the offsets themselves lie in -12..12.
        p.actQpOffsetY = r.se("pps_act_y_qp_offset_plus5", -7, 17) - 5
        p.actQpOffsetCb = r.se("pps_act_cb_qp_offset_plus5", -7, 17) - 5
        p.actQpOffsetCr = r.se("pps_act_cr_qp_offset_plus3", -9, 15) - 3
    }
    p.palettePredictorInitializersPresent = r.flag("pps_palette_predictor_initializers_present_flag")
    if (p.palettePredictorInitializersPresent) {
        // PaletteMaxPredictorSize is at most 128 (A.3.7); the exact bound is checked on activation.
        val n = r.ue("pps_num_palette_predictor_initializers", 0, 128)
        if (n > 0) {
            p.monochromePalette = r.flag("monochrome_palette_flag")
            p.lumaBitDepthEntry = r.ue("luma_bit_depth_entry_minus8", 0, 8) + 8
            if (!p.monochromePalette) p.chromaBitDepthEntry = r.ue("chroma_bit_depth_entry_minus8", 0, 8) + 8
            val comps = if (p.monochromePalette) 1 else 3
            p.palettePredictorInitializers = Array(comps) { comp ->
                IntArray(n) { r.u(if (comp == 0) p.lumaBitDepthEntry else p.chromaBitDepthEntry, "pps_palette_predictor_initializer") }
            }
        } else {
            p.palettePredictorInitializers = arrayOf(IntArray(0))
        }
    }
}
