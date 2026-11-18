package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.ImageMetadata
import io.github.yuroyami.imagekodec.KiteAnimation
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.KiteBitmap16
import io.github.yuroyami.imagekodec.PngColorType
import io.github.yuroyami.imagekodec.PngEncodeOptions
import io.github.yuroyami.imagekodec.internal.flate.ByteArrayBuilder
import io.github.yuroyami.imagekodec.internal.flate.Zlib

/**
 * The PNG writer behind [PngEncodeOptions] (#182): every colour type and bit depth of PNG, Third
 * Edition, 6.1 and 11.2.1, Adam7 interlace (8.2), and a search over filters (12.7 and 12.8) and
 * Deflate settings by effort. [PngEncoder] keeps the output of the calls without options; the
 * chunk, filter and frame helpers are its.
 *
 * Every sample is handled in 16-bit units, an 8-bit `v` as `v * 257`, so one survey and one row
 * packer serve [KiteBitmap] and [KiteBitmap16] alike. A sample fits `d` bits exactly when it is a
 * multiple of `65535 / (2^d - 1)`, the replication PNG, Third Edition, 13.12 describes, which is
 * also how [PngDecoder] scales it back up.
 */
internal object PngWriter {

    /** PNG colour type codes (PNG, Third Edition, table 11.1). */
    private const val GRAY = 0
    private const val RGB = 2
    private const val PALETTE = 3
    private const val GRAY_ALPHA = 4
    private const val RGBA = 6

    /** Filter strategies: 0 to 4 put that filter on every row; [ADAPTIVE] picks one a row. */
    private const val ADAPTIVE = PngEncoder.ADAPTIVE

    /** The largest array of filtered rows this writer allocates. */
    private const val MAX_BUFFER = Int.MAX_VALUE - 64

    /** Adam7 passes as x start, y start, x step, y step (PNG, Third Edition, 8.2). */
    private val ADAM7 = arrayOf(
        intArrayOf(0, 0, 8, 8), intArrayOf(4, 0, 8, 8), intArrayOf(0, 4, 4, 8), intArrayOf(2, 0, 4, 4),
        intArrayOf(0, 2, 2, 4), intArrayOf(1, 0, 2, 2), intArrayOf(0, 1, 1, 2),
    )

    /** A picture's samples: [argb] for an 8-bit bitmap, or [samples] with [channels] a pixel. */
    class Source(val width: Int, val height: Int, val argb: IntArray?, val samples: ShortArray?, val channels: Int) {
        val pixels: Int get() = width * height

        fun red(i: Int): Int =
            if (argb != null) ((argb[i] ushr 16) and 255) * 257 else samples!![i * channels].toInt() and 0xFFFF

        fun green(i: Int): Int =
            if (argb != null) ((argb[i] ushr 8) and 255) * 257
            else samples!![i * channels + (if (channels >= 3) 1 else 0)].toInt() and 0xFFFF

        fun blue(i: Int): Int =
            if (argb != null) (argb[i] and 255) * 257
            else samples!![i * channels + (if (channels >= 3) 2 else 0)].toInt() and 0xFFFF

        fun alpha(i: Int): Int = when {
            argb != null -> (argb[i] ushr 24) * 257
            channels == 2 -> samples!![i * 2 + 1].toInt() and 0xFFFF
            channels == 4 -> samples!![i * 4 + 3].toInt() and 0xFFFF
            else -> 65535
        }

        /** The pixel as 8-bit ARGB, for a source whose samples all fit 8 bits. */
        fun argb8(i: Int): Int = argb?.get(i)
            ?: (((alpha(i) ushr 8) shl 24) or ((red(i) ushr 8) shl 16) or ((green(i) ushr 8) shl 8) or (blue(i) ushr 8))

        companion object {
            fun of(bitmap: KiteBitmap) = Source(bitmap.width, bitmap.height, bitmap.argb, null, 4)
            fun of(bitmap: KiteBitmap16) = Source(bitmap.width, bitmap.height, null, bitmap.samples, bitmap.channels)
        }
    }

    /** The distinct 8-bit ARGB colours of a picture, up to 256, with a count and a palette index each. */
    class Colours {
        private val keys = IntArray(SLOTS)
        private val used = BooleanArray(SLOTS)
        private val counts = IntArray(SLOTS)
        private val index = IntArray(SLOTS)
        var size = 0
            private set

