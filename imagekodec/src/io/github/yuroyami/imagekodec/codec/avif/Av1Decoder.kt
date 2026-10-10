package io.github.yuroyami.imagekodec.codec.avif

import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.UnsupportedImageException

/**
 * A decoded AV1 frame: its [planes] of samples at [bitDepth] bits, luma [width] by
 * [height], chroma subsampled by [subX] and [subY], with the colour description the
 * sequence header gives.
 */
internal class Av1Picture(
    val width: Int,
    val height: Int,
    val bitDepth: Int,
    val subX: Int,
    val subY: Int,
    val planes: Array<ShortArray>,
    val strides: IntArray,
    val seq: Av1SequenceHeader,
) {
    val monochrome: Boolean get() = planes.size == 1
}

/** What the headers of an AV1 temporal unit say, without decoding a tile: see [Av1Decoder.inspect]. */
internal class Av1Inspection(val seq: Av1SequenceHeader, val width: Int, val height: Int, val unsupported: String?)

/**
 * Decodes the OBUs of AV1 data in low overhead format (specification section 7): the
 * configuration OBUs of an `av1C` box, then an AVIF item's or a sample's.
 *
 * It decodes the frames of [operatingPoint] and keeps every shown one with the spatial layer it
 * belongs to, so an AVIF item can show its last frame or the layer its `lsel` property names.
 * Frames refresh the reference slots as the reference frame update process says, so a later
 * frame shown from a slot (`show_existing_frame`) is the one stored there.
 */
internal class Av1Decoder(private val operatingPoint: Int = 0) {
    private var seq: Av1SequenceHeader? = null
    private val refs = Av1RefSlots()
    private var frameHeader: Av1FrameHeader? = null
    private var frame: Av1FrameDecoder? = null
    private var frameSpatialId = 0
    private var headersOnly = false
    private var unsupported: String? = null
    private val shown = ArrayList<Shown>()

    /** A shown frame: its picture without grain, its spatial layer, and the grain to add when handed out. */
    private class Shown(val picture: Av1Picture, val spatialId: Int, val grain: Av1FilmGrain?)
    private var lastSize: Pair<Int, Int>? = null

    /** Where a test counts the coding tools the stream used; null otherwise. */
    var toolUse: Av1ToolUse? = null

    /** The first frame [data]'s OBUs show; [data] may start with a sequence header or rely on an earlier one. */
    fun decode(data: ByteArray): Av1Picture {
        run(data)
        return handOut(shown.firstOrNull() ?: throw ImageDecodeException("AV1: the data holds no shown frame"), keep = false)
    }

    /** Every frame [data]'s OBUs show, in order, each with its own film grain. */
    fun decodeAll(data: ByteArray): List<Av1Picture> {
        shown.clear()
        run(data)
        val out = shown.map { handOut(it, keep = true) }
        shown.clear()
        return out
    }

    /**
     * The frame a temporal unit of an image sequence shows, [data] being one sample of an AV1
     * track: the last frame shown, which with several spatial layers is the top one, or null
     * when it shows none. The decoder keeps its reference frames for the samples that follow.
     */
    fun decodeTemporalUnit(data: ByteArray): Av1Picture? {
        shown.clear()
        run(data)
        val last = shown.lastOrNull() ?: return null
        shown.clear()
        return handOut(last, keep = true)
    }

    /**
     * The frame an AVIF item shows (AV1 Image File Format section 2.3): the last shown frame,
     * or for [layer] other than 0xFFFF the last shown frame of that spatial layer.
     */
    fun decodeStill(config: ByteArray?, data: ByteArray, layer: Int): Av1Picture {
        if (config != null && config.isNotEmpty()) configure(config)
        run(data)
        val frames = if (layer == 0xFFFF) shown else shown.filter { it.spatialId == layer }
        return handOut(
            frames.lastOrNull()
                ?: throw ImageDecodeException(if (layer == 0xFFFF) "AV1: the data holds no shown frame" else "AV1: no frame of spatial layer $layer is shown"),
            keep = false,
        )
    }

