package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.JpegComponents
import io.github.yuroyami.imagekodec.UnsupportedImageException

/**
 * Old-style JPEG in TIFF (compression 6, TIFF 6.0 section 22), withdrawn by Technical
 * Note 2 but still written by scanners and fax software of the 1990s. Its files do not
 * agree on where the JPEG stream lives: the headers may sit at JPEGInterchangeFormat
 * with the scan data in the strips, or the strips may hold only scan data with the
 * tables as bare arrays behind JPEGQTables, JPEGDCTables and JPEGACTables.
 *
 * So this rebuilds one JPEG stream for the whole image the way libtiff's tif_ojpeg.c
 * does, which is what every reader that handles these files ends up agreeing with:
 * read markers from JPEGInterchangeFormat and then on into the strips until the scan
 * header, build what is missing from the tags, give a restart interval of one strip
 * when there are several and put a restart marker between them, and end with EOI.
 *
 * libtiff hands the samples on without upsampling or color conversion, so the TIFF
 * YCbCr path converts them with the file's own ReferenceBlackWhite, which these files
 * often set to the studio range; [decode] returns the planes for that.
 */
internal object TiffOldJpeg {

    private fun err(msg: String): Nothing = throw ImageDecodeException("TIFF: old-style JPEG: $msg")

    /** What the TIFF directory says about the image and where its bytes are. */
    class Source(
        val width: Int,
        val height: Int,
        val tiled: Boolean,
        val strileWidth: Int,
        val strileLength: Int,
        val samples: Int,
        val ycbcr: Boolean,
        val tagSubH: Int,
        val tagSubV: Int,
        val interchangeFormat: Long,
        val interchangeFormatLength: Long,
        val restartIntervalTag: Int,
        val qTables: LongArray?,
        val dcTables: LongArray?,
        val acTables: LongArray?,
        val strileOffsets: LongArray,
        val strileCounts: LongArray?,
    )

    /**
     * The image's samples: [planes] at their own resolutions and the chroma subsampling
     * [subH] by [subV] they have. When the stream's sampling factors are not that simple
     * shape, they come back upsampled and [subH] and [subV] are 1, as libtiff then has
     * libjpeg upsample.
     */
    class Image(val subH: Int, val subV: Int, val planes: JpegDecoder.Planes?, val upsampled: JpegComponents?)

    private class Segment(val start: Int, val end: Int, val strile: Boolean)

    /** Reads bytes across the segments in order, as libtiff's input buffer does. */
    private class Cursor(val data: ByteArray, val segments: List<Segment>) {
        var seg = 0
        var pos = segments.firstOrNull()?.start ?: 0

        private fun settle() {
            while (seg < segments.size && pos >= segments[seg].end) {
                seg++
                if (seg < segments.size) pos = segments[seg].start
            }
        }

        fun peek(): Int {
            settle()
            if (seg >= segments.size) err("data ends inside the JPEG headers")
            return data[pos].toInt() and 0xFF
        }

        fun byte(): Int = peek().also { pos++ }

        fun word(): Int = (byte() shl 8) or byte()

        fun bytes(n: Int): ByteArray = ByteArray(n) { byte().toByte() }
    }

