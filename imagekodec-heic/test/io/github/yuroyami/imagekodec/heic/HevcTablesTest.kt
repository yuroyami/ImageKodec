package io.github.yuroyami.imagekodec.heic

import kotlin.test.Test
import kotlin.test.assertEquals

/** The constant tables hold the CRC-32 that tools/hevc_tables.py wrote with them, so an edit by hand fails. */
class HevcTablesTest {

    private fun crc32(values: IntArray): Long {
        var c = 0xFFFFFFFFL
        for (v in values) {
            c = c xor (v.toLong() and 0xFF)
            repeat(8) { c = if (c and 1L != 0L) (c ushr 1) xor 0xEDB88320L else c ushr 1 }
        }
        return c xor 0xFFFFFFFFL
    }

    @Test
    fun everyTableHoldsItsChecksum() {
        val tables = listOf(
            "DEFAULT_SCALING_INTRA" to (0xF4A79674L to HevcTables.DEFAULT_SCALING_INTRA),
            "DEFAULT_SCALING_INTER" to (0x1620423AL to HevcTables.DEFAULT_SCALING_INTER),
            "INIT_SAO_MERGE" to (0x24D340DEL to HevcTables.INIT_SAO_MERGE),
            "INIT_SAO_TYPE" to (0x832CA8F3L to HevcTables.INIT_SAO_TYPE),
            "INIT_SPLIT_CU" to (0x40859D04L to HevcTables.INIT_SPLIT_CU),
            "INIT_TRANSQUANT_BYPASS" to (0x94B1FCFEL to HevcTables.INIT_TRANSQUANT_BYPASS),
            "INIT_CU_SKIP" to (0xA6AEDE03L to HevcTables.INIT_CU_SKIP),
            "INIT_PRED_MODE" to (0x49344AB5L to HevcTables.INIT_PRED_MODE),
            "INIT_PART_MODE" to (0xA24687E5L to HevcTables.INIT_PART_MODE),
            "INIT_PREV_INTRA_LUMA_PRED" to (0xEAA73205L to HevcTables.INIT_PREV_INTRA_LUMA_PRED),
            "INIT_INTRA_CHROMA_PRED_MODE" to (0x973A60DBL to HevcTables.INIT_INTRA_CHROMA_PRED_MODE),
            "INIT_RQT_ROOT_CBF" to (0xD05B1D34L to HevcTables.INIT_RQT_ROOT_CBF),
            "INIT_MERGE_FLAG" to (0xAA7D2E8CL to HevcTables.INIT_MERGE_FLAG),
            "INIT_MERGE_IDX" to (0x006DB807L to HevcTables.INIT_MERGE_IDX),
            "INIT_INTER_PRED_IDC" to (0x8314D56AL to HevcTables.INIT_INTER_PRED_IDC),
            "INIT_REF_IDX" to (0x3A9EE8FEL to HevcTables.INIT_REF_IDX),
            "INIT_MVP_FLAG" to (0xFF0A0FC4L to HevcTables.INIT_MVP_FLAG),
            "INIT_SPLIT_TRANSFORM" to (0x3AB2B3D1L to HevcTables.INIT_SPLIT_TRANSFORM),
            "INIT_CBF_LUMA" to (0xFF5B07A6L to HevcTables.INIT_CBF_LUMA),
            "INIT_CBF_CHROMA" to (0x6617A471L to HevcTables.INIT_CBF_CHROMA),
            "INIT_ABS_MVD" to (0x1E2B5C48L to HevcTables.INIT_ABS_MVD),
            "INIT_CU_QP_DELTA_ABS" to (0x2F848F1BL to HevcTables.INIT_CU_QP_DELTA_ABS),
            "INIT_TRANSFORM_SKIP" to (0x76A61A48L to HevcTables.INIT_TRANSFORM_SKIP),
            "INIT_LAST_X_PREFIX" to (0xDEB6933BL to HevcTables.INIT_LAST_X_PREFIX),
            "INIT_LAST_Y_PREFIX" to (0xDEB6933BL to HevcTables.INIT_LAST_Y_PREFIX),
            "INIT_CODED_SUB_BLOCK" to (0xC3B085D6L to HevcTables.INIT_CODED_SUB_BLOCK),
            "INIT_SIG_COEFF" to (0xD0E3BD23L to HevcTables.INIT_SIG_COEFF),
            "INIT_GREATER1" to (0x48D945AFL to HevcTables.INIT_GREATER1),
            "INIT_GREATER2" to (0x61B682F9L to HevcTables.INIT_GREATER2),
            "INIT_EXPLICIT_RDPCM" to (0x5461016FL to HevcTables.INIT_EXPLICIT_RDPCM),
            "INIT_EXPLICIT_RDPCM_DIR" to (0x5461016FL to HevcTables.INIT_EXPLICIT_RDPCM_DIR),
            "INIT_CU_CHROMA_QP_OFFSET_FLAG" to (0x94B1FCFEL to HevcTables.INIT_CU_CHROMA_QP_OFFSET_FLAG),
            "INIT_CU_CHROMA_QP_OFFSET_IDX" to (0x94B1FCFEL to HevcTables.INIT_CU_CHROMA_QP_OFFSET_IDX),
            "INIT_LOG2_RES_SCALE_ABS" to (0xF4C79D28L to HevcTables.INIT_LOG2_RES_SCALE_ABS),
            "INIT_RES_SCALE_SIGN" to (0x2F848F1BL to HevcTables.INIT_RES_SCALE_SIGN),
            "CTX_IDX_MAP" to (0xB0091FC4L to HevcTables.CTX_IDX_MAP),
            "RANGE_TAB_LPS" to (0xDB858CD6L to HevcTables.RANGE_TAB_LPS),
            "TRANS_IDX_LPS" to (0xF9DA248CL to HevcTables.TRANS_IDX_LPS),
            "TRANS_IDX_MPS" to (0xF121A675L to HevcTables.TRANS_IDX_MPS),
            "CHROMA_422_MODE" to (0x30E2CB16L to HevcTables.CHROMA_422_MODE),
            "INTRA_PRED_ANGLE" to (0x49FE7E1BL to HevcTables.INTRA_PRED_ANGLE),
            "QPC_30_TO_43" to (0xD4DC21E9L to HevcTables.QPC_30_TO_43),
            "BETA_PRIME" to (0x5D5117BEL to HevcTables.BETA_PRIME),
            "TC_PRIME" to (0x693A18DAL to HevcTables.TC_PRIME),
            "DST_4" to (0x69BDEB20L to HevcTables.DST_4),
            "DCT_32" to (0xB73974B1L to HevcTables.DCT_32),
        )
        for ((name, expected) in tables) assertEquals(expected.first, crc32(expected.second), name)
    }

