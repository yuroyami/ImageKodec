// Altered Kotlin port of parts of libjxl 0.11.1 (lib/jxl/quant_weights.h, quant_weights.cc
// and ModularFrameDecoder::DecodeQuantTable of dec_modular.cc).
// Copyright (c) the JPEG XL Project Authors. All rights reserved.
// Use of the original source is governed by a BSD-style license; see NOTICE.

package io.github.yuroyami.imagekodec.codec.jxl

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** libjxl DctQuantWeightParams: for each channel, the weight at frequency zero and the step to each next band of frequencies. */
private class JxlQuantBands(val count: Int, val values: DoubleArray)

/** How a frame describes one quant table (libjxl QuantEncoding). Only the fields of its [mode] are set. */
private class JxlQuantEncoding(
    val mode: Int,
    /** The weights or multipliers of the mode, the three channels one after the other. */
    val weights: DoubleArray = DoubleArray(0),
    val bands: JxlQuantBands? = null,
    /** The bands of the 4 by 4 DCT of an AFV table. */
    val bands4x4: JxlQuantBands? = null,
    val raw: IntArray? = null,
    val rawDenominator: Double = 0.0,
)

/**
 * The dequantization matrices of a VarDCT frame, the lossy mode of JPEG XL: for each quant
 * table and channel, the factor that each quantized coefficient of a block is multiplied by.
 * There are 17 quant tables, one for each size of transform, so two transforms that are
 * each other's transpose share a table. A frame either takes the tables of the library or
 * describes its own.
 *
 * A matrix has the layout of the block's coefficients (see [JxlAcStrategy]): `8 * short side`
 * rows of `8 * long side` columns, with the long side counted in blocks.
 */
internal class JxlDequantMatrices private constructor() {
    // A null encoding is the table of the library.
    private val encodings = arrayOfNulls<JxlQuantEncoding>(NUM_TABLES)
    private val tables = arrayOfNulls<FloatArray>(NUM_TABLES)

    /**
     * Computes the tables that the strategies in the bit set [usedStrategies] need (libjxl
     * EnsureComputed). A frame's own table is checked only here, so a table that no block
     * uses can hold anything. Call it before any thread asks for a [matrix].
     */
    fun ensureComputed(usedStrategies: Int) {
        for (s in 0 until JxlAcStrategy.COUNT) {
            if (usedStrategies and (1 shl s) != 0) table(QUANT_TABLE[s])
        }
    }

    /**
     * The array that holds the matrices of [strategy] (libjxl DequantMatrices::Matrix). The one of
     * channel [c] starts at [matrixOffset] and has 64 values for each block the strategy
     * covers. Do not write to the array: frames that use the library's tables share it.
     */
    fun matrix(strategy: Int, c: Int): FloatArray = table(QUANT_TABLE[strategy])

    fun matrixOffset(strategy: Int, c: Int): Int = c * tableSize(QUANT_TABLE[strategy])

    /** How quant table [table] is described: one of the MODE constants. */
    fun mode(table: Int): Int = encodings[table]?.mode ?: MODE_LIBRARY

    /** The divisors of a [MODE_RAW] table as the frame holds them, three channels of rows; null for another mode. */
    fun rawTable(table: Int): IntArray? = encodings[table]?.raw

    /** libjxl qtable_den of a [MODE_RAW] table: the weight of a coefficient is 1 / (this * divisor). 0 for another mode. */
    fun rawDenominator(table: Int): Double = encodings[table]?.rawDenominator ?: 0.0

    private fun table(table: Int): FloatArray {
        tables[table]?.let { return it }
        val encoding = encodings[table]
        val made = if (encoding == null) LIBRARY_TABLES[table].value else computeTable(encoding, table)
        tables[table] = made
        return made
    }

