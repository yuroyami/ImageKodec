package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.internal.flate.ByteArrayBuilder

/**
 * Writes the quantized coefficients of a Huffman-coded DCT JPEG again with Huffman tables built
 * for the image (#132), so the file gets smaller and every pixel stays the same.
 *
 * The scans of a baseline, extended or progressive file are decoded to coefficients (ITU-T T.81,
 * F.2.2 and G.2), counted, and written again with tables from the code-length procedure of
 * Annex K.2, limited to 16 bits as K.3 describes. Every segment outside the entropy coding stays
 * in its order: the `APPn` and `COM` segments, the quantization tables and the frame header. The
 * restart interval goes, as `jpegtran` drops it. Arithmetic-coded, lossless and hierarchical files
 * come back unchanged, and so does a file the repack would not make smaller.
 */
internal object JpegRepack {

    private class Component(val id: Int, val h: Int, val v: Int) {
        var blocksWide = 0
        var blocksHigh = 0

        /** Blocks a scan of this component alone covers (T.81, A.2.2). */
        var scanWide = 0
        var scanHigh = 0
        lateinit var coefficients: ShortArray
    }

    private class Frame(val progressive: Boolean, val precision: Int, val width: Int, val height: Int, val components: List<Component>) {
        val hMax = components.maxOf { it.h }
        val vMax = components.maxOf { it.v }
        val mcusWide = (width + 8 * hMax - 1) / (8 * hMax)
        val mcusHigh = (height + 8 * vMax - 1) / (8 * vMax)
    }

    private class Unsupported : Exception()

    fun repack(data: ByteArray, progressive: Boolean): ByteArray {
        val out = rewrite(data, progressive)
        return if (out != null && out.size < data.size) out else data
    }

    /** The rewritten file, whatever its size, or null for a file this cannot read. */
    fun rewrite(data: ByteArray, progressive: Boolean): ByteArray? = try {
        repackOrNull(data, progressive)
    } catch (_: Unsupported) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private fun u16(d: ByteArray, at: Int): Int = ((d[at].toInt() and 0xFF) shl 8) or (d[at + 1].toInt() and 0xFF)

    private fun repackOrNull(data: ByteArray, progressive: Boolean): ByteArray? {
        if (data.size < 4 || u16(data, 0) != 0xFFD8) return null
        val kept = ArrayList<IntArray>() // start and end of each segment that stays, in order
        var frameSegment = -1
        var frame: Frame? = null
        val dc = arrayOfNulls<Huffman>(4)
        val ac = arrayOfNulls<Huffman>(4)
        var restart = 0
        var at = 2
        var end = -1
        var scans = 0
        while (at + 2 <= data.size) {
            if ((data[at].toInt() and 0xFF) != 0xFF) throw Unsupported()
            val marker = data[at + 1].toInt() and 0xFF
            if (marker == 0xFF) { at++; continue }
            if (marker == 0xD9) { end = at + 2; break }
            if (marker == 0x01 || marker in 0xD0..0xD7) { at += 2; continue }
            if (at + 4 > data.size) throw Unsupported()
            val length = u16(data, at + 2)
            if (length < 2 || at + 2 + length > data.size) throw Unsupported()
            val body = at + 4
            val next = at + 2 + length
            when (marker) {
                0xC0, 0xC1, 0xC2 -> {
                    if (frame != null) throw Unsupported()
                    frame = readFrame(data, body, next, marker == 0xC2)
                    frameSegment = kept.size
                    kept += intArrayOf(at, next)
                }
                0xC4 -> readTables(data, body, next, dc, ac)
                0xDD -> restart = u16(data, body)
                0xDA -> {
                    val f = frame ?: throw Unsupported()
                    at = decodeScan(data, body, next, f, dc, ac, restart)
                    scans++
                    continue
                }
                0xDC, 0xDE, 0xDF -> throw Unsupported() // DNL, DHP and EXP: hierarchical or a height set late
                in 0xC3..0xCF -> throw Unsupported() // lossless, hierarchical and arithmetic-coded frames
                else -> kept += intArrayOf(at, next)
            }
            at = next
        }
        val f = frame ?: return null
        if (end < 0 || scans == 0) return null

        val out = ByteArrayBuilder(data.size)
        out.append(data, 0, 2)
        for ((i, segment) in kept.withIndex()) {
            val bytes = data.copyOfRange(segment[0], segment[1])
            if (i == frameSegment) bytes[1] = (if (progressive) 0xC2 else if (f.precision == 8) 0xC0 else 0xC1).toByte()
            out.append(bytes)
        }
        if (progressive) writeProgressive(out, f) else writeSequential(out, f)
        out.append(0xFF.toByte())
        out.append(0xD9.toByte())
        val primary = out.toByteArray()
        if (end == data.size) return primary
        // Whatever follows the image, a gain map or a video, stays after it, and the MPF segment
        // that points to a second image moves its offsets with the new end of the first.
        return UltraHdr.shiftMpf(data, end, primary) + data.copyOfRange(end, data.size)
    }

