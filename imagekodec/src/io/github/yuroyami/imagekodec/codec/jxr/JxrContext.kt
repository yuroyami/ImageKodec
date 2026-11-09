/*
 * JPEG XR coding contexts: a Kotlin port of jxrlib's CCodingContext with AllocateCodingContextDec,
 * ResetCodingContextDec (image/decode/decode.c), ResetCodingContext, InitZigzagScan and
 * UpdateModelMB (image/sys/image.c), at 4creators/jxrlib commit 1ef17dd.
 *
 * Copyright (c) Microsoft Corp. All rights reserved.
 *
 * Use of this source code is governed by the BSD 2-Clause license that is reproduced in this
 * repository's NOTICE.
 */
package io.github.yuroyami.imagekodec.codec.jxr

/** The adaptive state of one tile column: its code tables, scan orders and models (T.832 9.2). */
internal class JxrContext(cbpSymbols: Int) {
    // A band whose packet the codestream does not have reads as no data at all.
    var ioDC: JxrBits = NO_PACKET
    var ioLP: JxrBits = NO_PACKET
    var ioAC: JxrBits = NO_PACKET
    var ioFL: JxrBits? = null

    val cbpY = JxrVlc(cbpSymbols)
    val cbpY1 = JxrVlc(5)
    val expt = Array(VLC_TABLES) { JxrVlc(ALPHABET[it]) }

    val scanLowpass = Scan()
    val scanHoriz = Scan()
    val scanVert = Scan()

    val modelAC = Model(BAND_AC)
    val modelLP = Model(BAND_LP)
    val modelDC = Model(BAND_DC)

    var cbpCountZero = 0
    var cbpCountMax = 0
    val cbpCount0 = IntArray(2)
    val cbpCount1 = IntArray(2)
    val cbpState = IntArray(2)

    var trimFlexbits = 0

    /** An adaptive scan order (CAdaptiveScan): where each position puts its coefficient, and how often it was hit. */
    class Scan {
        val scan = IntArray(16)
        val total = IntArray(16)

        /** Counts a hit at [i] and moves it ahead of a position hit less. */
        fun hit(i: Int) {
            total[i]++
            if (i > 0 && total[i] > total[i - 1]) {
                val t = total[i]
                total[i] = total[i - 1]
                total[i - 1] = t
                val s = scan[i]
                scan[i] = scan[i - 1]
                scan[i - 1] = s
            }
        }

        /** The totals of a reset, at the first macroblock of each run of 16. */
        fun resetTotals() {
            total[0] = MAX_TOTAL
            var weight = 32
            for (k in 1 until 16) {
                total[k] = weight
                weight -= 2
            }
        }
    }

    /** The bits of a band coded without a code, adapted at the end of each macroblock (CAdaptiveModel). */
    class Model(val band: Int) {
        val flcState = IntArray(2)
        val flcBits = IntArray(2)
    }

    /** ResetCodingContextDec. */
    fun reset() {
        cbpY.initialized = false
        cbpY1.initialized = false
        for (v in expt) v.initialized = false
        adaptLowpass()
        adaptHighpass()
        for (i in 0 until 16) {
            scanLowpass.scan[i] = ZIGZAG_LOWPASS[i]
            scanHoriz.scan[i] = DCT_INDEX[ZIGZAG_H[i]]
            scanVert.scan[i] = DCT_INDEX[ZIGZAG_V[i]]
        }
        for (m in arrayOf(modelAC, modelLP, modelDC)) {
            m.flcState.fill(0)
            m.flcBits.fill(0)
        }
        modelLP.flcBits.fill(4)
        modelDC.flcBits.fill(8)
        cbpCountMax = 1
        cbpCountZero = 1
        cbpCount0.fill(-4)
        cbpCount1.fill(4)
        cbpState.fill(0)
    }

    /** AdaptLowpassDec: the tables of the DC and lowpass bands. */
    fun adaptLowpass() {
        for (k in 0 until CONTEXTX + CTDC) expt[k].adapt()
    }

