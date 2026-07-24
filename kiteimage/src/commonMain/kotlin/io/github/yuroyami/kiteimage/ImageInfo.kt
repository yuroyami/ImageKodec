package io.github.yuroyami.kiteimage

/**
 * How a decoded image relates to the scene it was captured from: the EXIF
 * `Orientation` tag (TIFF tag 274), which phone cameras write instead of
 * physically rotating pixels. A portrait photo is usually stored landscape with
 * [Rotate90] set, so anything that draws raw decoded pixels shows it sideways.
 *
 * [KiteBitmap.oriented] applies one of these; [ImageInfo.orientation] and
 * `KiteImage.decode(applyOrientation = true)` are where they come from.
 *
 * The [exifValue]s are the wire numbers 1..8. Anything else in a file is
 * treated as [Normal], which is what every renderer does with garbage here.
 */
public enum class Orientation(public val exifValue: Int) {
    /** Stored as displayed. */
    Normal(1),

    /** Mirrored left-to-right. */
    FlipHorizontal(2),

    /** Upside down. */
    Rotate180(3),

    /** Mirrored top-to-bottom. */
    FlipVertical(4),

    /** Mirrored across the main diagonal (transpose): rows become columns. */
    Transpose(5),

    /** Rotate a quarter turn clockwise to display. */
    Rotate90(6),

    /** Mirrored across the anti-diagonal (transverse). */
    Transverse(7),

    /** Rotate a quarter turn counter-clockwise to display. */
    Rotate270(8),
    ;

    /**
     * True when applying this swaps width and height ([Transpose], [Rotate90],
     * [Transverse], [Rotate270]). Useful for laying out before you decode.
     */
    public val swapsAxes: Boolean
        get() = this == Transpose || this == Rotate90 || this == Transverse || this == Rotate270

    public companion object {
        /** Map an EXIF wire value to an [Orientation]; anything invalid is [Normal]. */
        public fun fromExif(value: Int): Orientation =
            entries.firstOrNull { it.exifValue == value } ?: Normal
    }
}

/**
 * What a file says about itself, read from its header alone: no pixel decode, no
 * multi-megabyte allocation. This is what you want for laying out a grid before
 * the images arrive, rejecting an upload that's 20000 px wide, or deciding
 * whether a decoder should claim a file at all.
 *
 * ```kotlin
 * val info = KiteImage.probe(bytes)
 * if (info.width * info.height > budget) reject()
 * grid.reserve(info.displayWidth, info.displayHeight)   // orientation applied
 * ```
 *
 * Cost is proportional to the header, not the image: a few hundred bytes for
 * PNG/JPEG/BMP/TIFF/WebP. GIF and APNG walk the frame structure (skipping every
 * compressed byte) so [frameCount] is exact rather than a guess.
 *
 * @property format the container, same value [KiteImage.detect] returns
 * @property width stored pixel width, before [orientation]
 * @property height stored pixel height, before [orientation]
 * @property bitDepth bits per channel as stored (8 for most things, 1..16 for PNG/TIFF)
 * @property hasAlpha the file declares an alpha channel or transparency. This is
 *   what the container says, not a pixel scan: an RGBA PNG whose pixels are all
 *   opaque still reports true. Use [KiteBitmap.hasTransparency] for the truth.
 * @property frameCount number of animation frames; 1 for stills
 * @property loopCount animation loop count, NETSCAPE/APNG semantics (0 = forever); 1 for stills
 * @property orientation the EXIF orientation, [Orientation.Normal] when absent
 * @property isDecodable whether this build's [KiteImage.decode] can actually
 *   produce pixels for this file. False means the format or a specific feature
 *   isn't implemented; [unsupportedReason] names it.
 * @property unsupportedReason human-readable reason when [isDecodable] is false
 */
public class ImageInfo(
    public val format: ImageFormat,
    public val width: Int,
    public val height: Int,
    public val bitDepth: Int,
    public val hasAlpha: Boolean,
    public val frameCount: Int,
    public val loopCount: Int,
    public val orientation: Orientation,
    public val isDecodable: Boolean,
    public val unsupportedReason: String? = null,
) {
    /** True when [frameCount] > 1. */
    public val isAnimated: Boolean get() = frameCount > 1

    /** [width] after [orientation] is applied: what the user will actually see. */
    public val displayWidth: Int get() = if (orientation.swapsAxes) height else width

    /** [height] after [orientation] is applied. */
    public val displayHeight: Int get() = if (orientation.swapsAxes) width else height

    override fun toString(): String = buildString {
        append("ImageInfo($format ${width}x$height")
        if (bitDepth != 8) append(" ${bitDepth}bit")
        if (hasAlpha) append(" alpha")
        if (isAnimated) append(" ${frameCount}f")
        if (orientation != Orientation.Normal) append(" $orientation")
        if (!isDecodable) append(" UNDECODABLE")
        append(")")
    }
}
