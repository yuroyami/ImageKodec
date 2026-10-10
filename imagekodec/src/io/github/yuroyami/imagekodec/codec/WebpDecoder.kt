package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.KiteAnimation
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.KiteFrame
import io.github.yuroyami.imagekodec.internal.Budget
import io.github.yuroyami.imagekodec.internal.sourceOver

/**
 * The WebP container: a RIFF file whose payload is one of two very different
 * codecs, optionally wrapped in the extended (`VP8X`) form that adds alpha,
 * animation and metadata.
 *
 * This decoder owns the container, including animation: `ANIM`/`ANMF` frames
 * composite through the same source-over operator APNG uses, honouring both the
 * blend and dispose flags. A frame is either lossless ([Vp8lDecoder]) or lossy
 * ([Vp8Decoder], with its opacity in an `ALPH` chunk), and both decode to the
 * pixels libwebp's dwebp writes (#11).
 */
internal object WebpDecoder {

    private const val MAX_DIMENSION = 1 shl 24
    private const val MAX_PIXELS = 1L shl 28

    private fun err(msg: String): Nothing = throw ImageDecodeException("WebP: $msg")

    fun decode(data: ByteArray): KiteBitmap {
        val file = parse(data)
        val frame = file.frames.firstOrNull() ?: err("no image data")
        return renderStill(data, file, frame)
    }

