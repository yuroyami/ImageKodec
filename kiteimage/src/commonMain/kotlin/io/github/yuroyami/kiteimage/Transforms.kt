package io.github.yuroyami.kiteimage

/**
 * Geometric pixel operations on a decoded [KiteBitmap]. All of them allocate a
 * new bitmap and copy: pixels are packed ints, so this is a straight index
 * remap with no colour maths and no precision loss.
 *
 * They exist mostly so [oriented] can exist: EXIF orientation is unfixable
 * without rotate and flip, and "the photo is sideways" is the single most
 * common complaint about a decoder that ignores it.
 */

/** Rotate a quarter turn clockwise. Width and height swap. */
public fun KiteBitmap.rotated90(): KiteBitmap {
    val dw = height
    val dh = width
    val out = IntArray(dw * dh)
    // dst(x, y) = src(y, height - 1 - x)
    for (y in 0 until dh) {
        val dstRow = y * dw
        for (x in 0 until dw) {
            out[dstRow + x] = argb[(height - 1 - x) * width + y]
        }
    }
    return KiteBitmap(dw, dh, out)
}

/** Rotate a half turn. Dimensions are unchanged. */
public fun KiteBitmap.rotated180(): KiteBitmap {
    val out = IntArray(argb.size)
    var src = argb.size - 1
    for (i in out.indices) out[i] = argb[src--]
    return KiteBitmap(width, height, out)
}

/** Rotate a quarter turn counter-clockwise. Width and height swap. */
public fun KiteBitmap.rotated270(): KiteBitmap {
    val dw = height
    val dh = width
    val out = IntArray(dw * dh)
    // dst(x, y) = src(width - 1 - y, x)
    for (y in 0 until dh) {
        val dstRow = y * dw
        val srcCol = width - 1 - y
        for (x in 0 until dw) {
            out[dstRow + x] = argb[x * width + srcCol]
        }
    }
    return KiteBitmap(dw, dh, out)
}

/** Mirror left-to-right. */
public fun KiteBitmap.flippedHorizontal(): KiteBitmap {
    val out = IntArray(argb.size)
    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) out[row + x] = argb[row + (width - 1 - x)]
    }
    return KiteBitmap(width, height, out)
}

/** Mirror top-to-bottom. */
public fun KiteBitmap.flippedVertical(): KiteBitmap {
    val out = IntArray(argb.size)
    for (y in 0 until height) {
        argb.copyInto(out, destinationOffset = y * width, startIndex = (height - 1 - y) * width, endIndex = (height - y) * width)
    }
    return KiteBitmap(width, height, out)
}

/** Mirror across the main diagonal: `dst(x, y) = src(y, x)`. Axes swap. */
public fun KiteBitmap.transposed(): KiteBitmap {
    val dw = height
    val dh = width
    val out = IntArray(dw * dh)
    for (y in 0 until dh) {
        val dstRow = y * dw
        for (x in 0 until dw) out[dstRow + x] = argb[x * width + y]
    }
    return KiteBitmap(dw, dh, out)
}

/** Mirror across the anti-diagonal. Axes swap. */
public fun KiteBitmap.transversed(): KiteBitmap {
    val dw = height
    val dh = width
    val out = IntArray(dw * dh)
    // dst(x, y) = src(width - 1 - y, height - 1 - x)
    for (y in 0 until dh) {
        val dstRow = y * dw
        val srcCol = width - 1 - y
        for (x in 0 until dw) out[dstRow + x] = argb[(height - 1 - x) * width + srcCol]
    }
    return KiteBitmap(dw, dh, out)
}

/**
 * Apply an EXIF [orientation] so the result is oriented the way the camera meant
 * it to be seen. [Orientation.Normal] returns the same instance.
 */
public fun KiteBitmap.oriented(orientation: Orientation): KiteBitmap = when (orientation) {
    Orientation.Normal -> this
    Orientation.FlipHorizontal -> flippedHorizontal()
    Orientation.Rotate180 -> rotated180()
    Orientation.FlipVertical -> flippedVertical()
    Orientation.Transpose -> transposed()
    Orientation.Rotate90 -> rotated90()
    Orientation.Transverse -> transversed()
    Orientation.Rotate270 -> rotated270()
}

/**
 * Copy the [width]×[height] region whose top-left corner is ([x], [y]).
 *
 * @throws IllegalArgumentException if the rectangle is empty or reaches outside
 *   the bitmap: silently clamping a bad crop hides the caller's bug.
 */
public fun KiteBitmap.cropped(x: Int, y: Int, width: Int, height: Int): KiteBitmap {
    require(width > 0 && height > 0) { "crop size must be positive: ${width}x$height" }
    require(x >= 0 && y >= 0 && x + width <= this.width && y + height <= this.height) {
        "crop ${width}x$height at ($x, $y) is outside ${this.width}x${this.height}"
    }
    if (x == 0 && y == 0 && width == this.width && height == this.height) return this

    val out = IntArray(width * height)
    for (row in 0 until height) {
        val src = (y + row) * this.width + x
        argb.copyInto(out, destinationOffset = row * width, startIndex = src, endIndex = src + width)
    }
    return KiteBitmap(width, height, out)
}

// --- animation-wide versions ------------------------------------------------
// Frames are already fully composited, so transforming each one independently is
// correct: there is no inter-frame state left to keep consistent.

/** [oriented] applied to every frame, preserving delays and loop count. */
public fun KiteAnimation.oriented(orientation: Orientation): KiteAnimation {
    if (orientation == Orientation.Normal) return this
    return mapFrames { it.oriented(orientation) }
}

/** [cropped] applied to every frame, preserving delays and loop count. */
public fun KiteAnimation.cropped(x: Int, y: Int, width: Int, height: Int): KiteAnimation =
    mapFrames { it.cropped(x, y, width, height) }

/** [rotated90] applied to every frame. */
public fun KiteAnimation.rotated90(): KiteAnimation = mapFrames { it.rotated90() }

/** [rotated180] applied to every frame. */
public fun KiteAnimation.rotated180(): KiteAnimation = mapFrames { it.rotated180() }

/** [rotated270] applied to every frame. */
public fun KiteAnimation.rotated270(): KiteAnimation = mapFrames { it.rotated270() }

private inline fun KiteAnimation.mapFrames(transform: (KiteBitmap) -> KiteBitmap): KiteAnimation {
    val mapped = frames.map { f ->
        KiteFrame(transform(f.bitmap), f.delayMillis, f.delayRawCentiseconds)
    }
    return KiteAnimation(
        width = mapped.first().bitmap.width,
        height = mapped.first().bitmap.height,
        frames = mapped,
        loopCount = loopCount,
    )
}
