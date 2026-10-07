package io.github.yuroyami.imagekodec.coil

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
import coil3.size.Scale
import coil3.size.SizeResolver
import io.github.yuroyami.imagekodec.compose.KiteAnimatedImage

/**
 * `AsyncImage`, but animations actually play: on every target.
 *
 * Coil does what Coil is for: [model] goes through its full pipeline (network
 * fetchers, disk cache, memory cache, request lifecycle) via [imageLoader].
 * Rendering is ours. When the result is a [KiteAnimationImage] (produced by a
 * registered [KiteImageDecoder]), this composable plays it with
 * `KiteAnimatedImage`'s elapsed-time frame loop: per-frame delays, loop count,
 * last-frame hold and no drift. That composable lives in `imagekodec-compose`,
 * which this module depends on as `implementation`, so declare it yourself if
 * you want to call it directly. Any other result renders exactly as `AsyncImage`
 * would, via `Image.asPainter`.
 *
 * The request carries this composable's **layout constraints** as its target
 * size, while [ContentScale.None] requests the original pixels. Drawing scales
 * map to Coil's FIT/FILL policy; an [ImageRequest]'s explicit size and scale
 * take precedence, so
 * [KiteImageDecoder] downscales still images *and every animation frame* to
 * what will actually be drawn. A still JPEG or JPEG 2000 also decodes at a
 * reduced size inside its inverse transform, so a photo in an avatar slot
 * never exists at wallpaper size; other formats decode in full first and keep
 * only the downscaled pixels.
 *
 * [model] may be anything Coil accepts as data, or a prebuilt [ImageRequest]
 * (with size and scale inferred from [contentScale] when not explicitly set).
 * [animate] pins animated results to their first frame when false. [placeholder]
 * shows while the request is in flight. On failure, [error] overrides the
 * request's error image; when absent, that image is displayed. [onSuccess] /
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

    val request = remember(model, context, sizeResolver, contentScale) {
        if (model is ImageRequest && model.defined.sizeResolver != null && model.defined.scale != null) {
            return@remember model
        }
        val builder = if (model is ImageRequest) model.newBuilder() else ImageRequest.Builder(context).data(model)
        if (model !is ImageRequest || model.defined.sizeResolver == null) {
            builder.size(if (contentScale == ContentScale.None) SizeResolver.ORIGINAL else sizeResolver)
        }
        if (model !is ImageRequest || model.defined.scale == null) {
            builder.scale(if (contentScale == ContentScale.Fit || contentScale == ContentScale.Inside) Scale.FIT else Scale.FILL)
        }
        builder.build()
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
        is ErrorResult -> PainterOrEmpty(error ?: r.image?.asPainter(context, filterQuality),
            contentDescription, chainedModifier, alignment, contentScale, alpha, colorFilter)
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