    /** Decodes the first [maxFrames] frames, calling [cancellationCheck] after each. */
    fun decodeAnimation(data: ByteArray, maxFrames: Int = Int.MAX_VALUE, cancellationCheck: (() -> Unit)? = null): KiteAnimation {
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
        val frames = if (file.frames.size > maxFrames) file.frames.subList(0, maxFrames) else file.frames
        if (!Budget.framesFitAbsolute(w, h, frames.size)) {
            err("${frames.size} frames of ${w}x$h exceed the output safety limit")
        }

        // A corrupt first entropy stream must fail before the canvas allocation.
        var canvas = IntArray(0)
        val out = ArrayList<KiteFrame>(frames.size)

        for (frame in frames) {
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
            if (canvas.isEmpty()) canvas = IntArray(w * h)
            composite(canvas, w, pixels, frame)

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

        return KiteAnimation(w, h, out, file.loopCount.toLong())
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
        /** Offset and length of the `ALPH` chunk payload of a lossy frame, or -1 without one. */
        val alphaAt: Int = -1,
        val alphaLength: Int = 0,
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
        var alphaAt = -1
        var alphaLength = 0
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
                            alphaAt = sub.alphaAt, alphaLength = sub.alphaLength,
                        ),
                    )
                }
                // The opacity of the lossy image that follows, in the extended format only. A
                // lossless image carries its own and ignores it, as libwebp does.
                "ALPH" -> if (sawVp8x && alphaAt < 0) {
                    alphaAt = body
                    alphaLength = len
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
                                alphaAt = if (lossless) -1 else alphaAt,
                                alphaLength = if (lossless) 0 else alphaLength,
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
        if (frames.isEmpty()) err("no image chunk")
        // Check every rectangle and codec header before an animation reserves a
        // full canvas. Sparse frames have no useful input-to-canvas size ratio.
        for (frame in frames) {
            if (frame.x.toLong() + frame.width > canvasWidth ||
                frame.y.toLong() + frame.height > canvasHeight
            ) err("frame rectangle leaves the ${canvasWidth}x$canvasHeight canvas")
            val dimensions = stillDimensions(data, frame.payloadAt, frame.payloadLength, frame.lossless)
            if (dimensions.first != frame.width || dimensions.second != frame.height) {
                err("frame dimensions do not match the ${if (frame.lossless) "VP8L" else "VP8"} header")
            }
        }

        return File(canvasWidth, canvasHeight, animated && frames.size >= 1, loopCount, frames)
    }

    private class SubImage(val at: Int, val length: Int, val lossless: Boolean, val alphaAt: Int = -1, val alphaLength: Int = 0)

    /** Walk an ANMF's own chunk list for its image chunk (VP8 or VP8L), and a lossy one's ALPH before it. */
    private fun readSubImage(data: ByteArray, start: Int, end: Int): SubImage {
        var p = start
        var alphaAt = -1
        var alphaLength = 0
        while (p + 8 <= end) {
            val tag = tag(data, p)
            val size = u32(data, p + 4)
            val body = p + 8
            if (size < 0 || body + size > end.toLong()) err("frame chunk $tag runs past the frame")
            val len = size.toInt()
            when (tag) {
                "ALPH" -> if (alphaAt < 0) {
                    alphaAt = body
                    alphaLength = len
                }
                "VP8 " -> return SubImage(body, len, lossless = false, alphaAt, alphaLength)
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
            if (data[body].toInt() and 255 != 0x2f) err("bad VP8L signature")
            val bits = u32(data, body + 1)
            if (bits shr 29 != 0L) err("unknown VP8L version")
            return Pair(((bits and 0x3FFF).toInt()) + 1, (((bits shr 14) and 0x3FFF).toInt()) + 1)
        }
        if (len < 10) err("VP8 chunk too short")
        // Key frame: 3-byte tag, the 9D 01 2A start code, then two 14-bit fields.
        return Pair(u16(data, body + 6) and 0x3FFF, u16(data, body + 8) and 0x3FFF)
    }

    // --- pixels -------------------------------------------------------------------

    private fun renderStill(data: ByteArray, file: File, frame: Frame): KiteBitmap {
        val pixels = decodeFrameImage(data, frame)
        if (file.animated) {
            // Still decode presents the first frame before its disposal, using
            // the same transparent canvas and blending as animation playback.
            val canvas = IntArray(file.canvasWidth * file.canvasHeight)
            composite(canvas, file.canvasWidth, pixels, frame)
            return KiteBitmap(file.canvasWidth, file.canvasHeight, canvas)
        }
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

    private fun composite(canvas: IntArray, canvasWidth: Int, pixels: Vp8lDecoder.Pixels, frame: Frame) {
        for (y in 0 until frame.height) {
            var source = y * frame.width
            var destination = (frame.y + y) * canvasWidth + frame.x
            for (x in 0 until frame.width) {
                val pixel = pixels.argb[source++]
                canvas[destination] = if (frame.blend) sourceOver(pixel, canvas[destination]) else pixel
                destination++
            }
        }
    }

    private fun decodeFrameImage(data: ByteArray, frame: Frame): Vp8lDecoder.Pixels {
        if (frame.lossless) return Vp8lDecoder.decode(data, frame.payloadAt, frame.payloadLength)
        val yuv = Vp8Decoder.decode(data, frame.payloadAt, frame.payloadLength)
        val alpha = if (frame.alphaAt >= 0) decodeAlpha(data, frame.alphaAt, frame.alphaLength, yuv.width, yuv.height) else null
        return Vp8lDecoder.Pixels(yuv.width, yuv.height, Vp8Decoder.toArgb(yuv, alpha))
    }

    /**
     * The opacity plane an `ALPH` chunk holds for a [width] by [height] lossy image, as libwebp's
     * alpha_dec.c reads it: one header byte, then the levels raw or as the green channel of a
     * header-less VP8L image, then undone from the prediction filter the header names.
     */
    private fun decodeAlpha(data: ByteArray, at: Int, length: Int, width: Int, height: Int): ByteArray {
        if (length <= 1) err("ALPH chunk is empty")
        val header = data[at].toInt() and 0xFF
        val method = header and 3
        val filter = (header shr 2) and 3
        val preprocessing = (header shr 4) and 3
        if (method > 1 || preprocessing > 1 || header shr 6 != 0) err("ALPH header $header is not one libwebp writes")
        val size = width * height
        val deltas: ByteArray = if (method == 0) {
            if (length - 1 < size) err("ALPH holds ${length - 1} levels for $size pixels")
            data.copyOfRange(at + 1, at + 1 + size)
        } else {
            val argb = Vp8lDecoder.decodeImageStream(data, at + 1, length - 1, width, height)
            ByteArray(size) { (argb[it] ushr 8).toByte() }
        }
        if (filter == 0) return deltas
        // WebPUnfilters: each row from the one above it, the first from nothing.
        val out = ByteArray(size)
        for (y in 0 until height) {
            val row = y * width
            val prev = row - width
            if (y == 0 || filter == 1) {
                var pred = if (y == 0) 0 else out[prev].toInt() and 0xFF
                for (x in 0 until width) {
                    pred = (pred + deltas[row + x]) and 0xFF
                    out[row + x] = pred.toByte()
                }
            } else if (filter == 2) {
                for (x in 0 until width) out[row + x] = ((out[prev + x] + deltas[row + x]) and 0xFF).toByte()
            } else {
                var top: Int
                var topLeft = out[prev].toInt() and 0xFF
                var left = topLeft
                for (x in 0 until width) {
                    top = out[prev + x].toInt() and 0xFF
                    val g = left + top - topLeft
                    val prediction = if (g and 0xFF.inv() == 0) g else if (g < 0) 0 else 255
                    left = (deltas[row + x] + prediction) and 0xFF
                    topLeft = top
                    out[row + x] = left.toByte()
                }
            }
        }
        return out
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
