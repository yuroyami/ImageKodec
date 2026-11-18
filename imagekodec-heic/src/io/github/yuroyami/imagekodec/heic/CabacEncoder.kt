package io.github.yuroyami.imagekodec.heic

/**
 * The arithmetic encoder of 9.3.5, or with [estimate] a counter of the bits the same bins would cost. Both
 * keep the context variables of 9.3.2 in [states] and move them as the decoder does (9.3.4.3.2.2), so one
 * piece of syntax code serves the rate estimates of the mode decisions and the real slice data.
 *
 * The estimate adds the cost of each bin from [HevcEncoderTables.BIT_COST] to [cost], in 1/256 bits; a
 * bypass bin costs one bit and a terminating bin of 0 nearly nothing. The encoder follows the flowcharts
 * of 9.3.5.2 to 9.3.5.6: ivlLow of 10 bits, PutBit with its outstanding bits, and the flush of
 * EncodeFlush, whose last bit is the rbsp_stop_one_bit or alignment_bit_equal_to_one.
 */
internal class CabacEncoder(val estimate: Boolean) {
    /** The context variables, laid out as [Ctx] says, pStateIdx shl 1 or valMps. */
    val states: ByteArray = ByteArray(Ctx.COUNT)

    /** The bits counted since the last reset, in 1/256 bits, when estimating. */
    var cost: Int = 0

    private val bitCost = HevcEncoderTables.BIT_COST
    private val rangeTabLps = HevcTables.RANGE_TAB_LPS
    private val transIdxMps = HevcTables.TRANS_IDX_MPS
    private val transIdxLps = HevcTables.TRANS_IDX_LPS

    private var low = 0
    private var range = 510
    private var outstanding = 0
    private var firstBit = true
    private var out = ByteArray(if (estimate) 0 else 4096)
    private var bitPos = 0

    /** InitEncoder of 9.3.5.2: the engine starts afresh; the contexts are set by [Ctx.init]. */
    fun start() {
        low = 0
        range = 510
        outstanding = 0
        firstBit = true
    }

    /** EncodeDecision of 9.3.5.3 for the context at [ctx]. */
    fun encode(ctx: Int, bin: Int) {
        val s = states[ctx].toInt()
        val state = s shr 1
        val mps = s and 1
        if (bin == mps) {
            states[ctx] = ((transIdxMps[state] shl 1) or mps).toByte()
        } else {
            states[ctx] = ((transIdxLps[state] shl 1) or (if (state == 0) 1 - mps else mps)).toByte()
        }
        if (estimate) {
            cost += bitCost[(state shl 1) or (bin xor mps)]
            return
        }
        val lps = rangeTabLps[(state shl 2) + ((range shr 6) and 3)]
        range -= lps
        if (bin != mps) {
            low += range
            range = lps
        }
        while (range < 256) renormalise()
    }

    /** The cost of [bin] in the context at [ctx], in 1/256 bits, without coding it. */
    fun costOf(ctx: Int, bin: Int): Int {
        val s = states[ctx].toInt()
        return bitCost[(s and 0x7E) or ((s and 1) xor bin)]
    }

    /** One step of RenormE, 9.3.5.4. */
    private fun renormalise() {
        if (low < 256) {
            putBit(0)
        } else if (low >= 512) {
            low -= 512
            putBit(1)
        } else {
            low -= 256
            outstanding++
        }
        range = range shl 1
        low = low shl 1
    }

    /** PutBit of 9.3.5.4: the bit, then the outstanding bits with the opposite value. */
    private fun putBit(b: Int) {
        if (firstBit) firstBit = false else writeBit(b)
        while (outstanding > 0) {
            writeBit(1 - b)
            outstanding--
        }
    }

    private fun writeBit(b: Int) {
        val p = bitPos shr 3
        if (p >= out.size) out = out.copyOf(out.size * 2)
        if (b != 0) out[p] = (out[p].toInt() or (0x80 ushr (bitPos and 7))).toByte()
        bitPos++
    }

    /** EncodeBypass of 9.3.5.5. */
    fun bypass(bin: Int) {
        if (estimate) {
            cost += 256
            return
        }
        low = low shl 1
        if (bin != 0) low += range
        if (low >= 1024) {
            putBit(1)
            low -= 1024
        } else if (low < 512) {
            putBit(0)
        } else {
            low -= 512
            outstanding++
        }
    }

    /** The low [n] bits of [v] as bypass bins, the highest first: the FL binarization of 9.3.3.5. */
    fun bypassBits(v: Int, n: Int) {
        if (estimate) {
            cost += n shl 8
            return
        }
        for (i in n - 1 downTo 0) bypass((v ushr i) and 1)
    }

    /** EncodeTerminate of 9.3.5.6; a 1 flushes the engine (EncodeFlush). */
    fun terminate(bin: Int) {
        if (estimate) {
            // A 0 costs log2( range / ( range - 2 ) ), a hundredth of a bit; a 1 about seven bits.
            cost += if (bin == 0) 1 else 7 * 256
            return
        }
        range -= 2
        if (bin != 0) {
            low += range
            range = 2
            while (range < 256) renormalise()
            putBit((low shr 9) and 1)
            writeBit((low shr 8) and 1)
            writeBit(1)
        } else {
            while (range < 256) renormalise()
        }
    }

    /** Zero bits up to the next byte boundary, after a flush: the rest of rbsp_trailing_bits or of the alignment. */
    fun alignZero() {
        while (bitPos and 7 != 0) writeBit(0)
    }

    /** The bytes written so far, which must end on a byte boundary. */
    fun toByteArray(): ByteArray {
        check(bitPos and 7 == 0) { "the CABAC data does not end on a byte boundary" }
        return out.copyOf(bitPos shr 3)
    }
}
