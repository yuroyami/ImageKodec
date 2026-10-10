package io.github.yuroyami.imagekodec.codec.avif

import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.UnsupportedImageException
import io.github.yuroyami.imagekodec.internal.Budget

/** A `grid` derived image (ISO/IEC 23008-12 section 6.6.2.3): tiles in rows, cropped to the output size. */
internal class AvifGrid(val rows: Int, val columns: Int, val width: Int, val height: Int) {

    companion object {
        fun read(data: ByteArray): AvifGrid {
            val r = IsoReader(data, 0, data.size, "grid")
            val version = r.u8()
            if (version != 0) throw UnsupportedImageException("AVIF grid of version $version")
            val flags = r.u8()
            val rows = r.u8() + 1
            val columns = r.u8() + 1
            val wide = (flags and 1) != 0
            val width = if (wide) r.u32() else r.u16().toLong()
            val height = if (wide) r.u32() else r.u16().toLong()
            if (width !in 1..65536 || height !in 1..65536 || width * height > Budget.MAX_PIXELS) {
                throw ImageDecodeException("AVIF: a grid of $width by $height")
            }
            return AvifGrid(rows, columns, width.toInt(), height.toInt())
        }

        /**
         * The tiles [tile] gives, joined row by row, as libavif checks them: every tile the
         * same size, depth, subsampling and range, the tiles together covering the output and
         * none lying wholly outside it, and in subsampled chroma an even tile size.
         */
        fun assemble(grid: AvifGrid, count: Int, tile: (Int) -> AvifPlanes): AvifPlanes {
            if (count != grid.rows * grid.columns) {
                throw ImageDecodeException("AVIF: a grid of ${grid.rows} by ${grid.columns} with $count tiles")
            }
            var out: AvifPlanes? = null
            var first: AvifPlanes? = null
            for (i in 0 until count) {
                val t = tile(i)
                val f = first ?: t.also {
                    first = it
                    val tw = it.width
                    val th = it.height
                    if (tw * grid.columns < grid.width || th * grid.rows < grid.height ||
                        tw * (grid.columns - 1) >= grid.width || th * (grid.rows - 1) >= grid.height
                    ) {
                        throw ImageDecodeException("AVIF: grid tiles of $tw by $th do not fit a ${grid.width} by ${grid.height} grid")
                    }
                    if ((it.subX != 0 && tw % 2 != 0) || (it.subY != 0 && th % 2 != 0)) {
                        throw ImageDecodeException("AVIF: grid tiles of $tw by $th with subsampled chroma")
                    }
                }
                if (t.width != f.width || t.height != f.height || t.depth != f.depth || t.subX != f.subX ||
                    t.subY != f.subY || t.planes.size != f.planes.size || t.fullRange != f.fullRange
                ) {
                    throw ImageDecodeException("AVIF: grid tile $i differs from the first")
                }
                val canvas = out ?: AvifPlanes(
                    grid.width, grid.height, f.depth, f.subX, f.subY,
                    Array(f.planes.size) { p ->
                        ShortArray(planeWidth(grid.width, if (p > 0) f.subX else 0) * planeWidth(grid.height, if (p > 0) f.subY else 0))
                    },
                    IntArray(f.planes.size) { p -> planeWidth(grid.width, if (p > 0) f.subX else 0) },
                    f.fullRange,
                ).also { out = it }
                val x0 = (i % grid.columns) * f.width
                val y0 = (i / grid.columns) * f.height
                for (p in t.planes.indices) {
                    val sx = if (p > 0) t.subX else 0
                    val sy = if (p > 0) t.subY else 0
                    val px = x0 shr sx
                    val py = y0 shr sy
                    val cw = canvas.strides[p]
                    val ch = planeWidth(grid.height, sy)
                    val w = minOf(planeWidth(t.width, sx), cw - px)
                    val h = minOf(planeWidth(t.height, sy), ch - py)
                    for (y in 0 until h) {
                        t.planes[p].copyInto(canvas.planes[p], (py + y) * cw + px, y * t.strides[p], y * t.strides[p] + w)
                    }
                }
            }
            return out!!
        }

        private fun planeWidth(size: Int, sub: Int): Int = (size + sub) shr sub
    }
}

