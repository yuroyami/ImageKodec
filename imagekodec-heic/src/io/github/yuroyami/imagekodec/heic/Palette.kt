package io.github.yuroyami.imagekodec.heic

/**
 * Palette mode of the screen content coding extension: palette_coding( ) of 7.3.8.13 with the semantics of
 * 7.4.9.13, the binarizations of 9.3.3.12 to 9.3.3.14, and the reconstruction of 8.4.1 and 8.4.4.2.7. The
 * predictor palette lives as long as the contexts of [cabac] do: [initialise], [store] and [restore] follow
 * 9.3.2.3 to 9.3.2.5, and each coding unit updates it by 8-79.
 *
 * The index map, its copy-above flags and the escape values sit in the coding unit's own raster, in the scan
 * coordinates of the syntax, before palette_transpose_flag turns them.
 */
internal class PaletteDecoder(private val d: PictureDecoder, private val cabac: Cabac) {
    private val sps = d.sps
    private val pps = d.pps
    /** numComps of 7-82 and 9-8. */
    private val comps = if (d.chromaArrayType == 0) 1 else 3
    private val maxSize = sps.paletteMaxSize
    private val maxPredictorSize = sps.paletteMaxPredictorSize
    private val bitDepth = IntArray(3) { c -> if (c == 0) sps.bitDepthY else sps.bitDepthC }

    /** PredictorPaletteEntries and PredictorPaletteSize, then the copies 9.3.2.4 keeps for WPP and a dependent slice segment. */
    private val predictor = Array(3) { Array(3) { IntArray(MAX_PREDICTOR) } }
    private val predictorSize = IntArray(3)

    private val reuse = BooleanArray(MAX_PREDICTOR)
    /** CurrentPaletteEntries (7-82). */
    private val entries = Array(3) { IntArray(MAX_PALETTE) }
    private val next = Array(3) { IntArray(MAX_PREDICTOR) }
    /** PaletteIndexIdc, up to one index per sample of a 32x32 unit. */
    private val indexIdc = IntArray(32 * 32)
    private val indexMap = IntArray(32 * 32)
    private val copyAbove = BooleanArray(32 * 32)
    private val escape = Array(3) { IntArray(32 * 32) }

    /** 9.3.2.3: the initializers of the PPS, else those of the SPS, else an empty predictor palette. */
    fun initialise() {
        val init = if (pps.palettePredictorInitializersPresent) pps.palettePredictorInitializers else sps.palettePredictorInitializers
        val size = init?.get(0)?.size ?: 0
        // Pps.checkAgainst bounds a PPS's count by PaletteMaxPredictorSize and readSpsScc the SPS's.
        predictorSize[CURRENT] = size
        // An empty list of the PPS holds one component only.
        if (init != null && size > 0) for (c in 0 until comps) init[c].copyInto(predictor[CURRENT][c], 0, 0, size)
    }

    /** 9.3.2.4: keeps the predictor palette in copy [slot], [WPP] or [DEPENDENT]. */
    fun store(slot: Int) {
        predictorSize[slot] = predictorSize[CURRENT]
        for (c in 0 until comps) predictor[CURRENT][c].copyInto(predictor[slot][c], 0, 0, predictorSize[CURRENT])
    }

    /** 9.3.2.5: takes the predictor palette back from copy [slot]. */
    fun restore(slot: Int) {
        predictorSize[CURRENT] = predictorSize[slot]
        for (c in 0 until comps) predictor[slot][c].copyInto(predictor[CURRENT][c], 0, 0, predictorSize[slot])
    }

