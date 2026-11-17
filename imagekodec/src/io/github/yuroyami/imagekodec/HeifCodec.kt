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
    /** The picture that [sample] shows, or null when it shows none, as a sample a codec skips can. */
    public fun decode(sample: ByteArray, cancellationCheck: (() -> Unit)?): HeifPlanes?
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
