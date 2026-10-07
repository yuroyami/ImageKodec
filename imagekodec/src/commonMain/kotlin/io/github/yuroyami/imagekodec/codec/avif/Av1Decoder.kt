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
    val planes: Array<IntArray>,
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
    private val store = arrayOfNulls<Av1Picture>(8)
    private var frameHeader: Av1FrameHeader? = null
    private var frame: Av1FrameDecoder? = null
    private var frameSpatialId = 0
    private var headersOnly = false
    private var unsupported: String? = null
    private val shown = ArrayList<Pair<Av1Picture, Int>>()
    private var lastSize: Pair<Int, Int>? = null

    /** The first frame [data]'s OBUs show; [data] may start with a sequence header or rely on an earlier one. */
    fun decode(data: ByteArray): Av1Picture {
        run(data)
        return shown.firstOrNull()?.first ?: throw ImageDecodeException("AV1: the data holds no shown frame")
    }

    /**
     * The frame an AVIF item shows (AV1 Image File Format section 2.3): the last shown frame,
     * or for [layer] other than 0xFFFF the last shown frame of that spatial layer.
     */
    fun decodeStill(config: ByteArray?, data: ByteArray, layer: Int): Av1Picture {
        if (config != null && config.isNotEmpty()) configure(config)
        run(data)
        val frames = if (layer == 0xFFFF) shown else shown.filter { it.second == layer }
        return frames.lastOrNull()?.first
            ?: throw ImageDecodeException(if (layer == 0xFFFF) "AV1: the data holds no shown frame" else "AV1: no frame of spatial layer $layer is shown")
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
        if (!fh.frameIsIntra) {
            if (headersOnly) {
                unsupported = unsupported ?: "AV1 inter frames"
            } else {
                throw UnsupportedImageException("AV1 inter frames")
            }
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
        frame = Av1FrameDecoder(s, fh, cdfs)
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
        val cdfs = f?.let { it.savedCdfs ?: it.frameCdfs }
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
            store[i] = picture
        }
    }

    /**
     * A frame shown again from slot frame_to_show_map_idx, with its own film grain; a key frame
     * shown this way refreshes every slot (the reference frame loading process, then update).
     */
    private fun showExisting(fh: Av1FrameHeader) {
        val idx = fh.frameToShowMapIdx
        if (!refs.valid[idx]) throw ImageDecodeException("AV1: frame shown from empty reference slot $idx")
        lastSize = refs.upscaledWidth[idx] to refs.frameHeight[idx]
        val picture = store[idx]
        if (fh.frameType == Av1FrameHeader.KEY_FRAME) {
            for (i in 0 until 8) {
                if (i == idx || (fh.refreshFrameFlags shr i) and 1 == 0) continue
                copySlot(idx, i)
            }
        }
        if (picture != null && !headersOnly) output(fh, picture)
        frameHeader = null
    }

    private fun copySlot(from: Int, to: Int) {
        refs.valid[to] = refs.valid[from]
        refs.frameId[to] = refs.frameId[from]
        refs.upscaledWidth[to] = refs.upscaledWidth[from]
        refs.frameWidth[to] = refs.frameWidth[from]
        refs.frameHeight[to] = refs.frameHeight[from]
        refs.renderWidth[to] = refs.renderWidth[from]
        refs.renderHeight[to] = refs.renderHeight[from]
        refs.miCols[to] = refs.miCols[from]
        refs.miRows[to] = refs.miRows[from]
        refs.frameType[to] = refs.frameType[from]
        refs.orderHint[to] = refs.orderHint[from]
        refs.savedOrderHints[from].copyInto(refs.savedOrderHints[to])
        refs.cdfs[to] = refs.cdfs[from]?.copy()
        for (ref in 0 until 8) refs.gmParams[from][ref].copyInto(refs.gmParams[to][ref])
        refs.loopFilterRefDeltas[from].copyInto(refs.loopFilterRefDeltas[to])
        refs.loopFilterModeDeltas[from].copyInto(refs.loopFilterModeDeltas[to])
        for (seg in 0 until 8) {
            refs.featureEnabled[from][seg].copyInto(refs.featureEnabled[to][seg])
            refs.featureData[from][seg].copyInto(refs.featureData[to][seg])
        }
        refs.grain[to].copyFrom(refs.grain[from])
        store[to] = store[from]
    }

    /** The output process: grain goes on the shown copy, never on the frame kept for reference. */
    private fun output(fh: Av1FrameHeader, picture: Av1Picture) {
        val s = picture.seq
        var planes = picture.planes
        if (s.filmGrainParamsPresent && fh.filmGrain.applyGrain) {
            val grain = Av1GrainSynthesis(fh.filmGrain, picture.bitDepth, picture.subX, picture.subY, planes.size, s.matrixCoefficients == 0)
            planes = grain.apply(planes, picture.strides, picture.width, picture.height)
        }
        shown += Av1Picture(picture.width, picture.height, picture.bitDepth, picture.subX, picture.subY, planes, picture.strides, s) to frameSpatialId
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