    private fun readFrame(d: ByteArray, at: Int, end: Int, progressive: Boolean): Frame {
        val precision = d[at].toInt() and 0xFF
        if (precision != 8 && precision != 12) throw Unsupported()
        val height = u16(d, at + 1)
        val width = u16(d, at + 3)
        val count = d[at + 5].toInt() and 0xFF
        if (height == 0 || width == 0 || count !in 1..4 || at + 6 + count * 3 > end) throw Unsupported()
        val components = List(count) {
            val c = at + 6 + it * 3
            val sampling = d[c + 1].toInt() and 0xFF
            val h = sampling shr 4
            val v = sampling and 15
            if (h !in 1..4 || v !in 1..4) throw Unsupported()
            Component(d[c].toInt() and 0xFF, h, v)
        }
        val frame = Frame(progressive, precision, width, height, components)
        for (c in components) {
            c.blocksWide = frame.mcusWide * c.h
            c.blocksHigh = frame.mcusHigh * c.v
            c.scanWide = ((width.toLong() * c.h + frame.hMax - 1) / frame.hMax + 7).toInt() / 8
            c.scanHigh = ((height.toLong() * c.v + frame.vMax - 1) / frame.vMax + 7).toInt() / 8
            val blocks = c.blocksWide.toLong() * c.blocksHigh
            if (blocks * 64 > Int.MAX_VALUE / 2) throw Unsupported()
            c.coefficients = ShortArray((blocks * 64).toInt())
        }
        return frame
    }

    /** A decoding table of T.81, F.2.2.3. */
    private class Huffman(counts: IntArray, val values: IntArray) {
        val maxCode = IntArray(18) { -1 }
        val valPtr = IntArray(17)
        val minCode = IntArray(17)

        init {
            var code = 0
            var k = 0
            for (length in 1..16) {
                valPtr[length] = k
                minCode[length] = code
                code += counts[length]
                k += counts[length]
                maxCode[length] = if (counts[length] > 0) code - 1 else -1
                code = code shl 1
            }
            maxCode[17] = Int.MAX_VALUE
        }
    }

    private fun readTables(d: ByteArray, from: Int, end: Int, dc: Array<Huffman?>, ac: Array<Huffman?>) {
        var at = from
        while (at < end) {
            val info = d[at].toInt() and 0xFF
            val counts = IntArray(17)
            var total = 0
            for (i in 1..16) { counts[i] = d[at + i].toInt() and 0xFF; total += counts[i] }
            if (info shr 4 > 1 || info and 15 > 3 || total > 256 || at + 17 + total > end) throw Unsupported()
            val values = IntArray(total) { d[at + 17 + it].toInt() and 0xFF }
            val table = Huffman(counts, values)
            if (info shr 4 == 0) dc[info and 15] = table else ac[info and 15] = table
            at += 17 + total
        }
    }

    /** Reads entropy-coded bits, undoing the byte stuffing; a marker ends the bits, which then read as 1s, as libjpeg pads them. */
    private class BitReader(val d: ByteArray, var at: Int) {
        private var buffer = 0
        private var count = 0
        var marker = -1

