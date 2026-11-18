package io.github.yuroyami.imagekodec.codec.avif

import io.github.yuroyami.imagekodec.Orientation

/**
 * The crop and the orientation that a sequence of HEIF transformative properties gives, applied
 * one after another. MIAF fixes their order (clean aperture, rotation, mirror); a file that is not
 * MIAF applies them in the order of its `ipma` association (ISO/IEC 23008-12 6.5.1) (#167).
 */
internal object HeifGeometry {

    /** Where the stored sample at ([x], [y]) of a [w] by [h] picture shows with [o], as EXIF defines the eight orientations. */
    fun map(o: Orientation, x: Int, y: Int, w: Int, h: Int): IntArray = when (o) {
        Orientation.Normal -> intArrayOf(x, y)
        Orientation.FlipHorizontal -> intArrayOf(w - 1 - x, y)
        Orientation.Rotate180 -> intArrayOf(w - 1 - x, h - 1 - y)
        Orientation.FlipVertical -> intArrayOf(x, h - 1 - y)
        Orientation.Transpose -> intArrayOf(y, x)
        Orientation.Rotate90 -> intArrayOf(h - 1 - y, x)
        Orientation.Transverse -> intArrayOf(h - 1 - y, w - 1 - x)
        Orientation.Rotate270 -> intArrayOf(y, w - 1 - x)
    }

    /** [first], then [then]: found as the one orientation that moves every sample of a 3 by 2 picture alike. */
    fun compose(first: Orientation, then: Orientation): Orientation {
        val w = 3
        val h = 2
        val (w1, h1) = if (first.swapsAxes) h to w else w to h
        return Orientation.entries.first { o ->
            (0 until h).all { y ->
                (0 until w).all { x ->
                    val mid = map(first, x, y, w, h)
                    map(then, mid[0], mid[1], w1, h1).contentEquals(map(o, x, y, w, h))
                }
            }
        }
    }

    /** The orientation that undoes [o]. */
    fun inverse(o: Orientation): Orientation = Orientation.entries.first { compose(o, it) == Orientation.Normal }

    /**
     * The crop, as left, top, width and height in stored samples, or null for none, and the
     * orientation of [steps] applied in order to a [width] by [height] picture. [crop] gives a clean
     * aperture's rectangle for the size the picture shows at that step, or null to leave it out.
     */
    fun fold(width: Int, height: Int, steps: List<AvifProperty>, crop: (AvifProperty.CleanAperture, Int, Int) -> IntArray?, orient: (AvifProperty.Rotation?, AvifProperty.Mirror?) -> Orientation): Pair<IntArray?, Orientation> {
        var x = 0
        var y = 0
        var w = width
        var h = height
        var o = Orientation.Normal
        for (step in steps) {
            when (step) {
                is AvifProperty.Rotation -> o = compose(o, orient(step, null))
                is AvifProperty.Mirror -> o = compose(o, orient(null, step))
                is AvifProperty.CleanAperture -> {
                    val (dw, dh) = if (o.swapsAxes) h to w else w to h
                    val r = crop(step, dw, dh) ?: continue
                    // The shown rectangle's corners, taken back to stored samples of the crop so far.
                    val inv = inverse(o)
                    val a = map(inv, r[0], r[1], dw, dh)
                    val b = map(inv, r[0] + r[2] - 1, r[1] + r[3] - 1, dw, dh)
                    x += minOf(a[0], b[0])
                    y += minOf(a[1], b[1])
                    w = kotlin.math.abs(a[0] - b[0]) + 1
                    h = kotlin.math.abs(a[1] - b[1]) + 1
                }
                else -> {}
            }
        }
        val rect = if (x == 0 && y == 0 && w == width && h == height) null else intArrayOf(x, y, w, h)
        return rect to o
    }
}
