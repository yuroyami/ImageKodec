package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.MqEncoder

/**
 * A small JBIG2 writer for the symbol dictionaries no installed encoder makes (#189): ones that
 * refine and aggregate symbols, arithmetic coded. It follows T.88 itself: the integer
 * coding of Annex A.2, the IAID coding of A.3, the standard Huffman tables of Annex B with the
 * canonical codes of B.3 for an uncompressed plain dictionary, the refinement template 1 of 6.3.5.3
 * and the text region of 6.4.
 * jbig2dec reads every file it writes in the oracle test.
 */
internal object Jbig2Writer {

    /** A bilevel glyph, row by row, 1 for black. */
    class Glyph(val w: Int, val h: Int, val bits: IntArray) {
        operator fun get(x: Int, y: Int): Int = if (x in 0 until w && y in 0 until h) bits[y * w + x] else 0
    }

    /** An instance of symbol [id] with its top left at ([s], [t]); with [target], refined into that glyph. */
    class Instance(val id: Int, val s: Int, val t: Int, val target: Glyph? = null, val rdx: Int = 0, val rdy: Int = 0)

    /** A new dictionary symbol: one known symbol refined (one instance), or a region of [instances]. */
    class NewSymbol(val w: Int, val h: Int, val instances: List<Instance>)

    // ---- the arithmetic coder's procedures -------------------------------------------------------

    private class Coder {
        val mq = MqEncoder()
        fun ctx() = IntArray(512)

        /** Annex A.2 inverted: a sign, a range prefix, then the offset into the range; null is OOB. */
        fun int(cx: IntArray, v: Int?) {
            var prev = 1
            fun bit(b: Int) {
                mq.encode(cx, prev, b)
                prev = if (prev < 256) (prev shl 1) or b else ((((prev shl 1) or b) and 511) or 256)
            }
            if (v == null) {
                bit(1); bit(0); bit(0); bit(0)
                return
            }
            val a = if (v < 0) -v else v
            bit(if (v < 0) 1 else 0)
            val (prefix, bits, low) = when {
                a < 4 -> Triple(0, 2, 0)
                a < 20 -> Triple(1, 4, 4)
                a < 84 -> Triple(2, 6, 20)
                a < 340 -> Triple(3, 8, 84)
                a < 4436 -> Triple(4, 12, 340)
                else -> Triple(5, 32, 4436)
            }
            repeat(prefix) { bit(1) }
            if (prefix < 5) bit(0)
            for (i in bits - 1 downTo 0) bit(((a - low) ushr i) and 1)
        }

        /** A.3: the id's bits from the most significant, each in the context of those before it. */
        fun iaid(cx: IntArray, id: Int, codeLen: Int) {
            var prev = 1
            for (i in codeLen - 1 downTo 0) {
                val b = (id ushr i) and 1
                mq.encode(cx, prev, b)
                prev = (prev shl 1) or b
            }
        }
    }

    /** Refinement template 1 (T.88 Figure 13): [target] coded against [ref] shifted by ([dx], [dy]). */
    private fun refine(mq: MqEncoder, cx: IntArray, target: Glyph, ref: Glyph, dx: Int, dy: Int) {
        for (y in 0 until target.h) for (x in 0 until target.w) {
            val context = target[x - 1, y] or (target[x + 1, y - 1] shl 1) or (target[x, y - 1] shl 2) or
                (target[x - 1, y - 1] shl 3) or (ref[x - dx + 1, y - dy + 1] shl 4) or (ref[x - dx, y - dy + 1] shl 5) or
                (ref[x - dx + 1, y - dy] shl 6) or (ref[x - dx, y - dy] shl 7) or (ref[x - dx - 1, y - dy] shl 8) or
                (ref[x - dx, y - dy - 1] shl 9)
            mq.encode(cx, context, target[x, y])
        }
    }

    private fun ceilLog2(n: Int): Int { var b = 0; while ((1 shl b) < n) b++; return b }

    // ---- Huffman ----------------------------------------------------------------------------------

    /** A standard table as Annex B lists it: prefix length, range length and low end a line, then lower, upper and OOB. */
    private class Table(val lines: IntArray, val oob: Boolean) {
        val n = lines.size / 3
        val codes = IntArray(n)

