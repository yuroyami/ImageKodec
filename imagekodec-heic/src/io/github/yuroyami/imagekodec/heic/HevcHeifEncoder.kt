package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.HeifCodedPicture
import io.github.yuroyami.imagekodec.HeifEncodeParameters
import io.github.yuroyami.imagekodec.HeifEncoder
import io.github.yuroyami.imagekodec.HeifPlanes

/** The intra [HevcEncoder] as the encoder of `hvc1` items, for the core's HEIF writer (#174). */
internal object HevcHeifEncoder : HeifEncoder {
    override val itemType: String = "hvc1"
    override val configurationType: String = "hvcC"

    // The alpha URN of ISO/IEC 23008-12 for HEVC auxiliary pictures, which libheif and Apple write and read.
    override val alphaUrn: String = "urn:mpeg:hevc:2015:auxid:1"

    override fun encode(planes: HeifPlanes, parameters: HeifEncodeParameters, cancellationCheck: (() -> Unit)?): HeifCodedPicture =
        encode(planes, parameters, HevcEncoderSettings(quality = parameters.quality, lossless = parameters.lossless, effort = parameters.effort), cancellationCheck)

    /** [planes] coded with [settings] in place of the ones [parameters] give: for tests that fix the QP. */
    fun encode(planes: HeifPlanes, parameters: HeifEncodeParameters, settings: HevcEncoderSettings, cancellationCheck: (() -> Unit)?): HeifCodedPicture {
        val colour = parameters.colorPrimaries?.let { p ->
            intArrayOf(p, parameters.transferCharacteristics ?: 2, parameters.matrixCoefficients ?: 2)
        }
        val coded = HevcEncoder.encode(planes, planes.fullRange, colour, settings, cancellationCheck)
        // ISO/IEC 23008-12 B.4.1: 'heic' for the Main and Main Still Picture profiles, 'heix' for Main 10 and
        // the format range extensions profiles.
        val brand = if (coded.profile == "Main Still Picture") "heic" else "heix"
        return HeifCodedPicture(coded.hvcC, coded.sample, coded.width, coded.height, brand)
    }

    /**
     * [frames], each shown for its entry of [durations], as one HEVC sequence for an image sequence track of HEIF
     * (#177), coded with the quality, lossless coding, effort and colour of [parameters] and the order of
     * [sequence]. The sequence's samples, `hvcC`, timing and sync samples are what a `trak` needs; its brand is
     * [sequenceBrand]. The frames are planes as [encode] takes them, all of one size and format.
     */
    fun encodeSequence(
        frames: List<HeifPlanes>, durations: LongArray, parameters: HeifEncodeParameters,
        sequence: HevcSequenceSettings = HevcSequenceSettings(), cancellationCheck: (() -> Unit)?,
    ): HevcSequence {
        val colour = parameters.colorPrimaries?.let { p ->
            intArrayOf(p, parameters.transferCharacteristics ?: 2, parameters.matrixCoefficients ?: 2)
        }
        val settings = HevcEncoderSettings(quality = parameters.quality, lossless = parameters.lossless, effort = parameters.effort)
        return HevcEncoder.encodeSequence(frames, durations, frames.firstOrNull()?.fullRange ?: false, colour, settings, sequence, cancellationCheck)
    }

    /**
     * The brand of ISO/IEC 23008-12 B.4.2 for an image sequence of [sequence]: `hevc` for the Main profile, `hevx`
     * for Main 10 and the format range extensions profiles.
     */
    fun sequenceBrand(sequence: HevcSequence): String = if (sequence.profile == "Main") "hevc" else "hevx"
}
