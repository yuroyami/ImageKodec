package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.HeifCodedPicture
import io.github.yuroyami.imagekodec.HeifCodedSample
import io.github.yuroyami.imagekodec.HeifEncodeParameters
import io.github.yuroyami.imagekodec.HeifEncoder
import io.github.yuroyami.imagekodec.HeifPlanes
import io.github.yuroyami.imagekodec.HeifSequenceEncoder

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

    override fun openSequence(parameters: HeifEncodeParameters, keyFrameInterval: Int): HeifSequenceEncoder =
        Sequence(parameters, HevcSequenceSettings(irapPeriod = keyFrameInterval))

    /**
     * An `hvc1` track (#177). B pictures refer to pictures after them, so the planner needs the whole sequence:
     * [encode] keeps each picture, and [finish] codes them all and gives the samples in decoding order.
     */
    private class Sequence(private val parameters: HeifEncodeParameters, private val settings: HevcSequenceSettings) : HeifSequenceEncoder {
        private val frames = ArrayList<HeifPlanes>()
        private var coded: HevcSequence? = null

        override val sampleEntryType: String = "hvc1"
        override val brand: String get() = sequenceBrand(done())
        override val imageBrand: String get() = if (done().profile == "Main") "heic" else "heix"
        override val configuration: ByteArray get() = done().hvcC

        override fun encode(planes: HeifPlanes, cancellationCheck: (() -> Unit)?): List<HeifCodedSample> {
            check(coded == null) { "HEVC: the sequence is finished" }
            frames.firstOrNull()?.let { f ->
                require(planes.width == f.width && planes.height == f.height && planes.bitDepthLuma == f.bitDepthLuma && planes.bitDepthChroma == f.bitDepthChroma &&
                    planes.subX == f.subX && planes.subY == f.subY && planes.planes.size == f.planes.size) {
                    "HEVC: every picture of a sequence has the first one's size, depth and chroma format"
                }
            }
            frames += planes
            return emptyList()
        }

        override fun finish(cancellationCheck: (() -> Unit)?): List<HeifCodedSample> {
            check(coded == null) { "HEVC: the sequence is finished" }
            require(frames.isNotEmpty()) { "HEVC: a sequence of no pictures" }
            // The core writes the timing of the track, so each picture lasts one tick here.
            val sequence = encodeSequence(frames, LongArray(frames.size) { 1 }, parameters, settings, cancellationCheck)
            coded = sequence
            frames.clear()
            return sequence.samples.map { HeifCodedSample(it.data, it.frame, it.sync) }
        }

        private fun done(): HevcSequence = checkNotNull(coded) { "HEVC: the sequence is not finished" }
    }

    /**
     * The brand of ISO/IEC 23008-12 B.4.2 for an image sequence of [sequence]: `hevc` for the Main profile, `hevx`
     * for Main 10 and the format range extensions profiles.
     */
    fun sequenceBrand(sequence: HevcSequence): String = if (sequence.profile == "Main") "hevc" else "hevx"
}
