package io.github.yuroyami.imagekodec.heic

/**
 * The profile, tier and level an encoded picture signals (7.3.3, A.3, A.4): the lowest profile whose
 * constraints the stream meets, and the lowest level whose picture size holds it.
 */
internal class HevcProfile(
    /** general_profile_idc. */
    val profileIdc: Int,
    /** general_profile_compatibility_flag[ j ] in bit 31 - j. */
    val compatibility: Int,
    /** The nine constraint flags of the format range extensions profiles (Table A.2), in syntax order, for profile 4. */
    val rangeFlags: IntArray?,
    /** general_one_picture_only_constraint_flag where the Main 10 syntax carries it. */
    val onePictureOnly: Boolean,
    /** general_level_idc: 30 times the level number. */
    val levelIdc: Int,
    /** The profile's name, for messages and tests. */
    val name: String,
) {
    companion object {
        /**
         * MaxLumaPs of each level of Table A.8 and its general_level_idc. Levels 1 to 4.1 do not change
         * MaxLumaPs between sublevels, so the first of each size is the lowest.
         */
        private val LEVELS = listOf(
            30 to 36_864L, 60 to 122_880L, 63 to 245_760L, 90 to 552_960L, 93 to 983_040L,
            120 to 2_228_224L, 150 to 8_912_896L, 180 to 35_651_584L, 189 to 80_216_064L, 210 to 142_606_336L,
        )

        /** The largest picture of any level but 8.5, which has no limits a decoder can rely on. */
        val MAX_LUMA_PS: Long = LEVELS.last().second

        /**
         * The lowest level of Table A.8 for a coded picture of [width] by [height] luma samples: A.4.1 a) bounds
         * the area by MaxLumaPs, and b) and c) each side by Sqrt( MaxLumaPs * 8 ). Null when no level holds it.
         */
        fun level(width: Int, height: Int, dpbSize: Int = 1): Int? {
            val area = width.toLong() * height
            for ((idc, maxLumaPs) in LEVELS) {
                val side = maxLumaPs * 8
                if (area <= maxLumaPs && width.toLong() * width <= side && height.toLong() * height <= side && dpbSize <= maxDpbSize(area, maxLumaPs)) return idc
            }
            return null
        }

        /**
         * MaxDpbSize of A-2 for a picture of [area] luma samples at a level of [maxLumaPs]: maxDpbPicBuf, 6, grows
         * to 8, 12 and 16 as the picture takes three quarters, half or a quarter of the level's largest.
         */
        private fun maxDpbSize(area: Long, maxLumaPs: Long): Int = when {
            area <= maxLumaPs shr 2 -> 16
            area <= maxLumaPs shr 1 -> 12
            area <= (3 * maxLumaPs) shr 2 -> 8
            else -> 6
        }

        /**
         * The profile for a picture of [chromaFormat] at [bitDepthY] and [bitDepthC] bits at [levelIdc].
         * 8-bit 4:2:0 is Main Still Picture, which A.3.4 NOTE asks to mark compatible with Main and Main 10
         * too. 4:2:0 of up to 10 bits is Main 10, with general_one_picture_only_constraint_flag making it Main
         * 10 Still Picture (A.3.3). Every other format takes the format range extensions profile of Table A.2
         * with the tightest flags: Monochrome, Monochrome 10 or 12, Main 12, Main 4:2:2 10 or 12, or Main
         * 4:4:4, 4:4:4 10 or 4:4:4 12, each with general_lower_bit_rate_constraint_flag 1.
         */
        fun choose(chromaFormat: Int, bitDepthY: Int, bitDepthC: Int, levelIdc: Int, sequence: Boolean = false): HevcProfile {
            val depth = maxOf(bitDepthY, bitDepthC)
            fun compat(vararg j: Int): Int = j.fold(0) { a, k -> a or (1 shl (31 - k)) }
            // A sequence of several pictures takes Main (A.3.2), marked compatible with Main 10, or Main 10 (A.3.3).
            if (sequence && chromaFormat == 1 && depth == 8) return HevcProfile(1, compat(1, 2), null, false, levelIdc, "Main")
            if (sequence && chromaFormat == 1 && depth <= 10) return HevcProfile(2, compat(2), null, false, levelIdc, "Main 10")
            if (chromaFormat == 1 && depth == 8) return HevcProfile(3, compat(1, 2, 3), null, true, levelIdc, "Main Still Picture")
            if (chromaFormat == 1 && depth <= 10) return HevcProfile(2, compat(2), null, true, levelIdc, "Main 10 Still Picture")
            val max12 = 1
            val max10 = if (depth <= 10) 1 else 0
            val max8 = if (depth <= 8) 1 else 0
            val (name, flags) = when (chromaFormat) {
                0 -> (if (depth <= 8) "Monochrome" else if (depth <= 10) "Monochrome 10" else "Monochrome 12") to intArrayOf(max12, max10, max8, 1, 1, 1, 0, 0, 1)
                1 -> "Main 12" to intArrayOf(1, 0, 0, 1, 1, 0, 0, 0, 1)
                // Table A.2 has no 8-bit 4:2:2 profile: Main 4:2:2 10 holds it.
                2 -> (if (depth <= 10) "Main 4:2:2 10" else "Main 4:2:2 12") to intArrayOf(max12, max10, 0, 1, 0, 0, 0, 0, 1)
                else -> (if (depth <= 8) "Main 4:4:4" else if (depth <= 10) "Main 4:4:4 10" else "Main 4:4:4 12") to intArrayOf(max12, max10, max8, 0, 0, 0, 0, 0, 1)
            }
            return HevcProfile(4, compat(4), flags, false, levelIdc, name)
        }
    }
}

