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
 * The factory claims only inputs KiteImage fully decodes (GIF, baseline JPEG,
 * and the PNG/BMP feature subsets) and declines everything else (CgBI PNGs, RLE/bitfields BMPs, lossless/arithmetic JPEGs) so Coil's platform decoders
 * keep handling those and nothing regresses versus a stock setup. Animated
 * results come back as [KiteAnimationImage]; static ones as an ordinary bitmap
 * image that memory-caches normally.
 */
public class KiteImageDecoder(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val bytes = source.source().use { it.readByteArray() }
        val animation = KiteImage.decodeAnimation(bytes)
        if (animation.isAnimated) {
            // Animations keep full resolution: scaling every composited frame
            // trades decode CPU for little: playback draws scaled anyway.
            return DecodeResult(image = KiteAnimationImage(animation), isSampled = false)
        }
        var bitmap = animation.frames.first().bitmap
        // Honor the request's target size (box-filter downscale, never up).
        val tw = (options.size.width as? Dimension.Pixels)?.px ?: 0
        val th = (options.size.height as? Dimension.Pixels)?.px ?: 0
        var sampled = false
        if (tw > 0 && th > 0 && (bitmap.width > tw || bitmap.height > th)) {
            bitmap = bitmap.scaled(tw, th)
            sampled = true
        }
        return DecodeResult(image = bitmap.toCoilImage(shareable = true), isSampled = sampled)
    }

    public class Factory : Decoder.Factory {

        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            val peek = result.source.source().peek()
            peek.request(PEEK_BYTES.toLong())
            val header = peek.buffer.readByteArray(minOf(PEEK_BYTES.toLong(), peek.buffer.size))
            val claimed = when (ImageFormat.sniff(header)) {
                ImageFormat.GIF -> true
                ImageFormat.PNG -> pngIsDecodable(header)
                ImageFormat.BMP -> bmpIsDecodable(header)
                // JPEG needs a marker walk (SOF position varies behind APPn/EXIF
                // blobs): claim SOF0/SOF1/SOF2, decline the exotics.
                ImageFormat.JPEG -> jpegIsClaimable(result.source.source().peek())
                // Platform decoders mostly can't read these anyway (no TIFF in
                // BitmapFactory, no JP2 in Skia): claiming is strictly better.
                ImageFormat.TIFF, ImageFormat.JP2 -> true
                else -> false
            }
            return if (claimed) KiteImageDecoder(result.source, options) else null
        }

        /**
         * Walk the marker stream (bounded) until the first SOF: SOF0/SOF1/SOF2
         * (baseline, extended sequential, progressive) → ours; the
         * lossless/arithmetic exotics → Coil's platform decoder. Runs on a
         * fresh peek, so the real source is unmoved.
         */
        internal fun jpegIsClaimable(s: okio.BufferedSource): Boolean = try {
            var ok = false
            if ((s.readByte().toInt() and 0xFF) == 0xFF && (s.readByte().toInt() and 0xFF) == 0xD8) {
                var scanned = 0L
                loop@ while (scanned < MAX_MARKER_SCAN) {
                    var m = s.readByte().toInt() and 0xFF
                    if (m != 0xFF) break@loop
                    while (m == 0xFF) m = s.readByte().toInt() and 0xFF
                    when (m) {
                        0xC0, 0xC1, 0xC2 -> { ok = true; break@loop }                  // baseline / ext. sequential / progressive
                        0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF ->
                            break@loop                                                 // exotics: not ours
                        0xD9, 0xDA -> break@loop                                       // EOI/SOS before any SOF: corrupt
                        0x01, in 0xD0..0xD8 -> Unit                                    // standalone markers, no segment
                        else -> {                                                      // segment with length (APPn, DQT, DHT, …)
                            val len = ((s.readByte().toInt() and 0xFF) shl 8) or (s.readByte().toInt() and 0xFF)
                            if (len < 2) break@loop
                            s.skip((len - 2).toLong())
                            scanned += len
                        }
                    }
                }
            }
            ok
        } catch (_: Exception) {
            false   // truncated peek → let the platform path deal with it
        }

        /** Decline what PngDecoder would reject so Coil's platform path keeps it. */
        private fun pngIsDecodable(h: ByteArray): Boolean {
            if (h.size < 29) return false
            // First chunk type at offset 12; Apple's CgBI variant goes to the platform.
            return !(h[12] == 'C'.code.toByte() && h[13] == 'g'.code.toByte() &&
                h[14] == 'B'.code.toByte() && h[15] == 'I'.code.toByte())
        }

        /** Decline what BmpDecoder would reject (compression, exotic depths, OS/2 header). */
        private fun bmpIsDecodable(h: ByteArray): Boolean {
            if (h.size < PEEK_BYTES) return false
            fun u16(at: Int) = (h[at].toInt() and 0xFF) or ((h[at + 1].toInt() and 0xFF) shl 8)
            fun u32(at: Int) = u16(at) or (u16(at + 2) shl 16)
            val dibSize = u32(14)
            if (dibSize != 40 && dibSize != 108 && dibSize != 124) return false
            val bpp = u16(28)
            if (bpp != 8 && bpp != 24 && bpp != 32) return false
            return u32(30) == 0   // BI_RGB only
        }

        private companion object {
            // Enough for the PNG IHDR interlace byte (29) and the BMP compression field (34).
            const val PEEK_BYTES = 34
            // JPEG marker-walk bound: EXIF/XMP blobs sit before SOF, but never this much.
            const val MAX_MARKER_SCAN = 1L shl 20
        }
    }
}
