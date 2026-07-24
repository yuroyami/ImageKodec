package io.github.yuroyami.kiteimage.coil

import coil3.ImageLoader
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Dimension
import io.github.yuroyami.kiteimage.ImageFormat
import io.github.yuroyami.kiteimage.KiteImage
import io.github.yuroyami.kiteimage.scaled
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.use

/**
 * A [coil3.decode.Decoder] backed by KiteImage's pure-Kotlin codecs.
 *
 * Register the [Factory] on your ImageLoader and Coil keeps doing everything it
 * is good at (network fetch, disk + memory cache, request lifecycle) while
 * decoding runs through KiteImage with identical behavior on every target:
 *
 * ```kotlin
 * ImageLoader.Builder(context)
 *     .components { add(KiteImageDecoder.Factory()) }
 *     .build()
 * ```
 *
 * The factory decides by asking `KiteImage.probe` whether this build can decode
 * the bytes, so it claims exactly what the codecs handle (PNG and APNG, JPEG,
 * GIF, BMP, lossless and animated WebP, TIFF, JP2) and declines the rest (SVG,
 * CgBI PNGs, lossy WebP, lossless/arithmetic JPEGs). Coil's platform decoders
 * keep everything it turns down, so nothing regresses versus a stock setup.
 *
 * Static results honor the request's target size (box-filter downscale, never
 * up) and memory-cache normally. Animated results come back as
 * [KiteAnimationImage] with **every frame downscaled** to the target size: that
 * is where animated memory goes: and are marked shareable (memory-cacheable)
 * when their pixel bytes fit under [Factory.maxCacheableAnimationBytes].
 * A cancelled request (fast scroll) aborts a multi-frame decode at the next
 * frame boundary instead of finishing the whole file.
 */
public class KiteImageDecoder(
    private val source: ImageSource,
    private val options: Options,
    private val maxCacheableAnimationBytes: Long = Factory.DEFAULT_MAX_CACHEABLE_ANIMATION_BYTES,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val bytes = source.source().use { it.readByteArray() }
        val ctx = currentCoroutineContext()
        val animation = KiteImage.decodeAnimation(bytes) { ctx.ensureActive() }

        // Honor the request's target size (box-filter downscale, never up).
        val tw = (options.size.width as? Dimension.Pixels)?.px ?: 0
        val th = (options.size.height as? Dimension.Pixels)?.px ?: 0
        val hasTarget = tw > 0 && th > 0

        if (animation.isAnimated) {
            var anim = animation
            var sampled = false
            if (hasTarget && (anim.width > tw || anim.height > th)) {
                anim = anim.scaled(tw, th)
                sampled = true
            }
            val pixelBytes = anim.frames.size.toLong() * anim.width * anim.height * 4
            return DecodeResult(
                image = KiteAnimationImage(anim, shareable = pixelBytes <= maxCacheableAnimationBytes),
                isSampled = sampled,
            )
        }

        var bitmap = animation.frames.first().bitmap
        var sampled = false
        if (hasTarget && (bitmap.width > tw || bitmap.height > th)) {
            bitmap = bitmap.scaled(tw, th)
            sampled = true
        }
        return DecodeResult(image = bitmap.toCoilImage(shareable = true), isSampled = sampled)
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

            // One question, asked once: can this build decode these bytes? The
            // probe reads headers only, and it already knows every rule each
            // decoder applies, so there is no second copy of "is this PNG a CgBI"
            // to drift out of sync here.
            val info = KiteImage.probeOrNull(header)
            val claimed = when {
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
             * How much of the source to peek before deciding. Generous enough for
             * a JPEG's EXIF/XMP blobs to sit in front of the frame header, and for
             * a small TIFF's directory, while staying far short of "read the file
             * to decide whether to read the file".
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
