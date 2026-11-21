package io.github.yuroyami.imagekodec

/**
 * The colour type a PNG is written in (PNG, Third Edition, 6.1), for [PngEncodeOptions].
 */
public enum class PngColorType {
    /**
     * The smallest type that holds the image exactly: gray at 1, 2, 4, 8 or 16 bits, a palette
     * of up to 256 colours, RGB, or the same with alpha, and a `tRNS` colour key in place of an
     * alpha channel when every transparent pixel is one colour that no opaque pixel has.
     */
    Auto,

    /** Gray, with a `tRNS` colour key when the image is transparent in the way [Auto] describes. */
    Gray,

    /** Gray with an alpha channel. */
    GrayAlpha,

    /** RGB, with a `tRNS` colour key when the image is transparent in the way [Auto] describes. */
    Rgb,

    /** RGB with an alpha channel. */
    Rgba,

    /** Indices into a palette of up to 256 colours, their alpha in `tRNS` when any is not opaque. */
    Palette,
}

/**
 * How [ImageKodec.encodePng] writes a PNG (#182). Every choice is lossless: the file decodes to
 * exactly the pixels it was given, so a [colorType] or [bitDepth] that cannot hold the image
 * makes the encode throw rather than change a pixel.
 *
 * - [colorType]: [PngColorType.Auto] (the default) picks the smallest type that holds the image;
 *   another value fixes the type.
 * - [bitDepth]: bits a sample, or null for the fewest that hold the image. Gray takes 1, 2, 4, 8
 *   or 16; a palette 1, 2, 4 or 8; gray with alpha, RGB and RGBA 8 or 16. [PngColorType.Auto]
 *   takes null only.
 * - [interlace]: writes Adam7 interlace (PNG, Third Edition, 8.2), which a browser draws coarse
 *   first. The file is usually a little larger.
 * - [effort]: from 1 (fast) to 3 (thorough), default 2. At 1, [PngColorType.Auto] takes the
 *   type of fewest bytes before compression, and the rows are filtered by the rule of PNG, Third
 *   Edition, 12.8 (no filter for a palette or samples under 8 bits, one chosen a row otherwise)
 *   and compressed with a short match search: two to four times as fast as 2, and 5 to 10
 *   percent larger on photographs. At 2, Auto also tries the best type without a palette, and each type
 *   is compressed three ways, filtered a row at a time and unfiltered with the usual search, and
 *   filtered with Huffman coding alone, the smallest kept: never larger than the call without
 *   options. At 3, every type that holds the image, both palette orders (by frequency and by
 *   luminance), and six filter choices with three match searches are tried, and the best three are
 *   compressed again with a long lazy search: 7 to 24 times slower than 2, and 3 to 9 percent
 *   smaller on photographs. The output is the same on every platform at every effort.
 *
 * @throws IllegalArgumentException if [bitDepth] is not one [colorType] takes, or [effort] is
 *   outside 1..3
 */
public class PngEncodeOptions @Throws(IllegalArgumentException::class) public constructor(
    public val colorType: PngColorType = PngColorType.Auto,
    public val bitDepth: Int? = null,
    public val interlace: Boolean = false,
    public val effort: Int = 2,
) {
    init {
        require(effort in 1..3) { "effort must be 1 to 3, was $effort" }
        if (bitDepth != null) {
            val allowed = when (colorType) {
                PngColorType.Auto -> intArrayOf()
                PngColorType.Gray -> intArrayOf(1, 2, 4, 8, 16)
                PngColorType.Palette -> intArrayOf(1, 2, 4, 8)
                else -> intArrayOf(8, 16)
            }
            require(bitDepth in allowed) {
                if (colorType == PngColorType.Auto) "bitDepth must be null with PngColorType.Auto, was $bitDepth"
                else "bitDepth $bitDepth is not one $colorType takes (${allowed.joinToString()})"
            }
        }
    }

    override fun toString(): String =
        "PngEncodeOptions(colorType=$colorType, bitDepth=$bitDepth, interlace=$interlace, effort=$effort)"
}