/** An `iovl` derived image (ISO/IEC 23008-12 section 6.6.2.2): inputs drawn on a filled canvas at offsets. */
internal class AvifOverlay(val fill: IntArray, val width: Int, val height: Int, val offsets: List<Pair<Long, Long>>) {
    companion object {
        fun read(data: ByteArray, inputs: Int): AvifOverlay {
            val r = IsoReader(data, 0, data.size, "iovl")
            val version = r.u8()
            if (version != 0) throw UnsupportedImageException("AVIF overlay of version $version")
            val flags = r.u8()
            val fill = IntArray(4) { r.u16() }
            val wide = (flags and 1) != 0
            val width = if (wide) r.u32() else r.u16().toLong()
            val height = if (wide) r.u32() else r.u16().toLong()
            if (width !in 1..65536 || height !in 1..65536 || width * height > Budget.MAX_PIXELS) {
                throw ImageDecodeException("AVIF: an overlay of $width by $height")
            }
            val offsets = (0 until inputs).map {
                if (wide) r.s32().toLong() to r.s32().toLong() else r.u16().toShort().toLong() to r.u16().toShort().toLong()
            }
            return AvifOverlay(fill, width.toInt(), height.toInt(), offsets)
        }
    }
}

/**
 * A sample transform derived image (`sato`, AV1 Image File Format 1.2 section 4.2.3): each
 * output sample computed from the inputs' samples at the same place by a postfix expression,
 * in signed arithmetic of 8, 16, 32 or 64 bits that saturates instead of wrapping.
 */
internal object AvifSampleTransform {

    private class Expression(val bits: Int, val tokens: IntArray, val constants: LongArray)

    private fun read(data: ByteArray, inputs: Int): Expression {
        val r = IsoReader(data, 0, data.size, "sato")
        val head = r.u8()
        val version = head ushr 6
        if (version != 0) throw UnsupportedImageException("AVIF sample transform of version $version")
        val bits = 8 shl (head and 3)
        val count = r.u8()
        if (count == 0) r.fail("has no tokens")
        val tokens = IntArray(count)
        val constants = LongArray(count)
        var stack = 0
        for (i in 0 until count) {
            val t = r.u8()
            tokens[i] = t
            when {
                t == 0 -> {
                    constants[i] = when (bits) {
                        8 -> r.u8().toByte().toLong()
                        16 -> r.u16().toShort().toLong()
                        32 -> r.s32().toLong()
                        else -> (r.u32() shl 32) or r.u32()
                    }
                    stack++
                }
                t <= 32 -> {
                    if (t > inputs) r.fail("reads input $t of $inputs")
                    stack++
                }
                t in 64..67 -> if (stack < 1) r.fail("applies an operator to an empty stack")
                t in 128..137 -> {
                    if (stack < 2) r.fail("applies a binary operator to ${stack} operand")
                    stack--
                }
                else -> throw UnsupportedImageException("AVIF sample transform token $t")
            }
        }
        if (stack != 1) r.fail("leaves $stack values on its stack")
        return Expression(bits, tokens, constants)
    }

    /** Why the sample transform in [data] cannot be computed, or null. */
    fun unsupported(data: ByteArray, inputs: Int): String? = try {
        read(data, inputs)
        null
    } catch (e: ImageDecodeException) {
        e.message
    }