        /** True once a 257th colour came: the picture has no palette. */
        var overflow = false
            private set

        private fun slot(c: Int): Int {
            var h = (c * -0x61c88647) ushr 22   // Fibonacci hashing onto the 1024 slots
            while (used[h] && keys[h] != c) h = (h + 1) and (SLOTS - 1)
            return h
        }

        fun add(c: Int) {
            if (overflow) return
            val s = slot(c)
            if (!used[s]) {
                if (size == 256) {
                    overflow = true
                    return
                }
                used[s] = true
                keys[s] = c
                size++
            }
            counts[s]++
        }

        fun contains(c: Int): Boolean = used[slot(c)]

        fun count(c: Int): Int = counts[slot(c)]

        fun indexOf(c: Int): Int = index[slot(c)]

        fun colours(): IntArray {
            val out = IntArray(size)
            var n = 0
            for (s in 0 until SLOTS) if (used[s]) out[n++] = keys[s]
            return out
        }

        /** Gives each colour of [palette] its position there as its index. */
        fun index(palette: IntArray) {
            for ((i, c) in palette.withIndex()) index[slot(c)] = i
        }

        private companion object {
            const val SLOTS = 1024
        }
    }

    /** What the samples of a set of pictures need: which colour types and depths hold them exactly. */
    class Survey(sources: List<Source>, cancel: (() -> Unit)?) {
        var gray = true
        var opaque = true
        var fits8 = true

        /** Every alpha is 0 or the largest value. */
        var binary = true

        /** Bit `d` is set when some gray sample does not fit `d` bits, for `d` of 1, 2, 4 and 8. */
        private var inexact = 0
        val colours = Colours()

        /** The red, green and blue of every transparent pixel, when they share one colour that no opaque pixel has. */
        var key: IntArray? = null

        init {
            var first: IntArray? = null
            for (src in sources) {
                for (y in 0 until src.height) {
                    cancel?.invoke()
                    for (i in y * src.width until (y + 1) * src.width) {
                        val r = src.red(i)
                        val g = src.green(i)
                        val b = src.blue(i)
                        val a = src.alpha(i)
                        if (gray && (r != g || g != b)) gray = false
                        if (a != 65535) {
                            opaque = false
                            if (a != 0) binary = false else if (first == null) first = intArrayOf(r, g, b)
                        }
                        if (fits8 && src.argb == null && (r % 257 != 0 || g % 257 != 0 || b % 257 != 0 || a % 257 != 0)) fits8 = false
                        if (gray) {
                            if (r % 257 != 0) inexact = inexact or (1 shl 8)
                            if (r % 4369 != 0) inexact = inexact or (1 shl 4)
                            if (r % 21845 != 0) inexact = inexact or (1 shl 2)
                            if (r != 0 && r != 65535) inexact = inexact or (1 shl 1)
                        }
                        if (fits8) colours.add(src.argb8(i))
                    }
                }
            }
            val k = first
            if (binary && k != null && sources.all { keyHolds(it, k) }) key = k
        }

        private fun keyHolds(src: Source, k: IntArray): Boolean {
            for (i in 0 until src.pixels) {
                val same = src.red(i) == k[0] && src.green(i) == k[1] && src.blue(i) == k[2]
                if ((src.alpha(i) == 0) != same) return false
            }
            return true
        }

        /** Whether every gray sample fits [depth] bits. */
        fun grayFits(depth: Int): Boolean = depth == 16 || (inexact and (1 shl depth)) == 0
    }

    /**
     * A colour type and depth to write: [palette] in index order with the first [trnsCount]
     * entries' alpha in `tRNS`, or a colour [key] in 16-bit units for gray (one value) or RGB.
     */
    class Layout(val colorType: Int, val depth: Int, val palette: IntArray?, val trnsCount: Int, val key: IntArray?) {
        val channels: Int = when (colorType) {
            GRAY, PALETTE -> 1
            GRAY_ALPHA -> 2
            RGB -> 3
            else -> 4
        }
        val bitsPerPixel: Int = channels * depth

