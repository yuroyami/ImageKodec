package io.github.yuroyami.kiteimage

/**
 * A decoded raster image: [width] × [height] pixels, packed as non-premultiplied
 * ARGB_8888 in a row-major [argb] array (`argb[y * width + x]`, alpha in the top
 * byte). One layout for every source format: decoders normalise grayscale,
 * palette, BGR and 16-bit inputs into this on the way out, so consumers and the
 * Compose interop never branch on the source format.
 *
 * [argb] is exposed directly (not copied) so a full-screen photo doesn't pay a
 * second multi-megabyte allocation on the way to `ImageBitmap`. Treat it as
 * read-only; mutate it only if you own the instance.
 */
public class KiteBitmap(
    public val width: Int,
    public val height: Int,
    public val argb: IntArray,
) {
    init {
        require(width > 0 && height > 0) { "bitmap dimensions must be positive: ${width}x$height" }
        require(argb.size == width * height) {
            "argb has ${argb.size} pixels, expected ${width}x$height = ${width * height}"
        }
    }

    /** The packed ARGB pixel at ([x], [y]). */
    public operator fun get(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height) {
            "($x, $y) outside ${width}x$height"
        }
        return argb[y * width + x]
    }

    /** True if any pixel has alpha < 255. O(n) scan, not cached. */
    public fun hasTransparency(): Boolean = argb.any { (it ushr 24) != 0xFF }

    override fun toString(): String = "KiteBitmap(${width}x$height)"
}
