package io.github.yuroyami.imagekodec

/**
 * Downscale to fit inside [maxWidth]×[maxHeight], preserving aspect ratio, using
 * box (area-average) filtering: each destination pixel averages the source pixels
 * under it, each weighted by the part of it that lies in the bin, so a thin line
 * keeps the same contrast wherever it falls and thumbnails don't shimmer the way
 * nearest-neighbor ones do.
 *
 * Color channels average **alpha-weighted** (premultiply, average, unpremultiply):
 * a fully transparent pixel contributes nothing to a bin's RGB, so transparent
 * padding (whose RGB is usually black) doesn't smear dark halos into the edges
 * of GIF/PNG thumbnails. For fully opaque input this reduces exactly to the
 * plain per-channel mean.
 *
 * Never upscales: if the bitmap already fits, the same instance returns.
 *
 * This is a post-decode scale: the decoder still materialises the full-size
 * image transiently. What it saves is *retained* memory (the thumbnail you keep
 * vs the 12MP original). [ImageKodec.decodeScaled] gives the same size from the
 * encoded bytes, and shrinks a JPEG or JPEG 2000 while it decodes.
 *
 * @throws IllegalArgumentException if either target dimension is not positive
 */
@Throws(IllegalArgumentException::class)
public fun KiteBitmap.scaled(maxWidth: Int, maxHeight: Int): KiteBitmap {
    require(maxWidth > 0 && maxHeight > 0) { "target must be positive: ${maxWidth}x$maxHeight" }
    val (dw, dh) = fittedSize(width, height, maxWidth, maxHeight) ?: return this
    return downscaledTo(dw, dh)
}

/**
 * The size [scaled] gives a [width] by [height] image for a [maxWidth] by [maxHeight] box, or null
 * when the image already fits. [ImageKodec.decodeScaled] sizes from it too.
 */