        /** Bytes to the corresponding byte of the pixel before, as the filters of PNG, Third Edition, 9.2 see it. */
        val filterUnit: Int = maxOf(1, bitsPerPixel / 8)

        fun withPalette(order: IntArray, trns: Int) = Layout(colorType, depth, order, trns, key)

        /** The bytes of the PLTE and tRNS chunks this layout writes, each with 12 of framing. */
        val tableBytes: Int get() = (if (palette != null) 12 + 3 * palette.size else 0) +
            (if (trnsCount > 0) 12 + trnsCount else 0) + (if (key != null) 12 + 2 * key.size else 0)
    }

    /** A fit of a fixed colour type, or the reason it does not hold the picture. */
    private class Fit(val layout: Layout?, val reason: String?)

    // --- Entry points ------------------------------------------------------------------------

    fun encode(bitmap: KiteBitmap, options: PngEncodeOptions, metadata: ImageMetadata, cancel: (() -> Unit)?): ByteArray =
        still(Source.of(bitmap), sixteen = false, options, metadata, cancel)

    fun encode16(bitmap: KiteBitmap16, options: PngEncodeOptions, metadata: ImageMetadata, cancel: (() -> Unit)?): ByteArray =
        still(Source.of(bitmap), sixteen = true, options, metadata, cancel)

