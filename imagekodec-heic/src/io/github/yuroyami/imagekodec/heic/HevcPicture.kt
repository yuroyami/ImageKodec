package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.HeifPlanes
import io.github.yuroyami.imagekodec.UnsupportedImageException

/** decoded_picture_hash( ), D.2.20: [hashType] 0 (MD5), 1 (CRC) or 2 (checksum), and the hash of each component, big-endian. */
internal class PictureHashSei(val hashType: Int, val values: Array<ByteArray>)

/**
 * A decoded picture before the conformance window, and the picture hash SEI that followed its slices, if
 * any, with what the decoded picture buffer keeps of it (C.5.2): its picture order count, its marking, its
 * output state and the motion that later pictures take temporal candidates from.
 */
internal class DecodedPicture(val sps: Sps, val planes: Array<ShortArray>, val strides: IntArray) {
    var hash: PictureHashSei? = null
    /** PicOrderCntVal (8.3.1). */
    var poc: Int = 0
    /** UNUSED, SHORT_TERM or LONG_TERM: "unused for reference", "used for short-term reference" or "used for long-term reference". */
    var reference: Int = UNUSED
    /** PicOutputFlag (8.1.3). */
    var outputFlag: Boolean = true
    /** Marked "needed for output" (C.5.2.3). */
    var neededForOutput: Boolean = false
    /** PicLatencyCount (C.5.2.3). */
    var latency: Int = 0
    /**
     * The motion for temporal candidates of each colour plane that is decoded on its own: one entry, or three
     * under separate_colour_plane_flag. An entry is null when every block of its plane is coded in intra mode.
     */
    var motion: Array<CollocatedMotion?> = arrayOfNulls(1)

    companion object {
        const val UNUSED = 0
        const val SHORT_TERM = 1
        const val LONG_TERM = 2
    }

    /** The planes cropped to the conformance window (7.4.3.2.1), as the core takes them. */
    fun toHeifPlanes(): HeifPlanes {
        val s = sps
        val subW = if (s.chromaArrayType == 1 || s.chromaArrayType == 2) 1 else 0
        val subH = if (s.chromaArrayType == 1) 1 else 0
        val left = s.subWidthC * s.confWin[0]
        val top = s.subHeightC * s.confWin[2]
        val w = s.croppedWidth
        val h = s.croppedHeight
        val out = Array(planes.size) { c ->
            val cw = if (c == 0) w else w shr subW
            val ch = if (c == 0) h else h shr subH
            val x0 = if (c == 0) left else left shr subW
            val y0 = if (c == 0) top else top shr subH
            val p = ShortArray(cw * ch)
            for (y in 0 until ch) planes[c].copyInto(p, y * cw, (y0 + y) * strides[c] + x0, (y0 + y) * strides[c] + x0 + cw)
            p
        }
        val strides = IntArray(planes.size) { c -> if (c == 0) w else w shr subW }
        val fullRange = s.vui?.let { it.videoSignalTypePresent && it.fullRange } ?: false
        return HeifPlanes(w, h, s.bitDepthY, s.planeBitDepth(1), subW, subH, out, strides, fullRange)
    }
}

/**
 * Decodes the NAL units of a stream, one at a time and in order, into pictures. Parameter sets and the
 * decoded picture buffer stay between calls, so a HEIF session hands it the units of hvcC once and then
 * those of each sample, and a picture may refer to the pictures of the samples before it.
 *
 * [finish] gives the pictures in decoding order, which is what a caller that shows each sample as it
 * comes wants. With [keepOutput], [takeOutput] and [flush] give them in output order, as the "bumping"
 * process of C.5.2.4 outputs them.
 */
internal class HevcDecoder(keepOutput: Boolean = false) {
    private val store = ParameterSetStore()
    private val buffer = PictureBuffer(keepOutput)
    /** The picture being decoded: one decoder, or one for each colour plane under separate_colour_plane_flag. */
    private var picture: Array<PictureDecoder>? = null
    private var current: DecodedPicture? = null
    private var previous: SliceHeader? = null
    /** PicOrderCntVal of the picture being decoded. */
    private var poc = 0
    /** The slices of a RASL picture that is not decoded are skipped up to the next picture. */
    private var skipping = false
    private val done = ArrayList<DecodedPicture>()