    @Test
    fun defaultScalingLists() {
        // Table 7-5: the default 4x4 list is flat; the larger ones start at 16 and end where Table 7-6 does.
        assertEquals(List(16) { 16 }, ScalingLists.DEFAULT.lists[0].toList())
        assertEquals(115, ScalingLists.DEFAULT.lists[6 + 2].last())
        assertEquals(91, ScalingLists.DEFAULT.lists[18 + 3].last())
    }

    @Test
    fun derivedTablesMatchTheText() {
        // Table 8-6, which the predictor derives from intraPredAngle rather than holds.
        val invAngle = listOf(-4096, -1638, -910, -630, -482, -390, -315, -256)
        for (mode in 11..25) {
            val expected = invAngle[if (mode <= 18) mode - 11 else 25 - mode]
            assertEquals(expected, IntraPredictor.invAngle(HevcTables.INTRA_PRED_ANGLE[mode - 2]), "invAngle of mode $mode")
        }
        // The 4-point DCT of 8-317 is every eighth row of the 32-point one: the H.265 4x4 basis.
        assertEquals(listOf(64, 64, 64, 64, 83, 36, -36, -83, 64, -64, -64, 64, 36, -83, 83, -36), InverseTransform.MATRICES[0].toList())
        // The butterfly of the inverse transform needs each basis even or odd about its middle.
        for (m in InverseTransform.MATRICES) {
            val n = kotlin.math.sqrt(m.size.toDouble()).toInt()
            for (k in 0 until n) for (i in 0 until n) assertEquals(if (k % 2 == 0) m[k * n + i] else -m[k * n + i], m[k * n + n - 1 - i], "basis $k of $n")
        }
        // Extended precision lets a coefficient reach 2^22 - 1 at 16 bits (7-29): its 16 and 32-point sums
        // pass 32 bits and its 8-point ones do not, and at 20 bits of range only the 32-point ones do.
        assertEquals(listOf(true, true, false, false), (2..5).map { InverseTransform.fitsInt(it, (1 shl 22) - 1) })
        assertEquals(listOf(true, true, true, true), (2..5).map { InverseTransform.fitsInt(it, (1 shl 20) - 1) })
        assertEquals(listOf(true, true, true, false), (2..5).map { InverseTransform.fitsInt(it, (1 shl 21) - 1) })
        // 9-6 at SliceQpY 26 for the neutral initValue 154: m = 0, n = 64, so pStateIdx 0 and valMps 1.
        val states = ByteArray(Ctx.COUNT)
        Ctx.init(states, 0, 26)
        assertEquals(1, states[Ctx.CU_QP_DELTA_ABS].toInt())
    }
}
