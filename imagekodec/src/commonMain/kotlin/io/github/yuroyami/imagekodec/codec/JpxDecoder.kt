package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.codec.Jp2Headers.Reader as R
import io.github.yuroyami.imagekodec.codec.Jp2Headers.Coding as Cod
import io.github.yuroyami.imagekodec.codec.Jp2Headers.Quant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * A pure-Kotlin JPEG 2000 decoder (ITU-T T.800), taking either JP2 container
 * boxes or a raw codestream, part 1 baseline. Produces an 8-bpc Gray or RGB
 * raster plus an optional alpha plane from a `cdef` opacity channel.
 *
 * Handled: SIZ/COD/QCD with COC/QCC overrides, multiple tiles and tile-parts,
 * LRCP/RLCP/RPCL/PCRL/CPRL progressions, general precincts, tag trees,
 * multi-layer tier-2 packet headers (with SOP/EPH), EBCOT tier-1 at the baseline
 * code-block style, reversible 5/3 and irreversible 9/7 inverse DWT, RCT and ICT
 * multiple-component transforms, DC level shift, component subsampling by
 * nearest upsample, and bit depths up to 16.
 *
 * Not handled, each of which makes [decode] return null: RGN regions of
 * interest, POC progression changes, PPM/PPT packed headers, and non-baseline
 * code-block styles (bypass, reset, termall, vsc, segsym). `ImageKodec.probe`
 * names them in main and tile-part headers, so a caller can find out without
 * attempting a decode.
 */
public object JpxDecoder {

    public class Result(
        public val width: Int,
        public val height: Int,
        public val colorSpace: String,
        /** Interleaved 8-bpc samples: 1 (gray) or 3 (RGB) per pixel. */
        public val pixelBytes: ByteArray,
        /** 8-bit opacity plane ([width]x[height]) from a cdef channel, or null. */
        public val alpha: ByteArray?,
    )

    public fun decode(data: ByteArray): Result? = runCatching { decodeOrThrow(data, 0) }.getOrNull()

    /**
     * Decode [data] with each side divided by [reduction], rounded up: 1, 2, 4 or 8. The decoder
     * drops the finest wavelet levels, as OpenJPEG's reduce option does, so the full-size samples
     * never exist. Each output sample is the low-pass value at every [reduction]-th sample of the
     * image, not the mean of a block. When a component has fewer levels than that, the decoder
     * drops the levels it has and averages blocks of the result for the rest.
     *
     * @throws IllegalArgumentException if [reduction] is not 1, 2, 4 or 8
     */
    @Throws(IllegalArgumentException::class)
    public fun decode(data: ByteArray, reduction: Int): Result? {
        require(reduction == 1 || reduction == 2 || reduction == 4 || reduction == 8) {
            "reduction must be 1, 2, 4 or 8, was $reduction"
        }
        return runCatching { decodeOrThrow(data, reduction.countTrailingZeroBits()) }.getOrNull()
    }

    /**
     * The decode with no catch at all: a damaged codestream or a missing feature throws
     * [ImageDecodeException], and anything else is a fault that escapes, so a test can tell the two
     * apart where [decode] returns null and the facade reports a decode error for both (#102).
     */
    internal fun decodeChecked(data: ByteArray, reduction: Int): Result = decodeOrThrow(data, reduction.countTrailingZeroBits())

    /** Preserve diagnostic failures for the facade without changing the nullable public API. */
    internal fun decodeForFacade(data: ByteArray, reduction: Int): Result = try {
        decodeOrThrow(data, reduction.countTrailingZeroBits())
    } catch (e: ImageDecodeException) {
        throw e
    } catch (e: Exception) {
        throw ImageDecodeException("JPEG 2000: ${e.message ?: "malformed codestream"}", e)
    }

    /** True when [data] looks like a JP2 container or a raw J2K codestream. */
    public fun isJpx(data: ByteArray): Boolean {
        if (data.size < 4) return false
        if ((data[0].toInt() and 0xFF) == 0xFF && (data[1].toInt() and 0xFF) == 0x4F) return true // SOC
        return data.size >= 12 && u32(data, 0) == 12L && u32(data, 4) == 0x6A502020L
    }

    private fun u32(d: ByteArray, p: Int): Long =
        ((d[p].toLong() and 0xFF) shl 24) or ((d[p + 1].toLong() and 0xFF) shl 16) or
            ((d[p + 2].toLong() and 0xFF) shl 8) or (d[p + 3].toLong() and 0xFF)

    // ---- JP2 container ------------------------------------------------------

    private class Jp2Info(
        val codestream: ByteArray,
        /** What the JP2 header says the components mean; none of it for a raw codestream. */
        val boxes: Jp2Color.Boxes,
    )

    private fun parseContainer(data: ByteArray): Jp2Info {
        val boxes = Jp2Color.parse(data) ?: throw ImageDecodeException("JPEG 2000: no jp2c codestream box")
        return Jp2Info(data.copyOfRange(boxes.codestreamStart, boxes.codestreamEnd), boxes)
    }

    // ---- codestream headers --------------------------------------------------

    private class Siz(
        val xsiz: Int, val ysiz: Int, val xosiz: Int, val yosiz: Int,
        val xtsiz: Int, val ytsiz: Int, val xtosiz: Int, val ytosiz: Int,
        val comps: Int, val prec: IntArray, val signed: BooleanArray,
        val dx: IntArray, val dy: IntArray,
    ) {
        val grid = Jp2TileGrid(
            xsiz.toLong() and 0xffffffffL, ysiz.toLong() and 0xffffffffL,
            xosiz.toLong() and 0xffffffffL, yosiz.toLong() and 0xffffffffL,
            xtsiz.toLong() and 0xffffffffL, ytsiz.toLong() and 0xffffffffL,
            xtosiz.toLong() and 0xffffffffL, ytosiz.toLong() and 0xffffffffL,
        )
        val tilesW: Int get() = grid.width
        val tilesH: Int get() = grid.height
    }

    // ---- geometry helpers -----------------------------------------------------

    private fun ceilDiv(a: Int, b: Int): Int = ((a.toLong() + b - 1) / b).toInt()
    private fun ceilShift(a: Int, s: Int): Int = ((a.toLong() + (1L shl s) - 1) shr s).toInt()

    // ---- tag tree -------------------------------------------------------------

    private class TagTree(val w: Int, val h: Int) {
        private val levels: Int
        private val low: IntArray
        private val known: BooleanArray
        private val offs: IntArray
        private val widths: IntArray

        init {
            var lw = w; var lh = h
            val ws = ArrayList<Int>(); val hs = ArrayList<Int>()
            while (true) {
                ws.add(lw); hs.add(lh)
                if (lw == 1 && lh == 1) break
                lw = ceilDiv(lw, 2); lh = ceilDiv(lh, 2)
            }
            levels = ws.size
            widths = ws.toIntArray()
            offs = IntArray(levels)
            var total = 0
            for (l in 0 until levels) { offs[l] = total; total += widths[l] * hs[l] }
            low = IntArray(total)
            known = BooleanArray(total)
        }

        private fun idx(l: Int, x: Int, y: Int) = offs[l] + y * widths[l] + x

        /**
         * The standard tag-tree query (B.10.2): decode bits until it is known
         * whether value(x,y) < [threshold]; true when it is. Node values are
         * lower bounds refined by 0-bits and pinned by a 1-bit.
         */
        fun decode(bio: Bio, x: Int, y: Int, threshold: Int): Boolean {
            var bound = 0
            for (l in levels - 1 downTo 0) {
                val i = idx(l, x shr l, y shr l)
                if (low[i] < bound) low[i] = bound
                while (!known[i] && low[i] < threshold) {
                    if (bio.bit() == 1) known[i] = true else low[i]++
                }
                if (!known[i]) return false // value >= threshold
                bound = low[i]
            }
            return true // leaf value = low[leaf] < threshold
        }

        /**
         * Decode until the leaf's exact value is known (zero-bitplane trees). Returns -1 when the
         * data ends first, because the zero bits past the end never finish a value, or when the
         * value passes any real bit depth.
         */
        fun decodeValue(bio: Bio, x: Int, y: Int): Int {
            var t = 1
            while (!decode(bio, x, y, t)) {
                if (bio.exhausted || t >= MAX_ZERO_BITPLANES) return -1
                t++
            }
            return low[idx(0, x, y)]
        }
    }

    // ---- packet-header bit reader (B.10.1 bit-stuffing) -----------------------

    private class Bio(val d: ByteArray, var pos: Int, val end: Int) {
        private var buf = 0
        private var ct = 0
        private var lastFF = false

        /** True once a read went past [end]: every later bit is a 0 that the data never held. */
        var exhausted = false

        fun bit(): Int {
            if (ct == 0) {
                if (pos >= end) { buf = 0; ct = if (lastFF) 7 else 8; lastFF = false; exhausted = true }
                else {
                    buf = d[pos++].toInt() and 0xFF
                    ct = if (lastFF) 7 else 8
                    lastFF = buf == 0xFF
                }
            }
            ct--
            return (buf shr ct) and 1
        }