        fun bit(): Int {
            if (count == 0) {
                var byte = 0xFF
                if (marker < 0 && at < d.size) {
                    byte = d[at].toInt() and 0xFF
                    if (byte == 0xFF) {
                        val next = if (at + 1 < d.size) d[at + 1].toInt() and 0xFF else 0xD9
                        if (next == 0) at += 2 else { marker = next; byte = 0xFF }
                    } else at++
                }
                buffer = byte
                count = 8
            }
            count--
            return (buffer shr count) and 1
        }

        fun bits(n: Int): Int {
            var v = 0
            repeat(n) { v = (v shl 1) or bit() }
            return v
        }

        /** Reads a restart marker: the bits left in the byte go, then the marker. */
        fun restart() {
            count = 0
            if (marker < 0) {
                while (at + 1 < d.size && !((d[at].toInt() and 0xFF) == 0xFF && (d[at + 1].toInt() and 0xFF) != 0)) at++
                marker = if (at + 1 < d.size) d[at + 1].toInt() and 0xFF else -1
            }
            if (marker !in 0xD0..0xD7) throw Unsupported()
            at += 2
            marker = -1
        }

        /** The position of the marker after the scan. */
        fun end(): Int {
            if (marker < 0) {
                while (at + 1 < d.size && !((d[at].toInt() and 0xFF) == 0xFF && (d[at + 1].toInt() and 0xFF) != 0)) at++
            }
            return at
        }

        fun decode(h: Huffman?): Int {
            if (h == null) throw Unsupported()
            var code = bit()
            var length = 1
            while (code > h.maxCode[length]) {
                code = (code shl 1) or bit()
                length++
                if (length > 16) throw Unsupported()
            }
            return h.values[h.valPtr[length] + code - h.minCode[length]]
        }
    }

    private fun extend(v: Int, n: Int): Int = if (n == 0) 0 else if (v < (1 shl (n - 1))) v - (1 shl n) + 1 else v

