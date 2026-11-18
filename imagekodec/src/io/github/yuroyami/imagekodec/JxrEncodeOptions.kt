package io.github.yuroyami.imagekodec

/** How a JPEG XR encode stores the colour of each macroblock (T.832 INTERNAL_CLR_FMT). */
public enum class JxrChroma {
    /** 4:4:4 from quality 50 up and for 16-bit samples, 4:2:0 below, as `JxrEncApp` chooses. */
    Auto,

    /** Every chroma sample kept (YUV 4:4:4). */
    Full,

    /** Chroma halved across and down (YUV 4:2:0), for 8-bit colour. */
    Half,
}

/**
 * The settings of a JPEG XR encode (#181), for [ImageKodec.encodeJxr] and [ImageKodec.encodeJxr16].
 *
 * @property quality 0 to 100, higher is better, as `JxrEncApp -q` reads a value of [quality]
 *   divided by 100: it picks the quantizer of each band and channel. Ignored when [lossless].
 * @property lossless code with a quantizer of 1, without overlap filtering: the file decodes to
 *   the input exactly, at every depth and channel count.
 * @property chroma 4:4:4 or 4:2:0 colour; lossless and 16-bit encodes always keep it full.
 * @property overlap the overlap filtering of T.832 (OVERLAP_MODE): 0 for none, 1 for the first
 *   level, 2 for both levels; null picks 1 from quality 50 up and 2 below, as `JxrEncApp` does,
 *   and 0 when [lossless].
 * @property chromaQuality the quality of the two chroma channels on their own, or null for the
 *   offset from [quality] that the encoder picks: a quantizer per channel.
 * @property planarAlpha store alpha as a codestream of its own after the image (T.832 A.7.17),
 *   as `JxrEncApp` does by default, or with false, as a plane interleaved with the colour,
 *   macroblock by macroblock.
 * @property alphaLossless code alpha exactly, as `JxrEncApp` does by default; otherwise at
 *   [alphaQuality].
 * @property alphaQuality 0 to 100, the quality of a lossy alpha plane.
 * @property tileSize the side of a tile in pixels, a multiple of 16, or 0 for one tile. Each tile
 *   is a packet of its own that the index table points to; the overlap filter runs across tile
 *   edges (soft tiles).
 * @property tileQualities a quality for each tile in raster order, overriding [quality] there:
 *   quantizers per tile. Null keeps one quantizer for the whole image.
 * @property frequencyOrder write each tile as four packets, DC, lowpass, highpass and flexbits
 *   (T.832 FREQUENCY_MODE_CODESTREAM_FLAG), which a decoder can stop reading early, in place of
 *   one packet in spatial order.
 * @throws IllegalArgumentException if a value is out of its range
 */
public class JxrEncodeOptions @Throws(IllegalArgumentException::class) public constructor(
    public val quality: Int = 90,
    public val lossless: Boolean = false,
    public val chroma: JxrChroma = JxrChroma.Auto,
    public val overlap: Int? = null,
    public val chromaQuality: Int? = null,
    public val planarAlpha: Boolean = true,
    public val alphaLossless: Boolean = true,
    public val alphaQuality: Int = 90,
    public val tileSize: Int = 0,
    tileQualities: List<Int>? = null,
    public val frequencyOrder: Boolean = false,
) {
    // A copy of the list given, so the options stay as they were made.
    public val tileQualities: List<Int>? = tileQualities?.toList()

    init {
        require(quality in 0..100) { "quality must be 0 to 100, was $quality" }
        require(overlap == null || overlap in 0..2) { "overlap must be 0, 1 or 2, was $overlap" }
        require(chromaQuality == null || chromaQuality in 0..100) { "chromaQuality must be 0 to 100, was $chromaQuality" }
        require(alphaQuality in 0..100) { "alphaQuality must be 0 to 100, was $alphaQuality" }
        require(tileSize >= 0 && tileSize % 16 == 0) { "tileSize must be a multiple of 16, was $tileSize" }
        tileQualities?.forEach { require(it in 0..100) { "tile qualities must be 0 to 100, one was $it" } }
        require(tileQualities == null || !lossless) { "tileQualities needs a lossy encode" }
    }
}
