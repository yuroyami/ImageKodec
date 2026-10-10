// Altered Kotlin port of parts of libjxl 0.11.1 (lib/jxl/ac_strategy.h, ac_strategy.cc,
// coeff_order.h and coeff_order_fwd.h).
// Copyright (c) the JPEG XL Project Authors. All rights reserved.
// Use of the original source is governed by a BSD-style license; see NOTICE.

package io.github.yuroyami.imagekodec.codec.jxl

/**
 * The 27 block transforms of VarDCT, the lossy mode of JPEG XL. libjxl calls the transform
 * of a block its "AC strategy". The numbers are libjxl's AcStrategyType, which is also the
 * value a codestream stores. A name gives rows before columns: DCT16X8 is 16 samples high
 * and 8 samples wide.
 *
 * ## Coefficient layout
 *
 * A transform that covers `cx` by `cy` blocks of 8 by 8 samples has `64 * cx * cy`
 * coefficients. libjxl stores them as `8 * min(cx, cy)` rows of `8 * max(cx, cy)` columns,
 * so the long side of the transform always runs along a row:
 *
 * - wider than high (`cx > cy`): the coefficient of vertical frequency `v` and horizontal
 *   frequency `h` is at `v * 8 * cx + h`;
 * - square or higher than wide: the coefficient is at `h * 8 * cy + v`. A square block is
 *   therefore transposed against JPEG.
 *
 * The lowest frequencies (LLF in libjxl) are the corner of `min(cx, cy)` rows by
 * `max(cx, cy)` columns at the start of the array. They come from the LF image and not
 * from the coefficient stream. The dequantization matrices use the same layout.
 */
internal object JxlAcStrategy {
    const val COUNT = 27

    const val DCT = 0
    const val IDENTITY = 1
    const val DCT2X2 = 2
    const val DCT4X4 = 3
    const val DCT16X16 = 4
    const val DCT32X32 = 5
    const val DCT16X8 = 6
    const val DCT8X16 = 7
    const val DCT32X8 = 8
    const val DCT8X32 = 9
    const val DCT32X16 = 10
    const val DCT16X32 = 11
    const val DCT4X8 = 12
    const val DCT8X4 = 13
    const val AFV0 = 14
    const val AFV1 = 15
    const val AFV2 = 16
    const val AFV3 = 17
    const val DCT64X64 = 18
    const val DCT64X32 = 19
    const val DCT32X64 = 20
    const val DCT128X128 = 21
    const val DCT128X64 = 22
    const val DCT64X128 = 23
    const val DCT256X256 = 24
    const val DCT256X128 = 25
    const val DCT128X256 = 26

    /** The side of a block in samples (libjxl kBlockDim). */
    const val BLOCK_DIM = 8

    /** The most blocks a transform covers along one side, and the most samples and coefficients that makes. */
    const val MAX_COEFF_BLOCKS = 32
    const val MAX_BLOCK_DIM = BLOCK_DIM * MAX_COEFF_BLOCKS
    const val MAX_COEFF_AREA = MAX_BLOCK_DIM * MAX_BLOCK_DIM

    /** How many coefficient orders a frame can hold for each channel (libjxl kNumOrders). */
    const val NUM_ORDERS = 13

    private val COVERED_X = intArrayOf(1, 1, 1, 1, 2, 4, 1, 2, 1, 4, 2, 4, 1, 1, 1, 1, 1, 1, 8, 4, 8, 16, 8, 16, 32, 16, 32)
    private val COVERED_Y = intArrayOf(1, 1, 1, 1, 2, 4, 2, 1, 4, 1, 4, 2, 1, 1, 1, 1, 1, 1, 8, 8, 4, 16, 16, 8, 32, 32, 16)

    /** libjxl kStrategyOrder: which of the 13 coefficient orders the strategy uses. */
    val STRATEGY_ORDER: IntArray = intArrayOf(0, 1, 1, 1, 2, 3, 4, 4, 5, 5, 6, 6, 1, 1, 1, 1, 1, 1, 7, 8, 8, 9, 10, 10, 11, 12, 12)

    // The blocks one order covers. Orders 0 and 1 are both for 8 by 8 transforms.
    private val ORDER_BLOCKS = IntArray(NUM_ORDERS).also { blocks ->
        for (s in 0 until COUNT) blocks[STRATEGY_ORDER[s]] = COVERED_X[s] * COVERED_Y[s]
    }