    companion object {
        /** libjxl kNumQuantTables. */
        const val NUM_TABLES = 17

        // libjxl QuantEncodingInternal::Mode, the value a frame stores in three bits.
        const val MODE_LIBRARY = 0
        const val MODE_IDENTITY = 1
        const val MODE_DCT2 = 2
        const val MODE_DCT4 = 3
        const val MODE_DCT4X8 = 4
        const val MODE_AFV = 5
        const val MODE_DCT = 6
        const val MODE_RAW = 7

        /** libjxl kAcStrategyToQuantTableMap: the quant table of each strategy. */
        val QUANT_TABLE: IntArray =
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 10, 10, 11, 12, 12, 13, 14, 14, 15, 16, 16)

        // libjxl required_size_x and required_size_y: the short and the long side of each table, in blocks.
        private val SHORT_SIDE = intArrayOf(1, 1, 1, 1, 2, 4, 1, 1, 2, 1, 1, 8, 4, 16, 8, 32, 16)
        private val LONG_SIDE = intArrayOf(1, 1, 1, 1, 2, 4, 2, 4, 4, 1, 1, 8, 8, 16, 16, 32, 32)

        // A weight, or what a weight is made from, below this is not valid (libjxl kAlmostZero).
        private const val ALMOST_ZERO = 1e-8

        // The library's tables are the same for every frame, so they are made once. `lazy` makes that safe from two threads.
        private val LIBRARY_TABLES = Array(NUM_TABLES) { table -> lazy { computeTable(libraryEncoding(table), table) } }

        /** How many values the matrix of one channel of [table] has. */
        fun tableSize(table: Int): Int = 64 * SHORT_SIDE[table] * LONG_SIDE[table]

        /** The matrices every frame starts with: the library's. */
        fun defaults(): JxlDequantMatrices = JxlDequantMatrices()

        /**
         * Reads a frame's matrices (libjxl DequantMatrices::Decode): one bit says that every table is
         * the library's, or else each of the 17 tables has its own description. A raw table is a
         * Modular image, which [modular] reads.
         */
        fun read(br: JxlBitReader, modular: JxlModularFrame): JxlDequantMatrices =
            read(br) { image, table -> modular.decodeStream(br, image, modular.quantTableStream(table)) }

        /** [read] with the reader of a raw table's Modular image given as [readRaw], which takes the image to fill and the table's number. */
        fun read(br: JxlBitReader, readRaw: (JxlModularImage, Int) -> Unit): JxlDequantMatrices {
            val matrices = JxlDequantMatrices()
            if (br.bool()) return matrices
            for (table in 0 until NUM_TABLES) matrices.encodings[table] = readEncoding(br, table, readRaw)
            return matrices
        }

        /** libjxl Decode of quant_weights.cc: the description of one table. Null stands for the library's table. */
        private fun readEncoding(br: JxlBitReader, table: Int, readRaw: (JxlModularImage, Int) -> Unit): JxlQuantEncoding? {
            val mode = br.bits(3)
            // Only the DCT and the raw description fit a table that is larger than one block.
            if (mode in MODE_IDENTITY..MODE_AFV && tableSize(table) != 64) jxlFail("quant table $table cannot have mode $mode")
            return when (mode) {
                MODE_LIBRARY -> null
                MODE_IDENTITY -> JxlQuantEncoding(mode, readWeights(br, 3, 3, 3, "ID quantizer is too small"))
                MODE_DCT2 -> JxlQuantEncoding(mode, readWeights(br, 6, 6, 6, "DCT2 quantizer is too small"))
                MODE_DCT4X8 -> {
                    val multipliers = readWeights(br, 1, 1, 0, "DCT4X8 multiplier is too small")
                    JxlQuantEncoding(mode, multipliers, readBands(br))
                }
                MODE_DCT4 -> {
                    val multipliers = readWeights(br, 2, 2, 0, "DCT4 multiplier is too small")
                    JxlQuantEncoding(mode, multipliers, readBands(br))
                }
                MODE_AFV -> {
                    // libjxl checks these nine weights only when it computes the table.
                    val weights = readWeights(br, 9, 0, 6, "")
                    val bands = readBands(br)
                    JxlQuantEncoding(mode, weights, bands, readBands(br))
                }
                MODE_DCT -> JxlQuantEncoding(mode, bands = readBands(br))
                else -> readRawTable(br, table, readRaw)
            }
        }

