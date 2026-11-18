package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.internal.flate.ByteArrayBuilder

/**
 * One component of a DCT frame that [JpegWriter] writes: its identifier, sampling factors and
 * quantization table, and its quantized coefficients, 64 a block in zigzag order, on a grid of
 * [blocksWide] by [blocksHigh] blocks that covers whole MCUs (T.81 A.2). The blocks an
 * interleaved scan codes past the image's edge hold what the encoder put there.
 */
internal class JpegComponentPlan(val id: Int, val h: Int, val v: Int, val tq: Int, val blocksWide: Int, val blocksHigh: Int) {
    val coefficients = ShortArray(blocksWide * blocksHigh * 64)

    /** The blocks a scan of this component alone covers (T.81 A.2.2), set by the frame. */
    var scanWide = 0
    var scanHigh = 0
}

/** A DCT frame: its precision, size, components and quantization tables (zigzag order, indexed by Tq). */
internal class JpegFramePlan(val precision: Int, val width: Int, val height: Int, val components: List<JpegComponentPlan>, val quantTables: List<IntArray>) {
    val hMax = components.maxOf { it.h }
    val vMax = components.maxOf { it.v }
    val mcusWide = (width + 8 * hMax - 1) / (8 * hMax)
    val mcusHigh = (height + 8 * vMax - 1) / (8 * vMax)

    init {
        for (c in components) {
            c.scanWide = ((width.toLong() * c.h + hMax - 1) / hMax + 7).toInt() / 8
            c.scanHigh = ((height.toLong() * c.v + vMax - 1) / vMax + 7).toInt() / 8
        }
    }
}

/** What precedes the frame: a JFIF APP0, an Adobe APP14 with [adobeTransform] (-1 for none), then [segments]. */
internal class JpegHeaderPlan(val jfif: Boolean, val adobeTransform: Int, val segments: ByteArray?) {
    /** Where [segments] start in the file. */
    val segmentsAt: Int get() = 2 + (if (jfif) 18 else 0) + (if (adobeTransform >= 0) 16 else 0)
}

/**
 * How the entropy coding goes: Huffman with the example tables of T.81 Annex K or with tables
 * built for the image, or arithmetic with the conditioning of a DAC segment; sequential or by
 * [progressive]'s scan script; with a restart marker every [restartInterval] MCUs.
 */
internal class JpegCodingPlan(
    val arithmetic: Boolean,
    val progressive: Boolean,
    val optimize: Boolean,
    val restartInterval: Int,
    val dcLower: Int = 0,
    val dcUpper: Int = 1,
    val acThreshold: Int = 5,
)

/**
 * Writes the JPEG files of #183 from quantized coefficients or lossless samples, written from
 * ITU-T T.81: the markers of Annex B, Huffman coding of F.1.2 and G.1.2, the QM coder of Annex D
 * with the models of F.1.4, G.1.3 and H.1.2.3, and the lossless prediction of Annex H.
 *
 * The statistics bins of the arithmetic models sit where [JpegDecoder] reads them: a DC table's
 * 64 bins as F.1.4.4.1 numbers them from S0 of each of the five contexts, X1 at 20 and M2 at 34,
 * an AC table's 3 bins a coefficient and the X1 of F.1.4.4.2 at 189 or 217, and a lossless
 * table's 5 by 5 contexts and X1 at 100 or 129.
 */
internal object JpegWriter {

    // ---- markers (T.81 Annex B) ----

    private fun ByteArrayBuilder.byte(v: Int) = append(v.toByte())

    private fun ByteArrayBuilder.word(v: Int) {
        byte(v ushr 8)
        byte(v)
    }

    private fun ByteArrayBuilder.segment(marker: Int, body: ByteArrayBuilder) {
        byte(0xFF)
        byte(marker)
        word(body.size + 2)
        append(body.toByteArray())
    }

