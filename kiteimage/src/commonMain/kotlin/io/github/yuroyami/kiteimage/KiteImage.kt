package io.github.yuroyami.kiteimage

import io.github.yuroyami.kiteimage.codec.BmpDecoder
import io.github.yuroyami.kiteimage.codec.BmpEncoder
import io.github.yuroyami.kiteimage.codec.GifDecoder
import io.github.yuroyami.kiteimage.codec.GifEncoder
import io.github.yuroyami.kiteimage.codec.ImageProbe
import io.github.yuroyami.kiteimage.codec.JpegDecoder
import io.github.yuroyami.kiteimage.codec.JpegEncoder
import io.github.yuroyami.kiteimage.codec.PngDecoder
import io.github.yuroyami.kiteimage.codec.PngEncoder
import io.github.yuroyami.kiteimage.codec.TiffDecoder
import io.github.yuroyami.kiteimage.codec.WebpDecoder

/**
 * The KiteImage facade. Everything is pure computation on byte arrays: no I/O,
 * no threads, no platform types: so it behaves identically on every KMP target.
 *
 * ```kotlin
 * val bitmap = KiteImage.decode(bytes)          // sniffs the format, dispatches
 * val anim = KiteImage.decodeAnimation(bytes)   // frames + delays + loop count
 * val format = KiteImage.detect(bytes)          // just the sniff
 * val info = KiteImage.probe(bytes)             // size + depth + frames, no decode
 * ```
 */
public object KiteImage {

    /** Identify [data]'s format from its magic bytes, or null if unrecognised. */
    public fun detect(data: ByteArray): ImageFormat? = ImageFormat.sniff(data)

    /**
     * Read [data]'s header and report what it says: dimensions, bit depth,
     * declared alpha, animation frame count, EXIF orientation, and whether this
     * build can decode it. No pixels are decoded and no image-sized buffer is
     * allocated, so this stays cheap on a 50 MP file.
     *
     * Use it to size a layout before the decode, to reject hostile uploads by
     * dimension, or to decide whether to claim a file at all.
     *
     * @throws ImageDecodeException if the format is unrecognised or the header
     *   is malformed past the point of reading
     */
    public fun probe(data: ByteArray): ImageInfo = ImageProbe.probe(data)

    /** [probe], but null instead of throwing on unrecognised or malformed input. */
    public fun probeOrNull(data: ByteArray): ImageInfo? =
        try {
            ImageProbe.probe(data)
        } catch (_: ImageDecodeException) {
            null
        }

    /**
     * Decode [data] into a [KiteBitmap], sniffing the format first. Animated
     * inputs yield their first frame (composited); use [decodeAnimation] for the
     * full sequence.
     *
     * With [applyOrientation] the EXIF orientation tag is honoured, so a phone
     * photo comes back the way it was shot instead of on its side. It defaults
     * to false because it changes the returned dimensions, and a caller that
     * already handles orientation itself should not pay for it twice: check
     * [ImageInfo.orientation] from [probe] if you want to decide per file.
     *
     * @throws ImageDecodeException on malformed/truncated input or unknown format
     * @throws UnsupportedImageException on formats recognised but not yet decodable
     *   (see [ImageFormat]: sniffing is deliberately wider than decoding)
     */
    public fun decode(data: ByteArray, applyOrientation: Boolean = false): KiteBitmap {
        val bitmap = decodeRaw(data)
        if (!applyOrientation) return bitmap
        val orientation = probeOrNull(data)?.orientation ?: Orientation.Normal
        return bitmap.oriented(orientation)
    }

    private fun decodeRaw(data: ByteArray): KiteBitmap = when (detect(data)) {
        ImageFormat.PNG -> PngDecoder.decode(data)
        ImageFormat.BMP -> BmpDecoder.decode(data)
        ImageFormat.GIF -> GifDecoder.decode(data, firstFrameOnly = true).frames.first().bitmap
        ImageFormat.JPEG -> JpegDecoder.decode(data)
        ImageFormat.JP2 -> jp2ToBitmap(data)
        ImageFormat.WEBP -> WebpDecoder.decode(data)
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

    /**
     * Encode [bitmap] as a BMP: 24-bit BI_RGB when it is opaque, or 32-bit
     * BI_BITFIELDS under a V4 header when it carries alpha (plain 32-bit BMP
     * alpha is officially "reserved", and half the world's readers discard it).
     * Uncompressed and lossless either way.
     */
    public fun encodeBmp(bitmap: KiteBitmap): ByteArray = BmpEncoder.encode(bitmap)

    /**
     * Encode [bitmap] as a GIF89a. Colours are quantised to the format's 256-entry
     * limit; images that already fit encode losslessly. Alpha is reduced to GIF's
     * single transparent index at a 50% threshold.
     *
     * [dither] applies Floyd-Steinberg error diffusion when quantisation actually
     * loses colours, which is what keeps a photograph from banding. It is ignored
     * when the palette came out exact, since there is no error to spread.
     */
    public fun encodeGif(bitmap: KiteBitmap, dither: Boolean = true): ByteArray =
        GifEncoder.encode(bitmap, dither)

    /**
     * Encode [animation] as an animated GIF89a: one shared colour table, per-frame
     * delays, and the NETSCAPE2.0 loop count. Frames must all be canvas-sized,
     * which is exactly what [decodeAnimation] produces.
     */
    public fun encodeGif(animation: KiteAnimation, dither: Boolean = true): ByteArray =
        GifEncoder.encode(animation, dither)

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
     *
     * [cancellationCheck], when given, runs between frames of a multi-frame
     * decode and may throw to abandon work whose result no longer matters
     * (pass `{ coroutineContext.ensureActive() }` from a coroutine). Static
     * formats decode in one step and never invoke it.
     *
     * [applyOrientation] honours the EXIF orientation tag on every frame, the
     * same way [decode] does for a still.
     */
    public fun decodeAnimation(
        data: ByteArray,
        applyOrientation: Boolean = false,
        cancellationCheck: (() -> Unit)? = null,
    ): KiteAnimation {
        val animation = when (detect(data)) {
            ImageFormat.GIF -> GifDecoder.decode(data, firstFrameOnly = false, cancellationCheck)
            ImageFormat.PNG -> PngDecoder.decodeAnimation(data, cancellationCheck)
            ImageFormat.WEBP -> WebpDecoder.decodeAnimation(data, cancellationCheck)
            else -> {
                val single = decodeRaw(data)
                KiteAnimation(
                    width = single.width,
                    height = single.height,
                    frames = listOf(KiteFrame(single, delayMillis = 0, delayRawCentiseconds = 0)),
                    loopCount = 1,
                )
            }
        }
        if (!applyOrientation) return animation
        val orientation = probeOrNull(data)?.orientation ?: Orientation.Normal
        return animation.oriented(orientation)
    }
}
