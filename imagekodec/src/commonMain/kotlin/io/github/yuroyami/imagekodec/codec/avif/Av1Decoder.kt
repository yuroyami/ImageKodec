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

/**
 * Decodes the OBUs of an AV1 still picture or of the first shown frame of a sequence
 * (specification section 7), from a low overhead bitstream: the configuration OBUs of
 * an `av1C` box followed by a sample's.
 */
internal class Av1Decoder {
    private var seq: Av1SequenceHeader? = null
    private val refs = Av1RefSlots()
    private var frameHeader: Av1FrameHeader? = null
    private var frame: Av1FrameDecoder? = null
    private var tileNum = 0
    private var shown: Av1Picture? = null

    /** The first frame [data]'s OBUs show; [data] may start with a sequence header or rely on an earlier one. */
    fun decode(data: ByteArray): Av1Picture {
        var pos = 0
        while (pos < data.size && shown == null) {
            pos = obu(data, pos)
        }
        return shown ?: throw ImageDecodeException("AV1: the data holds no shown frame")
    }

    /** Reads the OBUs of [data] for their sequence header only, as an `av1C` box carries one. */
    fun configure(data: ByteArray) {
        var pos = 0
        while (pos < data.size) pos = obu(data, pos, headersOnly = true)
    }

    private fun obu(data: ByteArray, start: Int, headersOnly: Boolean = false): Int {
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
        val idc = seq?.operatingPointIdc?.get(0) ?: 0
        if (type != OBU_SEQUENCE_HEADER && type != OBU_TEMPORAL_DELIMITER && idc != 0 && extension) {
            val inTemporal = (idc shr temporalId) and 1
            val inSpatial = (idc shr (spatialId + 8)) and 1
            if (inTemporal == 0 || inSpatial == 0) return end
        }
        val pr = Av1BitReader(data, payload, end)
        when (type) {
            OBU_SEQUENCE_HEADER -> seq = Av1SequenceHeader.parse(pr)
            OBU_TEMPORAL_DELIMITER -> frameHeader = null
            OBU_FRAME_HEADER, OBU_REDUNDANT_FRAME_HEADER -> if (!headersOnly) frameHeaderObu(pr, temporalId, spatialId)
            OBU_FRAME -> if (!headersOnly) {
                val startBit = pr.position
                frameHeaderObu(pr, temporalId, spatialId)
                pr.byteAlignment()
                tileGroupObu(data, pr, end)
                if (pr.position < startBit) throw ImageDecodeException("AV1: frame OBU cut off")
            }
            OBU_TILE_GROUP -> if (!headersOnly) tileGroupObu(data, pr, end)
            OBU_TILE_LIST -> throw UnsupportedImageException("AV1 large scale tile lists")
            else -> {}
        }
        return end
    }

    private fun frameHeaderObu(r: Av1BitReader, temporalId: Int, spatialId: Int) {
        if (frameHeader != null) return // frame_header_copy( ): a repeat of the header being decoded.
        val s = seq ?: throw ImageDecodeException("AV1: a frame header before the sequence header")
        val fh = Av1FrameHeader.parse(r, s, refs, temporalId, spatialId)
        if (fh.showExistingFrame) {
            throw UnsupportedImageException("AV1 frames shown from a reference slot")
        }
        frameHeader = fh
        tileNum = 0
        val cdfs = if (fh.primaryRefFrame == Av1FrameHeader.PRIMARY_REF_NONE) {
            Av1Cdfs.initial(fh.baseQIdx)
        } else {
            val saved = refs.cdfs[fh.refFrameIdx[fh.primaryRefFrame]] ?: throw ImageDecodeException("AV1: a primary reference frame with no CDFs")
            saved.copy().also { it.clearCounters() }
        }
        if (!fh.frameIsIntra) throw UnsupportedImageException("AV1 inter frames")
        frame = Av1FrameDecoder(s, fh, cdfs)
    }

    private fun tileGroupObu(data: ByteArray, r: Av1BitReader, end: Int) {
        val fh = frameHeader ?: throw ImageDecodeException("AV1: a tile group before its frame header")
        val f = frame!!
        val numTiles = fh.tileCols * fh.tileRows
        val startBit = r.position
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
        tileNum = tgEnd + 1
        if (startBit < 0) throw IllegalStateException()
        if (tgEnd == numTiles - 1) frameEnd(f, fh)
    }

    /** decode_frame_wrapup( ): the post filters, the reference update and the output of a shown frame. */
    private fun frameEnd(f: Av1FrameDecoder, fh: Av1FrameHeader) {
        Av1PostFilter.apply(f)
        val s = f.seq
        if (fh.showFrame) {
            var planes = f.outputPlanes()
            val strides = f.outputStrides()
            // The output process: grain goes on the shown copy, never on the frame kept for reference.
            if (s.filmGrainParamsPresent && fh.filmGrain.applyGrain) {
                val grain = Av1GrainSynthesis(fh.filmGrain, s.bitDepth, s.subsamplingX, s.subsamplingY, s.numPlanes, s.matrixCoefficients == 0)
                planes = grain.apply(planes, strides, fh.upscaledWidth, fh.frameHeight)
            }
            shown = Av1Picture(fh.upscaledWidth, fh.frameHeight, s.bitDepth, s.subsamplingX, s.subsamplingY, planes, strides, s)
        }
        frameHeader = null
        frame = null
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
    }
}
