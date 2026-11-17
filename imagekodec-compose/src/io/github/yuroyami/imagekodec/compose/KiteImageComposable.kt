package io.github.yuroyami.imagekodec.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import io.github.yuroyami.imagekodec.ColorTarget
import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.ImageFormat
import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.KiteAnimation
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.KiteFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/**
 * Display any image ImageKodec can decode: and it decides on its own whether to
 * animate. Feed it a GIF and it plays (correct delays, disposal compositing,
 * NETSCAPE loop count, holding the last frame after a finite loop ends); feed it
 * a PNG/BMP and it just draws. One composable, no format branching at call sites.
 *
 * Decoding runs off the UI thread ([Dispatchers.Default]) after the first layout,
 * and re-runs when [data] changes. A still decodes at the size it draws at in
 * that layout, as [contentScale] fits it, through `ImageKodec.decodeDownscaledTo`:
 * a JPEG, JPEG 2000 or JPEG XR reduces inside its inverse transform, and a HEIC
 * or AVIF grid one row of tiles at a time, so a large photo shown small never
 * exists at full size. When the layout later gives it a larger box, it decodes
 * again at the new size. A decode stops once the composable leaves composition or
 * [data] changes: a still at the decoder's next step, such as a row of JPEG
 * blocks or a HEIC tile, and an animation between frames, so a list scrolled past
 * large images does not queue their abandoned decodes ahead of the visible ones.
 *
 * HEIC (HEVC) needs the `imagekodec-heic` module and a call to
 * `ImageKodecHeic.install()` at startup, which this module does not make; without
 * it a HEIC file fails with an `UnsupportedImageException` that [onError]
 * receives. AVIF needs nothing more.
 *
 * The first frame converts to an
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
 *
 * The colours are converted to sRGB by default, through the ICC profile or the
 * colour chunks the file carries, as a browser draws it. A file that declares
 * nothing, or sRGB, is not touched. Pass [ColorTarget.Source] as [colorTarget] to
 * draw the samples as stored.
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
    colorTarget: ColorTarget = ColorTarget.Srgb(),
    onError: ((ImageDecodeException) -> Unit)? = null,
) {
    DecodedImage(
        data, contentDescription, modifier, alignment, contentScale, alpha, colorFilter, filterQuality,
        animate, onError, remember(colorTarget) { displayDecoder(colorTarget) },
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
 * The box a [KiteImage] draws in, from the constraints of its layout, and how [contentScale] fits
 * the image to it. A side of [Constraints.Infinity] is unbounded.
 */
internal class DrawBounds(maxWidth: Int, maxHeight: Int, val contentScale: ContentScale) {
    // A side of 0 says nothing yet about the size the image will draw at, so it bounds nothing.
    val maxWidth: Int = if (maxWidth <= 0) Constraints.Infinity else maxWidth
    val maxHeight: Int = if (maxHeight <= 0) Constraints.Infinity else maxHeight

    /**
     * The size a [width] by [height] image is decoded at to draw in this box: scaled as [contentScale]
     * scales it, rounded up so it covers the box, and never larger than the image. A [ContentScale]
     * other than the six of Compose, such as [ContentScale.None], gives the image's own size.
     */
    fun sizeFor(width: Int, height: Int): IntSize {
        val sx = if (maxWidth == Constraints.Infinity) null else maxWidth.toDouble() / width
        val sy = if (maxHeight == Constraints.Infinity) null else maxHeight.toDouble() / height
        val scale = when (contentScale) {
            ContentScale.Fit, ContentScale.Inside -> if (sx != null && sy != null) minOf(sx, sy) else sx ?: sy
            ContentScale.Crop, ContentScale.FillBounds -> if (sx != null && sy != null) maxOf(sx, sy) else sx ?: sy
            ContentScale.FillWidth -> sx
            ContentScale.FillHeight -> sy
            else -> null
        }
        if (scale == null || scale >= 1.0) return IntSize(width, height)
        // The small margin keeps an exact product, such as 0.1 * 640, from rounding up to the next pixel.
        fun side(full: Int) = ceil(full * scale - 1e-9).toInt().coerceIn(1, full)
        return IntSize(side(width), side(height))
    }

    /** Whether an image decoded for [earlier] may need more pixels to draw in this box. */
    fun grewFrom(earlier: DrawBounds): Boolean =
        contentScale != earlier.contentScale || maxWidth > earlier.maxWidth || maxHeight > earlier.maxHeight

    override fun equals(other: Any?): Boolean =
        other is DrawBounds && maxWidth == other.maxWidth && maxHeight == other.maxHeight && contentScale == other.contentScale

    override fun hashCode(): Int = (maxWidth * 31 + maxHeight) * 31 + contentScale.hashCode()
}

/** What an [AnimationDecoder] gives: the frames, and whether they are smaller than the image. */
internal class DisplayImage(val animation: KiteAnimation, val reduced: Boolean)

/**
 * Decodes at most `maxFrames` frames of `data` for drawing in `bounds` and calls
 * `cancellationCheck` while it works. A seam: tests pass one that counts frames.
 */
