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
}
