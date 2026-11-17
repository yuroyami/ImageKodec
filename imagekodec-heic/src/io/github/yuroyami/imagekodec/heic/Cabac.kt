package io.github.yuroyami.imagekodec.heic

/**
 * The context variables of one slice, laid out for one initType (Table 9-4): each syntax element owns
 * a run of entries from its offset here, indexed by ctxInc. An element with no context for an
 * initType (the inter elements of an I slice) keeps a neutral state that nothing reads. The four Rice
 * parameter initialization states StatCoeff of 9.3.3.11 follow the contexts, as 9.3.2.4 and 9.3.2.5
 * store and restore them together, so one copy of the array carries both.
 */
internal object Ctx {
    const val SAO_MERGE = 0
    const val SAO_TYPE = 1
    const val SPLIT_CU = 2
    const val TRANSQUANT_BYPASS = 5
    const val CU_SKIP = 6
    const val PRED_MODE = 9
    const val PART_MODE = 10
    const val PREV_INTRA_LUMA_PRED = 14
    const val INTRA_CHROMA_PRED_MODE = 15
    const val RQT_ROOT_CBF = 16
    const val MERGE_FLAG = 17
    const val MERGE_IDX = 18
    const val INTER_PRED_IDC = 19
    const val REF_IDX = 24
    const val MVP_FLAG = 26
    const val SPLIT_TRANSFORM = 27
    const val CBF_LUMA = 30
    const val CBF_CHROMA = 32
    const val ABS_MVD_GREATER0 = 37
    const val ABS_MVD_GREATER1 = 38
    const val CU_QP_DELTA_ABS = 39
    const val TRANSFORM_SKIP = 41
    const val LAST_X_PREFIX = 43
    const val LAST_Y_PREFIX = 61
    const val CODED_SUB_BLOCK = 79
    const val SIG_COEFF = 83
    const val GREATER1 = 127
    const val GREATER2 = 151
    const val CU_CHROMA_QP_OFFSET_FLAG = 157
    const val CU_CHROMA_QP_OFFSET_IDX = 158
    const val LOG2_RES_SCALE_ABS = 159
    const val RES_SCALE_SIGN = 167
    /** explicit_rdpcm_flag and explicit_rdpcm_dir_flag: one context for luma, then one for both chroma components. */
    const val EXPLICIT_RDPCM = 169
    const val EXPLICIT_RDPCM_DIR = 171
    /** The number of context variables. */
    const val CONTEXTS = 173
    /** StatCoeff[ 0..3 ], each 0 to about 90 (9-23 stops it near the largest level), so a byte holds it. */
    const val STAT_COEFF = 173
    const val COUNT = 177

    /** The initValue of every entry for initType 0, 1 and 2. */
    private val initValues: Array<IntArray> = Array(3) { t -> layout(t) }

    /** The ctxIdx of [table] that Table 9-4 gives initType [t]: [perType] in a row from t * perType. */
    private fun IntArray.put(table: IntArray, at: Int, t: Int, perType: Int) {
        for (i in 0 until perType) this[at + i] = table[t * perType + i]
    }

