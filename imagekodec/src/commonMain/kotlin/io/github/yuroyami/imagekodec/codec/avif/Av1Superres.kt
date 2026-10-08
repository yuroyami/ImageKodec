package io.github.yuroyami.imagekodec.codec.avif

/**
 * The AV1 upscaling process (specification section 7.16): widens each plane of a frame coded
 * at FrameWidth to UpscaledWidth with the 8-tap Upscale_Filter. Each row upscales on its own, so
 * the rows loop restoration keeps from before CDEF upscale apart from the frame.
 */
internal object Av1Superres {
    private const val SCALE_BITS = 14
    private const val EXTRA_BITS = 8
    private const val SCALE_MASK = (1 shl SCALE_BITS) - 1
    private const val FILTER_TAPS = 8
    private const val FILTER_OFFSET = 3
    private const val FILTER_BITS = 7

    /** The width of [plane] upscaled: one row of the result. */
    fun upscaledWidth(f: Av1FrameDecoder, plane: Int): Int = Av1.round2(f.fh.upscaledWidth, if (plane > 0) f.subX else 0)

    /** The upscaled copy of [planes], whose rows are [Av1FrameDecoder.planeWidth] apart. */
    fun upscale(f: Av1FrameDecoder, planes: Array<ShortArray>): Array<ShortArray> = Array(f.numPlanes) { plane ->
        val subY = if (plane > 0) f.subY else 0
        val planeH = Av1.round2(f.fh.frameHeight, subY)
        val width = upscaledWidth(f, plane)
        val out = ShortArray(width * planeH)
        for (y in 0 until planeH) row(f, plane, planes[plane], y * f.planeWidth[plane], out, y * width)
        out
    }

    /** One row of [plane] from [src] at [from], upscaled into [dst] at [to]. */
    fun row(f: Av1FrameDecoder, plane: Int, src: ShortArray, from: Int, dst: ShortArray, to: Int) {
        val fh = f.fh
        val subX = if (plane > 0) f.subX else 0
        val maxValue = (1 shl f.bitDepth) - 1
        val filter = Av1Tables.upscaleFilter
        val downscaledPlaneW = Av1.round2(fh.frameWidth, subX)
        val upscaledPlaneW = Av1.round2(fh.upscaledWidth, subX)
        val stepX = ((downscaledPlaneW shl SCALE_BITS) + (upscaledPlaneW / 2)) / upscaledPlaneW
        val err = upscaledPlaneW * stepX - (downscaledPlaneW shl SCALE_BITS)
        val initialSubpelX = ((-((upscaledPlaneW - downscaledPlaneW) shl (SCALE_BITS - 1)) + upscaledPlaneW / 2) / upscaledPlaneW +
            (1 shl (EXTRA_BITS - 1)) - err / 2) and SCALE_MASK
        val maxX = (f.miCols shr subX) * Av1.MI_SIZE - 1
        for (x in 0 until upscaledPlaneW) {
            val srcX = -(1 shl SCALE_BITS) + initialSubpelX + x * stepX
            val srcXPx = srcX shr SCALE_BITS
            val taps = ((srcX and SCALE_MASK) shr EXTRA_BITS) * FILTER_TAPS
            var sum = 0
            for (k in 0 until FILTER_TAPS) {
                val sampleX = (srcXPx + k - FILTER_OFFSET).coerceIn(0, maxX)
                sum += src[from + sampleX] * filter[taps + k]
            }
            dst[to + x] = Av1.round2(sum, FILTER_BITS).coerceIn(0, maxValue).toShort()
        }
    }
}
