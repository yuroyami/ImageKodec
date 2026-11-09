package io.github.yuroyami.imagekodec

/**
 * The gain map of an Ultra HDR JPEG (#130): a second, small JPEG after the main image that tells
 * an HDR screen how much brighter each area of the photo may be drawn. [ImageKodec.readMetadata]
 * finds it through the Multi-Picture Format segment of the main image, and an `encodeJpeg` with
 * the metadata writes it again after the new main image, with an MPF segment that points to it.
 *
 * A gain map covers the whole main image at any resolution, so it stays valid while the main
 * image keeps its aspect ratio and its orientation. [ImageMetadata.upright] turns it with the
 * pixels; an encode of a bitmap of another aspect ratio, a crop for one, leaves it out together
 * with the HDR fields of the XMP packet.
 *
 * @property jpeg the gain map image, a whole JPEG file with its own `hdrgm` XMP packet or ISO
 *   21496-1 segment, which hold its parameters.
 * @property primaryIso the body of the ISO 21496-1 `APP2` segment of the main image, which names
 *   the version of the gain map metadata, or null when the main image has none.
 * @throws ImageDecodeException when [jpeg] is not a JPEG whose size can be read
 */
public class GainMap(
    public val jpeg: ByteArray,
    public val primaryIso: ByteArray? = null,
) {
    private val info = ImageKodec.probe(jpeg).also {
        if (it.format != ImageFormat.JPEG) throw ImageDecodeException("a gain map is a JPEG, not ${it.format}")
    }

    /** The width of the gain map, in pixels. */
    public val width: Int get() = info.width

    /** The height of the gain map, in pixels. */
    public val height: Int get() = info.height

    override fun toString(): String = "GainMap(${width}x$height, ${jpeg.size} bytes)"
}