    private fun still(src: Source, sixteen: Boolean, options: PngEncodeOptions, metadata: ImageMetadata, cancel: (() -> Unit)?): ByteArray {
        val survey = Survey(listOf(src), cancel)
        val forceTrns = sixteen && (src.channels == 2 || src.channels == 4)
        val layouts = layouts(survey, options, if (sixteen) src.channels else 0, forceTrns, iccSpace(metadata))
        val pixels = src.pixels.toLong()
        val rule = ruleChoice(layouts, pixels, survey.colours.size)
        // Effort 2 also tries the best type without a palette, which a picture of smooth shades
        // can compress better than its palette; effort 3 tries every type and both palette orders.
        val tries = when (options.effort) {
            1 -> listOf(rule)
            2 -> listOfNotNull(rule, layouts.filter { it.colorType != PALETTE }.takeIf { it.isNotEmpty() }?.let { ruleChoice(it, pixels, 0) }).distinct()
            // An alpha channel of an opaque picture, or colour channels of a gray one, only add bytes.
            else -> layouts.filterNot { l ->
                (survey.opaque && (l.colorType == GRAY_ALPHA || l.colorType == RGBA)) ||
                    (survey.gray && (l.colorType == RGB || l.colorType == RGBA) && layouts.any { it.colorType == GRAY || it.colorType == GRAY_ALPHA })
            }.ifEmpty { layouts }
        }
        var data: ByteArray? = null
        var bestSize = 0
        var bestLayout: Layout? = null
        for (layout in tries) {
            val orders = if (layout.colorType == PALETTE && options.effort == 3) listOf(false, true) else listOf(BY_FREQUENCY)
            for (byFrequency in orders) {
                val ordered = ordered(layout, survey.colours, byFrequency)
                val z = compressed(packed(src, ordered, survey.colours, options.interlace, cancel), ordered, options.effort, cancel)
                // A palette's PLTE and tRNS count against it.
                val size = z.size + ordered.tableBytes
                if (data == null || size < bestSize) {
                    data = z
                    bestSize = size
                    bestLayout = ordered
                }
            }
        }
        val layout = bestLayout!!
        val out = ByteArrayBuilder(data!!.size + 1024)
        head(out, src.width, src.height, layout, options.interlace, metadata)
        PngEncoder.appendChunk(out, "IDAT", data)
        PngEncoder.appendChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    /**
     * [animation] as an APNG in the colour type [options] asks for, chosen over every frame at
     * once, as the IHDR holds one type for all of them. The frames are cut and blended as
     * [PngEncoder.encode] cuts them; a frame drawn over the canvas needs a transparent form of an
     * unchanged pixel, which an alpha channel or a palette has when any frame is transparent.
     */
    fun encode(animation: KiteAnimation, options: PngEncodeOptions, metadata: ImageMetadata, cancel: (() -> Unit)?): ByteArray {
        val frames = animation.frames
        val width = animation.width
        val height = animation.height
        require(frames.all { it.bitmap.width == width && it.bitmap.height == height }) {
            "every APNG frame must share the ${width}x$height canvas"
        }
        require(animation.loopCount in 0L..0xFFFFFFFFL) { "an APNG play count is a 32-bit value, not ${animation.loopCount}" }
        if (frames.size == 1) return encode(frames[0].bitmap, options, metadata, cancel)
        val survey = Survey(frames.map { Source.of(it.bitmap) }, cancel)
        // A palette needs an entry of alpha 0 for the unchanged pixels of a frame drawn over the canvas.
        if (!survey.opaque && !survey.colours.overflow && survey.colours.colours().none { it ushr 24 == 0 } && survey.colours.size < 256) {
            survey.colours.add(0)
        }
        // ffmpeg 6 reads the frames after the first wrong at fewer than 8 bits a sample, so an
        // animation takes 8 unless the caller asked for fewer.
        val layouts = layouts(survey, options, 0, forceTrns = false, iccSpace(metadata)).map {
            if (it.depth < 8 && options.bitDepth == null) Layout(it.colorType, 8, it.palette, it.trnsCount, it.key) else it
        }
        val layout = ordered(ruleChoice(layouts, width.toLong() * height, survey.colours.size), survey.colours, BY_FREQUENCY)
        val clear: Int? = when {
            survey.opaque -> null
            layout.colorType == GRAY_ALPHA || layout.colorType == RGBA -> 0
            layout.colorType == PALETTE -> layout.palette!!.firstOrNull { it ushr 24 == 0 }
            // A colour key would do by the specification, but ffmpeg 6 draws a keyed pixel of a
            // frame blended over the canvas as transparent, so those frames replace the canvas.
            else -> null
        }

        val out = ByteArrayBuilder(width * height + 1024)
        head(out, width, height, layout, options.interlace, metadata)
        val actl = ByteArray(8)
        PngEncoder.writeBe32(actl, 0, frames.size)
        PngEncoder.writeBe32(actl, 4, animation.loopCount.toInt())
        PngEncoder.appendChunk(out, "acTL", actl)
        var sequence = 0
        for ((index, frame) in frames.withIndex()) {
            val pixels = frame.bitmap.argb
            if (index == 0) {
                PngEncoder.appendChunk(out, "fcTL", PngEncoder.frameControl(sequence++, width, height, 0, 0, frame.delayMillis, PngEncoder.BLEND_SOURCE))
                PngEncoder.appendChunk(out, "IDAT", idat(Source.of(frame.bitmap), layout, survey.colours, options, cancel))
                continue
            }
            val previous = frames[index - 1].bitmap.argb
            val box = PngEncoder.changedBox(previous, pixels, width, height)
            val (x, y, w, h) = box
            var data = idat(region(pixels, width, box, null, 0), layout, survey.colours, options, cancel)
            var blend = PngEncoder.BLEND_SOURCE
            if (clear != null && PngEncoder.overIsExact(previous, pixels, width, box)) {
                val over = idat(region(pixels, width, box, previous, clear), layout, survey.colours, options, cancel)
                if (over.size < data.size) {
                    data = over
                    blend = PngEncoder.BLEND_OVER
                }
            }
            PngEncoder.appendChunk(out, "fcTL", PngEncoder.frameControl(sequence++, w, h, x, y, frame.delayMillis, blend))
            val fdat = ByteArray(4 + data.size)
            PngEncoder.writeBe32(fdat, 0, sequence++)
            data.copyInto(fdat, 4)
            PngEncoder.appendChunk(out, "fdAT", fdat)
        }
        PngEncoder.appendChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    /** The [box] of [argb], its rows [stride] apart, with each pixel equal to [unchangedFrom]'s written as [clear]. */
    private fun region(argb: IntArray, stride: Int, box: IntArray, unchangedFrom: IntArray?, clear: Int): Source {
        val (x0, y0, w, h) = box
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y0 + y) * stride + x0 + x
            out[y * w + x] = if (unchangedFrom != null && unchangedFrom[i] == argb[i]) clear else argb[i]
        }
        return Source(w, h, out, null, 4)
    }

    // --- Choosing a colour type --------------------------------------------------------------