        init {
            // B.3: codes assigned in order of length, and in table order within a length.
            val lens = IntArray(n) { lines[3 * it] }
            var code = 0
            for (len in 1..32) {
                code = code shl 1
                for (i in 0 until n) if (lens[i] == len) codes[i] = code++
            }
        }

        val lower = n - if (oob) 3 else 2
        val upper = lower + 1

        fun put(out: Bits, v: Int?) {
            if (v == null) {
                check(oob)
                out.put(codes[n - 1], lines[3 * (n - 1)])
                return
            }
            for (i in 0 until lower) {
                val len = lines[3 * i + 1]
                val low = lines[3 * i + 2]
                if (lines[3 * i] > 0 && v >= low && v - low < (1L shl len)) {
                    out.put(codes[i], lines[3 * i]); out.put(v - low, len)
                    return
                }
            }
            if (v >= lines[3 * upper + 2]) {
                out.put(codes[upper], lines[3 * upper]); out.put(v - lines[3 * upper + 2], 32)
            } else {
                out.put(codes[lower], lines[3 * lower]); out.put(lines[3 * lower + 2] - v, 32)
            }
        }
    }

    // The standard tables of T.88 Annex B that the plain dictionary uses.
    private val B1 = Table(intArrayOf(1, 4, 0, 2, 8, 16, 3, 16, 272, 0, 32, -1, 3, 32, 65808), false)
    private val B2 = Table(intArrayOf(1, 0, 0, 2, 0, 1, 3, 0, 2, 4, 3, 3, 5, 6, 11, 0, 32, -1, 6, 32, 75, 6, 0, 0), true)
    private val B4 = Table(intArrayOf(1, 0, 1, 2, 0, 2, 3, 0, 3, 4, 3, 4, 5, 6, 12, 0, 32, -1, 5, 32, 76), false)
    /** An MSB-first bit writer. */
    private class Bits {
        val bytes = ArrayList<Byte>()
        private var acc = 0
        private var count = 0
        fun put(v: Int, n: Int) {
            for (i in n - 1 downTo 0) {
                acc = (acc shl 1) or ((v ushr i) and 1)
                if (++count == 8) { bytes += acc.toByte(); acc = 0; count = 0 }
            }
        }
        fun align() { if (count > 0) put(0, 8 - count) }
        fun raw(b: ByteArray) { align(); for (x in b) bytes += x }
        fun toByteArray(): ByteArray { align(); return bytes.toByteArray() }
    }

    // ---- segments ---------------------------------------------------------------------------------

    private fun be(v: Long, n: Int) = ByteArray(n) { (v ushr (8 * (n - 1 - it))).toByte() }

    /** A segment header (7.2) with one-byte numbers, then [body]; [length] overrides the data length. */
    fun segment(number: Int, type: Int, refs: List<Int>, body: ByteArray, page: Int = 1, length: Long = body.size.toLong()): ByteArray =
        be(number.toLong(), 4) + byteArrayOf(type.toByte(), (refs.size shl 5).toByte()) +
            refs.map { it.toByte() }.toByteArray() + byteArrayOf(page.toByte()) + be(length, 4) + body

    fun pageInfo(width: Int, height: Int): ByteArray =
        segment(0, 48, emptyList(), be(width.toLong(), 4) + be(height.toLong(), 4) + ByteArray(8) + byteArrayOf(0, 0, 0))