    /**
     * palette_coding( ) of the coding unit at ([x0], [y0]) of 1 shl [log2] luma samples, then its samples
     * (8.4.4.2.7) written into the picture and the predictor palette updated (8-79). [bypass] is
     * cu_transquant_bypass_flag.
     */
    fun codingUnit(x0: Int, y0: Int, log2: Int, bypass: Boolean) {
        val n = 1 shl log2
        val total = n * n
        val c = cabac
        val pred = predictor[CURRENT]
        val predSize = predictorSize[CURRENT]
        // palette_predictor_run, EG0: the zeros before each entry reused, and 1 to stop.
        reuse.fill(false, 0, predSize)
        var numPredicted = 0
        var i = 0
        while (i < predSize && numPredicted < maxSize) {
            val run = expGolomb(0, "palette_predictor_run")
            if (run == 1) break
            if (run > 1) i += run - 1
            if (i >= predSize) hevcError("palette_predictor_run passes the predictor palette of $predSize entries")
            reuse[i] = true
            numPredicted++
            i++
        }
        val signalled = if (numPredicted < maxSize) expGolomb(0, "num_signalled_palette_entries") else 0
        // 7-81: CurrentPaletteSize is at most palette_max_size.
        if (numPredicted + signalled > maxSize) hevcError("a palette of ${numPredicted + signalled} entries passes palette_max_size $maxSize")
        val size = numPredicted + signalled
        var k = 0
        for (p in 0 until predSize) if (reuse[p]) {
            for (comp in 0 until comps) entries[comp][k] = pred[comp][p]
            k++
        }
        for (comp in 0 until comps) for (e in 0 until signalled) entries[comp][numPredicted + e] = c.bypassBits(bitDepth[comp])
        // palette_escape_val_present_flag, inferred 1 for an empty palette.
        val escapes = if (size != 0) c.bypass() == 1 else true
        val maxIndex = size - 1 + (if (escapes) 1 else 0)
        var numIndices = 1
        var finalRunCopy = false
        var transpose = false
        indexIdc[0] = 0
        if (maxIndex > 0) {
            numIndices = numIndicesMinus1(maxIndex) + 1
            if (numIndices > total) hevcError("num_palette_indices_minus1 ${numIndices - 1} passes the $total samples of the coding unit")
            // palette_idx_idc, TB with cMax MaxPaletteIndex for the first and MaxPaletteIndex - 1 for the others (9.3.3.13).
            for (j in 0 until numIndices) {
                val cMax = if (j == 0) maxIndex else maxIndex - 1
                indexIdc[j] = if (cMax > 0) truncatedBinary(cMax) else 0
            }
            finalRunCopy = c.decode(Ctx.COPY_ABOVE) == 1
            transpose = c.decode(Ctx.PALETTE_TRANSPOSE) == 1
        }
        if (escapes) d.paletteQp(bypass)
        indexRuns(n, maxIndex, numIndices, finalRunCopy)
        if (escapes) escapeValues(n, maxIndex, transpose, bypass)
        reconstruct(x0, y0, n, size, maxIndex, escapes, transpose, bypass)
        updatePredictor(size, predSize)
    }

    /**
     * The loop of 7.3.8.13 over the traverse scan (6.5.6): runs of one index, or of the indices of the row
     * above, into [indexMap], with the index adjustment of 7-83 and 7-84.
     */
    private fun indexRuns(n: Int, maxIndex: Int, numIndices: Int, finalRunCopy: Boolean) {
        val total = n * n
        var remaining = numIndices
        var pos = 0
        val finalCopy = if (finalRunCopy) 1 else 0
        while (pos < total) {
            val at = traverse(pos, n)
            val prev = if (pos > 0) traverse(pos - 1, n) else -1
            var copy = false
            if (maxIndex > 0 && pos >= n && !copyAbove[prev]) {
                copy = if (remaining > 0 && pos < total - 1) cabac.decode(Ctx.COPY_ABOVE) == 1 else !(pos == total - 1 && remaining > 0)
            }
            var index = 0
            var idc = 0
            if (!copy) {
                val used = numIndices - remaining
                if (used >= numIndices) hevcError("the palette runs need more than num_palette_indices_minus1 + 1 indices")
                idc = indexIdc[used]
                index = idc
                // 7-83 and 7-84: the index of the sample before, or of the one above after a copied run, is not coded.
                if (pos > 0) {
                    val adjustedRef = if (!copyAbove[prev]) indexMap[prev] else indexMap[at - n]
                    if (index >= adjustedRef) index++
                }
                if (index > maxIndex) hevcError("palette index $index passes MaxPaletteIndex $maxIndex")
            }
            var run = total - pos - 1
            if (maxIndex > 0) {
                if (!copy) remaining--
                if (remaining > 0 || (if (copy) 1 else 0) != finalCopy) {
                    val maxRun = total - pos - 1 - remaining - finalCopy
                    if (maxRun < 0) hevcError("PaletteMaxRunMinus1 is $maxRun")
                    run = if (maxRun > 0) runMinus1(maxRun, copy, idc) else 0
                }
            }
            for (r in 0..run) {
                val u = traverse(pos, n)
                copyAbove[u] = copy
                indexMap[u] = if (copy) indexMap[u - n] else index
                pos++
            }
        }
    }