    /**
     * [s]'s picture with its film grain, which the output process adds to the frame it outputs and
     * not to the one kept for reference. When the decoder goes on to later frames ([keep]) the
     * grain goes on a copy; when this picture is the last thing it hands out, on the frame itself.
     */
    private fun handOut(s: Shown, keep: Boolean): Av1Picture {
        val p = s.picture
        if (s.grain == null) return p
        val out = if (keep) Av1Picture(p.width, p.height, p.bitDepth, p.subX, p.subY, Array(p.planes.size) { p.planes[it].copyOf() }, p.strides, p.seq) else p
        Av1GrainSynthesis(s.grain, out.bitDepth, out.subX, out.subY, out.planes.size, out.seq.matrixCoefficients == 0)
            .apply(out.planes, out.strides, out.width, out.height)
        return out
    }

    /** Reads the OBUs of [data] for their sequence header only, as an `av1C` box carries one. */
    fun configure(data: ByteArray) {
        val was = headersOnly
        headersOnly = true
        var pos = 0
        try {
            while (pos < data.size) pos = obu(data, pos, sequenceOnly = true)
        } finally {
            headersOnly = was
        }
    }

    private fun run(data: ByteArray) {
        var pos = 0
        while (pos < data.size) pos = obu(data, pos)
    }

    private fun obu(data: ByteArray, start: Int, sequenceOnly: Boolean = false): Int {
        val r = Av1BitReader(data, start, data.size)
        if (r.bit() != 0) throw ImageDecodeException("AV1: OBU forbidden bit set")
        val type = r.f(4)
        val extension = r.flag()
        val hasSize = r.flag()
        r.bit()
        var temporalId = 0
        var spatialId = 0
        if (extension) {
            temporalId = r.f(3)
            spatialId = r.f(2)
            r.f(3)
        }
        val size: Long = if (hasSize) r.leb128() else (data.size - r.bytePosition).toLong()
        val payload = r.bytePosition
        if (size > data.size - payload) throw ImageDecodeException("AV1: OBU of $size bytes runs past the data")
        val end = payload + size.toInt()
        val s = seq
        if (s != null && type != OBU_SEQUENCE_HEADER && type != OBU_TEMPORAL_DELIMITER && extension) {
            val idc = s.operatingPointIdc[operatingPoint.coerceIn(0, s.operatingPointIdc.size - 1)]
            if (idc != 0) {
                val inTemporal = (idc shr temporalId) and 1
                val inSpatial = (idc shr (spatialId + 8)) and 1
                if (inTemporal == 0 || inSpatial == 0) return end
            }
        }
        val pr = Av1BitReader(data, payload, end)
        when (type) {
            OBU_SEQUENCE_HEADER -> seq = Av1SequenceHeader.parse(pr)
            OBU_TEMPORAL_DELIMITER -> frameHeader = null
            OBU_FRAME_HEADER, OBU_REDUNDANT_FRAME_HEADER -> if (!sequenceOnly) frameHeaderObu(pr, temporalId, spatialId)
            OBU_FRAME -> if (!sequenceOnly) {
                frameHeaderObu(pr, temporalId, spatialId)
                pr.byteAlignment()
                tileGroupObu(data, pr, end)
            }
            OBU_TILE_GROUP -> if (!sequenceOnly) tileGroupObu(data, pr, end)
            OBU_TILE_LIST -> throw UnsupportedImageException("AV1 large scale tile lists")
            else -> {}
        }
        return end
    }

