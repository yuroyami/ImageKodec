package io.github.yuroyami.imagekodec

/**
 * A decoded image at 16 bits a sample, for the files whose low byte matters: depth
 * maps, scientific and medical images, heavily graded photographs. [KiteBitmap] keeps
 * 8 bits a channel and is what drawing wants; this keeps every bit a 16-bit PNG or
 * TIFF stores, and [ImageKodec.decode16] is where it comes from.
 *
 * [samples] holds [channels] samples a pixel, row by row, interleaved
 * (`samples[(y * width + x) * channels + c]`), each an unsigned 16-bit value in a
 * `Short`: read one with `toInt() and 0xFFFF`, or through [get]. The channels are
 * those the file stores once a palette is looked up: 1 gray, 2 gray and alpha, 3 RGB,
 * 4 RGBA. Alpha is straight, as in [KiteBitmap], so a gray depth map stays one sample
 * a pixel rather than four.
 *
 * [samples] is exposed directly, not copied. Treat it as read-only; mutate it only if
 * you own the instance.
 *
 * @throws IllegalArgumentException if the dimensions are not positive, [channels] is
 *   not 1 to 4, or [samples] does not hold exactly [width] × [height] × [channels] samples
 */
public class KiteBitmap16 @Throws(IllegalArgumentException::class) public constructor(
    public val width: Int,
    public val height: Int,
    public val channels: Int,
    public val samples: ShortArray,
) {
    init {
        require(width > 0 && height > 0) { "bitmap dimensions must be positive: ${width}x$height" }
        require(channels in 1..4) { "channels must be 1 to 4, was $channels" }
        require(samples.size.toLong() == width.toLong() * height * channels) {
            "samples has ${samples.size} values, expected ${width}x${height}x$channels = ${width.toLong() * height * channels}"
        }
    }

    /** True for gray and alpha (2 channels) and RGBA (4). */
    public val hasAlpha: Boolean get() = channels == 2 || channels == 4

    /**
     * Sample [channel] of the pixel at ([x], [y]), 0 to 65535.
     * @throws IllegalArgumentException if the coordinates or the channel are out of range
     */
    @Throws(IllegalArgumentException::class)
    public operator fun get(x: Int, y: Int, channel: Int): Int {
        require(x in 0 until width && y in 0 until height) { "($x, $y) outside ${width}x$height" }
        require(channel in 0 until channels) { "channel $channel of $channels" }
        return samples[(y * width + x) * channels + channel].toInt() and 0xFFFF
    }

    /**
     * This image at 8 bits a channel, each sample cut to its high byte, which is what
     * [ImageKodec.decode] returns for a 16-bit PNG or TIFF.
     */
    public fun toBitmap(): KiteBitmap {
        val argb = IntArray(width * height)
        for (i in argb.indices) {
            val at = i * channels
            fun s(c: Int) = (samples[at + c].toInt() and 0xFFFF) ushr 8
            argb[i] = when (channels) {
                1 -> 0xFF000000.toInt() or (s(0) * 0x010101)
                2 -> (s(1) shl 24) or (s(0) * 0x010101)
                3 -> 0xFF000000.toInt() or (s(0) shl 16) or (s(1) shl 8) or s(2)
                else -> (s(3) shl 24) or (s(0) shl 16) or (s(1) shl 8) or s(2)
            }
        }
        return KiteBitmap(width, height, argb)
    }

    /**
     * Apply an EXIF [orientation], as [KiteBitmap.oriented] does. [Orientation.Normal]
     * returns the same instance.
     */
    public fun oriented(orientation: Orientation): KiteBitmap16 {
        if (orientation == Orientation.Normal) return this
        val swap = orientation.swapsAxes
        val dw = if (swap) height else width
        val dh = if (swap) width else height
        val out = ShortArray(samples.size)
        for (y in 0 until dh) for (x in 0 until dw) {
            // dst(x, y) = src(sx, sy), as each 8-bit transform maps it
            val sx: Int
            val sy: Int
            when (orientation) {
                Orientation.FlipHorizontal -> { sx = width - 1 - x; sy = y }
                Orientation.Rotate180 -> { sx = width - 1 - x; sy = height - 1 - y }
                Orientation.FlipVertical -> { sx = x; sy = height - 1 - y }
                Orientation.Transpose -> { sx = y; sy = x }
                Orientation.Rotate90 -> { sx = y; sy = height - 1 - x }
                Orientation.Transverse -> { sx = width - 1 - y; sy = height - 1 - x }
                else -> { sx = width - 1 - y; sy = x }   // Rotate270
            }
            val from = (sy * width + sx) * channels
            samples.copyInto(out, (y * dw + x) * channels, from, from + channels)
        }
        return KiteBitmap16(dw, dh, channels, out)
    }

    override fun toString(): String = "KiteBitmap16(${width}x$height, $channels channels)"

    internal companion object {
        /** [bitmap]'s 8-bit samples widened exactly, `v * 257`, as RGB, or RGBA when [alpha]. */
        fun widened(bitmap: KiteBitmap, alpha: Boolean): KiteBitmap16 {
            val channels = if (alpha) 4 else 3
            val out = ShortArray(bitmap.argb.size * channels)
            for (i in bitmap.argb.indices) {
                val p = bitmap.argb[i]
                val at = i * channels
                out[at] = (((p ushr 16) and 0xFF) * 257).toShort()
                out[at + 1] = (((p ushr 8) and 0xFF) * 257).toShort()
                out[at + 2] = ((p and 0xFF) * 257).toShort()
                if (alpha) out[at + 3] = ((p ushr 24) * 257).toShort()
            }
            return KiteBitmap16(bitmap.width, bitmap.height, channels, out)
        }
    }
}
