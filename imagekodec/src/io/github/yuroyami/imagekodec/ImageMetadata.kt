package io.github.yuroyami.imagekodec

/**
 * The metadata of a photo that a re-encode can carry over (#129): the [exif] block, the [xmp]
 * packet and the [icc] colour profile. [ImageKodec.readMetadata] reads them from a JPEG, PNG or
 * WebP file, and the `encodeJpeg`, `encodePng` and `encodeWebp` overloads that take an
 * [ImageMetadata] write them, so a re-encoded camera photo keeps its capture date, its place,
 * its camera and its colours.
 *
 * @property exif the EXIF block: the TIFF structure that follows `Exif\0\0` in a JPEG APP1
 *   segment, as a PNG `eXIf` chunk stores it, or null.
 * @property xmp the XMP packet, as text, or null.
 * @property icc the ICC profile, or null.
 * @property gainMap the gain map of an Ultra HDR JPEG, or null (#130).
 */
public class ImageMetadata(
    public val exif: ByteArray? = null,
    public val xmp: String? = null,
    public val icc: ByteArray? = null,
    public val gainMap: GainMap? = null,
) {
    /** True when there is nothing to write. */
    public val isEmpty: Boolean get() = exif == null && xmp == null && icc == null && gainMap == null

    /**
     * This metadata with the EXIF orientation set to 1, for pixels that a decode with
     * `applyOrientation = true` has already turned upright: a copied orientation would turn the
     * photo a second time. A [gainMap] turns the same way, or is left out when it cannot be
     * decoded. Metadata with no EXIF block, or an EXIF block with no orientation, comes back as it is.
     */
    public fun upright(): ImageMetadata {
        val block = exif ?: return this
        val orientation = Orientation.fromExif(io.github.yuroyami.imagekodec.codec.MetadataIo.orientation(block) ?: return this)
        val fixed = io.github.yuroyami.imagekodec.codec.MetadataIo.withOrientation(block, 1) ?: return this
        // The gain map is stored the way the main image is, so it turns with the pixels (#130).
        val turned = if (orientation == Orientation.Normal) gainMap else gainMap?.let { io.github.yuroyami.imagekodec.codec.UltraHdr.oriented(it, orientation) }
        return ImageMetadata(fixed, xmp, icc, turned)
    }

    override fun toString(): String =
        "ImageMetadata(exif=${exif?.size ?: 0} bytes, xmp=${xmp?.length ?: 0} chars, icc=${icc?.size ?: 0} bytes, gainMap=$gainMap)"
}
