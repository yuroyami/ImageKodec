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
    fun reason(h: HevcHeaders): String? {
        h.slice?.let { return sliceReason(it) }
        return firstUnsupportedField(h.sps, h.pps, null)?.let { "HEVC: $it is not supported yet" }
    }

    /** Why the slice segment [h] cannot be decoded, or null. */
    fun sliceReason(h: SliceHeader): String? = firstUnsupportedField(h.sps, h.pps, h)?.let { "HEVC: $it is not supported yet" }

    private fun firstUnsupportedField(s: Sps, p: Pps?, slice: SliceHeader?): String? = when {
        s.ctbLog2 < 4 -> "a CTB of ${s.ctbSize} samples (log2_diff_max_min_luma_coding_block_size)"
        s.separateColourPlane -> "separate_colour_plane_flag"
        s.bitDepthY > 12 -> "bit_depth_luma_minus8 ${s.bitDepthY - 8}"
        s.bitDepthC > 12 -> "bit_depth_chroma_minus8 ${s.bitDepthC - 8}"
        s.transformSkipRotation -> "transform_skip_rotation_enabled_flag"
        s.transformSkipContext -> "transform_skip_context_enabled_flag"
        s.implicitRdpcm -> "implicit_rdpcm_enabled_flag"
        s.explicitRdpcm -> "explicit_rdpcm_enabled_flag"
        s.extendedPrecision -> "extended_precision_processing_flag"
        s.intraSmoothingDisabled -> "intra_smoothing_disabled_flag"
        s.persistentRiceAdaptation -> "persistent_rice_adaptation_enabled_flag"
        s.cabacBypassAlignment -> "cabac_bypass_alignment_enabled_flag"
        s.paletteMode -> "palette_mode_enabled_flag"
        s.intraBoundaryFilteringDisabled -> "intra_boundary_filtering_disabled_flag"
        p != null && p.log2MaxTransformSkipSize > 2 -> "log2_max_transform_skip_block_size_minus2 ${p.log2MaxTransformSkipSize - 2}"
        p != null && p.crossComponentPrediction -> "cross_component_prediction_enabled_flag"
        p != null && p.chromaQpOffsetListEnabled -> "chroma_qp_offset_list_enabled_flag"
        p != null && p.currPicRefEnabled -> "pps_curr_pic_ref_enabled_flag"
        p != null && p.residualAdaptiveColourTransform -> "residual_adaptive_colour_transform_enabled_flag"
        slice != null && slice.sliceType != SliceType.I -> "slice_type ${slice.sliceType}"
        else -> null
    }
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
            bitDepthChroma = s.bitDepthC,
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
     * carries, stay for the samples after it. A picture the decoder lacks a tool for throws
     * UnsupportedImageException with the reason [inspect] gives.
     */
    override fun open(configuration: ByteArray?): HeifCodecSession {
        val config = configuration?.let { HevcConfig.parse(it) }
        val decoder = HevcDecoder()
        config?.nalUnits?.forEach { decoder.decodeNal(it, 0, it.size, null) }
        return object : HeifCodecSession {
            override fun decode(sample: ByteArray, cancellationCheck: (() -> Unit)?): HeifPlanes? {
                cancellationCheck?.invoke()
                for (range in splitSample(sample, config?.lengthSize ?: 4)) decoder.decodeNal(sample, range.first, range.last + 1, cancellationCheck)
                return decoder.finish().lastOrNull()?.toHeifPlanes()
            }
        }
    }
}