/** What the parameter sets of an encoded picture say: its size, format, tools and colour. */
internal class HevcStreamConfig(
    /** pic_width_in_luma_samples and pic_height_in_luma_samples: the picture padded to a multiple of MinCbSizeY. */
    val width: Int,
    val height: Int,
    /** conf_win_right_offset and conf_win_bottom_offset, in chroma units (7-19). */
    val confRight: Int,
    val confBottom: Int,
    val chromaFormat: Int,
    val bitDepthY: Int,
    val bitDepthC: Int,
    val profile: HevcProfile,
    val ctbLog2: Int,
    val minCbLog2: Int,
    val maxTbLog2: Int,
    val maxTransformHierarchyDepthIntra: Int,
    val sao: Boolean,
    val transformSkip: Boolean,
    val signDataHiding: Boolean,
    val transquantBypass: Boolean,
    val deblockingDisabled: Boolean,
    val cuQpDeltaDepth: Int,
    val initQp: Int,
    val fullRange: Boolean,
    /** colour_primaries, transfer_characteristics and matrix_coeffs, or null for none. */
    val colour: IntArray?,
    /** sps_max_dec_pic_buffering_minus1 and sps_max_num_reorder_pics, the same in the VPS (7.4.3.1). */
    val maxDecPicBufferingMinus1: Int = 0,
    val maxNumReorder: Int = 0,
    /** log2_max_pic_order_cnt_lsb_minus4 + 4. */
    val log2MaxPocLsb: Int = 4,
    /** The short-term reference picture sets of the SPS, each coded on its own (no inter RPS prediction). */
    val shortTermRps: List<ShortTermRps> = emptyList(),
    /** sps_temporal_mvp_enabled_flag. */
    val temporalMvp: Boolean = false,
    /** amp_enabled_flag: the asymmetric partitions of inter coding units. */
    val amp: Boolean = false,
    val maxTransformHierarchyDepthInter: Int = 0,
)

/** Writes the NAL units of an encoded picture: VPS, SPS, PPS, the slice segment header and the picture hash SEI. */
internal object HevcHeaderWriter {

    private fun nal(type: Int, body: (BitWriter) -> Unit): ByteArray {
        val w = BitWriter(64)
        // nal_unit_header( ), 7.3.1.2: forbidden_zero_bit, nal_unit_type, nuh_layer_id 0, nuh_temporal_id_plus1 1.
        w.u(1, 0)
        w.u(6, type)
        w.u(6, 0)
        w.u(3, 1)
        body(w)
        return w.toByteArray()
    }

    /** profile_tier_level( 1, 0 ), 7.3.3: the general profile, tier and level of [p]. */
    fun profileTierLevel(w: BitWriter, p: HevcProfile) {
        w.u(2, 0)
        w.u(1, 0)
        w.u(5, p.profileIdc)
        w.u(32, p.compatibility)
        // progressive_source_flag, interlaced_source_flag, non_packed_constraint_flag, frame_only_constraint_flag.
        w.u(1, 1)
        w.u(1, 0)
        w.u(1, 0)
        w.u(1, 1)
        val flags = p.rangeFlags
        if (flags != null) {
            for (f in flags) w.u(1, f)
            // general_reserved_zero_34bits: no profile here sets general_max_14bit_constraint_flag's syntax.
            w.u(32, 0)
            w.u(2, 0)
        } else if (p.compatibility and (1 shl (31 - 2)) != 0 || p.profileIdc == 2) {
            w.u(7, 0)
            w.flag(p.onePictureOnly)
            w.u(32, 0)
            w.u(3, 0)
        } else {
            w.u(32, 0)
            w.u(11, 0)
        }
        // general_inbld_flag: the picture is coded on its own.
        w.u(1, 0)
        w.u(8, p.levelIdc)
    }

