package io.github.yuroyami.imagekodec

/**
 * How [ImageKodec.encodeJxl] writes a JPEG XL file (#179).
 *
 * A lossy file is coded with VarDCT in the XYB colour space. Its loss is set by [distance], in
 * the units of `cjxl -d`: 1.0 is about visually lossless, larger is smaller and worse. When
 * [distance] is null it comes from [quality] as `cjxl -q` maps it: `0.1 + (100 - quality) * 0.09`
 * from 30 up, so 90 gives 1.0 and 100 gives 0.1, and
 * `53 / 3000 * quality^2 - 23 / 20 * quality + 25` below 30, so 0 gives 25.
 *
 * A [lossless] file is coded with Modular and decodes to exactly the input, at every depth and
 * channel count; [quality] and [distance] are then ignored.
 *
 * @property quality 0 to 100, higher is better; used when [distance] is null.
 * @property distance the loss, 0.1 to 25, or null to take it from [quality].
 * @property lossless code the image so that it decodes to exactly the input.
 * @property effort 1 (fast) to 3 (thorough). Lossless: 1 tries one predictor and a small tree,
 *   2 tries six predictors, both palette and colour transform for images of few colours, and a
 *   tree of up to 256 leaves, 3 tries every predictor and colour transform and a tree of up to
 *   1024 leaves that may test the channels before. Lossy: 1 uses 8 by 8 transforms only, 2 also
 *   16 by 16, 16 by 8 and 8 by 16, and 3 also the 32 by 32 transforms.
 * @property container wrap the codestream in the ISO BMFF container of ISO/IEC 18181-2 even when
 *   there is no metadata to carry; with metadata the container is always written.
 */
public class JxlEncodeOptions @Throws(IllegalArgumentException::class) public constructor(
    public val quality: Int = 90,
    public val distance: Double? = null,
    public val lossless: Boolean = false,
    public val effort: Int = 2,
    public val container: Boolean = false,
) {
    init {
        require(quality in 0..100) { "JPEG XL quality must be 0 to 100, was $quality" }
        require(distance == null || (distance >= 0.1 && distance <= 25.0)) { "JPEG XL distance must be 0.1 to 25, was $distance" }
        require(effort in 1..3) { "JPEG XL effort must be 1 to 3, was $effort" }
    }

    /** The distance a lossy encode uses: [distance], or the one [quality] maps to. */
    public val effectiveDistance: Double
        get() = distance ?: if (quality >= 30) 0.1 + (100 - quality) * 0.09 else 53.0 / 3000.0 * quality * quality - 23.0 / 20.0 * quality + 25.0

    override fun toString(): String =
        "JxlEncodeOptions(quality=$quality, distance=$distance, lossless=$lossless, effort=$effort, container=$container)"
}
