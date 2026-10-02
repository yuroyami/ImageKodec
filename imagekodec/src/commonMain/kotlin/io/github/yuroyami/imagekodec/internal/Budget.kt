package io.github.yuroyami.imagekodec.internal

/**
 * How many pixels a file is allowed to claim it decodes to.
 *
 * A fixed ceiling alone is not enough. Every format states its dimensions in a
 * header field, and a corrupted field can name a 250-megapixel image inside a
 * 250-byte file: a decoder that only checks the absolute limit will happily
 * reserve a gigabyte before discovering there is no data to fill it. Some
 * runtimes throw at that point, some just die.
 *
 * So the budget is the smaller of two numbers: an absolute ceiling, and an
 * expansion bound tied to the actual input size. [EXPANSION] is deliberately
 * loose (16 KiB of decoded pixels per input byte, far past what any real codec
 * reaches on real pictures) so it never rejects a legitimate file; what it does
 * is stop a header from writing cheques the payload cannot cash.
 */
internal object Budget {

    /** Absolute ceiling: 268M pixels, about 1 GiB of ARGB. */
    const val MAX_PIXELS: Long = 1L shl 28

    /** Always allow at least this much, so tiny inputs are never over-restricted. */
    const val MIN_PIXELS: Long = 1L shl 20

    /** Decoded pixels permitted per byte of input. */
    const val EXPANSION: Long = 4096

    /** The pixel budget for an input of [inputBytes]. */
    fun pixelCap(inputBytes: Int): Long =
        minOf(MAX_PIXELS, maxOf(MIN_PIXELS, inputBytes.toLong() * EXPANSION))

    /** True when [width] × [height] fits the budget for an input of [inputBytes]. */
    fun fits(width: Int, height: Int, inputBytes: Int): Boolean =
        width > 0 && height > 0 && width.toLong() * height <= pixelCap(inputBytes)

    /**
     * True when [frames] full canvases of [width] × [height] fit the budget for an
     * input of [inputBytes]. An animation keeps one composited canvas per frame, so
     * the canvas alone passing [fits] says nothing about the frames: a tiny frame
     * costs a few bytes of input and a whole canvas of memory.
     */
    fun framesFit(width: Int, height: Int, frames: Int, inputBytes: Int): Boolean =
        width > 0 && height > 0 && frames > 0 &&
            width.toLong() * height * frames <= pixelCap(inputBytes)
}
