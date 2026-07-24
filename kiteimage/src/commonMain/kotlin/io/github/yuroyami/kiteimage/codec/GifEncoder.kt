package io.github.yuroyami.kiteimage.codec

import io.github.yuroyami.kiteimage.KiteAnimation
import io.github.yuroyami.kiteimage.KiteBitmap
import io.github.yuroyami.kiteimage.internal.flate.ByteArrayBuilder

/**
 * GIF89a encoder: colour quantisation, optional error-diffusion dithering, and a
 * real variable-width LZW compressor. Writes stills and animations through the
 * same path, because in GIF a still simply is an animation of one frame.
 *
 * **Palette.** GIF allows 256 colours per table, so the encoder counts the
 * distinct colours across every frame first. If they fit, the palette is those
 * exact colours and the encode is lossless. If not, a median-cut quantiser
 * splits the colour cloud along its longest axis until it has the budget, and
 * each entry becomes the pixel-weighted mean of its box: the classic Heckbert
 * algorithm, which stays cheap and, unlike a fixed web palette, spends its
 * entries where the image actually has colour.
 *
 * **Transparency.** GIF alpha is one bit. Pixels at or under 50% alpha take a
 * reserved transparent index; everything else is treated as opaque. That single
 * index costs one palette slot and is only reserved when a frame needs it.
 *
 * **Dithering.** Floyd-Steinberg error diffusion, on by default, and skipped
 * automatically when the palette is exact (there is no error to diffuse). It
 * runs in integer arithmetic so every target produces byte-identical output.
 *
 * **Animation.** One global colour table shared by all frames, a NETSCAPE2.0
 * loop block, and per-frame delays. Frames arrive from the decoder as full
 * composited canvases, so each is written full-size with "restore to background"
 * disposal: the previous frame is cleared before the next draws, which is
 * exactly the semantics a composited sequence needs.
 */
internal object GifEncoder {

    private const val MAX_CODES = 4096
    private const val TRANSPARENT_CUTOFF = 128       // alpha < this becomes the transparent index

    fun encode(bitmap: KiteBitmap, dither: Boolean = true): ByteArray =
        write(listOf(bitmap), intArrayOf(0), loopCount = 1, animated = false, dither = dither)

    fun encode(animation: KiteAnimation, dither: Boolean = true): ByteArray {
        val frames = animation.frames.map { it.bitmap }
        // Delays travel in centiseconds on the wire; round the millisecond value.
        val delays = IntArray(animation.frames.size) { i ->
            val f = animation.frames[i]
            if (f.delayRawCentiseconds > 0) f.delayRawCentiseconds else (f.delayMillis + 5) / 10
        }
        return write(frames, delays, animation.loopCount, animated = frames.size > 1, dither = dither)
    }

    // --- container --------------------------------------------------------------

    private fun write(
        frames: List<KiteBitmap>,
        delaysCs: IntArray,
        loopCount: Int,
        animated: Boolean,
        dither: Boolean,
    ): ByteArray {
        require(frames.isNotEmpty()) { "GIF needs at least one frame" }
        val width = frames[0].width
        val height = frames[0].height
        require(frames.all { it.width == width && it.height == height }) {
            "every GIF frame must share the canvas size"
        }

        val quant = quantise(frames)
        val out = ByteArrayBuilder(width * height / 2 + 1024)

        // --- header + logical screen descriptor ---------------------------------
        out.append("GIF89a".encodeToByteArray())
        out.u16(width)
        out.u16(height)
        // Global colour table present, 8-bit colour resolution, table size exponent.
        out.append((0x80 or (7 shl 4) or (quant.tableBits - 1)).toByte())
        out.append(0)                                    // background colour index
        out.append(0)                                    // pixel aspect ratio: none

        val tableSize = 1 shl quant.tableBits
        for (i in 0 until tableSize) {
            val c = if (i < quant.palette.size) quant.palette[i] else 0
            out.append(((c ushr 16) and 0xFF).toByte())
            out.append(((c ushr 8) and 0xFF).toByte())
            out.append((c and 0xFF).toByte())
        }

        // --- NETSCAPE2.0 looping ------------------------------------------------
        if (animated) {
            out.append(0x21.toByte()); out.append(0xFF.toByte()); out.append(11)
            out.append("NETSCAPE2.0".encodeToByteArray())
            out.append(3); out.append(1)
            out.u16(loopCount.coerceIn(0, 0xFFFF))
            out.append(0)
        }

        // --- frames -------------------------------------------------------------
        val minCodeSize = maxOf(2, quant.tableBits)
        for ((index, frame) in frames.withIndex()) {
            val indices = map(frame, quant, dither)

            // Graphic control extension: disposal, delay, transparent index.
            val disposal = if (animated) 2 else 0        // restore to background
            val packed = (disposal shl 2) or (if (quant.transparentIndex >= 0) 1 else 0)
            out.append(0x21.toByte()); out.append(0xF9.toByte()); out.append(4)
            out.append(packed.toByte())
            out.u16(delaysCs.getOrElse(index) { 0 }.coerceIn(0, 0xFFFF))
            out.append((if (quant.transparentIndex >= 0) quant.transparentIndex else 0).toByte())
            out.append(0)

            // Image descriptor: full canvas, no local table, not interlaced.
            out.append(0x2C.toByte())
            out.u16(0); out.u16(0)
            out.u16(width); out.u16(height)
            out.append(0)

            out.append(minCodeSize.toByte())
            writeSubBlocks(out, lzwEncode(indices, minCodeSize))
        }

        out.append(0x3B.toByte())                        // trailer
        return out.toByteArray()
    }