    fun apply(data: ByteArray, inputs: List<AvifPlanes>, depth: Int, fullRange: Boolean): AvifPlanes {
        if (depth !in 1..16) throw UnsupportedImageException("AVIF sample transform to $depth bits")
        val e = read(data, inputs.size)
        val f = inputs.first()
        for (input in inputs) {
            if (input.width != f.width || input.height != f.height || input.planes.size != f.planes.size ||
                input.subX != f.subX || input.subY != f.subY
            ) {
                throw ImageDecodeException("AVIF: the inputs of a sample transform differ in size or channels")
            }
        }
        val min = if (e.bits == 64) Long.MIN_VALUE else -(1L shl (e.bits - 1))
        val max = if (e.bits == 64) Long.MAX_VALUE else (1L shl (e.bits - 1)) - 1
        val stack = LongArray(e.tokens.size)
        val planes = Array(f.planes.size) { p ->
            val sx = if (p > 0) f.subX else 0
            val sy = if (p > 0) f.subY else 0
            val w = (f.width + sx) shr sx
            val h = (f.height + sy) shr sy
            val luma = p == 0 || f.planes.size == 1
            val lo = if (fullRange) 0L else (16L shl (depth - 8))
            val hi = if (fullRange) (1L shl depth) - 1 else ((if (luma) 235L else 240L) shl (depth - 8))
            val out = ShortArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var top = 0
                for (k in e.tokens.indices) {
                    val t = e.tokens[k]
                    when {
                        t == 0 -> stack[top++] = e.constants[k]
                        t <= 32 -> {
                            val input = inputs[t - 1]
                            stack[top++] = input.sample(p, y * input.strides[p] + x).toLong()
                        }
                        t < 128 -> {
                            val l = stack[top - 1]
                            stack[top - 1] = when (t) {
                                64 -> if (l == min) max else clamp(-l, min, max)
                                65 -> if (l == min) max else clamp(kotlin.math.abs(l), min, max)
                                66 -> l.inv()
                                else -> if (l <= 0) 0 else (63 - l.countLeadingZeroBits()).toLong()
                            }
                        }
                        else -> {
                            val rr = stack[--top]
                            val l = stack[top - 1]
                            stack[top - 1] = binary(t, l, rr, min, max)
                        }
                    }
                }
                out[y * w + x] = stack[0].coerceIn(lo, hi).toInt().toShort()
            }
            out
        }
        val strides = IntArray(planes.size) { p -> (f.width + (if (p > 0) f.subX else 0)) shr (if (p > 0) f.subX else 0) }
        return AvifPlanes(f.width, f.height, depth, f.subX, f.subY, planes, strides, fullRange)
    }

    private fun clamp(v: Long, min: Long, max: Long): Long = v.coerceIn(min, max)

    /** [l] [op] [r] in the expression's precision, saturating at its bounds. */
    private fun binary(op: Int, l: Long, r: Long, min: Long, max: Long): Long = when (op) {
        128 -> addSat(l, r, min, max)
        129 -> if (r == Long.MIN_VALUE) (if (l >= 0) max else clamp(l - r, min, max)) else addSat(l, -r, min, max)
        130 -> mulSat(l, r, min, max)
        131 -> if (r == 0L) l else if (l == Long.MIN_VALUE && r == -1L) max else clamp(l / r, min, max)
        132 -> l and r
        133 -> l or r
        134 -> l xor r
        135 -> pow(l, r, min, max)
        136 -> if (l <= r) l else r
        else -> if (l <= r) r else l
    }

    private fun addSat(l: Long, r: Long, min: Long, max: Long): Long {
        val s = l + r
        // Overflow in 64 bits shows as a sign the operands do not share.
        if (((l xor s) and (r xor s)) < 0) return if (l < 0) min else max
        return clamp(s, min, max)
    }

    private fun mulSat(l: Long, r: Long, min: Long, max: Long): Long {
        if (l == 0L || r == 0L) return 0
        val negative = (l < 0) != (r < 0)
        val p = l * r
        val overflow = (l == -1L && r == Long.MIN_VALUE) || (r == -1L && l == Long.MIN_VALUE) || p / r != l
        if (overflow) return if (negative) min else max
        return clamp(p, min, max)
    }

    /**
     * [l] to the power [r], truncated: a negative power of anything but 1 or -1 truncates to 0.
     * A base of 2 or more saturates within 64 multiplications, so a huge power never loops.
     */
    private fun pow(l: Long, r: Long, min: Long, max: Long): Long {
        if (l == 0L) return 0
        if (l == 1L) return 1
        if (l == -1L) return if (r % 2 == 0L) 1 else -1
        if (r < 0) return 0
        var result = 1L
        var k = 0L
        while (k < r) {
            result = mulSat(result, l, min, max)
            if (result == min || result == max) return if (l < 0 && r % 2 != 0L) min else max
            k++
        }
        return result
    }
}
