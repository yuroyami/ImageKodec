package io.github.yuroyami.kiteimage.coil

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.compose.asPainter
import coil3.compose.rememberConstraintsSizeResolver
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import io.github.yuroyami.kiteimage.compose.KiteAnimatedImage

/**
 * `AsyncImage`, but animations actually play: on every target.
 *
 * Coil does what Coil is for: [model] goes through its full pipeline (network
 * fetchers, disk cache, memory cache, request lifecycle) via [imageLoader].
 * Rendering is ours: when the result is a [KiteAnimationImage] (produced by a
 * registered [KiteImageDecoder]), this composable plays it with
 * [KiteAnimatedImage]'s elapsed-time frame loop (per-frame delays, loop count,
 * last-frame hold, no drift), which Coil's static painter can't do outside
 * Android. Any other result renders exactly as `AsyncImage` would, via
 * `Image.asPainter`.
 *
 * The request carries this composable's **layout constraints** as its target
 * size (unless [model] is an [ImageRequest] that already defines one), so
 * [KiteImageDecoder] downscales still images *and every animation frame* to
 * what will actually be drawn instead of decoding wallpaper-sized pixels for an
 * avatar slot.
 *
 * [model] may be anything Coil accepts as data, or a prebuilt [ImageRequest]
 * (used as-is, plus the constraints size when it doesn't define its own).
 * [animate] pins animated results to their first frame when false. [placeholder]
 * shows while the request is in flight, [error] on failure; [onSuccess] /
 * [onError] fire once per completed request.
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
    onSuccess: ((SuccessResult) -> Unit)? = null,
    onError: ((ErrorResult) -> Unit)? = null,
) {
    val context = LocalPlatformContext.current
    val sizeResolver = rememberConstraintsSizeResolver()
    val currentOnSuccess by rememberUpdatedState(onSuccess)
    val currentOnError by rememberUpdatedState(onError)

    // The resolver doubles as a layout modifier feeding measured constraints to
    // the in-flight request; every branch below must keep it in the chain.
    val chainedModifier = modifier.then(sizeResolver)

    val request = remember(model, context, sizeResolver) {
        when {
            model is ImageRequest && model.defined.sizeResolver != null -> model
            model is ImageRequest -> model.newBuilder().size(sizeResolver).build()
            else -> ImageRequest.Builder(context).data(model).size(sizeResolver).build()
        }
    }

    var result by remember(request, imageLoader) { mutableStateOf<ImageResult?>(null) }

    LaunchedEffect(request, imageLoader) {
        val r = imageLoader.execute(request)
        result = r
        when (r) {
            is SuccessResult -> currentOnSuccess?.invoke(r)
            is ErrorResult -> currentOnError?.invoke(r)
        }
    }

    when (val r = result) {
        is SuccessResult -> {
            val image = r.image
            if (image is KiteAnimationImage) {
                KiteAnimatedImage(
                    animation = image.animation,
                    contentDescription = contentDescription,
                    modifier = chainedModifier,
                    alignment = alignment,
                    contentScale = contentScale,
                    alpha = alpha,
                    colorFilter = colorFilter,
                    filterQuality = filterQuality,
                    animate = animate,
                )
            } else {
                Image(
                    painter = image.asPainter(context, filterQuality),
                    contentDescription = contentDescription,
                    modifier = chainedModifier,
                    alignment = alignment,
                    contentScale = contentScale,
                    alpha = alpha,
                    colorFilter = colorFilter,
                )
            }
        }
        is ErrorResult -> PainterOrEmpty(error, contentDescription, chainedModifier, alignment, contentScale, alpha, colorFilter)
        else -> PainterOrEmpty(placeholder, contentDescription, chainedModifier, alignment, contentScale, alpha, colorFilter)
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
