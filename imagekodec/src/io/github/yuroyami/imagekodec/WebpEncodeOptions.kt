package io.github.yuroyami.imagekodec

/**
 * How [ImageKodec.encodeWebp] writes a WebP (#178).
 *
 * With [lossless] the image goes into a `VP8L` chunk and decodes to the exact pixels it came from;
 * [quality], [effort], [sharpYuv], [alphaQuality] and [alphaFilter] then do nothing. Otherwise the
 * colour goes into a lossy `VP8 ` chunk, in YUV 4:2:0, and any alpha into an `ALPH` chunk beside it.
 *
 * @property lossless write the lossless format instead of the lossy one.
 * @property quality 0 to 100, higher is better and larger, with the meaning of `cwebp -q`: the same
 *   number gives about the same quantizer as libwebp chooses.
 * @property effort 1 (fast) to 3 (thorough). 1 decides the modes of a macroblock by its distortion
 *   and a cost estimate in one pass, about as `cwebp -m 2` does. 2 adds a first pass that measures
 *   the token statistics, so the rate and distortion decisions of the second pass weigh real costs,
 *   as `cwebp -m 4` does. 3 also searches each coefficient's level for the best rate and
 *   distortion, and tries several loop filter strengths, as `cwebp -m 6` does.
 * @property sharpYuv convert to YUV 4:2:0 by an iterative search that keeps colour edges sharper
 *   after the decoder's upsampling, as `cwebp -sharp_yuv` does. Slower to encode.
 * @property alphaQuality 0 to 100: 100 keeps the alpha exact, and a lower value reduces the alpha to
 *   fewer levels, down to 2 at 0, as `cwebp -alpha_q` does. Fully opaque and fully transparent
 *   pixels stay exact at every value.
 * @property alphaFilter how the alpha plane is predicted before its lossless coding.
 * @property background the background colour, as ARGB, that an animation's `ANIM` chunk carries. A
 *   player may show it outside the frames; ImageKodec and libwebp start from a transparent canvas.
 * @throws IllegalArgumentException if [quality], [effort] or [alphaQuality] is out of range
 */
public class WebpEncodeOptions @Throws(IllegalArgumentException::class) public constructor(
    public val lossless: Boolean = false,
    public val quality: Int = 75,
    public val effort: Int = 2,
    public val sharpYuv: Boolean = false,
    public val alphaQuality: Int = 100,
    public val alphaFilter: WebpAlphaFilter = WebpAlphaFilter.Fast,
    public val background: Int = 0,
) {
    init {
        require(quality in 0..100) { "a WebP quality is 0 to 100, not $quality" }
        require(effort in 1..3) { "a WebP effort is 1 to 3, not $effort" }
        require(alphaQuality in 0..100) { "a WebP alpha quality is 0 to 100, not $alphaQuality" }
    }

    override fun toString(): String =
        "WebpEncodeOptions(lossless=$lossless, quality=$quality, effort=$effort, sharpYuv=$sharpYuv, " +
            "alphaQuality=$alphaQuality, alphaFilter=$alphaFilter, background=${background.toUInt().toString(16)})"
}

/** The prediction filter of a lossy WebP's alpha plane (the `ALPH` chunk's filtering method). */
public enum class WebpAlphaFilter {
    /** No prediction: the levels are coded as they are. */
    None,

    /** One of the horizontal, vertical and gradient filters, picked by a quick estimate of its cost. */
    Fast,

    /** Every filter, and no filter, each coded in full, and the smallest kept. Slower. */
    Best,
}
