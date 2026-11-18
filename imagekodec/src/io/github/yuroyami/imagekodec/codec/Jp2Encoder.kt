package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.ImageMetadata
import io.github.yuroyami.imagekodec.Jp2EncodeOptions
import io.github.yuroyami.imagekodec.Jp2Progression
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.KiteBitmap16
import io.github.yuroyami.imagekodec.internal.Budget
import kotlin.math.sqrt

/**
 * A JPEG 2000 Part 1 encoder (#180), written from ITU-T T.800: the forward 5/3 and 9/7 lifting
 * wavelets (Annex F, each the exact inverse of the synthesis [JpxDecoder] runs), the RCT and ICT
 * (Annex G), scalar quantization with expounded step sizes (Annex E), EBCOT tier 1 in the
 * encoding direction ([Jp2BlockEncoder], Annex D), tier 2 ([Jp2PacketWriter], Annex B), rate
 * control by post-compression rate and distortion optimisation (Annex J.14), and the JP2 file
 * format (Annex I).
 *
 * The 9/7 path works in `Double`. Its additions, multiplications and divisions round the same way
 * on every Kotlin target, IEEE 754 leaves them no freedom, and nothing here calls a transcendental
 * function, so a picture gives the same bytes everywhere; a committed hash checks that on each
 * target. The 5/3 path and everything that decides bits works on integers.
 */
internal object Jp2Encoder {

    // ---- the tile model -------------------------------------------------------------------------

    /** A code-block: its coded passes, what they are worth, and the state tier 2 keeps for it. */
    class Block(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        var coded: CodedBlock = CodedBlock.EMPTY
        /** Squared error, in image units, of a decode that has none of the block's passes. */
        var energy = 0.0
        /** The passes at each point of the convex hull, and the slope of the hull up to it. */
        var hullPasses = IntArray(0)
        var hullSlopes = DoubleArray(0)
        var hullGains = DoubleArray(0)
        /** The passes a decoder has of the block after each layer. */
        var layerEnd = IntArray(0)

        var lBlock = 3
        var sent = 0
        var sentBefore = 0
        var included = false

        /** The bytes of the first [k] passes. */
        fun byteAt(k: Int): Int = if (k == 0) 0 else coded.rates[k - 1]

        /** The hull points whose slope is at least [lambda]. */
        fun hullCount(lambda: Double): Int {
            var lo = 0
            var hi = hullSlopes.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (hullSlopes[mid] >= lambda) lo = mid + 1 else hi = mid
            }
            return lo
        }

