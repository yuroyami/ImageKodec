package io.github.yuroyami.imagekodec

/**
 * The HDR gain map of a HEIC or AVIF file (#153), as [ImageKodec.decodeGainMap] returns it: the
 * decoded map and what the file says of it. It is returned, not applied. [GainMap] stays the gain
 * map of an Ultra HDR JPEG, which holds JPEG bytes; this one holds decoded samples.
 *
 * A file carries its map in one of two forms, which [form] names:
 * - [Form.Iso21496]: a tone-mapped (`tmap`) derived item, ISO/IEC 23008-12 with ISO 21496-1, whose
 *   inputs are the base image and the gain map image and whose payload gives [metadata].
 * - [Form.Apple]: an auxiliary image of type `urn:com:apple:photo:2020:aux:hdrgainmap`, as an
 *   iPhone writes it: a 4:0:0 HEVC image, smaller than the photo. Its parameters are in the
 *   photo's EXIF, in tags 33 and 48 of Apple's MakerNote, which [appleMakerNote33] and
 *   [appleMakerNote48] give as stored.
 *
 * @property form which of the two forms the file uses.
 * @property bitmap the gain map image's samples at 16 bits, before its own clean aperture,
 *   rotation and mirror: one channel when the gain map image is monochrome, else three, red, green
 *   and blue. They are converted as [ImageKodec.decode16] converts a picture, with the gain map
 *   image's own colour properties: a full-range 8-bit sample `v` is `v * 257`, a limited-range one
 *   is stretched to full range first, and a picture in colour is converted from YCbCr to RGB.
 * @property metadata the parameters of [Form.Iso21496], or null for [Form.Apple].
 * @property appleMakerNote33 tag 33 (0x0021) of the Apple MakerNote in the primary image's EXIF, as
 *   a number, or null when the form is not [Form.Apple] or the tag is absent or not a number.
 * @property appleMakerNote48 tag 48 (0x0030) of the same MakerNote, or null in the same cases.
 * @property appleHeadroom the headroom Apple derives from tags 33 and 48: the ratio of the
 *   brightest white of the HDR rendition to SDR white, from the formula of Apple's "Applying Apple
 *   HDR effect to your photos". Null unless both tags are present.
 */
public class HeifGainMap internal constructor(
    public val form: Form,
    public val bitmap: KiteBitmap16,
    public val metadata: GainMapMetadata?,
    public val appleMakerNote33: Double?,
    public val appleMakerNote48: Double?,
    public val appleHeadroom: Double?,
) {
    /** The two ways a HEIF file stores its gain map. */
    public enum class Form {
        /** A `tmap` derived item with ISO 21496-1 metadata. */
        Iso21496,

        /** An auxiliary image of type `urn:com:apple:photo:2020:aux:hdrgainmap`. */
        Apple,
    }

    override fun toString(): String = "HeifGainMap($form, ${bitmap.width}x${bitmap.height}x${bitmap.channels})"
}

/**
 * The gain map metadata of ISO 21496-1, as the payload of a `tmap` item holds it (`GainMapMetadata`,
 * read as libavif's `avifParseToneMappedImageBox` reads it). Each fraction of the payload is given
 * as its numerator divided by its denominator; [raw] keeps the payload as stored.
 *
 * The per-channel arrays hold one value when the metadata is for one channel, which applies to all
 * three, and three, for red, green and blue, when it is multichannel. The headrooms, the gain map
 * minimum and maximum and the offsets are as ISO 21496-1 defines them: the headrooms and the gain map
 * minimum and maximum in log2 units, the offsets and the gamma as plain numbers.
 *
 * @property version the version of the payload, 0.
 * @property minimumVersion the oldest version of the metadata a reader must understand, 0 here:
 *   a payload that asks for more is refused.
 * @property writerVersion the version of the metadata the writer knew; a reader of version 0 may
 *   ignore bytes past the fields below when it is greater.
 * @property useBaseColorSpace whether the gain map is applied in the base image's colour space,
 *   rather than the alternate image's.
 * @property baseHdrHeadroom the HDR headroom of the base image, in log2 units.
 * @property alternateHdrHeadroom the HDR headroom of the alternate image, in log2 units.
 * @property gainMapMin the gain map minimum of each channel, in log2 units.
 * @property gainMapMax the gain map maximum of each channel, in log2 units.
 * @property gamma the gamma of each channel's gain map encoding.
 * @property baseOffset the offset added to each channel of the base image.
 * @property alternateOffset the offset added to each channel of the alternate image.
 * @property raw the payload of the `tmap` item, as stored. Treat the arrays as read-only.
 */
public class GainMapMetadata internal constructor(
    public val version: Int,
    public val minimumVersion: Int,
    public val writerVersion: Int,
    public val useBaseColorSpace: Boolean,
    public val baseHdrHeadroom: Double,
    public val alternateHdrHeadroom: Double,
    public val gainMapMin: DoubleArray,
    public val gainMapMax: DoubleArray,
    public val gamma: DoubleArray,
    public val baseOffset: DoubleArray,
    public val alternateOffset: DoubleArray,
    public val raw: ByteArray,
) {
    /** 1 when one set of values applies to every channel, 3 when each channel has its own. */
    public val channelCount: Int get() = gainMapMin.size

    override fun toString(): String =
        "GainMapMetadata(version=$version, channels=$channelCount, baseHdrHeadroom=$baseHdrHeadroom, alternateHdrHeadroom=$alternateHdrHeadroom)"
}