    private fun frameHeaderObu(r: Av1BitReader, temporalId: Int, spatialId: Int) {
        if (frameHeader != null) return // frame_header_copy( ): a repeat of the header being decoded.
        val s = seq ?: throw ImageDecodeException("AV1: a frame header before the sequence header")
        val fh = Av1FrameHeader.parse(r, s, refs, temporalId, spatialId)
        frameSpatialId = spatialId
        if (fh.showExistingFrame) {
            showExisting(fh)
            return
        }
        frameHeader = fh
        lastSize = fh.upscaledWidth to fh.frameHeight
        if (headersOnly) return
        val cdfs = if (fh.primaryRefFrame == Av1FrameHeader.PRIMARY_REF_NONE) {
            Av1Cdfs.initial(fh.baseQIdx)
        } else {
            val saved = refs.cdfs[fh.refFrameIdx[fh.primaryRefFrame]] ?: throw ImageDecodeException("AV1: a primary reference frame with no CDFs")
            saved.copy().also { it.clearCounters() }
        }
        frame = Av1FrameDecoder(s, fh, cdfs, refs).also { it.toolUse = toolUse }
        toolUse?.let { use ->
            use.add(if (fh.frameIsIntra) "intra frame" else "inter frame")
            if (fh.useSuperres && !fh.frameIsIntra) use.add("superres inter frame")
            if (fh.useRefFrameMvs) use.add("reference frame motion vectors")
            if (fh.skipModePresent) use.add("skip mode allowed")
            if (fh.segmentationEnabled && !fh.frameIsIntra) use.add("segmentation in inter frame")
            if (fh.segmentationEnabled && fh.segmentationTemporalUpdate) use.add("segmentation temporal update")
            if (fh.segmentationEnabled && !fh.segmentationUpdateMap) use.add("segmentation map kept")
            if (fh.primaryRefFrame != Av1FrameHeader.PRIMARY_REF_NONE) use.add("primary reference frame")
            if (fh.frameType == Av1FrameHeader.INTRA_ONLY_FRAME) use.add("intra only frame")
            if (fh.frameType == Av1FrameHeader.SWITCH_FRAME) use.add("switch frame")
            if (fh.errorResilientMode && !fh.frameIsIntra) use.add("error resilient inter frame")
            if (fh.seq.frameIdNumbersPresent) use.add("frame ids")
            if (fh.seq.use128x128Superblock) use.add("128x128 superblocks")
            if (fh.tileCols * fh.tileRows > 1) use.add("tiles")
            if (fh.filmGrain.applyGrain && !fh.frameIsIntra) use.add("film grain inter frame")
            if (!fh.showFrame) use.add("hidden frame")
        }
    }

    private fun tileGroupObu(data: ByteArray, r: Av1BitReader, end: Int) {
        val fh = frameHeader ?: throw ImageDecodeException("AV1: a tile group before its frame header")
        val numTiles = fh.tileCols * fh.tileRows
        var tileStartAndEndPresent = false
        if (numTiles > 1) tileStartAndEndPresent = r.flag()
        var tgStart = 0
        var tgEnd = numTiles - 1
        if (numTiles > 1 && tileStartAndEndPresent) {
            val tileBits = fh.tileColsLog2 + fh.tileRowsLog2
            tgStart = r.f(tileBits)
            tgEnd = r.f(tileBits)
        }
        r.byteAlignment()
        if (tgEnd >= numTiles || tgStart > tgEnd) throw ImageDecodeException("AV1: tile group $tgStart to $tgEnd of $numTiles tiles")
        val f = frame
        if (f != null) {
            var pos = r.bytePosition
            var sz = end - pos
            for (t in tgStart..tgEnd) {
                val tileSize: Int
                if (t == tgEnd) {
                    tileSize = sz
                } else {
                    val tr = Av1BitReader(data, pos, end)
                    tileSize = (tr.le(fh.tileSizeBytes) + 1).toInt()
                    pos += fh.tileSizeBytes
                    sz -= tileSize + fh.tileSizeBytes
                    if (tileSize < 0 || tileSize > end - pos) throw ImageDecodeException("AV1: tile of $tileSize bytes runs past its tile group")
                }
                if (tileSize <= 0) throw ImageDecodeException("AV1: empty tile")
                f.decodeTile(t, data, pos, tileSize)
                pos += tileSize
            }
        }
        if (tgEnd == numTiles - 1) frameEnd(fh, f)
    }