        fun passesAt(lambda: Double): Int = hullCount(lambda).let { if (it == 0) 0 else hullPasses[it - 1] }
    }

    class Precinct(val cbW: Int, val cbH: Int, val blocks: Array<Block?>) {
        val inclusion = Jp2TagTreeEncoder(cbW, cbH)
        val zeros = Jp2TagTreeEncoder(cbW, cbH)
    }

    class Band(
        val orient: Int, val index: Int,
        val x0: Int, val y0: Int, val x1: Int, val y1: Int,
        val precincts: List<Precinct>,
    ) {
        /** Mb of T.800 E-2: the guard bits plus the exponent, less one. */
        var magnitudePlanes = 0
        val width: Int get() = x1 - x0
        val height: Int get() = y1 - y0
    }

    class Resolution(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val ppx: Int, val ppy: Int, val numPw: Int, val numPh: Int, val bands: List<Band>)

    class Comp(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val resolutions: List<Resolution>)

    class Packet(val layer: Int, val res: Int, val comp: Int, val precinct: Int)

    class Tile(val index: Int, val x0: Int, val y0: Int, val comps: List<Comp>, var mct: Boolean) {
        var order: List<Packet> = emptyList()
    }

    /** The components to code: [planes] holds each one's samples, unsigned, of [precision] bits. */
    private class Image(
        val width: Int, val height: Int, val precision: Int,
        val planes: Array<IntArray>, val gray: Boolean, val alpha: Boolean,
    ) {
        val components: Int get() = planes.size
    }

    // ---- entry points ---------------------------------------------------------------------------

    fun encode(bitmap: KiteBitmap, options: Jp2EncodeOptions, metadata: ImageMetadata, cancel: (() -> Unit)?): ByteArray {
        val precision = if (options.bitsPerSample == 0) 8 else options.bitsPerSample
        require(precision <= 8) { "bitsPerSample of a KiteBitmap must be at most 8, was ${options.bitsPerSample}" }
        val argb = bitmap.argb
        var gray = true
        var alpha = false
        for (p in argb) {
            val r = (p ushr 16) and 0xFF
            if (r != (p ushr 8) and 0xFF || r != p and 0xFF) gray = false
            if (p ushr 24 != 0xFF) alpha = true
        }
        val n = (if (gray) 1 else 3) + (if (alpha) 1 else 0)
        val max = (1 shl precision) - 1
        fun scaled(v: Int): Int = if (precision == 8) v else (v * max + 127) / 255
        val planes = Array(n) { IntArray(argb.size) }
        for (i in argb.indices) {
            val p = argb[i]
            if (gray) planes[0][i] = scaled(p and 0xFF) else {
                planes[0][i] = scaled((p ushr 16) and 0xFF)
                planes[1][i] = scaled((p ushr 8) and 0xFF)
                planes[2][i] = scaled(p and 0xFF)
            }
            if (alpha) planes[n - 1][i] = scaled(p ushr 24)
        }
        return encodeImage(Image(bitmap.width, bitmap.height, precision, planes, gray, alpha), options, metadata, cancel)
    }

    fun encode16(bitmap: KiteBitmap16, options: Jp2EncodeOptions, metadata: ImageMetadata, cancel: (() -> Unit)?): ByteArray {
        val precision = if (options.bitsPerSample == 0) 16 else options.bitsPerSample
        val n = bitmap.channels
        val max = (1 shl precision) - 1
        val samples = bitmap.samples
        val count = bitmap.width * bitmap.height
        // 65535 * 32767 + 32767 still fits an Int, so the rounding needs no Long.
        val planes = Array(n) { c ->
            IntArray(count) { i ->
                val s = samples[i * n + c].toInt() and 0xFFFF
                if (precision == 16) s else (s * max + 32767) / 65535
            }
        }
        return encodeImage(Image(bitmap.width, bitmap.height, precision, planes, n <= 2, n == 2 || n == 4), options, metadata, cancel)
    }

    // ---- the encode -----------------------------------------------------------------------------

    private class Setup(
        val o: Jp2EncodeOptions,
        val image: Image,
        val tileW: Int, val tileH: Int, val tilesX: Int, val tilesY: Int,
        val levels: Int, val reversible: Boolean, val style: Int,
        val cbx: Int, val cby: Int, val pp: Int,
        /** The quantization step of each band index, in sample units (1 for the 5/3 path). */
        val steps: DoubleArray, val exponents: IntArray, val mantissas: IntArray,
        /** The image-domain squared error of a unit error in each band index. */
        val bandWeights: DoubleArray,
    ) {
        val mct: Boolean get() = image.components >= 3

        /** G of the QCD segment, set once the blocks are coded. */
        var guardBits = 2
    }

    private fun encodeImage(image: Image, o: Jp2EncodeOptions, metadata: ImageMetadata, cancel: (() -> Unit)?): ByteArray {
        val w = image.width
        val h = image.height
        val tileW = if (o.tileWidth == 0) w else minOf(o.tileWidth, w)
        val tileH = if (o.tileHeight == 0) h else minOf(o.tileHeight, h)
        val tilesX = (w + tileW - 1) / tileW
        val tilesY = (h + tileH - 1) / tileH
        require(tilesX.toLong() * tilesY <= 65535) { "a ${w}x$h image in ${tileW}x$tileH tiles needs ${tilesX.toLong() * tilesY} tiles, more than the 65535 a codestream holds" }
        // A level halves a side, rounding up; past the shorter side of a whole tile the bands would be empty.
        var levels = o.levels
        while (levels > 0 && minOf(tileW, tileH) < (1 shl levels)) levels--
        val reversible = o.lossless
        var style = 0
        if (o.bypass) style = style or Jp2Headers.STYLE_BYPASS
        if (o.resetContexts) style = style or Jp2Headers.STYLE_RESET
        if (o.terminateEachPass) style = style or Jp2Headers.STYLE_TERMALL
        if (o.verticalCausal) style = style or Jp2Headers.STYLE_VSC
        if (o.segmentationSymbols) style = style or Jp2Headers.STYLE_SEGSYM
        val pp = if (o.precinctSize == 0) 15 else o.precinctSize.countTrailingZeroBits()
        val bands = 3 * levels + 1
        val steps = DoubleArray(bands)
        val exponents = IntArray(bands)
        val mantissas = IntArray(bands)
        val weights = DoubleArray(bands)
        val p = image.precision
        for (b in 0 until bands) {
            val rr = if (b == 0) 0 else (b - 1) / 3 + 1
            val orient = if (b == 0) 0 else (b - 1) % 3 + 1
            val norm2 = Jp2Wavelet.bandNorm2(levels, rr, orient, reversible)
            if (reversible) {
                steps[b] = 1.0
                weights[b] = norm2
            } else {
                // Each band's step is the base step over the norm of its synthesis basis, so that a step
                // costs every band the same image error; the rate control then truncates (J.14).
                val base = pow2(p - 8) * (if (o.effort == 1) 2.0 else 1.0)
                val gain = when (orient) { 0 -> 0; 3 -> 2; else -> 1 }
                // IEEE 754 rounds a square root exactly, so it gives the same bits on every target.
                val (eps, mu) = stepParts(base / minOf(sqrt(norm2), MAX_NORM), p + gain)
                exponents[b] = eps
                mantissas[b] = mu
                steps[b] = pow2(p + gain - eps) * (1.0 + mu / 2048.0)
                weights[b] = norm2 * steps[b] * steps[b]
            }
        }
        val setup = Setup(
            o, image, tileW, tileH, tilesX, tilesY, levels, reversible, style,
            o.codeBlockWidth.countTrailingZeroBits(), o.codeBlockHeight.countTrailingZeroBits(), pp,
            steps, exponents, mantissas, weights,
        )

        val tiles = ArrayList<Tile>(tilesX * tilesY)
        for (t in 0 until tilesX * tilesY) {
            cancel?.invoke()
            tiles += codeTile(setup, t, cancel)
        }
        fixQuantization(setup, tiles)
        val layers = o.layerCount
        for (tile in tiles) tile.order = packetOrder(tile, layers, o.progression)
        rateControl(setup, tiles, layers)
        val codestream = codestream(setup, tiles, layers, metadata)
        return if (o.codestreamOnly) codestream else Jp2Boxes.file(image.width, image.height, image.components, image.precision, image.gray, image.alpha, metadata, codestream)
    }

    /** T.800 E-3's exponent and mantissa of the step nearest [step] for a band of nominal range [range] bits. */
    private fun stepParts(step: Double, range: Int): Pair<Int, Int> {
        var e = 0
        var v = step
        while (v >= 2.0) { v /= 2.0; e++ }
        while (v < 1.0) { v *= 2.0; e-- }
        var mu = ((v - 1.0) * 2048.0 + 0.5).toInt()
        if (mu == 2048) { mu = 0; e++ }
        val eps = (range - e).coerceIn(0, 31)
        return eps to (if (range - e == eps) mu else 0)
    }

    private fun pow2(e: Int): Double {
        var v = 1.0
        repeat(if (e > 0) e else 0) { v *= 2.0 }
        repeat(if (e < 0) -e else 0) { v /= 2.0 }
        return v
    }

    // ---- one tile -------------------------------------------------------------------------------

    private fun codeTile(s: Setup, t: Int, cancel: (() -> Unit)?): Tile {
        val image = s.image
        val tx = t % s.tilesX
        val ty = t / s.tilesX
        val x0 = tx * s.tileW
        val y0 = ty * s.tileH
        val x1 = minOf(x0 + s.tileW, image.width)
        val y1 = minOf(y0 + s.tileH, image.height)
        val tw = x1 - x0
        val th = y1 - y0
        val n = image.components
        val shift = 1 shl (image.precision - 1)
        fun samples(c: Int): IntArray {
            val src = image.planes[c]
            return IntArray(tw * th) { i -> src[(y0 + i / tw) * image.width + x0 + i % tw] - shift }
        }
        val comps = List(n) { buildComp(s, x0, y0, x1, y1) }
        if (s.reversible) {
            val planes = Array(n) { samples(it) }
            if (s.mct && s.o.effort == 3) {
                // Code the tile both ways and keep the smaller.
                val plain = Array(n) { planes[it].copyOf() }
                val withRct = Array(n) { planes[it].copyOf() }
                rct(withRct)
                val a = List(n) { buildComp(s, x0, y0, x1, y1) }
                for (c in 0 until n) codeReversible(s, a[c], withRct[c], x0, y0, x1, y1, c, true, cancel)
                for (c in 0 until n) codeReversible(s, comps[c], plain[c], x0, y0, x1, y1, c, false, cancel)
                return if (coded(a) <= coded(comps)) Tile(t, x0, y0, a, true) else Tile(t, x0, y0, comps, false)
            }
            if (s.mct) rct(planes)
            for (c in 0 until n) codeReversible(s, comps[c], planes[c], x0, y0, x1, y1, c, s.mct, cancel)
        } else {
            val planes = Array(n) { c -> samples(c).let { v -> DoubleArray(v.size) { v[it].toDouble() } } }
            if (s.mct) ict(planes)
            for (c in 0 until n) codeIrreversible(s, comps[c], planes[c], x0, y0, x1, y1, c, cancel)
        }
        return Tile(t, x0, y0, comps, s.mct)
    }

    private fun coded(comps: List<Comp>): Long {
        var sum = 0L
        for (c in comps) for (r in c.resolutions) for (b in r.bands) for (p in b.precincts) for (k in p.blocks) if (k != null) sum += k.coded.data.size
        return sum
    }

    /** The forward RCT (T.800 G.2), the exact inverse of JpxDecoder's: Y, then B - G, then R - G. */
    private fun rct(p: Array<IntArray>) {
        val r = p[0]; val g = p[1]; val b = p[2]
        for (i in r.indices) {
            val y = (r[i] + 2 * g[i] + b[i]) shr 2
            val u = b[i] - g[i]
            val v = r[i] - g[i]
            r[i] = y; g[i] = u; b[i] = v
        }
    }

    /** The forward ICT (T.800 G.3). */
    private fun ict(p: Array<DoubleArray>) {
        val r = p[0]; val g = p[1]; val b = p[2]
        for (i in r.indices) {
            val y = 0.299 * r[i] + 0.587 * g[i] + 0.114 * b[i]
            val cb = -0.16875 * r[i] - 0.33126 * g[i] + 0.5 * b[i]
            val cr = 0.5 * r[i] - 0.41869 * g[i] - 0.08131 * b[i]
            r[i] = y; g[i] = cb; b[i] = cr
        }
    }

    /**
     * The squared error over R, G and B that a unit error in component [c] leaves after the inverse
     * colour transform: the sum of the squares of its column of G-6 or G-8.
     */
    private fun componentWeight(s: Setup, c: Int, mct: Boolean): Double {
        if (!mct || c >= 3) return 1.0
        if (s.reversible) return if (c == 0) 3.0 else 11.0 / 16.0
        return when (c) {
            0 -> 3.0
            1 -> 0.34413 * 0.34413 + 1.772 * 1.772
            else -> 1.402 * 1.402 + 0.71414 * 0.71414
        }
    }

    /**
     * The resolutions, bands, precincts and code-blocks of a tile-component, laid out exactly as
     * JpxDecoder's decodeTile lays them out (T.800 B.5 to B.7).
     */
    private fun buildComp(s: Setup, cx0: Int, cy0: Int, cx1: Int, cy1: Int): Comp {
        val nl = s.levels
        val res = ArrayList<Resolution>(nl + 1)
        for (rr in 0..nl) {
            val lev = nl - rr
            val rx0 = ceilShift(cx0, lev); val ry0 = ceilShift(cy0, lev)
            val rx1 = ceilShift(cx1, lev); val ry1 = ceilShift(cy1, lev)
            val ppx = s.pp; val ppy = s.pp
            val numPw = if (rx1 > rx0) ceilDiv(rx1, 1 shl ppx) - (rx0 shr ppx) else 0
            val numPh = if (ry1 > ry0) ceilDiv(ry1, 1 shl ppy) - (ry0 shr ppy) else 0
            val cbw = minOf(s.cbx, if (rr == 0) ppx else ppx - 1)
            val cbh = minOf(s.cby, if (rr == 0) ppy else ppy - 1)
            fun band(orient: Int, index: Int): Band {
                val xo = orient and 1
                val yo = (orient shr 1) and 1
                val bx0: Int; val by0: Int; val bx1: Int; val by1: Int
                if (rr == 0) {
                    bx0 = rx0; by0 = ry0; bx1 = rx1; by1 = ry1
                } else {
                    val l2 = lev + 1
                    bx0 = ceilDiv(cx0 - (1 shl (l2 - 1)) * xo, 1 shl l2)
                    by0 = ceilDiv(cy0 - (1 shl (l2 - 1)) * yo, 1 shl l2)
                    bx1 = ceilDiv(cx1 - (1 shl (l2 - 1)) * xo, 1 shl l2)
                    by1 = ceilDiv(cy1 - (1 shl (l2 - 1)) * yo, 1 shl l2)
                }
                val shift = if (rr == 0) 0 else 1
                val precincts = ArrayList<Precinct>(maxOf(0, numPw * numPh))
                for (pj in 0 until numPh) for (pi in 0 until numPw) {
                    val pbx0 = (((rx0 shr ppx) + pi) shl ppx) shr shift
                    val pby0 = (((ry0 shr ppy) + pj) shl ppy) shr shift
                    val pbx1 = (((rx0 shr ppx) + pi + 1) shl ppx) shr shift
                    val pby1 = (((ry0 shr ppy) + pj + 1) shl ppy) shr shift
                    val ibx0 = maxOf(pbx0, bx0); val iby0 = maxOf(pby0, by0)
                    val ibx1 = minOf(pbx1, bx1); val iby1 = minOf(pby1, by1)
                    if (ibx1 <= ibx0 || iby1 <= iby0) {
                        precincts.add(Precinct(0, 0, emptyArray()))
                        continue
                    }
                    val gw = ceilDiv(ibx1, 1 shl cbw) - (ibx0 shr cbw)
                    val gh = ceilDiv(iby1, 1 shl cbh) - (iby0 shr cbh)
                    val blocks = arrayOfNulls<Block>(gw * gh)
                    for (gy in 0 until gh) for (gx in 0 until gw) {
                        val bx = maxOf(ibx0, ((ibx0 shr cbw) + gx) shl cbw)
                        val by = maxOf(iby0, ((iby0 shr cbh) + gy) shl cbh)
                        val ex = minOf(ibx1, ((ibx0 shr cbw) + gx + 1) shl cbw)
                        val ey = minOf(iby1, ((iby0 shr cbh) + gy + 1) shl cbh)
                        if (ex > bx && ey > by) blocks[gy * gw + gx] = Block(bx, by, ex, ey)
                    }
                    precincts.add(Precinct(gw, gh, blocks))
                }
                return Band(orient, index, bx0, by0, bx1, by1, precincts)
            }
            val bands = if (rr == 0) listOf(band(0, 0)) else listOf(band(1, 3 * (rr - 1) + 1), band(2, 3 * (rr - 1) + 2), band(3, 3 * (rr - 1) + 3))
            res.add(Resolution(rx0, ry0, rx1, ry1, ppx, ppy, numPw, numPh, bands))
        }
        return Comp(cx0, cy0, cx1, cy1, res)
    }

    private fun codeReversible(s: Setup, comp: Comp, samples: IntArray, x0: Int, y0: Int, x1: Int, y1: Int, c: Int, mct: Boolean, cancel: (() -> Unit)?) {
        val coefficients = Jp2Wavelet.forward53(samples, x0, y0, x1, y1, s.levels)
        val coder = Jp2BlockEncoder(s.style, reversible = true)
        val weight = componentWeight(s, c, mct)
        forEachBand(comp) { band, rr ->
            val data = coefficients[bandSlot(rr, band.orient)]
            codeBlocks(s, band, data, null, weight * s.bandWeights[band.index], coder, cancel)
        }
    }

    private fun codeIrreversible(s: Setup, comp: Comp, samples: DoubleArray, x0: Int, y0: Int, x1: Int, y1: Int, c: Int, cancel: (() -> Unit)?) {
        val coefficients = Jp2Wavelet.forward97(samples, x0, y0, x1, y1, s.levels)
        val coder = Jp2BlockEncoder(s.style, reversible = false)
        val weight = componentWeight(s, c, s.mct)
        forEachBand(comp) { band, rr ->
            val real = coefficients[bandSlot(rr, band.orient)]
            val step = s.steps[band.index]
            // Deadzone scalar quantization (E.1): the index is the magnitude in steps, rounded down.
            val magnitudes = DoubleArray(real.size)
            val indices = IntArray(real.size)
            for (i in real.indices) {
                val m = (if (real[i] < 0) -real[i] else real[i]) / step
                magnitudes[i] = m
                val q = m.toInt()
                indices[i] = if (real[i] < 0) -q else q
            }
            codeBlocks(s, band, indices, magnitudes, weight * s.bandWeights[band.index], coder, cancel)
        }
    }

    /** The slot of band [orient] of resolution [rr] in what Jp2Wavelet returns. */
    private fun bandSlot(rr: Int, orient: Int): Int = if (rr == 0) 0 else 3 * (rr - 1) + orient

    private inline fun forEachBand(comp: Comp, action: (Band, Int) -> Unit) {
        for ((rr, res) in comp.resolutions.withIndex()) for (band in res.bands) action(band, rr)
    }

    private fun codeBlocks(s: Setup, band: Band, values: IntArray, magnitudes: DoubleArray?, weight: Double, coder: Jp2BlockEncoder, cancel: (() -> Unit)?) {
        val bw = band.width
        for (precinct in band.precincts) for (b in precinct.blocks) {
            if (b == null) continue
            cancel?.invoke()
            val offset = (b.y0 - band.y0) * bw + (b.x0 - band.x0)
            b.coded = coder.encode(values, offset, bw, b.x1 - b.x0, b.y1 - b.y0, band.orient, magnitudes)
            var energy = 0.0
            for (y in 0 until b.y1 - b.y0) for (x in 0 until b.x1 - b.x0) {
                val i = offset + y * bw + x
                val m = if (magnitudes != null) magnitudes[i] else values[i].toDouble()
                energy += m * m
            }
            b.energy = energy * weight
            hull(b, weight)
        }
    }

    /**
     * The convex hull of the block's truncation points in the rate and distortion plane (J.14.4):
     * the points a slope threshold can pick, each with the slope that reaches it.
     */
    private fun hull(b: Block, weight: Double) {
        val n = b.coded.passes
        val passes = IntArray(n)
        val slopes = DoubleArray(n)
        val gains = DoubleArray(n)
        var size = 0
        for (k in 1..n) {
            val rate = b.coded.rates[k - 1]
            val gain = b.coded.gains[k - 1] * weight
            while (true) {
                val lastRate = if (size == 0) 0 else b.coded.rates[passes[size - 1] - 1]
                val lastGain = if (size == 0) 0.0 else gains[size - 1]
                val dGain = gain - lastGain
                if (dGain <= 0.0) break
                val dRate = rate - lastRate
                val slope = if (dRate <= 0) Double.MAX_VALUE else dGain / dRate
                if (size > 0 && slope >= slopes[size - 1]) { size--; continue }
                passes[size] = k
                slopes[size] = slope
                gains[size] = gain
                size++
                break
            }
        }
        b.hullPasses = passes.copyOf(size)
        b.hullSlopes = slopes.copyOf(size)
        b.hullGains = gains.copyOf(size)
    }

    /**
     * The guard bits and exponents of the QCD segment (E.1.1, A.6.4), now that the coded blocks say
     * how many magnitude bit planes each band needs: Mb = G + eps - 1 must hold every block's.
     */
    private fun fixQuantization(s: Setup, tiles: List<Tile>) {
        val bands = s.exponents.size
        val planes = IntArray(bands)
        for (tile in tiles) for (comp in tile.comps) forEachBand(comp) { band, _ ->
            for (p in band.precincts) for (b in p.blocks) if (b != null && b.coded.planes > planes[band.index]) planes[band.index] = b.coded.planes
        }
        if (s.reversible) {
            s.guardBits = 2
            val extra = if (s.mct) 1 else 0
            for (b in 0 until bands) {
                val orient = if (b == 0) 0 else (b - 1) % 3 + 1
                val gain = when (orient) { 0 -> 0; 3 -> 2; else -> 1 }
                s.exponents[b] = maxOf(s.image.precision + gain + extra, planes[b] - s.guardBits + 1)
                check(s.exponents[b] <= 31) { "a band needs ${planes[b]} magnitude bit planes" }
            }
        } else {
            var g = 1
            for (b in 0 until bands) g = maxOf(g, planes[b] - s.exponents[b] + 1)
            check(g <= 7) { "a band needs $g guard bits" }
            s.guardBits = g
        }
        for (tile in tiles) for (comp in tile.comps) forEachBand(comp) { band, _ -> band.magnitudePlanes = s.guardBits + s.exponents[band.index] - 1 }
    }

    // ---- packet order ---------------------------------------------------------------------------

    /** Every packet of [tile] in [progression] order, as JpxDecoder's readPackets walks them (B.12.1). */
    private fun packetOrder(tile: Tile, layers: Int, progression: Jp2Progression): List<Packet> {
        val comps = tile.comps
        val maxRes = comps.maxOf { it.resolutions.size }
        val out = ArrayList<Packet>()
        fun all(l: Int, rr: Int, c: Int) {
            val res = comps[c].resolutions.getOrNull(rr) ?: return
            for (p in 0 until res.numPw * res.numPh) out += Packet(l, rr, c, p)
        }
        when (progression) {
            Jp2Progression.LRCP -> for (l in 0 until layers) for (rr in 0 until maxRes) for (c in comps.indices) all(l, rr, c)
            Jp2Progression.RLCP -> for (rr in 0 until maxRes) for (l in 0 until layers) for (c in comps.indices) all(l, rr, c)
            else -> {
                // The reference-grid point where B.12.1.3 to B.12.1.5 reach each precinct; every component here
                // has a subsampling of 1.
                class Visit(val c: Int, val r: Int, val p: Int, val y: Long, val x: Long)
                val visits = ArrayList<Visit>()
                for ((c, comp) in comps.withIndex()) for ((rr, res) in comp.resolutions.withIndex()) {
                    if (res.x1 <= res.x0 || res.y1 <= res.y0) continue
                    val lev = comp.resolutions.size - 1 - rr
                    for (j in 0 until res.numPh) {
                        val y = maxOf(tile.y0.toLong(), (((res.y0 shr res.ppy) + j).toLong() shl res.ppy) shl lev)
                        for (i in 0 until res.numPw) {
                            val x = maxOf(tile.x0.toLong(), (((res.x0 shr res.ppx) + i).toLong() shl res.ppx) shl lev)
                            visits += Visit(c, rr, j * res.numPw + i, y, x)
                        }
                    }
                }
                val sorted = visits.sortedWith(
                    when (progression) {
                        Jp2Progression.RPCL -> compareBy<Visit>({ it.r }, { it.y }, { it.x }, { it.c })
                        Jp2Progression.PCRL -> compareBy({ it.y }, { it.x }, { it.c }, { it.r })
                        else -> compareBy({ it.c }, { it.y }, { it.x }, { it.r })
                    },
                )
                for (v in sorted) for (l in 0 until layers) out += Packet(l, v.r, v.c, v.p)
            }
        }
        return out
    }

    // ---- rate control ---------------------------------------------------------------------------

    private fun rateControl(s: Setup, tiles: List<Tile>, layers: Int) {
        val blocks = ArrayList<Block>()
        for (tile in tiles) for (comp in tile.comps) forEachBand(comp) { band, _ ->
            for (p in band.precincts) for (b in p.blocks) if (b != null) {
                b.layerEnd = IntArray(layers)
                blocks += b
            }
        }
        // Every slope a threshold can stop at, steepest first.
        val all = ArrayList<Double>()
        for (b in blocks) for (v in b.hullSlopes) all += v
        val slopes = all.sortedDescending().distinct().toDoubleArray()
        val writers = tiles.map { Jp2PacketWriter(it, layers, s.style, s.o.sopMarkers, s.o.ephMarkers) }
        val fixed = headerBytes(s, tiles, layers)

        /** Sets layer [l] to threshold index [k] (-1 for nothing), or to every pass with [all]. */
        fun assign(l: Int, k: Int, all: Boolean = false) {
            val lambda = if (k < 0) Double.POSITIVE_INFINITY else slopes[k]
            for (b in blocks) {
                val prev = if (l == 0) 0 else b.layerEnd[l - 1]
                val v = if (all) b.coded.passes else b.passesAt(lambda)
                b.layerEnd[l] = maxOf(prev, v)
            }
        }
        fun size(l: Int): Long {
            if (s.o.effort == 1) {
                // An estimate: the code-block bytes, about two header bytes for each block a layer adds to,
                // and a byte for each packet.
                var sum = fixed.toLong()
                for (b in blocks) {
                    sum += b.byteAt(b.layerEnd[l])
                    for (k in 0..l) if (b.layerEnd[k] > (if (k == 0) 0 else b.layerEnd[k - 1])) sum += 2
                }
                for (tile in tiles) sum += tile.order.count { it.layer <= l }
                return sum
            }
            var sum = fixed.toLong()
            for (wr in writers) sum += wr.write(null, l)
            return sum
        }
        /** The largest threshold index from [from] whose first [l] + 1 layers fit [target] bytes. */
        fun fit(l: Int, from: Int, to: Int, target: Double): Int {
            assign(l, from)
            if (size(l) > target) return from
            var lo = from
            var hi = to
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                assign(l, mid)
                if (size(l) <= target) lo = mid else hi = mid - 1
            }
            assign(l, lo)
            return lo
        }

        val o = s.o
        val top = slopes.size - 1
        val raw = s.image.width.toDouble() * s.image.height * s.image.components * s.image.precision / 8.0
        if (o.ratios.isNotEmpty()) {
            var k = -1
            for ((l, ratio) in o.ratios.withIndex()) k = fit(l, k, top, raw / ratio)
            if (o.lossless) assign(layers - 1, top, all = true)
            return
        }
        // The last layer: everything, or the quality target.
        val last = layers - 1
        val kLast: Int
        if (o.lossless) {
            kLast = top
            assign(last, top, all = true)
        } else {
            kLast = qualityIndex(s, blocks, slopes)
            assign(last, kLast)
        }
        if (layers == 1) return
        // Each layer before the last about half the size of the next.
        for (l in 0 until last) for (b in blocks) b.layerEnd[l] = 0
        val full = size(last).toDouble()
        var k = -1
        for (l in 0 until last) {
            var target = full
            repeat(last - l) { target /= 2.0 }
            k = fit(l, k, kLast, target)
        }
        if (o.lossless) assign(last, top, all = true) else assign(last, kLast)
    }

    /**
     * The threshold index that meets the quality target: the shallowest slope whose remaining squared
     * error is within the error that a PSNR of `20 + 0.28 * quality` dB allows. 10^(PSNR / 10) is
     * 100 * 10^(0.028 * quality), formed by multiplication alone.
     */
    private fun qualityIndex(s: Setup, blocks: List<Block>, slopes: DoubleArray): Int {
        val image = s.image
        var factor = 100.0
        repeat(s.o.quality) { factor *= TEN_TO_0_028 }
        val peak = ((1 shl image.precision) - 1).toDouble()
        val allowed = image.width.toDouble() * image.height * image.components * peak * peak / factor
        var total = 0.0
        for (b in blocks) total += b.energy
        fun remaining(k: Int): Double {
            if (k < 0) return total
            val lambda = slopes[k]
            var gained = 0.0
            for (b in blocks) {
                val n = b.hullCount(lambda)
                if (n > 0) gained += b.hullGains[n - 1]
            }
            return total - gained
        }
        if (remaining(-1) <= allowed) return -1
        var lo = 0
        var hi = slopes.size - 1
        if (hi < 0 || remaining(hi) > allowed) return hi
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (remaining(mid) <= allowed) hi = mid else lo = mid + 1
        }
        return lo
    }

    // ---- the codestream -------------------------------------------------------------------------

    /** The bytes outside the packets: the main header, each tile-part header and EOC. */
    private fun headerBytes(s: Setup, tiles: List<Tile>, layers: Int): Int =
        mainHeader(s, layers, 0).size + tiles.sumOf { 14 + if (it.mct != s.mct) cod(s, layers, it.mct).size else 0 } + 2

    private fun mainHeader(s: Setup, layers: Int, padding: Int): ByteArray {
        val out = Jp2ByteBuffer(256)
        val image = s.image
        out.u16(0xFF4F)
        // SIZ (A.5.1): no offsets, unsigned components of one precision, no subsampling.
        val n = image.components
        out.u16(0xFF51)
        out.u16(38 + 3 * n)
        out.u16(0)
        out.u32(image.width.toLong()); out.u32(image.height.toLong())
        out.u32(0); out.u32(0)
        out.u32(s.tileW.toLong()); out.u32(s.tileH.toLong())
        out.u32(0); out.u32(0)
        out.u16(n)
        repeat(n) { out.add(image.precision - 1); out.add(1); out.add(1) }
        out.add(cod(s, layers, s.mct))
        // QCD (A.6.4): no quantization for the 5/3 path, expounded steps for the 9/7 path.
        val bands = s.exponents.size
        out.u16(0xFF5C)
        if (s.reversible) {
            out.u16(3 + bands)
            out.add(s.guardBits shl 5)
            for (b in 0 until bands) out.add(s.exponents[b] shl 3)
        } else {
            out.u16(3 + 2 * bands)
            out.add((s.guardBits shl 5) or 2)
            for (b in 0 until bands) out.u16((s.exponents[b] shl 11) or s.mantissas[b])
        }
        // COM segments of binary zeros, when the file would otherwise be smaller than the decoder's
        // expansion bound expects of an image this size (Budget); see codestream().
        var left = padding
        while (left > 0) {
            val chunk = minOf(left, 65535 - 4)
            out.u16(0xFF64)
            out.u16(chunk + 4)
            out.u16(0)
            repeat(chunk) { out.add(0) }
            left -= chunk
        }
        return out.toByteArray()
    }

    /** A COD segment (A.6.1) with the multiple component transform [mct]. */
    private fun cod(s: Setup, layers: Int, mct: Boolean): ByteArray {
        val out = Jp2ByteBuffer(32)
        val precincts = s.o.precinctSize != 0
        out.u16(0xFF52)
        out.u16(12 + if (precincts) s.levels + 1 else 0)
        out.add((if (precincts) 1 else 0) or (if (s.o.sopMarkers) 2 else 0) or (if (s.o.ephMarkers) 4 else 0))
        out.add(s.o.progression.ordinal)
        out.u16(layers)
        out.add(if (mct) 1 else 0)
        out.add(s.levels)
        out.add(s.cbx - 2)
        out.add(s.cby - 2)
        out.add(s.style)
        out.add(if (s.reversible) 1 else 0)
        if (precincts) repeat(s.levels + 1) { out.add((s.pp shl 4) or s.pp) }
        return out.toByteArray()
    }

    private fun codestream(s: Setup, tiles: List<Tile>, layers: Int, metadata: ImageMetadata): ByteArray {
        val body = Jp2ByteBuffer(4096)
        for (tile in tiles) {
            val packets = Jp2ByteBuffer(4096)
            Jp2PacketWriter(tile, layers, s.style, s.o.sopMarkers, s.o.ephMarkers).write(packets, layers - 1)
            val tileCod = if (tile.mct != s.mct) cod(s, layers, tile.mct) else ByteArray(0)
            // SOT (A.4.2): one tile-part a tile.
            body.u16(0xFF90)
            body.u16(10)
            body.u16(tile.index)
            body.u32(12L + tileCod.size + 2 + packets.size)
            body.add(0)
            body.add(1)
            body.add(tileCod)
            body.u16(0xFF93)
            body.add(packets.toByteArray())
        }
        body.u16(0xFFD9)
        // JpxDecoder refuses an image of more pixels than Budget allows the file's size, a guard against
        // decompression bombs, and a flat picture can code smaller than that. A COM segment pads it.
        val image = s.image
        val boxes = if (s.o.codestreamOnly) 0 else Jp2Boxes.overhead(image.components, image.alpha, metadata)
        var padding = 0
        val main0 = mainHeader(s, layers, 0).size
        if (Budget.fitsAbsolute(image.width, image.height)) {
            while (!Budget.fits(image.width, image.height, main0 + padding + body.size + boxes)) padding += 1024
        }
        val main = mainHeader(s, layers, padding)
        val out = Jp2ByteBuffer(main.size + body.size)
        out.add(main)
        out.add(body.toByteArray())
        return out.toByteArray()
    }

    private fun ceilDiv(a: Int, b: Int): Int = ((a.toLong() + b - 1) / b).toInt()
    private fun ceilShift(a: Int, s: Int): Int = ((a.toLong() + (1L shl s) - 1) shr s).toInt()

    /** 10^0.028, the factor of one quality step in 10^(PSNR / 10). */
    private const val TEN_TO_0_028 = 1.066596121230258

    /** A band norm beyond which a smaller step buys nothing but more bit planes; see codeIrreversible. */
    private const val MAX_NORM = 1024.0
}