    /** AdaptHighpassDec: the coded block pattern tables and those of the highpass band. */
    fun adaptHighpass() {
        cbpY.adapt()
        cbpY1.adapt()
        for (k in 0 until CONTEXTX) expt[k + CONTEXTX + CTDC].adapt()
    }

    companion object {
        const val CONTEXTX = 8
        const val CTDC = 5
        const val VLC_TABLES = 21
        const val MAX_TOTAL = 32767

        private val NO_PACKET = JxrBits(ByteArray(0), 0, 0)

        const val BAND_DC = 1
        const val BAND_LP = 2
        const val BAND_AC = 3

        /** The alphabet of each table of m_pAHexpt (AllocateCodingContextDec). */
        private val ALPHABET = intArrayOf(5, 4, 8, 7, 7, 12, 6, 6, 12, 6, 6, 7, 7, 12, 6, 6, 12, 6, 6, 7, 7)

        /** grgiZigzagInv4x4_lowpass, grgiZigzagInv4x4H and grgiZigzagInv4x4V of image.c. */
        private val ZIGZAG_LOWPASS = intArrayOf(0, 1, 4, 5, 2, 8, 6, 9, 3, 12, 10, 7, 13, 11, 14, 15)
        private val ZIGZAG_H = ZIGZAG_LOWPASS
        private val ZIGZAG_V = intArrayOf(0, 4, 8, 5, 1, 12, 9, 6, 2, 13, 3, 15, 7, 10, 14, 11)

        /** dctIndex[0] of strcodec.c: where each coefficient of a 4x4 block sits in its 16 samples. */
        val DCT_INDEX = intArrayOf(0, 5, 1, 6, 10, 12, 8, 14, 2, 4, 3, 7, 9, 13, 11, 15)

        private const val MODEL_WEIGHT = 70
        private val WEIGHT0 = intArrayOf(240, 12, 1)
        private val WEIGHT1 = arrayOf(
            intArrayOf(0, 240, 120, 80, 60, 48, 40, 34, 30, 27, 24, 22, 20, 18, 17, 16),
            intArrayOf(0, 12, 6, 4, 3, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1),
            intArrayOf(0, 16, 8, 5, 4, 3, 3, 2, 2, 2, 2, 1, 1, 1, 1, 1),
        )
        private val WEIGHT2 = intArrayOf(120, 37, 2, 120, 18, 1)

        /** UpdateModelMB: moves a model's bit counts by the coefficients a macroblock coded. */
        fun updateModel(cf: Int, channels: Int, laplacianMean: IntArray, model: Model) {
            val band = model.band - BAND_DC
            laplacianMean[0] *= WEIGHT0[band]
            when (cf) {
                JxrColor.YUV_420 -> laplacianMean[1] *= WEIGHT2[band]
                JxrColor.YUV_422 -> laplacianMean[1] *= WEIGHT2[3 + band]
                else -> {
                    laplacianMean[1] *= WEIGHT1[band][channels - 1]
                    if (model.band == BAND_AC) laplacianMean[1] = laplacianMean[1] shr 4
                }
            }
            for (j in 0 until 2) {
                val lm = laplacianMean[j]
                var ms = model.flcState[j]
                var delta = (lm - MODEL_WEIGHT) shr 2
                if (delta <= -8) {
                    delta += 4
                    if (delta < -16) delta = -16
                    ms += delta
                    if (ms < -8) {
                        if (model.flcBits[j] == 0) {
                            ms = -8
                        } else {
                            ms = 0
                            model.flcBits[j]--
                        }
                    }
                } else if (delta >= 8) {
                    delta -= 4
                    if (delta > 15) delta = 15
                    ms += delta
                    if (ms > 8) {
                        if (model.flcBits[j] >= 15) {
                            model.flcBits[j] = 15
                            ms = 8
                        } else {
                            ms = 0
                            model.flcBits[j]++
                        }
                    }
                }
                model.flcState[j] = ms
                if (cf == JxrColor.Y_ONLY) break
            }
        }
    }
}