    private fun layout(t: Int): IntArray {
        val T = HevcTables
        // 154 gives m = 0 and n = 64 in 9-5, an even state: the one of an element unused at this initType.
        val v = IntArray(CONTEXTS) { 154 }
        v.put(T.INIT_SAO_MERGE, SAO_MERGE, t, 1)
        v.put(T.INIT_SAO_TYPE, SAO_TYPE, t, 1)
        v.put(T.INIT_SPLIT_CU, SPLIT_CU, t, 3)
        v.put(T.INIT_TRANSQUANT_BYPASS, TRANSQUANT_BYPASS, t, 1)
        v.put(T.INIT_PREV_INTRA_LUMA_PRED, PREV_INTRA_LUMA_PRED, t, 1)
        v.put(T.INIT_INTRA_CHROMA_PRED_MODE, INTRA_CHROMA_PRED_MODE, t, 1)
        v.put(T.INIT_SPLIT_TRANSFORM, SPLIT_TRANSFORM, t, 3)
        v.put(T.INIT_CBF_LUMA, CBF_LUMA, t, 2)
        v.put(T.INIT_CBF_CHROMA, CBF_CHROMA, t, 4)
        // Table 9-4: the fifth cbf_cb and cbf_cr context of each initType sits after the twelve others.
        v[CBF_CHROMA + 4] = T.INIT_CBF_CHROMA[12 + t]
        v.put(T.INIT_CU_QP_DELTA_ABS, CU_QP_DELTA_ABS, t, 2)
        v[TRANSFORM_SKIP] = T.INIT_TRANSFORM_SKIP[t]
        v[TRANSFORM_SKIP + 1] = T.INIT_TRANSFORM_SKIP[3 + t]
        v.put(T.INIT_LAST_X_PREFIX, LAST_X_PREFIX, t, 18)
        v.put(T.INIT_LAST_Y_PREFIX, LAST_Y_PREFIX, t, 18)
        v.put(T.INIT_CODED_SUB_BLOCK, CODED_SUB_BLOCK, t, 4)
        v.put(T.INIT_SIG_COEFF, SIG_COEFF, t, 42)
        v[SIG_COEFF + 42] = T.INIT_SIG_COEFF[126 + 2 * t]
        v[SIG_COEFF + 43] = T.INIT_SIG_COEFF[127 + 2 * t]
        v.put(T.INIT_GREATER1, GREATER1, t, 24)
        v.put(T.INIT_GREATER2, GREATER2, t, 6)
        v.put(T.INIT_CU_CHROMA_QP_OFFSET_FLAG, CU_CHROMA_QP_OFFSET_FLAG, t, 1)
        v.put(T.INIT_CU_CHROMA_QP_OFFSET_IDX, CU_CHROMA_QP_OFFSET_IDX, t, 1)
        v.put(T.INIT_LOG2_RES_SCALE_ABS, LOG2_RES_SCALE_ABS, t, 8)
        v.put(T.INIT_RES_SCALE_SIGN, RES_SCALE_SIGN, t, 2)
        // part_mode has one context at initType 0 and four at the others (Table 9-4).
        if (t == 0) {
            v[PART_MODE] = T.INIT_PART_MODE[0]
        } else {
            for (i in 0 until 4) v[PART_MODE + i] = T.INIT_PART_MODE[1 + (t - 1) * 4 + i]
            v.put(T.INIT_CU_SKIP, CU_SKIP, t - 1, 3)
            v[PRED_MODE] = T.INIT_PRED_MODE[t - 1]
            v[RQT_ROOT_CBF] = T.INIT_RQT_ROOT_CBF[t - 1]
            v[MERGE_FLAG] = T.INIT_MERGE_FLAG[t - 1]
            v[MERGE_IDX] = T.INIT_MERGE_IDX[t - 1]
            v.put(T.INIT_INTER_PRED_IDC, INTER_PRED_IDC, t - 1, 5)
            v.put(T.INIT_REF_IDX, REF_IDX, t - 1, 2)
            v[MVP_FLAG] = T.INIT_MVP_FLAG[t - 1]
            v[ABS_MVD_GREATER0] = T.INIT_ABS_MVD[(t - 1) * 2]
            v[ABS_MVD_GREATER1] = T.INIT_ABS_MVD[(t - 1) * 2 + 1]
            // Tables 9-32 and 9-33: ctxIdx t - 1 for luma and 2 + t - 1 for chroma (Table 9-4).
            v[EXPLICIT_RDPCM] = T.INIT_EXPLICIT_RDPCM[t - 1]
            v[EXPLICIT_RDPCM + 1] = T.INIT_EXPLICIT_RDPCM[2 + t - 1]
            v[EXPLICIT_RDPCM_DIR] = T.INIT_EXPLICIT_RDPCM_DIR[t - 1]
            v[EXPLICIT_RDPCM_DIR + 1] = T.INIT_EXPLICIT_RDPCM_DIR[2 + t - 1]
        }
        return v
    }

    /**
     * Initialises [states] for [initType] and the slice's SliceQpY [qp] (9.3.2.2): each context holds
     * pStateIdx shl 1 or valMps. StatCoeff starts at 0 (9.3.2.1).
     */
    fun init(states: ByteArray, initType: Int, qp: Int) {
        val values = initValues[initType]
        val q = qp.coerceIn(0, 51)
        states.fill(0, STAT_COEFF, COUNT)
        for (i in 0 until CONTEXTS) {
            val init = values[i]
            // 9-4 to 9-6.
            val m = (init shr 4) * 5 - 45
            val n = ((init and 15) shl 3) - 16
            val pre = (((m * q) shr 4) + n).coerceIn(1, 126)
            val mps = if (pre <= 63) 0 else 1
            val state = if (mps == 1) pre - 64 else 63 - pre
            states[i] = ((state shl 1) or mps).toByte()
        }
    }
}

