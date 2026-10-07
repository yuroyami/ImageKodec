package io.github.yuroyami.imagekodec.coil

import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.maxBitmapSize
import io.github.yuroyami.imagekodec.ImageFormat
import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.downscaledTo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.use

/**
 * A [coil3.decode.Decoder] backed by ImageKodec's pure-Kotlin codecs.
 *
 * Register the [Factory] on your ImageLoader and Coil keeps doing everything it
 * is good at (network fetch, disk + memory cache, request lifecycle) while
 * decoding runs through ImageKodec with identical behavior on every target:
 *
 * ```kotlin
 * ImageLoader.Builder(context)
 *     .components { add(KiteImageDecoder.Factory()) }
 *     .build()
 * ```
 *
 * The factory uses `ImageKodec.probe` and a complete WebP chunk-header walk to
 * decide whether this build can decode the bytes. It claims PNG and APNG, JPEG,
 * GIF, BMP, lossy and lossless WebP (including animations), TIFF and JP2.
 * Unsupported formats such as SVG, CgBI PNGs and lossless/arithmetic JPEGs fall
 * through to Coil's platform decoders.
 *
 * Static results honor the request's size, FIT/FILL scale, single defined side
 * and maximum bitmap size (box-filter downscale, never up), and memory-cache
 * normally. A still JPEG or JPEG 2000 passes that size down to the decoder,
 * which reduces inside its inverse transform, so a photo shown as an avatar
 * never exists at full size. Animated results come back as
 * [KiteAnimationImage] with **every frame downscaled** to the target size: that
 * is where animated memory goes: and are marked shareable (memory-cacheable)
 * when their pixel bytes fit under [Factory.maxCacheableAnimationBytes].
 * A cancelled request (fast scroll) aborts a multi-frame decode at the next
 * frame boundary instead of finishing the whole file, and a request with
 * [maxFrames] decodes only that many frames.
 */
public class KiteImageDecoder(
    private val source: ImageSource,
    private val options: Options,
    private val maxCacheableAnimationBytes: Long = Factory.DEFAULT_MAX_CACHEABLE_ANIMATION_BYTES,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val bytes = source.source().use { it.readByteArray() }
        val ctx = currentCoroutineContext()
        // EXIF orientation is applied, matching what Coil's own platform decoders
        // do: a decoder that quietly showed every portrait photo on its side would
        // be a regression against a stock ImageLoader, not a neutral swap.
        val info = ImageKodec.probeOrNull(bytes)
        if (info != null && info.frameCount == 1 && (info.format == ImageFormat.JPEG || info.format == ImageFormat.JP2)) {
            // A still JPEG or JPEG 2000 reduces inside its decoder, so the size goes down to it and the
            // full-size pixels never exist.
            val (width, height) = targetSize(info.displayWidth, info.displayHeight)
            val bitmap = ImageKodec.decodeDownscaledTo(bytes, width, height, applyOrientation = true)
            val sampled = width < info.displayWidth || height < info.displayHeight
            return DecodeResult(image = bitmap.toCoilImage(shareable = true), isSampled = sampled)
        }
        // A request for fewer frames, such as a paused thumbnail's, decodes no more than those.
        val animation = ImageKodec.decodeAnimation(bytes, applyOrientation = true, maxFrames = options.maxFrames) {
            ctx.ensureActive()
        }
        val (width, height) = targetSize(animation.width, animation.height)
        val sampled = width < animation.width || height < animation.height

        if (animation.isAnimated) {
            val anim = if (sampled) animation.downscaledTo(width, height) else animation
            val pixelBytes = anim.frames.size.toLong() * anim.width * anim.height * 4
            return DecodeResult(
                image = KiteAnimationImage(anim, shareable = pixelBytes <= maxCacheableAnimationBytes),
                isSampled = sampled,
            )
        }

        val original = animation.frames.first().bitmap
        val bitmap = if (sampled) original.downscaledTo(width, height) else original
        return DecodeResult(image = bitmap.toCoilImage(shareable = true), isSampled = sampled)
    }

    /**
     * The size Coil draws a [sourceWidth] by [sourceHeight] image at for this request: its size, FIT
     * or FILL scale, single defined side and maximum bitmap size, never larger than the source.
     */
    @OptIn(ExperimentalCoilApi::class)
    private fun targetSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        val target = DecodeUtils.computeDstSize(sourceWidth, sourceHeight,
            options.size, options.scale, options.maxBitmapSize)
        val multiplier = DecodeUtils.computeSizeMultiplier(sourceWidth, sourceHeight,
            target.first, target.second, options.scale, options.maxBitmapSize).coerceAtMost(1.0)
        // Coil's Skia conversion floors the scaled sides independently. Fitting
        // that output box again would shrink non-integral ratios a second time.
        val width = (sourceWidth * multiplier).toInt().coerceAtLeast(1)
        val height = (sourceHeight * multiplier).toInt().coerceAtLeast(1)
        return width to height
    }

    /**
     * [maxCacheableAnimationBytes]: animations whose decoded pixel bytes
     * (frames × width × height × 4) fit under this threshold are marked
     * shareable, so Coil's memory cache keeps them and revisits skip the
     * re-decode entirely. Animations above it stay unshareable (decode again
     * from the disk cache on revisit) so a single huge GIF cannot evict
     * everything else. `0` restores the old always-skip behavior;
     * [Long.MAX_VALUE] caches unconditionally.
     */
    public class Factory(
        private val maxCacheableAnimationBytes: Long = DEFAULT_MAX_CACHEABLE_ANIMATION_BYTES,
    ) : Decoder.Factory {

        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            val peek = result.source.source().peek()
            peek.request(PEEK_BYTES)
            val header = peek.buffer.readByteArray(minOf(PEEK_BYTES, peek.buffer.size))

            val info = ImageKodec.probeOrNull(header)
            val claimed = when {
                // A lossless first frame does not prove that later frames use
                // a supported codec; an initial peek may miss all image chunks.
                ImageFormat.sniff(header) == ImageFormat.WEBP ->
                    result.source.source().peek().use { it.hasOnlyDecodableWebpImages() }
                info != null -> info.isDecodable
                // A probe can legitimately fail on a truncated peek: TIFF in
                // particular often puts its IFD at the *end* of the file. Those
                // two formats have no platform decoder to fall back to anyway
                // (no TIFF in BitmapFactory, no JP2 in Skia), so claim them and
                // let the real decode produce a real error if they are broken.
                else -> ImageFormat.sniff(header).let { it == ImageFormat.TIFF || it == ImageFormat.JP2 }
            }
            return if (claimed) {
                KiteImageDecoder(result.source, options, maxCacheableAnimationBytes)
            } else {
                null
            }
        }

        public companion object {
            /**
             * Initial probe window, covering ordinary JPEG metadata and small
             * TIFF directories. WebP's chunk-header walk continues beyond this
             * window to check every frame's codec.
             */
            private const val PEEK_BYTES = 64L * 1024

            /**
             * Default [maxCacheableAnimationBytes]: 64 MiB of decoded frames.
             * Reaction-GIF-sized animations (a few MB decoded) cache freely;
             * screen-recording monsters fall back to re-decode-on-revisit.
             */
            public const val DEFAULT_MAX_CACHEABLE_ANIMATION_BYTES: Long = 64L * 1024 * 1024
        }
    }
}