    /** Decodes the scan whose header sits at [header]..[headerEnd], and returns where the next marker starts. */
    private fun decodeScan(d: ByteArray, header: Int, headerEnd: Int, f: Frame, dc: Array<Huffman?>, ac: Array<Huffman?>, restart: Int): Int {
        val count = d[header].toInt() and 0xFF
        if (count !in 1..4 || header + 1 + count * 2 + 3 > headerEnd) throw Unsupported()
        val comps = ArrayList<Component>()
        val dcTables = ArrayList<Huffman?>()
        val acTables = ArrayList<Huffman?>()
        for (i in 0 until count) {
            val id = d[header + 1 + i * 2].toInt() and 0xFF
            val tables = d[header + 2 + i * 2].toInt() and 0xFF
            comps += f.components.firstOrNull { it.id == id } ?: throw Unsupported()
            dcTables += dc[(tables shr 4) and 3]
            acTables += ac[tables and 3]
        }
        val p = header + 1 + count * 2
        val ss = d[p].toInt() and 0xFF
        val se = d[p + 1].toInt() and 0xFF
        val ah = (d[p + 2].toInt() and 0xFF) shr 4
        val al = d[p + 2].toInt() and 15
        val sequential = !f.progressive
        if (f.progressive && (ss > se || se > 63 || (ss == 0 && se != 0) || (ss > 0 && count != 1) || al > 13)) throw Unsupported()
        val r = BitReader(d, headerEnd)
        val predictions = IntArray(count)
        var eobRun = 0

        fun block(index: Int, c: Component, offset: Int) {
            val coef = c.coefficients
            if (sequential) {
                val s = r.decode(dcTables[index])
                predictions[index] += extend(r.bits(s), s)
                coef[offset] = predictions[index].toShort()
                var k = 1
                while (k < 64) {
                    val rs = r.decode(acTables[index])
                    val run = rs shr 4
                    val size = rs and 15
                    if (size == 0) {
                        if (run != 15) break
                        k += 16
                        continue
                    }
                    k += run
                    if (k > 63) throw Unsupported()
                    coef[offset + k] = extend(r.bits(size), size).toShort()
                    k++
                }
            } else if (ss == 0) {
                if (ah == 0) {
                    val s = r.decode(dcTables[index])
                    predictions[index] += extend(r.bits(s), s)
                    coef[offset] = (predictions[index] shl al).toShort()
                } else if (r.bit() == 1) {
                    coef[offset] = (coef[offset].toInt() or (1 shl al)).toShort()
                }
            } else if (ah == 0) {
                if (eobRun > 0) { eobRun--; return }
                var k = ss
                while (k <= se) {
                    val rs = r.decode(acTables[index])
                    val run = rs shr 4
                    val size = rs and 15
                    if (size == 0) {
                        if (run < 15) {
                            eobRun = (1 shl run) - 1
                            if (run > 0) eobRun += r.bits(run)
                            break
                        }
                        k += 16
                        continue
                    }
                    k += run
                    if (k > 63) throw Unsupported()
                    coef[offset + k] = (extend(r.bits(size), size) shl al).toShort()
                    k++
                }
            } else {
                // Successive approximation of the AC coefficients, as libjpeg's decode_mcu_AC_refine.
                val p1 = 1 shl al
                val m1 = -1 shl al
                var k = ss
                if (eobRun == 0) {
                    while (k <= se) {
                        val rs = r.decode(acTables[index])
                        var run = rs shr 4
                        var s = rs and 15
                        if (s != 0) {
                            s = if (r.bit() == 1) p1 else m1
                        } else if (run != 15) {
                            eobRun = 1 shl run
                            if (run > 0) eobRun += r.bits(run)
                            break
                        }
                        while (k <= se) {
                            val v = coef[offset + k].toInt()
                            if (v != 0) {
                                if (r.bit() == 1 && (v and p1) == 0) coef[offset + k] = (if (v >= 0) v + p1 else v + m1).toShort()
                            } else {
                                if (--run < 0) break
                            }
                            k++
                        }
                        if (s != 0 && k <= 63) coef[offset + k] = s.toShort()
                        k++
                    }
                }
                if (eobRun > 0) {
                    while (k <= se) {
                        val v = coef[offset + k].toInt()
                        if (v != 0 && r.bit() == 1 && (v and p1) == 0) coef[offset + k] = (if (v >= 0) v + p1 else v + m1).toShort()
                        k++
                    }
                    eobRun--
                }
            }
        }

        var units = 0
        fun unit() {
            if (restart > 0 && units > 0 && units % restart == 0) {
                r.restart()
                predictions.fill(0)
                eobRun = 0
            }
            units++
        }

        if (count == 1) {
            val c = comps[0]
            for (by in 0 until c.scanHigh) for (bx in 0 until c.scanWide) {
                unit()
                block(0, c, (by * c.blocksWide + bx) * 64)
            }
        } else {
            for (my in 0 until f.mcusHigh) for (mx in 0 until f.mcusWide) {
                unit()
                for ((i, c) in comps.withIndex()) for (y in 0 until c.v) for (x in 0 until c.h) {
                    block(i, c, ((my * c.v + y) * c.blocksWide + mx * c.h + x) * 64)
                }
            }
        }
        return r.end()
    }

    /** Collects symbols, then writes them with the tables built from their counts. */
    private class Coder(tables: Int) {
        val counts = Array(tables) { IntArray(257) }
        var codes: Array<IntArray>? = null
        var lengths: Array<IntArray>? = null
        var out: ByteArrayBuilder? = null
        private var buffer = 0L
        private var bits = 0

        fun symbol(table: Int, value: Int) {
            val o = out
            if (o == null) { counts[table][value]++; return }
            val length = lengths!![table][value]
            check(length > 0)
            put(codes!![table][value], length)
        }

        fun put(value: Int, n: Int) {
            if (out == null || n == 0) return
            buffer = (buffer shl n) or (value.toLong() and ((1L shl n) - 1))
            bits += n
            while (bits >= 8) {
                val byte = ((buffer shr (bits - 8)) and 0xFF).toInt()
                out!!.append(byte.toByte())
                if (byte == 0xFF) out!!.append(0)
                bits -= 8
            }
        }

