package io.github.yuroyami.imagekodec

/**
 * One presented frame of an animation: the **fully composited** canvas (disposal
 * methods, transparency overlay and frame offsets already applied), plus its
 * display duration.
 *
 * [delayMillis] is the normalised playback delay: raw GIF centiseconds ×10, with
 * the browser quirk applied: a stored delay of 0 or 1 centisecond means "as fast
 * as possible", which every major renderer clamps to 100 ms, so ImageKodec does
 * too. [delayRawCentiseconds] keeps the wire value for anyone who disagrees.
 */
public class KiteFrame(
    public val bitmap: KiteBitmap,
    public val delayMillis: Int,
    public val delayRawCentiseconds: Int,
)

/**
 * A decoded animation: GIF, APNG and animated WebP all arrive in this shape.
 * Static images decode as a single frame, so [ImageKodec.decodeAnimation] is total
 * over every supported format.
 *
 * [loopCount] means the same thing in all three formats: `0` = loop forever,
 * `n > 0` = play the sequence `n` times. A file that carries no loop count at
 * all gets `1` (play once), which is what its absence has always meant in
 * practice.
 *
 * [loopCount] is a [Long] so APNG's full four-byte play field is preserved,
 * including counts above the PNG Third Edition integer limit accepted for
 * compatibility with deployed APNG writers.
 *
 * @throws IllegalArgumentException if [frames] is empty or [loopCount] is negative
 */
public class KiteAnimation @Throws(IllegalArgumentException::class) public constructor(
    public val width: Int,
    public val height: Int,
    public val frames: List<KiteFrame>,
    public val loopCount: Long,
) {
    init {
        require(frames.isNotEmpty()) { "an animation needs at least one frame" }
        require(loopCount >= 0) { "an animation play count must not be negative" }
    }

    public val isAnimated: Boolean get() = frames.size > 1

    /** Sum of frame delays for one loop, in milliseconds. It stops at [Int.MAX_VALUE] instead of wrapping. */
    public val durationMillis: Int
        get() = frames.sumOf { it.delayMillis.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
