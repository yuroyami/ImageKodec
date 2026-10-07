package io.github.yuroyami.imagekodec.compose

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import io.github.yuroyami.imagekodec.KiteAnimation

/**
 * Play an already-decoded [KiteAnimation]: the one frame loop shared by
 * [KiteImage]'s byte-array overload and imagekodec-coil's `KiteAsyncImage`.
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
 * Frames convert to [ImageBitmap] on `Dispatchers.Default`, in order and ahead of
 * playback, once each per [animation] instance, so the thread that draws never pays
 * for the copy. Only a first frame of at most 65,536 pixels converts at once, where
 * that costs less than drawing nothing would; a larger one leaves the slot empty, at
 * the frame's size, until it is ready, and the clock starts when it shows. Should a
 * frame come due before its conversion ends, the newest converted frame stays up, as
 * a browser does when decoding falls behind. Static (single-frame) animations just
 * draw. [animate] pins the first frame when false (thumbnails, previews), and then
 * only that frame converts.
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
    FramePlayer(
        frames = remember(animation) { FrameBitmaps(animation) },
        contentDescription = contentDescription,
        modifier = modifier,
        alignment = alignment,
        contentScale = contentScale,
        alpha = alpha,
        colorFilter = colorFilter,
        filterQuality = filterQuality,
        animate = animate,
    )
}

/** [KiteAnimatedImage] over frames that may already hold converted bitmaps. */
@Composable
internal fun FramePlayer(
    frames: FrameBitmaps,
    contentDescription: String?,
    modifier: Modifier,
    alignment: Alignment,
    contentScale: ContentScale,
    alpha: Float,
    colorFilter: ColorFilter?,
    filterQuality: FilterQuality,
    animate: Boolean,
) {
    val animation = frames.animation
    val playing = animate && animation.isAnimated

    // Convert ahead of playback, away from this thread: every frame when playing,
    // only the one that shows when not.
    LaunchedEffect(frames, playing) {
        frames.fill(if (playing) animation.frames.size else 1)
    }

    val started by remember(frames) { derivedStateOf { frames.ready > 0 } }
    var frameIndex by remember(frames, playing) { mutableIntStateOf(0) }

    if (playing && started) {
        LaunchedEffect(frames) {
            play(frames) { frameIndex = it }
        }
    }

    val bitmap = if (started) frames[if (playing) frameIndex else 0] else null
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            modifier = modifier,
            alignment = alignment,
            contentScale = contentScale,
            alpha = alpha,
            colorFilter = colorFilter,
            filterQuality = filterQuality,
        )
    } else {
        val first = animation.frames[0].bitmap
        Image(
            painter = remember(first.width, first.height) { BlankPainter(first.width, first.height) },
            contentDescription = contentDescription,
            modifier = modifier,
            alignment = alignment,
            contentScale = contentScale,
        )
    }
}

/**
 * Shows, on every frame of the clock, the frame due at the time elapsed since the
 * first one showed, or the newest converted frame when the due one is not ready.
 */
private suspend fun play(frames: FrameBitmaps, show: (Int) -> Unit) {
    val animation = frames.animation
    val last = animation.frames.lastIndex
    // frameEnds[i] = cumulative time at which frame i stops showing.
    // Long: 3300 frames of a GIF's longest delay (655.35 s) already pass Int.MAX_VALUE.
    val frameEnds = LongArray(animation.frames.size)
    var acc = 0L
    for (i in animation.frames.indices) {
        acc += animation.frames[i].delayMillis
        frameEnds[i] = acc
    }
    val loopMillis = acc

    val startNanos = withFrameNanos { it }
    while (true) {
        val settled = withFrameNanos { now ->
            val elapsedMillis = (now - startNanos) / 1_000_000
            // Every delay zero leaves nothing meaningful to play, and a finite loop that
            // has ended holds its last frame.
            val holding = loopMillis <= 0L ||
                (animation.loopCount != 0L && elapsedMillis / loopMillis >= animation.loopCount)
            val due = if (holding) {
                last
            } else {
                val t = elapsedMillis % loopMillis
                var i = 0
                while (frameEnds[i] <= t) i++
                i
            }
            val shown = minOf(due, frames.ready - 1)
            show(shown)   // same value writes don't recompose
            holding && shown == due
        }
        if (settled) return
    }
}
