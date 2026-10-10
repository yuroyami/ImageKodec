package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.KiteAnimation
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.internal.flate.ByteArrayBuilder
import io.github.yuroyami.imagekodec.internal.flate.Crc32
import io.github.yuroyami.imagekodec.internal.flate.Zlib
import kotlin.math.abs

/**
 * PNG and APNG encoder (lodepng as the structural reference, libpng's filter heuristic).
 *
 * Output shape: 8-bit truecolor; color type 6 (RGBA) when any pixel carries
 * alpha, color type 2 (RGB) otherwise. Palette/gray optimization is deliberately
 * out of scope for v1: every decoder reads truecolor, and correctness beats
 * bytes here. Per row, all five filters are tried and the one with the minimal
 * sum of absolute differences wins (the classic libpng "MSAD" heuristic) before
 * the stream goes through the vendored zlib deflate.
 *
 * An animation is written as APNG (PNG Third Edition): the first frame is the default
 * image, so a reader without APNG support shows it, and every later frame is the
 * rectangle that changed since the one before it, as apngasm and ffmpeg write one.
 */
internal object PngEncoder {

    private const val DISPOSE_NONE = 0
    private const val BLEND_SOURCE = 0
    private const val BLEND_OVER = 1

    fun encode(bitmap: KiteBitmap): ByteArray {
        val alpha = bitmap.hasTransparency()
        val out = ByteArrayBuilder(bitmap.width * bitmap.height + 128)
        header(out, bitmap.width, bitmap.height, alpha)
        appendChunk(out, "IDAT", compressed(bitmap.argb, bitmap.width, 0, 0, bitmap.width, bitmap.height, alpha, null))
        appendChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    /**
     * [animation] as an APNG whose frames read back as its composited frames, with their
     * delays and its loop count. A single frame is written as a plain PNG.
     *
     * Each frame after the first is the bounding box of what changed, left in place for the
     * next (dispose none). It replaces the canvas there (blend source), or, when every changed
     * pixel is opaque, is drawn over it with the unchanged pixels made transparent (blend
     * over), which compresses better where a few pixels change in a wide box; the smaller
     * of the two is kept.
     */
    fun encode(animation: KiteAnimation): ByteArray {
        val frames = animation.frames
        val width = animation.width
        val height = animation.height
        require(frames.all { it.bitmap.width == width && it.bitmap.height == height }) {
            "every APNG frame must share the ${width}x$height canvas"
        }
        require(animation.loopCount in 0L..0xFFFFFFFFL) { "an APNG play count is a 32-bit value, not ${animation.loopCount}" }
        if (frames.size == 1) return encode(frames[0].bitmap)
        val alpha = frames.any { it.bitmap.hasTransparency() }
        val out = ByteArrayBuilder(width * height * 2 + 128)
        header(out, width, height, alpha)
        val actl = ByteArray(8)
        writeBe32(actl, 0, frames.size)
        writeBe32(actl, 4, animation.loopCount.toInt())
        appendChunk(out, "acTL", actl)
        var sequence = 0
        for ((index, frame) in frames.withIndex()) {
            val pixels = frame.bitmap.argb
            if (index == 0) {
                appendChunk(out, "fcTL", frameControl(sequence++, width, height, 0, 0, frame.delayMillis, BLEND_SOURCE))
                appendChunk(out, "IDAT", compressed(pixels, width, 0, 0, width, height, alpha, null))
                continue
            }
            val previous = frames[index - 1].bitmap.argb
            val box = changedBox(previous, pixels, width, height)
            val (x, y, w, h) = box
            var data = compressed(pixels, width, x, y, w, h, alpha, null)
            var blend = BLEND_SOURCE
            if (alpha && overIsExact(previous, pixels, width, box)) {
                val over = compressed(pixels, width, x, y, w, h, alpha, previous)
                if (over.size < data.size) {
                    data = over
                    blend = BLEND_OVER
                }
            }
            appendChunk(out, "fcTL", frameControl(sequence++, w, h, x, y, frame.delayMillis, blend))
            val fdat = ByteArray(4 + data.size)
            writeBe32(fdat, 0, sequence++)
            data.copyInto(fdat, 4)
            appendChunk(out, "fdAT", fdat)
        }
        appendChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun header(out: ByteArrayBuilder, w: Int, h: Int, alpha: Boolean) {
        out.append(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArray(13)
        writeBe32(ihdr, 0, w)
        writeBe32(ihdr, 4, h)
        ihdr[8] = 8
        ihdr[9] = (if (alpha) 6 else 2).toByte()
        appendChunk(out, "IHDR", ihdr)
    }

    /** The smallest box holding every pixel that differs, or one pixel when none does, as a frame cannot be empty. */
    private fun changedBox(previous: IntArray, pixels: IntArray, width: Int, height: Int): IntArray {
        var top = -1
        var bottom = -1
        var left = width
        var right = -1
        for (y in 0 until height) {
            val row = y * width
            var first = -1
            var last = -1
            for (x in 0 until width) {
                if (pixels[row + x] != previous[row + x]) {
                    if (first < 0) first = x
                    last = x
                }
            }
            if (first < 0) continue
            if (top < 0) top = y
            bottom = y
            if (first < left) left = first
            if (last > right) right = last
        }
        if (top < 0) return intArrayOf(0, 0, 1, 1)
        return intArrayOf(left, top, right - left + 1, bottom - top + 1)
    }

    /** Whether drawing the box over the previous frame, unchanged pixels transparent, gives the frame exactly. */
    private fun overIsExact(previous: IntArray, pixels: IntArray, width: Int, box: IntArray): Boolean {
        val (x0, y0, w, h) = box
        for (y in y0 until y0 + h) for (x in x0 until x0 + w) {
            val i = y * width + x
            if (pixels[i] != previous[i] && (pixels[i] ushr 24) != 0xFF) return false
        }
        return true
    }

    /** fcTL: the frame's size, offset, delay to the millisecond where it fits, and how it is drawn. */
    private fun frameControl(sequence: Int, w: Int, h: Int, x: Int, y: Int, delayMillis: Int, blend: Int): ByteArray {
        val d = ByteArray(26)
        writeBe32(d, 0, sequence)
        writeBe32(d, 4, w)
        writeBe32(d, 8, h)
        writeBe32(d, 12, x)
        writeBe32(d, 16, y)
        val millis = maxOf(0, delayMillis)
        val (num, den) = when {
            millis <= 0xFFFF -> millis to 1000
            (millis + 5) / 10 <= 0xFFFF -> (millis + 5) / 10 to 100
            else -> minOf(0xFFFF, (millis + 500) / 1000) to 1
        }
        d[20] = (num ushr 8).toByte()
        d[21] = num.toByte()
        d[22] = (den ushr 8).toByte()
        d[23] = den.toByte()
        d[24] = DISPOSE_NONE.toByte()
        d[25] = blend.toByte()
        return d
    }

    /**
     * The zlib stream of the [w] by [h] box at ([x0], [y0]) of [argb], its rows [stride] apart,
     * filtered row by row. With [unchangedFrom], a pixel equal to that image's is written
     * transparent.
     */
    private fun compressed(argb: IntArray, stride: Int, x0: Int, y0: Int, w: Int, h: Int, alpha: Boolean, unchangedFrom: IntArray?): ByteArray {
        val bpp = if (alpha) 4 else 3
        val rowBytes = w * bpp
        val raw = ByteArray(h * rowBytes)
        var o = 0
        for (y in y0 until y0 + h) {
            for (x in x0 until x0 + w) {
                val i = y * stride + x
                val p = if (unchangedFrom != null && unchangedFrom[i] == argb[i]) 0 else argb[i]
                raw[o++] = ((p ushr 16) and 0xFF).toByte()
                raw[o++] = ((p ushr 8) and 0xFF).toByte()
                raw[o++] = (p and 0xFF).toByte()
                if (alpha) raw[o++] = ((p ushr 24) and 0xFF).toByte()
            }
        }

        // Filter each row with the minimum-sum-of-absolute-differences choice.
        val filtered = ByteArray(h * (1 + rowBytes))
        val candidate = ByteArray(rowBytes)
        val best = ByteArray(rowBytes)
        for (y in 0 until h) {
            val rowOfs = y * rowBytes
            val prevOfs = rowOfs - rowBytes
            var bestFilter = 0
            var bestScore = Long.MAX_VALUE
            for (filter in 0..4) {
                var score = 0L
                for (i in 0 until rowBytes) {
                    val x = raw[rowOfs + i].toInt() and 0xFF
                    val a = if (i >= bpp) raw[rowOfs + i - bpp].toInt() and 0xFF else 0
                    val b = if (y > 0) raw[prevOfs + i].toInt() and 0xFF else 0
                    val c = if (y > 0 && i >= bpp) raw[prevOfs + i - bpp].toInt() and 0xFF else 0
                    val v = when (filter) {
                        0 -> x
                        1 -> (x - a) and 0xFF
                        2 -> (x - b) and 0xFF
                        3 -> (x - ((a + b) ushr 1)) and 0xFF
                        else -> {
                            val p = a + b - c
                            val pa = abs(p - a); val pb = abs(p - b); val pc = abs(p - c)
                            val pred = if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                            (x - pred) and 0xFF
                        }
                    }
                    candidate[i] = v.toByte()
                    // MSAD treats bytes as signed deltas around 0/256.
                    score += if (v < 128) v.toLong() else (256 - v).toLong()
                }
                if (score < bestScore) {
                    bestScore = score
                    bestFilter = filter
                    candidate.copyInto(best)
                }
            }
            filtered[y * (1 + rowBytes)] = bestFilter.toByte()
            best.copyInto(filtered, y * (1 + rowBytes) + 1)
        }
        return Zlib.compress(filtered)
    }

    private fun writeBe32(d: ByteArray, at: Int, v: Int) {
        d[at] = (v ushr 24).toByte()
        d[at + 1] = (v ushr 16).toByte()
        d[at + 2] = (v ushr 8).toByte()
        d[at + 3] = v.toByte()
    }

    private fun appendChunk(out: ByteArrayBuilder, type: String, data: ByteArray) {
        val len = ByteArray(4)
        writeBe32(len, 0, data.size)
        out.append(len)
        val typeBytes = ByteArray(4) { type[it].code.toByte() }
        out.append(typeBytes)
        out.append(data)
        val crc = Crc32()
        crc.update(typeBytes)
        crc.update(data)
        val crcBytes = ByteArray(4)
        writeBe32(crcBytes, 0, crc.value().toInt())
        out.append(crcBytes)
    }
}
