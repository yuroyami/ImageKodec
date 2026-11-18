package io.github.yuroyami.imagekodec.heic

/**
 * A tiny stream with every screen content coding tool (#188), written bin by bin from H.265 for the tests that run
 * on every target: the conformance streams that the oracle test decodes are too large to commit and carry no licence.
 *
 * One IDR picture of 32x32 samples, 4:4:4 at 8 bits, CTBs of 16, made of one P slice whose only reference is the
 * picture itself (pps_curr_pic_ref_enabled_flag), with use_integer_mv_flag (motion_vector_resolution_control_idc 2),
 * intra_boundary_filtering_disabled_flag, the adaptive colour transform with slice offsets, and palette predictor
 * initializers in the SPS and in the PPS, the PPS's in force. The loop filters are off, so every sample is the one
 * its coding unit makes:
 * - CTB 0: a 16x16 palette unit of two predicted entries and one new one, with escape samples and
 *   palette_transpose_flag, its indices in runs of their own and copied from above ([CTB0_RUNS]);
 * - CTB 1: a 16x16 unit that copies CTB 0 (block vector ( -16, 0 )), with one coefficient in luma and in Cb through
 *   tu_residual_act_flag;
 * - CTB 2: four 8x8 units: an intra unit with tu_residual_act_flag, a palette unit of two predicted entries that ends
 *   on copy_above_indices_for_final_run_flag, a unit that copies the top left 8x8 of CTB 0 (block vector ( 0, -24 )),
 *   and a skipped unit that merges that vector;
 * - CTB 3: a 16x16 intra unit in the horizontal mode, without residual.
 */
internal object SccStream {
    const val SIZE = 32
    const val QP = 26

    /** The PPS's palette predictor initializers, per component. */
    val PPS_INITIALIZERS = arrayOf(intArrayOf(10, 50, 90), intArrayOf(20, 60, 100), intArrayOf(30, 70, 110))
    /** The entry CTB 0 signals. */
    val NEW_ENTRY = intArrayOf(200, 150, 250)

    /** One run of the palette index map: [length] positions of the traverse scan, copied from above or of [index]. */
    class Run(val copy: Boolean, val index: Int, val length: Int)

    /** CTB 0: entries 0, 1, 2 and escape samples at index 3, a final run of an index. */
    val CTB0_RUNS = listOf(Run(false, 0, 20), Run(true, 0, 30), Run(false, 3, 40), Run(false, 1, 10), Run(true, 0, 100), Run(false, 2, 56))
    /** Unit b of CTB 2: two entries and no escape, the last run copied from above to the end. */
    val UNIT_B_RUNS = listOf(Run(false, 1, 10), Run(false, 0, 21), Run(true, 0, 33))

    /** The escape value of component [c] at scan position [pos]. */
    fun escapeValue(c: Int, pos: Int): Int = (pos * 7 + c * 13) % 60

    private fun nal(type: Int, body: (BitWriter) -> Unit): ByteArray {
        val w = BitWriter(64)
        w.u(1, 0)
        w.u(6, type)
        w.u(6, 0)
        w.u(3, 1)
        body(w)
        return escapeNal(w.toByteArray())
    }

    private val profile = HevcProfile.choose(3, 8, 8, HevcProfile.level(SIZE, SIZE)!!)

    private fun vps(): ByteArray = nal(NalType.VPS) { w ->
        w.u(4, 0)
        // vps_base_layer_internal_flag, vps_base_layer_available_flag, vps_max_layers_minus1, vps_max_sub_layers_minus1,
        // vps_temporal_id_nesting_flag, vps_reserved_0xffff_16bits.
        w.u(1, 1)
        w.u(1, 1)
        w.u(6, 0)
        w.u(3, 0)
        w.u(1, 1)
        w.u(16, 0xFFFF)
        HevcHeaderWriter.profileTierLevel(w, profile)
        w.u(1, 1)
        w.ue(0)
        w.ue(0)
        w.ue(0)
        w.u(6, 0)
        w.ue(0)
        w.u(1, 0)
        w.u(1, 0)
        w.trailingBits()
    }