        fun bits(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or bit() }; return v }

        /** Byte-align at the end of a packet header (consume the stuffed bit). */
        fun align() {
            ct = 0
            if (lastFF) { if (pos < end) pos++; lastFF = false }
        }
    }

    // ---- code-block / precinct / band model -----------------------------------

    private class CodeBlock(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        var included = false
        var zeroBitplanes = 0
        var lBlock = 3
        var passes = 0
        val data = ArrayList<ByteArray>()
        var newPasses = 0
    }

    private class Precinct(val cbW: Int, val cbH: Int, val blocks: List<CodeBlock?>) {
        val inclTree = TagTree(max(1, cbW), max(1, cbH))
        val zeroTree = TagTree(max(1, cbW), max(1, cbH))
    }

    private class Band(
        val orient: Int, // 0=LL 1=HL 2=LH 3=HH
        val x0: Int, val y0: Int, val x1: Int, val y1: Int,
        val precincts: List<Precinct>,
        val stepExp: Int, val stepMant: Int, val guardBits: Int,
        /** False for a band of a level that a reduced decode drops: it keeps no coefficients. */
        keep: Boolean,
    ) {
        var coeffs = if (keep) IntArray(max(0, (x1 - x0)) * max(0, (y1 - y0))) else IntArray(0)
    }

    private class Resolution(
        val r: Int,
        val x0: Int, val y0: Int, val x1: Int, val y1: Int,
        val numPw: Int, val numPh: Int,
        val bands: List<Band>,
    )

    private class TileComp(
        val comp: Int,
        val x0: Int, val y0: Int, val x1: Int, val y1: Int,
        val cod: Cod,
        val resolutions: List<Resolution>,
    )

    // ---- main decode -----------------------------------------------------------

    /** The decode with the [levels] finest wavelet levels dropped, as far as every component has them. */
    private fun decodeOrThrow(data: ByteArray, levels: Int): Result {
        val jp2 = parseContainer(data)
        val cs = jp2.codestream
        val r = R(cs, 0)
        if (r.u16() != 0xFF4F) r.fail("missing SOC marker")

        var siz: Siz? = null
        var mainCod: Cod? = null
        val mainCoc = HashMap<Int, Cod>()
        var mainQcd: Quant? = null
        val mainQcc = HashMap<Int, Quant>()
        val tileBodies = HashMap<Int, ArrayList<ByteArray>>()
        val tileCod = HashMap<Int, Cod>()
        val tileCoc = HashMap<Int, HashMap<Int, Cod>>()
        val tileQcd = HashMap<Int, Quant>()
        val tileQcc = HashMap<Int, HashMap<Int, Quant>>()

        var inTile = -1
        var tileEnd = 0L

        while (r.pos <= cs.size - 2) {
            val markerAt = r.pos
            val marker = r.u16()
            if (marker == 0xFFD9) break
            if (marker == 0xFF93) {
                if (inTile < 0) r.fail("SOD before SOT")
                if (r.pos > tileEnd) r.fail("SOD crosses the SOT tile-part length")
                val bodyEnd = minOf(tileEnd, cs.size.toLong()).toInt()
                tileBodies.getOrPut(inTile) { ArrayList() }.add(cs.copyOfRange(r.pos, bodyEnd))
                r.pos = bodyEnd
                inTile = -1
                continue
            }
            if (marker in 0xFF30..0xFF3F) continue
            if (marker < 0xFF00) r.fail("invalid marker 0x${marker.toString(16)}")

            r.context = Jp2Headers.markerName(marker)
            val len = r.u16()
            if (len < 2) r.fail("invalid segment length $len")
            val segmentEnd = r.pos.toLong() + len - 2
            if (segmentEnd > cs.size) r.fail("header cut off (segment length $len)")
            if (inTile >= 0 && segmentEnd > tileEnd) r.fail("header crosses the SOT tile-part length")
            // OpenJPEG bounds each marker reader by its payload length. A short
            // header must not read plausible fields from the following marker.
            r.end = segmentEnd.toInt()
            Jp2Headers.unsupportedMarker(marker)?.let { r.unsupported(it) }
            when (marker) {
                0xFF51 -> {
                    r.u16()
                    val xsiz = r.u32i(); val ysiz = r.u32i()
                    val xo = r.u32i(); val yo = r.u32i()
                    val xt = r.u32i(); val yt = r.u32i()
                    val xto = r.u32i(); val yto = r.u32i()
                    val nc = r.u16()
                    if (nc <= 0) r.fail("invalid component count $nc")
                    if (nc > 16) r.unsupported("with $nc components (1 to 16 are decodable)")
                    val prec = IntArray(nc); val signed = BooleanArray(nc)
                    val dx = IntArray(nc); val dy = IntArray(nc)
                    for (c in 0 until nc) {
                        val sample = r.u8()
                        prec[c] = (sample and 0x7F) + 1
                        signed[c] = sample and 0x80 != 0
                        dx[c] = r.u8(); dy[c] = r.u8()
                        if (prec[c] > 16) r.unsupported("with ${prec[c]}-bit samples (16-bit maximum)")
                        if (dx[c] == 0 || dy[c] == 0) r.fail("component $c has a zero subsampling factor")
                    }
                    siz = Siz(xsiz, ysiz, xo, yo, xt, yt, xto, yto, nc, prec, signed, dx, dy)
                }
                0xFF52 -> {
                    val cod = Jp2Headers.readCod(r)
                    if (inTile >= 0) tileCod[inTile] = cod else mainCod = cod
                }
                0xFF53 -> {
                    val nComps = siz?.comps ?: r.fail("before SIZ")
                    val c = Jp2Headers.component(r, nComps)
                    val base = (if (inTile >= 0) tileCod[inTile] else null) ?: mainCod ?: r.fail("before COD")
                    val coc = Jp2Headers.readCoc(r, base)
                    if (inTile >= 0) tileCoc.getOrPut(inTile) { HashMap() }[c] = coc else mainCoc[c] = coc
                }
                0xFF5C -> {
                    val q = Jp2Headers.readQuant(r, r.end)
                    if (inTile >= 0) tileQcd[inTile] = q else mainQcd = q
                }
                0xFF5D -> {
                    val nComps = siz?.comps ?: r.fail("before SIZ")
                    val c = Jp2Headers.component(r, nComps)
                    val q = Jp2Headers.readQuant(r, r.end)
                    if (inTile >= 0) tileQcc.getOrPut(inTile) { HashMap() }[c] = q else mainQcc[c] = q
                }
                0xFF90 -> {
                    if (inTile >= 0) r.fail("missing SOD before the next SOT")
                    if (len != 10) r.fail("invalid segment length $len (expected 10)")
                    val isot = r.u16()
                    val grid = siz?.grid ?: r.fail("before SIZ")
                    if (isot >= grid.count) r.fail("tile index $isot outside ${grid.count} tiles")
                    val psot = r.u32long()
                    r.u8(); r.u8()
                    inTile = isot
                    tileEnd = if (psot == 0L) cs.size.toLong() else markerAt.toLong() + psot
                    if (tileEnd < r.end.toLong() + 2) r.fail("tile-part length $psot leaves no SOD header")
                }
            }
            r.pos = r.end
            r.end = cs.size
            r.context = "codestream"
        }

        val s = siz ?: r.fail("missing SIZ marker")
        val cod0 = mainCod ?: r.fail("missing COD marker")
        val qcd0 = mainQcd ?: r.fail("missing QCD marker")
        if (inTile >= 0) r.fail("missing SOD marker for tile $inTile")
        if (tileBodies.isEmpty()) r.fail("missing SOT/SOD tile data")

        val imgW = s.xsiz - s.xosiz
        val imgH = s.ysiz - s.yosiz
        if (imgW <= 0 || imgH <= 0) r.fail("SIZ has invalid image dimensions ${imgW}x$imgH")
        Jp2Limits.sizeRefusal(imgW, imgH, data.size)?.let {
            throw ImageDecodeException("$it at byte ${r.pos}")
        }

        // Drop at most the levels that every coding style in the file has (A.6.1 picks one of these
        // per tile and component), and none where the reduced grid of a component would be empty.
        val styles = buildList {
            add(cod0); addAll(mainCoc.values); addAll(tileCod.values)
            for (m in tileCoc.values) addAll(m.values)
        }
        var drop = minOf(levels, styles.minOf { it.decompositions })
        fun empty(d: Int) = (0 until s.comps).any { c ->
            ceilShift(ceilDiv(s.xsiz, s.dx[c]), d) <= ceilShift(ceilDiv(s.xosiz, s.dx[c]), d) ||
                ceilShift(ceilDiv(s.ysiz, s.dy[c]), d) <= ceilShift(ceilDiv(s.yosiz, s.dy[c]), d)
        }
        while (drop > 0 && empty(drop)) drop--
        // A component subsampled past the image area has no sample at all, even at full size, so it
        // has nothing to show or to convert with the others; OpenJPEG cannot write such an image out
        // either (#102).
        if (empty(drop)) {
            val c = (0 until s.comps).first { c ->
                ceilDiv(s.xsiz, s.dx[c]) <= ceilDiv(s.xosiz, s.dx[c]) || ceilDiv(s.ysiz, s.dy[c]) <= ceilDiv(s.yosiz, s.dy[c])
            }
            throw ImageDecodeException("JPEG 2000: component $c, subsampled by ${s.dx[c]} by ${s.dy[c]}, has no samples in the image")
        }

        // Component output planes at the component's resolution with those levels dropped, and
        // where each starts on that grid (B.5: a level halves the coordinates, rounding up).
        val planeX0 = IntArray(s.comps) { ceilShift(ceilDiv(s.xosiz, s.dx[it]), drop) }
        val planeY0 = IntArray(s.comps) { ceilShift(ceilDiv(s.yosiz, s.dy[it]), drop) }
        val planeW = IntArray(s.comps) { ceilShift(ceilDiv(s.xsiz, s.dx[it]), drop) - planeX0[it] }
        val planeH = IntArray(s.comps) { ceilShift(ceilDiv(s.ysiz, s.dy[it]), drop) - planeY0[it] }
        val planes = Array(s.comps) { IntArray(planeW[it] * planeH[it]) }

        for ((t, parts) in tileBodies) {
            val body = parts.let {
                if (parts.size == 1) parts[0]
                else ByteArray(parts.sumOf { it.size }).also { out ->
                    var o = 0
                    for (p in parts) { p.copyInto(out, o); o += p.size }
                }
            }

            val cod = tileCod[t] ?: cod0
            val qcd = tileQcd[t] ?: qcd0
            // Precedence (A.6.1): tile COC > tile COD > main COC > main COD.
            fun codFor(c: Int): Cod = tileCoc[t]?.get(c) ?: tileCod[t] ?: mainCoc[c] ?: cod0
            fun quantFor(c: Int): Quant = tileQcc[t]?.get(c) ?: tileQcd[t] ?: mainQcc[c] ?: qcd0

            decodeTile(s, t, body, ::codFor, ::quantFor, cod, Planes(planes, planeW, planeH, planeX0, planeY0, drop))
        }

        // Assemble output: gray or RGB, plus optional cdef opacity channel. A reduced image takes
        // the rounded-up size; sampling clamps to the plane, so an offset grid repeats its edge.
        val out = assemble(s, jp2, planes, planeW, planeH, ceilShift(imgW, drop), ceilShift(imgH, drop))
        return if (drop < levels) out.averaged(1 shl (levels - drop)) else out
    }

    /** The component planes of one decode, on the grid with [r] wavelet levels dropped. */
    private class Planes(
        val data: Array<IntArray>, val w: IntArray, val h: IntArray, val x0: IntArray, val y0: IntArray, val r: Int,
    )

    /**
     * This result with each block of [f] by [f] samples averaged, for levels that a component
     * does not have: `ceil(width / f)` by `ceil(height / f)`, and an edge block averages what
     * it has.
     */
    private fun Result.averaged(f: Int): Result {
        val n = pixelBytes.size / (width * height)
        val w = ceilDiv(width, f)
        val h = ceilDiv(height, f)
        fun average(src: ByteArray, n: Int): ByteArray {
            val out = ByteArray(w * h * n)
            for (oy in 0 until h) for (ox in 0 until w) for (c in 0 until n) {
                var sum = 0
                var count = 0
                for (y in oy * f until min((oy + 1) * f, height)) for (x in ox * f until min((ox + 1) * f, width)) {
                    sum += src[(y * width + x) * n + c].toInt() and 0xFF
                    count++
                }
                out[(oy * w + ox) * n + c] = ((sum + count / 2) / count).toByte()
            }
            return out
        }
        return Result(w, h, colorSpace, average(pixelBytes, n), alpha?.let { average(it, 1) })
    }

    // ---- tile decode -----------------------------------------------------------

    private fun decodeTile(
        s: Siz, t: Int, body: ByteArray,
        codFor: (Int) -> Cod, quantFor: (Int) -> Quant, tileCod: Cod,
        out: Planes,
    ) {
        val ti = t % s.tilesW
        val tj = t / s.tilesW
        val tx0 = max(s.xtosiz.toLong() + ti.toLong() * s.xtsiz, s.xosiz.toLong()).toInt()
        val ty0 = max(s.ytosiz.toLong() + tj.toLong() * s.ytsiz, s.yosiz.toLong()).toInt()
        val tx1 = min(s.xtosiz.toLong() + (ti + 1L) * s.xtsiz, s.xsiz.toLong()).toInt()
        val ty1 = min(s.ytosiz.toLong() + (tj + 1L) * s.ytsiz, s.ysiz.toLong()).toInt()
        if (tx1 <= tx0 || ty1 <= ty0) return

        // Build the component/resolution/band/precinct/code-block model.
        val comps = ArrayList<TileComp>(s.comps)
        for (c in 0 until s.comps) {
            val cod = codFor(c)
            val q = quantFor(c)
            val cx0 = ceilDiv(tx0, s.dx[c]); val cy0 = ceilDiv(ty0, s.dy[c])
            val cx1 = ceilDiv(tx1, s.dx[c]); val cy1 = ceilDiv(ty1, s.dy[c])
            val res = ArrayList<Resolution>(cod.decompositions + 1)
            for (rr in 0..cod.decompositions) {
                val lev = cod.decompositions - rr
                val rx0 = ceilShift(cx0, lev); val ry0 = ceilShift(cy0, lev)
                val rx1 = ceilShift(cx1, lev); val ry1 = ceilShift(cy1, lev)
                val ppx = cod.ppx[rr]; val ppy = cod.ppy[rr]
                val numPw = if (rx1 > rx0) ceilDiv(rx1, 1 shl ppx) - (rx0 shr ppx) else 0
                val numPh = if (ry1 > ry0) ceilDiv(ry1, 1 shl ppy) - (ry0 shr ppy) else 0
                val bands = ArrayList<Band>()
                // Code-block partition exponents within a precinct.
                val cbw = min(cod.cbW, if (rr == 0) ppx else ppx - 1)
                val cbh = min(cod.cbH, if (rr == 0) ppy else ppy - 1)

                fun bandFor(orient: Int, qIndex: Int): Band {
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
                    val (se, sm) = stepFor(q, qIndex, rr)
                    // Precincts of this band: derived from the resolution grid.
                    val precincts = ArrayList<Precinct>(max(0, numPw * numPh))
                    val shift = if (rr == 0) 0 else 1
                    for (pj in 0 until numPh) for (pi in 0 until numPw) {
                        // The precinct's grid lines on the resolution grid, in band coordinates. A band
                        // below the top level has half the precinct size (B.6), so halve the grid lines,
                        // not the corners clipped to the resolution: a high-pass band that starts at an
                        // odd sample otherwise loses its first column.
                        val pbx0 = (((rx0 shr ppx) + pi) shl ppx) shr shift
                        val pby0 = (((ry0 shr ppy) + pj) shl ppy) shr shift
                        val pbx1 = (((rx0 shr ppx) + pi + 1) shl ppx) shr shift
                        val pby1 = (((ry0 shr ppy) + pj + 1) shl ppy) shr shift
                        val ibx0 = max(pbx0, bx0); val iby0 = max(pby0, by0)
                        val ibx1 = min(pbx1, bx1); val iby1 = min(pby1, by1)
                        if (ibx1 <= ibx0 || iby1 <= iby0) {
                            precincts.add(Precinct(0, 0, emptyList()))
                            continue
                        }
                        val gw = ceilDiv(ibx1, 1 shl cbw) - (ibx0 shr cbw)
                        val gh = ceilDiv(iby1, 1 shl cbh) - (iby0 shr cbh)
                        val blocks = ArrayList<CodeBlock?>(gw * gh)
                        for (gy in 0 until gh) for (gx in 0 until gw) {
                            val bx = max(ibx0, ((ibx0 shr cbw) + gx) shl cbw)
                            val by = max(iby0, ((iby0 shr cbh) + gy) shl cbh)
                            val ex = min(ibx1, ((ibx0 shr cbw) + gx + 1) shl cbw)
                            val ey = min(iby1, ((iby0 shr cbh) + gy + 1) shl cbh)
                            blocks.add(if (ex > bx && ey > by) CodeBlock(bx, by, ex, ey) else null)
                        }
                        precincts.add(Precinct(gw, gh, blocks))
                    }
                    return Band(orient, bx0, by0, bx1, by1, precincts, se, sm, q.guardBits, keep = rr <= cod.decompositions - out.r)
                }

                if (rr == 0) {
                    bands.add(bandFor(0, 0))
                } else {
                    bands.add(bandFor(1, 3 * (rr - 1) + 1)) // HL
                    bands.add(bandFor(2, 3 * (rr - 1) + 2)) // LH
                    bands.add(bandFor(3, 3 * (rr - 1) + 3)) // HH
                }
                res.add(Resolution(rr, rx0, ry0, rx1, ry1, numPw, numPh, bands))
            }
            comps.add(TileComp(c, cx0, cy0, cx1, cy1, cod, res))
        }

        // Tier-2: walk packets in progression order, filling code-block data.
        readPackets(body, comps, tileCod, s, tx0, ty0)

        // Tier-1 + dequant + IDWT per component, tile-local and before any shift.
        val samples = comps.map { decodeTileComp(s, it, out.r) }

        // Multiple-component transform on the tile area, then round, shift and clamp into the planes.
        val rounded = arrayOfNulls<IntArray>(comps.size)
        if (tileCod.mct == 1 && s.comps >= 3) applyInverseMct(samples, tileCod.reversible, rounded)
        for ((i, tc) in comps.withIndex()) {
            val c = tc.comp
            finalizeTileComp(s, tc, rounded[i] ?: samples[i].rounded(), samples[i], out.data[c], out.w[c], out.h[c], out.x0[c], out.y0[c])
        }
    }

    private fun stepFor(q: Quant, qIndex: Int, resolution: Int): Pair<Int, Int> = when (q.style) {
        0 -> Pair(q.exps.getOrElse(qIndex) { q.exps.lastOrNull() ?: 8 }, 0)
        // T.800 E-5: LL and the coarsest details keep epsilon0; each finer
        // detail resolution lowers it by one. Remaining synthesis levels run
        // in the opposite direction and cannot be used for this derivation.
        1 -> Pair((q.exps[0] - maxOf(0, resolution - 1)).coerceAtLeast(0), q.mants[0])
        else -> Pair(
            q.exps.getOrElse(qIndex) { q.exps.lastOrNull() ?: 8 },
            q.mants.getOrElse(qIndex) { q.mants.lastOrNull() ?: 0 },
        )
    }

    // ---- tier-2 packet reading ---------------------------------------------------

    private fun readPackets(body: ByteArray, comps: List<TileComp>, cod: Cod, s: Siz, tx0: Int, ty0: Int) {
        val bio = PacketReader(body, cod.sop, cod.eph)
        val maxRes = comps.maxOf { it.resolutions.size }
        val layers = cod.layers

        fun packet(l: Int, rr: Int, c: Int, p: Int) {
            val tc = comps.getOrNull(c) ?: return
            val res = tc.resolutions.getOrNull(rr) ?: return
            if (p >= res.numPw * res.numPh) return
            bio.readPacket(res, p, l)
        }

        // The three spatial orders visit each precinct where T.800 B.12.1.3 to B.12.1.5 reach it on
        // the reference grid, which depends on the component's subsampling and the resolution, not
        // on the precinct's ordinal: ordinal k of two resolutions or two components sit apart (#56).
        fun visits(order: Int): List<Visit> {
            val out = ArrayList<Visit>()
            for ((c, tc) in comps.withIndex()) for (rr in tc.resolutions.indices) {
                precinctVisits(tc, rr, s.dx[tc.comp], s.dy[tc.comp], tx0, ty0, c, out)
            }
            out.sortWith(
                when (order) {
                    2 -> compareBy<Visit>({ it.r }, { it.y }, { it.x }, { it.c })   // RPCL
                    3 -> compareBy({ it.y }, { it.x }, { it.c }, { it.r })          // PCRL
                    else -> compareBy({ it.c }, { it.y }, { it.x }, { it.r })       // CPRL
                },
            )
            return out
        }

        when (cod.progression) {
            0 -> for (l in 0 until layers) for (rr in 0 until maxRes) for (c in comps.indices) {
                val res = comps[c].resolutions.getOrNull(rr) ?: continue
                for (p in 0 until res.numPw * res.numPh) packet(l, rr, c, p)
            }
            1 -> for (rr in 0 until maxRes) for (l in 0 until layers) for (c in comps.indices) {
                val res = comps[c].resolutions.getOrNull(rr) ?: continue
                for (p in 0 until res.numPw * res.numPh) packet(l, rr, c, p)
            }
            2, 3, 4 -> for (v in visits(cod.progression)) for (l in 0 until layers) packet(l, v.r, v.c, v.p)
            else -> {}
        }
    }

    /** Precinct [p] of resolution [r] of component [c], which the spatial orders reach at ([x], [y]) on the reference grid. */
    private class Visit(val c: Int, val r: Int, val p: Int, val y: Long, val x: Long)

    /**
     * Adds to [out] where B.12.1.3 reaches each precinct of resolution [rr] of [tc]: the reference-grid
     * point x = XRsiz * 2^(PPx + N_L - r) * k (B-21) that falls in it, or the tile's own corner for a
     * first precinct that starts before the tile, as its second condition allows. On the
     * resolution's grid the precinct starts at (floor(trx0 / 2^PPx) + i) * 2^PPx, so the point is that
     * start scaled by the component's subsampling and the levels below the resolution.
     */
    private fun precinctVisits(tc: TileComp, rr: Int, dx: Int, dy: Int, tx0: Int, ty0: Int, c: Int, out: MutableList<Visit>) {
        val res = tc.resolutions[rr]
        if (res.x1 <= res.x0 || res.y1 <= res.y0) return
        val lev = tc.cod.decompositions - rr
        val ppx = tc.cod.ppx[rr]
        val ppy = tc.cod.ppy[rr]
        for (j in 0 until res.numPh) {
            val y = maxOf(ty0.toLong(), (((res.y0 shr ppy) + j).toLong() shl ppy) * dy shl lev)
            for (i in 0 until res.numPw) {
                val x = maxOf(tx0.toLong(), (((res.x0 shr ppx) + i).toLong() shl ppx) * dx shl lev)
                out.add(Visit(c, rr, j * res.numPw + i, y, x))
            }
        }
    }

    /** Reads packet headers + bodies sequentially from a tile bitstream. */
    private class PacketReader(val d: ByteArray, val sop: Boolean, val eph: Boolean) {
        var pos = 0

        fun readPacket(res: Resolution, p: Int, layer: Int) {
            if (pos >= d.size) return
            if (sop && pos + 6 <= d.size &&
                (d[pos].toInt() and 0xFF) == 0xFF && (d[pos + 1].toInt() and 0xFF) == 0x91
            ) {
                pos += 6
            }
            val bio = Bio(d, pos, d.size)
            val included = ArrayList<CodeBlock>()
            val newPassCounts = ArrayList<Int>()
            val segLens = ArrayList<Int>()

            if (bio.bit() == 0) {
                // Empty packet.
                bio.align()
                pos = bio.pos
                if (eph && pos + 2 <= d.size && (d[pos].toInt() and 0xFF) == 0xFF && (d[pos + 1].toInt() and 0xFF) == 0x92) pos += 2
                return
            }

            for (band in res.bands) {
                val precinct = band.precincts.getOrNull(p) ?: continue
                for (i in precinct.blocks.indices) {
                    val cb = precinct.blocks[i] ?: continue
                    val gx = i % max(1, precinct.cbW)
                    val gy = i / max(1, precinct.cbW)
                    var incl: Boolean
                    if (!cb.included) {
                        incl = precinct.inclTree.decode(bio, gx, gy, layer + 1)
                        if (incl) {
                            cb.zeroBitplanes = precinct.zeroTree.decodeValue(bio, gx, gy)
                            // A header cut off by the end of the data: keep the packets before it and
                            // skip the rest of the tile, as OpenJPEG does with a short stream.
                            if (cb.zeroBitplanes < 0) {
                                pos = d.size
                                return
                            }
                            cb.lBlock = 3
                        }
                    } else {
                        incl = bio.bit() == 1
                    }
                    if (!incl) continue
                    cb.included = true
                    // Number of new coding passes (B.10.6).
                    val np = when {
                        bio.bit() == 0 -> 1
                        bio.bit() == 0 -> 2
                        else -> {
                            val v = bio.bits(2)
                            if (v < 3) 3 + v
                            else {
                                val v2 = bio.bits(5)
                                if (v2 < 31) 6 + v2 else 37 + bio.bits(7)
                            }
                        }
                    }
                    // Lblock update (unary), then the single segment length.
                    while (bio.bit() == 1) cb.lBlock++
                    var bits = cb.lBlock
                    var passes = np
                    while (passes > 1) { bits++; passes = passes shr 1 }
                    // OpenJPEG refuses a length of more than 32 bits ("Invalid bit number"): no data is
                    // that long, and the read would wrap an Int to a negative length (#102).
                    if (bits > 32) throw ImageDecodeException("JPEG 2000: a packet header gives a code-block length of $bits bits")
                    val segLen = bio.bits(bits)
                    included.add(cb)
                    newPassCounts.add(np)
                    segLens.add(segLen)
                }
            }
            bio.align()
            pos = bio.pos
            if (eph && pos + 2 <= d.size && (d[pos].toInt() and 0xFF) == 0xFF && (d[pos + 1].toInt() and 0xFF) == 0x92) pos += 2

            for (k in included.indices) {
                val cb = included[k]
                val len = segLens[k]
                // A length past the data, or a 32-bit one that reads as a negative Int, runs to its end.
                val end = if (len < 0 || len > d.size - pos) d.size else pos + len
                cb.data.add(d.copyOfRange(pos, end))
                cb.passes += newPassCounts[k]
                pos = end
            }
        }
    }

    // ---- tier-1 EBCOT ---------------------------------------------------------

    private const val CTX_UNI = 18
    private const val CTX_RLC = 17

    /**
     * ZC context lookup, indexed [orientGroup][h*12 + v*4 + min(d,3)]: h and v
     * clamp to 2, but d must reach 3 because the HH table distinguishes d >= 3
     * (context 8) from d == 2.
     */
    private val ZC_LL = buildZcTable(0)
    private val ZC_HL = buildZcTable(1)
    private val ZC_HH = buildZcTable(2)

    private fun buildZcTable(group: Int): IntArray {
        val t = IntArray(36)
        for (h in 0..2) for (v in 0..2) for (dd in 0..3) {
            val ctx = when (group) {
                0 -> when { // LL and LH
                    h == 2 -> 8
                    h == 1 && v >= 1 -> 7
                    h == 1 && v == 0 && dd >= 1 -> 6
                    h == 1 -> 5
                    v == 2 -> 4
                    v == 1 -> 3
                    dd >= 2 -> 2
                    dd == 1 -> 1
                    else -> 0
                }
                1 -> when { // HL: transpose of LL
                    v == 2 -> 8
                    v == 1 && h >= 1 -> 7
                    v == 1 && h == 0 && dd >= 1 -> 6
                    v == 1 -> 5
                    h == 2 -> 4
                    h == 1 -> 3
                    dd >= 2 -> 2
                    dd == 1 -> 1
                    else -> 0
                }
                else -> when { // HH
                    dd >= 2 && h + v >= 1 -> if (dd >= 3) 8 else 7
                    dd >= 3 -> 8
                    dd == 2 -> 6
                    dd == 1 && h + v >= 2 -> 5
                    dd == 1 && h + v == 1 -> 4
                    dd == 1 -> 3
                    h + v >= 2 -> 2
                    h + v == 1 -> 1
                    else -> 0
                }
            }
            t[h * 12 + v * 4 + dd] = ctx
        }
        return t
    }

    private class T1(val w: Int, val h: Int) {
        val sig = IntArray(w * h)      // 1 = significant
        val visited = IntArray(w * h)  // pass-local flag
        val refined = IntArray(w * h)  // has had a refinement pass
        val sign = IntArray(w * h)     // 1 = negative
        val mag = IntArray(w * h)
        /** Bitplane of the last pass that touched the coefficient (for the r=0.5 bias). */
        val lastPlane = IntArray(w * h)
    }

    private fun decodeCodeBlock(cb: CodeBlock, band: Band, cod: Cod, mb: Int) {
        if (cb.data.isEmpty() || cb.passes <= 0) return
        val w = cb.x1 - cb.x0
        val h = cb.y1 - cb.y0
        if (w <= 0 || h <= 0) return
        val total = ByteArray(cb.data.sumOf { it.size })
        var o = 0
        for (seg in cb.data) { seg.copyInto(total, o); o += seg.size }

        val mq = MqDecoder(total, 0, total.size)
        val cx = IntArray(19)
        cx[0] = 4 shl 1
        cx[CTX_RLC] = 3 shl 1
        cx[CTX_UNI] = 46 shl 1

        val t1 = T1(w, h)
        val zc = when (band.orient) {
            1 -> ZC_HL
            3 -> ZC_HH
            else -> ZC_LL
        }

        var bp = mb - 1 - cb.zeroBitplanes
        // A magnitude of bp + 1 bits, doubled for the irreversible half step, must fit an Int. No
        // sample of 16 bits or fewer needs more, so a block that claims more is damaged.
        if (bp > MAX_MAGNITUDE_PLANE) throw ImageDecodeException("JPEG 2000: a code-block with ${bp + 1} magnitude bitplanes")
        var passNo = 0
        var passType = 2 // start with cleanup
        var passes = cb.passes
        while (passes > 0 && bp >= 0) {
            when (passType) {
                0 -> sigPropPass(t1, mq, cx, zc, bp)
                1 -> magRefPass(t1, mq, cx, bp)
                2 -> cleanupPass(t1, mq, cx, zc, bp)
            }
            passes--
            passNo++
            if (passType == 2) { passType = 0; bp-- } else passType++
        }
        // T.800 E.1.1/E.1.2 apply the half step at each coefficient's last decoded
        // plane: a block can stop partway through a plane. Reversible plane zero
        // stays exact; irreversible magnitudes carry twice the value so the
        // dequantizer can retain its fractional half step.
        if (cod.reversible) {
            for (i in t1.mag.indices) {
                val last = t1.lastPlane[i]
                if (t1.mag[i] != 0 && last > 0) t1.mag[i] = t1.mag[i] or (1 shl (last - 1))
            }
        } else {
            for (i in t1.mag.indices) {
                if (t1.mag[i] != 0) t1.mag[i] = (t1.mag[i] shl 1) or (1 shl t1.lastPlane[i])
            }
        }

        // Store signed magnitudes into the band's coefficient array.
        val bw = band.x1 - band.x0
        for (y in 0 until h) for (x in 0 until w) {
            val m = t1.mag[y * w + x]
            if (m == 0) continue
            val v = if (t1.sign[y * w + x] == 1) -m else m
            band.coeffs[(cb.y0 - band.y0 + y) * bw + (cb.x0 - band.x0 + x)] = v
        }
    }

    private fun T1.neighborSums(x: Int, y: Int): Triple<Int, Int, Int> {
        fun s(px: Int, py: Int) = if (px in 0 until w && py in 0 until h) sig[py * w + px] else 0
        val hh = s(x - 1, y) + s(x + 1, y)
        val vv = s(x, y - 1) + s(x, y + 1)
        val dd = s(x - 1, y - 1) + s(x + 1, y - 1) + s(x - 1, y + 1) + s(x + 1, y + 1)
        return Triple(min(hh, 2), min(vv, 2), min(dd, 3))
    }

    /** Sign context (ctx 9..13) + XOR bit from the H/V neighbour signs. */
    private fun T1.signContext(x: Int, y: Int): Pair<Int, Int> {
        fun c(px: Int, py: Int): Int {
            if (px !in 0 until w || py !in 0 until h) return 0
            if (sig[py * w + px] == 0) return 0
            return if (sign[py * w + px] == 1) -1 else 1
        }
        val hc = (c(x - 1, y) + c(x + 1, y)).coerceIn(-1, 1)
        val vc = (c(x, y - 1) + c(x, y + 1)).coerceIn(-1, 1)
        return when {
            hc == 1 && vc == 1 -> 13 to 0
            hc == 1 && vc == 0 -> 12 to 0
            hc == 1 && vc == -1 -> 11 to 0
            hc == 0 && vc == 1 -> 10 to 0
            hc == 0 && vc == 0 -> 9 to 0
            hc == 0 && vc == -1 -> 10 to 1
            hc == -1 && vc == 1 -> 11 to 1
            hc == -1 && vc == 0 -> 12 to 1
            else -> 13 to 1
        }
    }

    private fun sigPropPass(t1: T1, mq: MqDecoder, cx: IntArray, zc: IntArray, bp: Int) {
        val w = t1.w; val h = t1.h
        var y0 = 0
        while (y0 < h) {
            for (x in 0 until w) {
                for (y in y0 until min(y0 + 4, h)) {
                    val i = y * w + x
                    t1.visited[i] = 0
                    if (t1.sig[i] != 0) continue
                    val (hh, vv, dd) = t1.neighborSums(x, y)
                    val ctx = zc[hh * 12 + vv * 4 + dd]
                    if (ctx == 0) continue
                    t1.visited[i] = 1
                    if (mq.bit(cx, ctx) == 1) {
                        val (sctx, xor) = t1.signContext(x, y)
                        val sbit = mq.bit(cx, sctx) xor xor
                        t1.sig[i] = 1
                        t1.sign[i] = sbit
                        t1.mag[i] = 1 shl bp
                        t1.lastPlane[i] = bp
                    }
                }
            }
            y0 += 4
        }
    }

    private fun magRefPass(t1: T1, mq: MqDecoder, cx: IntArray, bp: Int) {
        val w = t1.w; val h = t1.h
        var y0 = 0
        while (y0 < h) {
            for (x in 0 until w) {
                for (y in y0 until min(y0 + 4, h)) {
                    val i = y * w + x
                    // Refine only coefficients significant BEFORE this plane's SPP
                    // (the visited flag marks everything SPP coded this plane).
                    if (t1.sig[i] == 0 || t1.visited[i] == 1) continue
                    val ctx = when {
                        t1.refined[i] != 0 -> 16
                        else -> {
                            val (hh, vv, dd) = t1.neighborSums(x, y)
                            if (hh + vv + dd > 0) 15 else 14
                        }
                    }
                    val bit = mq.bit(cx, ctx)
                    t1.refined[i] = 1
                    t1.lastPlane[i] = bp
                    if (bit == 1) t1.mag[i] = t1.mag[i] or (1 shl bp)
                }
            }
            y0 += 4
        }
    }

    private fun cleanupPass(t1: T1, mq: MqDecoder, cx: IntArray, zc: IntArray, bp: Int) {
        val w = t1.w; val h = t1.h
        var y0 = 0
        while (y0 < h) {
            for (x in 0 until w) {
                var y = y0
                val stripeH = min(4, h - y0)
                // Run-length mode: full stripe of 4, nothing significant or visited,
                // and every ZC context zero.
                var runMode = false
                if (stripeH == 4) {
                    runMode = true
                    for (yy in y0 until y0 + 4) {
                        val i = yy * w + x
                        if (t1.sig[i] != 0 || t1.visited[i] != 0) { runMode = false; break }
                        val (hh, vv, dd) = t1.neighborSums(x, yy)
                        if (zc[hh * 12 + vv * 4 + dd] != 0) { runMode = false; break }
                    }
                }
                if (runMode) {
                    if (mq.bit(cx, CTX_RLC) == 0) continue // whole column stays insignificant
                    val pos = (mq.bit(cx, CTX_UNI) shl 1) or mq.bit(cx, CTX_UNI)
                    y = y0 + pos
                    // The first significant coefficient: sign only (the 1 is implied).
                    val i = y * w + x
                    val (sctx, xor) = t1.signContext(x, y)
                    val sbit = mq.bit(cx, sctx) xor xor
                    t1.sig[i] = 1
                    t1.sign[i] = sbit
                    t1.mag[i] = 1 shl bp
                    t1.lastPlane[i] = bp
                    y++
                }
                while (y < y0 + stripeH) {
                    val i = y * w + x
                    if (t1.sig[i] == 0 && t1.visited[i] == 0) {
                        val (hh, vv, dd) = t1.neighborSums(x, y)
                        val ctx = zc[hh * 12 + vv * 4 + dd]
                        if (mq.bit(cx, ctx) == 1) {
                            val (sctx, xor) = t1.signContext(x, y)
                            val sbit = mq.bit(cx, sctx) xor xor
                            t1.sig[i] = 1
                            t1.sign[i] = sbit
                            t1.mag[i] = 1 shl bp
                            t1.lastPlane[i] = bp
                        }
                    }
                    t1.visited[i] = 0
                    y++
                }
            }
            y0 += 4
        }
        // Clear visited for the next bitplane's SPP.
        for (i in t1.visited.indices) t1.visited[i] = 0
    }

    // ---- component reconstruction ----------------------------------------------

    /**
     * One tile-component reconstructed at the kept resolution, before the MCT and the display
     * shift: [ints] holds the 5/3 integers, [fixed] the 9/7 samples in fixed point with [FRACT]
     * fractional bits. Exactly one of the two is set.
     */
    private class TileSamples(val x0: Int, val y0: Int, val x1: Int, val y1: Int, var ints: IntArray?, var fixed: LongArray?) {
        val w get() = x1 - x0
        val h get() = y1 - y0

        /** The samples rounded to integers: the 9/7 fraction is rounded once, here, half up. */
        fun rounded(): IntArray = ints ?: fixed!!.let { f ->
            IntArray(f.size) { ((f[it] + (1L shl (FRACT - 1))) shr FRACT).toInt() }
        }

        /** The samples in fixed point, the 5/3 integers shifted up. */
        fun fixedPoint(): LongArray = fixed ?: ints!!.let { v -> LongArray(v.size) { v[it].toLong() shl FRACT } }
    }

    private fun decodeTileComp(s: Siz, tc: TileComp, drop: Int): TileSamples {
        val cod = tc.cod
        // The resolution that this decode stops at: the full one, or [drop] levels below it.
        val top = cod.decompositions - drop
        // Tier-1 on every code-block of the kept resolutions.
        for (res in tc.resolutions) {
            if (res.r > top) break
            for (band in res.bands) {
                val mb = band.guardBits + band.stepExp - 1
                for (precinct in band.precincts) {
                    for (cb in precinct.blocks) {
                        if (cb != null) decodeCodeBlock(cb, band, cod, mb)
                    }
                }
            }
        }
        val ll = tc.resolutions[0].bands[0]
        if (cod.reversible) {
            // Inverse DWT: successively synthesize resolutions.
            var current = SubbandGrid(ll.x0, ll.y0, ll.x1, ll.y1, ll.coeffs)
            for (rr in 1..top) current = synthesize(current, tc.resolutions[rr])
            return TileSamples(current.x0, current.y0, current.x1, current.y1, current.data, null)
        }
        // The 9/7 path keeps every coefficient and sample in fixed point, wide enough for any
        // 16-bit image, through the synthesis and the ICT, and rounds once at the end, as
        // OpenJPEG keeps real values through dwt.c and mct.c and rounds in tcd.c (#101).
        val prec = s.prec[tc.comp]
        var current = FixedGrid(ll.x0, ll.y0, ll.x1, ll.y1, dequantize(ll, prec))
        for (rr in 1..top) {
            val res = tc.resolutions[rr]
            current = synthesizeFixed(current, res, res.bands.map { dequantize(it, prec) })
        }
        return TileSamples(current.x0, current.y0, current.x1, current.y1, null, current.data)
    }

    /**
     * [band]'s coefficients times their step size (E.1.1: 2^(Rb - eps_b) * (1 + mu_b / 2^11)), in
     * fixed point. The magnitudes arrive doubled, carrying the half-step bias, hence the /2 folded
     * into the scale. The band's integers are released: the synthesis reads only these.
     */
    private fun dequantize(band: Band, prec: Int): LongArray {
        val gain = when (band.orient) { 0 -> 0; 3 -> 2; else -> 1 }
        val rb = prec + gain
        val delta = (1.0 + band.stepMant / 2048.0) * pow2((rb - band.stepExp).toDouble()) / 2.0
        val scale = delta * (1L shl FRACT)
        val out = LongArray(band.coeffs.size)
        for (i in out.indices) {
            val c = band.coeffs[i]
            if (c == 0) continue
            val v = (c * scale).roundToLong()
            if (v > MAX_FIXED || v < -MAX_FIXED) throw ImageDecodeException("JPEG 2000: a coefficient beyond the range of the 9/7 transform")
            out[i] = v
        }
        band.coeffs = IntArray(0)
        return out
    }

    /** [samples], after any MCT, shifted to the unsigned display range and clipped into the component's [plane]. */
    private fun finalizeTileComp(
        s: Siz, tc: TileComp, samples: IntArray, rect: TileSamples, plane: IntArray, pw: Int, ph: Int, px0: Int, py0: Int,
    ) {
        val prec = s.prec[tc.comp]
        // T.800 G.1 restores only unsigned DC shifts, but Result exposes unsigned
        // display channels. Signed components need the same midpoint translation
        // of their centered range before clipping, as in OpenJPEG's output conversion.
        val displayOffset = 1 shl (prec - 1)
        val maxV = (1 shl prec) - 1
        val w = rect.w
        for (y in rect.y0 until rect.y1) for (x in rect.x0 until rect.x1) {
            val ppx = x - px0
            val ppy = y - py0
            if (ppx !in 0 until pw || ppy !in 0 until ph) continue
            var v = samples[(y - rect.y0) * w + (x - rect.x0)] + displayOffset
            if (v < 0) v = 0
            if (v > maxV) v = maxV
            plane[ppy * pw + ppx] = v
        }
    }

    private const val FRACT = 13

    /**
     * The largest 9/7 value kept between synthesis levels, in fixed point. One level grows a value
     * at most 141 times, and its largest product, a neighbour sum of up to 57 times this bound
     * against a lifting factor under 2^17, must stay inside a Long: 2^40 leaves room, and a
     * 16-bit image needs about 2^34.
     */
    private const val MAX_FIXED = 1L shl 40

    /** The 9/7 samples clipped to this before the ICT, far outside any display range: its products stay inside a Long. */
    private const val MAX_SAMPLE = 1L shl 34

    /** The highest magnitude bitplane a code-block may start at: doubled, its magnitude still fits an Int. */
    private const val MAX_MAGNITUDE_PLANE = 29

    /** More zero bitplanes than any sample holds: the header is damaged. */
    private const val MAX_ZERO_BITPLANES = 64

    private fun pow2(e: Double): Double {
        var v = 1.0
        var n = e.toInt()
        while (n > 0) { v *= 2; n-- }
        while (n < 0) { v /= 2; n++ }
        return v
    }

    private class SubbandGrid(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val data: IntArray)

    private class FixedGrid(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val data: LongArray)

    /** [synthesize] for the 9/7 filter, on fixed-point values; [bands] holds res.bands dequantized. */
    private fun synthesizeFixed(ll: FixedGrid, res: Resolution, bands: List<LongArray>): FixedGrid {
        val x0 = res.x0; val y0 = res.y0; val x1 = res.x1; val y1 = res.y1
        val w = x1 - x0; val h = y1 - y0
        if (w <= 0 || h <= 0) return FixedGrid(x0, y0, x1, y1, LongArray(0))
        val a = LongArray(w * h)

        fun scatter(bx0: Int, by0: Int, bx1: Int, by1: Int, data: LongArray, xo: Int, yo: Int) {
            val bw = bx1 - bx0
            for (v in by0 until by1) for (u in bx0 until bx1) {
                val gx = 2 * u + xo
                val gy = 2 * v + yo
                if (gx in x0 until x1 && gy in y0 until y1) {
                    a[(gy - y0) * w + (gx - x0)] = data[(v - by0) * bw + (u - bx0)]
                }
            }
        }
        scatter(ll.x0, ll.y0, ll.x1, ll.y1, ll.data, 0, 0)
        for ((i, band) in res.bands.withIndex()) {
            scatter(band.x0, band.y0, band.x1, band.y1, bands[i], band.orient and 1, (band.orient shr 1) and 1)
        }

        val row = LongArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) row[x] = a[y * w + x]
            liftFixed(row, x0, x0 + w)
            for (x in 0 until w) a[y * w + x] = row[x]
        }
        val col = LongArray(h)
        for (x in 0 until w) {
            for (y in 0 until h) col[y] = a[y * w + x]
            liftFixed(col, y0, y0 + h)
            for (y in 0 until h) a[y * w + x] = col[y]
        }
        for (v in a) {
            if (v > MAX_FIXED || v < -MAX_FIXED) throw ImageDecodeException("JPEG 2000: wavelet samples beyond the range of the 9/7 transform")
        }
        return FixedGrid(x0, y0, x1, y1, a)
    }

    /** One 2D 5/3 synthesis level: LL = [ll] + res.bands (HL/LH/HH) -> next LL. */
    private fun synthesize(ll: SubbandGrid, res: Resolution): SubbandGrid {
        val x0 = res.x0; val y0 = res.y0; val x1 = res.x1; val y1 = res.y1
        val w = x1 - x0; val h = y1 - y0
        if (w <= 0 || h <= 0) return SubbandGrid(x0, y0, x1, y1, IntArray(0))
        val a = IntArray(w * h)

        fun scatter(bx0: Int, by0: Int, bx1: Int, by1: Int, data: IntArray, xo: Int, yo: Int) {
            val bw = bx1 - bx0
            for (v in by0 until by1) for (u in bx0 until bx1) {
                val gx = 2 * u + xo
                val gy = 2 * v + yo
                if (gx in x0 until x1 && gy in y0 until y1) {
                    a[(gy - y0) * w + (gx - x0)] = data[(v - by0) * bw + (u - bx0)]
                }
            }
        }
        scatter(ll.x0, ll.y0, ll.x1, ll.y1, ll.data, 0, 0)
        for (band in res.bands) {
            val xo = band.orient and 1
            val yo = (band.orient shr 1) and 1
            scatter(band.x0, band.y0, band.x1, band.y1, band.coeffs, xo, yo)
        }

        // Horizontal pass on every row, then vertical on every column (T.800 F.3.4:
        // the order is interchangeable for these filters).
        val row = IntArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) row[x] = a[y * w + x]
            lift1d(row, x0, x0 + w)
            for (x in 0 until w) a[y * w + x] = row[x]
        }
        val col = IntArray(h)
        for (x in 0 until w) {
            for (y in 0 until h) col[y] = a[y * w + x]
            lift1d(col, y0, y0 + h)
            for (y in 0 until h) a[y * w + x] = col[y]
        }
        return SubbandGrid(x0, y0, x1, y1, a)
    }

    /**
     * In-place 1D synthesis over samples with GLOBAL indices [i0, i1);
     * X[k] holds global index i0+k. Interleaved layout: even global indices
     * are low-pass, odd are high-pass.
     */
    private fun lift1d(x: IntArray, i0: Int, i1: Int) {
        val n = i1 - i0
        if (n <= 0) return
        if (n == 1) {
            // T.800 F.3.6 defines the singleton rule before selecting either
            // filter: an odd high-pass sample halves, including fixed-point 9/7.
            if (i0 % 2 != 0) x[0] = x[0] shr 1
            return
        }
        fun get(i: Int): Int {
            // Symmetric extension around the boundaries.
            var k = i
            val last = i1 - 1
            while (k < i0 || k > last) {
                k = if (k < i0) 2 * i0 - k else 2 * last - k
            }
            return x[k - i0]
        }
        fun set(i: Int, v: Int) { if (i in i0 until i1) x[i - i0] = v }

        // 5/3: even then odd (T.800 F.3.8.2.1).
        var i = if (i0 % 2 == 0) i0 else i0 + 1
        while (i < i1) {
            set(i, get(i) - ((get(i - 1) + get(i + 1) + 2) shr 2))
            i += 2
        }
        i = if (i0 % 2 == 0) i0 + 1 else i0
        while (i < i1) {
            set(i, get(i) + ((get(i - 1) + get(i + 1)) shr 1))
            i += 2
        }
    }

    /** [lift1d] for the 9/7 filter, on fixed-point values (T.800 F.3.8.2.2). */
    private fun liftFixed(x: LongArray, i0: Int, i1: Int) {
        val n = i1 - i0
        if (n <= 0) return
        if (n == 1) {
            // T.800 F.3.6 defines the singleton rule before selecting either
            // filter: an odd high-pass sample halves, including fixed-point 9/7.
            if (i0 % 2 != 0) x[0] = x[0] shr 1
            return
        }
        fun get(i: Int): Long {
            // Symmetric extension around the boundaries.
            var k = i
            val last = i1 - 1
            while (k < i0 || k > last) {
                k = if (k < i0) 2 * i0 - k else 2 * last - k
            }
            return x[k - i0]
        }
        fun set(i: Int, v: Long) { if (i in i0 until i1) x[i - i0] = v }
        fun stepScale(parity: Int, factorNum: Long) {
            var i = if (i0 % 2 == parity) i0 else i0 + 1
            while (i < i1) {
                set(i, (get(i) * factorNum) shr FIX_SHIFT)
                i += 2
            }
        }
        fun stepLift(parity: Int, coefNum: Long) {
            var i = if (i0 % 2 == parity) i0 else i0 + 1
            while (i < i1) {
                set(i, get(i) + (((get(i - 1) + get(i + 1)) * coefNum) shr FIX_SHIFT))
                i += 2
            }
        }
        stepScale(0, K_FIX)         // even *= K
        stepScale(1, INV_K_FIX)     // odd  *= 1/K
        stepLift(0, NEG_DELTA_FIX)  // even -= delta * (odd neighbours)
        stepLift(1, NEG_GAMMA_FIX)
        stepLift(0, NEG_BETA_FIX)
        stepLift(1, NEG_ALPHA_FIX)
    }

    private const val FIX_SHIFT = 16
    private val K_FIX = (1.230174104914001 * (1 shl FIX_SHIFT)).toLong()
    private val INV_K_FIX = ((1.0 / 1.230174104914001) * (1 shl FIX_SHIFT)).toLong()
    private val NEG_ALPHA_FIX = (1.586134342059924 * (1 shl FIX_SHIFT)).toLong()
    private val NEG_BETA_FIX = (0.052980118572961 * (1 shl FIX_SHIFT)).toLong()
    private val NEG_GAMMA_FIX = (-0.882911075530934 * (1 shl FIX_SHIFT)).toLong()
    private val NEG_DELTA_FIX = (-0.443506852043971 * (1 shl FIX_SHIFT)).toLong()

    // ---- inverse MCT + assembly ---------------------------------------------------

    /**
     * The inverse multiple-component transform of the first three tile-components into [rounded],
     * when they share a geometry: the RCT on integers (G.2), or the ICT on the 9/7 fixed-point
     * samples (G.3), rounded once after it.
     */
    private fun applyInverseMct(samples: List<TileSamples>, reversible: Boolean, rounded: Array<IntArray?>) {
        val (a, b, c) = samples
        if (a.w != b.w || a.w != c.w || a.h != b.h || a.h != c.h) return
        val n = a.w * a.h
        if (reversible) { // RCT (G.2)
            val y = a.rounded(); val u = b.rounded(); val v = c.rounded()
            val r = IntArray(n); val g = IntArray(n); val bl = IntArray(n)
            for (i in 0 until n) {
                val gv = y[i] - ((u[i] + v[i]) shr 2)
                r[i] = v[i] + gv
                g[i] = gv
                bl[i] = u[i] + gv
            }
            rounded[0] = r; rounded[1] = g; rounded[2] = bl
            return
        }
        // ICT (G.3), with its factors in 1/65536ths: each output carries FRACT + 16 fractional
        // bits until the one rounding. Samples are first clipped to MAX_SAMPLE, far outside any
        // display range, so the products stay inside a Long.
        val y = a.fixedPoint(); val u = b.fixedPoint(); val v = c.fixedPoint()
        val half = 1L shl (FRACT + 15)
        val r = IntArray(n); val g = IntArray(n); val bl = IntArray(n)
        for (i in 0 until n) {
            val yv = y[i].coerceIn(-MAX_SAMPLE, MAX_SAMPLE) shl 16
            val uv = u[i].coerceIn(-MAX_SAMPLE, MAX_SAMPLE)
            val vv = v[i].coerceIn(-MAX_SAMPLE, MAX_SAMPLE)
            r[i] = ((yv + ICT_RV * vv + half) shr (FRACT + 16)).toInt()
            g[i] = ((yv - ICT_GU * uv - ICT_GV * vv + half) shr (FRACT + 16)).toInt()
            bl[i] = ((yv + ICT_BU * uv + half) shr (FRACT + 16)).toInt()
        }
        rounded[0] = r; rounded[1] = g; rounded[2] = bl
    }

    // T.800 G.3's ICT factors, in 1/65536ths.
    private val ICT_RV = (1.402 * 65536).roundToLong()
    private val ICT_GU = (0.34413 * 65536).roundToLong()
    private val ICT_GV = (0.71414 * 65536).roundToLong()
    private val ICT_BU = (1.772 * 65536).roundToLong()

    /**
     * The result in 8-bit samples: the components through the JP2 header's palette, channel
     * definitions and colour space (Annex I.5.3), as [Jp2Color.plan] lays them out, converted to
     * gray or RGB, with an opacity channel as the alpha plane.
     */
    private fun assemble(
        s: Siz, jp2: Jp2Info,
        planes: Array<IntArray>, planeW: IntArray, planeH: IntArray,
        imgW: Int, imgH: Int,
    ): Result {
        val plan = Jp2Color.plan(jp2.boxes, s.comps)
        val palette = jp2.boxes.palette

        // A component's display sample at image position (x, y): sampling clamps to the plane, so a
        // subsampled or offset grid repeats its edge.
        fun component(c: Int, x: Int, y: Int): Int {
            val cw = planeW[c]; val ch = planeH[c]
            val cx = (x / s.dx[c]).coerceIn(0, cw - 1)
            val cy = (y / s.dy[c]).coerceIn(0, ch - 1)
            return planes[c][cy * cw + cx]
        }
        fun value(ch: Jp2Color.Channel, x: Int, y: Int): Int {
            val v = component(ch.component, x, y)
            if (ch.column < 0) return v
            // Indices past the palette clamp to it, as OpenJPEG's opj_jp2_apply_pclr clamps them.
            return palette!!.values[ch.column][v.coerceIn(0, palette.entries - 1)]
        }
        fun precision(ch: Jp2Color.Channel): Int = if (ch.column < 0) s.prec[ch.component] else palette!!.depth[ch.column]

        fun to8(v: Int, prec: Int): Int = when {
            prec == 8 -> v
            prec > 8 -> v shr (prec - 8)
            else -> v * 255 / ((1 shl prec) - 1)
        }.coerceIn(0, 255)

        val colors = plan.colors
        val precs = IntArray(colors.size) { precision(colors[it]) }
        val n = if (plan.space == Jp2Color.Space.GRAY) 1 else 3
        val out = ByteArray(imgW * imgH * n)
        val samples = IntArray(colors.size)
        val rgb = IntArray(3)
        for (y in 0 until imgH) for (x in 0 until imgW) {
            for (k in colors.indices) samples[k] = value(colors[k], x, y)
            val o = (y * imgW + x) * n
            when (plan.space) {
                Jp2Color.Space.GRAY -> out[o] = to8(samples[0], precs[0]).toByte()
                Jp2Color.Space.RGB -> for (k in 0 until 3) out[o + k] = to8(samples[k], precs[k]).toByte()
                Jp2Color.Space.SYCC -> {
                    syccToRgb(samples[0], samples[1], samples[2], precs[0], rgb)
                    for (k in 0 until 3) out[o + k] = to8(rgb[k], precs[0]).toByte()
                }
                Jp2Color.Space.ESYCC -> {
                    esyccToRgb(samples[0], samples[1], samples[2], precs[0], rgb)
                    for (k in 0 until 3) out[o + k] = to8(rgb[k], precs[0]).toByte()
                }
                Jp2Color.Space.CMYK -> {
                    // OpenJPEG's color_cmyk_to_rgb: each ink's share left uncovered, times the black's,
                    // truncated to 8 bits.
                    val kMax = (1L shl precs[3]) - 1
                    val k = kMax - samples[3]
                    for (c in 0 until 3) {
                        val max = (1L shl precs[c]) - 1
                        out[o + c] = (255L * (max - samples[c]) * k / (max * kMax)).toInt().coerceIn(0, 255).toByte()
                    }
                }
                Jp2Color.Space.CMY -> for (k in 0 until 3) out[o + k] = to8(((1 shl precs[k]) - 1) - samples[k], precs[k]).toByte()
            }
        }
        val alphaChannel = plan.alpha
        val alpha = if (alphaChannel != null) {
            val p = precision(alphaChannel)
            ByteArray(imgW * imgH).also { ab ->
                for (y in 0 until imgH) for (x in 0 until imgW) ab[y * imgW + x] = to8(value(alphaChannel, x, y), p).toByte()
            }
        } else null
        if (alpha != null && plan.premultiplied) {
            // Premultiplied opacity (cdef type 2): the result holds straight alpha, so divide it out.
            for (i in 0 until imgW * imgH) {
                val a = alpha[i].toInt() and 0xFF
                for (k in 0 until n) {
                    val c = out[i * n + k].toInt() and 0xFF
                    out[i * n + k] = (if (a == 0) 0 else minOf(255, (c * 255 + a / 2) / a)).toByte()
                }
            }
        }
        return Result(imgW, imgH, if (n == 3) "DeviceRGB" else "DeviceGray", out, alpha)
    }

    // T.800-family YCC conversions with OpenJPEG's color.c factors, in fixed point: each product in
    // 1/2^20ths, truncated toward zero where OpenJPEG casts a double to int.
    private fun fix(v: Double): Long = kotlin.math.round(v * (1 shl 20)).toLong()
    private val SYCC_RV = fix(1.402)
    private val SYCC_GU = fix(0.344)
    private val SYCC_GV = fix(0.714)
    private val SYCC_BU = fix(1.772)

    /** sYCC (enumerated 18) to RGB, as OpenJPEG's sycc_to_rgb: chroma centred on 2^(prec - 1), each product truncated. */
    private fun syccToRgb(y: Int, cb: Int, cr: Int, prec: Int, out: IntArray) {
        val offset = 1 shl (prec - 1)
        val max = (1 shl prec) - 1
        val u = (cb - offset).toLong()
        val v = (cr - offset).toLong()
        out[0] = (y + SYCC_RV * v / (1 shl 20)).toInt().coerceIn(0, max)
        out[1] = (y - (SYCC_GU * u + SYCC_GV * v) / (1 shl 20)).toInt().coerceIn(0, max)
        out[2] = (y + SYCC_BU * u / (1 shl 20)).toInt().coerceIn(0, max)
    }

    private val ESYCC = longArrayOf(
        fix(0.0000368), fix(1.40199), fix(1.0003), fix(0.344125), fix(0.7141128), fix(0.999823), fix(1.77204), fix(0.000008),
    )

    /** e-sYCC (enumerated 24) to RGB, as OpenJPEG's color_esycc_to_rgb: rounded half up, then clipped. */
    private fun esyccToRgb(y: Int, cb: Int, cr: Int, prec: Int, out: IntArray) {
        val offset = 1 shl (prec - 1)
        val max = (1 shl prec) - 1
        val yy = y.toLong() shl 20
        val u = (cb - offset).toLong()
        val v = (cr - offset).toLong()
        val half = 1L shl 19
        fun round(x: Long): Int = (if (x >= 0) x shr 20 else -((-x) shr 20)).toInt()
        out[0] = round(yy - ESYCC[0] * u + ESYCC[1] * v + half).coerceIn(0, max)
        out[1] = round(ESYCC[2] * y - ESYCC[3] * u - ESYCC[4] * v + half).coerceIn(0, max)
        out[2] = round(ESYCC[5] * y + ESYCC[6] * u - ESYCC[7] * v + half).coerceIn(0, max)
    }
}
