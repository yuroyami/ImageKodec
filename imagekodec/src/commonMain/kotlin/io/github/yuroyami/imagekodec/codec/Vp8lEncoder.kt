package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.internal.flate.ByteArrayBuilder
import io.github.yuroyami.imagekodec.internal.flate.Deflater
import kotlin.math.abs
import kotlin.math.ln

/**
 * Lossless WebP (VP8L) encoder, written against the WebP Lossless Bitstream Specification
 * (RFC 9649) and shaped like libwebp's own encoder: the image is tried as a palette when it
 * has 256 colours or fewer, and otherwise through the subtract-green, predictor and
 * cross-colour transforms, each predictor and colour transform chosen per tile by the
 * entropy it leaves; then LZ77 with VP8L's two-dimensional distance codes, a colour cache
 * whose size is picked by the cost it saves, and canonical prefix codes no longer than 15
 * bits, one group of them for the whole image or, through the entropy image, one for each
 * cluster of tiles with alike statistics. Every candidate is written and the smallest kept,
 * so a transform that does not pay for itself is left out.
 */
internal object Vp8lEncoder {
    private const val NUM_LITERAL_CODES = 256
    private const val NUM_LENGTH_CODES = 24
    private const val NUM_DISTANCE_CODES = 40
    private const val CODE_LENGTH_CODES = 19
    private const val MAX_LENGTH = 4096
    private const val MAX_DISTANCE = (1 shl 20) - 120
    private const val ARGB_BLACK = 0xFF000000.toInt()

    private val CODE_LENGTH_CODE_ORDER = intArrayOf(17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)

    /** The 120 (y, x) offsets of the distance plane codes, as the decoder derives them. */
    private val CODE_TO_PLANE: IntArray = run {
        data class Offset(val distanceSquared: Int, val y: Int, val x: Int)
        val candidates = ArrayList<Offset>(120)
        for (y in 0..7) for (xCode in 0..15) {
            val x = 8 - xCode
            if (y == 0 && x < 1) continue
            candidates.add(Offset(x * x + y * y, y, x))
        }
        candidates.sortWith(compareBy<Offset> { it.distanceSquared }.thenByDescending { it.y }.thenByDescending { it.x })
        IntArray(candidates.size) { (candidates[it].y shl 4) or (8 - candidates[it].x) }
    }

    /** The VP8L bitstream of [bitmap], from its signature byte. */
    fun encode(bitmap: KiteBitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        require(w <= 16384 && h <= 16384) { "a lossless WebP is at most 16384 pixels a side, not ${w}x$h" }
        val argb = bitmap.argb
        val alpha = argb.any { (it ushr 24) != 0xFF }
        val candidates = ArrayList<ByteArray>()
        palette(argb)?.let { colors ->
            candidates += write(w, h, alpha) { out -> paletteImage(out, argb, w, h, colors, predicted = false) }
            // Past 16 colours an index takes a whole pixel, and the predictor can then smooth
            // the index image the way it smooths colours, as libwebp's palette-and-spatial mode does.
            if (colors.size > 16) candidates += write(w, h, alpha) { out -> paletteImage(out, argb, w, h, colors, predicted = true) }
        }
        candidates += write(w, h, alpha) { out -> transformedImage(out, argb, w, h, subtractGreen = true) }
        candidates += write(w, h, alpha) { out -> transformedImage(out, argb, w, h, subtractGreen = false) }
        candidates += write(w, h, alpha) { out ->
            out.write(0, 1)
            entropyCoded(out, argb.copyOf(), w, h, topLevel = true)
        }
        return candidates.minByOrNull { it.size }!!
    }

    private fun write(w: Int, h: Int, alpha: Boolean, body: (BitWriter) -> Unit): ByteArray {
        val out = BitWriter()
        out.write(0x2F, 8)
        out.write(w - 1, 14)
        out.write(h - 1, 14)
        out.write(if (alpha) 1 else 0, 1)
        out.write(0, 3)
        body(out)
        return out.toByteArray()
    }

    // --- palette --------------------------------------------------------------------

    /** The image's colours, sorted, when there are 256 or fewer. */
    private fun palette(argb: IntArray): IntArray? {
        val seen = HashSet<Int>()
        for (p in argb) {
            if (seen.add(p) && seen.size > 256) return null
        }
        return seen.toIntArray().also { it.sort() }
    }

    private fun paletteImage(out: BitWriter, argb: IntArray, w: Int, h: Int, palette: IntArray, predicted: Boolean) {
        val size = palette.size
        val bits = when {
            size <= 2 -> 3
            size <= 4 -> 2
            size <= 16 -> 1
            else -> 0
        }
        out.write(1, 1)
        out.write(3, 2)
        out.write(size - 1, 8)
        // The palette is stored as deltas along its row.
        val deltas = IntArray(size) { i -> if (i == 0) palette[0] else subPixels(palette[i], palette[i - 1]) }
        entropyCoded(out, deltas, size, 1, topLevel = false)
        val index = HashMap<Int, Int>(size * 2)
        for ((i, c) in palette.withIndex()) index[c] = i
        val perPixel = 1 shl bits
        val packedWidth = (w + perPixel - 1) shr bits
        val bitsPerIndex = 8 shr bits
        val packed = IntArray(packedWidth * h)
        for (y in 0 until h) for (x in 0 until w) {
            val idx = index.getValue(argb[y * w + x])
            val at = y * packedWidth + (x shr bits)
            packed[at] = packed[at] or (idx shl (bitsPerIndex * (x and (perPixel - 1))))
        }
        for (i in packed.indices) packed[i] = ARGB_BLACK or (packed[i] shl 8)
        if (predicted) {
            out.write(1, 1)
            out.write(0, 2)
            predicted(out, packed, packedWidth, h)
        } else {
            out.write(0, 1)
            entropyCoded(out, packed, packedWidth, h, topLevel = true)
        }
    }