/**
 * The arithmetic decoding engine of 9.3.4.3 over the bytes of an RBSP. ivlOffset is kept scaled: the
 * register [value] holds ivlOffset followed by [bits] bits read ahead, so a renormalisation shifts no
 * register and reads whole bytes. A read past [end] gives zero bytes, which a conforming slice never
 * consumes; a few of them mean the data was cut off.
 */
internal class Cabac {
    private var data: ByteArray = ByteArray(0)
    private var pos = 0
    private var end = 0
    private var range = 510
    private var value = 0
    private var bits = 0

    /** The context variables of the current slice segment. */
    val states: ByteArray = ByteArray(Ctx.COUNT)
    private val rangeTabLps = HevcTables.RANGE_TAB_LPS
    private val transIdxMps = HevcTables.TRANS_IDX_MPS
    private val transIdxLps = HevcTables.TRANS_IDX_LPS

    /** Starts the engine at byte [from] of [rbsp], whose data ends before byte [end] (9.3.2.6). */
    fun start(rbsp: ByteArray, from: Int, end: Int) {
        data = rbsp
        pos = from
        this.end = end
        if (from >= end) hevcError("the slice segment data is empty")
        range = 510
        value = (nextByte() shl 8) or nextByte()
        bits = 7
        // 9.3.2.6: ivlOffset 510 and 511 cannot occur.
        if ((value shr 7) >= 510) hevcError("the CABAC data starts with ivlOffset ${value shr 7}")
    }

    private fun nextByte(): Int {
        if (pos < end) return data[pos++].toInt() and 0xFF
        // Up to three bytes are read ahead of the decoder's position; a fourth means it ran past the end.
        if (++pos - end > 3) hevcError("the slice segment data is cut off")
        return 0
    }

    /** The bit position of the decoder in the RBSP: the bits ivlOffset has taken in. */
    val bitPosition: Int get() = pos * 8 - bits

    /** The byte after the bits the decoder has taken in, which byte_alignment( ) completes. */
    val alignedByte: Int get() = (bitPosition + 7) shr 3

    /** DecodeDecision of the context at [ctx] (9.3.4.3.2). */
    fun decode(ctx: Int): Int {
        val s = states[ctx].toInt()
        val state = s shr 1
        var mps = s and 1
        val lps = rangeTabLps[(state shl 2) + ((range shr 6) and 3)]
        range -= lps
        val scaled = range shl bits
        if (value < scaled) {
            states[ctx] = ((transIdxMps[state] shl 1) or mps).toByte()
            if (range < 256) renormalise(1)
            return mps
        }
        value -= scaled
        val bin = 1 - mps
        // 9-66: an LPS in state 0 swaps the most probable symbol.
        if (state == 0) mps = 1 - mps
        states[ctx] = ((transIdxLps[state] shl 1) or mps).toByte()
        range = lps
        renormalise(range.countLeadingZeroBits() - 23)
        return bin
    }

    /** RenormD of 9.3.4.3.3: [shift] doublings of ivlCurrRange, each taking one bit into ivlOffset. */
    private fun renormalise(shift: Int) {
        range = range shl shift
        if (bits < shift) refill()
        bits -= shift
    }

    /** Reads two bytes ahead. ivlOffset stays below 2^9 and [bits] below 23, so [value] keeps to 31 bits. */
    private fun refill() {
        value = (value shl 16) or (nextByte() shl 8) or nextByte()
        bits += 16
    }

    /** DecodeBypass (9.3.4.3.4). */
    fun bypass(): Int {
        if (bits == 0) refill()
        bits--
        val scaled = range shl bits
        // Without a branch, as a bypass bin is as likely 0 as 1: mask is -1 when ivlOffset reaches ivlCurrRange.
        val mask = (value - scaled).inv() shr 31
        value -= scaled and mask
        return mask and 1
    }

    /**
     * The alignment process of 9.3.4.3.6, before the bypass bins of a sub-block when
     * cabac_bypass_alignment_enabled_flag is 1. A range of 256 makes each bypass bin a plain bit of
     * the data, which a decoder may read faster; this one reads them as any bypass bin.
     */
    fun align() {
        range = 256
    }

    /** [n] bypass bins, the first in the highest bit: the FL binarisation of 9.3.3.5. */
    fun bypassBits(n: Int): Int {
        var v = 0
        for (i in 0 until n) v = (v shl 1) or bypass()
        return v
    }

    /** DecodeTerminate (9.3.4.3.5). After a 1 the engine stops, its last bit read being the stop or alignment bit. */
    fun terminate(): Int {
        range -= 2
        if (value >= (range shl bits)) return 1
        if (range < 256) renormalise(1)
        return 0
    }
}
