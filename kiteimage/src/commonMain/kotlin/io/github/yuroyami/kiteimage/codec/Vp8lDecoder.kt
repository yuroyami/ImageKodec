package io.github.yuroyami.kiteimage.codec

import io.github.yuroyami.kiteimage.ImageDecodeException
import io.github.yuroyami.kiteimage.internal.Budget

/**
 * VP8L, the lossless half of WebP.
 *
 * Written from the *WebP Lossless Bitstream Specification* rather than ported
 * from a reference tree, and then verified bit-exact against libwebp's own
 * `dwebp` binary across every transform, both palette packings, all ten encoder
 * effort levels and a meta-prefix-sized image. The provenance matters for a
 * clean-room library, so: spec in, oracle out, no libwebp source consulted.
 *
 * The format is a small stack of ideas rather than one big algorithm:
 *
 *  - an LZ77 back-reference layer over ARGB pixels, with distances mapped
 *    through a 120-entry plane table so "the pixel above" costs one short code
 *  - prefix (Huffman) codes for five alphabets per group: green + length +
 *    cache, red, blue, alpha, distance. A meta-prefix image lets different
 *    regions of the picture use different groups
 *  - a colour cache: a hash of recently emitted pixels, addressed by index
 *  - four reversible transforms applied by the encoder and undone here in
 *    reverse order: subtract-green, predictor (14 spatial predictors on a block
 *    grid), cross-colour, and colour-indexing (a palette, with several pixels
 *    bundled per byte when the palette is small)
 *
 * Everything is integer arithmetic on `IntArray`s, so the output is identical on
 * every target.
 */
internal object Vp8lDecoder {

    private const val MAX_DIMENSION = 1 shl 14        // the format's own 14-bit fields
    private const val MAX_PIXELS = 1L shl 28

    private const val ARGB_BLACK = 0xFF000000.toInt()

    private const val NUM_LITERAL_CODES = 256
    private const val NUM_LENGTH_CODES = 24
    private const val NUM_DISTANCE_CODES = 40
    private const val CODE_LENGTH_CODES = 19
    private const val MAX_ALLOWED_CODE_LENGTH = 15

    private const val PREDICTOR_TRANSFORM = 0
    private const val CROSS_COLOR_TRANSFORM = 1
    private const val SUBTRACT_GREEN = 2
    private const val COLOR_INDEXING_TRANSFORM = 3

    /** Order in which code-length code lengths are stored. */
    private val CODE_LENGTH_CODE_ORDER = intArrayOf(
        17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
    )

    /**
     * Distance plane codes 1..120 name a pixel near the current one instead of a
     * raw distance, so "one row up" costs a short code whatever the image width.
     *
     * The mapping is a table of 120 packed offsets (`y = byte shr 4`,
     * `x = 8 - (byte and 15)`), and it is *derived* here rather than transcribed:
     * the 120 candidates are every offset with `y` in 0..7 and `x` in -7..8,
     * minus the ones on row 0 that would point forwards, ordered by squared
     * distance, then by `y` descending, then by `x` descending. Generating it
     * from the rule removes the one failure mode a copied table has, which is a
     * silently wrong entry that only shows up on images that happen to use it.
     */
    private val CODE_TO_PLANE: IntArray = run {
        data class Offset(val distanceSquared: Int, val y: Int, val x: Int)
        val candidates = ArrayList<Offset>(120)
        for (y in 0..7) {
            for (xCode in 0..15) {
                val x = 8 - xCode
                if (y == 0 && x < 1) continue          // row 0 can only look backwards
                candidates.add(Offset(x * x + y * y, y, x))
            }
        }
        candidates.sortWith(
            compareBy<Offset> { it.distanceSquared }.thenByDescending { it.y }.thenByDescending { it.x },
        )
        IntArray(candidates.size) { (candidates[it].y shl 4) or (8 - candidates[it].x) }
    }

    private fun err(msg: String): Nothing = throw ImageDecodeException("WebP lossless: $msg")

