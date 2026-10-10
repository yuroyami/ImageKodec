package io.github.yuroyami.imagekodec.codec.avif

/**
 * One set of the AV1 CDF arrays: those `init_non_coeff_cdfs` and `init_coeff_cdfs` name
 * (specification section 6.8.2), which a frame keeps, saves for later frames and averages
 * at its end, plus `TileIntraFrameYModeCdf`, which every tile starts afresh. Each array is
 * flattened row-major, every CDF in it [STRIDE] entries apart: its cumulative values, the
 * last 32768, then the adaptation counter.
 */
internal class Av1Cdfs private constructor(private val a: Array<IntArray>) {

    fun copy(): Av1Cdfs = Av1Cdfs(Array(a.size) { a[it].copyOf() })

    /** `load_cdfs`: once loaded, each CDF's counter starts at 0 again. */
    fun clearCounters() {
        for (k in a.indices) {
            val s = STRIDE[k]
            var i = s - 1
            while (i < a[k].size) {
                a[k][i] = 0
                i += s
            }
        }
    }

    /** `TileIntraFrameYModeCdf`, which `init_symbol` resets for every tile. */
    fun resetIntraFrameYMode() {
        Av1Tables.defaultIntraFrameYModeCdf.copyInto(a[INTRA_FRAME_Y_MODE])
    }

    operator fun get(array: Int): IntArray = a[array]

    /** Array [array] of every set in [sets] averaged into this one, for the frame end update; used by inter frames. */
    fun arrays(): Array<IntArray> = a