    /** video_parameter_set_rbsp( ), 7.3.2.1, of one layer and one sub-layer. */
    fun vps(c: HevcStreamConfig): ByteArray = nal(NalType.VPS) { w ->
        w.u(4, 0)
        w.u(1, 1)
        w.u(1, 1)
        w.u(6, 0)
        w.u(3, 0)
        w.u(1, 1)
        w.u(16, 0xFFFF)
        profileTierLevel(w, c.profile)
        w.u(1, 1)
        // vps_max_dec_pic_buffering_minus1, vps_max_num_reorder_pics and vps_max_latency_increase_plus1 as the SPS
        // has them: a still picture holds one picture and reorders none, as the still profiles ask.
        w.ue(c.maxDecPicBufferingMinus1)
        w.ue(c.maxNumReorder)
        w.ue(0)
        w.u(6, 0)
        w.ue(0)
        w.u(1, 0)
        w.u(1, 0)
        w.trailingBits()
    }

    /** seq_parameter_set_rbsp( ), 7.3.2.2, with a VUI when the picture has a colour description or full range. */
    fun sps(c: HevcStreamConfig): ByteArray = nal(NalType.SPS) { w ->
        w.u(4, 0)
        w.u(3, 0)
        w.u(1, 1)
        profileTierLevel(w, c.profile)
        w.ue(0)
        w.ue(c.chromaFormat)
        if (c.chromaFormat == 3) w.u(1, 0)
        w.ue(c.width)
        w.ue(c.height)
        val window = c.confRight != 0 || c.confBottom != 0
        w.flag(window)
        if (window) {
            w.ue(0)
            w.ue(c.confRight)
            w.ue(0)
            w.ue(c.confBottom)
        }
        w.ue(c.bitDepthY - 8)
        w.ue(c.bitDepthC - 8)
        // log2_max_pic_order_cnt_lsb_minus4: an IDR picture codes no picture order count.
        w.ue(c.log2MaxPocLsb - 4)
        w.u(1, 1)
        w.ue(c.maxDecPicBufferingMinus1)
        w.ue(c.maxNumReorder)
        // sps_max_latency_increase_plus1 0: no bound on the latency besides the reorder count.
        w.ue(0)
        w.ue(c.minCbLog2 - 3)
        w.ue(c.ctbLog2 - c.minCbLog2)
        w.ue(0)
        w.ue(c.maxTbLog2 - 2)
        // max_transform_hierarchy_depth_inter and _intra.
        w.ue(c.maxTransformHierarchyDepthInter)
        w.ue(c.maxTransformHierarchyDepthIntra)
        // scaling_list_enabled_flag, amp_enabled_flag.
        w.u(1, 0)
        w.flag(c.amp)
        w.flag(c.sao)
        // pcm_enabled_flag, num_short_term_ref_pic_sets and the sets, long_term_ref_pics_present_flag,
        // sps_temporal_mvp_enabled_flag.
        w.u(1, 0)
        w.ue(c.shortTermRps.size)
        for ((i, rps) in c.shortTermRps.withIndex()) shortTermRefPicSet(w, i, rps)
        w.u(1, 0)
        w.flag(c.temporalMvp)
        // strong_intra_smoothing_enabled_flag.
        w.u(1, 1)
        val vui = c.fullRange || c.colour != null
        w.flag(vui)
        if (vui) vui(w, c)
        // sps_extension_present_flag.
        w.u(1, 0)
        w.trailingBits()
    }

    /** vui_parameters( ), E.2.1, with only video_signal_type_present_flag set. */
    private fun vui(w: BitWriter, c: HevcStreamConfig) {
        w.u(1, 0)
        w.u(1, 0)
        w.u(1, 1)
        // video_format 5: unspecified (Table E.2).
        w.u(3, 5)
        w.flag(c.fullRange)
        val colour = c.colour
        w.flag(colour != null)
        if (colour != null) {
            w.u(8, colour[0])
            w.u(8, colour[1])
            w.u(8, colour[2])
        }
        // chroma_loc_info_present_flag, neutral_chroma_indication_flag, field_seq_flag,
        // frame_field_info_present_flag, default_display_window_flag, vui_timing_info_present_flag,
        // bitstream_restriction_flag.
        w.u(7, 0)
    }

