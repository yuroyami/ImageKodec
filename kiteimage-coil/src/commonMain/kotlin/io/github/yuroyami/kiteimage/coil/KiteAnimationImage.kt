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
 * [shareable] is false, the same contract as coil-gif's Android decoder, so
 * Coil skips the memory cache for animations and re-decodes from the disk cache
 * on revisit instead of holding every frame of every GIF in RAM.
 */
public class KiteAnimationImage(
    public val animation: KiteAnimation,
) : Image {

    override val size: Long =
        animation.frames.size.toLong() * animation.width * animation.height * 4

    override val width: Int get() = animation.width
    override val height: Int get() = animation.height
    override val shareable: Boolean get() = false

    private val firstFrame: Image by lazy {
        animation.frames.first().bitmap.toCoilImage(shareable = true)
    }

    override fun draw(canvas: Canvas) {
        firstFrame.draw(canvas)
    }
}

/** Platform bitmap-backed [coil3.Image] from a [KiteBitmap] (static path). */
internal expect fun KiteBitmap.toCoilImage(shareable: Boolean): Image