    private fun sps(): ByteArray = nal(NalType.SPS) { w ->
        w.u(4, 0)
        w.u(3, 0)
        w.u(1, 1)
        HevcHeaderWriter.profileTierLevel(w, profile)
        w.ue(0)
        // chroma_format_idc 3, separate_colour_plane_flag 0, the size, no conformance window, 8 bits.
        w.ue(3)
        w.u(1, 0)
        w.ue(SIZE)
        w.ue(SIZE)
        w.u(1, 0)
        w.ue(0)
        w.ue(0)
        // log2_max_pic_order_cnt_lsb_minus4, sps_sub_layer_ordering_info_present_flag and its one sub-layer.
        w.ue(0)
        w.u(1, 1)
        w.ue(0)
        w.ue(0)
        w.ue(0)
        // Coding blocks of 8 to 16, transform blocks of 4 to 16, no transform hierarchy.
        w.ue(0)
        w.ue(1)
        w.ue(0)
        w.ue(2)
        w.ue(0)
        w.ue(0)
        // scaling lists, AMP, SAO, PCM, short-term sets, long-term pictures, temporal MVP, strong smoothing, VUI.
        w.u(1, 0)
        w.u(1, 0)
        w.u(1, 0)
        w.u(1, 0)
        w.ue(0)
        w.u(1, 0)
        w.u(1, 0)
        w.u(1, 0)
        w.u(1, 0)
        // sps_extension_present_flag, then only sps_scc_extension_flag.
        w.u(1, 1)
        w.u(1, 0)
        w.u(1, 0)
        w.u(1, 0)
        w.u(1, 1)
        w.u(4, 0)
        // sps_scc_extension( ): current picture referencing, palettes of up to 4 entries and a predictor of up to 8
        // with two initializers, motion_vector_resolution_control_idc 2, intra boundary filtering disabled.
        w.u(1, 1)
        w.u(1, 1)
        w.ue(4)
        w.ue(4)
        w.u(1, 1)
        w.ue(1)
        for (c in 0..2) for (i in 0..1) w.u(8, 1 + 2 * c + i)
        w.u(2, 2)
        w.u(1, 1)
        w.trailingBits()
    }

    private fun pps(): ByteArray = nal(NalType.PPS) { w ->
        w.ue(0)
        w.ue(0)
        w.u(1, 0)
        w.u(1, 0)
        w.u(3, 0)
        // sign_data_hiding_enabled_flag, cabac_init_present_flag, the default reference counts, init_qp_minus26.
        w.u(1, 0)
        w.u(1, 0)
        w.ue(0)
        w.ue(0)
        w.se(QP - 26)
        // constrained intra prediction, transform skip, cu_qp_delta, the chroma offsets, weighted prediction, bypass.
        w.u(1, 0)
        w.u(1, 0)
        w.u(1, 0)
        w.se(0)
        w.se(0)
        w.u(1, 0)
        w.u(1, 0)
        w.u(1, 0)
        w.u(1, 0)
        // tiles, WPP, loop filter across slices; the deblocking filter is off.
        w.u(1, 0)
        w.u(1, 0)
        w.u(1, 0)
        w.u(1, 1)
        w.u(1, 0)
        w.u(1, 1)
        // scaling lists, list modification, parallel merge level, header extension.
        w.u(1, 0)
        w.u(1, 0)
        w.ue(0)
        w.u(1, 0)
        // pps_extension_present_flag, then only pps_scc_extension_flag.
        w.u(1, 1)
        w.u(1, 0)
        w.u(1, 0)
        w.u(1, 0)
        w.u(1, 1)
        w.u(4, 0)
        // pps_scc_extension( ): current picture referencing; the adaptive colour transform with slice offsets and
        // offsets of -2, 0 and 0; three predictor initializers of 8 bits.
        w.u(1, 1)
        w.u(1, 1)
        w.u(1, 1)
        w.se(3)
        w.se(5)
        w.se(3)
        w.u(1, 1)
        w.ue(3)
        w.u(1, 0)
        w.ue(0)
        w.ue(0)
        for (c in 0..2) for (v in PPS_INITIALIZERS[c]) w.u(8, v)
        w.trailingBits()
    }

    private fun sliceHeader(): ByteArray {
        val w = BitWriter(64)
        w.u(1, 0)
        w.u(6, NalType.IDR_W_RADL)
        w.u(6, 0)
        w.u(3, 1)
        // first_slice_segment_in_pic_flag, no_output_of_prior_pics_flag, slice_pic_parameter_set_id, slice_type P.
        w.u(1, 1)
        w.u(1, 0)
        w.ue(0)
        w.ue(SliceType.P)
        // num_ref_idx_active_override_flag 0; five_minus_max_num_merge_cand 3; use_integer_mv_flag 1.
        w.u(1, 0)
        w.ue(3)
        w.u(1, 1)
        // slice_qp_delta, then slice_act_y_qp_offset, slice_act_cb_qp_offset and slice_act_cr_qp_offset: the Cb offset
        // gives CTB 1 a Cb residual unlike its luma one, so the colour transform leaves no component as it was.
        w.se(0)
        w.se(-1)
        w.se(12)
        w.se(-1)
        w.byteAlignment()
        return w.toByteArray()
    }