    private fun ByteArrayBuilder.u16(v: Int) {
        append((v and 0xFF).toByte())
        append(((v ushr 8) and 0xFF).toByte())
    }

    private fun ByteArrayBuilder.append(v: Int) = append((v and 0xFF).toByte())

    /** Split [data] into the format's ≤255-byte sub-blocks, terminated by an empty one. */
    private fun writeSubBlocks(out: ByteArrayBuilder, data: ByteArray) {
        var at = 0
        while (at < data.size) {
            val n = minOf(255, data.size - at)
            out.append(n.toByte())
            out.append(data, at, n)
            at += n
        }
        out.append(0)
    }

    // --- quantisation -----------------------------------------------------------

    private class Quantised(
        val palette: IntArray,          // 0xRRGGBB entries
        val transparentIndex: Int,      // -1 when no frame needs transparency
        val tableBits: Int,             // log2 of the padded table size, 1..8
        val exact: Boolean,             // true when no colour was approximated
        val exactLookup: HashMap<Int, Int>?,
    )

    private fun quantise(frames: List<KiteBitmap>): Quantised {
        var needsTransparent = false
        val histogram = HashMap<Int, Int>()
        for (frame in frames) {
            for (p in frame.argb) {
                if ((p ushr 24) < TRANSPARENT_CUTOFF) {
                    needsTransparent = true
                } else {
                    val rgb = p and 0x00FFFFFF
                    histogram[rgb] = (histogram[rgb] ?: 0) + 1
                }
            }
        }
        // An all-transparent image still needs one colour so the table is legal.
        if (histogram.isEmpty()) histogram[0] = 1

        val budget = if (needsTransparent) 255 else 256
        val exact = histogram.size <= budget

        val palette: IntArray = if (exact) {
            // Sorted so the same input always produces the same file.
            histogram.keys.sorted().toIntArray()
        } else {
            medianCut(histogram, budget)
        }

        val transparentIndex = if (needsTransparent) palette.size else -1
        val used = palette.size + if (needsTransparent) 1 else 0
        // Never smaller than four entries: the LZW minimum code size is 2, and a
        // table of 2 with 4 root codes trips decoders that assume they match.
        var bits = 2
        while ((1 shl bits) < used) bits++
        if (bits > 8) bits = 8

        val lookup = if (exact) {
            HashMap<Int, Int>(palette.size * 2).apply {
                for (i in palette.indices) put(palette[i], i)
            }
        } else null

        return Quantised(palette, transparentIndex, bits, exact, lookup)
    }