    /** Decode the VP8L chunk at [offset] (length [length]) into packed ARGB. */
    fun decode(data: ByteArray, offset: Int, length: Int): Pixels {
        val br = BitReader(data, offset, length)
        if (br.read(8) != 0x2F) err("bad signature byte")
        val width = br.read(14) + 1
        val height = br.read(14) + 1
        br.read(1)                                    // alpha_is_used: a hint, not a fact
        if (br.read(3) != 0) err("unknown version")
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) err("${width}x$height too large")
        if (width.toLong() * height > MAX_PIXELS) err("${width}x$height exceeds safety limits")
        if (!Budget.fits(width, height, length)) {
            err("${width}x$height cannot come from a $length-byte chunk")
        }
        return Pixels(width, height, decodeImage(br, width, height, isTopLevel = true))
    }

    class Pixels(val width: Int, val height: Int, val argb: IntArray)

    // --- image stream ------------------------------------------------------------

    private class Transform(
        val type: Int,
        val bits: Int,
        val width: Int,
        val height: Int,
        val data: IntArray,
    )

    private fun decodeImage(br: BitReader, width: Int, height: Int, isTopLevel: Boolean): IntArray {
        var w = width
        val transforms = ArrayList<Transform>(4)

        if (isTopLevel) {
            // Transforms are listed in application order; we undo them in reverse.
            val seen = BooleanArray(4)
            while (br.read(1) == 1) {
                val type = br.read(2)
                if (seen[type]) err("transform $type appears twice")
                seen[type] = true
                when (type) {
                    PREDICTOR_TRANSFORM, CROSS_COLOR_TRANSFORM -> {
                        val bits = br.read(3) + 2
                        val tw = subSampleSize(w, bits)
                        val th = subSampleSize(height, bits)
                        transforms.add(Transform(type, bits, tw, th, decodeImage(br, tw, th, isTopLevel = false)))
                    }
                    SUBTRACT_GREEN -> transforms.add(Transform(type, 0, 0, 0, IntArray(0)))
                    COLOR_INDEXING_TRANSFORM -> {
                        val size = br.read(8) + 1
                        // Small palettes pack several pixels into one byte.
                        val bits = when {
                            size <= 2 -> 3
                            size <= 4 -> 2
                            size <= 16 -> 1
                            else -> 0
                        }
                        val table = decodeImage(br, size, 1, isTopLevel = false)
                        // Palette entries are stored as deltas along the row.
                        for (i in 1 until size) table[i] = addPixels(table[i], table[i - 1])
                        transforms.add(Transform(type, bits, size, 1, table))
                        w = subSampleSize(w, bits)
                    }
                    else -> err("unknown transform $type")
                }
            }
        }

        var pixels = decodeEntropyCoded(br, w, height, isTopLevel)

        for (i in transforms.indices.reversed()) {
            val t = transforms[i]
            pixels = when (t.type) {
                SUBTRACT_GREEN -> { addGreenToBlueAndRed(pixels); pixels }
                PREDICTOR_TRANSFORM -> { inversePredictor(pixels, w, height, t); pixels }
                CROSS_COLOR_TRANSFORM -> { inverseCrossColor(pixels, w, height, t); pixels }
                COLOR_INDEXING_TRANSFORM -> inverseColorIndexing(pixels, w, width, height, t)
                else -> pixels
            }
            if (t.type == COLOR_INDEXING_TRANSFORM) w = width
        }
        return pixels
    }

    private fun subSampleSize(size: Int, samplingBits: Int): Int =
        (size + (1 shl samplingBits) - 1) ushr samplingBits

    /** The five prefix codes one region of the image decodes with. */
    private class Group(
        val green: Huffman,
        val red: Huffman,
        val blue: Huffman,
        val alpha: Huffman,
        val distance: Huffman,
    )

    private fun decodeEntropyCoded(
        br: BitReader,
        width: Int,
        height: Int,
        isTopLevel: Boolean,
    ): IntArray {
        // Optional colour cache, present at every level.
        var cacheBits = 0
        if (br.read(1) == 1) {
            cacheBits = br.read(4)
            if (cacheBits < 1 || cacheBits > 11) err("colour cache bits $cacheBits out of range")
        }

        // Optional meta-prefix image: which group each block of pixels uses. Only
        // the top-level stream may carry one; nested streams (the predictor,
        // cross-colour and palette images) never do, and reading the flag anyway
        // would desynchronise everything after it.
        var metaBits = 0
        var metaImage: IntArray? = null
        var metaWidth = 0
        var groupCount = 1
        if (isTopLevel && br.read(1) == 1) {
            metaBits = br.read(3) + 2
            metaWidth = subSampleSize(width, metaBits)
            val metaHeight = subSampleSize(height, metaBits)
            val meta = decodeImage(br, metaWidth, metaHeight, isTopLevel = false)
            var max = 0
            for (i in meta.indices) {
                // The group index lives in the red and green channels.
                val g = ((meta[i] ushr 8) and 0xFFFF)
                meta[i] = g
                if (g > max) max = g
            }
            groupCount = max + 1
            metaImage = meta
        }
        if (groupCount > 1 shl 16) err("too many prefix groups")

        val greenAlphabet = NUM_LITERAL_CODES + NUM_LENGTH_CODES +
            (if (cacheBits > 0) 1 shl cacheBits else 0)
        val groups = Array(groupCount) {
            Group(
                green = readHuffmanCode(br, greenAlphabet),
                red = readHuffmanCode(br, NUM_LITERAL_CODES),
                blue = readHuffmanCode(br, NUM_LITERAL_CODES),
                alpha = readHuffmanCode(br, NUM_LITERAL_CODES),
                distance = readHuffmanCode(br, NUM_DISTANCE_CODES),
            )
        }

        val out = IntArray(width * height)
        val cache = if (cacheBits > 0) IntArray(1 shl cacheBits) else null
        var at = 0
        var x = 0
        var y = 0

        fun groupAt(px: Int, py: Int): Group {
            val meta = metaImage ?: return groups[0]
            val idx = meta[(py shr metaBits) * metaWidth + (px shr metaBits)]
            if (idx >= groups.size) err("prefix group index $idx out of ${groups.size}")
            return groups[idx]
        }

        var group = groupAt(0, 0)
        while (at < out.size) {
            // A back reference can land anywhere, so the tile lookup is redone per
            // pixel rather than only on tile boundaries.
            if (metaImage != null) group = groupAt(x, y)

            val code = group.green.decode(br)
            when {
                code < NUM_LITERAL_CODES -> {
                    // Literal: green came from the first tree, the rest follow.
                    val red = group.red.decode(br)
                    val blue = group.blue.decode(br)
                    val alpha = group.alpha.decode(br)
                    val pixel = (alpha shl 24) or (red shl 16) or (code shl 8) or blue
                    out[at++] = pixel
                    if (cache != null) cache[cacheHash(pixel, cacheBits)] = pixel
                    x++
                    if (x == width) { x = 0; y++ }
                }
                code < NUM_LITERAL_CODES + NUM_LENGTH_CODES -> {
                    // Back reference.
                    val length = prefixValue(br, code - NUM_LITERAL_CODES)
                    val distCode = group.distance.decode(br)
                    val distance = planeCodeToDistance(width, prefixValue(br, distCode))
                    if (distance > at) err("back reference of $distance at pixel $at")
                    if (at + length > out.size) err("back reference overruns the image")
                    var src = at - distance
                    repeat(length) {
                        val p = out[src++]
                        out[at++] = p
                        if (cache != null) cache[cacheHash(p, cacheBits)] = p
                    }
                    x = at % width
                    y = at / width
                }
                else -> {
                    // Colour cache hit.
                    val cacheArr = cache ?: err("cache index without a colour cache")
                    val index = code - NUM_LITERAL_CODES - NUM_LENGTH_CODES
                    if (index >= cacheArr.size) err("colour cache index $index out of ${cacheArr.size}")
                    out[at++] = cacheArr[index]
                    x++
                    if (x == width) { x = 0; y++ }
                }
            }
        }
        return out
    }

    private fun cacheHash(argb: Int, bits: Int): Int =
        ((0x1e35a7bd * argb) ushr (32 - bits))

    /**
     * Both lengths and distances use the same prefix scheme: codes 0..3 are the
     * literal values 1..4, and every code above that carries extra bits.
     */
    private fun prefixValue(br: BitReader, code: Int): Int {
        if (code < 4) return code + 1
        val extraBits = (code - 2) shr 1
        val offset = (2 + (code and 1)) shl extraBits
        return offset + br.read(extraBits) + 1
    }

    private fun planeCodeToDistance(width: Int, planeCode: Int): Int {
        if (planeCode > CODE_TO_PLANE.size) return planeCode - CODE_TO_PLANE.size
        val code = CODE_TO_PLANE[planeCode - 1]
        val yOffset = code shr 4
        val xOffset = 8 - (code and 0x0F)
        val dist = yOffset * width + xOffset
        return if (dist >= 1) dist else 1
    }

    // --- prefix codes ------------------------------------------------------------

    private fun readHuffmanCode(br: BitReader, alphabetSize: Int): Huffman {
        if (br.read(1) == 1) {
            // Simple code: one or two symbols, no lengths on the wire.
            val count = br.read(1) + 1
            val firstIsWide = br.read(1) == 1
            val symbols = IntArray(count)
            symbols[0] = br.read(if (firstIsWide) 8 else 1)
            if (count == 2) symbols[1] = br.read(8)
            for (s in symbols) if (s >= alphabetSize) err("symbol $s outside a $alphabetSize alphabet")
            val lengths = IntArray(alphabetSize)
            for (s in symbols) lengths[s] = if (count == 1) 0 else 1
            // A one-symbol code consumes no bits at all.
            return if (count == 1) Huffman.constant(symbols[0]) else Huffman(lengths)
        }

        // Normal code: the code lengths are themselves prefix-coded.
        val numCodeLengths = br.read(4) + 4
        val codeLengthLengths = IntArray(CODE_LENGTH_CODES)
        for (i in 0 until numCodeLengths) {
            codeLengthLengths[CODE_LENGTH_CODE_ORDER[i]] = br.read(3)
        }
        val lengthDecoder = Huffman(codeLengthLengths)

        var maxSymbol = alphabetSize
        if (br.read(1) == 1) {
            val lengthNBits = 2 + 2 * br.read(3)
            maxSymbol = 2 + br.read(lengthNBits)
            if (maxSymbol > alphabetSize) err("max symbol $maxSymbol beyond the alphabet")
        }

        val lengths = IntArray(alphabetSize)
        var prev = 8
        var symbol = 0
        var remaining = maxSymbol
        while (symbol < alphabetSize) {
            if (remaining-- <= 0) break
            val code = lengthDecoder.decode(br)
            if (code < 16) {
                lengths[symbol++] = code
                if (code != 0) prev = code
            } else {
                val repeatOf: Int
                val count: Int
                when (code) {
                    16 -> { repeatOf = prev; count = 3 + br.read(2) }
                    17 -> { repeatOf = 0; count = 3 + br.read(3) }
                    else -> { repeatOf = 0; count = 11 + br.read(7) }
                }
                if (symbol + count > alphabetSize) err("code length run overruns the alphabet")
                repeat(count) { lengths[symbol++] = repeatOf }
            }
        }
        return Huffman(lengths)
    }

    /**
     * Canonical prefix decoder in the DEFLATE style: bits arrive most-significant
     * first within each code, so walking one bit at a time over the per-length
     * counts is enough, with no table to build or bound.
     */
    private class Huffman private constructor(
        private val counts: IntArray,
        private val symbols: IntArray,
        private val single: Int,
    ) {
        constructor(lengths: IntArray) : this(
            counts = IntArray(MAX_ALLOWED_CODE_LENGTH + 1).also { c ->
                for (l in lengths) {
                    if (l > MAX_ALLOWED_CODE_LENGTH) err("code length $l out of range")
                    c[l]++
                }
                c[0] = 0
            },
            symbols = IntArray(lengths.size),
            single = -1,
        ) {
            // Sort symbols by (length, symbol) so the canonical order matches.
            val offsets = IntArray(MAX_ALLOWED_CODE_LENGTH + 2)
            for (l in 1..MAX_ALLOWED_CODE_LENGTH) offsets[l + 1] = offsets[l] + counts[l]
            for (s in lengths.indices) {
                if (lengths[s] != 0) symbols[offsets[lengths[s]]++] = s
            }
        }

        fun decode(br: BitReader): Int {
            if (single >= 0) return single
            var code = 0
            var first = 0
            var index = 0
            for (len in 1..MAX_ALLOWED_CODE_LENGTH) {
                code = code or br.read(1)
                val count = counts[len]
                if (code - first < count) return symbols[index + code - first]
                index += count
                first = (first + count) shl 1
                code = code shl 1
            }
            err("invalid prefix code")
        }

        companion object {
            /** A code with a single symbol reads no bits at all. */
            fun constant(symbol: Int) = Huffman(IntArray(MAX_ALLOWED_CODE_LENGTH + 1), IntArray(0), symbol)
        }
    }

    // --- transforms ---------------------------------------------------------------

    private fun addPixels(a: Int, b: Int): Int {
        val alpha = ((a ushr 24) + (b ushr 24)) and 0xFF
        val red = (((a ushr 16) and 0xFF) + ((b ushr 16) and 0xFF)) and 0xFF
        val green = (((a ushr 8) and 0xFF) + ((b ushr 8) and 0xFF)) and 0xFF
        val blue = ((a and 0xFF) + (b and 0xFF)) and 0xFF
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun addGreenToBlueAndRed(pixels: IntArray) {
        for (i in pixels.indices) {
            val p = pixels[i]
            val green = (p ushr 8) and 0xFF
            val red = ((p ushr 16) + green) and 0xFF
            val blue = (p + green) and 0xFF
            pixels[i] = (p and 0xFF00FF00.toInt()) or (red shl 16) or blue
        }
    }

    private fun inversePredictor(pixels: IntArray, width: Int, height: Int, t: Transform) {
        val bits = t.bits
        val tilesPerRow = t.width
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                if (x == 0 && y == 0) {
                    pixels[i] = addPixels(pixels[i], ARGB_BLACK)
                    continue
                }
                val mode = when {
                    y == 0 -> 1                       // top row: always predict from the left
                    x == 0 -> 2                       // left column: always from above
                    else -> (t.data[(y shr bits) * tilesPerRow + (x shr bits)] ushr 8) and 0x0F
                }
                pixels[i] = addPixels(pixels[i], predict(mode, pixels, i, width, x, y))
            }
        }
    }

    private fun predict(mode: Int, p: IntArray, i: Int, width: Int, x: Int, y: Int): Int {
        val left = if (x > 0) p[i - 1] else 0
        val top = if (y > 0) p[i - width] else 0
        val topLeft = if (x > 0 && y > 0) p[i - width - 1] else 0
        // "Top right" is read straight off the previous row with no edge case: on
        // the last column that lands on the current row's first pixel, which is
        // already decoded. The reference relies on exactly that wrap.
        val topRight = if (y > 0) p[i - width + 1] else 0
        return when (mode) {
            0 -> ARGB_BLACK
            1 -> left
            2 -> top
            3 -> topRight
            4 -> topLeft
            5 -> average2(average2(left, topRight), top)
            6 -> average2(left, topLeft)
            7 -> average2(left, top)
            8 -> average2(topLeft, top)
            9 -> average2(top, topRight)
            10 -> average2(average2(left, topLeft), average2(top, topRight))
            11 -> select(top, left, topLeft)
            12 -> clampedAddSubtractFull(left, top, topLeft)
            13 -> clampedAddSubtractHalf(left, top, topLeft)
            else -> ARGB_BLACK
        }
    }

    private fun average2(a: Int, b: Int): Int {
        // Per-channel mean with no carry between channels.
        return (((a xor b) and 0xFEFEFEFE.toInt()) ushr 1) + (a and b)
    }

    /** Predictor 11: pick whichever of left/top the gradient favours. */
    private fun select(top: Int, left: Int, topLeft: Int): Int {
        var pa = 0
        var pb = 0
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val t = (top ushr shift) and 0xFF
            val l = (left ushr shift) and 0xFF
            val tl = (topLeft ushr shift) and 0xFF
            val predict = l + t - tl
            pa += kotlin.math.abs(predict - t)
            pb += kotlin.math.abs(predict - l)
        }
        return if (pa <= pb) top else left
    }

    private fun clamp255(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    private fun clampedAddSubtractFull(a: Int, b: Int, c: Int): Int {
        var out = 0
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val v = clamp255(((a ushr shift) and 0xFF) + ((b ushr shift) and 0xFF) - ((c ushr shift) and 0xFF))
            out = out or (v shl shift)
        }
        return out
    }

    private fun clampedAddSubtractHalf(a: Int, b: Int, c: Int): Int {
        val ave = average2(a, b)
        var out = 0
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val av = (ave ushr shift) and 0xFF
            val cv = (c ushr shift) and 0xFF
            // Half the gradient away from the average. The division truncates
            // toward zero, matching the reference exactly for negative deltas.
            out = out or (clamp255(av + (av - cv) / 2) shl shift)
        }
        return out
    }

    private fun inverseCrossColor(pixels: IntArray, width: Int, height: Int, t: Transform) {
        val bits = t.bits
        val tilesPerRow = t.width
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val m = t.data[(y shr bits) * tilesPerRow + (x shr bits)]
                val greenToRed = (m and 0xFF).toByte().toInt()
                val greenToBlue = ((m ushr 8) and 0xFF).toByte().toInt()
                val redToBlue = ((m ushr 16) and 0xFF).toByte().toInt()

                val p = pixels[i]
                val green = (p ushr 8) and 0xFF
                var red = (p ushr 16) and 0xFF
                var blue = p and 0xFF
                red = (red + colorTransformDelta(greenToRed, green)) and 0xFF
                blue = (blue + colorTransformDelta(greenToBlue, green)) and 0xFF
                blue = (blue + colorTransformDelta(redToBlue, red)) and 0xFF
                pixels[i] = (p and 0xFF00FF00.toInt()) or (red shl 16) or blue
            }
        }
    }

    private fun colorTransformDelta(t: Int, c: Int): Int = (t * c.toByte().toInt()) shr 5

    private fun inverseColorIndexing(
        pixels: IntArray,
        packedWidth: Int,
        width: Int,
        height: Int,
        t: Transform,
    ): IntArray {
        val table = t.data
        val bits = t.bits
        if (bits == 0) {
            // One index per pixel: a straight palette lookup in place.
            for (i in pixels.indices) {
                val idx = (pixels[i] ushr 8) and 0xFF
                pixels[i] = if (idx < table.size) table[idx] else 0
            }
            return pixels
        }

        // Several indices per byte, unpacked left to right.
        val perPixel = 1 shl bits
        val mask = (1 shl (8 shr bits)) - 1
        val out = IntArray(width * height)
        for (y in 0 until height) {
            var packedAt = y * packedWidth
            var x = 0
            while (x < width) {
                val packed = (pixels[packedAt++] ushr 8) and 0xFF
                var slot = 0
                while (slot < perPixel && x < width) {
                    val idx = (packed shr ((8 shr bits) * slot)) and mask
                    out[y * width + x] = if (idx < table.size) table[idx] else 0
                    slot++
                    x++
                }
            }
        }
        return out
    }

    // --- bit reader ---------------------------------------------------------------

    /**
     * LSB-first bit reader. VP8L, like DEFLATE, fills each byte from bit 0 upward
     * and stores prefix codes most-significant-bit first inside that stream.
     * Reading past the end yields zero bits rather than throwing: a truncated
     * stream surfaces as a decode error from the layer above, with context.
     */
    private class BitReader(private val data: ByteArray, offset: Int, length: Int) {
        private val end = minOf(data.size, offset + length)
        private var pos = offset
        private var buf = 0L
        private var bits = 0

        fun read(n: Int): Int {
            if (n == 0) return 0
            while (bits < n) {
                val b = if (pos < end) data[pos].toLong() and 0xFF else 0L
                pos++
                buf = buf or (b shl bits)
                bits += 8
            }
            val v = (buf and ((1L shl n) - 1)).toInt()
            buf = buf ushr n
            bits -= n
            return v
        }
    }
}
