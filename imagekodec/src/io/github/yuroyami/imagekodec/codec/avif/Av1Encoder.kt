package io.github.yuroyami.imagekodec.codec.avif

import io.github.yuroyami.imagekodec.ExperimentalHeifCodecApi
import io.github.yuroyami.imagekodec.HeifPlanes

/**
 * The settings of one AV1 encode (#176): [qIndex] 0 codes losslessly; [effort] 1 to 3 widens the search.
 */
internal class Av1EncodeSettings(
    val qIndex: Int,
    val effort: Int,
    /** colour_primaries, transfer_characteristics and matrix_coefficients to signal, or null for none. */
    val colour: IntArray?,
    /** Whether loop restoration may be chosen; tests turn it off to measure what it brings. */
    val restoration: Boolean = true,
)

/** A coded AV1 still picture: the sequence header OBU, and the item's data (the sequence header and the frame OBU). */
internal class Av1Coded(val params: Av1StreamParams, val sequenceHeaderObu: ByteArray, val data: ByteArray)

/**
 * The AV1 intra encoder (#176): one key frame of [planes], as a still picture with a reduced still picture header.
 *
 * It runs the core's own frame decoder in coding mode ([Av1FrameDecoder.enc]): the encoder picks each partition,
 * mode and transform, and the frame decoder walks the syntax, predicts and reconstructs, so the encoder's
 * reconstruction is what any decoder rebuilds. Three passes:
 *
 * 1. Each superblock is searched ([Av1BlockSearch]): partitions, intra modes and transform sizes by rate and
 *    distortion, each candidate coded into a counter on frozen CDFs. The chosen tree is then coded once more into
 *    a counter that adapts the CDFs, which leaves the reconstruction in the frame and records every decision.
 * 2. The loop filter levels, the CDEF strengths and the restoration units are chosen on that reconstruction
 *    ([Av1FilterSearch]).
 * 3. The recorded decisions are coded again into the arithmetic coder under the final frame header.
 */
@OptIn(ExperimentalHeifCodecApi::class)
internal class Av1Encoder(private val planes: HeifPlanes, private val settings: Av1EncodeSettings, private val cancel: (() -> Unit)?) {

    private val width = planes.width
    private val height = planes.height
    private val bitDepth = planes.bitDepthLuma
    private val mono = planes.planes.size == 1
    private val subX = if (mono) 1 else planes.subX
    private val subY = if (mono) 1 else planes.subY
    private val lossless = settings.qIndex == 0
    private val effort = settings.effort

    val stream = Av1StreamParams(
        width, height, bitDepth, mono, subX, subY, settings.colour, planes.fullRange,
        enableFilterIntra = !lossless, enableCdef = !lossless, enableRestoration = !lossless && effort >= 2 && settings.restoration,
    )

    fun encode(): Av1Coded {
        val seqPayload = stream.sequenceHeader()
        val seq = Av1SequenceHeader.parse(Av1BitReader(seqPayload, 0, seqPayload.size))
        val (colsLog2, rowsLog2) = tiles()
        val search = Av1FrameParams(settings.qIndex, screenContent(), colsLog2, rowsLog2, cdefBits = if (lossless) 0 else 3)
        val fh1 = parseFrameHeader(seq, search)
        val d = Av1FrameDecoder(seq, fh1, Av1Cdfs.initial(fh1.baseQIdx), Av1RefSlots())
        d.cancel = cancel
        val source = Av1Source(planes, d)
        val record = Av1Record()
        lastRecord = record
        val block = Av1BlockSearch(d, source, record, settings.qIndex, effort)
        for (tile in 0 until fh1.tileCols * fh1.tileRows) block.tile(tile)
        // The filters on the reconstruction, then the final header.
        val filterSearch = Av1FilterSearch(d, source, record, effort)
        val filters = filterSearch.search(search, stream)
        chosenFilters = filters
        val fh = parseFrameHeader(seq, filters)
        val w = Av1FrameDecoder(seq, fh, Av1Cdfs.initial(fh.baseQIdx), Av1RefSlots())
        val writer = Av1Replay(record, filters)
        writer.lrUnits = filterSearch.lr
        w.enc = writer
        val tiles = ArrayList<ByteArray>()
        for (tile in 0 until fh.tileCols * fh.tileRows) {
            cancel?.invoke()
            w.beginTile(tile)
            val sink = Av1SymbolWriter(adapt = true)
            writer.sink = sink
            var r = w.miRowStart
            while (r < w.miRowEnd) {
                w.beginSuperblockRow()
                var c = w.miColStart
                while (c < w.miColEnd) {
                    w.superblock(r, c)
                    c += 16
                }
                r += 16
            }
            tiles += sink.finish()
        }
        reconstruction = w
        val header = filters.frameHeader(stream)
        val tg = Av1BitWriter()
        if (tiles.size > 1) {
            tg.flag(false) // tile_start_and_end_present_flag
            tg.byteAlignment()
        }
        val tileGroup = tg.toByteArray()
        var size = header.size + tileGroup.size
        for (i in tiles.indices) size += tiles[i].size + (if (i < tiles.size - 1) 4 else 0)
        val payload = ByteArray(size)
        var at = 0
        header.copyInto(payload, at)
        at += header.size
        tileGroup.copyInto(payload, at)
        at += tileGroup.size
        for (i in tiles.indices) {
            if (i < tiles.size - 1) {
                val n = tiles[i].size - 1
                for (k in 0 until 4) payload[at + k] = (n ushr (8 * k)).toByte()
                at += 4
            }
            tiles[i].copyInto(payload, at)
            at += tiles[i].size
        }
        val seqObu = Av1BitWriter.obu(Av1Decoder.OBU_SEQUENCE_HEADER, seqPayload)
        return Av1Coded(stream, seqObu, seqObu + Av1BitWriter.obu(Av1Decoder.OBU_FRAME, payload))
    }