    /** The NAL units: VPS, SPS, PPS and the slice. */
    fun units(): List<ByteArray> = listOf(vps(), sps(), pps(), escapeNal(sliceHeader() + SliceData().write()))

    /** The palette index map of [runs] over a block of side [n], in the raster of the scan (before any transposition). */
    fun indexMap(runs: List<Run>, n: Int): IntArray {
        val map = IntArray(n * n)
        var pos = 0
        for (run in runs) repeat(run.length) {
            val u = PaletteDecoder.traverse(pos, n)
            map[u] = if (run.copy) map[u - n] else run.index
            pos++
        }
        return map
    }

    /** The CABAC writer of the slice data, its bins in the contexts the decoder reads them with. */
    private class SliceData {
        private val c = CabacEncoder(false)

        fun write(): ByteArray {
            // initType 1: a P slice without cabac_init_flag (Table 9-4).
            Ctx.init(c.states, 1, QP)
            c.start()
            ctb0()
            c.terminate(0)
            ctb1()
            c.terminate(0)
            ctb2()
            c.terminate(0)
            ctb3()
            c.terminate(1)
            c.alignZero()
            return c.toByteArray()
        }

        private fun eg(k0: Int, value: Int) {
            var k = k0
            var v = value
            while (v >= (1 shl k)) {
                c.bypass(1)
                v -= 1 shl k
                k++
            }
            c.bypass(0)
            c.bypassBits(v, k)
        }

        private fun tb(cMax: Int, v: Int) {
            val n = cMax + 1
            val k = 31 - n.countLeadingZeroBits()
            val u = (1 shl (k + 1)) - n
            if (v < u) c.bypassBits(v, k) else c.bypassBits(v + u, k + 1)
        }

        /** split_cu_flag, cu_skip_flag 0 (the neighbours give both context 0 here but where noted), pred_mode_flag. */
        private fun unitStart(split: Int, splitInc: Int, intra: Boolean, skipCoded: Boolean = true) {
            if (split >= 0) c.encode(Ctx.SPLIT_CU + splitInc, split)
            if (skipCoded) c.encode(Ctx.CU_SKIP, 0)
            c.encode(Ctx.PRED_MODE, if (intra) 1 else 0)
        }

        /** mvd_coding( ) of a vector in whole samples, then mvp_l0_flag 0. */
        private fun mvd(x: Int, y: Int) {
            c.encode(Ctx.ABS_MVD_GREATER0, if (x != 0) 1 else 0)
            c.encode(Ctx.ABS_MVD_GREATER0, if (y != 0) 1 else 0)
            if (x != 0) c.encode(Ctx.ABS_MVD_GREATER1, if (kotlin.math.abs(x) > 1) 1 else 0)
            if (y != 0) c.encode(Ctx.ABS_MVD_GREATER1, if (kotlin.math.abs(y) > 1) 1 else 0)
            for (v in intArrayOf(x, y)) if (v != 0) {
                if (kotlin.math.abs(v) > 1) eg(1, kotlin.math.abs(v) - 2)
                c.bypass(if (v < 0) 1 else 0)
            }
            c.encode(Ctx.MVP_FLAG, 0)
        }

        /** residual_coding( ) of one coefficient of level 1 at ( 0, 0 ) of a [log2] block of luma or chroma, diagonal scan. */
        private fun dcCoefficient(log2: Int, chroma: Boolean, negative: Boolean) {
            val offset = if (chroma) 15 else 3 * (log2 - 2) + ((log2 - 1) shr 2)
            c.encode(Ctx.LAST_X_PREFIX + offset, 0)
            c.encode(Ctx.LAST_Y_PREFIX + offset, 0)
            c.encode(Ctx.GREATER1 + (if (chroma) 16 else 0) + 1, 0)
            c.bypass(if (negative) 1 else 0)
        }

