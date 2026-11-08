package io.github.yuroyami.imagekodec

/**
 * The samples of a JPEG 2000 image as the codestream stores them: every component after the
 * inverse wavelet, the multiple component transform and the DC level shift, sampled onto the image
 * grid, and before the JP2 palette, channel definitions and colour space. [ImageKodec.decodeJpxComponents]
 * returns it.
 *
 * Nothing is inverted or converted: a four-component file holds four components here, whether its
 * header calls them CMYK or says nothing, and a palette image holds its indices. The caller decides
 * what they mean, from [colorSpecification] or, in a PDF, from the image's own `ColorSpace`, which
 * ISO 32000-1, 8.9.5.2 says overrides the file's.
 *
 * @property width pixels per row, after the reduction
 * @property height rows, after the reduction
 * @property componentCount the components of the codestream, 1 or more
 * @property samples [componentCount] bytes per pixel, row by row, the components of a pixel in
 *   codestream order. A component deeper than 8 bits keeps its high 8 bits, and a shallower one is
 *   stretched to 0 to 255, as [ImageKodec.decode] narrows them. Exposed directly, not copied: treat
 *   it as read-only.
 * @property bitDepths the bit depth the codestream gives each component, before the narrowing to
 *   8 bits. Exposed directly: treat it as read-only.
 * @property colorSpecification the first colour specification box of the JP2 header, or null for a
 *   raw codestream or a JP2 file without one
 * @throws IllegalArgumentException if a side or [componentCount] is not positive, or [samples] or
 *   [bitDepths] does not hold what [componentCount] asks
 */
public class JpxComponents @Throws(IllegalArgumentException::class) public constructor(
    public val width: Int,
    public val height: Int,
    public val componentCount: Int,
    public val samples: ByteArray,
    public val bitDepths: IntArray,
    public val colorSpecification: JpxColorSpecification?,
) {
    init {
        require(width > 0 && height > 0) { "dimensions must be positive: ${width}x$height" }
        require(componentCount > 0) { "componentCount must be positive, was $componentCount" }
        require(samples.size.toLong() == width.toLong() * height * componentCount) {
            "samples has ${samples.size} bytes, expected ${width}x$height x $componentCount = ${width.toLong() * height * componentCount}"
        }
        require(bitDepths.size == componentCount) { "bitDepths has ${bitDepths.size} entries for $componentCount components" }
    }

    /**
     * The sample of [component] at ([x], [y]), 0 to 255.
     * @throws IllegalArgumentException if the coordinates or the component are out of range
     */
    @Throws(IllegalArgumentException::class)
    public operator fun get(x: Int, y: Int, component: Int): Int {
        require(x in 0 until width && y in 0 until height) { "($x, $y) outside ${width}x$height" }
        require(component in 0 until componentCount) { "component $component outside 0 until $componentCount" }
        return samples[(y * width + x) * componentCount + component].toInt() and 0xFF
    }

    override fun toString(): String = "JpxComponents(${width}x$height, $componentCount components)"
}

/**
 * A JP2 colour specification box (T.800, I.5.3.3), as [JpxComponents.colorSpecification] reports it.
 *
 * @property method 1 for an enumerated colour space, 2 for a restricted ICC profile, 3 for any ICC
 *   profile; another value is passed on as the file stores it
 * @property enumerated with method 1 the enumerated colour space: 12 CMYK, 16 sRGB, 17 greyscale,
 *   18 sYCC, among those T.800 Table I.11 lists; -1 for the other methods
 * @property iccProfile with method 2 or 3 the ICC profile's bytes, else null. Exposed directly: treat
 *   it as read-only.
 */
public class JpxColorSpecification public constructor(
    public val method: Int,
    public val enumerated: Int,
    public val iccProfile: ByteArray?,
) {
    override fun toString(): String = "JpxColorSpecification(method=$method, enumerated=$enumerated)"
}