    /** The frame header's filter choices of the last [encode], for tests that check a tool was used. */
    var chosenFilters: Av1FrameParams? = null
        private set

    private var lastRecord: Av1Record? = null

    /**
     * How many blocks or transform blocks of the last [encode] used each coding tool, by name: for tests that check a
     * tool was exercised, not merely allowed.
     */
    fun toolUse(): Map<String, Int> {
        val rec = lastRecord ?: error("encode first")
        val out = HashMap<String, Int>()
        fun add(name: String) {
            out[name] = (out[name] ?: 0) + 1
        }
        for (b in rec.blocks) {
            when {
                b.paletteSizeY > 0 -> add("palette")
                b.useFilterIntra -> add("filter intra")
                Av1.isDirectionalMode(b.yMode) -> add(if (b.angleDeltaY != 0) "directional with angle delta" else "directional")
                b.yMode == Av1.DC_PRED -> add("dc")
                b.yMode == Av1.PAETH_PRED -> add("paeth")
                else -> add("smooth")
            }
            if (b.uvMode == Av1.UV_CFL_PRED) add("chroma from luma")
            if (b.skip == 1) add("skip")
        }
        var at = 0
        val c = rec.coefficients
        while (at < rec.coefficientsSize) {
            val type = c[at]
            val eob = c[at + 1]
            at += 2 + eob
            if (eob > 0) add("transform type $type")
        }
        for (p in rec.partitions.values) add("partition $p")
        return out
    }

    /** The frame decoder that wrote the stream, its frame the reconstruction before the post filters. */
    private var reconstruction: Av1FrameDecoder? = null

    /**
     * The picture any decoder outputs for the stream [encode] wrote: the writing pass's reconstruction after the
     * decoder's own post filters, each plane cropped to its shown size, rows packed. For the oracle tests.
     */
    fun decodedPlanes(): Array<ShortArray> {
        val w = reconstruction ?: error("encode first")
        Av1PostFilter.apply(w)
        val out = w.outputPlanes()
        val strides = w.outputStrides()
        return Array(w.numPlanes) { p ->
            val pw = if (p == 0) width else (width + subX) shr subX
            val ph = if (p == 0) height else (height + subY) shr subY
            ShortArray(pw * ph) { i -> out[p][(i / pw) * strides[p] + i % pw] }
        }
    }

    /**
     * Whether to allow the screen content tools (the palette): from effort 2, when at least a tenth of the 16 by 16
     * luma blocks hold 2 to 8 values spread over at least 3/16 of the range, as text and drawings do and photos do not.
     */
    private fun screenContent(): Boolean {
        if (effort < 2) return false
        val luma = planes.planes[0]
        val stride = planes.strides[0]
        val spread = 3 shl (bitDepth - 4)
        val seen = IntArray(9)
        var blocks = 0
        var screen = 0
        var by = 0
        while (by + 16 <= height) {
            var bx = 0
            while (bx + 16 <= width) {
                blocks++
                var n = 0
                var lo = Int.MAX_VALUE
                var hi = Int.MIN_VALUE
                scan@ for (y in by until by + 16) for (x in bx until bx + 16) {
                    val v = luma[y * stride + x].toInt()
                    lo = minOf(lo, v)
                    hi = maxOf(hi, v)
                    var k = 0
                    while (k < n && seen[k] != v) k++
                    if (k == n) {
                        if (n == 9) break@scan
                        seen[n++] = v
                    }
                }
                if (n in 2..8 && hi - lo >= spread) screen++
                bx += 16
            }
            by += 16
        }
        return blocks > 0 && screen * 10 >= blocks
    }

    /**
     * Uniform tiles as few as the specification allows: a tile at most 4096 samples wide and 4096 * 2304 in area
     * (MAX_TILE_WIDTH and MAX_TILE_AREA). One tile up to that.
     */
    private fun tiles(): Pair<Int, Int> {
        val sbCols = (width + 63) shr 6
        val sbRows = (height + 63) shr 6
        val minCols = Av1FrameHeader.tileLog2(Av1FrameHeader.MAX_TILE_WIDTH shr 6, sbCols)
        val minTiles = maxOf(minCols, Av1FrameHeader.tileLog2(Av1FrameHeader.MAX_TILE_AREA shr 12, sbRows * sbCols))
        return minCols to maxOf(minTiles - minCols, 0)
    }