    /** Decodes the NAL unit of [bytes] from [from] to [to]. [cancel] runs once per CTB row. */
    fun decodeNal(bytes: ByteArray, from: Int, to: Int, cancel: (() -> Unit)?) {
        val rbsp = unescapeNal(bytes, from, to)
        val r = BitReader(rbsp)
        val nal = readNalHeader(r)
        if (nal.layerId != 0) return
        when {
            NalType.isSlice(nal.type) -> {
                val h = readSliceHeader(r, nal, store, previous, to - from)
                if (h.firstSliceSegmentInPic) {
                    finishPicture()
                    HevcSupport.sliceReason(h)?.let { throw UnsupportedImageException(it) }
                    val pictureOrder = buffer.beginPicture(h)
                    skipping = pictureOrder == null
                    if (pictureOrder == null) return
                    poc = pictureOrder
                    // 8.1.3: three separate colour planes are three monochrome pictures, each decoded alone.
                    val decoders = Array(if (h.sps.separateColourPlane) 3 else 1) { k -> PictureDecoder(h.sps, h.pps, k) }
                    picture = decoders
                    val planes = if (decoders.size == 1) decoders[0].planes else Array(3) { k -> decoders[k].planes[0] }
                    val strides = if (decoders.size == 1) decoders[0].strides else IntArray(3) { k -> decoders[k].strides[0] }
                    current = DecodedPicture(h.sps, planes, strides).also {
                        it.poc = pictureOrder
                        it.outputFlag = h.picOutput
                    }
                } else if (skipping) {
                    return
                }
                previous = h
                val decoders = picture ?: hevcError("a slice segment has no first_slice_segment_in_pic_flag before it")
                // colour_plane_id is 0 without separate colour planes, and is read only with them.
                val p = decoders.getOrNull(h.colourPlaneId) ?: hevcError("colour_plane_id ${h.colourPlaneId} in a picture without separate colour planes")
                // 7.4.7.1: the slice segments of a picture share one PPS.
                if (h.pps !== p.pps || h.sps !== p.sps) hevcError("the slice segments of a picture name different PPSs")
                HevcSupport.sliceReason(h)?.let { throw UnsupportedImageException(it) }
                if (h.sliceType != SliceType.I) h.refs = if (h.dependentSliceSegment) h.refs else buffer.refLists(h, poc, current!!)
                p.decodeSliceSegment(h, rbsp, emulationPreventionBytes(bytes, from, to), cancel)
            }
            nal.type == NalType.SUFFIX_SEI -> current?.let { readSei(r, it) }
            nal.type == NalType.EOS -> {
                finishPicture()
                buffer.endOfSequence()
            }
            else -> store.add(nal, r)
        }
    }

    /** The pictures completed since the last call, in decoding order: every picture whose slices have all arrived. */
    fun finish(): List<DecodedPicture> {
        finishPicture()
        val out = ArrayList(done)
        done.clear()
        return out
    }

    /** The pictures the decoded picture buffer has output since the last call, in output order (needs keepOutput). */
    fun takeOutput(): List<DecodedPicture> = buffer.takeOutput()

    /** Ends the stream: completes the last picture and outputs every picture still waiting, in output order. */
    fun flush(): List<DecodedPicture> {
        finishPicture()
        buffer.flush()
        return buffer.takeOutput()
    }

    private fun finishPicture() {
        val decoders = picture ?: return
        for (p in decoders) {
            val total = p.sps.picSizeInCtbs
            val plane = if (decoders.size > 1) " of colour plane ${p.colourPlane}" else ""
            if (p.decodedCtbs != total) hevcError("the slice segments of a picture hold ${p.decodedCtbs} of its $total CTBs$plane")
        }
        for (p in decoders) p.finishLoopFilters()
        val c = current!!
        c.motion = Array(decoders.size) { k -> decoders[k].collocatedMotion() }
        buffer.endPicture(c)
        done += c
        picture = null
        current = null
    }

    /** sei_rbsp( ), 7.3.2.4, keeping only the decoded picture hash (payloadType 132). */
    private fun readSei(r: BitReader, picture: DecodedPicture) {
        while (r.moreRbspData()) {
            var type = 0
            var b = r.u(8, "last_payload_type_byte")
            while (b == 0xFF) {
                type += 255
                b = r.u(8, "last_payload_type_byte")
            }
            type += b
            var size = 0
            b = r.u(8, "last_payload_size_byte")
            while (b == 0xFF) {
                size += 255
                b = r.u(8, "last_payload_size_byte")
            }
            size += b
            if (size.toLong() * 8 > r.bitsLeft) hevcError("an SEI payload of $size bytes passes the end of its NAL unit")
            val start = r.pos
            if (type == 132) {
                val hashType = r.u(8, "hash_type")
                val length = when (hashType) { 0 -> 16; 1 -> 2; 2 -> 4; else -> 0 }
                val comps = if (picture.sps.chromaFormatIdc == 0) 1 else 3
                if (length > 0 && comps * length + 1 <= size) {
                    picture.hash = PictureHashSei(hashType, Array(comps) { ByteArray(length) { r.u(8, "picture_hash").toByte() } })
                }
            }
            r.skip(start + size * 8 - r.pos, "sei_payload")
        }
    }
}