    /** The colour space of an ICC profile, which must agree with gray or colour (PNG, Third Edition, 11.3.2.3). */
    private fun iccSpace(metadata: ImageMetadata): String? {
        val icc = metadata.icc ?: return null
        if (icc.size < 20) return null
        return icc.copyOfRange(16, 20).decodeToString()
    }

    /**
     * The layouts that hold the survey's pictures exactly: the one [options] fixes, or for
     * [PngColorType.Auto] every type that does. With [sixteenChannels] of a 16-bit picture, Auto
     * keeps to the types that [PngDecoder.decode16] reads back to that many channels.
     */
    private fun layouts(survey: Survey, options: PngEncodeOptions, sixteenChannels: Int, forceTrns: Boolean, icc: String?): List<Layout> {
        fun iccAllows(type: PngColorType): Boolean = when (icc) {
            "GRAY" -> type == PngColorType.Gray || type == PngColorType.GrayAlpha
            "RGB " -> type != PngColorType.Gray && type != PngColorType.GrayAlpha
            else -> true
        }
        if (options.colorType != PngColorType.Auto) {
            require(iccAllows(options.colorType)) { "an ICC profile of colour space '${icc?.trim()}' cannot go with PngColorType.${options.colorType}" }
            val fit = fit(options.colorType, options.bitDepth, survey, forceTrns)
            return listOf(fit.layout ?: throw IllegalArgumentException("PngColorType.${options.colorType} cannot hold this picture exactly: ${fit.reason}"))
        }
        val types = when (sixteenChannels) {
            0 -> PngColorType.entries.filter { it != PngColorType.Auto }
            1 -> listOf(PngColorType.Gray)
            2 -> if (survey.opaque) listOf(PngColorType.GrayAlpha) else listOf(PngColorType.Gray, PngColorType.GrayAlpha)
            3 -> listOf(PngColorType.Palette, PngColorType.Rgb)
            else -> if (survey.opaque) listOf(PngColorType.Palette, PngColorType.Rgba) else listOf(PngColorType.Palette, PngColorType.Rgb, PngColorType.Rgba)
        }
        val out = types.filter { iccAllows(it) }.mapNotNull { fit(it, null, survey, forceTrns).layout }
        require(out.isNotEmpty()) { "no PNG colour type holds this picture with an ICC profile of colour space '${icc?.trim()}'" }
        return out
    }

    private fun fit(type: PngColorType, depth: Int?, survey: Survey, forceTrns: Boolean): Fit {
        val keyReason = "it has partial alpha, or transparent pixels of more than one colour or of a colour an opaque pixel has, which a tRNS colour key cannot hold"
        val wide = if (survey.fits8) 8 else 16
        fun deep(d: Int): Fit? = if (d == 8 && !survey.fits8) Fit(null, "it has samples that 8 bits cannot hold") else null
        return when (type) {
            PngColorType.Gray -> when {
                !survey.gray -> Fit(null, "it has pixels that are not gray")
                !survey.opaque && survey.key == null -> Fit(null, keyReason)
                depth != null && !survey.grayFits(depth) -> Fit(null, "it has gray samples that $depth bits cannot hold")
                else -> {
                    val d = depth ?: intArrayOf(1, 2, 4, 8, 16).first { survey.grayFits(it) }
                    Fit(Layout(GRAY, d, null, 0, survey.key?.let { intArrayOf(it[0]) }.takeIf { !survey.opaque }), null)
                }
            }
            PngColorType.GrayAlpha -> when {
                !survey.gray -> Fit(null, "it has pixels that are not gray")
                else -> deep(depth ?: wide) ?: Fit(Layout(GRAY_ALPHA, depth ?: wide, null, 0, null), null)
            }
            PngColorType.Rgb -> when {
                !survey.opaque && survey.key == null -> Fit(null, keyReason)
                else -> deep(depth ?: wide) ?: Fit(Layout(RGB, depth ?: wide, null, 0, survey.key.takeIf { !survey.opaque }), null)
            }
            PngColorType.Rgba -> deep(depth ?: wide) ?: Fit(Layout(RGBA, depth ?: wide, null, 0, null), null)
            PngColorType.Palette -> {
                val count = survey.colours.size
                when {
                    !survey.fits8 -> Fit(null, "it has samples that 8 bits cannot hold")
                    survey.colours.overflow -> Fit(null, "it has more than 256 colours")
                    depth != null && count > (1 shl depth) -> Fit(null, "it has $count colours, more than $depth bits index")
                    else -> {
                        val d = depth ?: intArrayOf(1, 2, 4, 8).first { count <= (1 shl it) }
                        // trnsCount here only marks that tRNS is written; ordered() sets the count.
                        Fit(Layout(PALETTE, d, IntArray(0), if (!survey.opaque || forceTrns) 1 else 0, null), null)
                    }
                }
            }
            PngColorType.Auto -> error("Auto is not a fixed type")
        }
    }

