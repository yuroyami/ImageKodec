package io.github.yuroyami.imagekodec.codec.avif

/** The AV1 specification's enumerations and the lookups built on its tables, by their own names. */
internal object Av1 {
    const val MI_SIZE = 4
    const val MI_SIZE_LOG2 = 2

    // Block sizes (subSize).
    const val BLOCK_4X4 = 0
    const val BLOCK_8X8 = 3
    const val BLOCK_64X64 = 12
    const val BLOCK_128X128 = 15
    const val BLOCK_INVALID = 22

    // Intra prediction modes.
    const val DC_PRED = 0
    const val V_PRED = 1
    const val H_PRED = 2
    const val D45_PRED = 3
    const val D135_PRED = 4
    const val D113_PRED = 5
    const val D157_PRED = 6
    const val D203_PRED = 7
    const val D67_PRED = 8
    const val SMOOTH_PRED = 9
    const val SMOOTH_V_PRED = 10
    const val SMOOTH_H_PRED = 11
    const val PAETH_PRED = 12
    const val UV_CFL_PRED = 13

    // Partitions.
    const val PARTITION_NONE = 0
    const val PARTITION_HORZ = 1
    const val PARTITION_VERT = 2
    const val PARTITION_SPLIT = 3
    const val PARTITION_HORZ_A = 4
    const val PARTITION_HORZ_B = 5
    const val PARTITION_VERT_A = 6
    const val PARTITION_VERT_B = 7
    const val PARTITION_HORZ_4 = 8
    const val PARTITION_VERT_4 = 9

    // Transform sizes.
    const val TX_4X4 = 0
    const val TX_8X8 = 1
    const val TX_16X16 = 2
    const val TX_32X32 = 3
    const val TX_64X64 = 4
    const val TX_16X32 = 9
    const val TX_32X16 = 10
    const val TX_32X64 = 11
    const val TX_64X32 = 12
    const val TX_16X64 = 17
    const val TX_64X16 = 18
    const val TX_SIZES = 5
    const val TX_SIZES_ALL = 19

    // Transform types.
    const val DCT_DCT = 0
    const val ADST_DCT = 1
    const val DCT_ADST = 2
    const val ADST_ADST = 3
    const val FLIPADST_DCT = 4
    const val DCT_FLIPADST = 5
    const val FLIPADST_FLIPADST = 6
    const val ADST_FLIPADST = 7
    const val FLIPADST_ADST = 8
    const val IDTX = 9
    const val V_DCT = 10
    const val H_DCT = 11
    const val V_ADST = 12
    const val H_ADST = 13
    const val V_FLIPADST = 14
    const val H_FLIPADST = 15

    // Transform sets.
    const val TX_SET_DCTONLY = 0
    const val TX_SET_INTRA_1 = 1
    const val TX_SET_INTRA_2 = 2
    const val TX_SET_INTER_1 = 1
    const val TX_SET_INTER_2 = 2
    const val TX_SET_INTER_3 = 3

    const val TX_CLASS_2D = 0
    const val TX_CLASS_HORIZ = 1
    const val TX_CLASS_VERT = 2

    const val CFL_SIGN_ZERO = 0
    const val CFL_SIGN_NEG = 1
    const val CFL_SIGN_POS = 2

    const val MAX_ANGLE_DELTA = 3
    const val ANGLE_STEP = 3
    const val PALETTE_COLORS = 8
    const val PALETTE_NUM_NEIGHBORS = 3
    const val DELTA_Q_SMALL = 3
    const val DELTA_LF_SMALL = 3
    const val FRAME_LF_COUNT = 4
    const val MAX_LOOP_FILTER = 63
    const val NUM_BASE_LEVELS = 2
    const val COEFF_BASE_RANGE = 12
    const val BR_CDF_SIZE = 4
    const val SIG_COEF_CONTEXTS = 42
    const val SIG_COEF_CONTEXTS_EOB = 4
    const val SIG_REF_DIFF_OFFSET_NUM = 5
    const val INTRA_FILTER_SCALE_BITS = 4
    const val MAX_VARTX_DEPTH = 2
    const val SEG_LVL_ALT_LF_Y_V = 1
    const val SEG_LVL_SKIP = 6
    const val SEG_LVL_GLOBALMV = 7
    const val SEG_LVL_REF_FRAME = 5

    // Reference frames.
    const val NONE = -1
    const val INTRA_FRAME = 0

    private val t = Av1Tables

    val num4x4Wide: IntArray = t.num4x4BlocksWide
    val num4x4High: IntArray = t.num4x4BlocksHigh
    val miWidthLog2: IntArray = t.miWidthLog2
    val miHeightLog2: IntArray = t.miHeightLog2
    val blockWidth: IntArray = IntArray(22) { t.num4x4BlocksWide[it] * 4 }
    val blockHeight: IntArray = IntArray(22) { t.num4x4BlocksHigh[it] * 4 }
    val txWidth: IntArray = t.txWidth
    val txHeight: IntArray = t.txHeight
    val txWidthLog2: IntArray = t.txWidthLog2
    val txHeightLog2: IntArray = t.txHeightLog2

    /** `Partition_Subsize[ partition ][ bSize ]`. */
    fun partitionSubsize(partition: Int, bSize: Int): Int = t.partitionSubsize[partition * 22 + bSize]

    /** `Subsampled_Size[ subsize ][ subx ][ suby ]`. */
    fun subsampledSize(size: Int, subX: Int, subY: Int): Int = t.subsampledSize[size * 4 + subX * 2 + subY]

    fun isDirectionalMode(mode: Int): Boolean = mode in V_PRED..D67_PRED

    fun txClass(txType: Int): Int = when (txType) {
        V_DCT, V_ADST, V_FLIPADST -> TX_CLASS_VERT
        H_DCT, H_ADST, H_FLIPADST -> TX_CLASS_HORIZ
        else -> TX_CLASS_2D
    }

    /** `find_tx_size( w, h )`. */
    fun findTxSize(w: Int, h: Int): Int {
        var txSz = 0
        while (txSz < TX_SIZES_ALL) {
            if (txWidth[txSz] == w && txHeight[txSz] == h) break
            txSz++
        }
        return txSz
    }

    fun round2(x: Int, n: Int): Int = if (n == 0) x else (x + (1 shl (n - 1))) shr n

    fun round2Signed(x: Int, n: Int): Int = if (x >= 0) round2(x, n) else -round2(-x, n)

    fun round2(x: Long, n: Int): Long = if (n == 0) x else (x + (1L shl (n - 1))) shr n
}
