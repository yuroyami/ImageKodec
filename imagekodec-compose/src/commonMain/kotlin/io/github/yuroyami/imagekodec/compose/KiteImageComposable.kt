package io.github.yuroyami.imagekodec.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.KiteAnimation
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.KiteFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Display any image ImageKodec can decode: and it decides on its own whether to
 * animate. Feed it a GIF and it plays (correct delays, disposal compositing,
 * NETSCAPE loop count, holding the last frame after a finite loop ends); feed it
 * a PNG/BMP and it just draws. One composable, no format branching at call sites.
 *
 * Decoding runs off the UI thread ([Dispatchers.Default]) and re-runs when [data]
 * changes. It stops between frames once the composable leaves composition or
 * [data] changes, so a list scrolled past animated images does not queue their
 * abandoned decodes ahead of the visible ones. The first frame converts to an
 * `ImageBitmap` in the same background step, and the rest convert there ahead of
 * playback, as [KiteAnimatedImage] describes. Until the first frame is ready (or
 * on failure) the composable occupies its layout slot but draws nothing;
 * failures also reach [onError] if provided.
 *
 * Playback is [KiteAnimatedImage]: frame selection by elapsed frame-clock time,
 * so long loops never drift and janky frames get skipped, not stretched.
 *
 * [animate] is the escape hatch: `false` pins the first composited frame of an
 * animated input (thumbnails, previews), and only that frame is decoded. Turning
 * it on later decodes the rest while the pinned frame stays on screen; turning it
 * off keeps the frames already decoded. Static inputs ignore it.
 */
@Composable
public fun KiteImage(
    data: ByteArray,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
    colorFilter: ColorFilter? = null,
    filterQuality: FilterQuality = DrawScope.DefaultFilterQuality,
    animate: Boolean = true,
    onError: ((ImageDecodeException) -> Unit)? = null,
) {
    DecodedImage(
        data, contentDescription, modifier, alignment, contentScale, alpha, colorFilter, filterQuality,
        animate, onError, decodeForDisplay,
    )
}

/**
 * Display an already-decoded [KiteBitmap] (for callers running their own decode
 * pipeline). Conversion to `ImageBitmap` is remembered per instance and runs on
 * `Dispatchers.Default`, except for a bitmap of at most 65,536 pixels, which
 * converts at once because that costs less than a frame of nothing. Until a larger
 * one is ready, the composable lays out at the bitmap's size and draws nothing.
 */
@Composable
public fun KiteImage(
    bitmap: KiteBitmap,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
    colorFilter: ColorFilter? = null,
    filterQuality: FilterQuality = DrawScope.DefaultFilterQuality,
) {
    FramePlayer(
        frames = remember(bitmap) { FrameBitmaps(KiteAnimation(bitmap.width, bitmap.height, listOf(KiteFrame(bitmap, 0, 0)), 1)) },
        contentDescription = contentDescription,
        modifier = modifier,
        alignment = alignment,
        contentScale = contentScale,
        alpha = alpha,
        colorFilter = colorFilter,
        filterQuality = filterQuality,
        animate = false,
    )
}

// ---------------------------------------------------------------------------

/**
 * Decodes at most `maxFrames` frames of `data` and calls `cancellationCheck`
 * between them. A seam: tests pass one that counts frames.
 */
internal typealias AnimationDecoder = (data: ByteArray, maxFrames: Int, cancellationCheck: () -> Unit) -> KiteAnimation

internal val decodeForDisplay: AnimationDecoder = { data, maxFrames, cancellationCheck ->
    // Orientation is applied here, unlike the raw `ImageKodec.decode` default:
    // this composable draws for a human, and a phone photo whose EXIF tag says
    // "rotate me" is simply sideways without it.
    ImageKodec.decodeAnimation(data, applyOrientation = true, maxFrames = maxFrames, cancellationCheck = cancellationCheck)
}

/** [KiteImage] over a [decode] of its own. */
@Composable
internal fun DecodedImage(
    data: ByteArray,
    contentDescription: String?,
    modifier: Modifier,
    alignment: Alignment,
    contentScale: ContentScale,
    alpha: Float,
    colorFilter: ColorFilter?,
    filterQuality: FilterQuality,
    animate: Boolean,
    onError: ((ImageDecodeException) -> Unit)?,
    decode: AnimationDecoder,
) {
    val currentOnError by rememberUpdatedState(onError)

    val state by produceState<DecodeState>(DecodeState.Loading, data, animate) {
        val held = (value as? DecodeState.Ready)?.takeIf { it.data === data }
        // Every frame is already there, or the pinned one is all that is asked for.
        if (held != null && (held.complete || !animate)) return@produceState
        // A pinned frame stays on screen while the rest decodes.
        if (held == null) value = DecodeState.Loading
        try {
            value = withContext(Dispatchers.Default) {
                val animation = decode(data, if (animate) Int.MAX_VALUE else 1) { ensureActive() }
                val first = animation.frames[0].bitmap.toPreparedImageBitmap()
                DecodeState.Ready(data, FrameBitmaps(animation, first), complete = animate)
            }
        } catch (e: ImageDecodeException) {
            currentOnError?.invoke(e)
            // A later frame that fails takes nothing away from the frame already showing.
            if (held == null) value = DecodeState.Failed
        }
    }

    when (val s = state) {
        is DecodeState.Ready -> FramePlayer(
            frames = s.frames,
            contentDescription = contentDescription,
            modifier = modifier,
            alignment = alignment,
            contentScale = contentScale,
            alpha = alpha,
            colorFilter = colorFilter,
            filterQuality = filterQuality,
            animate = animate,
        )
        // Loading / Failed: hold the layout slot, draw nothing.
        else -> Box(modifier)
    }
}

private sealed interface DecodeState {
    data object Loading : DecodeState
    data object Failed : DecodeState

    /** [frames] of [data], all of them when [complete], or only the first. */
    class Ready(val data: ByteArray, val frames: FrameBitmaps, val complete: Boolean) : DecodeState
}