        /**
         * Reads [count] half floats for each channel. The first [checked] of a channel must not be
         * zero, and the first [scaled] of a channel are stored as a 64th of their value.
         */
        private fun readWeights(br: JxlBitReader, count: Int, checked: Int, scaled: Int, tooSmall: String): DoubleArray {
            val weights = DoubleArray(3 * count)
            for (c in 0 until 3) {
                for (i in 0 until count) {
                    var v = br.f16()
                    if (i < checked && abs(v) < ALMOST_ZERO) jxlFail(tooSmall)
                    if (i < scaled) v *= 64.0
                    weights[c * count + i] = v
                }
            }
            return weights
        }

        /** libjxl DecodeDctParams. */
        private fun readBands(br: JxlBitReader): JxlQuantBands {
            val count = br.bits(4) + 1
            val values = DoubleArray(3 * count)
            for (c in 0 until 3) {
                for (i in 0 until count) values[c * count + i] = br.f16()
                if (values[c * count] < ALMOST_ZERO) jxlFail("distance band seed is too small")
                values[c * count] *= 64.0
            }
            return JxlQuantBands(count, values)
        }

        /**
         * libjxl DecodeQuantTable: a raw table is a denominator and a Modular image of three channels
         * that holds a whole divisor for every coefficient, as a JPEG quantization table does.
         */
        private fun readRawTable(br: JxlBitReader, table: Int, readRaw: (JxlModularImage, Int) -> Unit): JxlQuantEncoding {
            val denominator = br.f16()
            if (denominator < ALMOST_ZERO) jxlFail("raw quant table denominator is too small")
            // libjxl makes the image as wide as the table's short side. The samples are then taken in reading order.
            val w = 8 * SHORT_SIDE[table]
            val h = 8 * LONG_SIDE[table]
            val image = JxlModularImage(8)
            repeat(3) { image.channels.add(JxlChannel(w, h)) }
            readRaw(image, table)
            if (image.channels.size != 3) jxlFail("raw quant table $table does not have three channels")
            val raw = IntArray(3 * w * h)
            for (c in 0 until 3) {
                val channel = image.channels[c]
                if (channel.w != w || channel.h != h) jxlFail("raw quant table $table has a channel of the wrong size")
                val data = channel.data
                for (i in 0 until w * h) {
                    if (data[i] <= 0) jxlFail("raw quant table $table holds a value that is not positive")
                    raw[c * w * h + i] = data[i]
                }
            }
            return JxlQuantEncoding(MODE_RAW, raw = raw, rawDenominator = denominator)
        }