        fun flush() {
            if (bits > 0) put((1 shl (8 - bits)) - 1, 8 - bits)
            buffer = 0
        }
    }

    private fun ByteArrayBuilder.append(b: Int) = append(b.toByte())

    private fun bitLength(v: Int): Int {
        var x = if (v < 0) -v else v
        var n = 0
        while (x != 0) { n++; x = x shr 1 }
        return n
    }

    private fun magnitude(coder: Coder, table: Int, value: Int, run: Int = 0) {
        val n = bitLength(value)
        coder.symbol(table, (run shl 4) or n)
        coder.put(if (value < 0) value - 1 else value, n)
    }

    /**
     * Code lengths for symbol [counts] by Annex K.2, limited to 16 bits by K.3, as libjpeg's
     * `jpeg_gen_optimal_table`: the 16 counts and the symbols in code order.
     */
    private fun optimalTable(counts: IntArray): Pair<IntArray, IntArray> {
        val freq = LongArray(257) { counts[it].toLong() }
        freq[256] = 1 // a reserved code point, so that no code is all 1s
        val size = IntArray(257)
        val others = IntArray(257) { -1 }
        while (true) {
            var c1 = -1
            var v = Long.MAX_VALUE
            for (i in 0..256) if (freq[i] != 0L && freq[i] <= v) { v = freq[i]; c1 = i }
            var c2 = -1
            v = Long.MAX_VALUE
            for (i in 0..256) if (freq[i] != 0L && freq[i] <= v && i != c1) { v = freq[i]; c2 = i }
            if (c2 < 0) break
            freq[c1] += freq[c2]
            freq[c2] = 0
            size[c1]++
            while (others[c1] >= 0) { c1 = others[c1]; size[c1]++ }
            others[c1] = c2
            size[c2]++
            while (others[c2] >= 0) { c2 = others[c2]; size[c2]++ }
        }
        val bits = IntArray(258)
        for (i in 0..256) if (size[i] > 0) bits[size[i]]++
        for (i in 257 downTo 17) {
            while (bits[i] > 0) {
                var j = i - 2
                while (bits[j] == 0) j--
                bits[i] -= 2
                bits[i - 1]++
                bits[j + 1] += 2
                bits[j]--
            }
        }
        var i = 16
        while (bits[i] == 0) i--
        bits[i]--
        val values = ArrayList<Int>()
        for (length in 1..257) for (s in 0..255) if (size[s] == length) values += s
        return bits.copyOfRange(0, 17) to values.toIntArray()
    }

    /** Builds the tables of [coder] from its counts, writes them as one DHT segment, and readies [coder] to write. */
    private fun writeTables(out: ByteArrayBuilder, coder: Coder, classes: IntArray, ids: IntArray) {
        val segment = ByteArrayBuilder(512)
        coder.codes = Array(classes.size) { IntArray(256) }
        coder.lengths = Array(classes.size) { IntArray(256) }
        for (t in classes.indices) {
            if (coder.counts[t].none { it > 0 }) continue
            val (bits, values) = optimalTable(coder.counts[t])
            segment.append(((classes[t] shl 4) or ids[t]).toByte())
            for (n in 1..16) segment.append(bits[n].toByte())
            for (s in values) segment.append(s.toByte())
            var code = 0
            var k = 0
            for (length in 1..16) {
                repeat(bits[length]) {
                    coder.codes!![t][values[k]] = code
                    coder.lengths!![t][values[k]] = length
                    code++
                    k++
                }
                code = code shl 1
            }
        }
        val body = segment.toByteArray()
        if (body.isNotEmpty()) {
            out.append(0xFF); out.append(0xC4); out.append((body.size + 2) shr 8); out.append(body.size + 2)
            out.append(body)
        }
        coder.out = out
    }

    private fun scanHeader(out: ByteArrayBuilder, comps: List<Pair<Component, Int>>, ss: Int, se: Int) {
        val length = 6 + comps.size * 2
        out.append(0xFF); out.append(0xDA); out.append(length shr 8); out.append(length)
        out.append(comps.size)
        for ((c, tables) in comps) { out.append(c.id); out.append(tables) }
        out.append(ss); out.append(se); out.append(0)
    }