    /**
     * The layout of fewest bytes before compression, palette and `tRNS` included; on a tie gray,
     * then a palette, gray with alpha, RGB and RGBA, the order of their usual compressed sizes.
     */
    private fun ruleChoice(layouts: List<Layout>, pixels: Long, paletteSize: Int): Layout {
        // A PLTE and a tRNS chunk: 12 bytes of framing each and up to 4 bytes an entry.
        fun cost(l: Layout): Long = (pixels * l.bitsPerPixel + 7) / 8 + if (l.colorType == PALETTE) 4L * paletteSize + 24 else 0L
        fun rank(l: Layout): Int = when (l.colorType) {
            GRAY -> 0
            PALETTE -> 1
            GRAY_ALPHA -> 2
            RGB -> 3
            else -> 4
        }
        return layouts.minWith(compareBy<Layout>({ cost(it) }, { rank(it) }))
    }

    /**
     * [layout] with its palette in order: the entries that are not opaque first, so `tRNS` ends
     * at the last of them (PNG, Third Edition, 11.3.2.1), and within each group by luminance or
     * by frequency, most frequent first. Ties go by the colour value, so the order is the same
     * on every platform.
     */
    private fun ordered(layout: Layout, colours: Colours, byFrequency: Boolean): Layout {
        if (layout.colorType != PALETTE) return layout
        val all = colours.colours()
        fun luma(c: Int) = 299 * ((c ushr 16) and 255) + 587 * ((c ushr 8) and 255) + 114 * (c and 255)
        val comparator = if (byFrequency) {
            compareBy<Int>({ it ushr 24 == 255 }, { -colours.count(it) }, { it })
        } else {
            compareBy<Int>({ it ushr 24 == 255 }, { luma(it) }, { it ushr 24 }, { it })
        }
        val order = all.sortedWith(comparator).toIntArray()
        val translucent = order.count { it ushr 24 != 255 }
        val trns = if (layout.trnsCount > 0) maxOf(1, translucent) else 0
        return layout.withPalette(order, trns)
    }

    // --- Writing -----------------------------------------------------------------------------

    /** The signature, IHDR, the metadata chunks, PLTE and tRNS. */
    private fun head(out: ByteArrayBuilder, width: Int, height: Int, layout: Layout, interlace: Boolean, metadata: ImageMetadata) {
        out.append(PngEncoder.SIGNATURE)
        val ihdr = ByteArray(13)
        PngEncoder.writeBe32(ihdr, 0, width)
        PngEncoder.writeBe32(ihdr, 4, height)
        ihdr[8] = layout.depth.toByte()
        ihdr[9] = layout.colorType.toByte()
        ihdr[12] = (if (interlace) 1 else 0).toByte()
        PngEncoder.appendChunk(out, "IHDR", ihdr)
        // iCCP comes before PLTE and the image data, and so does eXIf (PNG, Third Edition, 5.6).
        for ((type, body) in MetadataIo.pngChunks(metadata)) PngEncoder.appendChunk(out, type, body)
        val palette = layout.palette
        if (palette != null) {
            val plte = ByteArray(palette.size * 3)
            for ((i, c) in palette.withIndex()) {
                plte[3 * i] = (c ushr 16).toByte()
                plte[3 * i + 1] = (c ushr 8).toByte()
                plte[3 * i + 2] = c.toByte()
            }
            PngEncoder.appendChunk(out, "PLTE", plte)
            if (layout.trnsCount > 0) PngEncoder.appendChunk(out, "tRNS", ByteArray(layout.trnsCount) { (palette[it] ushr 24).toByte() })
        }
        val key = layout.key
        if (key != null) {
            // A key is written at the image's depth, in two bytes a sample (PNG, Third Edition, 11.3.2.1).
            val shift = 16 - layout.depth
            val trns = ByteArray(key.size * 2)
            for ((i, v) in key.withIndex()) {
                val k = v ushr shift
                trns[2 * i] = (k ushr 8).toByte()
                trns[2 * i + 1] = k.toByte()
            }
            PngEncoder.appendChunk(out, "tRNS", trns)
        }
    }

