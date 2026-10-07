package io.github.yuroyami.imagekodec.codec.avif

/**
 * The AV1 upscaling process (specification section 7.16): widens each plane of a frame coded
 * at FrameWidth to UpscaledWidth with the 8-tap Upscale_Filter. The source planes keep the
 * decoder's superblock-aligned stride; the result has one row of Round2(UpscaledWidth, subX).
 */
internal object Av1Superres {
    private const val SCALE_BITS = 14
    private const val EXTRA_BITS = 8
    private const val SCALE_MASK = (1 shl SCALE_BITS) - 1
    private const val FILTER_TAPS = 8
    private const val FILTER_OFFSET = 3
    private const val FILTER_BITS = 7

    /** The upscaled copy of [planes], whose rows are [Av1FrameDecoder.planeWidth] apart. */
    fun upscale(f: Av1FrameDecoder, planes: Array<IntArray>): Array<IntArray> {
        val fh = f.fh
        val maxValue = (1 shl f.bitDepth) - 1
        val filter = Av1Tables.upscaleFilter
        return Array(f.numPlanes) { plane ->
            val subX = if (plane > 0) f.subX else 0
            val subY = if (plane > 0) f.subY else 0
            val downscaledPlaneW = Av1.round2(fh.frameWidth, subX)
            val upscaledPlaneW = Av1.round2(fh.upscaledWidth, subX)
            val planeH = Av1.round2(fh.frameHeight, subY)
            val stepX = ((downscaledPlaneW shl SCALE_BITS) + (upscaledPlaneW / 2)) / upscaledPlaneW
            val err = upscaledPlaneW * stepX - (downscaledPlaneW shl SCALE_BITS)
            val initialSubpelX = ((-((upscaledPlaneW - downscaledPlaneW) shl (SCALE_BITS - 1)) + upscaledPlaneW / 2) / upscaledPlaneW +
                (1 shl (EXTRA_BITS - 1)) - err / 2) and SCALE_MASK
            val maxX = (f.miCols shr subX) * Av1.MI_SIZE - 1
            val src = planes[plane]
            val stride = f.planeWidth[plane]
            val out = IntArray(upscaledPlaneW * planeH)
            for (y in 0 until planeH) {
                val row = y * stride
                for (x in 0 until upscaledPlaneW) {
                    val srcX = -(1 shl SCALE_BITS) + initialSubpelX + x * stepX
                    val srcXPx = srcX shr SCALE_BITS
                    val taps = ((srcX and SCALE_MASK) shr EXTRA_BITS) * FILTER_TAPS
                    var sum = 0
                    for (k in 0 until FILTER_TAPS) {
                        val sampleX = (srcXPx + k - FILTER_OFFSET).coerceIn(0, maxX)
                        sum += src[row + sampleX] * filter[taps + k]
                    }
                    out[y * upscaledPlaneW + x] = Av1.round2(sum, FILTER_BITS).coerceIn(0, maxValue)
                }
            }
            out
        }
    }
}