    /** The table a component uses: the first component its own, every other one the second, as libjpeg assigns them. */
    private fun slot(f: Frame, c: Component): Int = if (c === f.components[0]) 0 else 1

    private fun forEachBlock(f: Frame, comps: List<Component>, action: (Int, Component, Int) -> Unit) {
        if (comps.size == 1) {
            val c = comps[0]
            for (by in 0 until c.scanHigh) for (bx in 0 until c.scanWide) action(0, c, (by * c.blocksWide + bx) * 64)
        } else {
            for (my in 0 until f.mcusHigh) for (mx in 0 until f.mcusWide) {
                for ((i, c) in comps.withIndex()) for (y in 0 until c.v) for (x in 0 until c.h) {
                    action(i, c, ((my * c.v + y) * c.blocksWide + mx * c.h + x) * 64)
                }
            }
        }
    }

    /** One interleaved sequential scan, with a DC and an AC table for each slot. */
    private fun writeSequential(out: ByteArrayBuilder, f: Frame) {
        val coder = Coder(4)
        fun pass() {
            val predictions = IntArray(f.components.size)
            forEachBlock(f, f.components) { i, c, offset ->
                val s = slot(f, c)
                val coef = c.coefficients
                val dc = coef[offset].toInt()
                magnitude(coder, s, dc - predictions[i])
                predictions[i] = dc
                var run = 0
                for (k in 1..63) {
                    val v = coef[offset + k].toInt()
                    if (v == 0) { run++; continue }
                    while (run > 15) { coder.symbol(2 + s, 0xF0); run -= 16 }
                    magnitude(coder, 2 + s, v, run)
                    run = 0
                }
                if (run > 0) coder.symbol(2 + s, 0)
            }
        }
        pass()
        writeTables(out, coder, intArrayOf(0, 0, 1, 1), intArrayOf(0, 1, 0, 1))
        scanHeader(out, f.components.map { it to (slot(f, it) * 0x11) }, 0, 63)
        pass()
        coder.flush()
    }

    /**
     * A progressive file by spectral selection: one interleaved DC scan, then for each component
     * the AC bands 1 to 5 and 6 to 63, each with its own table and end-of-band runs (T.81,
     * G.1.2.2). Every coefficient is sent whole, so no refinement scan is needed.
     */
    private fun writeProgressive(out: ByteArrayBuilder, f: Frame) {
        val coder = Coder(2)
        fun dcPass() {
            val predictions = IntArray(f.components.size)
            forEachBlock(f, f.components) { i, c, offset ->
                val dc = c.coefficients[offset].toInt()
                magnitude(coder, slot(f, c), dc - predictions[i])
                predictions[i] = dc
            }
        }
        dcPass()
        writeTables(out, coder, intArrayOf(0, 0), intArrayOf(0, 1))
        scanHeader(out, f.components.map { it to (slot(f, it) shl 4) }, 0, 0)
        dcPass()
        coder.flush()

        for (c in f.components) for ((ss, se) in listOf(1 to 5, 6 to 63)) {
            val band = Coder(1)
            fun acPass() {
                var eobRun = 0
                fun flushRun() {
                    if (eobRun == 0) return
                    val n = bitLength(eobRun) - 1
                    band.symbol(0, n shl 4)
                    band.put(eobRun, n)
                    eobRun = 0
                }
                forEachBlock(f, listOf(c)) { _, _, offset ->
                    val coef = c.coefficients
                    var run = 0
                    for (k in ss..se) {
                        val v = coef[offset + k].toInt()
                        if (v == 0) { run++; continue }
                        flushRun()
                        while (run > 15) { band.symbol(0, 0xF0); run -= 16 }
                        magnitude(band, 0, v, run)
                        run = 0
                    }
                    if (run > 0) {
                        eobRun++
                        if (eobRun == 0x7FFF) flushRun()
                    }
                }
                flushRun()
            }
            acPass()
            writeTables(out, band, intArrayOf(1), intArrayOf(0))
            scanHeader(out, listOf(c to 0), ss, se)
            acPass()
            band.flush()
        }
    }
}
