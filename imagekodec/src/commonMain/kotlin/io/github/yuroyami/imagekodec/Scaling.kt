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
 */
public fun KiteBitmap.scaled(maxWidth: Int, maxHeight: Int): KiteBitmap {
    require(maxWidth > 0 && maxHeight > 0) { "target must be positive: ${maxWidth}x$maxHeight" }
    if (width <= maxWidth && height <= maxHeight) return this

    // Fit inside, keep aspect, floor: but never to zero.
    val scale = minOf(maxWidth.toDouble() / width, maxHeight.toDouble() / height)
    val dw = maxOf(1, (width * scale).toInt())
    val dh = maxOf(1, (height * scale).toInt())

    val sumA = LongArray(dw * dh)
    val sumR = LongArray(dw * dh)
    val sumG = LongArray(dw * dh)
    val sumB = LongArray(dw * dh)
    val count = IntArray(dw * dh)

    // Source column → destination column, computed once instead of one integer
    // division per pixel (the row map amortises per row already).
    val colMap = IntArray(width) { x -> minOf(dw - 1, x * dw / width) }

    for (y in 0 until height) {
        val dy = minOf(dh - 1, y * dh / height)
        val rowBase = dy * dw
        val srcBase = y * width
        for (x in 0 until width) {
            val p = argb[srcBase + x]
            val i = rowBase + colMap[x]
            val a = (p ushr 24) and 0xFF
            sumA[i] += a.toLong()
            // Premultiplied accumulation: weight = alpha. a == 0 contributes 0.
            sumR[i] += (((p ushr 16) and 0xFF) * a).toLong()
            sumG[i] += (((p ushr 8) and 0xFF) * a).toLong()
            sumB[i] += ((p and 0xFF) * a).toLong()
            count[i]++
        }
    }

    val out = IntArray(dw * dh)
    for (i in out.indices) {
        val n = count[i]
        val a = ((sumA[i] + n / 2) / n).toInt()
        val w = sumA[i]
        if (w == 0L) {
            // Bin is fully transparent: no color information survives.
            out[i] = 0
        } else {
            out[i] = (a shl 24) or
                (((sumR[i] + w / 2) / w).toInt() shl 16) or
                (((sumG[i] + w / 2) / w).toInt() shl 8) or
                ((sumB[i] + w / 2) / w).toInt()
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
 */
public fun KiteAnimation.scaled(maxWidth: Int, maxHeight: Int): KiteAnimation {
    require(maxWidth > 0 && maxHeight > 0) { "target must be positive: ${maxWidth}x$maxHeight" }
    if (width <= maxWidth && height <= maxHeight) return this

    val scaledFrames = frames.map { f ->
        KiteFrame(
            bitmap = f.bitmap.scaled(maxWidth, maxHeight),
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
                    val pa = (p ushr 24).toLong()
                    a += pa
                    r += ((p shr 16) and 0xFF) * pa
                    g += ((p shr 8) and 0xFF) * pa
                    b += (p and 0xFF) * pa
                    n++
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
