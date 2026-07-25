package io.github.yuroyami.kiteimage.codec

import io.github.yuroyami.kiteimage.ImageDecodeException
import io.github.yuroyami.kiteimage.KiteAnimation
import io.github.yuroyami.kiteimage.KiteBitmap
import io.github.yuroyami.kiteimage.KiteFrame
import io.github.yuroyami.kiteimage.UnsupportedImageException
import io.github.yuroyami.kiteimage.internal.Budget
import io.github.yuroyami.kiteimage.internal.sourceOver

/**
 * The WebP container: a RIFF file whose payload is one of two very different
 * codecs, optionally wrapped in the extended (`VP8X`) form that adds alpha,
 * animation and metadata.
 *
 * This decoder owns the container and the **lossless** codec ([Vp8lDecoder]),
 * including animation: `ANIM`/`ANMF` frames composite through the same
 * source-over operator APNG uses, honouring both the blend and dispose flags.
 *
 * The **lossy** codec (`VP8`, the intra-only slice of VP8 video) is declined by
 * name. It is not a gap in the container handling: a faithful VP8 port needs
 * libwebp's static probability tables reproduced exactly, which is a vendoring
 * decision rather than a coding one. Files that use it get an
 * [UnsupportedImageException] that says so, and `KiteImage.probe` reports them
 * as undecodable up front, so a caller can route them elsewhere without paying
 * for a failed decode.
 */
internal object WebpDecoder {

    private const val MAX_DIMENSION = 1 shl 24
    private const val MAX_PIXELS = 1L shl 28
    private const val MAX_TOTAL_PIXELS = 1L shl 28

    private const val LOSSY_MESSAGE =
        "WebP lossy (VP8) is not supported; this build decodes lossless (VP8L) WebP only"

    private fun err(msg: String): Nothing = throw ImageDecodeException("WebP: $msg")

    fun decode(data: ByteArray): KiteBitmap {
        val file = parse(data)
        val frame = file.frames.firstOrNull() ?: err("no image data")
        return renderStill(data, file, frame)
    }

    fun decodeAnimation(data: ByteArray, cancellationCheck: (() -> Unit)? = null): KiteAnimation {
        val file = parse(data)
        if (!file.animated) {
            val still = decode(data)
            return KiteAnimation(
                width = still.width,
                height = still.height,
                frames = listOf(KiteFrame(still, delayMillis = 0, delayRawCentiseconds = 0)),
                loopCount = 1,
            )
        }

        val w = file.canvasWidth
        val h = file.canvasHeight
        if (w.toLong() * h * file.frames.size > MAX_TOTAL_PIXELS) {
            err("${file.frames.size} frames of ${w}x$h exceeds safety limits")
        }

        val canvas = IntArray(w * h)                     // starts fully transparent
        val out = ArrayList<KiteFrame>(file.frames.size)

        for (frame in file.frames) {
            // A frame rectangle that leaves the canvas is malformed, not something
            // to clip: the file is claiming pixels that have nowhere to go.
            if (frame.x < 0 || frame.y < 0 ||
                frame.x + frame.width > w || frame.y + frame.height > h
            ) {
                err("frame ${frame.width}x${frame.height} at (${frame.x}, ${frame.y}) leaves the ${w}x$h canvas")
            }
            val pixels = decodeFrameImage(data, frame)
            if (frame.width != pixels.width || frame.height != pixels.height) {
                err("frame declares ${frame.width}x${frame.height} but decodes ${pixels.width}x${pixels.height}")
            }
            for (y in 0 until frame.height) {
                var s = y * frame.width
                var d = (frame.y + y) * w + frame.x
                for (x in 0 until frame.width) {
                    val sp = pixels.argb[s++]
                    canvas[d] = if (frame.blend) sourceOver(sp, canvas[d]) else sp
                    d++
                }
            }

            // Duration 0 means "as fast as possible"; clamp it the way browsers do.
            val millis = if (frame.durationMillis <= 10) 100 else frame.durationMillis
            out.add(
                KiteFrame(
                    bitmap = KiteBitmap(w, h, canvas.copyOf()),
                    delayMillis = millis,
                    delayRawCentiseconds = (frame.durationMillis + 5) / 10,
                ),
            )

            if (frame.disposeToBackground) {
                for (y in 0 until frame.height) {
                    val row = (frame.y + y) * w + frame.x
                    canvas.fill(0, row, row + frame.width)
                }
            }
            cancellationCheck?.invoke()
        }

        return KiteAnimation(w, h, out, file.loopCount)
    }