    fun decode(data: ByteArray, s: Source): Image {
        if (s.samples != 1 && s.samples != 3) {
            throw UnsupportedImageException("TIFF: old-style JPEG with ${s.samples} samples per pixel (1 or 3)")
        }
        if (s.tiled && (s.width + s.strileWidth - 1) / s.strileWidth > 1) {
            throw UnsupportedImageException("TIFF: old-style JPEG in more than one column of tiles")
        }
        val size = data.size.toLong()
        val segments = ArrayList<Segment>()
        // JPEGInterchangeFormat first, clipped to the file, and ignored if it starts past the end.
        if (s.interchangeFormat in 1 until size) {
            val length = s.interchangeFormatLength
            val end = if (length <= 0 || length > size - s.interchangeFormat) size else s.interchangeFormat + length
            segments += Segment(s.interchangeFormat.toInt(), end.toInt(), strile = false)
        }
        for (i in s.strileOffsets.indices) {
            val offset = s.strileOffsets[i]
            if (offset <= 0 || offset >= size) {
                segments += Segment(0, 0, strile = true)
                continue
            }
            val count = s.strileCounts?.getOrNull(i) ?: 0L
            val end = if (count <= 0 || count > size - offset) size else offset + count
            segments += Segment(offset.toInt(), end.toInt(), strile = true)
        }

        // --- the markers before the scan, as OJPEGReadHeaderInfoSec reads them ---------
        val cursor = Cursor(data, segments)
        val quantization = arrayOfNulls<ByteArray>(4)
        val dc = arrayOfNulls<ByteArray>(4)
        val ac = arrayOfNulls<ByteArray>(4)
        var streamRestart = -1
        var sofMarker = -1
        var sofX = 0
        var sofY = 0
        val sofC = IntArray(3)
        val sofHv = IntArray(3)
        val sofTq = IntArray(3)
        var sawSos = false
        val sosCs = IntArray(3)
        val sosTda = IntArray(3)
        while (!sawSos) {
            if (cursor.peek() != 0xFF) break
            cursor.byte()
            var m: Int
            do m = cursor.byte() while (m == 0xFF)
            when (m) {
                0xD8 -> Unit
                0xFE, in 0xE0..0xEF -> {
                    val n = cursor.word()
                    if (n < 2) err("corrupt marker length")
                    repeat(n - 2) { cursor.byte() }
                }
                0xDD -> {
                    if (cursor.word() != 4) err("corrupt DRI marker")
                    streamRestart = cursor.word()
                }
                0xDB -> {
                    var n = cursor.word() - 2
                    if (n <= 0) err("corrupt DQT marker")
                    while (n > 0) {
                        if (n < 65) err("corrupt DQT marker")
                        val pqTq = cursor.byte()
                        if (pqTq shr 4 != 0) err("16-bit quantization tables")
                        val id = pqTq and 15
                        if (id > 3) err("quantization table $id")
                        quantization[id] = byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0, 67, id.toByte()) + cursor.bytes(64)
                        n -= 65
                    }
                }
                0xC4 -> {
                    var n = cursor.word() - 2
                    if (n <= 0) err("corrupt DHT marker")
                    while (n > 0) {
                        if (n < 17) err("corrupt DHT marker")
                        val tcTh = cursor.byte()
                        val counts = cursor.bytes(16)
                        val values = counts.sumOf { it.toInt() and 0xFF }
                        if (n < 17 + values) err("corrupt DHT marker")
                        val table = huffmanSegment(tcTh, counts, cursor.bytes(values))
                        val id = tcTh and 15
                        if (id > 3) err("Huffman table $id")
                        when (tcTh shr 4) {
                            0 -> dc[id] = table
                            1 -> ac[id] = table
                            else -> err("corrupt DHT marker")
                        }
                        n -= 17 + values
                    }
                }
                0xC0, 0xC1, 0xC3 -> {
                    if (sofMarker >= 0) err("two frame headers")
                    sofMarker = m
                    val length = cursor.word()
                    if (length < 11 || (length - 8) % 3 != 0) err("corrupt SOF marker")
                    val n = (length - 8) / 3
                    if (n != s.samples) err("the frame has $n components for ${s.samples} samples")
                    if (cursor.byte() != 8) throw UnsupportedImageException("TIFF: old-style JPEG with other than 8-bit samples")
                    sofY = cursor.word()
                    sofX = cursor.word()
                    if (cursor.byte() != n) err("corrupt SOF marker")
                    for (q in 0 until n) {
                        sofC[q] = cursor.byte()
                        sofHv[q] = cursor.byte()
                        sofTq[q] = cursor.byte()
                    }
                }
                0xDA -> {
                    if (sofMarker < 0) err("scan header before the frame header")
                    if (cursor.word() != 6 + s.samples * 2) err("corrupt SOS marker")
                    if (cursor.byte() != s.samples) err("corrupt SOS marker")
                    for (q in 0 until s.samples) {
                        sosCs[q] = cursor.byte()
                        sosTda[q] = cursor.byte()
                    }
                    repeat(3) { cursor.byte() }   // Ss, Se, Ah and Al: not checked, as libtiff and Tom Lane advise
                    sawSos = true
                }
                else -> err("unknown marker 0x${m.toString(16)} in the JPEG headers")
            }
        }