    /** decode_frame_wrapup( ): the post filters, the reference update and the output of a shown frame. */
    private fun frameEnd(fh: Av1FrameHeader, f: Av1FrameDecoder?) {
        val s = fh.seq
        var picture: Av1Picture? = null
        if (f != null) {
            Av1PostFilter.apply(f)
            picture = Av1Picture(fh.upscaledWidth, fh.frameHeight, s.bitDepth, s.subsamplingX, s.subsamplingY, f.outputPlanes(), f.outputStrides(), s)
        }
        update(fh, f, picture)
        if (fh.showFrame && picture != null) output(fh, picture)
        frameHeader = null
        frame = null
    }

    /** The reference frame update process (specification section 7.20), for the slots [fh] refreshes. */
    private fun update(fh: Av1FrameHeader, f: Av1FrameDecoder?, picture: Av1Picture?) {
        if (fh.refreshFrameFlags == 0) return
        val cdfs = f?.let { it.savedCdfs ?: it.frameCdfs }
        val motion = if (f != null && !fh.frameIsIntra) savedMotion(fh, f) else null
        val segments = f?.let { savedSegmentIds(fh, it) }
        for (i in 0 until 8) {
            if ((fh.refreshFrameFlags shr i) and 1 == 0) continue
            refs.valid[i] = true
            refs.frameId[i] = fh.currentFrameId
            refs.upscaledWidth[i] = fh.upscaledWidth
            refs.frameWidth[i] = fh.frameWidth
            refs.frameHeight[i] = fh.frameHeight
            refs.renderWidth[i] = fh.renderWidth
            refs.renderHeight[i] = fh.renderHeight
            refs.miCols[i] = fh.miCols
            refs.miRows[i] = fh.miRows
            refs.frameType[i] = fh.frameType
            refs.orderHint[i] = fh.orderHint
            fh.orderHints.copyInto(refs.savedOrderHints[i])
            refs.cdfs[i] = cdfs?.copy()
            for (ref in 0 until 8) fh.gmParams[ref].copyInto(refs.gmParams[i][ref])
            fh.loopFilterRefDeltas.copyInto(refs.loopFilterRefDeltas[i])
            fh.loopFilterModeDeltas.copyInto(refs.loopFilterModeDeltas[i])
            for (seg in 0 until 8) {
                fh.featureEnabled[seg].copyInto(refs.featureEnabled[i][seg])
                fh.featureData[seg].copyInto(refs.featureData[i][seg])
            }
            if (fh.seq.filmGrainParamsPresent) refs.grain[i].copyFrom(fh.filmGrain)
            refs.pictures[i] = picture
            refs.savedRefFrames[i] = motion?.first
            refs.savedMvs[i] = motion?.second
            refs.savedSegmentIds[i] = segments
        }
    }

    /**
     * The motion field motion vector storage process: for each 8x8, the last vector of the block
     * at its odd row and column that points back in time and is short enough, and its reference.
     */
    private fun savedMotion(fh: Av1FrameHeader, f: Av1FrameDecoder): Pair<ByteArray, ShortArray> {
        val w8 = fh.miCols shr 1
        val h8 = fh.miRows shr 1
        val refFrames = ByteArray(w8 * h8) { Av1.NONE.toByte() }
        val mvs = ShortArray(w8 * h8 * 2)
        for (y8 in 0 until h8) for (x8 in 0 until w8) {
            val m = f.mi(2 * y8 + 1, 2 * x8 + 1)
            val i = y8 * w8 + x8
            for (list in 0 until 2) {
                val r = if (list == 0) f.refFrames0[m] else f.refFrames1[m]
                if (r <= Av1.INTRA_FRAME) continue
                if (fh.relativeDist(fh.orderHints[r], fh.orderHint) >= 0) continue
                val mvRow = f.mvs[m * 4 + list * 2]
                val mvCol = f.mvs[m * 4 + list * 2 + 1]
                if (kotlin.math.abs(mvRow) <= REFMVS_LIMIT && kotlin.math.abs(mvCol) <= REFMVS_LIMIT) {
                    refFrames[i] = r.toByte()
                    mvs[i * 2] = mvRow.toShort()
                    mvs[i * 2 + 1] = mvCol.toShort()
                }
            }
        }
        return refFrames to mvs
    }

