package io.github.yuroyami.imagekodec.codec.avif

import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.UnsupportedImageException

/** An AV1 sequence header OBU (specification section 5.5) and its colour configuration. */
internal class Av1SequenceHeader(
    val profile: Int,
    val stillPicture: Boolean,
    val reducedStillPictureHeader: Boolean,
    val decoderModelInfoPresent: Boolean,
    val equalPictureInterval: Boolean,
    val bufferRemovalTimeLength: Int,
    val framePresentationTimeLength: Int,
    /** operating_point_idc and decoder_model_present_for_this_op for each operating point. */
    val operatingPointIdc: IntArray,
    val decoderModelPresentForOp: BooleanArray,
    val frameWidthBits: Int,
    val frameHeightBits: Int,
    val maxFrameWidth: Int,
    val maxFrameHeight: Int,
    val frameIdNumbersPresent: Boolean,
    val deltaFrameIdLength: Int,
    val additionalFrameIdLength: Int,
    val use128x128Superblock: Boolean,
    val enableFilterIntra: Boolean,
    val enableIntraEdgeFilter: Boolean,
    val enableInterintraCompound: Boolean,
    val enableMaskedCompound: Boolean,
    val enableWarpedMotion: Boolean,
    val enableDualFilter: Boolean,
    val enableOrderHint: Boolean,
    val enableJntComp: Boolean,
    val enableRefFrameMvs: Boolean,
    val seqForceScreenContentTools: Int,
    val seqForceIntegerMv: Int,
    val orderHintBits: Int,
    val enableSuperres: Boolean,
    val enableCdef: Boolean,
    val enableRestoration: Boolean,
    val bitDepth: Int,
    val monochrome: Boolean,
    val colorPrimaries: Int,
    val transferCharacteristics: Int,
    val matrixCoefficients: Int,
    val colorRange: Boolean,
    val subsamplingX: Int,
    val subsamplingY: Int,
    val chromaSamplePosition: Int,
    val separateUvDeltaQ: Boolean,
    val filmGrainParamsPresent: Boolean,
) {
    val numPlanes: Int get() = if (monochrome) 1 else 3

    companion object {
        const val SELECT_SCREEN_CONTENT_TOOLS = 2
        const val SELECT_INTEGER_MV = 2
        const val CP_BT_709 = 1
        const val CP_UNSPECIFIED = 2
        const val TC_UNSPECIFIED = 2
        const val TC_SRGB = 13
        const val MC_IDENTITY = 0
        const val MC_UNSPECIFIED = 2

        fun parse(r: Av1BitReader): Av1SequenceHeader {
            val profile = r.f(3)
            if (profile > 2) throw UnsupportedImageException("AV1 profile $profile")
            val stillPicture = r.flag()
            val reduced = r.flag()
            var decoderModelInfoPresent = false
            var equalPictureInterval = false
            var bufferDelayLength = 0
            var bufferRemovalTimeLength = 0
            var framePresentationTimeLength = 0
            val idc: IntArray
            val modelPresent: BooleanArray
            if (reduced) {
                if (!stillPicture) throw ImageDecodeException("AV1: a reduced still picture header on a sequence that is not a still picture")
                r.f(5) // seq_level_idx[0]
                idc = intArrayOf(0)
                modelPresent = booleanArrayOf(false)
            } else {
                val timingInfoPresent = r.flag()
                if (timingInfoPresent) {
                    r.f32() // num_units_in_display_tick
                    r.f32() // time_scale
                    equalPictureInterval = r.flag()
                    if (equalPictureInterval) r.uvlc()
                    decoderModelInfoPresent = r.flag()
                    if (decoderModelInfoPresent) {
                        bufferDelayLength = r.f(5) + 1
                        r.f32() // num_units_in_decoding_tick
                        bufferRemovalTimeLength = r.f(5) + 1
                        framePresentationTimeLength = r.f(5) + 1
                    }
                }
                val initialDisplayDelayPresent = r.flag()
                val count = r.f(5) + 1
                idc = IntArray(count)
                modelPresent = BooleanArray(count)
                for (i in 0 until count) {
                    idc[i] = r.f(12)
                    val level = r.f(5)
                    if (level > 7) r.f(1) // seq_tier
                    if (decoderModelInfoPresent) {
                        modelPresent[i] = r.flag()
                        if (modelPresent[i]) {
                            r.f(bufferDelayLength) // decoder_buffer_delay
                            r.f(bufferDelayLength) // encoder_buffer_delay
                            r.f(1) // low_delay_mode_flag
                        }
                    }
                    if (initialDisplayDelayPresent && r.flag()) r.f(4)
                }
            }
            val widthBits = r.f(4) + 1
            val heightBits = r.f(4) + 1
            val maxWidth = r.f(widthBits) + 1
            val maxHeight = r.f(heightBits) + 1
            val frameIdNumbersPresent = if (reduced) false else r.flag()
            var deltaFrameIdLength = 0
            var additionalFrameIdLength = 0
            if (frameIdNumbersPresent) {
                deltaFrameIdLength = r.f(4) + 2
                additionalFrameIdLength = r.f(3) + 1
            }
            val sb128 = r.flag()
            val filterIntra = r.flag()
            val intraEdge = r.flag()
            var interintra = false
            var masked = false
            var warped = false
            var dualFilter = false
            var orderHint = false
            var jnt = false
            var refFrameMvs = false
            var screenContent = SELECT_SCREEN_CONTENT_TOOLS
            var integerMv = SELECT_INTEGER_MV
            var orderHintBits = 0
            if (!reduced) {
                interintra = r.flag()
                masked = r.flag()
                warped = r.flag()
                dualFilter = r.flag()
                orderHint = r.flag()
                if (orderHint) {
                    jnt = r.flag()
                    refFrameMvs = r.flag()
                }
                screenContent = if (r.flag()) SELECT_SCREEN_CONTENT_TOOLS else r.f(1)
                integerMv = if (screenContent > 0) {
                    if (r.flag()) SELECT_INTEGER_MV else r.f(1)
                } else {
                    SELECT_INTEGER_MV
                }
                if (orderHint) orderHintBits = r.f(3) + 1
            }
            val superres = r.flag()
            val cdef = r.flag()
            val restoration = r.flag()
            // color_config( )
            val highBitdepth = r.flag()
            val bitDepth = when {
                profile == 2 && highBitdepth -> if (r.flag()) 12 else 10
                else -> if (highBitdepth) 10 else 8
            }
            val mono = if (profile == 1) false else r.flag()
            var primaries = CP_UNSPECIFIED
            var transfer = TC_UNSPECIFIED
            var matrix = MC_UNSPECIFIED
            if (r.flag()) {
                primaries = r.f(8)
                transfer = r.f(8)
                matrix = r.f(8)
            }
            val range: Boolean
            var ssx: Int
            var ssy: Int
            var chromaSamplePosition = 0
            var separateUvDeltaQ = false
            if (mono) {
                range = r.flag()
                ssx = 1
                ssy = 1
            } else {
                if (primaries == CP_BT_709 && transfer == TC_SRGB && matrix == MC_IDENTITY) {
                    range = true
                    ssx = 0
                    ssy = 0
                } else {
                    range = r.flag()
                    when (profile) {
                        0 -> { ssx = 1; ssy = 1 }
                        1 -> { ssx = 0; ssy = 0 }
                        else -> if (bitDepth == 12) {
                            ssx = r.f(1)
                            ssy = if (ssx == 1) r.f(1) else 0
                        } else {
                            ssx = 1
                            ssy = 0
                        }
                    }
                    if (ssx == 1 && ssy == 1) chromaSamplePosition = r.f(2)
                }
                separateUvDeltaQ = r.flag()
            }
            val grain = r.flag()
            return Av1SequenceHeader(
                profile, stillPicture, reduced, decoderModelInfoPresent, equalPictureInterval,
                bufferRemovalTimeLength, framePresentationTimeLength, idc, modelPresent,
                widthBits, heightBits, maxWidth, maxHeight, frameIdNumbersPresent,
                deltaFrameIdLength, additionalFrameIdLength, sb128, filterIntra, intraEdge,
                interintra, masked, warped, dualFilter, orderHint, jnt, refFrameMvs,
                screenContent, integerMv, orderHintBits, superres, cdef, restoration,
                bitDepth, mono, primaries, transfer, matrix, range, ssx, ssy,
                chromaSamplePosition, separateUvDeltaQ, grain,
            )
        }
    }
}
