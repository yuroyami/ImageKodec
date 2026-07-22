package io.github.yuroyami.kiteimage

import io.github.yuroyami.kiteimage.codec.BmpDecoder
import io.github.yuroyami.kiteimage.codec.GifDecoder
import io.github.yuroyami.kiteimage.codec.JpegDecoder
import io.github.yuroyami.kiteimage.codec.JpegEncoder
import io.github.yuroyami.kiteimage.codec.PngDecoder
import io.github.yuroyami.kiteimage.codec.PngEncoder
import io.github.yuroyami.kiteimage.codec.TiffDecoder

/**
 * The KiteImage facade. Everything is pure computation on byte arrays: no I/O,
 * no threads, no platform types: so it behaves identically on every KMP target.
 *
 * ```kotlin
 * val bitmap = KiteImage.decode(bytes)          // sniffs the format, dispatches
 * val anim = KiteImage.decodeAnimation(bytes)   // frames + delays + loop count
 * val format = KiteImage.detect(bytes)          // just the sniff
 * ```
 */
public object KiteImage {

    /** Identify [data]'s format from its magic bytes, or null if unrecognised. */
    public fun detect(data: ByteArray): ImageFormat? = ImageFormat.sniff(data)

    /**
     * Decode [data] into a [KiteBitmap], sniffing the format first. Animated
     * inputs yield their first frame (composited); use [decodeAnimation] for the
     * full sequence.
     *
     * @throws ImageDecodeException on malformed/truncated input or unknown format
     * @throws UnsupportedImageException on formats recognised but not yet decodable
     *   (see [ImageFormat]: sniffing is deliberately wider than decoding)
     */
    public fun decode(data: ByteArray): KiteBitmap = when (detect(data)) {
        ImageFormat.PNG -> PngDecoder.decode(data)
        ImageFormat.BMP -> BmpDecoder.decode(data)
        ImageFormat.GIF -> GifDecoder.decode(data, firstFrameOnly = true).frames.first().bitmap
        ImageFormat.JPEG -> JpegDecoder.decode(data)
        ImageFormat.JP2 -> jp2ToBitmap(data)
        ImageFormat.WEBP -> throw UnsupportedImageException(
            "WebP decoding is not in this version yet",
        )
        ImageFormat.TIFF -> TiffDecoder.decode(data)
        null -> throw ImageDecodeException(
            "unrecognised image format (${data.size} bytes${
                if (data.size >= 4) {
                    ", starts " + data.take(4).joinToString(" ") { b ->
                        (b.toInt() and 0xFF).toString(16).padStart(2, '0')
                    }
                } else ""
            })",
        )
    }

    /**
     * Encode [bitmap] as a PNG: 8-bit RGB, or RGBA when any pixel carries alpha.
     * Lossless: decoding the result returns the exact same pixels.
     */
    public fun encodePng(bitmap: KiteBitmap): ByteArray = PngEncoder.encode(bitmap)

    /**
     * Encode [bitmap] as a baseline JPEG at [quality] 1..100 (default 90).
     * Alpha is discarded (JPEG has none); quality ≤ 90 uses 4:2:0 chroma
     * subsampling, above that 4:4:4; stb_image_write's behavior.
     */
    public fun encodeJpeg(bitmap: KiteBitmap, quality: Int = 90): ByteArray =
        JpegEncoder.encode(bitmap, quality)

    /** JPEG 2000 → ARGB via the JPX codec (gray replicated, cdef alpha honored). */
    private fun jp2ToBitmap(data: ByteArray): KiteBitmap {
        val r = io.github.yuroyami.kiteimage.codec.JpxDecoder.decode(data)
            ?: throw ImageDecodeException("JPEG 2000: stream is malformed or uses an unsupported feature")
        val n = if (r.colorSpace == "DeviceRGB") 3 else 1
        val argb = IntArray(r.width * r.height)
        for (i in argb.indices) {
            val a = r.alpha?.let { it[i].toInt() and 0xFF } ?: 0xFF
            if (n == 3) {
                argb[i] = (a shl 24) or
                    ((r.pixelBytes[i * 3].toInt() and 0xFF) shl 16) or
                    ((r.pixelBytes[i * 3 + 1].toInt() and 0xFF) shl 8) or
                    (r.pixelBytes[i * 3 + 2].toInt() and 0xFF)
            } else {
                val g = r.pixelBytes[i].toInt() and 0xFF
                argb[i] = (a shl 24) or (g shl 16) or (g shl 8) or g
            }
        }
        return KiteBitmap(r.width, r.height, argb)
    }

    /**
     * Decode [data] as an animation. GIF returns every composited frame with
     * delays and the loop count; static formats return a single zero-delay frame,
     * so this is safe to call on anything [decode] accepts.
     */
    public fun decodeAnimation(data: ByteArray): KiteAnimation = when (detect(data)) {
        ImageFormat.GIF -> GifDecoder.decode(data, firstFrameOnly = false)
        else -> {
            val single = decode(data)
            KiteAnimation(
                width = single.width,
                height = single.height,
                frames = listOf(KiteFrame(single, delayMillis = 0, delayRawCentiseconds = 0)),
                loopCount = 1,
            )
        }
    }
}
