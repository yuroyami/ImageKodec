package io.github.yuroyami.kiteimage.coil

import coil3.Canvas
import coil3.Image
import io.github.yuroyami.kiteimage.KiteAnimation
import io.github.yuroyami.kiteimage.KiteBitmap

/**
 * A [coil3.Image] carrying a full decoded [animation]. Coil's own machinery
 * treats it as a static image (drawing the first composited frame), which keeps
 * plain `AsyncImage` working everywhere; [KiteAsyncImage] detects this type in a
 * successful result and takes over playback with its own frame loop.
 *
 * Unlike coil-gif's `AnimatedImageDrawable` wrapper, this image is **stateless**:
 * the frames are immutable and playback position lives in the composable, so
 * sharing one instance between targets and the memory cache is safe. [shareable]
 * therefore *may* be true: [KiteImageDecoder] sets it for animations whose
 * pixel bytes fit under its `maxCacheableAnimationBytes` threshold, which turns
 * a scroll-back in a list into a memory-cache hit instead of a full re-decode.
 * Oversized animations stay unshareable so one huge GIF can't evict the whole
 * cache. [size] reports the exact pixel-byte footprint so Coil's LRU accounting
 * stays honest either way.
 */
public class KiteAnimationImage(
    public val animation: KiteAnimation,
    override val shareable: Boolean = false,
) : Image {

    override val size: Long =
        animation.frames.size.toLong() * animation.width * animation.height * 4

    override val width: Int get() = animation.width
    override val height: Int get() = animation.height

    private val firstFrame: Image by lazy {
        animation.frames.first().bitmap.toCoilImage(shareable = true)
    }

    override fun draw(canvas: Canvas) {
        firstFrame.draw(canvas)
    }
}

/** Platform bitmap-backed [coil3.Image] from a [KiteBitmap] (static path). */
internal expect fun KiteBitmap.toCoilImage(shareable: Boolean): Image