        /**
         * libjxl ComputeQuantTable: the weight of every coefficient from the table's description,
         * then the matrix, which holds 1 / weight. A weight outside of 1e-8 to 1e8 is not valid.
         * libjxl lets a weight that is not a number pass; this refuses it.
         */
        private fun computeTable(encoding: JxlQuantEncoding, table: Int): FloatArray {
            val rows = 8 * SHORT_SIDE[table]
            val cols = 8 * LONG_SIDE[table]
            val num = rows * cols
            val w = DoubleArray(3 * num)
            if (encoding.mode != MODE_DCT && encoding.mode != MODE_RAW && num != 64) jxlFail("quant table $table cannot have mode ${encoding.mode}")
            when (encoding.mode) {
                MODE_IDENTITY -> identityWeights(encoding.weights, w)
                MODE_DCT2 -> dct2Weights(encoding.weights, w)
                MODE_DCT4 -> {
                    // A 4 by 4 DCT weight serves the same frequency of each of the four interleaved DCTs.
                    val w4x4 = DoubleArray(3 * 16)
                    dctWeights(4, 4, encoding.bands!!, w4x4)
                    for (c in 0 until 3) {
                        for (y in 0 until 8) {
                            for (x in 0 until 8) w[c * 64 + y * 8 + x] = w4x4[c * 16 + (y / 2) * 4 + x / 2]
                        }
                        w[c * 64 + 1] /= encoding.weights[c * 2]
                        w[c * 64 + 8] /= encoding.weights[c * 2]
                        w[c * 64 + 9] /= encoding.weights[c * 2 + 1]
                    }
                }
                MODE_DCT4X8 -> {
                    val w4x8 = DoubleArray(3 * 32)
                    dctWeights(4, 8, encoding.bands!!, w4x8)
                    for (c in 0 until 3) {
                        for (y in 0 until 8) {
                            for (x in 0 until 8) w[c * 64 + y * 8 + x] = w4x8[c * 32 + (y / 2) * 8 + x]
                        }
                        w[c * 64 + 8] /= encoding.weights[c]
                    }
                }
                MODE_AFV -> afvWeights(encoding, w)
                MODE_DCT -> dctWeights(rows, cols, encoding.bands!!, w)
                MODE_RAW -> {
                    val raw = encoding.raw
                    if (raw == null || raw.size != 3 * num) jxlFail("raw quant table $table has the wrong size")
                    for (i in 0 until 3 * num) w[i] = 1.0 / (encoding.rawDenominator * raw[i])
                }
                else -> jxlFail("quant table $table has no description")
            }
            val matrix = FloatArray(3 * num)
            for (i in 0 until 3 * num) {
                val weight = w[i]
                if (!(weight >= ALMOST_ZERO && weight < 1.0 / ALMOST_ZERO)) jxlFail("quant table $table holds a weight that is not valid")
                matrix[i] = (1.0 / weight).toFloat()
            }
            return matrix
        }

        /** libjxl GetQuantWeightsIdentity: one weight for most samples, and two for the three places that hold the means. */
        private fun identityWeights(id: DoubleArray, w: DoubleArray) {
            for (c in 0 until 3) {
                for (i in 0 until 64) w[c * 64 + i] = id[c * 3]
                w[c * 64 + 1] = id[c * 3 + 1]
                w[c * 64 + 8] = id[c * 3 + 1]
                w[c * 64 + 9] = id[c * 3 + 2]
            }
        }

        /** libjxl GetQuantWeightsDCT2: two weights for each of the three rounds of the transform. */
        private fun dct2Weights(d: DoubleArray, w: DoubleArray) {
            for (c in 0 until 3) {
                val start = c * 64
                // The place of the mean is not used; libjxl puts 0xBAD there.
                w[start] = 2989.0
                w[start + 1] = d[c * 6]
                w[start + 8] = d[c * 6]
                w[start + 9] = d[c * 6 + 1]
                for (y in 0 until 2) {
                    for (x in 0 until 2) {
                        w[start + y * 8 + x + 2] = d[c * 6 + 2]
                        w[start + (y + 2) * 8 + x] = d[c * 6 + 2]
                        w[start + (y + 2) * 8 + x + 2] = d[c * 6 + 3]
                    }
                }
                for (y in 0 until 4) {
                    for (x in 0 until 4) {
                        w[start + y * 8 + x + 4] = d[c * 6 + 4]
                        w[start + (y + 4) * 8 + x] = d[c * 6 + 4]
                        w[start + (y + 4) * 8 + x + 4] = d[c * 6 + 5]
                    }
                }
            }
        }

