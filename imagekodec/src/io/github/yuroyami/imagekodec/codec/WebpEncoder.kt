package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.ImageMetadata
import io.github.yuroyami.imagekodec.KiteAnimation
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.WebpAlphaFilter
import io.github.yuroyami.imagekodec.WebpEncodeOptions
import io.github.yuroyami.imagekodec.internal.flate.ByteArrayBuilder
import io.github.yuroyami.imagekodec.internal.sourceOver

/**
 * WebP files, written to the RIFF container specification (RFC 9649 section 2). A lossless image
 * is one `VP8L` chunk from [Vp8lEncoder] (#12); with metadata, the extended form: `VP8X`, `ICCP`,
 * the image, `EXIF` and `XMP ` (#129). A lossy image is a `VP8 ` chunk from [Vp8Encoder], with its
 * alpha, if any, in an `ALPH` chunk before it; and an animation is `ANIM` and one `ANMF` chunk a
 * frame (#178).
 */
internal object WebpEncoder {

    fun encode(bitmap: KiteBitmap, metadata: ImageMetadata? = null): ByteArray {
        if (metadata != null && !metadata.isEmpty) return extended(bitmap, listOf("VP8L" to Vp8lEncoder.encode(bitmap)), metadata, bitmap.hasTransparency())
        return simple("VP8L", Vp8lEncoder.encode(bitmap))
    }

    fun encode(bitmap: KiteBitmap, options: WebpEncodeOptions, metadata: ImageMetadata, cancel: (() -> Unit)?): ByteArray {
        if (options.lossless) {
            cancel?.invoke()
            return encode(bitmap, metadata)
        }
        val chunks = lossyChunks(bitmap, options, cancel)
        if (metadata.isEmpty && chunks.size == 1) return simple("VP8 ", chunks[0].second)
        return extended(bitmap, chunks, metadata, chunks.size > 1)
    }

    /** `ALPH` (when [bitmap] has transparency) and `VP8 ` chunks of a lossy picture. */
    private fun lossyChunks(bitmap: KiteBitmap, options: WebpEncodeOptions, cancel: (() -> Unit)?): List<Pair<String, ByteArray>> {
        require(bitmap.width <= MAX_LOSSY && bitmap.height <= MAX_LOSSY) {
            "a lossy WebP is at most $MAX_LOSSY pixels a side, not ${bitmap.width}x${bitmap.height}"
        }
        val planes = Vp8Import.planes(bitmap.argb, bitmap.width, bitmap.height, options.sharpYuv)
        val vp8 = Vp8Encoder.encode(planes, options.quality, options.effort, cancel)
        if (!bitmap.hasTransparency()) return listOf("VP8 " to vp8)
        return listOf("ALPH" to alpha(bitmap, options.alphaQuality, options.alphaFilter), "VP8 " to vp8)
    }

    // --- alpha ----------------------------------------------------------------------------------

