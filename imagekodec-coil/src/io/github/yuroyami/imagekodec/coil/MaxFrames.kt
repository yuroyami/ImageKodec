package io.github.yuroyami.imagekodec.coil

import coil3.Extras
import coil3.getExtra
import coil3.request.ImageRequest
import coil3.request.Options

/**
 * Decode at most [maxFrames] frames of an animated image, as
 * `ImageKodec.decodeAnimation` does with the same argument. `1` suits a thumbnail
 * or a paused image: [KiteImageDecoder] then decodes and downscales one frame
 * instead of every frame, and returns it as a still image. [KiteAsyncImage] sets
 * it to `1` while `animate` is false.
 *
 * It is part of the memory cache key, so a result with fewer frames never answers
 * a request for more. The default, every frame, leaves the key as it was.
 *
 * @throws IllegalArgumentException if [maxFrames] is less than 1
 */
@Throws(IllegalArgumentException::class)
public fun ImageRequest.Builder.maxFrames(maxFrames: Int): ImageRequest.Builder {
    require(maxFrames >= 1) { "maxFrames must be at least 1, was $maxFrames" }
    extras[maxFramesKey] = maxFrames
    return memoryCacheKeyExtra(MAX_FRAMES_CACHE_KEY, if (maxFrames == Int.MAX_VALUE) null else maxFrames.toString())
}

/** How many frames of an animated image this request decodes at most; every frame by default. */
public val ImageRequest.maxFrames: Int
    get() = getExtra(maxFramesKey)

/** How many frames of an animated image the decoder decodes at most; every frame by default. */
public val Options.maxFrames: Int
    get() = getExtra(maxFramesKey)

private val maxFramesKey = Extras.Key(default = Int.MAX_VALUE)

private const val MAX_FRAMES_CACHE_KEY = "imagekodec#max_frames"