    companion object {
        const val INTRA_FRAME_Y_MODE = 0
        const val Y_MODE = 1
        const val UV_MODE_CFL_NOT_ALLOWED = 2
        const val UV_MODE_CFL_ALLOWED = 3
        const val ANGLE_DELTA = 4
        const val INTRABC = 5
        const val PARTITION_W8 = 6
        const val PARTITION_W16 = 7
        const val PARTITION_W32 = 8
        const val PARTITION_W64 = 9
        const val PARTITION_W128 = 10
        const val SEGMENT_ID = 11
        const val SEGMENT_ID_PREDICTED = 12
        const val TX_8X8 = 13
        const val TX_16X16 = 14
        const val TX_32X32 = 15
        const val TX_64X64 = 16
        const val TXFM_SPLIT = 17
        const val FILTER_INTRA_MODE = 18
        const val FILTER_INTRA = 19
        const val INTERP_FILTER = 20
        const val MOTION_MODE = 21
        const val NEW_MV = 22
        const val ZERO_MV = 23
        const val REF_MV = 24
        const val COMPOUND_MODE = 25
        const val DRL_MODE = 26
        const val IS_INTER = 27
        const val COMP_MODE = 28
        const val SKIP_MODE = 29
        const val SKIP = 30
        const val COMP_REF = 31
        const val COMP_BWD_REF = 32
        const val SINGLE_REF = 33
        /** [MV_CONTEXTS] */
        const val MV_JOINT = 34
        /** [MV_CONTEXTS][comp] */
        const val MV_CLASS = 35
        /** [MV_CONTEXTS][comp] */
        const val MV_CLASS0_BIT = 36
        /** [MV_CONTEXTS][comp] */
        const val MV_FR = 37
        /** [MV_CONTEXTS][comp][CLASS0_SIZE] */
        const val MV_CLASS0_FR = 38
        /** [MV_CONTEXTS][comp] */
        const val MV_CLASS0_HP = 39
        /** [MV_CONTEXTS][comp] */
        const val MV_SIGN = 40
        /** [MV_CONTEXTS][comp][MV_OFFSET_BITS] */
        const val MV_BIT = 41
        /** [MV_CONTEXTS][comp] */
        const val MV_HP = 42
        const val PALETTE_Y_MODE = 43
        const val PALETTE_UV_MODE = 44
        const val PALETTE_Y_SIZE = 45
        const val PALETTE_UV_SIZE = 46
        /** PaletteSize{2..8}YColorCdf at 47..53, PaletteSize{2..8}UVColorCdf at 54..60. */
        const val PALETTE_Y_COLOR = 47
        const val PALETTE_UV_COLOR = 54
        const val DELTA_Q = 61
        const val DELTA_LF = 62
        /** [FRAME_LF_COUNT] */
        const val DELTA_LF_MULTI = 63
        const val INTRA_TX_TYPE_SET1 = 64
        const val INTRA_TX_TYPE_SET2 = 65
        const val INTER_TX_TYPE_SET1 = 66
        const val INTER_TX_TYPE_SET2 = 67
        const val INTER_TX_TYPE_SET3 = 68
        const val USE_OBMC = 69
        const val INTER_INTRA = 70
        const val COMP_REF_TYPE = 71
        const val CFL_SIGN = 72
        const val UNI_COMP_REF = 73
        const val WEDGE_INTER_INTRA = 74
        const val COMP_GROUP_IDX = 75
        const val COMPOUND_IDX = 76
        const val COMPOUND_TYPE = 77
        const val INTER_INTRA_MODE = 78
        const val WEDGE_INDEX = 79
        const val CFL_ALPHA = 80
        const val USE_WIENER = 81
        const val USE_SGRPROJ = 82
        const val RESTORATION_TYPE = 83
        const val TXB_SKIP = 84
        const val EOB_PT_16 = 85
        const val EOB_PT_32 = 86
        const val EOB_PT_64 = 87
        const val EOB_PT_128 = 88
        const val EOB_PT_256 = 89
        const val EOB_PT_512 = 90
        const val EOB_PT_1024 = 91
        const val EOB_EXTRA = 92
        const val DC_SIGN = 93
        const val COEFF_BASE_EOB = 94
        const val COEFF_BASE = 95
        const val COEFF_BR = 96
        const val COUNT = 97

        /** The distance between CDFs in each array: the symbol count plus one. */
        val STRIDE: IntArray = IntArray(COUNT)

        private class Source(val table: IntArray, val stride: Int, val copies: Int = 1, val quantizerSets: Int = 1)

        private val SOURCES: Array<Source> = run {
            val t = Av1Tables
            val s = arrayOfNulls<Source>(COUNT)
            s[INTRA_FRAME_Y_MODE] = Source(t.defaultIntraFrameYModeCdf, 14)
            s[Y_MODE] = Source(t.defaultYModeCdf, 14)
            s[UV_MODE_CFL_NOT_ALLOWED] = Source(t.defaultUvModeCflNotAllowedCdf, 14)
            s[UV_MODE_CFL_ALLOWED] = Source(t.defaultUvModeCflAllowedCdf, 15)
            s[ANGLE_DELTA] = Source(t.defaultAngleDeltaCdf, 8)
            s[INTRABC] = Source(t.defaultIntrabcCdf, 3)
            s[PARTITION_W8] = Source(t.defaultPartitionW8Cdf, 5)
            s[PARTITION_W16] = Source(t.defaultPartitionW16Cdf, 11)
            s[PARTITION_W32] = Source(t.defaultPartitionW32Cdf, 11)
            s[PARTITION_W64] = Source(t.defaultPartitionW64Cdf, 11)
            s[PARTITION_W128] = Source(t.defaultPartitionW128Cdf, 9)
            s[SEGMENT_ID] = Source(t.defaultSegmentIdCdf, 9)
            s[SEGMENT_ID_PREDICTED] = Source(t.defaultSegmentIdPredictedCdf, 3)
            s[TX_8X8] = Source(t.defaultTx8x8Cdf, 3)
            s[TX_16X16] = Source(t.defaultTx16x16Cdf, 4)
            s[TX_32X32] = Source(t.defaultTx32x32Cdf, 4)
            s[TX_64X64] = Source(t.defaultTx64x64Cdf, 4)
            s[TXFM_SPLIT] = Source(t.defaultTxfmSplitCdf, 3)
            s[FILTER_INTRA_MODE] = Source(t.defaultFilterIntraModeCdf, 6)
            s[FILTER_INTRA] = Source(t.defaultFilterIntraCdf, 3)
            s[INTERP_FILTER] = Source(t.defaultInterpFilterCdf, 4)
            s[MOTION_MODE] = Source(t.defaultMotionModeCdf, 4)
            s[NEW_MV] = Source(t.defaultNewMvCdf, 3)
            s[ZERO_MV] = Source(t.defaultZeroMvCdf, 3)
            s[REF_MV] = Source(t.defaultRefMvCdf, 3)
            s[COMPOUND_MODE] = Source(t.defaultCompoundModeCdf, 9)
            s[DRL_MODE] = Source(t.defaultDrlModeCdf, 3)
            s[IS_INTER] = Source(t.defaultIsInterCdf, 3)
            s[COMP_MODE] = Source(t.defaultCompModeCdf, 3)
            s[SKIP_MODE] = Source(t.defaultSkipModeCdf, 3)
            s[SKIP] = Source(t.defaultSkipCdf, 3)
            s[COMP_REF] = Source(t.defaultCompRefCdf, 3)
            s[COMP_BWD_REF] = Source(t.defaultCompBwdRefCdf, 3)
            s[SINGLE_REF] = Source(t.defaultSingleRefCdf, 3)
            // The motion vector CDFs: one copy for each of the MV_CONTEXTS contexts, and for the
            // per-component ones one for each component too.
            s[MV_JOINT] = Source(t.defaultMvJointCdf, 5, copies = 2)
            s[MV_CLASS] = Source(t.defaultMvClassCdf, 12, copies = 2)
            s[MV_CLASS0_BIT] = Source(t.defaultMvClass0BitCdf, 3, copies = 4)
            s[MV_FR] = Source(t.defaultMvFrCdf, 5, copies = 2)
            s[MV_CLASS0_FR] = Source(t.defaultMvClass0FrCdf, 5, copies = 2)
            s[MV_CLASS0_HP] = Source(t.defaultMvClass0HpCdf, 3, copies = 4)
            s[MV_SIGN] = Source(t.defaultMvSignCdf, 3, copies = 4)
            s[MV_BIT] = Source(t.defaultMvBitCdf, 3, copies = 4)
            s[MV_HP] = Source(t.defaultMvHpCdf, 3, copies = 4)
            s[PALETTE_Y_MODE] = Source(t.defaultPaletteYModeCdf, 3)
            s[PALETTE_UV_MODE] = Source(t.defaultPaletteUvModeCdf, 3)
            s[PALETTE_Y_SIZE] = Source(t.defaultPaletteYSizeCdf, 8)
            s[PALETTE_UV_SIZE] = Source(t.defaultPaletteUvSizeCdf, 8)
            val yColors = arrayOf(
                t.defaultPaletteSize2YColorCdf, t.defaultPaletteSize3YColorCdf, t.defaultPaletteSize4YColorCdf,
                t.defaultPaletteSize5YColorCdf, t.defaultPaletteSize6YColorCdf, t.defaultPaletteSize7YColorCdf,
                t.defaultPaletteSize8YColorCdf,
            )
            val uvColors = arrayOf(
                t.defaultPaletteSize2UvColorCdf, t.defaultPaletteSize3UvColorCdf, t.defaultPaletteSize4UvColorCdf,
                t.defaultPaletteSize5UvColorCdf, t.defaultPaletteSize6UvColorCdf, t.defaultPaletteSize7UvColorCdf,
                t.defaultPaletteSize8UvColorCdf,
            )
            for (n in 2..8) {
                s[PALETTE_Y_COLOR + n - 2] = Source(yColors[n - 2], n + 1)
                s[PALETTE_UV_COLOR + n - 2] = Source(uvColors[n - 2], n + 1)
            }
            s[DELTA_Q] = Source(t.defaultDeltaQCdf, 5)
            s[DELTA_LF] = Source(t.defaultDeltaLfCdf, 5)
            s[DELTA_LF_MULTI] = Source(t.defaultDeltaLfCdf, 5, copies = 4)
            s[INTRA_TX_TYPE_SET1] = Source(t.defaultIntraTxTypeSet1Cdf, 8)
            s[INTRA_TX_TYPE_SET2] = Source(t.defaultIntraTxTypeSet2Cdf, 6)
            s[INTER_TX_TYPE_SET1] = Source(t.defaultInterTxTypeSet1Cdf, 17)
            s[INTER_TX_TYPE_SET2] = Source(t.defaultInterTxTypeSet2Cdf, 13)
            s[INTER_TX_TYPE_SET3] = Source(t.defaultInterTxTypeSet3Cdf, 3)
            s[USE_OBMC] = Source(t.defaultUseObmcCdf, 3)
            s[INTER_INTRA] = Source(t.defaultInterIntraCdf, 3)
            s[COMP_REF_TYPE] = Source(t.defaultCompRefTypeCdf, 3)
            s[CFL_SIGN] = Source(t.defaultCflSignCdf, 9)
            s[UNI_COMP_REF] = Source(t.defaultUniCompRefCdf, 3)
            s[WEDGE_INTER_INTRA] = Source(t.defaultWedgeInterIntraCdf, 3)
            s[COMP_GROUP_IDX] = Source(t.defaultCompGroupIdxCdf, 3)
            s[COMPOUND_IDX] = Source(t.defaultCompoundIdxCdf, 3)
            s[COMPOUND_TYPE] = Source(t.defaultCompoundTypeCdf, 3)
            s[INTER_INTRA_MODE] = Source(t.defaultInterIntraModeCdf, 5)
            s[WEDGE_INDEX] = Source(t.defaultWedgeIndexCdf, 17)
            s[CFL_ALPHA] = Source(t.defaultCflAlphaCdf, 17)
            s[USE_WIENER] = Source(t.defaultUseWienerCdf, 3)
            s[USE_SGRPROJ] = Source(t.defaultUseSgrprojCdf, 3)
            s[RESTORATION_TYPE] = Source(t.defaultRestorationTypeCdf, 4)
            // The coefficient CDFs come in COEFF_CDF_Q_CTXS sets, one chosen by base_q_idx.
            s[TXB_SKIP] = Source(t.defaultTxbSkipCdf, 3, quantizerSets = 4)
            s[EOB_PT_16] = Source(t.defaultEobPt16Cdf, 6, quantizerSets = 4)
            s[EOB_PT_32] = Source(t.defaultEobPt32Cdf, 7, quantizerSets = 4)
            s[EOB_PT_64] = Source(t.defaultEobPt64Cdf, 8, quantizerSets = 4)
            s[EOB_PT_128] = Source(t.defaultEobPt128Cdf, 9, quantizerSets = 4)
            s[EOB_PT_256] = Source(t.defaultEobPt256Cdf, 10, quantizerSets = 4)
            s[EOB_PT_512] = Source(t.defaultEobPt512Cdf, 11, quantizerSets = 4)
            s[EOB_PT_1024] = Source(t.defaultEobPt1024Cdf, 12, quantizerSets = 4)
            s[EOB_EXTRA] = Source(t.defaultEobExtraCdf, 3, quantizerSets = 4)
            s[DC_SIGN] = Source(t.defaultDcSignCdf, 3, quantizerSets = 4)
            s[COEFF_BASE_EOB] = Source(t.defaultCoeffBaseEobCdf, 4, quantizerSets = 4)
            s[COEFF_BASE] = Source(t.defaultCoeffBaseCdf, 5, quantizerSets = 4)
            s[COEFF_BR] = Source(t.defaultCoeffBrCdf, 5, quantizerSets = 4)
            Array(COUNT) { s[it]!!.also { src -> STRIDE[it] = src.stride } }
        }

        private fun default(k: Int, quantizerSet: Int): IntArray {
            val src = SOURCES[k]
            if (src.quantizerSets > 1) {
                val n = src.table.size / src.quantizerSets
                return src.table.copyOfRange(quantizerSet * n, (quantizerSet + 1) * n)
            }
            if (src.copies == 1) return src.table.copyOf()
            val n = src.table.size
            return IntArray(n * src.copies) { src.table[it % n] }
        }

        /** The coefficient CDF set `init_coeff_cdfs` picks for [baseQIdx]. */
        fun quantizerSet(baseQIdx: Int): Int = when {
            baseQIdx <= 20 -> 0
            baseQIdx <= 60 -> 1
            baseQIdx <= 120 -> 2
            else -> 3
        }

        /** `init_non_coeff_cdfs` and `init_coeff_cdfs` for a frame with [baseQIdx]. */
        fun initial(baseQIdx: Int): Av1Cdfs {
            val set = quantizerSet(baseQIdx)
            return Av1Cdfs(Array(COUNT) { default(it, set) })
        }

        /** [from] with its coefficient CDFs replaced by `init_coeff_cdfs` for [baseQIdx]. */
        fun withInitialCoefficients(from: Av1Cdfs, baseQIdx: Int): Av1Cdfs {
            val set = quantizerSet(baseQIdx)
            val out = from.copy()
            for (k in TXB_SKIP until COUNT) out.a[k] = default(k, set)
            return out
        }
    }
}