    /**
     * The `ALPH` payload of [bitmap]'s alpha (RFC 9649 section 2.7.1.2): a header byte, then the
     * levels, predicted by one of the filters and coded as the green channel of a VP8L image
     * without its five header bytes, or raw when that is smaller.
     */
    fun alpha(bitmap: KiteBitmap, alphaQuality: Int, filter: WebpAlphaFilter): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val levels = ByteArray(w * h) { (bitmap.argb[it] ushr 24).toByte() }
        val reduced = alphaQuality < 100
        if (reduced) reduceLevels(levels, 2 + alphaQuality * 254 / 100)
        val filters = when (filter) {
            WebpAlphaFilter.None -> listOf(0)
            WebpAlphaFilter.Fast -> listOf(cheapestFilter(levels, w, h))
            WebpAlphaFilter.Best -> listOf(0, 1, 2, 3)
        }
        var best: ByteArray? = null
        for (f in filters) {
            val deltas = filterLevels(levels, w, h, f)
            val header = (if (reduced) 1 shl 4 else 0) or (f shl 2)
            val image = KiteBitmap(w, h, IntArray(w * h) { 0xFF000000.toInt() or ((deltas[it].toInt() and 0xFF) shl 8) })
            val stream = Vp8lEncoder.encode(image)
            // The five bytes of signature, size and flags end on a byte boundary.
            val lossless = ByteArray(stream.size - 4)
            lossless[0] = (header or 1).toByte()
            stream.copyInto(lossless, 1, 5)
            if (best == null || lossless.size < best.size) best = lossless
            if (1 + deltas.size < best.size) {
                val raw = ByteArray(1 + deltas.size)
                raw[0] = header.toByte()
                deltas.copyInto(raw, 1)
                best = raw
            }
        }
        return best!!
    }

    /** The prediction residuals of [levels] under filter [f] (1 horizontal, 2 vertical, 3 gradient). */
    fun filterLevels(levels: ByteArray, w: Int, h: Int, f: Int): ByteArray {
        if (f == 0) return levels
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val left = if (x > 0) levels[row + x - 1].toInt() and 0xFF else -1
                val top = if (y > 0) levels[row - w + x].toInt() and 0xFF else -1
                // The first row runs left to right from 0; each later row starts from the level above it.
                val pred = when {
                    y == 0 -> if (x == 0) 0 else left
                    x == 0 -> top
                    f == 1 -> left
                    f == 2 -> top
                    else -> {
                        val g = left + top - (levels[row - w + x - 1].toInt() and 0xFF)
                        if (g < 0) 0 else if (g > 255) 255 else g
                    }
                }
                out[row + x] = ((levels[row + x].toInt() and 0xFF) - pred).toByte()
            }
        }
        return out
    }

    /** The filter whose residuals are smallest in sum of magnitude, a quick stand-in for their cost. */
    private fun cheapestFilter(levels: ByteArray, w: Int, h: Int): Int {
        var best = 0
        var bestSum = Long.MAX_VALUE
        for (f in 0..3) {
            val d = filterLevels(levels, w, h, f)
            var sum = 0L
            for (b in d) {
                val v = if (f == 0) (if (b.toInt() and 0xFF >= 128) 255 - (b.toInt() and 0xFF) else b.toInt() and 0xFF) else b.toInt().let { if (it < 0) -it else it }
                sum += v
            }
            if (sum < bestSum) {
                bestSum = sum
                best = f
            }
        }
        return best
    }

    /**
     * Reduce [levels] to [count] values by Lloyd's iteration on their histogram, with 0 and 255
     * held as values of their own so that opaque and clear pixels stay exact.
     */
    fun reduceLevels(levels: ByteArray, count: Int) {
        val histogram = LongArray(256)
        for (b in levels) histogram[b.toInt() and 0xFF]++
        val used = histogram.count { it > 0 }
        if (used <= count) return
        val centres = IntArray(count) { (it * 255 + (count - 1) / 2) / (count - 1) }
        val map = IntArray(256)
        repeat(8) {
            for (v in 0 until 256) {
                var best = 0
                for (c in 1 until count) if (kotlin.math.abs(centres[c] - v) < kotlin.math.abs(centres[best] - v)) best = c
                map[v] = best
            }
            val sum = LongArray(count)
            val n = LongArray(count)
            for (v in 0 until 256) {
                sum[map[v]] += histogram[v] * v
                n[map[v]] += histogram[v]
            }
            for (c in 1 until count - 1) if (n[c] > 0) centres[c] = ((sum[c] + n[c] / 2) / n[c]).toInt()
        }
        for (v in 0 until 256) {
            var best = 0
            for (c in 1 until count) if (kotlin.math.abs(centres[c] - v) < kotlin.math.abs(centres[best] - v)) best = c
            map[v] = centres[best]
        }
        for (i in levels.indices) levels[i] = map[levels[i].toInt() and 0xFF].toByte()
    }

    // --- animation ------------------------------------------------------------------------------

    /**
     * [animation] as `ANIM` and `ANMF` chunks. Each frame after the first codes only the rectangle
     * where it differs from what a decoder shows at that point, either replacing that rectangle or,
     * when every changed pixel is opaque, blended over it with the unchanged pixels transparent,
     * whichever is smaller. A lossy frame is compared with the decoded frames before it, so the
     * difference left in place stays within a threshold set by the quality.
     */
    fun encodeAnimation(animation: KiteAnimation, options: WebpEncodeOptions, metadata: ImageMetadata, cancel: (() -> Unit)?): ByteArray {
        val w = animation.width
        val h = animation.height
        for ((i, f) in animation.frames.withIndex()) {
            require(f.bitmap.width == w && f.bitmap.height == h) {
                "frame $i is ${f.bitmap.width}x${f.bitmap.height}, not the ${w}x$h canvas"
            }
            require(f.delayMillis in 0..0xFFFFFF) { "frame $i's delay of ${f.delayMillis} ms does not fit WebP's 24 bits" }
        }
        require(animation.loopCount <= 0xFFFF) { "a WebP loop count is at most 65535, not ${animation.loopCount}" }
        val limit = if (options.lossless) 16384 else MAX_LOSSY
        require(w <= limit && h <= limit) { "a WebP frame is at most $limit pixels a side, not ${w}x$h" }
        if (animation.frames.size == 1) return encode(animation.frames[0].bitmap, options, metadata, cancel)

        val canvas = IntArray(w * h)
        val frames = ArrayList<ByteArray>()
        var anyAlpha = false
        val threshold = if (options.lossless) 0 else 2 + (100 - options.quality) / 8
        for ((index, frame) in animation.frames.withIndex()) {
            val target = frame.bitmap.argb
            var x0 = 0; var y0 = 0; var x1 = w - 1; var y1 = h - 1
            if (index > 0) {
                x0 = w; y0 = h; x1 = -1; y1 = -1
                for (y in 0 until h) for (x in 0 until w) {
                    if (changed(target[y * w + x], canvas[y * w + x], threshold)) {
                        if (x < x0) x0 = x
                        if (x > x1) x1 = x
                        if (y < y0) y0 = y
                        if (y > y1) y1 = y
                    }
                }
            }
            if (x1 < 0) {
                // Nothing changed: a single transparent pixel, blended, keeps the canvas and the delay.
                val still = KiteBitmap(1, 1, intArrayOf(0))
                frames += anmf(0, 0, 1, 1, frame.delayMillis, blend = true, listOf("VP8L" to Vp8lEncoder.encode(still)))
                anyAlpha = true
                continue
            }
            x0 = x0 and 1.inv()
            y0 = y0 and 1.inv()
            val fw = x1 - x0 + 1
            val fh = y1 - y0 + 1
            val replace = IntArray(fw * fh) { target[(y0 + it / fw) * w + x0 + it % fw] }
            val candidates = ArrayList<Pair<Boolean, List<Pair<String, ByteArray>>>>()
            candidates += false to frameChunks(KiteBitmap(fw, fh, replace), options, cancel)
            if (index > 0) {
                var opaque = true
                val over = IntArray(fw * fh) { i ->
                    val at = (y0 + i / fw) * w + x0 + i % fw
                    if (changed(target[at], canvas[at], threshold)) {
                        if (target[at] ushr 24 != 0xFF) opaque = false
                        target[at]
                    } else {
                        0
                    }
                }
                if (opaque) candidates += true to frameChunks(KiteBitmap(fw, fh, over), options, cancel)
            }
            val (blend, chunks) = candidates.minBy { c -> c.second.sumOf { it.second.size } }
            if (chunks.size > 1 || (chunks[0].first == "VP8L" && hasAlphaBit(chunks[0].second))) anyAlpha = true
            frames += anmf(x0, y0, fw, fh, frame.delayMillis, blend, chunks)
            // What a decoder now shows: the exact frame, or the lossy one read back.
            val shown = if (options.lossless) {
                if (blend) IntArray(fw * fh) { i -> val at = (y0 + i / fw) * w + x0 + i % fw; if (changed(target[at], canvas[at], 0)) target[at] else 0 } else replace
            } else {
                WebpDecoder.decode(still(fw, fh, chunks)).argb
            }
            for (j in 0 until fh) for (i in 0 until fw) {
                val at = (y0 + j) * w + x0 + i
                val p = shown[j * fw + i]
                canvas[at] = if (blend) sourceOver(p, canvas[at]) else p
            }
            cancel?.invoke()
        }

        val anim = ByteArray(6)
        val bg = options.background
        anim[0] = bg.toByte()
        anim[1] = (bg ushr 8).toByte()
        anim[2] = (bg ushr 16).toByte()
        anim[3] = (bg ushr 24).toByte()
        anim[4] = animation.loopCount.toInt().toByte()
        anim[5] = (animation.loopCount.toInt() ushr 8).toByte()
        val chunks = ArrayList<Pair<String, ByteArray>>()
        chunks += "ANIM" to anim
        for (f in frames) chunks += "ANMF" to f
        return extended(w, h, chunks, metadata, anyAlpha, animated = true)
    }

    private fun changed(a: Int, b: Int, threshold: Int): Boolean {
        if (threshold == 0) return a != b
        if ((a ushr 24) != (b ushr 24)) return true
        if (a ushr 24 == 0) return false
        if (threshold == 0) return true
        for (shift in 0..16 step 8) {
            val d = ((a ushr shift) and 0xFF) - ((b ushr shift) and 0xFF)
            if (d > threshold || d < -threshold) return true
        }
        return false
    }

    private fun hasAlphaBit(vp8l: ByteArray): Boolean = vp8l.size > 4 && (vp8l[4].toInt() and 0x10) != 0

    private fun frameChunks(bitmap: KiteBitmap, options: WebpEncodeOptions, cancel: (() -> Unit)?): List<Pair<String, ByteArray>> =
        if (options.lossless) listOf("VP8L" to Vp8lEncoder.encode(bitmap)) else lossyChunks(bitmap, options, cancel)

    private fun anmf(x: Int, y: Int, w: Int, h: Int, duration: Int, blend: Boolean, chunks: List<Pair<String, ByteArray>>): ByteArray {
        val body = chunks.sumOf { 8 + it.second.size + (it.second.size and 1) }
        val out = ByteArrayBuilder(16 + body)
        le24(out, x / 2)
        le24(out, y / 2)
        le24(out, w - 1)
        le24(out, h - 1)
        le24(out, duration)
        out.append((if (blend) 0 else 2).toByte()) // bit 1: do not blend; bit 0 clear: no disposal
        writeChunks(out, chunks)
        return out.toByteArray()
    }

    /** A still file of one frame's chunks, to read back what a decoder will show. */
    private fun still(w: Int, h: Int, chunks: List<Pair<String, ByteArray>>): ByteArray =
        if (chunks.size == 1) simple(chunks[0].first, chunks[0].second) else extended(w, h, chunks, ImageMetadata(), alpha = true, animated = false)

    // --- container ------------------------------------------------------------------------------

    private fun simple(type: String, payload: ByteArray): ByteArray {
        val padded = payload.size + (payload.size and 1)
        val out = ByteArrayBuilder(padded + 20)
        out.append("RIFF".encodeToByteArray())
        le32(out, 4 + 8 + padded)
        out.append("WEBP".encodeToByteArray())
        writeChunks(out, listOf(type to payload))
        return out.toByteArray()
    }

    private fun extended(bitmap: KiteBitmap, image: List<Pair<String, ByteArray>>, metadata: ImageMetadata, alpha: Boolean): ByteArray =
        extended(bitmap.width, bitmap.height, image, metadata, alpha, animated = false)

    private fun extended(w: Int, h: Int, image: List<Pair<String, ByteArray>>, metadata: ImageMetadata, alpha: Boolean, animated: Boolean): ByteArray {
        val chunks = ArrayList<Pair<String, ByteArray>>()
        var flags = 0
        if (alpha) flags = flags or 0x10
        metadata.icc?.let { flags = flags or 0x20 }
        metadata.exif?.let { flags = flags or 0x08 }
        metadata.xmp?.let { flags = flags or 0x04 }
        if (animated) flags = flags or 0x02
        val vp8x = ByteArray(10)
        vp8x[0] = flags.toByte()
        val cw = w - 1
        val ch = h - 1
        vp8x[4] = cw.toByte(); vp8x[5] = (cw ushr 8).toByte(); vp8x[6] = (cw ushr 16).toByte()
        vp8x[7] = ch.toByte(); vp8x[8] = (ch ushr 8).toByte(); vp8x[9] = (ch ushr 16).toByte()
        chunks += "VP8X" to vp8x
        metadata.icc?.let { chunks += "ICCP" to it }
        chunks += image
        metadata.exif?.let { chunks += "EXIF" to it }
        metadata.xmp?.let { chunks += "XMP " to it.encodeToByteArray() }
        val body = chunks.sumOf { 8 + it.second.size + (it.second.size and 1) }
        val out = ByteArrayBuilder(body + 12)
        out.append("RIFF".encodeToByteArray())
        le32(out, 4 + body)
        out.append("WEBP".encodeToByteArray())
        writeChunks(out, chunks)
        return out.toByteArray()
    }

    private fun writeChunks(out: ByteArrayBuilder, chunks: List<Pair<String, ByteArray>>) {
        for ((type, data) in chunks) {
            out.append(type.encodeToByteArray())
            le32(out, data.size)
            out.append(data)
            if (data.size and 1 != 0) out.append(0)
        }
    }

    private fun le24(out: ByteArrayBuilder, v: Int) {
        out.append(byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte()))
    }

    private fun le32(out: ByteArrayBuilder, v: Int) {
        out.append(byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte()))
    }

    /** The largest side a VP8 frame header stores, in 14 bits. */
    const val MAX_LOSSY = 16383
}