    /** Deflate's match search: [chain] candidates a position (none: Huffman coding alone), [lazy], and the shortest match taken. */
    private class Setting(val chain: Int, val lazy: Boolean, val minMatch: Int)

    private val FAST = Setting(16, lazy = false, minMatch = 3)
    private val DEFAULT = Setting(128, lazy = false, minMatch = 3)

    /** Huffman coding alone, which noisy filtered rows, such as a gray photograph's, often favour. */
    private val HUFFMAN = Setting(0, lazy = false, minMatch = 3)

    /** Matches of 6 bytes or more only, the idea of zlib's Z_FILTERED strategy. */
    private val FILTERED = Setting(16, lazy = false, minMatch = 6)

    /** A filter strategy and a search, and the zlib stream they gave. */
    private class Attempt(val bytes: ByteArray, val strategy: Int, val setting: Setting)

    private fun attempt(raw: Packed, layout: Layout, strategy: Int, setting: Setting, cancel: (() -> Unit)?): Attempt =
        Attempt(Zlib.compress(filtered(raw, layout, strategy, cancel), setting.chain, setting.lazy, setting.minMatch, cancel), strategy, setting)

    /**
     * The smallest zlib stream of [raw] that [effort] looks for. At 1, the rule's filters with a
     * short search. At 2, adaptive filters and none with the default search, and adaptive
     * filters with Huffman coding alone. At 3, those of 2, and the three best of each of the six
     * filter strategies with a short search, with Huffman coding alone and with long matches
     * only, each compressed again with a long lazy search. A trial with a short search ranks
     * the filters well, but not against the default search, so the two are kept apart.
     */
    private fun compressed(raw: Packed, layout: Layout, effort: Int, cancel: (() -> Unit)?): ByteArray {
        fun smallest(plan: List<Pair<Int, Setting>>): List<Attempt> =
            plan.map { (strategy, setting) -> attempt(raw, layout, strategy, setting, cancel) }.sortedBy { it.bytes.size }
        if (effort == 1) return smallest(listOf(ruleStrategy(layout) to FAST))[0].bytes
        var best = smallest(listOf(ADAPTIVE to DEFAULT, 0 to DEFAULT, ADAPTIVE to HUFFMAN))[0].bytes
        if (effort == 2) return best
        val trials = smallest((0..ADAPTIVE).flatMap { listOf(it to FAST, it to HUFFMAN, it to FILTERED) })
        for (trial in trials.take(TRIALS_KEPT)) {
            val z = if (trial.setting.chain == 0) trial.bytes
            else attempt(raw, layout, trial.strategy, Setting(LONG_CHAIN, lazy = true, minMatch = trial.setting.minMatch), cancel).bytes
            if (z.size < best.size) best = z
        }
        return best
    }

    /** The zlib stream of [src] in [layout], filtered and compressed as [options] effort says. */
    private fun idat(src: Source, layout: Layout, colours: Colours, options: PngEncodeOptions, cancel: (() -> Unit)?): ByteArray =
        compressed(packed(src, layout, colours, options.interlace, cancel), layout, options.effort, cancel)

    /**
     * No filter for a palette or samples under 8 bits, and one chosen a row otherwise, as PNG,
     * Third Edition, 12.8 recommends.
     */
    private fun ruleStrategy(layout: Layout): Int = if (layout.colorType == PALETTE || layout.depth < 8) 0 else ADAPTIVE

    /** Unfiltered rows: [bytes] holds every pass's rows, [passes] where each starts and how wide it is. */
    private class Packed(val bytes: ByteArray, val passes: List<IntArray>)

