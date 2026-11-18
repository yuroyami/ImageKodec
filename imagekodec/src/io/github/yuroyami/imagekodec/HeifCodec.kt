package io.github.yuroyami.imagekodec

/**
 * Marks the interface through which a module adds the codec of a HEIF item type. It may still
 * change between releases, so a caller opts in to use it.
 */
@RequiresOptIn(message = "The HEIF codec interface may change between releases.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR)
public annotation class ExperimentalHeifCodecApi

/**
 * The codec of one coded item type of HEIF (ISO/IEC 23008-12), such as `hvc1` for HEVC. The core
 * reads the container: it finds the items, joins grids, applies alpha, crops, turns and converts
 * to RGB. A codec only turns the coded data of one item, or of one sample of a track, into planes.
 *
 * Register a codec with [ImageKodec.registerHeifCodec].
 */
@ExperimentalHeifCodecApi
public interface HeifCodec {
    /** The four-character item type this codec decodes, such as `hvc1`. */
    public val itemType: String

    /** The four-character sample entry types of a track this codec decodes, such as `hvc1` and `hev1`. */
    public val sampleEntryTypes: Set<String>

    /** The four-character type of the item property that configures the codec, such as `hvcC`. */
    public val configurationType: String

    /**
     * What the coded data says about the picture, without decoding it. [configuration] is the payload
     * of the [configurationType] property, without its box header, or null when the item has none.
     * [data] is the coded data of the item or of the first sample, or null when it is not available,
     * as when a caller probes the first bytes of a file. Throws [ImageDecodeException] for data that
     * is malformed.
     */
    public fun inspect(configuration: ByteArray?, data: ByteArray?): HeifCodecInfo

    /**
     * A session that decodes the coded samples of one item or of one track, in order. A session
     * keeps state across the samples of a track, so the core opens one session for each.
     */
    public fun open(configuration: ByteArray?): HeifCodecSession
}

/**
 * Decodes the coded samples of one item or one track.
 *
 * The contract: call `cancellationCheck` at a coarse step, such as once a row of coding blocks, and
 * let what it throws pass through. Never catch `Throwable`, because cancellation is not an
 * `Exception`. Throw [ImageDecodeException] for malformed data and [UnsupportedImageException]
 * for a feature the codec lacks.
 */
@ExperimentalHeifCodecApi
public interface HeifCodecSession {
    /**
     * The picture that [sample] shows, or null when it shows none, as a sample a codec skips can. The
     * core decodes an item, and the first frame of a track, through this method.
     */
    public fun decode(sample: ByteArray, cancellationCheck: (() -> Unit)?): HeifPlanes?

    /**
     * Decodes [sample], the next sample of a track in decoding order, and returns the pictures the
     * codec outputs after it, in output order: none, one or several. A codec whose output order differs
     * from its decoding order, as HEVC's does with B pictures, holds a picture back until every picture
     * before it in output order is decoded, and [flush] gives the ones it still holds at the end. The
     * core plays a track through this method and [flush], and pairs the pictures in turn with the
     * samples in order of composition time.
     *
     * The default returns what [decode] gives, for a codec that outputs each picture as it decodes it.
     */
    public fun decodeInOutputOrder(sample: ByteArray, cancellationCheck: (() -> Unit)?): List<HeifPlanes> =
        listOfNotNull(decode(sample, cancellationCheck))

    /**
     * Ends the track: the pictures the codec still holds back for output, in output order. The default
     * holds none.
     */
    public fun flush(): List<HeifPlanes> = emptyList()
}

/**
 * What a codec reads from the configuration and the coded data of an item.
 *
 * [width] and [height] are the size of the decoded picture, after any cropping the bitstream itself
 * signals, or 0 when unknown. [chromaFormat] is 0 for monochrome, 1 for 4:2:0, 2 for 4:2:2 and 3 for
 * 4:4:4. The colour fields are the code points of ITU-T H.273 that the bitstream signals, or null
 * when it signals none. [unsupportedReason] says what the codec cannot decode, or is null.
 */
@ExperimentalHeifCodecApi
public class HeifCodecInfo(
    public val width: Int,
    public val height: Int,
    public val bitDepthLuma: Int,
    public val bitDepthChroma: Int,
    public val chromaFormat: Int,
    public val colorPrimaries: Int? = null,
    public val transferCharacteristics: Int? = null,
    public val matrixCoefficients: Int? = null,
    public val fullRange: Boolean? = null,
    public val unsupportedReason: String? = null,
)

/**
 * The decoded planes of one picture: luma [width] by [height], then chroma subsampled by
 * [subX] and [subY] (each 0 or 1), or luma alone when [planes] holds one plane. Each plane's rows are
 * [strides] samples apart. Samples are unsigned in the low [bitDepthLuma] or [bitDepthChroma] bits
 * of each `Short`. [fullRange] is false for the limited range of studio video.
 */
@ExperimentalHeifCodecApi
public class HeifPlanes(
    public val width: Int,
    public val height: Int,
    public val bitDepthLuma: Int,
    public val bitDepthChroma: Int,
    public val subX: Int,
    public val subY: Int,
    public val planes: Array<ShortArray>,
    public val strides: IntArray,
    public val fullRange: Boolean,
)

/**
 * The encoder of one coded item type of HEIF, such as `hvc1` for HEVC (#174). The core writes the
 * container: it converts RGB to planes, cuts a large picture into the tiles of a grid, and writes the
 * items, their properties and their references. An encoder only turns the planes of one picture into
 * the payload of its configuration property and the coded data of its item.
 *
 * Register an encoder with [ImageKodec.registerHeifEncoder].
 */
@ExperimentalHeifCodecApi
public interface HeifEncoder {
    /** The four-character item type this encoder writes, such as `hvc1`. */
    public val itemType: String

    /** The four-character type of the item property that configures the codec, such as `hvcC`. */
    public val configurationType: String

    /**
     * The URN of the `auxC` property of an alpha plane coded by this encoder, as the readers of the
     * codec expect it: `urn:mpeg:hevc:2015:auxid:1` for HEVC, `urn:mpeg:mpegB:cicp:systems:auxiliary:alpha`
     * for AV1.
     */
    public val alphaUrn: String

    /**
     * Codes [planes], one picture, as [parameters] ask. Luma has 1 sample or more on each side, and in
     * subsampled chroma an even number, so the picture needs no rounding up. Call [cancellationCheck]
     * at a coarse step, such as once a row of coding blocks, and let what it throws pass through. Throw
     * [IllegalArgumentException] for a picture the codec cannot hold.
     */
    public fun encode(planes: HeifPlanes, parameters: HeifEncodeParameters, cancellationCheck: (() -> Unit)?): HeifCodedPicture

    /**
     * Opens an encoder of one image sequence (#177), the pictures of one track of a HEIF file, coded as
     * [parameters] ask, or returns null when this encoder codes still pictures only, as the default does. The
     * core calls it for [ImageKodec.encodeAvif] and [ImageKodec.encodeHeic] with an animation, once for the
     * colour track and once more for an alpha track. [keyFrameInterval] above 0 asks for a picture that needs no
     * earlier one (a sync sample) at least every that many pictures; 0 asks for the first alone.
     */
    public fun openSequence(parameters: HeifEncodeParameters, keyFrameInterval: Int): HeifSequenceEncoder? = null
}

/**
 * The encoder of one image sequence (#177), which a [HeifEncoder] opens. The core hands it the pictures of the
 * track in display order and writes the samples it returns into the track in the order they come, which is
 * decoding order: an encoder that reorders pictures, as HEVC does with B pictures, holds a picture back and
 * returns it later, each sample naming the picture it codes by [HeifCodedSample.frameIndex]. The core then writes
 * the composition offsets (`ctts`) that put each picture back at its display time.
 *
 * The contract of [HeifEncoder.encode] holds for each picture: every picture of the sequence has the first one's
 * size, depth and chroma format, call `cancellationCheck` at a coarse step and let what it throws pass through.
 */
@ExperimentalHeifCodecApi
public interface HeifSequenceEncoder {
    /** The four-character sample entry type of the track, such as `av01` or `hvc1`. */
    public val sampleEntryType: String

    /**
     * The brand of the image sequence for the `ftyp` box, such as `avis` for AV1 or `hevc` for HEVC, which the
     * core lists beside the item brand of the first picture.
     */
    public val brand: String

    /** The brand of the first picture as a still image item, such as `avif` or `heic`, for the `ftyp` box. */
    public val imageBrand: String

    /**
     * Codes the next picture, [planes], and returns the samples that are ready, in decoding order: none while
     * the encoder holds pictures back, one, or several.
     */
    public fun encode(planes: HeifPlanes, cancellationCheck: (() -> Unit)?): List<HeifCodedSample>

    /** Ends the sequence: the samples the encoder still holds, in decoding order. */
    public fun finish(cancellationCheck: (() -> Unit)?): List<HeifCodedSample>

    /**
     * The payload of the configuration property of the sample entry (`av1C`, `hvcC`), without its box header,
     * once the first picture is coded. The core also gives it to the first picture, which it writes as the
     * primary item too, so that a reader of still images shows that picture.
     */
    public val configuration: ByteArray
}

/**
 * One coded sample of an image sequence: its [data], the index from 0 in display order of the picture it codes,
 * and whether it is a sync sample, decodable without the samples before it. A sequence's first sample is one.
 */
@ExperimentalHeifCodecApi
public class HeifCodedSample(
    public val data: ByteArray,
    public val frameIndex: Int,
    public val sync: Boolean,
)

/**
 * What a [HeifEncoder] makes of one picture. [quality] runs from 0 to 100, higher better, and is
 * ignored when [lossless] asks for the planes to decode exactly as given. [effort] runs from 1 (fast)
 * to 3 (thorough). The colour fields are the code points of ITU-T H.273 for the codec to signal in its
 * own headers, or null to signal none, as for an alpha plane; [HeifPlanes.fullRange] gives the range.
 */
@ExperimentalHeifCodecApi
public class HeifEncodeParameters(
    public val quality: Int,
    public val lossless: Boolean,
    public val effort: Int,
    public val colorPrimaries: Int? = null,
    public val transferCharacteristics: Int? = null,
    public val matrixCoefficients: Int? = null,
)

/**
 * One coded picture: [configuration] is the payload of the encoder's configuration property, without its
 * box header, and [data] the coded data of the item. The picture decodes to [width] by [height], the size
 * of the planes. [brand] is the brand of ISO/IEC 23008-12 Annex B, or of the codec's own HEIF
 * specification, that the coded picture conforms to, such as `heic`, `heix` or `avif`, for the `ftyp` box.
 */
@ExperimentalHeifCodecApi
public class HeifCodedPicture(
    public val configuration: ByteArray,
    public val data: ByteArray,
    public val width: Int,
    public val height: Int,
    public val brand: String,
)