    /** pic_parameter_set_rbsp( ), 7.3.2.3. */
    fun pps(c: HevcStreamConfig): ByteArray = nal(NalType.PPS) { w ->
        w.ue(0)
        w.ue(0)
        // dependent_slice_segments_enabled_flag, output_flag_present_flag, num_extra_slice_header_bits.
        w.u(1, 0)
        w.u(1, 0)
        w.u(3, 0)
        w.flag(c.signDataHiding)
        // cabac_init_present_flag, num_ref_idx_l0 and l1_default_active_minus1.
        w.u(1, 0)
        w.ue(0)
        w.ue(0)
        w.se(c.initQp - 26)
        // constrained_intra_pred_flag.
        w.u(1, 0)
        w.flag(c.transformSkip)
        w.flag(c.cuQpDeltaDepth >= 0)
        if (c.cuQpDeltaDepth >= 0) w.ue(c.cuQpDeltaDepth)
        // pps_cb_qp_offset, pps_cr_qp_offset, pps_slice_chroma_qp_offsets_present_flag,
        // weighted_pred_flag, weighted_bipred_flag.
        w.se(0)
        w.se(0)
        w.u(1, 0)
        w.u(1, 0)
        w.u(1, 0)
        w.flag(c.transquantBypass)
        // tiles_enabled_flag, entropy_coding_sync_enabled_flag, pps_loop_filter_across_slices_enabled_flag.
        w.u(1, 0)
        w.u(1, 0)
        w.u(1, 0)
        // deblocking_filter_control_present_flag, only to turn the filter off.
        w.flag(c.deblockingDisabled)
        if (c.deblockingDisabled) {
            w.u(1, 0)
            w.u(1, 1)
        }
        // pps_scaling_list_data_present_flag, lists_modification_present_flag,
        // log2_parallel_merge_level_minus2, slice_segment_header_extension_present_flag, pps_extension_present_flag.
        w.u(1, 0)
        w.u(1, 0)
        w.ue(0)
        w.u(1, 0)
        w.u(1, 0)
        w.trailingBits()
    }

    /**
     * st_ref_pic_set( [idx] ), 7.3.7, of [rps], never predicted from another set: inter_ref_pic_set_prediction_flag
     * 0 where it is coded, then each entry as its distance from the one before (7-61, 7-62).
     */
    fun shortTermRefPicSet(w: BitWriter, idx: Int, rps: ShortTermRps) {
        if (idx != 0) w.u(1, 0)
        w.ue(rps.numNegative)
        w.ue(rps.numPositive)
        var prev = 0
        for (i in 0 until rps.numNegative) {
            w.ue(prev - rps.deltaPocS0[i] - 1)
            w.flag(rps.usedS0[i])
            prev = rps.deltaPocS0[i]
        }
        prev = 0
        for (i in 0 until rps.numPositive) {
            w.ue(rps.deltaPocS1[i] - prev - 1)
            w.flag(rps.usedS1[i])
            prev = rps.deltaPocS1[i]
        }
    }

    /**
     * The NAL unit header and slice_segment_header( ) (7.3.6.1) of the one slice of a picture of a sequence, up to
     * and with byte_alignment( ): the I slice of an IDR picture ([idr]), or a slice of a TRAIL_R picture of picture
     * order count [poc] with the reference picture set [rps], the SPS's set [rpsIdx] or coded in the header when
     * [rpsIdx] is -1. A P or B slice has [numRefL0] and [numRefL1] active references, its temporal predictor from
     * the first picture of list 1 in a B slice and of list 0 in a P slice, and five merging candidates.
     */
    fun sequenceSliceHeader(
        c: HevcStreamConfig, sliceType: Int, idr: Boolean, poc: Int, rps: ShortTermRps?, rpsIdx: Int,
        numRefL0: Int, numRefL1: Int, saoLuma: Boolean, saoChroma: Boolean, qp: Int,
    ): ByteArray = nal(if (idr) NalType.IDR_N_LP else NalType.TRAIL_R) { w ->
        w.u(1, 1)
        // no_output_of_prior_pics_flag of an IRAP picture.
        if (idr) w.u(1, 0)
        w.ue(0)
        w.ue(sliceType)
        if (!idr) {
            w.u(c.log2MaxPocLsb, poc and ((1 shl c.log2MaxPocLsb) - 1))
            val count = c.shortTermRps.size
            // short_term_ref_pic_set_sps_flag, then the set or short_term_ref_pic_set_idx.
            w.flag(rpsIdx >= 0)
            if (rpsIdx < 0) shortTermRefPicSet(w, count, rps!!) else if (count > 1) w.u(ceilLog2(count), rpsIdx)
            // slice_temporal_mvp_enabled_flag.
            if (c.temporalMvp) w.u(1, 1)
        }
        if (c.sao) {
            w.flag(saoLuma)
            if (c.chromaFormat != 0) w.flag(saoChroma)
        }
        if (sliceType != SliceType.I) {
            val b = sliceType == SliceType.B
            // num_ref_idx_active_override_flag, against the PPS's default of one picture in each list.
            val override = numRefL0 != 1 || (b && numRefL1 != 1)
            w.flag(override)
            if (override) {
                w.ue(numRefL0 - 1)
                if (b) w.ue(numRefL1 - 1)
            }
            // mvd_l1_zero_flag.
            if (b) w.u(1, 0)
            if (c.temporalMvp) {
                // collocated_from_l0_flag 0 in a B slice: ColPic is RefPicList1[ 0 ], the nearest later picture.
                // collocated_ref_idx is 0, coded only where the list holds more than one picture.
                if (b) w.u(1, 0)
                if ((if (b) numRefL1 else numRefL0) > 1) w.ue(0)
            }
            // five_minus_max_num_merge_cand.
            w.ue(0)
        }
        w.se(qp - c.initQp)
        w.byteAlignment()
    }

