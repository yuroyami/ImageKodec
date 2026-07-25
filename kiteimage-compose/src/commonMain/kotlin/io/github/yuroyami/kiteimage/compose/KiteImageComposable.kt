package io.github.yuroyami.kiteimage.compose

import androidx.compose.foundation.Image
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
import io.github.yuroyami.kiteimage.ImageDecodeException
import io.github.yuroyami.kiteimage.KiteAnimation
import io.github.yuroyami.kiteimage.KiteBitmap
import io.github.yuroyami.kiteimage.KiteImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Display any image KiteImage can decode: and it decides on its own whether to
 * animate. Feed it a GIF and it plays (correct delays, disposal compositing,
 * NETSCAPE loop count, holding the last frame after a finite loop ends); feed it
 * a PNG/BMP and it just draws. One composable, no format branching at call sites.
 *
 * Decoding runs off the UI thread ([Dispatchers.Default]) and re-runs when [data]
 * changes. Until it finishes (or on failure) the composable occupies its layout
 * slot but draws nothing; failures also reach [onError] if provided.
 *
 * Playback is [KiteAnimatedImage]: frame selection by elapsed frame-clock time,
 * so long loops never drift and janky frames get skipped, not stretched.
 *
 * [animate] is the escape hatch: `false` pins the first composited frame of an
 * animated input (thumbnails, previews). Static inputs ignore it.
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
    val currentOnError by rememberUpdatedState(onError)

    val state by produceState<DecodeState>(DecodeState.Loading, data) {
        value = DecodeState.Loading
        value = try {
            // Orientation is applied here, unlike the raw `KiteImage.decode`
            // default: this composable draws for a human, and a phone photo whose
            // EXIF tag says "rotate me" is simply sideways without it.
            DecodeState.Ready(
                withContext(Dispatchers.Default) {
                    KiteImage.decodeAnimation(data, applyOrientation = true)
                },
            )
        } catch (e: ImageDecodeException) {
            currentOnError?.invoke(e)
            DecodeState.Failed
        }
    }

    when (val s = state) {
        is DecodeState.Ready -> KiteAnimatedImage(
            animation = s.animation,
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

/**
 * Display an already-decoded [KiteBitmap] (for callers running their own decode
 * pipeline). Conversion to `ImageBitmap` is remembered per instance.
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
    Image(
        bitmap = remember(bitmap) { bitmap.toImageBitmap() },
        contentDescription = contentDescription,
        modifier = modifier,
        alignment = alignment,
        contentScale = contentScale,
        alpha = alpha,
        colorFilter = colorFilter,
        filterQuality = filterQuality,
    )
}

// ---------------------------------------------------------------------------

private sealed interface DecodeState {
    data object Loading : DecodeState
    data object Failed : DecodeState
    class Ready(val animation: KiteAnimation) : DecodeState
}
