package io.github.yuroyami.kiteimage

/**
 * Downscale to fit inside [maxWidth]×[maxHeight], preserving aspect ratio, using
 * box (area-average) filtering — each destination pixel averages every source
 * pixel that maps into its bin, so thumbnails don't shimmer the way
 * nearest-neighbor ones do. Channels average independently in straight
 * (non-premultiplied) alpha.
 *
 * Never upscales: if the bitmap already fits, the same instance returns.
 *
 * This is a post-decode scale — the decoder still materialises the full-size
 * image transiently. What it saves is *retained* memory (the thumbnail you keep
 * vs the 12MP original). Decode-time DCT-domain scaling is future work.
 */
public fun KiteBitmap.scaled(maxWidth: Int, maxHeight: Int): KiteBitmap {
    require(maxWidth > 0 && maxHeight > 0) { "target must be positive: ${maxWidth}x$maxHeight" }
    if (width <= maxWidth && height <= maxHeight) return this

    // Fit inside, keep aspect, floor — but never to zero.
    val scale = minOf(maxWidth.toDouble() / width, maxHeight.toDouble() / height)
    val dw = maxOf(1, (width * scale).toInt())
    val dh = maxOf(1, (height * scale).toInt())

    val sumA = LongArray(dw * dh)
    val sumR = LongArray(dw * dh)
    val sumG = LongArray(dw * dh)
    val sumB = LongArray(dw * dh)
    val count = IntArray(dw * dh)

    for (y in 0 until height) {
        val dy = minOf(dh - 1, y * dh / height)
        val rowBase = dy * dw
        for (x in 0 until width) {
            val dx = minOf(dw - 1, x * dw / width)
            val p = argb[y * width + x]
            val i = rowBase + dx
            sumA[i] += (p ushr 24) and 0xFF
            sumR[i] += (p ushr 16) and 0xFF
            sumG[i] += (p ushr 8) and 0xFF
            sumB[i] += p and 0xFF
            count[i]++
        }
    }

    val out = IntArray(dw * dh)
    for (i in out.indices) {
        val n = count[i]
        out[i] = (((sumA[i] + n / 2) / n).toInt() shl 24) or
            (((sumR[i] + n / 2) / n).toInt() shl 16) or
            (((sumG[i] + n / 2) / n).toInt() shl 8) or
            ((sumB[i] + n / 2) / n).toInt()
    }
    return KiteBitmap(dw, dh, out)
}