    /**
     * Symbol dictionary [number] (7.4.2) of [glyphs], Huffman coded without refinement: a height class
     * for each height, its widths in increasing order, each class a collective bitmap stored
     * uncompressed (BMSIZE 0), and every glyph exported. The glyphs must come in that order.
     */
    fun plainDictionary(number: Int, glyphs: List<Glyph>): ByteArray {
        check(glyphs == glyphs.sortedWith(compareBy({ it.h }, { it.w }))) { "glyphs must come by height, then width" }
        val out = Bits()
        var height = 0
        for (h in glyphs.map { it.h }.distinct()) {
            val group = glyphs.filter { it.h == h }
            B4.put(out, h - height)
            height = h
            var width = 0
            for (g in group) { B2.put(out, g.w - width); width = g.w }
            B2.put(out, null)
            B1.put(out, 0)
            out.align()
            for (y in 0 until h) {
                val row = Bits()
                for (g in group) for (x in 0 until g.w) row.put(g[x, y], 1)
                out.raw(row.toByteArray())
            }
        }
        B1.put(out, 0); B1.put(out, glyphs.size)
        return segment(number, 0, emptyList(), be(0x0001, 2) + be(glyphs.size.toLong(), 4) + be(glyphs.size.toLong(), 4) + out.toByteArray())
    }

    /**
     * Symbol dictionary [number] that refines and aggregates (SDREFAGG = 1, refinement template 1),
     * over the [known] symbols of the dictionaries it refers to, [refs]. Arithmetic coded unless
     * [huffman]. Every new symbol is exported.
     */
    fun refinementDictionary(number: Int, refs: List<Int>, known: List<Glyph>, symbols: List<NewSymbol>, decoded: List<Glyph>, huffman: Boolean): ByteArray {
        val flags = (if (huffman) 1 else 0) or 0x0002 or 0x1000
        val header = be(flags.toLong(), 2) + (if (huffman) ByteArray(0) else byteArrayOf(3, -1, -3, -1, 2, -2, -2, -2)) +
            be(symbols.size.toLong(), 4) + be(symbols.size.toLong(), 4)
        val total = known.size + symbols.size
        val all = known + decoded
        val body = if (huffman) ByteArray(16) else arithmeticBody(symbols, all, maxOf(1, ceilLog2(total)), known.size)
        return segment(number, 0, refs, header + body)
    }

    /** Symbols ordered as the dictionary codes them: by height class, then as given. */
    private fun classes(symbols: List<NewSymbol>): List<List<NewSymbol>> {
        val heights = symbols.map { it.h }
        check(heights == heights.sorted()) { "symbols must come in order of height" }
        return heights.distinct().map { h -> symbols.filter { it.h == h } }
    }

    private fun arithmeticBody(symbols: List<NewSymbol>, all: List<Glyph>, codeLen: Int, knownCount: Int): ByteArray {
        val c = Coder()
        val iadh = c.ctx(); val iadw = c.ctx(); val iaex = c.ctx(); val iaai = c.ctx()
        val iardx = c.ctx(); val iardy = c.ctx(); val iadt = c.ctx(); val iafs = c.ctx(); val iads = c.ctx()
        val iari = c.ctx(); val iardw = c.ctx(); val iardh = c.ctx()
        val iaid = IntArray(1 shl (codeLen + 1))
        val refCx = IntArray(1 shl 16)
        var height = 0
        for (group in classes(symbols)) {
            c.int(iadh, group[0].h - height)
            height = group[0].h
            var width = 0
            for (sym in group) {
                c.int(iadw, sym.w - width)
                width = sym.w
                c.int(iaai, sym.instances.size)
                if (sym.instances.size == 1) {
                    val i = sym.instances[0]
                    c.iaid(iaid, i.id, codeLen)
                    c.int(iardx, i.rdx); c.int(iardy, i.rdy)
                    refine(c.mq, refCx, i.target ?: all[i.id], all[i.id], i.rdx, i.rdy)
                } else {
                    textRegion(c, sym.instances, all, codeLen, iadt, iafs, iads, iaid, iari, iardw, iardh, iardx, iardy, refCx, refinement = true)
                }
            }
            c.int(iadw, null)
        }
        // Export flags: none of the known symbols, then every new one.
        c.int(iaex, knownCount); c.int(iaex, symbols.size)
        return c.mq.finish()
    }

