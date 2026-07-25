package io.github.yuroyami.kiteimage

/**
 * Thrown when input claims to be an image but can't be decoded: truncated data,
 * corrupt structures, failed checksums, dimension overflow, or no recognisable
 * format at all.
 */
public open class ImageDecodeException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The input is a well-formed image in a format, or a format feature, KiteImage
 * doesn't decode: lossy WebP, an arithmetic-coded JPEG, a CgBI PNG. The message
 * always names the specific feature, and `KiteImage.probe` reports the same
 * thing up front through [ImageInfo.unsupportedReason], so a caller can route
 * the file elsewhere instead of catching this.
 *
 * Subtype of [ImageDecodeException] so callers who don't care about the
 * distinction catch one type.
 */
public class UnsupportedImageException(
    message: String,
    cause: Throwable? = null,
) : ImageDecodeException(message, cause)
