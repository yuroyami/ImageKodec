package io.github.yuroyami.imagekodec.codec.avif

import io.github.yuroyami.imagekodec.ExperimentalHeifCodecApi
import io.github.yuroyami.imagekodec.HeifCodedPicture
import io.github.yuroyami.imagekodec.HeifEncodeParameters
import io.github.yuroyami.imagekodec.HeifEncoder
import io.github.yuroyami.imagekodec.HeifPlanes

/**
 * The core's AV1 encoder ([Av1Encoder]) as the HEIF encoder of `av01` items (#176): the AVIF of AV1 Image File
 * Format 1.1. The configuration is `av1C` with the sequence header OBU (AV1-ISOBMFF section 2.3), and the item's
 * data is the sequence header and the frame OBU, as libavif writes a still image.
 */
@OptIn(ExperimentalHeifCodecApi::class)
internal object Av1HeifEncoder : HeifEncoder {
    override val itemType: String = "av01"
    override val configurationType: String = "av1C"
    override val alphaUrn: String = "urn:mpeg:mpegB:cicp:systems:auxiliary:alpha"

    override fun encode(planes: HeifPlanes, parameters: HeifEncodeParameters, cancellationCheck: (() -> Unit)?): HeifCodedPicture {
        require(planes.width in 1..65536 && planes.height in 1..65536) { "AV1: a picture of ${planes.width} by ${planes.height}" }
        require(planes.bitDepthLuma in intArrayOf(8, 10, 12) && planes.bitDepthChroma == planes.bitDepthLuma) {
            "AV1 codes 8, 10 or 12 bits a sample, not ${planes.bitDepthLuma}"
        }
        require(planes.planes.size == 1 || planes.planes.size == 3) { "AV1: ${planes.planes.size} planes" }
        if (planes.planes.size == 3) {
            require(planes.subX in 0..1 && planes.subY in 0..planes.subX) { "AV1 has no chroma subsampled by ${planes.subX} and ${planes.subY}" }
            require(planes.width % (1 shl planes.subX) == 0 && planes.height % (1 shl planes.subY) == 0) {
                "AV1: subsampled chroma of a picture of ${planes.width} by ${planes.height}"
            }
        }
        val colour = if (parameters.colorPrimaries != null && parameters.transferCharacteristics != null && parameters.matrixCoefficients != null) {
            intArrayOf(parameters.colorPrimaries, parameters.transferCharacteristics, parameters.matrixCoefficients)
        } else null
        val settings = Av1EncodeSettings(if (parameters.lossless) 0 else qIndex(parameters.quality), parameters.effort, colour)
        return encode(planes, settings, cancellationCheck)
    }

    /** [planes] coded with [settings] as an `av01` item: for tests that turn a tool off to measure what it brings. */
    fun encode(planes: HeifPlanes, settings: Av1EncodeSettings, cancellationCheck: (() -> Unit)?): HeifCodedPicture {
        val coded = Av1Encoder(planes, settings, cancellationCheck).encode()
        return HeifCodedPicture(coded.params.configuration(coded.sequenceHeaderObu), coded.data, planes.width, planes.height, "avif")
    }

    /**
     * base_q_idx for a quality of 0 to 100: 240 at 0, falling 37/15 a step, so 117 at 50, 43 at 80 and 1 from 97 on (0
     * would be lossless). The slope and the anchors come from `Av1EncoderBenchmarkTest`: at quality 50 and 80 the
     * mean SSIMULACRA 2 score over the photo scenes lands next to avifenc's (libaom) at `-q 50` and `-q 80`.
     */
    fun qIndex(quality: Int): Int = ((3600 - 37 * quality + 7) / 15).coerceIn(1, 255)
}