    /**
     * Heckbert median cut. Boxes hold a slice of the distinct-colour list; the box
     * with the longest channel range is split at the median of that channel until
     * the budget is met or no box can be split further. Each surviving box
     * contributes its pixel-count-weighted mean colour.
     */
    private fun medianCut(histogram: HashMap<Int, Int>, budget: Int): IntArray {
        val colors = histogram.keys.sorted().toIntArray()
        val counts = IntArray(colors.size) { histogram[colors[it]]!! }

        class Box(var from: Int, var to: Int) {          // [from, to)
            var rMin = 0; var rMax = 0
            var gMin = 0; var gMax = 0
            var bMin = 0; var bMax = 0
            var pixels = 0L

            fun measure() {
                rMin = 255; rMax = 0; gMin = 255; gMax = 0; bMin = 255; bMax = 0
                pixels = 0
                for (i in from until to) {
                    val c = colors[i]
                    val r = (c ushr 16) and 0xFF
                    val g = (c ushr 8) and 0xFF
                    val b = c and 0xFF
                    if (r < rMin) rMin = r; if (r > rMax) rMax = r
                    if (g < gMin) gMin = g; if (g > gMax) gMax = g
                    if (b < bMin) bMin = b; if (b > bMax) bMax = b
                    pixels += counts[i]
                }
            }

            /** Longest axis: 0 = red, 1 = green, 2 = blue. */
            fun axis(): Int {
                val dr = rMax - rMin
                val dg = gMax - gMin
                val db = bMax - bMin
                return if (dr >= dg && dr >= db) 0 else if (dg >= db) 1 else 2
            }

            fun span(): Int = maxOf(rMax - rMin, gMax - gMin, bMax - bMin)
        }

        val boxes = ArrayList<Box>(budget)
        boxes.add(Box(0, colors.size).also { it.measure() })

        while (boxes.size < budget) {
            // Split the box that is both splittable and widest; ties go to the one
            // holding more pixels, which keeps the choice deterministic.
            var best: Box? = null
            for (b in boxes) {
                if (b.to - b.from < 2 || b.span() == 0) continue
                if (best == null || b.span() > best.span() ||
                    (b.span() == best.span() && b.pixels > best.pixels)
                ) {
                    best = b
                }
            }
            val box = best ?: break

            // Sort this slice by the longest axis, then cut at the pixel median.
            val axis = box.axis()
            val slice = colors.copyOfRange(box.from, box.to)
            val sliceCounts = IntArray(slice.size) { counts[box.from + it] }
            val order = slice.indices.sortedWith(
                compareBy({ channel(slice[it], axis) }, { slice[it] }),
            )
            for (i in order.indices) {
                colors[box.from + i] = slice[order[i]]
                counts[box.from + i] = sliceCounts[order[i]]
            }

            // Cut where the running pixel count crosses half the box: the median by
            // pixels, not by distinct colours, so a big flat area keeps its own box.
            var acc = 0L
            var cut = box.to - 1
            for (i in box.from until box.to - 1) {
                acc += counts[i]
                if (acc * 2 >= box.pixels) { cut = i + 1; break }
            }

            val right = Box(cut, box.to)
            box.to = cut
            box.measure()
            right.measure()
            boxes.add(right)
        }

        return IntArray(boxes.size) { i ->
            val b = boxes[i]
            var r = 0L; var g = 0L; var bl = 0L; var n = 0L
            for (j in b.from until b.to) {
                val c = colors[j]
                val w = counts[j].toLong()
                r += ((c ushr 16) and 0xFF) * w
                g += ((c ushr 8) and 0xFF) * w
                bl += (c and 0xFF) * w
                n += w
            }
            if (n == 0L) 0 else {
                (((r + n / 2) / n).toInt() shl 16) or
                    (((g + n / 2) / n).toInt() shl 8) or
                    ((bl + n / 2) / n).toInt()
            }
        }
    }

    private fun channel(color: Int, axis: Int): Int = when (axis) {
        0 -> (color ushr 16) and 0xFF
        1 -> (color ushr 8) and 0xFF
        else -> color and 0xFF
    }

    // --- pixel → index mapping ---------------------------------------------------

    private fun map(frame: KiteBitmap, quant: Quantised, dither: Boolean): ByteArray {
        val w = frame.width
        val h = frame.height
        val out = ByteArray(w * h)

        if (quant.exact) {
            // Every colour is in the table, so this is a lookup, not a search.
            val lookup = quant.exactLookup!!
            for (i in out.indices) {
                val p = frame.argb[i]
                out[i] = if ((p ushr 24) < TRANSPARENT_CUTOFF && quant.transparentIndex >= 0) {
                    quant.transparentIndex.toByte()
                } else {
                    (lookup[p and 0x00FFFFFF] ?: 0).toByte()
                }
            }
            return out
        }

        val nearest = NearestCache(quant.palette)
        if (!dither) {
            for (i in out.indices) {
                val p = frame.argb[i]
                out[i] = if ((p ushr 24) < TRANSPARENT_CUTOFF && quant.transparentIndex >= 0) {
                    quant.transparentIndex.toByte()
                } else {
                    nearest.of((p ushr 16) and 0xFF, (p ushr 8) and 0xFF, p and 0xFF).toByte()
                }
            }
            return out
        }

        // Floyd-Steinberg: 7/16 right, 3/16 below-left, 5/16 below, 1/16 below-right.
        var curr = IntArray(w * 3)
        var next = IntArray(w * 3)
        for (y in 0 until h) {
            next.fill(0)
            for (x in 0 until w) {
                val i = y * w + x
                val p = frame.argb[i]
                if ((p ushr 24) < TRANSPARENT_CUTOFF && quant.transparentIndex >= 0) {
                    out[i] = quant.transparentIndex.toByte()
                    continue
                }
                val r = clamp8(((p ushr 16) and 0xFF) + (curr[x * 3] shr 4))
                val g = clamp8(((p ushr 8) and 0xFF) + (curr[x * 3 + 1] shr 4))
                val b = clamp8((p and 0xFF) + (curr[x * 3 + 2] shr 4))

                val idx = nearest.of(r, g, b)
                out[i] = idx.toByte()
                val c = quant.palette[idx]
                // Errors stay in 1/16 units so the whole diffusion is integer maths.
                val er = (r - ((c ushr 16) and 0xFF)) shl 4
                val eg = (g - ((c ushr 8) and 0xFF)) shl 4
                val eb = (b - (c and 0xFF)) shl 4

                if (x + 1 < w) {
                    curr[(x + 1) * 3] += er * 7 / 16
                    curr[(x + 1) * 3 + 1] += eg * 7 / 16
                    curr[(x + 1) * 3 + 2] += eb * 7 / 16
                }
                if (x > 0) {
                    next[(x - 1) * 3] += er * 3 / 16
                    next[(x - 1) * 3 + 1] += eg * 3 / 16
                    next[(x - 1) * 3 + 2] += eb * 3 / 16
                }
                next[x * 3] += er * 5 / 16
                next[x * 3 + 1] += eg * 5 / 16
                next[x * 3 + 2] += eb * 5 / 16
                if (x + 1 < w) {
                    next[(x + 1) * 3] += er / 16
                    next[(x + 1) * 3 + 1] += eg / 16
                    next[(x + 1) * 3 + 2] += eb / 16
                }
            }
            val swap = curr; curr = next; next = swap
        }
        return out
    }

