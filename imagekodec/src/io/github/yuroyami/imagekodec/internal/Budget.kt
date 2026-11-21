package io.github.yuroyami.imagekodec.internal

/**
 * Output ceilings and loose input-relative bounds where packed sample storage
 * gives those bounds meaning. VP8L singleton codes and CCITT reference rows can
 * emit pixels without a corresponding number of input bits; they use only the
 * absolute ceiling. Other codecs may still refuse unusually compressed files.
 */
internal object Budget {

    /** Absolute ceiling: 268M pixels, about 1 GiB of ARGB. */
    const val MAX_PIXELS: Long = 1L shl 28

    /** Always allow at least this much, so tiny inputs are never over-restricted. */
    const val MIN_PIXELS: Long = 1L shl 20

    /** Loose byte expansion bound; DEFLATE copies at most 258 bytes per symbol. */
    const val EXPANSION: Long = 4096

    /**
     * Expansion bound for Zstandard and LZMA. A zstd RLE block writes 128 KiB from 4 bytes, so
     * 32,768:1 is its limit. LZMA stays below about 7,500:1.
     */
    const val EXPANSION_ZSTD_LZMA: Long = 32768

    /** Packed samples smaller than a byte represent several pixels per decoded byte. */
    fun pixelCap(inputBytes: Int, bitsPerPixel: Int = 8, expansion: Long = EXPANSION): Long {
        require(inputBytes >= 0 && bitsPerPixel > 0 && expansion > 0)
        val perByte = expansion * 8 / minOf(bitsPerPixel, 8)
        return minOf(MAX_PIXELS, maxOf(MIN_PIXELS, inputBytes.toLong() * perByte))
    }

    fun fits(width: Int, height: Int, inputBytes: Int, bitsPerPixel: Int = 8, expansion: Long = EXPANSION): Boolean =
        width > 0 && height > 0 && width.toLong() * height <= pixelCap(inputBytes, bitsPerPixel, expansion)

    fun fitsAbsolute(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width.toLong() * height <= MAX_PIXELS

    fun framesFit(width: Int, height: Int, frames: Int, inputBytes: Int, bitsPerPixel: Int = 8): Boolean =
        framesFitCap(width, height, frames, pixelCap(inputBytes, bitsPerPixel))

    fun framesFitAbsolute(width: Int, height: Int, frames: Int): Boolean =
        framesFitCap(width, height, frames, MAX_PIXELS)

    // Division keeps dimensions and frame counts from overflowing a Long product.
    private fun framesFitCap(width: Int, height: Int, frames: Int, cap: Long): Boolean =
        width > 0 && height > 0 && frames > 0 &&
            width.toLong() * height <= cap / frames
}