        // --- the subsampling the samples really have, as OJPEGSubsamplingCorrect finds it ---
        var subH = 1
        var subV = 1
        var upsample = false
        if (s.ycbcr && s.samples == 3) {
            if (sofMarker >= 0) {
                subH = sofHv[0] shr 4
                subV = sofHv[0] and 15
                if (subH !in LEGAL || subV !in LEGAL || sofHv[1] != 0x11 || sofHv[2] != 0x11) {
                    upsample = true
                    subH = 1
                    subV = 1
                }
            } else {
                subH = s.tagSubH
                subV = s.tagSubV
            }
        } else if (sofMarker >= 0 && (1 until s.samples).any { sofHv[it] != sofHv[0] }) {
            // Samples the TIFF fields call unsubsampled come out of a subsampled stream at full size.
            upsample = true
        }

        // --- what the tags supply when the stream does not, as OJPEGReadHeaderInfoSec does ---
        if (sofMarker < 0) {
            val q = s.qTables ?: err("missing JPEG tables")
            val d = s.dcTables ?: err("missing JPEG tables")
            val a = s.acTables ?: err("missing JPEG tables")
            for (m in 0 until s.samples) {
                sofTq[m] = tableId(q, m) { quantization[it] = byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0, 67, it.toByte()) + tableBytes(data, q[it], 64) }
                val dcId = tableId(d, m) { dc[it] = huffmanTable(data, d[it], it) }
                val acId = tableId(a, m) { ac[it] = huffmanTable(data, a[it], 0x10 or it) }
                sosTda[m] = (dcId shl 4) or acId
                sofC[m] = m
                sosCs[m] = m
                sofHv[m] = if (m == 0) (subH shl 4) or subV else 0x11
            }
            sofMarker = 0xC0
            sofX = s.strileWidth
            sofY = if (s.tiled) (s.height + s.strileLength - 1) / s.strileLength * s.strileLength else s.height
        } else if (!sawSos) {
            for (m in 0 until s.samples) {
                sosCs[m] = sofC[m]
                sosTda[m] = (m shl 4) or m
            }
        }
        if (sofX < s.width || sofY < s.height) err("the JPEG frame is ${sofX}x$sofY for a ${s.width}x${s.height} image")
        if (sofMarker == 0xC3) throw UnsupportedImageException("TIFF: old-style lossless JPEG is not supported")

        // --- a restart interval of one strip when there are several, as OJPEGReadHeaderInfo sets it ---
        var restart = s.restartIntervalTag
        if (s.strileLength < s.height) {
            if (subH !in LEGAL || subV !in LEGAL) err("invalid subsampling ${subH}x$subV")
            if (s.strileLength % (subV * 8) != 0) err("strips of ${s.strileLength} rows do not hold whole MCU rows")
            val interval = (s.strileWidth + subH * 8 - 1) / (subH * 8).toLong() * (s.strileLength / (subV * 8))
            if (interval > 0xFFFF) err("a restart interval of $interval MCUs")
            restart = interval.toInt()
        }
        if (streamRestart >= 0) restart = streamRestart

        // --- the stream, as OJPEGWriteStream lays it out ---------------------------------
        val out = ByteArrayBuilder()
        out.add(0xFF, 0xD8)
        for (t in quantization) t?.let { out.add(it) }
        for (t in dc) t?.let { out.add(it) }
        for (t in ac) t?.let { out.add(it) }
        if (restart != 0) out.add(0xFF, 0xDD, 0, 4, restart shr 8, restart and 0xFF)
        out.add(0xFF, sofMarker, 0, 8 + s.samples * 3, 8, sofY shr 8, sofY and 0xFF, sofX shr 8, sofX and 0xFF, s.samples)
        for (m in 0 until s.samples) out.add(sofC[m], sofHv[m], sofTq[m])
        out.add(0xFF, 0xDA, 0, 6 + s.samples * 2, s.samples)
        for (m in 0 until s.samples) out.add(sosCs[m], sosTda[m])
        out.add(0, 63, 0)
        // The scan data: the rest of the segment the headers ended in, then every later one,
        // with a restart marker after each strip or tile that is not the last.
        val stripCount = segments.count { it.strile }
        var striles = segments.take(cursor.seg).count { it.strile }
        var rst = 0
        for (i in cursor.seg until segments.size) {
            val segment = segments[i]
            val from = if (i == cursor.seg) cursor.pos else segment.start
            if (segment.strile) striles++
            if (from >= segment.end) continue
            out.add(data, from, segment.end)
            if (segment.strile && striles < stripCount) {
                out.add(0xFF, 0xD0 + rst)
                rst = (rst + 1) and 7
            }
        }
        out.add(0xFF, 0xD9)

        val stream = out.toByteArray()
        return try {
            if (upsample) {
                Image(1, 1, null, JpegDecoder.decodeComponents(stream).components)
            } else {
                Image(subH, subV, JpegDecoder.decodePlanes(stream), null)
            }
        } catch (e: UnsupportedImageException) {
            throw UnsupportedImageException("TIFF: old-style JPEG: ${e.message}", e)
        } catch (e: ImageDecodeException) {
            throw ImageDecodeException("TIFF: old-style JPEG: ${e.message}", e)
        }
    }

    private val LEGAL = intArrayOf(1, 2, 4)

    /**
     * The table id component [m] uses: its own, read with [read] when its offset is new,
     * or the previous component's when the offsets repeat, as libtiff numbers them.
     */
    private fun tableId(offsets: LongArray, m: Int, read: (Int) -> Unit): Int {
        // A sample past the end of the tag's values has no table of its own, like an offset of 0.
        fun offset(k: Int) = offsets.getOrElse(k) { 0L }
        var k = m
        while (true) {
            if (offset(k) != 0L && (k == 0 || offset(k) != offset(k - 1))) {
                if (k == m) {
                    for (n in 0 until m - 1) if (offset(n) == offset(m)) err("corrupt JPEG table offsets")
                    read(m)
                }
                return k
            }
            if (k == 0) err("missing JPEG tables")
            k--
        }
    }

    private fun tableBytes(data: ByteArray, offset: Long, n: Int): ByteArray {
        if (offset < 0 || offset > data.size - n) err("a JPEG table past the end of the file")
        return data.copyOfRange(offset.toInt(), offset.toInt() + n)
    }

    /** A DHT segment for the bare table at [offset]: 16 code counts, then the values. */
    private fun huffmanTable(data: ByteArray, offset: Long, tcTh: Int): ByteArray {
        val counts = tableBytes(data, offset, 16)
        val values = counts.sumOf { it.toInt() and 0xFF }
        return huffmanSegment(tcTh, counts, tableBytes(data, offset + 16, values))
    }

    private fun huffmanSegment(tcTh: Int, counts: ByteArray, values: ByteArray): ByteArray {
        val length = 19 + values.size
        return byteArrayOf(0xFF.toByte(), 0xC4.toByte(), (length shr 8).toByte(), length.toByte(), tcTh.toByte()) + counts + values
    }

    private class ByteArrayBuilder {
        private var bytes = ByteArray(1024)
        private var size = 0

        private fun room(n: Int) {
            if (size + n > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + n))
        }

        fun add(vararg values: Int) {
            room(values.size)
            for (v in values) bytes[size++] = v.toByte()
        }

        fun add(values: ByteArray) {
            room(values.size)
            values.copyInto(bytes, size)
            size += values.size
        }

        fun add(source: ByteArray, from: Int, to: Int) {
            room(to - from)
            source.copyInto(bytes, size, from, to)
            size += to - from
        }

        fun toByteArray(): ByteArray = bytes.copyOf(size)
    }
}