    /** PaletteRunMinus1 of 7-85 and 7-86: palette_run_prefix (TR, the contexts of 9.3.4.2.8) and palette_run_suffix (TB). */
    private fun runMinus1(maxRun: Int, copy: Boolean, idc: Int): Int {
        val cMax = 31 - maxRun.countLeadingZeroBits() + 1
        var prefix = 0
        while (prefix < cMax) {
            val bin = if (prefix > 4) {
                cabac.bypass()
            } else {
                // Table 9-51, and 9-63 for the first bin of a coded index.
                val inc = when {
                    prefix == 0 && !copy -> if (idc < 1) 0 else if (idc < 3) 1 else 2
                    prefix == 0 -> 5
                    prefix <= 2 -> if (copy) 6 else 3
                    else -> if (copy) 7 else 4
                }
                cabac.decode(Ctx.PALETTE_RUN_PREFIX + inc)
            }
            if (bin == 0) break
            prefix++
        }
        if (prefix < 2) return prefix
        val offset = 1 shl (prefix - 1)
        if (maxRun == offset) return offset
        val suffixMax = if ((offset shl 1) > maxRun) maxRun - offset else offset - 1
        return offset + truncatedBinary(suffixMax)
    }

    /** palette_escape_val of each escape sample of each component (7.3.8.13), with the binarization of 9.3.3.12. */
    private fun escapeValues(n: Int, maxIndex: Int, transpose: Boolean, bypass: Boolean) {
        val chromaType = d.chromaArrayType
        for (comp in 0 until comps) {
            val limit = (1 shl (bitDepth[comp] + 1)) - 1
            val values = escape[comp]
            for (pos in 0 until n * n) {
                val u = traverse(pos, n)
                if (indexMap[u] != maxIndex) continue
                val x = u and (n - 1)
                val y = u shr (31 - n.countLeadingZeroBits())
                // The chroma samples that exist at this position of the scan.
                val coded = comp == 0 || chromaType == 3 ||
                    (chromaType == 1 && x % 2 == 0 && y % 2 == 0) ||
                    (chromaType == 2 && (if (transpose) y % 2 == 0 else x % 2 == 0))
                if (!coded) continue
                val v = if (bypass) cabac.bypassBits(bitDepth[comp]) else expGolomb(3, "palette_escape_val")
                // 7.4.9.13: PaletteEscapeVal is below 2^( BitDepth + 1 ).
                if (v > limit) hevcError("palette_escape_val $v passes ${limit}")
                values[u] = v
            }
        }
    }

    /**
     * 8.4.1 and 8.4.4.2.7: each sample of each component takes its palette entry, or its escape value, scaled by
     * 8-77 unless bypassed. A component sample at ( x, y ) sits over the luma sample ( x * SubWidthC, y * SubHeightC ),
     * which palette_transpose_flag finds at the transposed position of the scan. 8-13 with 8-69 and 8-70 as printed
     * transpose twice, which would leave the flag without effect; the conformance streams PPI_A and PPI_B take the
     * transposed position, as the escape samples of 4:2:2 in 7.3.8.13 do.
     */
    private fun reconstruct(x0: Int, y0: Int, n: Int, size: Int, maxIndex: Int, escapes: Boolean, transpose: Boolean, bypass: Boolean) {
        val log2 = 31 - n.countLeadingZeroBits()
        for (comp in 0 until comps) {
            val sw = if (comp == 0) 0 else d.subWShift
            val sh = if (comp == 0) 0 else d.subHShift
            val plane = d.planes[comp]
            val stride = d.strides[comp]
            val max = (1 shl bitDepth[comp]) - 1
            val qp = if (escapes && !bypass) maxOf(0, if (comp == 0) d.lumaQpPrime else d.chromaQp(comp)) else 0
            val scale = LEVEL_SCALE[qp % 6]
            val shift = qp / 6
            val values = entries[comp]
            val esc = escape[comp]
            for (y in 0 until (n shr sh)) {
                val row = ((y0 shr sh) + y) * stride + (x0 shr sw)
                for (x in 0 until (n shr sw)) {
                    val lx = x shl sw
                    val ly = y shl sh
                    val u = if (transpose) (lx shl log2) + ly else (ly shl log2) + lx
                    val index = indexMap[u]
                    val v = if (!escapes || index != maxIndex) {
                        if (index >= size) hevcError("palette index $index has no entry")
                        values[index]
                    } else if (bypass) {
                        esc[u]
                    } else {
                        dequantise(esc[u], scale, shift, max)
                    }
                    plane[row + x] = v.toShort()
                }
            }
        }
    }