    private fun parseFrameHeader(seq: Av1SequenceHeader, p: Av1FrameParams): Av1FrameHeader {
        val bytes = p.frameHeader(stream)
        return Av1FrameHeader.parse(Av1BitReader(bytes, 0, bytes.size), seq, Av1RefSlots(), 0, 0)
    }
}

/**
 * The source planes, at the frame decoder's superblock-aligned size: the picture's samples, and past its right and
 * bottom edges its last column and row repeated, which costs little to code where nothing is shown.
 */
@OptIn(ExperimentalHeifCodecApi::class)
internal class Av1Source(p: HeifPlanes, d: Av1FrameDecoder) {
    val width = p.width
    val height = p.height
    val planes: Array<ShortArray> = Array(d.numPlanes) { plane ->
        val sx = if (plane > 0) d.subX else 0
        val sy = if (plane > 0) d.subY else 0
        val w = (p.width + sx) shr sx
        val h = (p.height + sy) shr sy
        val stride = d.planeWidth[plane]
        val rows = d.planeHeight[plane]
        val src = p.planes[plane]
        val srcStride = p.strides[plane]
        val out = ShortArray(stride * rows)
        for (y in 0 until rows) {
            val sy2 = minOf(y, h - 1) * srcStride
            for (x in 0 until stride) out[y * stride + x] = src[sy2 + minOf(x, w - 1)]
        }
        out
    }

    /** The shown width and height of [plane]. */
    fun shownWidth(plane: Int, d: Av1FrameDecoder): Int = if (plane == 0) width else (width + d.subX) shr d.subX
    fun shownHeight(plane: Int, d: Av1FrameDecoder): Int = if (plane == 0) height else (height + d.subY) shr d.subY
}

/**
 * Every decision of the search, in coding order: the partitions of each block size by position, and the blocks
 * and transform blocks as the commit pass coded them, which the writing pass codes again.
 */
internal class Av1Record {
    /** The partition of the square block of each size (8 to 128) at each 4x4 position, by size index 0 to 4. */
    val partitions = HashMap<Long, Int>()
    val blocks = ArrayList<Av1BlockChoice>()
    /** Per transform block: its type, its end of block, then its signed levels in scan order. */
    var coefficients = IntArray(1 shl 16)
    var coefficientsSize = 0
    /** cdef_idx of each 64 by 64 block by its 4x4 position, -1 for none. */
    val cdef = HashMap<Int, Int>()

    fun key(r: Int, c: Int, bSize: Int): Long = (r.toLong() shl 32) or (c.toLong() shl 8) or bSize.toLong()

    fun add(v: Int) {
        if (coefficientsSize == coefficients.size) coefficients = coefficients.copyOf(coefficients.size * 2)
        coefficients[coefficientsSize++] = v
    }
}

/** The writing pass: every decision from [record], and the filters' from [filters]. */
internal class Av1Replay(private val record: Av1Record, private val filters: Av1FrameParams) : Av1Coding() {
    private var nextBlock = 0
    private var nextCoefficient = 0
    var lrUnits: Av1LrChoice? = null

    override fun partition(r: Int, c: Int, bSize: Int): Int = record.partitions[record.key(r, c, bSize)]!!

    override fun block(d: Av1FrameDecoder, r: Int, c: Int, subSize: Int): Av1BlockChoice = record.blocks[nextBlock++]

    override fun cdefIdx(r: Int, c: Int): Int = record.cdef[(r shl 16) or c] ?: 0

    override fun coefficients(d: Av1FrameDecoder, plane: Int, startX: Int, startY: Int, txSz: Int): Int {
        val rec = record.coefficients
        txType = rec[nextCoefficient++]
        val eob = rec[nextCoefficient++]
        if (eob == 0) return 0
        val scan = d.scanFor(txSz, txType)
        val n = minOf(32, Av1.txWidth[txSz]) * minOf(32, Av1.txHeight[txSz])
        levels.fill(0, 0, n)
        for (c in 0 until eob) levels[scan[c]] = rec[nextCoefficient++]
        return eob
    }

    override fun lrUnit(plane: Int, unitRow: Int, unitCol: Int, wiener: IntArray, wienerAt: Int, xqd: IntArray, xqdAt: Int): Int {
        val lr = lrUnits ?: return Av1FrameHeader.RESTORE_NONE
        return lr.unit(this, plane, unitRow, unitCol, wiener, wienerAt, xqd, xqdAt)
    }
}

/** The restoration units the filter search chose; see [Av1FilterSearch]. */
internal interface Av1LrChoice {
    fun unit(coding: Av1Coding, plane: Int, unitRow: Int, unitCol: Int, wiener: IntArray, wienerAt: Int, xqd: IntArray, xqdAt: Int): Int
}