    private fun header(out: ByteArrayBuilder, header: JpegHeaderPlan) {
        out.byte(0xFF); out.byte(0xD8)
        if (header.jfif) {
            // JFIF 1.01, no units, an aspect ratio of 1:1 and no thumbnail, as stb_image_write writes it.
            out.append(byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 0x10, 0x4A, 0x46, 0x49, 0x46, 0, 1, 1, 0, 0, 1, 0, 1, 0, 0))
        }
        if (header.adobeTransform >= 0) {
            // Adobe's APP14: version 100, no flags, and the colour transform, which libjpeg reads to know RGB from YCbCr.
            out.append(byteArrayOf(0xFF.toByte(), 0xEE.toByte(), 0, 0x0E, 0x41, 0x64, 0x6F, 0x62, 0x65, 0, 0x64, 0, 0, 0, 0, header.adobeTransform.toByte()))
        }
        header.segments?.let { out.append(it) }
    }

    private fun quantTables(out: ByteArrayBuilder, tables: List<IntArray>) {
        val body = ByteArrayBuilder(140)
        for ((t, table) in tables.withIndex()) {
            val wide = table.any { it > 255 }
            body.byte(((if (wide) 1 else 0) shl 4) or t)
            for (q in table) if (wide) body.word(q) else body.byte(q)
        }
        out.segment(0xDB, body)
    }

    private fun frameHeader(out: ByteArrayBuilder, marker: Int, precision: Int, width: Int, height: Int, ids: IntArray, sampling: IntArray, tq: IntArray) {
        val body = ByteArrayBuilder(20)
        body.byte(precision)
        body.word(height)
        body.word(width)
        body.byte(ids.size)
        for (i in ids.indices) {
            body.byte(ids[i])
            body.byte(sampling[i])
            body.byte(tq[i])
        }
        out.segment(marker, body)
    }

    private fun restartDefinition(out: ByteArrayBuilder, interval: Int) {
        if (interval == 0) return
        val body = ByteArrayBuilder(2)
        body.word(interval)
        out.segment(0xDD, body)
    }

    /** DAC (T.81 B.2.4.3): [dcTables] with the bounds L and U, [acTables] with Kx. */
    private fun conditioning(out: ByteArrayBuilder, coding: JpegCodingPlan, dcTables: IntArray, acTables: IntArray) {
        val body = ByteArrayBuilder(16)
        for (t in dcTables) { body.byte(t); body.byte((coding.dcUpper shl 4) or coding.dcLower) }
        for (t in acTables) { body.byte(0x10 or t); body.byte(coding.acThreshold) }
        out.segment(0xCC, body)
    }

    private fun scanHeader(out: ByteArrayBuilder, ids: IntArray, tables: IntArray, ss: Int, se: Int, ah: Int, al: Int) {
        val body = ByteArrayBuilder(12)
        body.byte(ids.size)
        for (i in ids.indices) { body.byte(ids[i]); body.byte(tables[i]) }
        body.byte(ss)
        body.byte(se)
        body.byte((ah shl 4) or al)
        out.segment(0xDA, body)
    }

    // ---- Huffman coding (T.81 F.1.2, G.1.2) ----

    /**
     * A Huffman coder that either counts the symbols of each table, for tables built by K.2, or
     * writes them with the tables set. Bits pass through in the counting pass unwritten, so the
     * two passes walk the same code.
     */
    private class Huffman(val out: ByteArrayBuilder, tableCount: Int) {
        val counts = Array(tableCount) { IntArray(257) }
        var codes: Array<IntArray>? = null
        var lengths: Array<IntArray>? = null
        private var buffer = 0L
        private var bits = 0

        val writing: Boolean get() = codes != null

        fun symbol(table: Int, value: Int) {
            val c = codes
            if (c == null) { counts[table][value]++; return }
            val n = lengths!![table][value]
            check(n > 0) { "no code for symbol $value in table $table" }
            put(c[table][value], n)
        }

        fun put(value: Int, n: Int) {
            if (codes == null || n == 0) return
            buffer = (buffer shl n) or (value.toLong() and ((1L shl n) - 1))
            bits += n
            while (bits >= 8) {
                bits -= 8
                val b = ((buffer ushr bits) and 0xFF).toInt()
                out.append(b.toByte())
                if (b == 0xFF) out.append(0)
            }
            buffer = buffer and ((1L shl bits) - 1)
        }

        /** Pads the last byte with 1 bits (T.81 F.1.2.3). */
        fun flush() {
            if (bits > 0) put((1 shl (8 - bits)) - 1, 8 - bits)
        }

        fun setTables(counts: List<IntArray>, values: List<IntArray>) {
            codes = Array(counts.size) { IntArray(256) }
            lengths = Array(counts.size) { IntArray(256) }
            for (t in counts.indices) {
                var code = 0
                var k = 0
                for (length in 1..16) {
                    repeat(counts[t][length]) {
                        codes!![t][values[t][k]] = code
                        lengths!![t][values[t][k]] = length
                        code++
                        k++
                    }
                    code = code shl 1
                }
            }
        }
    }

    /** The magnitude category of [v], T.81 Table F.1. */
    private fun category(v: Int): Int {
        var a = if (v < 0) -v else v
        var n = 0
        while (a != 0) { a = a shr 1; n++ }
        return n
    }

    /** [v] as its category's symbol in [table], then its additional bits (F.1.2.1.1). */
    private fun Huffman.magnitude(table: Int, v: Int, run: Int = 0) {
        val n = category(v)
        symbol(table, (run shl 4) or n)
        put(if (v < 0) v - 1 else v, n)
    }

    /** The K.2 tables of [huffman]'s counts for [tables], as one DHT segment of classes [classes] and ids [ids]; [huffman] then writes. */
    private fun builtTables(out: ByteArrayBuilder, huffman: Huffman, classes: IntArray, ids: IntArray) {
        val body = ByteArrayBuilder(512)
        val counts = ArrayList<IntArray>()
        val values = ArrayList<IntArray>()
        for (t in classes.indices) {
            if (huffman.counts[t].none { it > 0 }) {
                counts += IntArray(17)
                values += IntArray(0)
                continue
            }
            val (bits, symbols) = JpegRepack.optimalTable(huffman.counts[t])
            body.byte((classes[t] shl 4) or ids[t])
            for (n in 1..16) body.byte(bits[n])
            for (s in symbols) body.byte(s)
            counts += bits
            values += symbols
        }
        if (body.size > 0) out.segment(0xC4, body)
        huffman.setTables(counts, values)
    }

    /** The example tables of K.3 as one DHT segment: DC then AC of table 0, then of table 1 when [chroma]. */
    private fun exampleTables(out: ByteArrayBuilder, huffman: Huffman, chroma: Boolean) {
        val body = ByteArrayBuilder(420)
        fun add(id: Int, counts: IntArray, values: IntArray) {
            body.byte(id)
            for (i in 1..16) body.byte(counts[i])
            for (v in values) body.byte(v)
        }
        add(0x00, JpegEncoder.STD_DC_LUM_NRCODES, JpegEncoder.STD_DC_LUM_VALUES)
        add(0x10, JpegEncoder.STD_AC_LUM_NRCODES, JpegEncoder.STD_AC_LUM_VALUES)
        if (chroma) {
            add(0x01, JpegEncoder.STD_DC_CHR_NRCODES, JpegEncoder.STD_DC_CHR_VALUES)
            add(0x11, JpegEncoder.STD_AC_CHR_NRCODES, JpegEncoder.STD_AC_CHR_VALUES)
        }
        out.segment(0xC4, body)
        // Table order of the coder: DC 0, DC 1, AC 0, AC 1.
        huffman.setTables(
            listOf(JpegEncoder.STD_DC_LUM_NRCODES, JpegEncoder.STD_DC_CHR_NRCODES, JpegEncoder.STD_AC_LUM_NRCODES, JpegEncoder.STD_AC_CHR_NRCODES),
            listOf(JpegEncoder.STD_DC_LUM_VALUES, JpegEncoder.STD_DC_CHR_VALUES, JpegEncoder.STD_AC_LUM_VALUES, JpegEncoder.STD_AC_CHR_VALUES),
        )
    }

    // ---- arithmetic coding (T.81 Annex D) ----

    /**
     * The QM encoder of T.81 D.1. The code register [c] holds, from bit 0 up, the 16 bits aligned
     * with the interval [a], 3 spacer bits, the 8 bits of the next byte and a carry, as Figure D.1
     * lays them out. The bytes of one restart interval are kept unstuffed, so that a carry adds
     * into them as into a number (D.1.6); [finish] flushes, drops the trailing zero bytes a
     * decoder supplies by itself, and stuffs a zero after each 0xFF (D.1.6, F.1.2.3).
     */
    private class QmEncoder(val out: ByteArrayBuilder) {
        private var c = 0
        private var a = 0x10000
        private var ct = 11
        private var raw = ByteArray(4096)
        private var size = 0

        fun code(stats: ByteArray, at: Int, bit: Int) {
            val sv = stats[at].toInt() and 0xFF
            val entry = JpegDecoder.ARITAB[sv and 0x7F]
            val qe = entry ushr 16
            a -= qe
            if (bit != sv shr 7) {
                // The LPS takes the upper sub-interval, unless it is the larger one (D.1.3, the conditional exchange).
                if (a >= qe) {
                    c += a
                    a = qe
                }
                stats[at] = ((sv and 0x80) xor (entry and 0xFF)).toByte()
            } else {
                if (a >= 0x8000) return
                if (a < qe) {
                    c += a
                    a = qe
                }
                stats[at] = ((sv and 0x80) xor ((entry ushr 8) and 0xFF)).toByte()
            }
            do {
                a = a shl 1
                c = c shl 1
                if (--ct == 0) {
                    byteOut()
                    ct = 8
                }
            } while (a < 0x8000)
        }

        private fun byteOut() {
            val t = c ushr 19
            c = c and 0x7FFFF
            if (t > 0xFF) {
                var i = size - 1
                while (raw[i].toInt() == -1) raw[i--] = 0
                raw[i] = (raw[i] + 1).toByte()
            }
            if (size == raw.size) raw = raw.copyOf(raw.size * 2)
            raw[size++] = t.toByte()
        }

        /** FLUSH of D.1.8: the value in the interval with the most trailing zero bits, then its bytes. */
        fun finish() {
            var t = (c + a - 1) and 0xFFFF0000.toInt()
            if (t < c) t += 0x8000
            c = t shl ct
            byteOut()
            c = c shl 8
            byteOut()
            var n = size
            while (n > 0 && raw[n - 1].toInt() == 0) n--
            for (i in 0 until n) {
                out.append(raw[i])
                if (raw[i].toInt() == -1) out.append(0)
            }
            c = 0
            a = 0x10000
            ct = 11
            size = 0
        }
    }

    /** The statistics bins of one table: DC, AC and lossless, as [JpegDecoder] lays them out. */
    private class ArithmeticTables {
        val dc = Array(4) { ByteArray(64) }
        val ac = Array(4) { ByteArray(256) }
        val lossless = Array(4) { ByteArray(158) }
        val fixed = byteArrayOf(JpegDecoder.FIXED_BIN.toByte())
    }

    /** The highest power of two in [v] > 0. */
    private fun topBit(v: Int): Int = Int.MIN_VALUE.ushr(v.countLeadingZeroBits())

    /**
     * The magnitude [mag] (|value| - 1) after its first decision: the Mx decisions of F.1.4.3.1
     * from bin [x1] while the bound is below its top bit, then the bits under that bit at the
     * bin 14 past where the decisions stopped. Returns the top bit, or 0 for a zero [mag].
     */
    private fun QmEncoder.magnitudeRest(stats: ByteArray, mag: Int, x1: Int, startBound: Int): Int {
        val top = topBit(mag)
        var st = x1
        var bound = startBound
        while (bound < top) {
            code(stats, st, 1)
            bound = bound shl 1
            st++
        }
        code(stats, st, 0)
        st += 14
        var bit = top shr 1
        while (bit != 0) {
            code(stats, st, if (mag and bit != 0) 1 else 0)
            bit = bit shr 1
        }
        return top
    }

    /** A DC difference by F.1.4.1 in [stats], from the conditioning context [context]; the next context. */
    private fun QmEncoder.dc(stats: ByteArray, context: Int, v: Int, coding: JpegCodingPlan): Int {
        if (v == 0) {
            code(stats, context, 0)
            return 0
        }
        code(stats, context, 1)
        val sign = if (v < 0) 1 else 0
        code(stats, context + 1, sign)
        val st = context + 2 + sign
        val mag = (if (v < 0) -v else v) - 1
        val m: Int
        if (mag == 0) {
            code(stats, st, 0)
            m = 0
        } else {
            code(stats, st, 1)
            m = magnitudeRest(stats, mag, 20, 1)
        }
        // The conditioning category of the next difference, F.1.4.4.1.2.
        return when {
            m < (1 shl coding.dcLower) shr 1 -> 0
            m > (1 shl coding.dcUpper) shr 1 -> 12 + sign * 4
            else -> 4 + sign * 4
        }
    }

    /** Coefficients [start] to [end] of the block at [at] of [coef], each divided by 2^[al], by F.1.4.2. */
    private fun QmEncoder.ac(tables: ArithmeticTables, stats: ByteArray, coef: ShortArray, at: Int, start: Int, end: Int, al: Int, kx: Int) {
        var last = end
        while (last >= start && magnitude(coef[at + last].toInt(), al) == 0) last--
        var k = start
        while (k <= last) {
            var st = 3 * (k - 1)
            code(stats, st, 0)
            while (magnitude(coef[at + k].toInt(), al) == 0) {
                code(stats, st + 1, 0)
                st += 3
                k++
            }
            code(stats, st + 1, 1)
            val value = coef[at + k].toInt()
            code(tables.fixed, 0, if (value < 0) 1 else 0)
            st += 2
            val mag = magnitude(value, al) - 1
            if (mag == 0) {
                code(stats, st, 0)
            } else {
                code(stats, st, 1)
                if (mag == 1) {
                    code(stats, st, 0)
                    st += 14
                } else {
                    code(stats, st, 1)
                    magnitudeRest(stats, mag, if (k <= kx) 189 else 217, 2)
                }
            }
            k++
        }
        if (k <= end) code(stats, 3 * (k - 1), 1)
    }

    /** The successive approximation of the band [ss] to [se] of the block at [at] by G.1.3.3. */
    private fun QmEncoder.acRefine(tables: ArithmeticTables, stats: ByteArray, coef: ShortArray, at: Int, ss: Int, se: Int, al: Int) {
        // The end of block of the scans before: past it, a coefficient can only be new.
        var before = se
        while (before > 0 && magnitude(coef[at + before].toInt(), al + 1) == 0) before--
        var last = se
        while (last >= ss && magnitude(coef[at + last].toInt(), al) == 0) last--
        var k = ss
        while (k <= last) {
            var st = 3 * (k - 1)
            if (k > before) code(stats, st, 0)
            while (true) {
                val value = coef[at + k].toInt()
                val t = magnitude(value, al)
                if (t > 1) {
                    code(stats, st + 2, t and 1)
                    break
                }
                if (t == 1) {
                    code(stats, st + 1, 1)
                    code(tables.fixed, 0, if (value < 0) 1 else 0)
                    break
                }
                code(stats, st + 1, 0)
                st += 3
                k++
            }
            k++
        }
        if (k <= se) code(stats, 3 * (k - 1), 1)
    }

    /** |[v]| divided by 2^[al], the point transform of an AC coefficient (G.1.2.2). */
    private fun magnitude(v: Int, al: Int): Int = (if (v < 0) -v else v) shr al

    // ---- scans ----

    /** One scan of the script: the frame's components [comps], the band [ss] to [se], and the bit positions [ah] and [al]. */
    private class Scan(val comps: IntArray, val ss: Int, val se: Int, val ah: Int, val al: Int)

    /**
     * The scan script libjpeg's `jpeg_simple_progression` gives YCbCr and gray: the DC at half
     * precision, the low luma band and the chroma at half precision, the rest of the luma, the
     * luma's next bit, then the last bit of each.
     */
    private fun progression(components: Int): List<Scan> {
        val all = IntArray(components) { it }
        if (components == 1) {
            return listOf(
                Scan(all, 0, 0, 0, 1), Scan(all, 1, 5, 0, 2), Scan(all, 6, 63, 0, 2),
                Scan(all, 1, 63, 2, 1), Scan(all, 0, 0, 1, 0), Scan(all, 1, 63, 1, 0),
            )
        }
        val y = intArrayOf(0)
        val cb = intArrayOf(1)
        val cr = intArrayOf(2)
        return listOf(
            Scan(all, 0, 0, 0, 1), Scan(y, 1, 5, 0, 2), Scan(cr, 1, 63, 0, 1), Scan(cb, 1, 63, 0, 1),
            Scan(y, 6, 63, 0, 2), Scan(y, 1, 63, 2, 1), Scan(all, 0, 0, 1, 0),
            Scan(cr, 1, 63, 1, 0), Scan(cb, 1, 63, 1, 0), Scan(y, 1, 63, 1, 0),
        )
    }

    /** The table a component uses: the first component its own, the others the second, as libjpeg assigns them. */
    private fun slot(index: Int): Int = if (index == 0) 0 else 1

    /**
     * Calls [block] for each block of [comps] in the order a scan codes them (T.81 A.2), and
     * [restart] before each MCU that starts a new restart interval. [row] runs before each row.
     */
    private inline fun forEachBlock(
        frame: JpegFramePlan, comps: List<JpegComponentPlan>, interval: Int, row: () -> Unit, restart: () -> Unit,
        block: (index: Int, c: JpegComponentPlan, at: Int) -> Unit,
    ) {
        var units = 0
        if (comps.size == 1) {
            val c = comps[0]
            for (by in 0 until c.scanHigh) {
                row()
                for (bx in 0 until c.scanWide) {
                    if (interval > 0 && units > 0 && units % interval == 0) restart()
                    units++
                    block(0, c, (by * c.blocksWide + bx) * 64)
                }
            }
        } else {
            for (my in 0 until frame.mcusHigh) {
                row()
                for (mx in 0 until frame.mcusWide) {
                    if (interval > 0 && units > 0 && units % interval == 0) restart()
                    units++
                    for ((i, c) in comps.withIndex()) for (y in 0 until c.v) for (x in 0 until c.h) {
                        block(i, c, ((my * c.v + y) * c.blocksWide + mx * c.h + x) * 64)
                    }
                }
            }
        }
    }

    private fun restartMarker(out: ByteArrayBuilder, n: Int) {
        out.byte(0xFF)
        out.byte(0xD0 + (n and 7))
    }

    /**
     * The file of [frame]: [header], the quantization tables, the frame header and the scans
     * [coding] asks for. [cancel] runs before each row of MCUs of each scan.
     */
    fun writeDct(frame: JpegFramePlan, header: JpegHeaderPlan, coding: JpegCodingPlan, cancel: (() -> Unit)?): ByteArray {
        val comps = frame.components
        val out = ByteArrayBuilder(frame.width * frame.height / 2 + 1024)
        header(out, header)
        quantTables(out, frame.quantTables)
        val marker = when {
            coding.arithmetic -> if (coding.progressive) 0xCA else 0xC9
            coding.progressive -> 0xC2
            frame.precision == 8 -> 0xC0
            else -> 0xC1
        }
        frameHeader(
            out, marker, frame.precision, frame.width, frame.height,
            IntArray(comps.size) { comps[it].id }, IntArray(comps.size) { (comps[it].h shl 4) or comps[it].v }, IntArray(comps.size) { comps[it].tq },
        )
        val slots = if (comps.size > 1) intArrayOf(0, 1) else intArrayOf(0)
        if (coding.arithmetic) conditioning(out, coding, slots, slots)
        restartDefinition(out, coding.restartInterval)
        val scans = if (coding.progressive) progression(comps.size) else listOf(Scan(IntArray(comps.size) { it }, 0, 63, 0, 0))
        for (scan in scans) {
            when {
                coding.arithmetic -> arithmeticScan(out, frame, scan, coding, cancel)
                coding.progressive || coding.optimize -> huffmanBuiltScan(out, frame, scan, coding, cancel)
                else -> {
                    val h = Huffman(out, 4)
                    exampleTables(out, h, comps.size > 1)
                    scanHeader(out, IntArray(comps.size) { comps[it].id }, IntArray(comps.size) { slot(it) * 0x11 }, 0, 63, 0, 0)
                    huffmanScan(h, out, frame, scan, coding, cancel)
                }
            }
        }
        out.byte(0xFF); out.byte(0xD9)
        return out.toByteArray()
    }

    /**
     * A Huffman scan with tables built for it: a counting pass, the DHT segment and the scan
     * header, then the writing pass. A DC refinement sends raw bits and needs no table.
     */
    private fun huffmanBuiltScan(out: ByteArrayBuilder, frame: JpegFramePlan, scan: Scan, coding: JpegCodingPlan, cancel: (() -> Unit)?) {
        val h = Huffman(out, 4)
        huffmanScan(h, out, frame, scan, coding, cancel)
        builtTables(out, h, intArrayOf(0, 0, 1, 1), intArrayOf(0, 1, 0, 1))
        val tables = IntArray(scan.comps.size) {
            val s = slot(scan.comps[it])
            when {
                !coding.progressive -> s * 0x11
                scan.ss == 0 && scan.ah == 0 -> s shl 4
                scan.ss == 0 -> 0
                else -> s
            }
        }
        scanHeader(out, IntArray(scan.comps.size) { frame.components[scan.comps[it]].id }, tables, scan.ss, scan.se, scan.ah, scan.al)
        huffmanScan(h, out, frame, scan, coding, cancel)
    }

    /**
     * The Huffman code of [scan] through [h]: tables 0 and 1 are the DC tables of the two slots,
     * 2 and 3 the AC ones. A sequential scan codes F.1.2's blocks, a progressive one G.1.2's
     * first scans and refinements, with end-of-band runs held across blocks.
     */
    private fun huffmanScan(h: Huffman, out: ByteArrayBuilder, frame: JpegFramePlan, scan: Scan, coding: JpegCodingPlan, cancel: (() -> Unit)?) {
        val comps = scan.comps.map { frame.components[it] }
        val predictions = IntArray(comps.size)
        val sequential = !coding.progressive
        var eobRun = 0
        var corrections = ByteArray(64)           // the correction bits held for the end-of-band run
        var held0 = 0
        val pending = IntArray(64)                // the correction bits since the last symbol of a block
        var restarts = 0

        fun flushRun(table: Int) {
            if (eobRun == 0) return
            val n = category(eobRun) - 1
            h.symbol(table, n shl 4)
            h.put(eobRun, n)
            eobRun = 0
            for (b in 0 until held0) h.put(corrections[b].toInt(), 1)
            held0 = 0
        }

        val acTable = 2 + slot(scan.comps[0])
        forEachBlock(frame, comps, coding.restartInterval, { cancel?.invoke() }, {
            if (!sequential && scan.ss > 0) flushRun(acTable)
            h.flush()
            if (h.writing) restartMarker(out, restarts)
            restarts++
            predictions.fill(0)
        }) { i, c, at ->
            val coef = c.coefficients
            val s = slot(scan.comps[i])
            when {
                sequential -> {
                    val dc = coef[at].toInt()
                    h.magnitude(s, dc - predictions[i])
                    predictions[i] = dc
                    var run = 0
                    for (k in 1..63) {
                        val v = coef[at + k].toInt()
                        if (v == 0) { run++; continue }
                        while (run > 15) { h.symbol(2 + s, 0xF0); run -= 16 }
                        h.magnitude(2 + s, v, run)
                        run = 0
                    }
                    if (run > 0) h.symbol(2 + s, 0)
                }
                scan.ss == 0 && scan.ah == 0 -> {
                    val dc = coef[at].toInt() shr scan.al
                    h.magnitude(s, dc - predictions[i])
                    predictions[i] = dc
                }
                scan.ss == 0 -> h.put((coef[at].toInt() shr scan.al) and 1, 1)
                scan.ah == 0 -> {
                    var run = 0
                    for (k in scan.ss..scan.se) {
                        val v = coef[at + k].toInt()
                        val t = magnitude(v, scan.al)
                        if (t == 0) { run++; continue }
                        flushRun(acTable)
                        while (run > 15) { h.symbol(acTable, 0xF0); run -= 16 }
                        val n = category(t)
                        h.symbol(acTable, (run shl 4) or n)
                        h.put(if (v < 0) t.inv() else t, n)
                        run = 0
                    }
                    if (run > 0) {
                        eobRun++
                        if (eobRun == 0x7FFF) flushRun(acTable)
                    }
                }
                else -> {
                    // G.1.2.3: a coefficient that was zero before is coded as a run and a sign, one
                    // that was not gets a correction bit, sent after the symbol that passes it.
                    var last = scan.se
                    while (last >= scan.ss && magnitude(coef[at + last].toInt(), scan.al) != 1) last--
                    var run = 0
                    var held = 0
                    for (k in scan.ss..scan.se) {
                        val v = coef[at + k].toInt()
                        val t = magnitude(v, scan.al)
                        if (t == 0) { run++; continue }
                        while (run > 15 && k <= last) {
                            flushRun(acTable)
                            h.symbol(acTable, 0xF0)
                            run -= 16
                            for (b in 0 until held) h.put(pending[b], 1)
                            held = 0
                        }
                        if (t > 1) {
                            pending[held++] = t and 1
                            continue
                        }
                        flushRun(acTable)
                        h.symbol(acTable, (run shl 4) or 1)
                        h.put(if (v < 0) 0 else 1, 1)
                        for (b in 0 until held) h.put(pending[b], 1)
                        held = 0
                        run = 0
                    }
                    if (run > 0 || held > 0) {
                        eobRun++
                        if (held0 + held > corrections.size) corrections = corrections.copyOf(maxOf(corrections.size * 2, held0 + held))
                        for (b in 0 until held) corrections[held0++] = pending[b].toByte()
                        if (eobRun == 0x7FFF) flushRun(acTable)
                    }
                }
            }
        }
        if (!sequential && scan.ss > 0) flushRun(acTable)
        h.flush()
    }

    /** An arithmetic-coded scan by F.1.4 or G.1.3, with fresh statistics at its start and at each restart (F.1.4.4, G.1.3.2). */
    private fun arithmeticScan(out: ByteArrayBuilder, frame: JpegFramePlan, scan: Scan, coding: JpegCodingPlan, cancel: (() -> Unit)?) {
        val comps = scan.comps.map { frame.components[it] }
        val tables = IntArray(comps.size) {
            val s = slot(scan.comps[it])
            when {
                !coding.progressive -> s * 0x11
                scan.ss == 0 -> s shl 4
                else -> s
            }
        }
        scanHeader(out, IntArray(comps.size) { comps[it].id }, tables, scan.ss, scan.se, scan.ah, scan.al)
        val qm = QmEncoder(out)
        val stats = ArithmeticTables()
        val predictions = IntArray(comps.size)
        val contexts = IntArray(comps.size)
        val sequential = !coding.progressive
        fun reset() {
            for (t in stats.dc) t.fill(0)
            for (t in stats.ac) t.fill(0)
            predictions.fill(0)
            contexts.fill(0)
        }
        var restarts = 0
        forEachBlock(frame, comps, coding.restartInterval, { cancel?.invoke() }, {
            qm.finish()
            restartMarker(out, restarts++)
            reset()
        }) { i, c, at ->
            val coef = c.coefficients
            val s = slot(scan.comps[i])
            when {
                sequential -> {
                    val dc = coef[at].toInt()
                    contexts[i] = qm.dc(stats.dc[s], contexts[i], dc - predictions[i], coding)
                    predictions[i] = dc
                    qm.ac(stats, stats.ac[s], coef, at, 1, 63, 0, coding.acThreshold)
                }
                scan.ss == 0 && scan.ah == 0 -> {
                    val dc = coef[at].toInt() shr scan.al
                    contexts[i] = qm.dc(stats.dc[s], contexts[i], dc - predictions[i], coding)
                    predictions[i] = dc
                }
                scan.ss == 0 -> qm.code(stats.fixed, 0, (coef[at].toInt() shr scan.al) and 1)
                scan.ah == 0 -> qm.ac(stats, stats.ac[s], coef, at, scan.ss, scan.se, scan.al, coding.acThreshold)
                else -> qm.acRefine(stats, stats.ac[s], coef, at, scan.ss, scan.se, scan.al)
            }
        }
        qm.finish()
    }

    // ---- lossless (T.81 Annex H) ----

    /**
     * A lossless file of [planes], one a component of [width] by [height] samples each already
     * through the point transform [pointTransform], with ids [ids], at [precision] bits, by
     * [predictor]. Huffman files get tables built for them, one a component; arithmetic ones a
     * conditioning table each. [interval] counts MCUs and holds whole rows.
     */
    fun writeLossless(
        planes: List<IntArray>, ids: IntArray, width: Int, height: Int, precision: Int, predictor: Int, pointTransform: Int,
        header: JpegHeaderPlan, coding: JpegCodingPlan, cancel: (() -> Unit)?,
    ): ByteArray {
        val n = planes.size
        val out = ByteArrayBuilder(width * height * n * (if (precision > 8) 2 else 1) / 2 + 1024)
        header(out, header)
        frameHeader(out, if (coding.arithmetic) 0xCB else 0xC3, precision, width, height, ids, IntArray(n) { 0x11 }, IntArray(n))
        val tableIds = IntArray(n) { it }
        if (coding.arithmetic) conditioning(out, coding, tableIds, IntArray(0))
        restartDefinition(out, coding.restartInterval)
        val initial = 1 shl (precision - pointTransform - 1)
        // The prediction of H.1.2.1 and the difference modulo 2^16 of H.1.2.2, in -32768..32767.
        fun differences(row: Int, firstRow: Int, action: (component: Int, x: Int, diff: Int) -> Unit) {
            for (x in 0 until width) for (k in 0 until n) {
                val s = planes[k]
                val i = row * width + x
                val prediction = when {
                    row == firstRow -> if (x == 0) initial else s[i - 1]
                    x == 0 -> s[i - width]
                    else -> {
                        val ra = s[i - 1]
                        val rb = s[i - width]
                        val rc = s[i - width - 1]
                        when (predictor) {
                            1 -> ra
                            2 -> rb
                            3 -> rc
                            4 -> ra + rb - rc
                            5 -> ra + ((rb - rc) shr 1)
                            6 -> rb + ((ra - rc) shr 1)
                            else -> (ra + rb) shr 1
                        }
                    }
                }
                val d = (s[i] - prediction) and 0xFFFF
                action(k, x, if (d >= 32768) d - 65536 else d)
            }
        }
        val rowsPerInterval = if (coding.restartInterval == 0) Int.MAX_VALUE else coding.restartInterval / width

        if (!coding.arithmetic) {
            val h = Huffman(out, n)
            fun pass() {
                var restarts = 0
                var firstRow = 0
                for (row in 0 until height) {
                    cancel?.invoke()
                    if (row > 0 && row % rowsPerInterval == 0) {
                        h.flush()
                        if (h.writing) restartMarker(out, restarts)
                        restarts++
                        firstRow = row
                    }
                    differences(row, firstRow) { k, _, d ->
                        if (d == -32768) h.symbol(k, 16) else h.magnitude(k, d)
                    }
                }
                h.flush()
            }
            pass()
            builtTables(out, h, IntArray(n), tableIds)
            scanHeader(out, ids, IntArray(n) { it shl 4 }, predictor, 0, 0, pointTransform)
            pass()
        } else {
            scanHeader(out, ids, IntArray(n) { it shl 4 }, predictor, 0, 0, pointTransform)
            val qm = QmEncoder(out)
            val stats = ArithmeticTables()
            // The category of each component's difference to the left, and of the row above (H.1.2.3, Figure H.2).
            val above = Array(n) { ByteArray(width) }
            val left = IntArray(n)
            var restarts = 0
            var firstRow = 0
            for (row in 0 until height) {
                cancel?.invoke()
                if (row > 0 && row % rowsPerInterval == 0) {
                    qm.finish()
                    restartMarker(out, restarts++)
                    for (t in stats.lossless) t.fill(0)
                    firstRow = row
                }
                differences(row, firstRow) { k, x, d ->
                    val stat = stats.lossless[k]
                    val a = if (x == 0) 0 else left[k]
                    val b = if (row == firstRow) 0 else above[k][x].toInt()
                    val s0 = 20 * a + 4 * b
                    val categoryOut: Int
                    if (d == 0) {
                        qm.code(stat, s0, 0)
                        categoryOut = 0
                    } else {
                        qm.code(stat, s0, 1)
                        val sign = if (d < 0) 1 else 0
                        qm.code(stat, s0 + 1, sign)
                        val st = s0 + 2 + sign
                        val mag = (if (d < 0) -d else d) - 1
                        val m = if (mag == 0) {
                            qm.code(stat, st, 0)
                            0
                        } else {
                            qm.code(stat, st, 1)
                            qm.magnitudeRest(stat, mag, if (b < 3) 100 else 129, 1)
                        }
                        categoryOut = when {
                            m < (1 shl coding.dcLower) shr 1 -> 0
                            m > (1 shl coding.dcUpper) shr 1 -> 3 + sign
                            else -> 1 + sign
                        }
                    }
                    left[k] = categoryOut
                    above[k][x] = categoryOut.toByte()
                }
            }
            qm.finish()
        }
        out.byte(0xFF); out.byte(0xD9)
        return out.toByteArray()
    }
}
