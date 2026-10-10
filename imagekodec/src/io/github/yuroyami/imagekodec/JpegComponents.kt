package io.github.yuroyami.imagekodec

/**
 * The samples of a JPEG as the file stores them: every component after the inverse DCT and the
 * upsampling, before any color conversion. [ImageKodec.decodeJpegComponents] returns it.
 *
 * Nothing is inverted, transformed or multiplied in. A three-component file usually stores
 * YCbCr, a four-component one CMYK or YCCK in Adobe's inverted convention, and the caller decides
 * how to read them, for instance from [adobeTransform] or, in a PDF, from the image dictionary's
 * `ColorTransform` and `Decode` entries. Run through [ImageKodec.decode]'s own color conversion,
 * these samples give exactly the pixels of [ImageKodec.decodeReduced] at the same reduction.
 *
 * @property width pixels per row, after the reduction
 * @property height rows, after the reduction
 * @property componentCount 1, 3 or 4
 * @property samples [componentCount] bytes per pixel, row by row, the components of a pixel in the
 *   order the frame header lists them. Exposed directly, not copied: treat it as read-only.
 * @property adobeTransform the transform flag of the last Adobe APP14 marker: 0 for none (RGB or
 *   CMYK), 1 for YCbCr and 2 for YCCK. A file may store another value, which is passed on as it
 *   is, 0 to 255. -1 when the file has no Adobe marker.
 * @throws IllegalArgumentException if a side is not positive, [componentCount] is not 1, 3 or 4,
 *   [samples] does not hold [componentCount] bytes for each pixel, or [adobeTransform] is outside
 *   -1 to 255
 */
public class JpegComponents @Throws(IllegalArgumentException::class) public constructor(
    public val width: Int,
    public val height: Int,
    public val componentCount: Int,
    public val samples: ByteArray,
    public val adobeTransform: Int,
) {
    init {
        require(width > 0 && height > 0) { "dimensions must be positive: ${width}x$height" }
        require(componentCount == 1 || componentCount == 3 || componentCount == 4) {
            "componentCount must be 1, 3 or 4, was $componentCount"
        }
        require(samples.size.toLong() == width.toLong() * height * componentCount) {
            "samples has ${samples.size} bytes, expected ${width}x$height x $componentCount = ${width.toLong() * height * componentCount}"
        }
        require(adobeTransform in -1..255) { "adobeTransform must be -1 to 255, was $adobeTransform" }
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

    override fun toString(): String = "JpegComponents(${width}x$height, $componentCount components)"
}
