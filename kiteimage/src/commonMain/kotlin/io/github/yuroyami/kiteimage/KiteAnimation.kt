package io.github.yuroyami.kiteimage

/**
 * One presented frame of an animation: the **fully composited** canvas (disposal
 * methods, transparency overlay and frame offsets already applied), plus its
 * display duration.
 *
 * [delayMillis] is the normalised playback delay: raw GIF centiseconds ×10, with
 * the browser quirk applied: a stored delay of 0 or 1 centisecond means "as fast
 * as possible", which every major renderer clamps to 100 ms, so KiteImage does
 * too. [delayRawCentiseconds] keeps the wire value for anyone who disagrees.
 */
public class KiteFrame(
    public val bitmap: KiteBitmap,
    public val delayMillis: Int,
    public val delayRawCentiseconds: Int,
)

/**
 * A decoded animation (today: GIF; animated WebP will reuse this shape). Static
 * images decode as a single frame, so [KiteImage.decodeAnimation] is total over
 * every supported format.
 *
 * [loopCount] carries the NETSCAPE2.0 semantics as stored: `0` = loop forever,
 * `n > 0` = play the sequence `n` times. Files with no loop extension get `1`
 * (play once): that's what the extension's absence meant in practice.
 */
public class KiteAnimation(
    public val width: Int,
    public val height: Int,
    public val frames: List<KiteFrame>,
    public val loopCount: Int,
) {
    init {
        require(frames.isNotEmpty()) { "an animation needs at least one frame" }
    }

    public val isAnimated: Boolean get() = frames.size > 1

    /** Sum of frame delays for one loop, in milliseconds. */
    public val durationMillis: Int get() = frames.sumOf { it.delayMillis }
}
