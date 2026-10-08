package io.github.yuroyami.imagekodec

/**
 * Where a decode leaves the colours of its samples, as Android's
 * `ImageDecoder.setTargetColorSpace` sets it.
 *
 * - [Source] keeps the samples as the file stores them, whatever colour space its
 *   [ColorProfile] declares, which is what every decode does by default.
 * - [Srgb] converts them to sRGB through the file's embedded ICC profile, for drawing on a
 *   screen that shows sRGB, as browsers convert. A file that declares nothing, or declares
 *   sRGB, comes back as [Source] would give it.
 */
public sealed class ColorTarget {
    /** The samples as the file stores them. */
    public object Source : ColorTarget() {
        override fun toString(): String = "ColorTarget.Source"
    }

    /** Converted to sRGB with [intent], perceptual by default as browsers and lcms2's tools convert. */
    public data class Srgb(public val intent: RenderingIntent = RenderingIntent.Perceptual) : ColorTarget()
}

/**
 * How a colour outside the target's gamut, or a source white that is not the target's, is
 * mapped (ICC.1 Annex D). Perceptual and saturation use the profile's own tables when it has
 * them; a matrix profile maps them as relative colorimetric, with black point compensation as
 * lcms2 applies it. Absolute colorimetric keeps the source's media white instead of adapting it.
 */
public enum class RenderingIntent {
    Perceptual,
    RelativeColorimetric,
    Saturation,
    AbsoluteColorimetric,
}
