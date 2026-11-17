package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.HeifPlanes
import io.github.yuroyami.imagekodec.UnsupportedImageException

/** decoded_picture_hash( ), D.2.20: [hashType] 0 (MD5), 1 (CRC) or 2 (checksum), and the hash of each component, big-endian. */
internal class PictureHashSei(val hashType: Int, val values: Array<ByteArray>)

/** A decoded picture before the conformance window, and the picture hash SEI that followed its slices, if any. */
internal class DecodedPicture(val sps: Sps, val planes: Array<ShortArray>, val strides: IntArray) {
    var hash: PictureHashSei? = null

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
        return HeifPlanes(w, h, s.bitDepthY, s.bitDepthC, subW, subH, out, strides, fullRange)
    }
}

/**
 * Decodes the NAL units of a stream, one at a time and in order, into pictures. Parameter sets stay
 * between calls, so a HEIF session hands it the units of hvcC once and then those of each sample.
 * With [checkLoopFilters] false, a picture whose slices turn on the loop filters is decoded without
 * them, which only a test of the parsing wants.
 */
internal class HevcDecoder(private val checkLoopFilters: Boolean = true) {
    private val store = ParameterSetStore()
    private var picture: PictureDecoder? = null
    private var current: DecodedPicture? = null
    private var previous: SliceHeader? = null
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
                previous = h
                if (h.firstSliceSegmentInPic) {
                    finishPicture()
                    val p = PictureDecoder(h.sps, h.pps)
                    picture = p
                    current = DecodedPicture(h.sps, p.planes, p.strides)
                }
                val p = picture ?: hevcError("a slice segment has no first_slice_segment_in_pic_flag before it")
                // 7.4.7.1: the slice segments of a picture share one PPS.
                if (h.pps !== p.pps) hevcError("the slice segments of a picture name different PPSs")
                HevcSupport.sliceReason(h, checkLoopFilters)?.let { throw UnsupportedImageException(it) }
                p.decodeSliceSegment(h, rbsp, emulationPreventionBytes(bytes, from, to), cancel)
            }
            nal.type == NalType.SUFFIX_SEI -> current?.let { readSei(r, it) }
            else -> store.add(nal, r)
        }
    }

    /** The pictures completed since the last call: every picture whose slices have all arrived. */
    fun finish(): List<DecodedPicture> {
        finishPicture()
        val out = ArrayList(done)
        done.clear()
        return out
    }

    private fun finishPicture() {
        val p = picture ?: return
        val total = p.sps.picSizeInCtbs
        if (p.decodedCtbs != total) hevcError("the slice segments of a picture hold ${p.decodedCtbs} of its $total CTBs")
        done += current!!
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