    private fun clamp8(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    /**
     * Nearest-palette-entry lookup, memoised on a 5:5:5 grid. A linear scan over
     * 256 entries per pixel is too slow for a full-screen frame; binning to 32768
     * cells and filling them lazily keeps it O(1) per pixel after the first hit in
     * each cell, at a colour error far below what quantisation already introduced.
     */
    private class NearestCache(private val palette: IntArray) {
        private val cache = IntArray(1 shl 15) { -1 }

        fun of(r: Int, g: Int, b: Int): Int {
            val key = ((r and 0xF8) shl 7) or ((g and 0xF8) shl 2) or (b ushr 3)
            val hit = cache[key]
            if (hit >= 0) return hit

            // Search from the cell centre so every colour in the cell gets the same,
            // stable answer.
            val cr = (r and 0xF8) or 4
            val cg = (g and 0xF8) or 4
            val cb = (b and 0xF8) or 4
            var best = 0
            var bestDist = Int.MAX_VALUE
            for (i in palette.indices) {
                val c = palette[i]
                val dr = cr - ((c ushr 16) and 0xFF)
                val dg = cg - ((c ushr 8) and 0xFF)
                val db = cb - (c and 0xFF)
                val d = dr * dr + dg * dg + db * db
                if (d < bestDist) { bestDist = d; best = i }
            }
            cache[key] = best
            return best
        }
    }

    // --- LZW ---------------------------------------------------------------------

    /**
     * GIF's variable-width LSB-first LZW, the exact inverse of the decoder in
     * [GifDecoder]: codes widen when the next free code reaches `2^width`, and a
     * full 4096-entry table emits a CLEAR and starts over.
     */
    private fun lzwEncode(indices: ByteArray, minCodeSize: Int): ByteArray {
        val clear = 1 shl minCodeSize
        val end = clear + 1
        val bits = BitWriter()

        var codeSize = minCodeSize + 1
        var next = end + 1
        val dict = HashMap<Int, Int>(1024)

        bits.write(clear, codeSize)
        if (indices.isEmpty()) {
            bits.write(end, codeSize)
            return bits.finish()
        }

        var prefix = indices[0].toInt() and 0xFF
        for (i in 1 until indices.size) {
            val k = indices[i].toInt() and 0xFF
            val key = (prefix shl 8) or k
            val existing = dict[key]
            if (existing != null) {
                prefix = existing
                continue
            }
            bits.write(prefix, codeSize)
            if (next < MAX_CODES) {
                dict[key] = next++
                // The decoder learns each entry one code later than the encoder
                // creates it, so its `avail` always trails this `next` by one.
                // Widening at `next > 2^width` (not `==`) is what keeps the two
                // sides reading and writing the same number of bits.
                if (next > (1 shl codeSize) && codeSize < 12) codeSize++
            } else {
                bits.write(clear, codeSize)
                dict.clear()
                codeSize = minCodeSize + 1
                next = end + 1
            }
            prefix = k
        }
        bits.write(prefix, codeSize)
        bits.write(end, codeSize)
        return bits.finish()
    }

    /** LSB-first bit packer: GIF fills each byte from bit 0 upward. */
    private class BitWriter {
        private val out = ByteArrayBuilder(256)
        private var acc = 0
        private var count = 0

        fun write(code: Int, width: Int) {
            acc = acc or (code shl count)
            count += width
            while (count >= 8) {
                out.append((acc and 0xFF).toByte())
                acc = acc ushr 8
                count -= 8
            }
        }

        fun finish(): ByteArray {
            if (count > 0) out.append((acc and 0xFF).toByte())
            return out.toByteArray()
        }
    }
}