internal typealias AnimationDecoder =
    (data: ByteArray, maxFrames: Int, bounds: DrawBounds, cancellationCheck: () -> Unit) -> DisplayImage

internal fun displayDecoder(colorTarget: ColorTarget): AnimationDecoder = { data, maxFrames, bounds, cancellationCheck ->
    // Orientation is applied here, unlike the raw `ImageKodec.decode` default:
    // this composable draws for a human, and a phone photo whose EXIF tag says
    // "rotate me" is simply sideways without it.
    val info = ImageKodec.probeOrNull(data)
    if (info != null && info.frameCount == 1 && info.format != ImageFormat.PNG) {
        // A still decodes at the size it draws at. A JPEG, JPEG 2000, JPEG XR and a HEIC or AVIF grid
        // reduce while they decode, so the full-size pixels never exist; the other formats decode in
        // full and scale. A PNG keeps the frame path, where a one-frame APNG shows its animation frame
        // rather than its default image.
        val size = bounds.sizeFor(info.displayWidth, info.displayHeight)
        val bitmap = ImageKodec.decodeDownscaledTo(
            data, size.width, size.height, applyOrientation = true, colorTarget = colorTarget, cancellationCheck = cancellationCheck,
        )
        val still = KiteAnimation(bitmap.width, bitmap.height, listOf(KiteFrame(bitmap, delayMillis = 0, delayRawCentiseconds = 0)), loopCount = 1)
        DisplayImage(still, reduced = size.width < info.displayWidth || size.height < info.displayHeight)
    } else {
        val animation = ImageKodec.decodeAnimation(
            data, applyOrientation = true, maxFrames = maxFrames, colorTarget = colorTarget, cancellationCheck = cancellationCheck,
        )
        DisplayImage(animation, reduced = false)
    }
}

/** What [KiteImage] decodes with by default: colours converted to sRGB. */
internal val decodeForDisplay: AnimationDecoder = displayDecoder(ColorTarget.Srgb())

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
    // The box of the last layout. Nothing decodes before the first one, so a still decodes at the size it draws at.
    val drawBounds = remember { mutableStateOf<DrawBounds?>(null) }
    val measured = modifier.layout { measurable, constraints ->
        val bounds = DrawBounds(constraints.maxWidth, constraints.maxHeight, contentScale)
        if (Snapshot.withoutReadObservation { drawBounds.value } != bounds) drawBounds.value = bounds
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    val state by produceState<DecodeState>(DecodeState.Loading, data, animate, decode) {
        val held = (value as? DecodeState.Ready)?.takeIf { it.data === data && it.decode === decode }
        var shown = held

        /** Decodes for [bounds] and shows the result, or reports the failure and gives null. */
        suspend fun load(bounds: DrawBounds): DecodeState.Ready? = try {
            withContext(Dispatchers.Default) {
                val decoded = decode(data, if (animate) Int.MAX_VALUE else 1, bounds) { ensureActive() }
                val first = decoded.animation.frames[0].bitmap.toPreparedImageBitmap()
                DecodeState.Ready(data, decode, FrameBitmaps(decoded.animation, first), complete = animate, bounds, decoded.reduced)
            }.also {
                value = it
                shown = it
            }
        } catch (e: ImageDecodeException) {
            currentOnError?.invoke(e)
            // A later frame that fails takes nothing away from the frame already showing.
            if (shown == null) value = DecodeState.Failed
            null
        }

        // Every frame is already there, or the pinned one is all that is asked for.
        var ready = if (held != null && (held.complete || !animate)) {
            held
        } else {
            // A pinned frame stays on screen while the rest decodes.
            if (held == null) value = DecodeState.Loading
            load(snapshotFlow { drawBounds.value }.filterNotNull().first()) ?: return@produceState
        }
        // A still decoded smaller than the image decodes again when its box grows past the one it was decoded for.
        if (!ready.reduced) return@produceState
        snapshotFlow { drawBounds.value }.filterNotNull().collect { bounds ->
            if (ready.reduced && bounds.grewFrom(ready.bounds)) ready = load(bounds) ?: ready
        }
    }

    when (val s = state) {
        is DecodeState.Ready -> FramePlayer(
            frames = s.frames,
            contentDescription = contentDescription,
            modifier = measured,
            alignment = alignment,
            contentScale = contentScale,
            alpha = alpha,
            colorFilter = colorFilter,
            filterQuality = filterQuality,
            animate = animate,
        )
        // Loading / Failed: hold the layout slot, draw nothing.
        else -> Box(measured)
    }
}

private sealed interface DecodeState {
    data object Loading : DecodeState
    data object Failed : DecodeState

    /**
     * [frames] of [data] as [decode] gave them for [bounds], all of them when [complete], or only the
     * first, and smaller than the image when [reduced].
     */
    class Ready(
        val data: ByteArray,
        val decode: AnimationDecoder,
        val frames: FrameBitmaps,
        val complete: Boolean,
        val bounds: DrawBounds,
        val reduced: Boolean,
    ) : DecodeState
}
