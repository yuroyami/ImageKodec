package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.HeifCodec
import io.github.yuroyami.imagekodec.HeifCodecInfo
import io.github.yuroyami.imagekodec.HeifCodecSession
import io.github.yuroyami.imagekodec.HeifPlanes
import io.github.yuroyami.imagekodec.UnsupportedImageException

/** The headers of the first picture of an item or a sample: its SPS, and its PPS and slice when the data has one. */
internal class HevcHeaders(val sps: Sps, val pps: Pps?, val slice: SliceHeader?)

/** Reads the parameter sets and the first slice segment header of HEIF data. */
internal object HevcHeaderReader {

    /**
     * The headers of [data], with the parameter sets of [config] first. Parameter sets may also sit in
     * the sample data, as a Sony camera writes them (#140). Returns null when there is no SPS at all.
     */
    fun read(config: HevcConfig?, data: ByteArray?): HevcHeaders? {
        val store = ParameterSetStore()
        var firstSps: Sps? = null
        fun parameterSet(bytes: ByteArray, from: Int, to: Int): SliceHeader? {
            val rbsp = unescapeNal(bytes, from, to)
            val r = BitReader(rbsp)
            val nal = readNalHeader(r)
            if (nal.layerId != 0) return null
            if (NalType.isSlice(nal.type)) return readSliceHeader(r, nal, store, null, to - from)
            val set = store.add(nal, r)
            if (set is Sps && firstSps == null) firstSps = set
            return null
        }
        config?.nalUnits?.forEach { parameterSet(it, 0, it.size) }
        if (data != null) {
            for (range in splitSample(data, config?.lengthSize ?: 4)) {
                val slice = parameterSet(data, range.first, range.last + 1) ?: continue
                return HevcHeaders(slice.sps, slice.pps, slice)
            }
        }
        return firstSps?.let { HevcHeaders(it, null, null) }
    }
}

/**
 * Which pictures the decoder handles, decided from the header fields and never from the profile:
 * Apple's alpha and gain map images are of the Main Intra profile in 4:0:0, for one (#140).
 */
internal object HevcSupport {
    /**
     * Why the picture of [h] cannot be decoded, or null when it can. Without a slice header the
     * parameter sets decide. Each issue that adds a tool removes its line here.
     */
    fun reason(h: HevcHeaders): String? = spsReason(h.slice?.sps ?: h.sps)

    /** Why the slice segment [h] cannot be decoded, or null. */
    fun sliceReason(h: SliceHeader): String? = spsReason(h.sps)

    /**
     * Every tool of the parameter sets decodes since the screen content coding tools and separate colour planes
     * (#188), but for a CTB of 8 samples, which every profile of Annex A rules out (CtbLog2SizeY of 4 to 6).
     */
    private fun spsReason(s: Sps): String? =
        if (s.ctbLog2 < 4) "HEVC: a CTB of ${s.ctbSize} samples (log2_diff_max_min_luma_coding_block_size) is not supported yet" else null
}

/** The HEVC codec of `hvc1` items and of `hvc1` and `hev1` tracks, for the core's HEIF reader. */
internal object HevcCodec : HeifCodec {
    override val itemType: String = "hvc1"
    override val sampleEntryTypes: Set<String> = setOf("hvc1", "hev1")
    override val configurationType: String = "hvcC"

    override fun inspect(configuration: ByteArray?, data: ByteArray?): HeifCodecInfo {
        val config = configuration?.let { HevcConfig.parse(it) }
        val headers = try {
            HevcHeaderReader.read(config, data)
        } catch (e: UnsupportedImageException) {
            // A header this parser cannot follow: the fixed fields of hvcC still give the depths.
            return fromConfig(config, e.message)
        }
        if (headers == null) {
            // Probing a depth reads hvcC alone, whose SPS may come with the samples; any other caller needs an SPS.
            if (data == null && config != null) return fromConfig(config, null)
            hevcError("no sequence parameter set")
        }
        val s = headers.sps
        val vui = s.vui
        val colour = vui?.colourDescriptionPresent == true
        return HeifCodecInfo(
            width = s.croppedWidth,
            height = s.croppedHeight,
            bitDepthLuma = s.bitDepthY,
            bitDepthChroma = s.planeBitDepth(1),
            chromaFormat = s.chromaFormatIdc,
            colorPrimaries = if (colour) vui.colourPrimaries else null,
            transferCharacteristics = if (colour) vui.transferCharacteristics else null,
            matrixCoefficients = if (colour) vui.matrixCoeffs else null,
            fullRange = if (vui?.videoSignalTypePresent == true) vui.fullRange else null,
            unsupportedReason = HevcSupport.reason(headers),
        )
    }

    private fun fromConfig(config: HevcConfig?, reason: String?) = HeifCodecInfo(
        0, 0, config?.bitDepthLuma ?: 8, config?.bitDepthChroma ?: 8, config?.chromaFormat ?: 1,
        unsupportedReason = reason,
    )

    /**
     * A session that decodes each sample into one picture. The parameter sets of hvcC, and any a sample
     * carries, stay for the samples after it, and so do the decoded pictures a later sample refers to. A
     * picture the decoder lacks a tool for throws UnsupportedImageException with the reason [inspect] gives.
     */
    override fun open(configuration: ByteArray?): HeifCodecSession = HevcSession(configuration?.let { HevcConfig.parse(it) })
}

/**
 * The samples of an item or a track, decoded in order by one [HevcDecoder], so the decoded picture
 * buffer carries the reference pictures from sample to sample. [decode] returns the picture each sample
 * codes, in decoding order; [decodeInOutputOrder] and [flush] return the pictures in output order, as
 * the "bumping" process of C.5.2.4 outputs them. The buffer keeps what it outputs until a call takes it,
 * and [decode] drops what it does not return, so a caller that mixes the two holds nothing it does not use.
 */
internal class HevcSession(private val config: HevcConfig?) : HeifCodecSession {
    private val decoder = HevcDecoder(keepOutput = true)

    init {
        config?.nalUnits?.forEach { decoder.decodeNal(it, 0, it.size, null) }
    }

    private fun decodeNals(sample: ByteArray, cancellationCheck: (() -> Unit)?): List<DecodedPicture> {
        cancellationCheck?.invoke()
        for (range in splitSample(sample, config?.lengthSize ?: 4)) decoder.decodeNal(sample, range.first, range.last + 1, cancellationCheck)
        return decoder.finish()
    }

    override fun decode(sample: ByteArray, cancellationCheck: (() -> Unit)?): HeifPlanes? {
        val decoded = decodeNals(sample, cancellationCheck)
        decoder.takeOutput()
        return decoded.lastOrNull()?.toHeifPlanes()
    }

    override fun decodeInOutputOrder(sample: ByteArray, cancellationCheck: (() -> Unit)?): List<HeifPlanes> {
        decodeNals(sample, cancellationCheck)
        return decoder.takeOutput().map { it.toHeifPlanes() }
    }

    override fun flush(): List<HeifPlanes> = decoder.flush().map { it.toHeifPlanes() }
}
