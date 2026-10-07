package io.github.yuroyami.imagekodec

/**
 * Downscale to fit inside [maxWidth]×[maxHeight], preserving aspect ratio, using
 * box (area-average) filtering: each destination pixel averages every source
 * pixel that maps into its bin, so thumbnails don't shimmer the way
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
 * vs the 12MP original). To shrink a JPEG while it decodes, use
 * [ImageKodec.decodeReduced] first.
 *
 * @throws IllegalArgumentException if either target dimension is not positive
 */
@Throws(IllegalArgumentException::class)
public fun KiteBitmap.scaled(maxWidth: Int, maxHeight: Int): KiteBitmap {
    require(maxWidth > 0 && maxHeight > 0) { "target must be positive: ${maxWidth}x$maxHeight" }
    if (width <= maxWidth && height <= maxHeight) return this

    val dw: Int
    val dh: Int
    if (maxWidth.toLong() * height <= maxHeight.toLong() * width) {
        dw = maxWidth
        dh = maxOf(1, (height.toLong() * maxWidth / width).toInt())
    } else {
        dh = maxHeight
        dw = maxOf(1, (width.toLong() * maxHeight / height).toInt())
    }
    return downscaledTo(dw, dh)
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

    // Invert floor(source * destinationSize / sourceSize) with ceiling
    // division. Each source sample belongs to exactly one destination bin.
    fun boundary(bin: Int, sourceSize: Int, destinationSize: Int): Int =
        ((bin.toLong() * sourceSize + destinationSize - 1) / destinationSize).toInt()

    val out = IntArray(dw * dh)
    for (dy in 0 until dh) {
        val y0 = boundary(dy, height, dh)
        val y1 = boundary(dy + 1, height, dh)
        for (dx in 0 until dw) {
            val x0 = boundary(dx, width, dw)
            val x1 = boundary(dx + 1, width, dw)
            var sumA = 0L
            var sumR = 0L
            var sumG = 0L
            var sumB = 0L
            for (y in y0 until y1) {
                val srcBase = y * width
                for (x in x0 until x1) {
                    val p = argb[srcBase + x]
                    if (p ushr 24 == 0) continue
                    val a = (p ushr 24).toLong()
                    sumA += a
                    sumR += ((p ushr 16) and 0xFF) * a
                    sumG += ((p ushr 8) and 0xFF) * a
                    sumB += (p and 0xFF) * a
                }
            }
            val n = (x1 - x0).toLong() * (y1 - y0)
            out[dy * dw + dx] = if (sumA == 0L) {
                0
            } else {
                (((sumA + n / 2) / n).toInt() shl 24) or
                    (((sumR + sumA / 2) / sumA).toInt() shl 16) or
                    (((sumG + sumA / 2) / sumA).toInt() shl 8) or
                    ((sumB + sumA / 2) / sumA).toInt()
            }
        }
    }
    return KiteBitmap(dw, dh, out)
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