internal fun fittedSize(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int>? {
    if (width <= maxWidth && height <= maxHeight) return null
    return if (maxWidth.toLong() * height <= maxHeight.toLong() * width) {
        maxWidth to maxOf(1, (height.toLong() * maxWidth / width).toInt())
    } else {
        maxOf(1, (width.toLong() * maxHeight / height).toInt()) to maxHeight
    }
}

/**
 * Downscale to exactly [targetWidth]×[targetHeight] with [scaled]'s alpha-weighted
 * box filter. The caller chooses the aspect ratio; this operation does not fit
 * the requested dimensions inside another box. Equal dimensions return this
 * instance.
 *
 * @throws IllegalArgumentException if either dimension is not positive or
 *   exceeds the corresponding source dimension
 */
@Throws(IllegalArgumentException::class)
public fun KiteBitmap.downscaledTo(targetWidth: Int, targetHeight: Int): KiteBitmap {
    require(targetWidth in 1..width && targetHeight in 1..height) {
        "downscale target ${targetWidth}x$targetHeight outside ${width}x$height"
    }
    if (width == targetWidth && height == targetHeight) return this
    val dw = targetWidth
    val dh = targetHeight

    // An area-average filter weights each source pixel by the part of it inside the bin. Both
    // axes are scaled by the destination size, so the weights are whole numbers and every target
    // gives the same pixels: source pixel x covers [x * dw, (x + 1) * dw) and bin d covers
    // [d * sw, (d + 1) * sw). A pixel that straddles two bins counts in each by its share (#122).
    val xs = AxisWeights(width, dw)
    val ys = AxisWeights(height, dh)
    // Each bin covers width * height scaled units, the sum of its weights.
    val area = width.toLong() * height

    val out = IntArray(dw * dh)
    for (dy in 0 until dh) {
        val yFirst = ys.first(dy)
        val yCount = ys.count(dy)
        for (dx in 0 until dw) {
            val xFirst = xs.first(dx)
            val xCount = xs.count(dx)
            var sumA = 0L
            var sumR = 0L
            var sumG = 0L
            var sumB = 0L
            for (j in 0 until yCount) {
                val y = yFirst + j
                val wy = ys.weight(dy, y).toLong()
                val srcBase = y * width
                for (i in 0 until xCount) {
                    val x = xFirst + i
                    val p = argb[srcBase + x]
                    if (p ushr 24 == 0) continue
                    val wa = (p ushr 24) * wy * xs.weight(dx, x)
                    sumA += wa
                    sumR += ((p ushr 16) and 0xFF) * wa
                    sumG += ((p ushr 8) and 0xFF) * wa
                    sumB += (p and 0xFF) * wa
                }
            }
            out[dy * dw + dx] = if (sumA == 0L) {
                0
            } else {
                (((sumA + area / 2) / area).toInt() shl 24) or
                    (((sumR + sumA / 2) / sumA).toInt() shl 16) or
                    (((sumG + sumA / 2) / sumA).toInt() shl 8) or
                    ((sumB + sumA / 2) / sumA).toInt()
            }
        }
    }
    return KiteBitmap(dw, dh, out)
}

/**
 * The source pixels of each destination bin along one axis of [sourceSize] pixels scaled to
 * [destinationSize], with the whole-number weights of [downscaledTo].
 */
private class AxisWeights(private val sourceSize: Int, private val destinationSize: Int) {
    /** The first source pixel of bin [d]. Nothing is tabled, so a scale allocates only its output. */
    fun first(d: Int): Int = (d.toLong() * sourceSize / destinationSize).toInt()

    /** How many source pixels bin [d] touches: its last one may straddle into bin d + 1. */
    fun count(d: Int): Int {
        val end = (d + 1).toLong() * sourceSize
        return (end / destinationSize).toInt() - first(d) + if (end % destinationSize != 0L) 1 else 0
    }

    /** The overlap of source pixel [x] with bin [d], in units of 1 / [destinationSize] of a pixel. */
    fun weight(d: Int, x: Int): Int {
        val lo = maxOf(x.toLong() * destinationSize, d.toLong() * sourceSize)
        val hi = minOf((x + 1).toLong() * destinationSize, (d + 1).toLong() * sourceSize)
        return (hi - lo).toInt()
    }
}

/**
 * Downscale every frame of an animation to fit inside [maxWidth]×[maxHeight]
 * (same box filter and aspect handling as [KiteBitmap.scaled]), preserving
 * delays and the loop count. This is where animated memory goes: a full-screen
 * GIF shown as an avatar keeps W×H×4 bytes *per frame* unless scaled down.
 *
 * Never upscales: if the canvas already fits, the same instance returns.
 *
 * @throws IllegalArgumentException if either target dimension is not positive
 */
@Throws(IllegalArgumentException::class)
public fun KiteAnimation.scaled(maxWidth: Int, maxHeight: Int): KiteAnimation {
    require(maxWidth > 0 && maxHeight > 0) { "target must be positive: ${maxWidth}x$maxHeight" }
    if (width <= maxWidth && height <= maxHeight) return this
    return mapScaled { it.scaled(maxWidth, maxHeight) }
}

/**
 * Downscale every frame to exactly [targetWidth]×[targetHeight] with
 * [KiteBitmap.downscaledTo], preserving delays and the play count. Equal canvas
 * dimensions return this instance.
 *
 * @throws IllegalArgumentException if either dimension is not positive or
 *   exceeds the corresponding canvas or frame dimension
 */
@Throws(IllegalArgumentException::class)
public fun KiteAnimation.downscaledTo(targetWidth: Int, targetHeight: Int): KiteAnimation {
    require(targetWidth in 1..width && targetHeight in 1..height) {
        "downscale target ${targetWidth}x$targetHeight outside ${width}x$height"
    }
    if (width == targetWidth && height == targetHeight) return this
    return mapScaled { it.downscaledTo(targetWidth, targetHeight) }
}

private fun KiteAnimation.mapScaled(transform: (KiteBitmap) -> KiteBitmap): KiteAnimation {
    val scaledFrames = frames.map { f ->
        KiteFrame(
            bitmap = transform(f.bitmap),
            delayMillis = f.delayMillis,
            delayRawCentiseconds = f.delayRawCentiseconds,
        )
    }
    return KiteAnimation(
        width = scaledFrames.first().bitmap.width,
        height = scaledFrames.first().bitmap.height,
        frames = scaledFrames,
        loopCount = loopCount,
    )
}

/**
 * This bitmap with each block of [reduction] by [reduction] pixels averaged into one, weighted
 * by alpha as [scaled] averages: `ceil(width / reduction)` by `ceil(height / reduction)`
 * pixels, where a block at the right or bottom edge averages the pixels it has.
 */
internal fun KiteBitmap.reducedBy(reduction: Int): KiteBitmap {
    if (reduction <= 1) return this
    val dw = (width + reduction - 1) / reduction
    val dh = (height + reduction - 1) / reduction
    val out = IntArray(dw * dh)
    for (oy in 0 until dh) {
        for (ox in 0 until dw) {
            var a = 0L
            var r = 0L
            var g = 0L
            var b = 0L
            var n = 0
            for (y in oy * reduction until minOf((oy + 1) * reduction, height)) {
                for (x in ox * reduction until minOf((ox + 1) * reduction, width)) {
                    val p = argb[y * width + x]
                    n++
                    if (p ushr 24 == 0) continue
                    val pa = (p ushr 24).toLong()
                    a += pa
                    r += ((p shr 16) and 0xFF) * pa
                    g += ((p shr 8) and 0xFF) * pa
                    b += (p and 0xFF) * pa
                }
            }
            out[oy * dw + ox] = if (a == 0L) {
                0
            } else {
                val oa = ((a + n / 2) / n).toInt()
                val or = ((r + a / 2) / a).toInt()
                val og = ((g + a / 2) / a).toInt()
                val ob = ((b + a / 2) / a).toInt()
                (oa shl 24) or (or shl 16) or (og shl 8) or ob
            }
        }
    }
    return KiteBitmap(dw, dh, out)
}