    /**
     * The 6.4.5 instance loop inverted, for one strip with the reference corner at the top left and
     * no offset: the instances in order of T and then S, each placed one pixel past the last.
     */
    private fun textRegion(
        c: Coder, instances: List<Instance>, all: List<Glyph>, codeLen: Int, iadt: IntArray, iafs: IntArray, iads: IntArray,
        iaid: IntArray, iari: IntArray, iardw: IntArray, iardh: IntArray, iardx: IntArray, iardy: IntArray, refCx: IntArray,
        refinement: Boolean,
    ) {
        c.int(iadt, 0)
        var stripT = 0
        val strips = instances.groupBy { it.t }.entries.sortedBy { it.key }.map { it.key to it.value }
        var firstS = 0
        for ((t, row) in strips) {
            c.int(iadt, t - stripT)
            stripT = t
            var curS = 0
            for ((k, i) in row.sortedBy { it.s }.withIndex()) {
                if (k == 0) { c.int(iafs, i.s - firstS); firstS = i.s } else c.int(iads, i.s - curS)
                c.iaid(iaid, i.id, codeLen)
                val glyph = i.target ?: all[i.id]
                if (refinement) {
                    c.int(iari, if (i.target != null) 1 else 0)
                    if (i.target != null) {
                        val ref = all[i.id]
                        val rdw = glyph.w - ref.w
                        val rdh = glyph.h - ref.h
                        c.int(iardw, rdw); c.int(iardh, rdh); c.int(iardx, i.rdx); c.int(iardy, i.rdy)
                        refine(c.mq, refCx, glyph, ref, (rdw shr 1) + i.rdx, (rdh shr 1) + i.rdy)
                    }
                }
                curS = i.s + glyph.w - 1
            }
            // Every strip ends with an OOB, the last one too (6.4.5).
            c.int(iads, null)
        }
    }

    /**
     * An immediate text region (7.4.4) of [width] by [height] at the page origin, arithmetic coded,
     * without refinement, placing [instances] of the [symbols] of the dictionaries [refs].
     */
    fun textRegionSegment(number: Int, refs: List<Int>, width: Int, height: Int, symbols: List<Glyph>, instances: List<Instance>): ByteArray {
        val c = Coder()
        val codeLen = maxOf(1, ceilLog2(symbols.size))
        textRegion(c, instances, symbols, codeLen, c.ctx(), c.ctx(), c.ctx(), IntArray(1 shl (codeLen + 1)), c.ctx(),
            c.ctx(), c.ctx(), c.ctx(), c.ctx(), IntArray(1 shl 16), refinement = false)
        val body = be(width.toLong(), 4) + be(height.toLong(), 4) + ByteArray(8) + byteArrayOf(0) +
            be(0x0010, 2) + be(instances.size.toLong(), 4) + c.mq.finish()
        return segment(number, 6, refs, body)
    }

    /** [instances] drawn with OR on a [width] by [height] page, as packed rows with a set bit white, as [Jbig2Decoder] gives a page. */
    fun page(width: Int, height: Int, symbols: List<Glyph>, instances: List<Instance>): ByteArray {
        val px = IntArray(width * height)
        for (i in instances) {
            val g = i.target ?: symbols[i.id]
            for (y in 0 until g.h) for (x in 0 until g.w) {
                if (g[x, y] == 1 && i.s + x in 0 until width && i.t + y in 0 until height) px[(i.t + y) * width + i.s + x] = 1
            }
        }
        val stride = (width + 7) / 8
        val out = ByteArray(stride * height)
        for (y in 0 until height) for (x in 0 until width) {
            if (px[y * width + x] == 0) out[y * stride + x / 8] = (out[y * stride + x / 8].toInt() or (0x80 ushr (x % 8))).toByte()
        }
        return out
    }

    /** The glyph a new symbol's instances make, drawn with OR at their places. */
    fun render(sym: NewSymbol, all: List<Glyph>): Glyph {
        val bits = IntArray(sym.w * sym.h)
        for (i in sym.instances) {
            val g = i.target ?: all[i.id]
            val s0 = if (sym.instances.size == 1) 0 else i.s
            val t0 = if (sym.instances.size == 1) 0 else i.t
            for (y in 0 until g.h) for (x in 0 until g.w) {
                if (g[x, y] == 1 && s0 + x in 0 until sym.w && t0 + y in 0 until sym.h) bits[(t0 + y) * sym.w + s0 + x] = 1
            }
        }
        return Glyph(sym.w, sym.h, bits)
    }
}