        /** The weights of an AFV table: the corner's own weights, and those of a 4 by 4 and of a 4 by 8 DCT around them. */
        private fun afvWeights(encoding: JxlQuantEncoding, w: DoubleArray) {
            val afv = encoding.weights
            val w4x8 = DoubleArray(3 * 32)
            dctWeights(4, 8, encoding.bands!!, w4x8)
            val w4x4 = DoubleArray(3 * 16)
            dctWeights(4, 4, encoding.bands4x4!!, w4x4)
            val low = AFV_FREQUENCIES[2]
            val span = AFV_FREQUENCIES[15] - low + 1e-6
            val bands = DoubleArray(4)
            for (c in 0 until 3) {
                bands[0] = afv[c * 9 + 5]
                if (!(bands[0] >= ALMOST_ZERO)) jxlFail("AFV quant bands are not valid")
                for (i in 1 until 4) {
                    bands[i] = bands[i - 1] * bandStep(afv[c * 9 + 5 + i])
                    if (!(bands[i] >= ALMOST_ZERO)) jxlFail("AFV quant bands are not valid")
                }
                val start = c * 64
                // The place of the mean is not used.
                w[start] = 1.0
                // The corner's coefficients are on the even rows and columns. Its first ones have their own weights.
                w[start + 8] = afv[c * 9]
                w[start + 1] = afv[c * 9 + 1]
                w[start + 16] = afv[c * 9 + 2]
                w[start + 2] = afv[c * 9 + 3]
                w[start + 18] = afv[c * 9 + 4]
                for (y in 0 until 4) {
                    for (x in 0 until 4) {
                        if (x < 2 && y < 2) continue
                        val at = (AFV_FREQUENCIES[y * 4 + x] - low) * 3 / span
                        w[start + 2 * y * 8 + 2 * x] = interpolate(at, bands, 4)
                    }
                }
                // The 4 by 8 DCT is on the odd rows, and the 4 by 4 DCT on the odd columns of the even rows.
                for (y in 0 until 4) {
                    for (x in 0 until 8) {
                        if (x != 0 || y != 0) w[start + (2 * y + 1) * 8 + x] = w4x8[c * 32 + y * 8 + x]
                    }
                }
                for (y in 0 until 4) {
                    for (x in 0 until 4) {
                        if (x != 0 || y != 0) w[start + 2 * y * 8 + 2 * x + 1] = w4x4[c * 16 + y * 4 + x]
                    }
                }
            }
        }

        /**
         * libjxl GetQuantWeights: the weights of a DCT of [rows] by [cols] coefficients, three channels
         * into [out]. The weight depends on how far the coefficient is from frequency zero, with
         * both sides of the block counted as 1: the bands are that distance's stops, and the
         * weight between two stops is a geometric blend.
         */
        private fun dctWeights(rows: Int, cols: Int, params: JxlQuantBands, out: DoubleArray) {
            val count = params.count
            val bands = DoubleArray(count)
            // The far corner stays just below the last band, so a blend always has a next band.
            val scale = (count - 1) / (sqrt(2.0) + 1e-6)
            val perCol = scale / (cols - 1)
            val perRow = scale / (rows - 1)
            for (c in 0 until 3) {
                bands[0] = params.values[c * count]
                if (!(bands[0] >= ALMOST_ZERO)) jxlFail("quant distance bands are not valid")
                for (i in 1 until count) {
                    bands[i] = bands[i - 1] * bandStep(params.values[c * count + i])
                    if (!(bands[i] >= ALMOST_ZERO)) jxlFail("quant distance bands are not valid")
                }
                for (y in 0 until rows) {
                    val dy = y * perRow
                    val row = c * rows * cols + y * cols
                    for (x in 0 until cols) {
                        val dx = x * perCol
                        out[row + x] = if (count == 1) bands[0] else interpolate(sqrt(dx * dx + dy * dy), bands, count)
                    }
                }
            }
        }

        /** libjxl Mult: a positive step raises the weight by that share, a negative one divides it. */
        private fun bandStep(v: Double): Double = if (v > 0.0) 1.0 + v else 1.0 / (1.0 - v)

        /** The weight at [position], counted in bands. libjxl uses an approximate power here (FastPowf, about 3e-5 off). */
        private fun interpolate(position: Double, bands: DoubleArray, count: Int): Double {
            val index = minOf(position.toInt(), count - 2)
            val a = bands[index]
            val b = bands[index + 1]
            return a * (b / a).pow(position - index)
        }