    /**
     * The NAL unit header and slice_segment_header( ) (7.3.6.1) of the one I slice of an IDR picture, up to and
     * with byte_alignment( ), after which the slice segment data follows.
     */
    fun sliceHeader(c: HevcStreamConfig, saoLuma: Boolean, saoChroma: Boolean, qp: Int): ByteArray = nal(NalType.IDR_N_LP) { w ->
        w.u(1, 1)
        // no_output_of_prior_pics_flag, slice_pic_parameter_set_id.
        w.u(1, 0)
        w.ue(0)
        w.ue(SliceType.I)
        if (c.sao) {
            w.flag(saoLuma)
            if (c.chromaFormat != 0) w.flag(saoChroma)
        }
        w.se(qp - c.initQp)
        w.byteAlignment()
    }

    /** A suffix SEI NAL unit (7.3.2.4) of one decoded_picture_hash( ) message (D.2.20) of MD5 [hashes]. */
    fun pictureHash(hashes: List<ByteArray>): ByteArray = nal(NalType.SUFFIX_SEI) { w ->
        val size = 1 + 16 * hashes.size
        // last_payload_type_byte 132, last_payload_size_byte.
        w.u(8, 132)
        w.u(8, size)
        // hash_type 0: MD5.
        w.u(8, 0)
        for (h in hashes) w.bytes(h)
        w.trailingBits()
    }

    /**
     * The HEVCDecoderConfigurationRecord (ISO/IEC 14496-15, 8.3.2.1.2) of the parameter sets [vps], [sps] and
     * [pps] as RBSPs with their NAL headers: its profile fields are the very bits of the SPS's
     * profile_tier_level( ), and its format fields those of the SPS. NAL unit lengths take 4 bytes.
     */
    fun hvcC(c: HevcStreamConfig, vps: ByteArray, sps: ByteArray, pps: ByteArray): ByteArray {
        val ptl = BitWriter(16).also { profileTierLevel(it, c.profile) }.toByteArray()
        val w = BitWriter(64 + vps.size + sps.size + pps.size)
        w.u(8, 1)
        // general_profile_space to general_level_idc: the 12 bytes of profile_tier_level( 1, 0 ).
        w.bytes(ptl)
        // reserved '1111', min_spatial_segmentation_idc 0; reserved '111111', parallelismType 0.
        w.u(4, 0xF)
        w.u(12, 0)
        w.u(6, 0x3F)
        w.u(2, 0)
        w.u(6, 0x3F)
        w.u(2, c.chromaFormat)
        w.u(5, 0x1F)
        w.u(3, c.bitDepthY - 8)
        w.u(5, 0x1F)
        w.u(3, c.bitDepthC - 8)
        // avgFrameRate, constantFrameRate, numTemporalLayers 1, temporalIdNested 1, lengthSizeMinusOne 3.
        w.u(16, 0)
        w.u(2, 0)
        w.u(3, 1)
        w.u(1, 1)
        w.u(2, 3)
        w.u(8, 3)
        for ((type, unit) in listOf(NalType.VPS to vps, NalType.SPS to sps, NalType.PPS to pps)) {
            val escaped = escapeNal(unit)
            // array_completeness 1, reserved 0, NAL_unit_type; numNalus 1; nalUnitLength.
            w.u(1, 1)
            w.u(1, 0)
            w.u(6, type)
            w.u(16, 1)
            w.u(16, escaped.size)
            w.bytes(escaped)
        }
        return w.toByteArray()
    }
}
