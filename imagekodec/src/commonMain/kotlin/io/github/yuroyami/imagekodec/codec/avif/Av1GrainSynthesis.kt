package io.github.yuroyami.imagekodec.codec.avif

/**
 * The AV1 film grain synthesis process (specification section 7.18.3), on a copy of the
 * output planes so the frame a later frame predicts from stays without grain.
 *
 * The specification builds the noise for the whole frame first; this builds it one stripe
 * of 32 luma rows at a time, keeping the stripe before for the vertical overlap, and blends
 * each stripe's chroma before its luma, as chroma reads the luma without its noise.
 */
internal class Av1GrainSynthesis(
    private val g: Av1FilmGrain,
    private val bitDepth: Int,
    private val subX: Int,
    private val subY: Int,
    private val numPlanes: Int,
    private val identityMatrix: Boolean,
) {
    private var random = 0
    private val grainCenter = 128 shl (bitDepth - 8)
    private val grainMin = -grainCenter
    private val grainMax = (256 shl (bitDepth - 8)) - 1 - grainCenter
    private val lumaGrain = IntArray(73 * 82)
    private val chromaW = if (subX != 0) 44 else 82
    private val chromaH = if (subY != 0) 38 else 73
    private val cbGrain = IntArray(chromaW * chromaH)
    private val crGrain = IntArray(chromaW * chromaH)
    private val scalingLut = Array(3) { IntArray(256) }

    /** [planes] of a [w] by [h] frame, rows [strides] apart, with the grain added. */
    fun apply(planes: Array<IntArray>, strides: IntArray, w: Int, h: Int): Array<IntArray> {
        val out = Array(planes.size) { planes[it].copyOf() }
        random = g.grainSeed
        generateGrain()
        initialiseScaling()
        addNoise(out, strides, w, h)
        return out
    }

    private fun randomNumber(bits: Int): Int {
        var r = random
        val bit = (r xor (r shr 1) xor (r shr 3) xor (r shr 12)) and 1
        r = (r shr 1) or (bit shl 15)
        random = r
        return (r shr (16 - bits)) and ((1 shl bits) - 1)
    }

    private fun generateGrain() {
        val gaussian = Av1Tables.gaussianSequence
        var shift = 12 - bitDepth + g.grainScaleShift
        for (i in 0 until 73 * 82) {
            val v = if (g.numYPoints > 0) gaussian[randomNumber(11)] else 0
            lumaGrain[i] = Av1.round2(v, shift)
        }
        val lag = g.arCoeffLag
        shift = g.arCoeffShiftMinus6 + 6
        for (y in 3 until 73) {
            for (x in 3 until 82 - 3) {
                var s = 0
                var pos = 0
                rows@ for (deltaRow in -lag..0) {
                    for (deltaCol in -lag..lag) {
                        if (deltaRow == 0 && deltaCol == 0) break@rows
                        val c = g.arCoeffsYPlus128[pos] - 128
                        s += lumaGrain[(y + deltaRow) * 82 + x + deltaCol] * c
                        pos++
                    }
                }
                lumaGrain[y * 82 + x] = (lumaGrain[y * 82 + x] + Av1.round2(s, shift)).coerceIn(grainMin, grainMax)
            }
        }
        if (numPlanes == 1) return
        shift = 12 - bitDepth + g.grainScaleShift
        random = g.grainSeed xor 0xb524
        for (i in 0 until chromaW * chromaH) {
            val v = if (g.numCbPoints > 0 || g.chromaScalingFromLuma) gaussian[randomNumber(11)] else 0
            cbGrain[i] = Av1.round2(v, shift)
        }
        random = g.grainSeed xor 0x49d8
        for (i in 0 until chromaW * chromaH) {
            val v = if (g.numCrPoints > 0 || g.chromaScalingFromLuma) gaussian[randomNumber(11)] else 0
            crGrain[i] = Av1.round2(v, shift)
        }
        shift = g.arCoeffShiftMinus6 + 6
        for (y in 3 until chromaH) {
            for (x in 3 until chromaW - 3) {
                var s0 = 0
                var s1 = 0
                var pos = 0
                rows@ for (deltaRow in -lag..0) {
                    for (deltaCol in -lag..lag) {
                        val c0 = g.arCoeffsCbPlus128[pos] - 128
                        val c1 = g.arCoeffsCrPlus128[pos] - 128
                        if (deltaRow == 0 && deltaCol == 0) {
                            if (g.numYPoints > 0) {
                                var luma = 0
                                val lumaX = ((x - 3) shl subX) + 3
                                val lumaY = ((y - 3) shl subY) + 3
                                for (i in 0..subY) for (j in 0..subX) luma += lumaGrain[(lumaY + i) * 82 + lumaX + j]
                                luma = Av1.round2(luma, subX + subY)
                                s0 += luma * c0
                                s1 += luma * c1
                            }
                            break@rows
                        }
                        s0 += cbGrain[(y + deltaRow) * chromaW + x + deltaCol] * c0
                        s1 += crGrain[(y + deltaRow) * chromaW + x + deltaCol] * c1
                        pos++
                    }
                }
                cbGrain[y * chromaW + x] = (cbGrain[y * chromaW + x] + Av1.round2(s0, shift)).coerceIn(grainMin, grainMax)
                crGrain[y * chromaW + x] = (crGrain[y * chromaW + x] + Av1.round2(s1, shift)).coerceIn(grainMin, grainMax)
            }
        }
    }

    private fun initialiseScaling() {
        for (plane in 0 until numPlanes) {
            val fromLuma = plane == 0 || g.chromaScalingFromLuma
            val numPoints = if (fromLuma) g.numYPoints else if (plane == 1) g.numCbPoints else g.numCrPoints
            val xs = if (fromLuma) g.pointYValue else if (plane == 1) g.pointCbValue else g.pointCrValue
            val ys = if (fromLuma) g.pointYScaling else if (plane == 1) g.pointCbScaling else g.pointCrScaling
            val lut = scalingLut[plane]
            if (numPoints == 0) {
                lut.fill(0)
                continue
            }
            for (x in 0 until xs[0]) lut[x] = ys[0]
            for (i in 0 until numPoints - 1) {
                val deltaY = ys[i + 1] - ys[i]
                val deltaX = xs[i + 1] - xs[i]
                val delta = deltaY * ((65536 + (deltaX shr 1)) / deltaX)
                for (x in 0 until deltaX) lut[xs[i] + x] = ys[i] + ((x * delta + 32768) shr 16)
            }
            for (x in xs[numPoints - 1] until 256) lut[x] = ys[numPoints - 1]
        }
    }

    private fun scaleLut(plane: Int, index: Int): Int {
        val shift = bitDepth - 8
        val x = index shr shift
        val rem = index - (x shl shift)
        val lut = scalingLut[plane]
        if (bitDepth == 8 || x == 255) return lut[x]
        val start = lut[x]
        val end = lut[x + 1]
        return start + Av1.round2((end - start) * rem, shift)
    }

    private fun addNoise(out: Array<IntArray>, strides: IntArray, w: Int, h: Int) {
        val stripeW = w + 34
        var stripe = Array(numPlanes) { IntArray(34 * stripeW) }
        var previous = Array(numPlanes) { IntArray(34 * stripeW) }
        val minValue: Int
        val maxLuma: Int
        val maxChroma: Int
        if (g.clipToRestrictedRange) {
            minValue = 16 shl (bitDepth - 8)
            maxLuma = 235 shl (bitDepth - 8)
            maxChroma = if (identityMatrix) maxLuma else 240 shl (bitDepth - 8)
        } else {
            minValue = 0
            maxLuma = (256 shl (bitDepth - 8)) - 1
            maxChroma = maxLuma
        }
        val scalingShift = g.grainScalingMinus8 + 8
        val maxSample = (1 shl bitDepth) - 1
        val noiseRow = Array(numPlanes) { IntArray(stripeW) }
        var lumaNum = 0
        var y = 0
        while (y < (h + 1) / 2) {
            buildStripe(stripe, stripeW, lumaNum, w)
            // This stripe's rows of the noise image, then the noise added to them.
            val chromaRows = 32 shr subY
            val firstChroma = lumaNum * chromaRows
            if (numPlanes > 1) {
                val chromaH = (h + subY) shr subY
                val chromaWidth = (w + subX) shr subX
                for (cy in firstChroma until minOf(chromaH, firstChroma + chromaRows)) {
                    for (plane in 1..2) noiseImageRow(noiseRow[plane], stripe, previous, plane, cy - firstChroma, lumaNum, chromaWidth, subY)
                    val lumaRow = (cy shl subY) * strides[0]
                    for (x in 0 until chromaWidth) {
                        val lumaX = x shl subX
                        val lumaNextX = minOf(lumaX + 1, w - 1)
                        val averageLuma = if (subX != 0) Av1.round2(out[0][lumaRow + lumaX] + out[0][lumaRow + lumaNextX], 1) else out[0][lumaRow + lumaX]
                        if (g.numCbPoints > 0 || g.chromaScalingFromLuma) {
                            val at = cy * strides[1] + x
                            val orig = out[1][at]
                            val merged = if (g.chromaScalingFromLuma) averageLuma else {
                                val combined = averageLuma * (g.cbLumaMult - 128) + orig * (g.cbMult - 128)
                                ((combined shr 6) + ((g.cbOffset - 256) shl (bitDepth - 8))).coerceIn(0, maxSample)
                            }
                            val noise = Av1.round2(scaleLut(1, merged) * noiseRow[1][x], scalingShift)
                            out[1][at] = (orig + noise).coerceIn(minValue, maxChroma)
                        }
                        if (g.numCrPoints > 0 || g.chromaScalingFromLuma) {
                            val at = cy * strides[2] + x
                            val orig = out[2][at]
                            val merged = if (g.chromaScalingFromLuma) averageLuma else {
                                val combined = averageLuma * (g.crLumaMult - 128) + orig * (g.crMult - 128)
                                ((combined shr 6) + ((g.crOffset - 256) shl (bitDepth - 8))).coerceIn(0, maxSample)
                            }
                            val noise = Av1.round2(scaleLut(2, merged) * noiseRow[2][x], scalingShift)
                            out[2][at] = (orig + noise).coerceIn(minValue, maxChroma)
                        }
                    }
                }
            }
            if (g.numYPoints > 0) {
                val firstLuma = lumaNum * 32
                for (ly in firstLuma until minOf(h, firstLuma + 32)) {
                    noiseImageRow(noiseRow[0], stripe, previous, 0, ly - firstLuma, lumaNum, w, 0)
                    val row = ly * strides[0]
                    for (x in 0 until w) {
                        val orig = out[0][row + x]
                        val noise = Av1.round2(scaleLut(0, orig) * noiseRow[0][x], scalingShift)
                        out[0][row + x] = (orig + noise).coerceIn(minValue, maxLuma)
                    }
                }
            }
            val t = previous
            previous = stripe
            stripe = t
            lumaNum++
            y += 16
        }
    }

    /** noiseStripe[lumaNum]: the grain blocks of one stripe, blended across where they overlap. */
    private fun buildStripe(stripe: Array<IntArray>, stripeW: Int, lumaNum: Int, w: Int) {
        random = g.grainSeed
        random = random xor (((lumaNum * 37 + 178) and 255) shl 8)
        random = random xor ((lumaNum * 173 + 105) and 255)
        var x = 0
        while (x < (w + 1) / 2) {
            val rand = randomNumber(8)
            val offsetX = rand shr 4
            val offsetY = rand and 15
            for (plane in 0 until numPlanes) {
                val planeSubX = if (plane > 0) subX else 0
                val planeSubY = if (plane > 0) subY else 0
                val planeOffsetX = if (planeSubX != 0) 6 + offsetX else 9 + offsetX * 2
                val planeOffsetY = if (planeSubY != 0) 6 + offsetY else 9 + offsetY * 2
                val grain = when (plane) {
                    0 -> lumaGrain
                    1 -> cbGrain
                    else -> crGrain
                }
                val grainW = if (plane == 0) 82 else chromaW
                val s = stripe[plane]
                for (i in 0 until (34 shr planeSubY)) {
                    for (j in 0 until (34 shr planeSubX)) {
                        var v = grain[(planeOffsetY + i) * grainW + planeOffsetX + j]
                        if (planeSubX == 0) {
                            val at = i * stripeW + x * 2 + j
                            if (j < 2 && g.overlapFlag && x > 0) {
                                val old = s[at]
                                v = if (j == 0) old * 27 + v * 17 else old * 17 + v * 27
                                v = Av1.round2(v, 5).coerceIn(grainMin, grainMax)
                            }
                            s[at] = v
                        } else {
                            val at = i * stripeW + x + j
                            if (j == 0 && g.overlapFlag && x > 0) {
                                val old = s[at]
                                v = old * 23 + v * 22
                                v = Av1.round2(v, 5).coerceIn(grainMin, grainMax)
                            }
                            s[at] = v
                        }
                    }
                }
            }
            x += 16
        }
    }

    /** Row [i] of [plane]'s noise image within stripe [lumaNum], blended with the stripe above. */
    private fun noiseImageRow(dst: IntArray, stripe: Array<IntArray>, previous: Array<IntArray>, plane: Int, i: Int, lumaNum: Int, width: Int, planeSubY: Int) {
        val stripeW = dst.size
        val s = stripe[plane]
        val p = previous[plane]
        for (x in 0 until width) {
            var v = s[i * stripeW + x]
            if (planeSubY == 0) {
                if (i < 2 && lumaNum > 0 && g.overlapFlag) {
                    val old = p[(i + 32) * stripeW + x]
                    v = if (i == 0) old * 27 + v * 17 else old * 17 + v * 27
                    v = Av1.round2(v, 5).coerceIn(grainMin, grainMax)
                }
            } else {
                if (i < 1 && lumaNum > 0 && g.overlapFlag) {
                    val old = p[(i + 16) * stripeW + x]
                    v = old * 23 + v * 22
                    v = Av1.round2(v, 5).coerceIn(grainMin, grainMax)
                }
            }
            dst[x] = v
        }
    }
}