    /** SegmentIds as the frame leaves them: PrevSegmentIds when segmentation kept the previous map. */
    private fun savedSegmentIds(fh: Av1FrameHeader, f: Av1FrameDecoder): ByteArray {
        val out = ByteArray(fh.miRows * fh.miCols)
        if (fh.segmentationEnabled && !fh.segmentationUpdateMap) {
            f.prevSegmentIds?.copyInto(out)
            return out
        }
        for (row in 0 until fh.miRows) for (col in 0 until fh.miCols) out[row * fh.miCols + col] = f.segmentIds[f.mi(row, col)].toByte()
        return out
    }

    /**
     * A frame shown again from slot frame_to_show_map_idx, with its own film grain; a key frame
     * shown this way refreshes every slot (the reference frame loading process, then update).
     */
    private fun showExisting(fh: Av1FrameHeader) {
        val idx = fh.frameToShowMapIdx
        toolUse?.add("show existing frame")
        if (!refs.valid[idx]) throw ImageDecodeException("AV1: frame shown from empty reference slot $idx")
        lastSize = refs.upscaledWidth[idx] to refs.frameHeight[idx]
        val picture = refs.pictures[idx]
        if (fh.frameType == Av1FrameHeader.KEY_FRAME) {
            for (i in 0 until 8) {
                if (i == idx || (fh.refreshFrameFlags shr i) and 1 == 0) continue
                refs.copy(idx, i)
            }
        }
        if (picture != null && !headersOnly) output(fh, picture)
        frameHeader = null
    }

    /** The output process, with the film grain parameters kept for when the picture is handed out. */
    private fun output(fh: Av1FrameHeader, picture: Av1Picture) {
        val grain = if (picture.seq.filmGrainParamsPresent && fh.filmGrain.applyGrain) Av1FilmGrain().also { it.copyFrom(fh.filmGrain) } else null
        shown += Shown(picture, frameSpatialId, grain)
    }

    companion object {
        const val OBU_SEQUENCE_HEADER = 1
        const val OBU_TEMPORAL_DELIMITER = 2
        const val OBU_FRAME_HEADER = 3
        const val OBU_TILE_GROUP = 4
        const val OBU_METADATA = 5
        const val OBU_FRAME = 6
        const val OBU_REDUNDANT_FRAME_HEADER = 7
        const val OBU_TILE_LIST = 8
        const val OBU_PADDING = 15
        private const val REFMVS_LIMIT = (1 shl 12) - 1

        /**
         * The sequence header of [config] and [data] and what their frame headers need, without
         * decoding a tile: the size of the last frame, and why a frame cannot be decoded.
         */
        fun inspect(config: ByteArray?, data: ByteArray, operatingPoint: Int = 0): Av1Inspection {
            val d = Av1Decoder(operatingPoint)
            d.headersOnly = true
            if (config != null && config.isNotEmpty()) d.configure(config)
            d.run(data)
            val s = d.seq ?: throw ImageDecodeException("AV1: the data holds no sequence header")
            val (w, h) = d.lastSize ?: (s.maxFrameWidth to s.maxFrameHeight)
            return Av1Inspection(s, w, h, d.unsupported)
        }
    }
}