    /** [src]'s rows in [layout], in the seven Adam7 passes when [interlace]: each pass as rows, row bytes, offset. */
    private fun packed(src: Source, layout: Layout, colours: Colours, interlace: Boolean, cancel: (() -> Unit)?): Packed {
        val geometry = if (interlace) ADAM7 else arrayOf(intArrayOf(0, 0, 1, 1))
        val passes = ArrayList<IntArray>()
        var raw = 0L
        var filteredSize = 0L
        for (p in geometry) {
            val cols = if (src.width > p[0]) (src.width - p[0] + p[2] - 1) / p[2] else 0
            val rows = if (src.height > p[1]) (src.height - p[1] + p[3] - 1) / p[3] else 0
            if (cols == 0 || rows == 0) continue
            val rowBytes = (cols.toLong() * layout.bitsPerPixel + 7) / 8
            raw += rows * rowBytes
            filteredSize += rows * (rowBytes + 1)
            require(filteredSize <= MAX_BUFFER) {
                "a ${src.width}x${src.height} PNG at ${layout.bitsPerPixel} bits a pixel needs more than ${MAX_BUFFER} bytes of rows"
            }
            passes += intArrayOf(p[0], p[1], p[2], p[3], cols, rows, rowBytes.toInt(), (raw - rows * rowBytes).toInt())
        }
        val bytes = ByteArray(raw.toInt())
        if (layout.colorType == PALETTE) colours.index(layout.palette!!)
        for (pass in passes) {
            val (x0, y0, dx, dy) = pass
            val cols = pass[4]
            val rows = pass[5]
            val rowBytes = pass[6]
            for (r in 0 until rows) {
                cancel?.invoke()
                packRow(src, layout, colours, y0 + r * dy, x0, dx, cols, bytes, pass[7] + r * rowBytes)
            }
        }
        return Packed(bytes, passes)
    }

    /** [count] pixels of row [y] of [src] from [x0], [dx] apart, as [layout]'s samples into [out] at [at]. */
    private fun packRow(src: Source, layout: Layout, colours: Colours, y: Int, x0: Int, dx: Int, count: Int, out: ByteArray, at: Int) {
        var o = at
        val depth = layout.depth
        var acc = 0
        var bits = 0
        fun put(v16: Int) {
            when (depth) {
                16 -> {
                    out[o++] = (v16 ushr 8).toByte()
                    out[o++] = v16.toByte()
                }
                8 -> out[o++] = (v16 ushr 8).toByte()
                else -> {
                    acc = (acc shl depth) or (v16 ushr (16 - depth))
                    bits += depth
                    if (bits == 8) {
                        out[o++] = acc.toByte()
                        acc = 0
                        bits = 0
                    }
                }
            }
        }
        val rowStart = y * src.width
        for (k in 0 until count) {
            val i = rowStart + x0 + k * dx
            when (layout.colorType) {
                GRAY -> put(src.red(i))
                RGB -> { put(src.red(i)); put(src.green(i)); put(src.blue(i)) }
                // An index is put as a sample of its depth: shifted to the top of 16 bits.
                PALETTE -> put(colours.indexOf(src.argb8(i)) shl (16 - depth))
                GRAY_ALPHA -> { put(src.red(i)); put(src.alpha(i)) }
                else -> { put(src.red(i)); put(src.green(i)); put(src.blue(i)); put(src.alpha(i)) }
            }
        }
        if (bits > 0) out[o] = (acc shl (8 - bits)).toByte()
    }

    /** [raw]'s rows with a filter byte each, by [strategy], each pass filtered on its own (PNG, Third Edition, 8.2). */
    private fun filtered(raw: Packed, layout: Layout, strategy: Int, cancel: (() -> Unit)?): ByteArray {
        var size = 0
        for (p in raw.passes) size += p[5] * (p[6] + 1)
        val out = ByteArray(size)
        var at = 0
        for (p in raw.passes) {
            PngEncoder.filterRows(raw.bytes, p[7], p[5], p[6], layout.filterUnit, strategy, out, at, cancel)
            at += p[5] * (p[6] + 1)
        }
        return out
    }

    /** Hash-chain candidates a position in effort 3's last pass. */
    private const val LONG_CHAIN = 1024

    /** Effort 3's trials compressed again with the long search. */
    private const val TRIALS_KEPT = 3

    /** The palette order of efforts 1 and 2: by frequency when true, by luminance otherwise. */
    private const val BY_FREQUENCY = true
}