        // libjxl kFreqs of ComputeQuantTable: the frequency each coefficient of the AFV corner stands for. The first two of rows 0 and 1 are not used.
        private val AFV_FREQUENCIES = doubleArrayOf(
            0.0, 0.0, 0.8517778890324296, 5.37778436506804,
            0.0, 0.0, 4.734747904497923, 5.449245381693219,
            1.6598270267479331, 4.0, 7.275749096817861, 10.423227632456525,
            2.662932286148962, 7.630657783650829, 8.962388608184032, 12.97166202570235,
        )

        private fun bands(count: Int, vararg values: Double): JxlQuantBands = JxlQuantBands(count, values)

        private fun dct(count: Int, vararg values: Double): JxlQuantEncoding = JxlQuantEncoding(MODE_DCT, bands = JxlQuantBands(count, values))

        /** The library's table for the transforms of 64 samples a side and above, which differ only in the first weight of each channel. */
        private fun largeDct(x: Double, y: Double, b: Double): JxlQuantEncoding = dct(
            8,
            x, -1.025, -0.78, -0.65012, -0.19041574084286472, -0.20819395464, -0.421064, -0.32733845535848671,
            y, -0.3041958212306401, -0.3633036457487539, -0.35660379990111464, -0.3443074455424403, -0.33699592683512467,
            -0.30180866526242109, -0.27321683125358037,
            b, -1.2, -1.2, -0.8, -0.7, -0.7, -0.4, -0.5,
        )

        private fun library4x4Bands(): JxlQuantBands = bands(
            4,
            2200.0, 0.0, 0.0, 0.0,
            392.0, 0.0, 0.0, 0.0,
            112.0, -0.25, -0.25, -0.5,
        )

        private fun library4x8Bands(): JxlQuantBands = bands(
            4,
            2198.050556016380522, -0.96269623020744692, -0.76194253026666783, -0.6551140670773547,
            764.3655248643528689, -0.92630200888366945, -0.9675229603596517, -0.27845290869168118,
            527.107573587542228, -1.4594385811273854, -1.450082094097871593, -1.5843722511996204,
        )