    /** The predictor transform's tile modes and its residuals after them, then the end of the transforms and the residuals. */
    private fun predicted(out: BitWriter, pixels: IntArray, w: Int, h: Int) {
        val bits = tileBits(w, h)
        val tw = (w + (1 shl bits) - 1) shr bits
        val th = (h + (1 shl bits) - 1) shr bits
        val modes = predictorModes(pixels, w, h, bits, tw, th)
        out.write(bits - 2, 3)
        entropyCoded(out, IntArray(tw * th) { ARGB_BLACK or (modes[it] shl 8) }, tw, th, topLevel = false)
        out.write(0, 1)
        entropyCoded(out, residuals(pixels, w, h, bits, tw, modes), w, h, topLevel = true)
    }

    private fun residuals(pixels: IntArray, w: Int, h: Int, bits: Int, tw: Int, modes: IntArray): IntArray {
        val residuals = IntArray(pixels.size)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val mode = when {
                x == 0 && y == 0 -> -1
                y == 0 -> 1
                x == 0 -> 2
                else -> modes[(y shr bits) * tw + (x shr bits)]
            }
            val prediction = if (mode < 0) ARGB_BLACK else predict(mode, pixels, i, w, x, y)
            residuals[i] = subPixels(pixels[i], prediction)
        }
        return residuals
    }

    // --- subtract green, predictor and cross colour -----------------------------------

    private fun transformedImage(out: BitWriter, source: IntArray, w: Int, h: Int, subtractGreen: Boolean) {
        val pixels = source.copyOf()
        if (subtractGreen) {
            for (i in pixels.indices) {
                val p = pixels[i]
                val g = (p ushr 8) and 0xFF
                pixels[i] = (p and 0xFF00FF00.toInt()) or ((((p ushr 16) - g) and 0xFF) shl 16) or (((p and 0xFF) - g) and 0xFF)
            }
            out.write(1, 1)
            out.write(2, 2)
        }

        val bits = tileBits(w, h)
        val tw = (w + (1 shl bits) - 1) shr bits
        val th = (h + (1 shl bits) - 1) shr bits
        val modes = predictorModes(pixels, w, h, bits, tw, th)
        val residuals = residuals(pixels, w, h, bits, tw, modes)
        out.write(1, 1)
        out.write(0, 2)
        out.write(bits - 2, 3)
        entropyCoded(out, IntArray(tw * th) { ARGB_BLACK or (modes[it] shl 8) }, tw, th, topLevel = false)

        val multipliers = crossColor(residuals, w, h, bits, tw, th)
        out.write(1, 1)
        out.write(1, 2)
        out.write(bits - 2, 3)
        entropyCoded(out, multipliers.copyOf(), tw, th, topLevel = false)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            residuals[i] = forwardCrossColor(residuals[i], multipliers[(y shr bits) * tw + (x shr bits)])
        }
        out.write(0, 1)
        entropyCoded(out, residuals, w, h, topLevel = true)
    }

    private fun tileBits(w: Int, h: Int): Int = when {
        w.toLong() * h <= 32L * 32 -> 3
        w.toLong() * h <= 128L * 128 -> 4
        else -> 5
    }

    /**
     * The predictor mode of each tile: the one whose residuals add the fewest bits to those of
     * the tiles chosen before it, as libwebp chooses, since one prefix code serves them all.
     */
    private fun predictorModes(p: IntArray, w: Int, h: Int, bits: Int, tw: Int, th: Int): IntArray {
        val modes = IntArray(tw * th)
        val accumulated = IntArray(4 * 256)
        val tile = IntArray(4 * 256)
        val touched = IntArray(4 shl (2 * bits))
        val accumulatedTotal = IntArray(4)
        val residuals = IntArray(1 shl (2 * bits))
        for (ty in 0 until th) for (tx in 0 until tw) {
            var best = 0
            var bestCost = Double.MAX_VALUE
            var bestCount = 0
            for (mode in 0..13) {
                var n = 0
                for (y in (ty shl bits) until minOf(h, (ty + 1) shl bits)) for (x in (tx shl bits) until minOf(w, (tx + 1) shl bits)) {
                    if (x == 0 || y == 0) continue
                    val i = y * w + x
                    residuals[n++] = subPixels(p[i], predict(mode, p, i, w, x, y))
                }
                if (n == 0) break
                var nTouched = 0
                for (k in 0 until n) {
                    val r = residuals[k]
                    for (c in 0 until 4) {
                        val bin = c * 256 + ((r ushr (24 - 8 * c)) and 0xFF)
                        if (tile[bin]++ == 0) touched[nTouched++] = bin
                    }
                }
                val cost = addedBits(accumulated, accumulatedTotal, tile, touched, nTouched, n)
                for (k in 0 until nTouched) tile[touched[k]] = 0
                if (cost < bestCost) {
                    bestCost = cost
                    best = mode
                    bestCount = n
                }
            }
            modes[ty * tw + tx] = best
            if (bestCount > 0) {
                for (y in (ty shl bits) until minOf(h, (ty + 1) shl bits)) for (x in (tx shl bits) until minOf(w, (tx + 1) shl bits)) {
                    if (x == 0 || y == 0) continue
                    val i = y * w + x
                    addToHistogram(accumulated, subPixels(p[i], predict(best, p, i, w, x, y)))
                }
                for (c in 0 until 4) accumulatedTotal[c] += bestCount
            }
        }
        return modes
    }

    private fun addToHistogram(h: IntArray, r: Int) {
        h[r ushr 24]++
        h[256 + ((r ushr 16) and 0xFF)]++
        h[512 + ((r ushr 8) and 0xFF)]++
        h[768 + (r and 0xFF)]++
    }

    /**
     * How many more nats, by entropy, [accumulated] takes once the [n] residuals of [tile]
     * join each channel: only the bins the tile [touched] change.
     */
    private fun addedBits(accumulated: IntArray, totals: IntArray, tile: IntArray, touched: IntArray, nTouched: Int, n: Int): Double {
        var nats = 0.0
        for (c in 0 until 4) nats += xlogx(totals[c] + n) - xlogx(totals[c])
        for (k in 0 until nTouched) {
            val bin = touched[k]
            val a = accumulated[bin]
            nats -= xlogx(a + tile[bin]) - xlogx(a)
        }
        return nats
    }

    /** c ln c for the counts most histograms hold, computed once. */
    private val XLOGX: DoubleArray by lazy { DoubleArray(1 shl 16) { c -> if (c == 0) 0.0 else c * ln(c.toDouble()) } }

    private fun xlogx(c: Int): Double = if (c < XLOGX.size) XLOGX[c] else c * ln(c.toDouble())

    /** The entropy in nats of the [n] symbols these counts hold. */
    private fun entropy(histogram: IntArray, from: Int, to: Int, n: Int): Double {
        var sum = 0.0
        for (k in from until to) {
            val c = histogram[k]
            if (c > 0) sum += xlogx(c)
        }
        return xlogx(n) - sum
    }

    private fun predict(mode: Int, p: IntArray, i: Int, width: Int, x: Int, y: Int): Int {
        val left = if (x > 0) p[i - 1] else 0
        val top = if (y > 0) p[i - width] else 0
        val topLeft = if (x > 0 && y > 0) p[i - width - 1] else 0
        val topRight = if (y > 0) p[i - width + 1] else 0
        return when (mode) {
            0 -> ARGB_BLACK
            1 -> left
            2 -> top
            3 -> topRight
            4 -> topLeft
            5 -> average2(average2(left, topRight), top)
            6 -> average2(left, topLeft)
            7 -> average2(left, top)
            8 -> average2(topLeft, top)
            9 -> average2(top, topRight)
            10 -> average2(average2(left, topLeft), average2(top, topRight))
            11 -> select(top, left, topLeft)
            12 -> clampedAddSubtractFull(left, top, topLeft)
            else -> clampedAddSubtractHalf(left, top, topLeft)
        }
    }

    private fun average2(a: Int, b: Int): Int = (((a xor b) and 0xFEFEFEFE.toInt()) ushr 1) + (a and b)

    private fun select(top: Int, left: Int, topLeft: Int): Int {
        var pa = 0
        var pb = 0
        var shift = 24
        while (shift >= 0) {
            val t = (top ushr shift) and 0xFF
            val l = (left ushr shift) and 0xFF
            val tl = (topLeft ushr shift) and 0xFF
            val predict = l + t - tl
            pa += abs(predict - t)
            pb += abs(predict - l)
            shift -= 8
        }
        return if (pa <= pb) top else left
    }

    private fun clamp255(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    private fun clampedAddSubtractFull(a: Int, b: Int, c: Int): Int {
        var out = 0
        var shift = 24
        while (shift >= 0) {
            out = out or (clamp255(((a ushr shift) and 0xFF) + ((b ushr shift) and 0xFF) - ((c ushr shift) and 0xFF)) shl shift)
            shift -= 8
        }
        return out
    }

    private fun clampedAddSubtractHalf(a: Int, b: Int, c: Int): Int {
        val ave = average2(a, b)
        var out = 0
        var shift = 24
        while (shift >= 0) {
            val av = (ave ushr shift) and 0xFF
            val cv = (c ushr shift) and 0xFF
            out = out or (clamp255(av + (av - cv) / 2) shl shift)
            shift -= 8
        }
        return out
    }

    private fun subPixels(a: Int, b: Int): Int {
        val alpha = ((a ushr 24) - (b ushr 24)) and 0xFF
        val red = (((a ushr 16) and 0xFF) - ((b ushr 16) and 0xFF)) and 0xFF
        val green = (((a ushr 8) and 0xFF) - ((b ushr 8) and 0xFF)) and 0xFF
        val blue = ((a and 0xFF) - (b and 0xFF)) and 0xFF
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun delta(t: Int, c: Int): Int = (t * c.toByte().toInt()) shr 5

    /** Pixel [p] with the colour transform [m] (green to red, green to blue, red to blue) taken out. */
    private fun forwardCrossColor(p: Int, m: Int): Int {
        val greenToRed = (m and 0xFF).toByte().toInt()
        val greenToBlue = ((m ushr 8) and 0xFF).toByte().toInt()
        val redToBlue = ((m ushr 16) and 0xFF).toByte().toInt()
        val green = (p ushr 8) and 0xFF
        val red = (p ushr 16) and 0xFF
        val blue = p and 0xFF
        val newRed = (red - delta(greenToRed, green)) and 0xFF
        val newBlue = (blue - delta(greenToBlue, green) - delta(redToBlue, red)) and 0xFF
        return (p and 0xFF00FF00.toInt()) or (newRed shl 16) or newBlue
    }

    /**
     * Each tile's colour transform: the multipliers least squares suggests, then the
     * neighbours of each that leave the least entropy, one multiplier at a time.
     */
    private fun crossColor(p: IntArray, w: Int, h: Int, bits: Int, tw: Int, th: Int): IntArray {
        val out = IntArray(tw * th)
        val histogram = IntArray(256)
        for (ty in 0 until th) for (tx in 0 until tw) {
            val x0 = tx shl bits
            val y0 = ty shl bits
            val x1 = minOf(w, x0 + (1 shl bits))
            val y1 = minOf(h, y0 + (1 shl bits))
            fun channelCost(f: (Int) -> Int): Double {
                histogram.fill(0)
                var n = 0
                for (y in y0 until y1) for (x in x0 until x1) {
                    histogram[f(p[y * w + x]) and 0xFF]++
                    n++
                }
                return entropy(histogram, 0, 256, n)
            }
            fun leastSquares(source: (Int) -> Int, target: (Int) -> Int): Int {
                var num = 0.0
                var den = 0.0
                for (y in y0 until y1) for (x in x0 until x1) {
                    val s = source(p[y * w + x]).toByte().toInt()
                    val t = target(p[y * w + x]).toByte().toInt()
                    num += s * t
                    den += s * s
                }
                return if (den == 0.0) 0 else (32 * num / den).toInt().coerceIn(-128, 127)
            }
            fun refine(start: Int, cost: (Int) -> Double): Int {
                var best = 0
                var bestCost = cost(0)
                for (c in listOf(start - 2, start - 1, start, start + 1, start + 2).filter { it in -128..127 && it != 0 }.distinct()) {
                    val k = cost(c)
                    if (k < bestCost) {
                        bestCost = k
                        best = c
                    }
                }
                return best
            }
            val green = { q: Int -> (q ushr 8) and 0xFF }
            val red = { q: Int -> (q ushr 16) and 0xFF }
            val blue = { q: Int -> q and 0xFF }
            val g2r = refine(leastSquares(green, red)) { c -> channelCost { q -> red(q) - delta(c, green(q)) } }
            val g2b = refine(leastSquares(green, blue)) { c -> channelCost { q -> blue(q) - delta(c, green(q)) } }
            val r2b = refine(leastSquares(red, { q -> blue(q) - delta(g2b, green(q)) })) { c ->
                channelCost { q -> blue(q) - delta(g2b, green(q)) - delta(c, red(q)) }
            }
            out[ty * tw + tx] = ARGB_BLACK or ((r2b and 0xFF) shl 16) or ((g2b and 0xFF) shl 8) or (g2r and 0xFF)
        }
        return out
    }

    // --- LZ77, colour cache and prefix codes --------------------------------------------

    /** A literal (argb), a cache hit (index), or a copy (length, distance). */
    private class Tokens(n: Int) {
        var kind = ByteArray(n)
        var a = IntArray(n)
        var b = IntArray(n)
        var size = 0

        fun add(k: Int, x: Int, y: Int) {
            if (size == kind.size) {
                kind = kind.copyOf(size * 2)
                a = a.copyOf(size * 2)
                b = b.copyOf(size * 2)
            }
            kind[size] = k.toByte()
            a[size] = x
            b[size] = y
            size++
        }
    }

    private const val LITERAL = 0
    private const val COPY = 1

    /** Greedy LZ77 over pixels with a hash chain, the left and upper neighbours tried first. */
    private fun lz77(p: IntArray, w: Int): Tokens {
        val n = p.size
        val tokens = Tokens(n / 2 + 16)
        val hashBits = 16
        val head = IntArray(1 shl hashBits) { -1 }
        val prev = IntArray(n)
        fun hash(i: Int): Int {
            val v = p[i] * 0x1e35a7bd + (if (i + 1 < n) p[i + 1] else 0) * 0x2545F491
            return v ushr (32 - hashBits)
        }
        fun insert(i: Int) {
            if (i + 1 >= n) return
            val k = hash(i)
            prev[i] = head[k]
            head[k] = i
        }
        fun matchLength(i: Int, j: Int, max: Int): Int {
            var len = 0
            while (len < max && p[i + len] == p[j + len]) len++
            return len
        }
        var i = 0
        while (i < n) {
            val max = minOf(MAX_LENGTH, n - i)
            var bestLen = 0
            var bestDist = 0
            for (d in intArrayOf(1, w)) {
                if (d in 1..i) {
                    val len = matchLength(i, i - d, max)
                    if (len > bestLen) {
                        bestLen = len
                        bestDist = d
                    }
                }
            }
            if (i + 1 < n && bestLen < max) {
                var cand = head[hash(i)]
                var chain = 32
                while (cand >= 0 && chain-- > 0 && i - cand <= MAX_DISTANCE) {
                    if (p[cand + bestLen.coerceAtMost(max - 1)] == p[i + bestLen.coerceAtMost(max - 1)]) {
                        val len = matchLength(i, cand, max)
                        if (len > bestLen) {
                            bestLen = len
                            bestDist = i - cand
                            if (len == max) break
                        }
                    }
                    cand = prev[cand]
                }
            }
            if (bestLen >= 3 || (bestLen >= 2 && (bestDist == 1 || bestDist == w))) {
                tokens.add(COPY, bestLen, bestDist)
                for (k in i until i + bestLen) insert(k)
                i += bestLen
            } else {
                tokens.add(LITERAL, p[i], 0)
                insert(i)
                i++
            }
        }
        return tokens
    }

    private fun cacheHash(argb: Int, bits: Int): Int = (0x1e35a7bd * argb) ushr (32 - bits)

    /** The distance code of [distance] in an image [width] wide: a plane code when one names it. */
    private fun distanceCode(distance: Int, width: Int, planes: IntArray?): Int {
        if (planes != null && distance < planes.size && planes[distance] > 0) return planes[distance]
        return distance + 120
    }

    /** For each distance up to 8 rows, the smallest plane code naming it, 0 for none. */
    private fun planeCodes(width: Int): IntArray {
        val out = IntArray(8 * width + 9)
        for (code in CODE_TO_PLANE.size downTo 1) {
            val c = CODE_TO_PLANE[code - 1]
            val dist = maxOf(1, (c shr 4) * width + 8 - (c and 0x0F))
            if (dist < out.size) out[dist] = code
        }
        return out
    }

    /** The prefix code of [value] (at least 1) and its extra bits: (code, extra bit count, extra value). */
    private fun prefix(value: Int): Triple<Int, Int, Int> {
        if (value <= 4) return Triple(value - 1, 0, 0)
        val x = value - 1
        val h = 31 - x.countLeadingZeroBits()
        val second = (x ushr (h - 1)) and 1
        val extraBits = h - 1
        return Triple(2 * h + second, extraBits, x and ((1 shl extraBits) - 1))
    }

    /**
     * An entropy-coded image: the colour cache that pays best, no meta prefix image, the five
     * prefix codes and the pixels.
     */
    private fun entropyCoded(out: BitWriter, p: IntArray, w: Int, h: Int, topLevel: Boolean) {
        val tokens = lz77(p, w)
        val planes = planeCodes(w)
        val bestBits = cacheBits(tokens, p, w, planes)
        if (bestBits > 0) {
            out.write(1, 1)
            out.write(bestBits, 4)
        } else {
            out.write(0, 1)
        }
        val symbols = symbols(tokens, p, bestBits, w, planes)
        val greenSize = NUM_LITERAL_CODES + NUM_LENGTH_CODES + (if (bestBits > 0) 1 shl bestBits else 0)
        val sizes = intArrayOf(greenSize, 256, 256, 256, NUM_DISTANCE_CODES)
        val single = BitWriter()
        if (topLevel) single.write(0, 1)
        val freq = Array(5) { IntArray(sizes[it]) }
        countSymbols(symbols, freq)
        val codes = Array(5) { k -> PrefixCode(freq[k]) }
        for (c in codes) c.writeHeader(single)
        writeSymbols(single, symbols, codes, null, null)
        var best = single
        if (topLevel) {
            // The entropy image: regions of the image with prefix codes of their own, written
            // only where it comes out smaller.
            val starts = tokenStarts(tokens)
            for (metaBits in metaBitsFor(w, h)) {
                val meta = BitWriter()
                if (!entropyImage(meta, symbols, starts, w, h, sizes, metaBits)) continue
                if (meta.bitCount < best.bitCount) best = meta
            }
        }
        out.append(best)
    }

    /** The pixel each token starts at. */
    private fun tokenStarts(tokens: Tokens): IntArray {
        val out = IntArray(tokens.size)
        var pos = 0
        for (t in 0 until tokens.size) {
            out[t] = pos
            pos += if (tokens.kind[t].toInt() == LITERAL) 1 else tokens.a[t]
        }
        return out
    }

    /** Entropy image tile sizes worth trying: tiles of 2^bits pixels a side, at most about 1000 of them. */
    private fun metaBitsFor(w: Int, h: Int): List<Int> {
        var bits = 3
        while (bits < 9 && ((w + (1 shl bits) - 1) shr bits).toLong() * ((h + (1 shl bits) - 1) shr bits) > 1024) bits++
        return listOf(bits, bits + 1).filter { b -> ((w + (1 shl b) - 1) shr b) * ((h + (1 shl b) - 1) shr b) >= 2 }
    }

    /**
     * Writes the meta prefix image and its groups with tiles of 2^[metaBits]: the tiles'
     * histograms clustered by k-means under the cost in bits of coding a tile with a cluster's
     * statistics, then clusters merged while merging saves more than a group's codes cost.
     * False when one cluster is all that is left, which the single code already covers.
     */
    private fun entropyImage(out: BitWriter, symbols: IntArray, starts: IntArray, w: Int, h: Int, sizes: IntArray, metaBits: Int): Boolean {
        val tw = (w + (1 shl metaBits) - 1) shr metaBits
        val th = (h + (1 shl metaBits) - 1) shr metaBits
        val nTiles = tw * th
        val offsets = IntArray(6)
        for (k in 0 until 5) offsets[k + 1] = offsets[k] + sizes[k]
        val total = offsets[5]
        val tokenCount = starts.size
        val tileOfToken = IntArray(tokenCount) { t -> ((starts[t] / w) shr metaBits) * tw + ((starts[t] % w) shr metaBits) }
        // Each tile's histogram, as its nonzero bins.
        val dense = Array(nTiles) { IntArray(0) }
        run {
            val counts = HashMap<Int, IntArray>()
            for (t in 0 until tokenCount) {
                val h0 = counts.getOrPut(tileOfToken[t]) { IntArray(total) }
                val o = t * 5
                h0[symbols[o]]++
                when (symbols[o + 1]) {
                    -1 -> {}
                    -2 -> h0[offsets[4] + symbols[o + 3]]++
                    else -> {
                        h0[offsets[1] + symbols[o + 1]]++
                        h0[offsets[2] + symbols[o + 2]]++
                        h0[offsets[3] + symbols[o + 3]]++
                    }
                }
            }
            for ((tile, hist) in counts) dense[tile] = hist
        }
        val bins = Array(nTiles) { tile ->
            val d = dense[tile]
            if (d.isEmpty()) IntArray(0) else {
                val nz = (0 until total).filter { d[it] > 0 }
                IntArray(nz.size * 2).also { a -> for ((i, b) in nz.withIndex()) { a[2 * i] = b; a[2 * i + 1] = d[b] } }
            }
        }
        val occupied = (0 until nTiles).filter { bins[it].isNotEmpty() }
        if (occupied.size < 2) return false

        // k-means, seeded with tiles spread over the range of their own bits per token.
        val k0 = minOf(occupied.size, 32)
        val byEntropy = occupied.sortedBy { tile -> histogramBits(dense[tile], offsets) / maxOf(1, tokensIn(dense[tile], offsets)) }
        val clusters = Array(k0) { c -> dense[byEntropy[(c * (byEntropy.size - 1)) / maxOf(1, k0 - 1)]].copyOf() }
        val assignment = IntArray(nTiles) { -1 }
        val cost = Array(k0) { DoubleArray(total) }
        repeat(4) {
            for (c in 0 until k0) bitCosts(clusters[c], offsets, cost[c])
            for (tile in occupied) {
                val b = bins[tile]
                var bestC = 0
                var bestCost = Double.MAX_VALUE
                for (c in 0 until k0) {
                    var bits = 0.0
                    val cc = cost[c]
                    var i = 0
                    while (i < b.size) {
                        bits += b[i + 1] * cc[b[i]]
                        i += 2
                    }
                    if (bits < bestCost) {
                        bestCost = bits
                        bestC = c
                    }
                }
                assignment[tile] = bestC
            }
            for (c in 0 until k0) clusters[c].fill(0)
            for (tile in occupied) {
                val cl = clusters[assignment[tile]]
                val b = bins[tile]
                var i = 0
                while (i < b.size) {
                    cl[b[i]] += b[i + 1]
                    i += 2
                }
            }
        }

        // Greedy merging while it saves bits, a group's codes counted at what they would cost.
        val alive = BooleanArray(k0) { c -> clusters[c].any { it > 0 } }
        val own = DoubleArray(k0) { c -> if (alive[c]) histogramBits(clusters[c], offsets) + headerBits(clusters[c], offsets) else 0.0 }
        val merged = IntArray(total)
        fun gain(a: Int, b: Int): Double {
            for (i in 0 until total) merged[i] = clusters[a][i] + clusters[b][i]
            return own[a] + own[b] - histogramBits(merged, offsets) - headerBits(merged, offsets)
        }
        val gains = Array(k0) { DoubleArray(k0) }
        for (a in 0 until k0) for (b in a + 1 until k0) if (alive[a] && alive[b]) gains[a][b] = gain(a, b)
        while (true) {
            var bestA = -1
            var bestB = -1
            var bestGain = 0.0
            for (a in 0 until k0) if (alive[a]) for (b in a + 1 until k0) if (alive[b] && gains[a][b] > bestGain) {
                bestGain = gains[a][b]
                bestA = a
                bestB = b
            }
            if (bestA < 0) break
            for (i in 0 until total) clusters[bestA][i] += clusters[bestB][i]
            alive[bestB] = false
            own[bestA] = histogramBits(clusters[bestA], offsets) + headerBits(clusters[bestA], offsets)
            for (tile in occupied) if (assignment[tile] == bestB) assignment[tile] = bestA
            for (c in 0 until k0) if (alive[c] && c != bestA) {
                val a = minOf(c, bestA)
                val b = maxOf(c, bestA)
                gains[a][b] = gain(a, b)
            }
        }
        val live = (0 until k0).filter { alive[it] }
        if (live.size < 2) return false
        val groupOf = IntArray(k0) { -1 }
        for ((g, c) in live.withIndex()) groupOf[c] = g
        // A tile with no token start reads no symbols with its group, so group 0 does.
        val tileGroup = IntArray(nTiles) { tile -> if (assignment[tile] >= 0) groupOf[assignment[tile]] else 0 }

        out.write(1, 1)
        out.write(metaBits - 2, 3)
        entropyCoded(out, IntArray(nTiles) { t -> ((tileGroup[t] ushr 8) shl 16) or ((tileGroup[t] and 0xFF) shl 8) }, tw, th, topLevel = false)
        val groups = Array(live.size) { g ->
            val hist = clusters[live[g]]
            Array(5) { k -> PrefixCode(hist.copyOfRange(offsets[k], offsets[k + 1])) }
        }
        for (g in groups) for (c in g) c.writeHeader(out)
        writeSymbols(out, symbols, groups[0], groups, IntArray(tokenCount) { tileGroup[tileOfToken[it]] })
        return true
    }

    private fun tokensIn(hist: IntArray, offsets: IntArray): Int {
        var n = 0
        for (i in offsets[0] until offsets[1]) n += hist[i]
        return n
    }

    /** The bits the symbols of [hist] take at the entropy of each of its five alphabets. */
    private fun histogramBits(hist: IntArray, offsets: IntArray): Double {
        var nats = 0.0
        for (k in 0 until 5) {
            var n = 0
            var sum = 0.0
            for (i in offsets[k] until offsets[k + 1]) {
                val c = hist[i]
                if (c > 0) {
                    n += c
                    sum += c * ln(c.toDouble())
                }
            }
            if (n > 0) nats += n * ln(n.toDouble()) - sum
        }
        return nats / ln(2.0)
    }

    /** A rough price of writing [hist]'s five prefix codes. */
    private fun headerBits(hist: IntArray, offsets: IntArray): Double {
        var bits = 0.0
        for (k in 0 until 5) {
            var used = 0
            for (i in offsets[k] until offsets[k + 1]) if (hist[i] > 0) used++
            bits += when {
                used <= 1 -> 4.0
                used == 2 -> 20.0
                else -> 40.0 + 4.0 * used
            }
        }
        return bits
    }

    /** Each symbol's cost in bits under [hist]'s statistics, smoothed so an unseen symbol is costly but finite. */
    private fun bitCosts(hist: IntArray, offsets: IntArray, out: DoubleArray) {
        for (k in 0 until 5) {
            var n = 0
            for (i in offsets[k] until offsets[k + 1]) n += hist[i]
            val size = offsets[k + 1] - offsets[k]
            val denominator = n + 0.25 * size
            for (i in offsets[k] until offsets[k + 1]) out[i] = -ln((hist[i] + 0.25) / denominator) / ln(2.0)
        }
    }

    /**
     * The token stream as symbols, five ints a token: the green-code symbol, then red, blue and
     * alpha for a literal (red -1 for a cache hit), or -2, the length's extra bits, the distance
     * symbol and its extra bits for a copy, each extra as count shl 24 or value.
     */
    private fun symbols(tokens: Tokens, p: IntArray, cacheBits: Int, w: Int, planes: IntArray): IntArray {
        val out = IntArray(tokens.size * 5)
        val cache = if (cacheBits > 0) IntArray(1 shl cacheBits) else null
        var pos = 0
        for (t in 0 until tokens.size) {
            val o = t * 5
            if (tokens.kind[t].toInt() == LITERAL) {
                val px = tokens.a[t]
                if (cache != null && cache[cacheHash(px, cacheBits)] == px) {
                    out[o] = NUM_LITERAL_CODES + NUM_LENGTH_CODES + cacheHash(px, cacheBits)
                    out[o + 1] = -1
                } else {
                    out[o] = (px ushr 8) and 0xFF
                    out[o + 1] = (px ushr 16) and 0xFF
                    out[o + 2] = px and 0xFF
                    out[o + 3] = px ushr 24
                }
                if (cache != null) cache[cacheHash(px, cacheBits)] = px
                pos++
            } else {
                val len = tokens.a[t]
                val (lc, lb, lv) = prefix(len)
                val (dc, db, dv) = prefix(distanceCode(tokens.b[t], w, planes))
                out[o] = NUM_LITERAL_CODES + lc
                out[o + 1] = -2
                out[o + 2] = (lb shl 24) or lv
                out[o + 3] = dc
                out[o + 4] = (db shl 24) or dv
                if (cache != null) for (k in pos until pos + len) cache[cacheHash(p[k], cacheBits)] = p[k]
                pos += len
            }
        }
        return out
    }

    private fun countSymbols(symbols: IntArray, freq: Array<IntArray>) {
        var o = 0
        while (o < symbols.size) {
            freq[0][symbols[o]]++
            when (symbols[o + 1]) {
                -1 -> {}
                -2 -> freq[4][symbols[o + 3]]++
                else -> {
                    freq[1][symbols[o + 1]]++
                    freq[2][symbols[o + 2]]++
                    freq[3][symbols[o + 3]]++
                }
            }
            o += 5
        }
    }

    /** The symbols, token t with the codes of group [groupOfToken] (t) when there are [groups], else [single]. */
    private fun writeSymbols(out: BitWriter, symbols: IntArray, single: Array<PrefixCode>, groups: Array<Array<PrefixCode>>?, groupOfToken: IntArray?) {
        var o = 0
        var t = 0
        while (o < symbols.size) {
            val codes = if (groups != null && groupOfToken != null) groups[groupOfToken[t]] else single
            t++
            codes[0].write(out, symbols[o])
            when (symbols[o + 1]) {
                -1 -> {}
                -2 -> {
                    val lb = symbols[o + 2] ushr 24
                    if (lb > 0) out.write(symbols[o + 2] and 0xFFFFFF, lb)
                    codes[4].write(out, symbols[o + 3])
                    val d = symbols[o + 4]
                    val db = d ushr 24
                    if (db > 0) out.write(d and 0xFFFFFF, db)
                }
                else -> {
                    codes[1].write(out, symbols[o + 1])
                    codes[2].write(out, symbols[o + 2])
                    codes[3].write(out, symbols[o + 3])
                }
            }
            o += 5
        }
    }

    /**
     * The colour cache size, 0 for none, that leaves the fewest bits by the entropy of each
     * code, a few bits a used symbol added for the code itself: every size simulated in one
     * pass, as the tokens do not depend on it.
     */
    private fun cacheBits(tokens: Tokens, p: IntArray, w: Int, planes: IntArray): Int {
        val maxBits = (1..10).lastOrNull { (1 shl it) <= p.size * 2 } ?: 0
        val caches = Array(maxBits + 1) { if (it == 0) IntArray(0) else IntArray(1 shl it) }
        // Per size: green (literal green, length codes and cache indices), red, blue and alpha.
        val green = Array(maxBits + 1) { IntArray(NUM_LITERAL_CODES + NUM_LENGTH_CODES + (if (it > 0) 1 shl it else 0)) }
        val rba = Array(maxBits + 1) { IntArray(3 * 256) }
        val distance = IntArray(NUM_DISTANCE_CODES)
        var pos = 0
        for (t in 0 until tokens.size) {
            if (tokens.kind[t].toInt() == LITERAL) {
                val px = tokens.a[t]
                for (b in 0..maxBits) {
                    if (b > 0) {
                        val k = cacheHash(px, b)
                        if (caches[b][k] == px) {
                            green[b][NUM_LITERAL_CODES + NUM_LENGTH_CODES + k]++
                            continue
                        }
                        caches[b][k] = px
                    }
                    green[b][(px ushr 8) and 0xFF]++
                    rba[b][(px ushr 16) and 0xFF]++
                    rba[b][256 + (px and 0xFF)]++
                    rba[b][512 + (px ushr 24)]++
                }
                pos++
            } else {
                val len = tokens.a[t]
                val lc = prefix(len).first
                for (b in 0..maxBits) green[b][NUM_LITERAL_CODES + lc]++
                distance[prefix(distanceCode(tokens.b[t], w, planes)).first]++
                for (b in 1..maxBits) for (k in pos until pos + len) caches[b][cacheHash(p[k], b)] = p[k]
                pos += len
            }
        }
        var best = 0
        var bestBits = Double.MAX_VALUE
        for (b in 0..maxBits) {
            var nats = 0.0
            var used = 0
            fun add(h: IntArray, from: Int, to: Int) {
                var n = 0
                for (i in from until to) if (h[i] > 0) { n += h[i]; used++ }
                if (n > 0) nats += entropy(h, from, to, n)
            }
            add(green[b], 0, green[b].size)
            add(rba[b], 0, 256)
            add(rba[b], 256, 512)
            add(rba[b], 512, 768)
            val bits = nats / ln(2.0) + 4.0 * used
            if (bits < bestBits) {
                bestBits = bits
                best = b
            }
        }
        return best
    }

    /** A canonical prefix code over [freq]'s alphabet, at most 15 bits a code, and how VP8L stores it. */
    private class PrefixCode(freq: IntArray) {
        val lengths: IntArray
        val codes: IntArray
        private val used = freq.indices.filter { freq[it] > 0 }

        init {
            lengths = if (used.size <= 1) IntArray(freq.size) else Deflater.codeLengths(freq, 15)
            codes = Deflater.canonicalCodes(lengths)
        }

        /** A code with one symbol or none reads no bits, so writes none. */
        fun write(out: BitWriter, symbol: Int) {
            if (used.size <= 1) return
            out.writeCode(codes[symbol], lengths[symbol])
        }

        fun writeHeader(out: BitWriter) {
            if (used.size <= 2 && used.all { it < 256 }) {
                // Simple code: one or two symbols, the first in 1 or 8 bits.
                val symbols = if (used.isEmpty()) listOf(0) else used
                out.write(1, 1)
                out.write(symbols.size - 1, 1)
                val first = symbols[0]
                if (first < 2) {
                    out.write(0, 1)
                    out.write(first, 1)
                } else {
                    out.write(1, 1)
                    out.write(first, 8)
                }
                if (symbols.size == 2) out.write(symbols[1], 8)
                return
            }
            // A single symbol past 255 is a normal code with one length, which reads no bits.
            val lens = if (used.size == 1) IntArray(lengths.size).also { it[used[0]] = 1 } else lengths
            out.write(0, 1)
            val runs = runLengths(lens)
            val clFreq = IntArray(CODE_LENGTH_CODES)
            var r = 0
            while (r < runs.size) {
                clFreq[runs[r]]++
                r += 2
            }
            val clLengths = Deflater.codeLengths(clFreq, 7)
            if (clFreq.count { it > 0 } == 1) clLengths[clFreq.indexOfFirst { it > 0 }] = 1
            val clCodes = Deflater.canonicalCodes(clLengths)
            var numCodeLengths = CODE_LENGTH_CODES
            while (numCodeLengths > 4 && clLengths[CODE_LENGTH_CODE_ORDER[numCodeLengths - 1]] == 0) numCodeLengths--
            out.write(numCodeLengths - 4, 4)
            for (i in 0 until numCodeLengths) out.write(clLengths[CODE_LENGTH_CODE_ORDER[i]], 3)
            out.write(0, 1) // max_symbol: the whole alphabet
            val singleCl = clFreq.count { it > 0 } == 1
            r = 0
            while (r < runs.size) {
                val sym = runs[r]
                if (!singleCl) out.writeCode(clCodes[sym], clLengths[sym])
                when (sym) {
                    16 -> out.write(runs[r + 1], 2)
                    17 -> out.write(runs[r + 1], 3)
                    18 -> out.write(runs[r + 1], 7)
                }
                r += 2
            }
        }

        /**
         * The code lengths as code-length symbols with their extra values: 16 repeats the last
         * nonzero length 3 to 6 times, 17 and 18 write runs of zeros.
         */
        private fun runLengths(lens: IntArray): IntArray {
            val out = ArrayList<Int>()
            var i = 0
            var prev = 8
            while (i < lens.size) {
                val v = lens[i]
                var run = 1
                while (i + run < lens.size && lens[i + run] == v) run++
                if (v == 0) {
                    var left = run
                    while (left >= 11) {
                        val n = minOf(left, 138)
                        out += 18; out += n - 11
                        left -= n
                    }
                    if (left >= 3) {
                        out += 17; out += left - 3
                        left = 0
                    }
                    repeat(left) { out += 0; out += 0 }
                } else {
                    var left = run
                    if (v != prev) {
                        out += v; out += 0
                        left--
                        prev = v
                    }
                    while (left >= 3) {
                        val n = minOf(left, 6)
                        out += 16; out += n - 3
                        left -= n
                    }
                    repeat(left) { out += v; out += 0 }
                }
                i += run
            }
            return out.toIntArray()
        }
    }

    /** LSB-first bit writer; prefix codes go most significant bit first, as DEFLATE writes them. */
    internal class BitWriter {
        private val out = ByteArrayBuilder(1024)
        private var acc = 0L
        private var count = 0

        /** The bits written so far. */
        val bitCount: Long get() = out.size.toLong() * 8 + count

        /** Everything [other] holds, appended bit for bit. */
        fun append(other: BitWriter) {
            val bytes = other.out.toByteArray()
            for (b in bytes) write(b.toInt() and 0xFF, 8)
            write(other.acc.toInt(), other.count)
        }

        fun write(value: Int, bits: Int) {
            if (bits == 0) return
            acc = acc or ((value.toLong() and ((1L shl bits) - 1)) shl count)
            count += bits
            while (count >= 8) {
                out.append(acc.toByte())
                acc = acc ushr 8
                count -= 8
            }
        }

        fun writeCode(code: Int, length: Int) {
            var reversed = 0
            for (k in 0 until length) reversed = reversed or (((code ushr k) and 1) shl (length - 1 - k))
            write(reversed, length)
        }

        fun toByteArray(): ByteArray {
            if (count > 0) {
                out.append(acc.toByte())
                acc = 0
                count = 0
            }
            return out.toByteArray()
        }
    }
}