        /** palette_coding( ) of a unit of side [n] whose predictor is [predictorSize] long. */
        private fun palette(n: Int, predictorRuns: IntArray, newEntries: List<IntArray>, size: Int, escape: Boolean, runs: List<Run>, finalCopy: Boolean, transpose: Boolean) {
            for (r in predictorRuns) eg(0, r)
            val maxSize = 4
            val predicted = size - newEntries.size
            if (predicted < maxSize) eg(0, newEntries.size)
            for (comp in 0..2) for (e in newEntries) c.bypassBits(e[comp], 8)
            if (size != 0) c.bypass(if (escape) 1 else 0)
            val maxIndex = size - 1 + (if (escape) 1 else 0)
            // Simulates the scan as 7.3.8.13 parses it, for the indices to send and how each run is coded.
            val total = n * n
            val map = IntArray(total)
            val copied = BooleanArray(total)
            val numIndices = runs.count { !it.copy }
            val idc = ArrayList<Int>()
            val events = ArrayList<() -> Unit>()
            var remaining = numIndices
            var pos = 0
            val fin = if (finalCopy) 1 else 0
            for (run in runs) {
                val at = PaletteDecoder.traverse(pos, n)
                val prev = if (pos > 0) PaletteDecoder.traverse(pos - 1, n) else -1
                if (maxIndex > 0 && pos >= n && !copied[prev]) {
                    if (remaining > 0 && pos < total - 1) {
                        val bin = if (run.copy) 1 else 0
                        events += { c.encode(Ctx.COPY_ABOVE, bin) }
                    } else {
                        check(run.copy == !(pos == total - 1 && remaining > 0)) { "the run at $pos cannot be coded" }
                    }
                } else {
                    check(!run.copy) { "the run at $pos cannot copy" }
                }
                var syntaxIdc = 0
                if (!run.copy) {
                    val ref = if (pos == 0) maxIndex + 1 else if (!copied[prev]) map[prev] else map[at - n]
                    check(run.index != ref) { "the run at $pos repeats the index it would be told from" }
                    syntaxIdc = if (run.index > ref) run.index - 1 else run.index
                    idc += syntaxIdc
                    remaining--
                }
                if (maxIndex > 0 && (remaining > 0 || (if (run.copy) 1 else 0) != fin)) {
                    val maxRun = total - pos - 1 - remaining - fin
                    val value = run.length - 1
                    check(value in 0..maxRun) { "the run at $pos is too long" }
                    if (maxRun > 0) {
                        val copy = run.copy
                        val code = syntaxIdc
                        events += { runMinus1(value, maxRun, copy, code) }
                    }
                } else {
                    check(run.length == total - pos) { "the run at $pos does not reach the end" }
                }
                repeat(run.length) {
                    val u = PaletteDecoder.traverse(pos, n)
                    copied[u] = run.copy
                    map[u] = if (run.copy) map[u - n] else run.index
                    pos++
                }
            }
            check(pos == total)
            if (maxIndex > 0) {
                // num_palette_indices_minus1, 9.3.3.14.
                val rice = 3 + ((maxIndex + 1) shr 3)
                val v = numIndices - 1
                val cMax = 4 shl rice
                if (v < cMax) {
                    repeat(v shr rice) { c.bypass(1) }
                    c.bypass(0)
                    c.bypassBits(v and ((1 shl rice) - 1), rice)
                } else {
                    repeat(4) { c.bypass(1) }
                    eg(rice + 1, v - cMax)
                }
                for ((j, value) in idc.withIndex()) {
                    val max = if (j == 0) maxIndex else maxIndex - 1
                    if (max > 0) tb(max, value)
                }
                c.encode(Ctx.COPY_ABOVE, fin)
                c.encode(Ctx.PALETTE_TRANSPOSE, if (transpose) 1 else 0)
            }
            for (e in events) e()
            if (escape) for (comp in 0..2) for (s in 0 until total) {
                if (map[PaletteDecoder.traverse(s, n)] == maxIndex) eg(3, escapeValue(comp, s))
            }
        }

        /** palette_run_prefix and palette_run_suffix of PaletteRunMinus1 [value] (7-85, 7-86, 9.3.4.2.8). */
        private fun runMinus1(value: Int, maxRun: Int, copy: Boolean, idc: Int) {
            val cMax = 31 - maxRun.countLeadingZeroBits() + 1
            val prefix = if (value < 2) value else 31 - value.countLeadingZeroBits() + 1
            for (b in 0 until minOf(prefix + 1, cMax)) {
                val bin = if (b < prefix) 1 else 0
                if (b > 4) {
                    c.bypass(bin)
                } else {
                    val inc = when {
                        b == 0 && !copy -> if (idc < 1) 0 else if (idc < 3) 1 else 2
                        b == 0 -> 5
                        b <= 2 -> if (copy) 6 else 3
                        else -> if (copy) 7 else 4
                    }
                    c.encode(Ctx.PALETTE_RUN_PREFIX + inc, bin)
                }
            }
            if (prefix > 1) {
                val offset = 1 shl (prefix - 1)
                if (maxRun != offset) tb(if ((offset shl 1) > maxRun) maxRun - offset else offset - 1, value - offset)
            }
        }

