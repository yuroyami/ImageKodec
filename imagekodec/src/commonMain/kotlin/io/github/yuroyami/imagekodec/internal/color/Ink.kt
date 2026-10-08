package io.github.yuroyami.imagekodec.internal.color

/**
 * CMYK ink as a decoder reads it for conversion through the file's profile: cyan, magenta,
 * yellow and black a pixel, each from none (0) to full (255 in [ink], 65535 in [ink16]), and
 * straight alpha, one sample a pixel, when the image has any. A decoder fills the 8-bit
 * arrays or the 16-bit ones.
 */
internal class Ink(
    val width: Int,
    val height: Int,
    val ink: ByteArray? = null,
    val ink16: ShortArray? = null,
    val alpha: ByteArray? = null,
    val alpha16: ShortArray? = null,
)

/**
 * This 8-bit ink with each side divided by [reduction], rounded up: each block's ink averaged,
 * weighted by alpha, and its alpha averaged, as [io.github.yuroyami.imagekodec.reducedBy]
 * averages a bitmap.
 */
internal fun Ink.reducedBy(reduction: Int): Ink {
    val samples = ink ?: error("only 8-bit ink reduces")
    if (reduction <= 1) return this
    val dw = (width + reduction - 1) / reduction
    val dh = (height + reduction - 1) / reduction
    val out = ByteArray(dw * dh * 4)
    val outAlpha = if (alpha != null) ByteArray(dw * dh) else null
    val sum = LongArray(4)
    for (oy in 0 until dh) for (ox in 0 until dw) {
        sum.fill(0L)
        var a = 0L
        var n = 0
        for (y in oy * reduction until minOf((oy + 1) * reduction, height)) {
            for (x in ox * reduction until minOf((ox + 1) * reduction, width)) {
                val i = y * width + x
                n++
                val pa = if (alpha == null) 255L else (alpha[i].toLong() and 0xFF)
                if (pa == 0L) continue
                a += pa
                for (c in 0 until 4) sum[c] += (samples[4 * i + c].toLong() and 0xFF) * pa
            }
        }
        val o = oy * dw + ox
        if (a != 0L) for (c in 0 until 4) out[4 * o + c] = ((sum[c] + a / 2) / a).toByte()
        if (outAlpha != null) outAlpha[o] = ((a + n / 2) / n).toByte()
    }
    return Ink(dw, dh, ink = out, alpha = outAlpha)
}