    // libjxl kCoeffOrderOffset: where each order of each channel starts, in blocks of 64 entries.
    private val ORDER_OFFSET = IntArray(3 * NUM_ORDERS + 1).also { offsets ->
        for (i in 0 until 3 * NUM_ORDERS) offsets[i + 1] = offsets[i] + ORDER_BLOCKS[i / 3]
    }

    /** The size of the array that holds every coefficient order of a frame (libjxl kCoeffOrderMaxSize). */
    val COEFF_ORDER_SIZE: Int = ORDER_OFFSET[3 * NUM_ORDERS] * 64

    fun isValid(strategy: Int): Boolean = strategy in 0 until COUNT

    /** How many 8x8 blocks the transform covers across and down (libjxl covered_blocks_x / covered_blocks_y). */
    fun coveredBlocksX(strategy: Int): Int = COVERED_X[strategy]
    fun coveredBlocksY(strategy: Int): Int = COVERED_Y[strategy]

    /** The base 2 logarithm of the number of blocks the transform covers. */
    fun log2CoveredBlocks(strategy: Int): Int = (COVERED_X[strategy] * COVERED_Y[strategy]).countTrailingZeroBits()

    /** How many coefficients the transform has, LLF included. */
    fun coefficientCount(strategy: Int): Int = 64 * COVERED_X[strategy] * COVERED_Y[strategy]

    /** Whether the transform covers more than one block. */
    fun isMultiblock(strategy: Int): Boolean = COVERED_X[strategy] * COVERED_Y[strategy] > 1

    /** How many coefficients order [order] permutes. */
    fun orderSize(order: Int): Int = ORDER_BLOCKS[order] * 64

    /** Where order [order] of channel [c] starts in the array of every order (libjxl CoeffOrderOffset). */
    fun coeffOrderOffset(order: Int, c: Int): Int = ORDER_OFFSET[3 * order + c] * 64

    // Element 0 is the order and element 1 its inverse. `lazy` makes the first use safe from two threads.
    private val NATURAL = Array(NUM_ORDERS) { order ->
        lazy {
            val s = STRATEGY_ORDER.indexOf(order)
            computeNaturalOrder(COVERED_X[s], COVERED_Y[s])
        }
    }

    /**
     * The natural order of the strategy's coefficients (libjxl ComputeNaturalCoeffOrder): a zigzag
     * from low to high frequency. Position k of the scan gives the index of the coefficient in
     * the block's coefficient array. The LLF come first, row by row. Do not write to the array.
     */
    fun naturalCoeffOrder(strategy: Int): IntArray = NATURAL[STRATEGY_ORDER[strategy]].value[0]

    /** The inverse of [naturalCoeffOrder] (libjxl ComputeNaturalCoeffOrderLut). Do not write to the array. */
    fun naturalCoeffOrderLut(strategy: Int): IntArray = NATURAL[STRATEGY_ORDER[strategy]].value[1]

    /**
     * libjxl CoeffOrderAndLut. It walks the diagonals of a square of the long side and keeps
     * the rows that a block of the short side has, so a block that is not square is scanned
     * as if its short side were stretched.
     */
    private fun computeNaturalOrder(coveredX: Int, coveredY: Int): Array<IntArray> {
        val cx = maxOf(coveredX, coveredY)
        val cy = minOf(coveredX, coveredY)
        val side = cx * BLOCK_DIM
        val order = IntArray(cx * cy * 64)
        val lut = IntArray(cx * cy * 64)
        val ratio = cx / cy
        val mask = ratio - 1
        val shift = ratio.countTrailingZeroBits()
        // The LLF take the first cx * cy places; every other coefficient follows in scan order.
        var next = cx * cy
        for (i in 0 until side) {
            for (j in 0..i) {
                var x = j
                var y = i - j
                if (i and 1 != 0) x = y.also { y = x }
                if (y and mask != 0) continue
                y = y shr shift
                val place = if (x < cx && y < cy) y * cx + x else next++
                order[place] = y * side + x
                lut[y * side + x] = place
            }
        }
        for (i in side - 2 downTo 0) {
            for (j in 0..i) {
                var x = side - 1 - (i - j)
                var y = side - 1 - j
                if (i and 1 != 0) x = y.also { y = x }
                if (y and mask != 0) continue
                y = y shr shift
                val place = next++
                order[place] = y * side + x
                lut[y * side + x] = place
            }
        }
        return arrayOf(order, lut)
    }
}