    // --- container --------------------------------------------------------------

    private class Frame(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val durationMillis: Int,
        val blend: Boolean,
        val disposeToBackground: Boolean,
        /** Offset and length of this frame's VP8 or VP8L chunk payload. */
        val payloadAt: Int,
        val payloadLength: Int,
        val lossless: Boolean,
    )

    private class File(
        val canvasWidth: Int,
        val canvasHeight: Int,
        val animated: Boolean,
        val loopCount: Int,
        val frames: List<Frame>,
    )

    private fun parse(data: ByteArray): File {
        if (data.size < 12) err("too short for a RIFF header")
        if (!tagAt(data, 0, "RIFF") || !tagAt(data, 8, "WEBP")) err("not a RIFF/WEBP file")
        val riffSize = u32(data, 4)
        // The RIFF size counts everything after the 8-byte prefix.
        val end = minOf(data.size.toLong(), 8L + riffSize).toInt()

        var canvasWidth = 0
        var canvasHeight = 0
        var animated = false
        var loopCount = 1
        var sawVp8x = false
        val frames = ArrayList<Frame>()

        var p = 12
        while (p + 8 <= end) {
            val tag = tag(data, p)
            val size = u32(data, p + 4)
            val body = p + 8
            if (size < 0 || body + size > end.toLong()) err("chunk $tag runs past the file")
            val len = size.toInt()

            when (tag) {
                "VP8X" -> {
                    if (len < 10) err("VP8X too short")
                    val flags = data[body].toInt() and 0xFF
                    animated = (flags and 0x02) != 0
                    canvasWidth = u24(data, body + 4) + 1
                    canvasHeight = u24(data, body + 7) + 1
                    sawVp8x = true
                }
                "ANIM" -> {
                    if (len < 6) err("ANIM too short")
                    loopCount = u16(data, body + 4)
                }
                "ANMF" -> {
                    if (len < 16) err("ANMF too short")
                    val fx = u24(data, body) * 2
                    val fy = u24(data, body + 3) * 2
                    val fw = u24(data, body + 6) + 1
                    val fh = u24(data, body + 9) + 1
                    val duration = u24(data, body + 12)
                    val flags = data[body + 15].toInt() and 0xFF
                    val sub = readSubImage(data, body + 16, body + len)
                    frames.add(
                        Frame(
                            x = fx, y = fy, width = fw, height = fh,
                            durationMillis = duration,
                            // Bit 1 set means "do not blend", bit 0 set means
                            // "dispose to background".
                            blend = (flags and 0x02) == 0,
                            disposeToBackground = (flags and 0x01) != 0,
                            payloadAt = sub.at, payloadLength = sub.length,
                            lossless = sub.lossless,
                        ),
                    )
                }
                "VP8 ", "VP8L" -> {
                    if (frames.isEmpty() || !animated) {
                        val lossless = tag == "VP8L"
                        if (!sawVp8x) {
                            val dims = stillDimensions(data, body, len, lossless)
                            canvasWidth = dims.first
                            canvasHeight = dims.second
                        }
                        frames.add(
                            Frame(
                                x = 0, y = 0, width = canvasWidth, height = canvasHeight,
                                durationMillis = 0, blend = false, disposeToBackground = false,
                                payloadAt = body, payloadLength = len,
                                lossless = lossless,
                            ),
                        )
                    }
                }
            }
            // Chunks pad to an even length.
            p = body + len + (len and 1)
        }

        if (canvasWidth <= 0 || canvasHeight <= 0) err("no usable image dimensions")
        if (canvasWidth > MAX_DIMENSION || canvasHeight > MAX_DIMENSION) {
            err("${canvasWidth}x$canvasHeight exceeds the dimension limit")
        }
        if (canvasWidth.toLong() * canvasHeight > MAX_PIXELS) {
            err("${canvasWidth}x$canvasHeight exceeds safety limits")
        }
        if (!Budget.fits(canvasWidth, canvasHeight, data.size)) {
            err("canvas ${canvasWidth}x$canvasHeight cannot come from ${data.size} bytes")
        }
        if (frames.isEmpty()) err("no image chunk")

        return File(canvasWidth, canvasHeight, animated && frames.size >= 1, loopCount, frames)
    }