        private fun ctb0() {
            unitStart(split = 0, splitInc = 0, intra = true)
            c.encode(Ctx.PALETTE_MODE, 1)
            // Entries 0 and 2 of the PPS's predictor (palette_predictor_run 0 and 2), then the new one.
            palette(16, intArrayOf(0, 2), listOf(NEW_ENTRY), 3, true, CTB0_RUNS, false, true)
        }

        private fun ctb1() {
            unitStart(split = 0, splitInc = 0, intra = false)
            // part_mode 2Nx2N, merge_flag 0, the block vector, rqt_root_cbf 1.
            c.encode(Ctx.PART_MODE, 1)
            c.encode(Ctx.MERGE_FLAG, 0)
            mvd(-16, 0)
            c.encode(Ctx.RQT_ROOT_CBF, 1)
            // cbf_cb 1, cbf_cr 0, cbf_luma 1, tu_residual_act_flag 1, then a coefficient in luma and in Cb.
            c.encode(Ctx.CBF_CHROMA, 1)
            c.encode(Ctx.CBF_CHROMA, 0)
            c.encode(Ctx.CBF_LUMA + 1, 1)
            c.encode(Ctx.TU_RESIDUAL_ACT, 1)
            dcCoefficient(4, false, false)
            dcCoefficient(4, true, true)
        }

        private fun ctb2() {
            // a: intra 2Nx2N, the first most probable mode, chroma mode 4, a luma coefficient under the colour transform.
            unitStart(split = 1, splitInc = 0, intra = true)
            c.encode(Ctx.PALETTE_MODE, 0)
            c.encode(Ctx.PART_MODE, 1)
            c.encode(Ctx.PREV_INTRA_LUMA_PRED, 1)
            c.bypass(0)
            c.encode(Ctx.INTRA_CHROMA_PRED_MODE, 0)
            c.encode(Ctx.CBF_CHROMA, 0)
            c.encode(Ctx.CBF_CHROMA, 0)
            c.encode(Ctx.CBF_LUMA + 1, 1)
            c.encode(Ctx.TU_RESIDUAL_ACT, 1)
            dcCoefficient(3, false, true)
            // b: a palette of the first two entries of the predictor (runs 0 and 0, then 1 to stop).
            unitStart(split = -1, splitInc = 0, intra = true)
            c.encode(Ctx.PALETTE_MODE, 1)
            palette(8, intArrayOf(0, 0, 1), emptyList(), 2, false, UNIT_B_RUNS, true, false)
            // c: the top left 8x8 of CTB 0, without residual.
            unitStart(split = -1, splitInc = 0, intra = false)
            c.encode(Ctx.PART_MODE, 1)
            c.encode(Ctx.MERGE_FLAG, 0)
            mvd(0, -24)
            c.encode(Ctx.RQT_ROOT_CBF, 0)
            // d: skipped, merge_idx 0, which takes the vector of c on its left.
            c.encode(Ctx.CU_SKIP, 1)
            c.encode(Ctx.MERGE_IDX, 0)
        }

        private fun ctb3() {
            // The left neighbour sits at depth 1 (Table 9-49).
            unitStart(split = 0, splitInc = 1, intra = true)
            c.encode(Ctx.PALETTE_MODE, 0)
            // rem_intra_luma_pred_mode 8: mode 10, past the candidates 0 and 1 (26 is above it).
            c.encode(Ctx.PREV_INTRA_LUMA_PRED, 0)
            c.bypassBits(8, 5)
            // intra_chroma_pred_mode 1: vertical.
            c.encode(Ctx.INTRA_CHROMA_PRED_MODE, 1)
            c.bypassBits(1, 2)
            c.encode(Ctx.CBF_CHROMA, 0)
            c.encode(Ctx.CBF_CHROMA, 0)
            c.encode(Ctx.CBF_LUMA + 1, 0)
        }
    }
}