        /** libjxl DequantMatricesLibraryDef, quant_weights.cc: the description of each table of the library. */
        private fun libraryEncoding(table: Int): JxlQuantEncoding = when (table) {
            // DCT
            0 -> dct(
                6,
                3150.0, 0.0, -0.4, -0.4, -0.4, -2.0,
                560.0, 0.0, -0.3, -0.3, -0.3, -0.3,
                512.0, -2.0, -1.0, 0.0, -1.0, -2.0,
            )
            // IDENTITY
            1 -> JxlQuantEncoding(
                MODE_IDENTITY,
                doubleArrayOf(
                    280.0, 3160.0, 3160.0,
                    60.0, 864.0, 864.0,
                    18.0, 200.0, 200.0,
                ),
            )
            // DCT2X2
            2 -> JxlQuantEncoding(
                MODE_DCT2,
                doubleArrayOf(
                    3840.0, 2560.0, 1280.0, 640.0, 480.0, 300.0,
                    960.0, 640.0, 320.0, 180.0, 140.0, 120.0,
                    640.0, 320.0, 128.0, 64.0, 32.0, 16.0,
                ),
            )
            // DCT4X4
            3 -> JxlQuantEncoding(MODE_DCT4, doubleArrayOf(1.0, 1.0, 1.0, 1.0, 1.0, 1.0), library4x4Bands())
            // DCT16X16
            4 -> dct(
                7,
                8996.8725711814115328, -1.3000777393353804, -0.49424529824571225, -0.439093774457103443, -0.6350101832695744,
                -0.90177264050827612, -1.6162099239887414,
                3191.48366296844234752, -0.67424582104194355, -0.80745813428471001, -0.44925837484843441, -0.35865440981033403,
                -0.31322389111877305, -0.37615025315725483,
                1157.50408145487200256, -2.0531423165804414, -1.4, -0.50687130033378396, -0.42708730624733904,
                -1.4856834539296244, -4.9209142884401604,
            )
            // DCT32X32
            5 -> dct(
                8,
                15718.40830982518931456, -1.025, -0.98, -0.9012, -0.4, -0.48819395464, -0.421064, -0.27,
                7305.7636810695983104, -0.8041958212306401, -0.7633036457487539, -0.55660379990111464, -0.49785304658857626,
                -0.43699592683512467, -0.40180866526242109, -0.27321683125358037,
                3803.53173721215041536, -3.060733579805728, -2.0413270132490346, -2.0235650159727417, -0.5495389509954993,
                -0.4, -0.4, -0.3,
            )
            // DCT16X8 and DCT8X16
            6 -> dct(
                7,
                7240.7734393502, -0.7, -0.7, -0.2, -0.2, -0.2, -0.5,
                1448.15468787004, -0.5, -0.5, -0.5, -0.2, -0.2, -0.2,
                506.854140754517, -1.4, -0.2, -0.5, -0.5, -1.5, -3.6,
            )
            // DCT32X8 and DCT8X32
            7 -> dct(
                8,
                16283.2494710648897, -1.7812845336559429, -1.6309059012653515, -1.0382179034313539, -0.85, -0.7, -0.9,
                -1.2360638576849587,
                5089.15750884921511936, -0.320049391452786891, -0.35362849922161446, -0.30340000000000003, -0.61, -0.5, -0.5,
                -0.6,
                3397.77603275308720128, -0.321327362693153371, -0.34507619223117997, -0.70340000000000003, -0.9, -1.0, -1.0,
                -1.1754605576265209,
            )
            // DCT32X16 and DCT16X32
            8 -> dct(
                8,
                13844.97076442300573, -0.97113799999999995, -0.658, -0.42026, -0.22712, -0.2206, -0.226, -0.6,
                4798.964084220744293, -0.61125308982767057, -0.83770786552491361, -0.79014862079498627, -0.2692727459704829,
                -0.38272769465388551, -0.22924222653091453, -0.20719098826199578,
                1807.236946760964614, -1.2, -1.2, -0.7, -0.7, -0.7, -0.4, -0.5,
            )
            // DCT4X8 and DCT8X4
            9 -> JxlQuantEncoding(MODE_DCT4X8, doubleArrayOf(1.0, 1.0, 1.0), library4x8Bands())
            // AFV0 to AFV3
            10 -> JxlQuantEncoding(
                MODE_AFV,
                doubleArrayOf(
                    3072.0, 3072.0, 256.0, 256.0, 256.0, 414.0, 0.0, 0.0, 0.0,
                    1024.0, 1024.0, 50.0, 50.0, 50.0, 58.0, 0.0, 0.0, 0.0,
                    384.0, 384.0, 12.0, 12.0, 12.0, 22.0, -0.25, -0.25, -0.25,
                ),
                library4x8Bands(),
                library4x4Bands(),
            )
            // DCT64X64
            11 -> largeDct(0.9 * 26629.073922049845, 0.9 * 9311.3238710010046, 0.9 * 4992.2486445538634)
            // DCT64X32 and DCT32X64
            12 -> largeDct(0.65 * 23629.073922049845, 0.65 * 8611.3238710010046, 0.65 * 4492.2486445538634)
            // DCT128X128
            13 -> largeDct(1.8 * 26629.073922049845, 1.8 * 9311.3238710010046, 1.8 * 4992.2486445538634)
            // DCT128X64 and DCT64X128
            14 -> largeDct(1.3 * 23629.073922049845, 1.3 * 8611.3238710010046, 1.3 * 4492.2486445538634)
            // DCT256X256
            15 -> largeDct(3.6 * 26629.073922049845, 3.6 * 9311.3238710010046, 3.6 * 4992.2486445538634)
            // DCT256X128 and DCT128X256
            else -> largeDct(2.6 * 23629.073922049845, 2.6 * 8611.3238710010046, 2.6 * 4492.2486445538634)
        }
    }
}
