package io.github.yuroyami.kiteimage.compose

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import io.github.yuroyami.kiteimage.KiteAnimation
import kotlinx.coroutines.isActive

/**
 * Play an already-decoded [KiteAnimation]: the one frame loop shared by
 * [KiteImage]'s byte-array overload and kiteimage-coil's `KiteAsyncImage`.
 * Public because callers running their own decode pipeline deserve playback
 * too, exactly like the [KiteImage] overload that takes a `KiteBitmap`.
 *
 * The frame to show is derived from **elapsed frame-clock time** against the
 * animation's cumulative delays (not chained `delay()` calls), so scheduling
 * latency never accumulates: playback can't drift slow over long loops, and a
 * janky UI skips frames like every browser does instead of slowing the GIF
 * down. Honors per-frame delays, the NETSCAPE loop count (0 = forever), and
 * holds the last frame once a finite loop completes.
 *
 * Frames convert to [ImageBitmap] lazily, once each, cached per [animation]
 * instance. Static (single-frame) animations just draw. [animate] pins the
 * first frame when false (thumbnails, previews).
 */
@Composable
public fun KiteAnimatedImage(
    animation: KiteAnimation,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
    colorFilter: ColorFilter? = null,
    filterQuality: FilterQuality = DrawScope.DefaultFilterQuality,
    animate: Boolean = true,
) {
    val frameCache = remember(animation) { arrayOfNulls<ImageBitmap>(animation.frames.size) }
    fun frameAt(i: Int): ImageBitmap =
        frameCache[i] ?: animation.frames[i].bitmap.toImageBitmap().also { frameCache[i] = it }

    var frameIndex by remember(animation, animate) { mutableIntStateOf(0) }

    if (animate && animation.isAnimated) {
        LaunchedEffect(animation) {
            val frames = animation.frames
            // frameEnds[i] = cumulative time at which frame i stops showing.
            val frameEnds = IntArray(frames.size)
            var acc = 0
            for (i in frames.indices) {
                acc += frames[i].delayMillis
                frameEnds[i] = acc
            }
            val loopMillis = acc.toLong()
            if (loopMillis <= 0L) {
                // Degenerate: every delay is zero; nothing meaningful to play.
                frameIndex = frames.lastIndex
                return@LaunchedEffect
            }

            val startNanos = withFrameNanos { it }
            var finished = false
            while (isActive && !finished) {
                withFrameNanos { now ->
                    val elapsedMillis = (now - startNanos) / 1_000_000
                    val loopsDone = elapsedMillis / loopMillis
                    if (animation.loopCount != 0 && loopsDone >= animation.loopCount) {
                        frameIndex = frames.lastIndex   // finite loop ended: hold last frame
                        finished = true
                    } else {
                        val t = (elapsedMillis % loopMillis).toInt()
                        var i = 0
                        while (frameEnds[i] <= t) i++
                        frameIndex = i   // same value writes don't recompose
                    }
                }
            }
        }
    }

    Image(
        bitmap = frameAt(if (animate) frameIndex else 0),
        contentDescription = contentDescription,
        modifier = modifier,
        alignment = alignment,
        contentScale = contentScale,
        alpha = alpha,
        colorFilter = colorFilter,
        filterQuality = filterQuality,
    )
}
