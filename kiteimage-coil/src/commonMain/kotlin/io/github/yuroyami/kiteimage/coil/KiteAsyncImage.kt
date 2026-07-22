package io.github.yuroyami.kiteimage.coil

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.compose.asPainter
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import io.github.yuroyami.kiteimage.KiteAnimation
import io.github.yuroyami.kiteimage.compose.toImageBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * `AsyncImage`, but animations actually play: on every target.
 *
 * Coil does what Coil is for: [model] goes through its full pipeline (network
 * fetchers, disk cache, memory cache, request lifecycle) via [imageLoader].
 * Rendering is ours: when the result is a [KiteAnimationImage] (produced by a
 * registered [KiteImageDecoder]), this composable runs KiteImage's frame loop
 * (per-frame delays, loop count, last-frame hold) instead of Coil's static
 * painter, which has no animation driver outside Android. Any other result
 * renders exactly as `AsyncImage` would, via `Image.asPainter`.
 *
 * [animate] pins animated results to their first frame when false. [placeholder]
 * shows while the request is in flight, [error] on failure; both optional.
 */
@Composable
public fun KiteAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    imageLoader: ImageLoader = SingletonImageLoader.get(LocalPlatformContext.current),
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
    colorFilter: ColorFilter? = null,
    filterQuality: FilterQuality = DrawScope.DefaultFilterQuality,
    animate: Boolean = true,
    placeholder: Painter? = null,
    error: Painter? = null,
) {
    val context = LocalPlatformContext.current
    var result by remember(model, imageLoader) { mutableStateOf<ImageResult?>(null) }

    LaunchedEffect(model, imageLoader) {
        result = imageLoader.execute(
            ImageRequest.Builder(context).data(model).build(),
        )
    }

    when (val r = result) {
        is SuccessResult -> {
            val image = r.image
            if (image is KiteAnimationImage && image.animation.isAnimated && animate) {
                AnimatedFrames(
                    animation = image.animation,
                    contentDescription = contentDescription,
                    modifier = modifier,
                    alignment = alignment,
                    contentScale = contentScale,
                    alpha = alpha,
                    colorFilter = colorFilter,
                    filterQuality = filterQuality,
                )
            } else if (image is KiteAnimationImage) {
                Image(
                    bitmap = remember(image) { image.animation.frames.first().bitmap.toImageBitmap() },
                    contentDescription = contentDescription,
                    modifier = modifier,
                    alignment = alignment,
                    contentScale = contentScale,
                    alpha = alpha,
                    colorFilter = colorFilter,
                    filterQuality = filterQuality,
                )
            } else {
                Image(
                    painter = image.asPainter(context, filterQuality),
                    contentDescription = contentDescription,
                    modifier = modifier,
                    alignment = alignment,
                    contentScale = contentScale,
                    alpha = alpha,
                    colorFilter = colorFilter,
                )
            }
        }
        is ErrorResult -> PainterOrEmpty(error, contentDescription, modifier, alignment, contentScale, alpha, colorFilter)
        else -> PainterOrEmpty(placeholder, contentDescription, modifier, alignment, contentScale, alpha, colorFilter)
    }
}

@Composable
private fun PainterOrEmpty(
    painter: Painter?,
    contentDescription: String?,
    modifier: Modifier,
    alignment: Alignment,
    contentScale: ContentScale,
    alpha: Float,
    colorFilter: ColorFilter?,
) {
    if (painter != null) {
        Image(
            painter = painter,
            contentDescription = contentDescription,
            modifier = modifier,
            alignment = alignment,
            contentScale = contentScale,
            alpha = alpha,
            colorFilter = colorFilter,
        )
    } else {
        Box(modifier)
    }
}

@Composable
private fun AnimatedFrames(
    animation: KiteAnimation,
    contentDescription: String?,
    modifier: Modifier,
    alignment: Alignment,
    contentScale: ContentScale,
    alpha: Float,
    colorFilter: ColorFilter?,
    filterQuality: FilterQuality,
) {
    val frameCache = remember(animation) { arrayOfNulls<ImageBitmap>(animation.frames.size) }
    fun frameAt(i: Int): ImageBitmap =
        frameCache[i] ?: animation.frames[i].bitmap.toImageBitmap().also { frameCache[i] = it }

    var frameIndex by remember(animation) { mutableIntStateOf(0) }

    LaunchedEffect(animation) {
        var loopsDone = 0
        // NETSCAPE semantics: 0 = forever, n = play the sequence n times.
        while (isActive && (animation.loopCount == 0 || loopsDone < animation.loopCount)) {
            for (i in animation.frames.indices) {
                frameIndex = i
                delay(animation.frames[i].delayMillis.toLong())
            }
            loopsDone++
        }
        frameIndex = animation.frames.lastIndex   // finite loop ended: hold last frame
    }

    Image(
        bitmap = frameAt(frameIndex),
        contentDescription = contentDescription,
        modifier = modifier,
        alignment = alignment,
        contentScale = contentScale,
        alpha = alpha,
        colorFilter = colorFilter,
        filterQuality = filterQuality,
    )
}