    private class SubImage(val at: Int, val length: Int, val lossless: Boolean)

    /** Walk an ANMF's own chunk list for its image chunk (VP8 or VP8L). */
    private fun readSubImage(data: ByteArray, start: Int, end: Int): SubImage {
        var p = start
        while (p + 8 <= end) {
            val tag = tag(data, p)
            val size = u32(data, p + 4)
            val body = p + 8
            if (size < 0 || body + size > end.toLong()) err("frame chunk $tag runs past the frame")
            val len = size.toInt()
            when (tag) {
                "VP8 " -> return SubImage(body, len, lossless = false)
                "VP8L" -> return SubImage(body, len, lossless = true)
            }
            p = body + len + (len and 1)
        }
        err("animation frame has no image chunk")
    }

    /** Read a still frame's dimensions straight from the codec header. */
    private fun stillDimensions(data: ByteArray, body: Int, len: Int, lossless: Boolean): Pair<Int, Int> {
        if (lossless) {
            if (len < 5) err("VP8L chunk too short")
            val bits = u32(data, body + 1)
            return Pair(((bits and 0x3FFF).toInt()) + 1, (((bits shr 14) and 0x3FFF).toInt()) + 1)
        }
        if (len < 10) err("VP8 chunk too short")
        // Key frame: 3-byte tag, the 9D 01 2A start code, then two 14-bit fields.
        return Pair(u16(data, body + 6) and 0x3FFF, u16(data, body + 8) and 0x3FFF)
    }

    // --- pixels -------------------------------------------------------------------

    private fun renderStill(data: ByteArray, file: File, frame: Frame): KiteBitmap {
        val pixels = decodeFrameImage(data, frame)
        // The spec requires a still file's canvas to match its image exactly, and
        // holding it to that is also what stops a corrupted VP8X header from
        // sizing an allocation the payload could never fill.
        if (pixels.width != file.canvasWidth || pixels.height != file.canvasHeight) {
            err(
                "canvas ${file.canvasWidth}x${file.canvasHeight} does not match the " +
                    "image ${pixels.width}x${pixels.height}",
            )
        }
        return KiteBitmap(pixels.width, pixels.height, pixels.argb)
    }

    private fun decodeFrameImage(data: ByteArray, frame: Frame): Vp8lDecoder.Pixels {
        if (!frame.lossless) throw UnsupportedImageException(LOSSY_MESSAGE)
        return Vp8lDecoder.decode(data, frame.payloadAt, frame.payloadLength)
    }

    // --- little-endian helpers -----------------------------------------------------

    private fun tagAt(data: ByteArray, at: Int, expected: String): Boolean {
        if (at + 4 > data.size) return false
        for (i in 0..3) if (data[at + i] != expected[i].code.toByte()) return false
        return true
    }

    private fun tag(data: ByteArray, at: Int): String =
        buildString(4) { for (i in 0..3) append((data[at + i].toInt() and 0xFF).toChar()) }

    private fun u16(data: ByteArray, at: Int): Int {
        if (at + 2 > data.size) err("truncated at $at")
        return (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
    }

    private fun u24(data: ByteArray, at: Int): Int {
        if (at + 3 > data.size) err("truncated at $at")
        return u16(data, at) or ((data[at + 2].toInt() and 0xFF) shl 16)
    }

    private fun u32(data: ByteArray, at: Int): Long {
        if (at + 4 > data.size) err("truncated at $at")
        return (u16(data, at).toLong()) or (u16(data, at + 2).toLong() shl 16)
    }
}
