package io.github.yuroyami.imagekodec

/**
 * The settings of a BMP encode (#184), for [ImageKodec.encodeBmp]. The defaults give the bytes the
 * call without options gives.
 *
 * @property bitsPerPixel the depth of each pixel: null picks 24 for an opaque bitmap and 32 for
 *   one with alpha, as the call without options does. 1, 4 and 8 write a palette of the bitmap's
 *   colours in the order they first appear, and need an opaque bitmap of at most 2, 16 or 256
 *   colours. 16 writes 5 bits each of red, green and blue (BI_RGB), each rounded to the nearest of
 *   its 32 levels, so it is lossy for most bitmaps. 24 is BI_RGB and needs an opaque bitmap. 32 is
 *   BI_BITFIELDS under a BITMAPV4HEADER with an alpha mask. Every depth but 16 is lossless.
 * @property rle compress the palette indices with BI_RLE8 at 8 bits or BI_RLE4 at 4 bits. Every
 *   pixel is written, with no delta jumps, so a reader leaves no hole.
 * @throws IllegalArgumentException if [bitsPerPixel] is not one of 1, 4, 8, 16, 24 and 32, or [rle]
 *   is asked for at a depth other than 4 or 8
 */
public class BmpEncodeOptions @Throws(IllegalArgumentException::class) public constructor(
    public val bitsPerPixel: Int? = null,
    public val rle: Boolean = false,
) {
    init {
        require(bitsPerPixel == null || bitsPerPixel in intArrayOf(1, 4, 8, 16, 24, 32)) {
            "BMP: bitsPerPixel must be 1, 4, 8, 16, 24 or 32, was $bitsPerPixel"
        }
        require(!rle || bitsPerPixel == 4 || bitsPerPixel == 8) { "BMP: RLE needs 4 or 8 bits a pixel, was $bitsPerPixel" }
    }

    override fun toString(): String = "BmpEncodeOptions(bitsPerPixel=$bitsPerPixel, rle=$rle)"
}