    /** 8-79: the current palette first, then the predictor entries it did not reuse, up to PaletteMaxPredictorSize. */
    private fun updatePredictor(size: Int, predSize: Int) {
        val pred = predictor[CURRENT]
        for (comp in 0 until comps) entries[comp].copyInto(next[comp], 0, 0, size)
        var newSize = size
        for (p in 0 until predSize) {
            if (newSize >= maxPredictorSize) break
            if (reuse[p]) continue
            for (comp in 0 until comps) next[comp][newSize] = pred[comp][p]
            newSize++
        }
        for (comp in 0 until comps) next[comp].copyInto(pred[comp], 0, 0, newSize)
        predictorSize[CURRENT] = newSize
    }

    /** num_palette_indices_minus1 (9.3.3.14): a TR prefix of up to four ones with cRiceParam, then EG( cRiceParam + 1 ). */
    private fun numIndicesMinus1(maxIndex: Int): Int {
        val rice = 3 + ((maxIndex + 1) shr 3)
        var ones = 0
        while (ones < 4 && cabac.bypass() == 1) ones++
        if (ones < 4) return (ones shl rice) + cabac.bypassBits(rice)
        return (4 shl rice) + expGolomb(rice + 1, "num_palette_indices_minus1")
    }

    /** The TB binarization of 9.3.3.6 with [cMax], every bin bypass. */
    private fun truncatedBinary(cMax: Int): Int {
        val count = cMax + 1
        val k = 31 - count.countLeadingZeroBits()
        val u = (1 shl (k + 1)) - count
        val v = cabac.bypassBits(k)
        return if (v < u) v else ((v shl 1) or cabac.bypass()) - u
    }

    /** The k-th order Exp-Golomb code of 9.3.3.3, every bin bypass. No value of [name] needs a longer prefix than 16. */
    private fun expGolomb(order: Int, name: String): Int {
        var k = order
        var v = 0
        while (cabac.bypass() == 1) {
            v += 1 shl k
            if (++k > 24) hevcError("$name is out of range")
        }
        return v + cabac.bypassBits(k)
    }

    internal companion object {
        const val CURRENT = 0
        const val WPP = 1
        const val DEPENDENT = 2
        /** PaletteMaxPredictorSize is at most 128 and palette_max_size 64 (A.3.7). */
        const val MAX_PREDICTOR = 128
        const val MAX_PALETTE = 64
        private val LEVEL_SCALE = intArrayOf(40, 45, 51, 57, 64, 72)

        /** The raster position within a block of side [n] of traverse scan position [pos] (6-14): rows alternate direction. */
        fun traverse(pos: Int, n: Int): Int {
            val log2 = 31 - n.countLeadingZeroBits()
            val y = pos shr log2
            val x = pos and (n - 1)
            return (y shl log2) + (if (y and 1 == 0) x else n - 1 - x)
        }

        /**
         * 8-77 and 8-78: an escape value scaled by levelScale [scale] and 2^[shift], in 1/64. The product of a value
         * below 2^17 and a scale below 2^7 fits an Int, and a shift that would pass 31 bits saturates at [max].
         */
        fun dequantise(value: Int, scale: Int, shift: Int, max: Int): Int {
            val product = value * scale
            if (product > ((Int.MAX_VALUE - 32) shr shift)) return max
            val v = ((product shl shift) + 32) shr 6
            return if (v > max) max else v
        }
    }
}
