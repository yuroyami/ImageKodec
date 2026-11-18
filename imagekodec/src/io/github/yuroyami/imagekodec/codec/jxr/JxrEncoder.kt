package io.github.yuroyami.imagekodec.codec.jxr

import io.github.yuroyami.imagekodec.ImageMetadata
import io.github.yuroyami.imagekodec.JxrChroma
import io.github.yuroyami.imagekodec.JxrEncodeOptions
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.KiteBitmap16

/**
 * A JPEG XR encoder (ITU-T T.832 | ISO/IEC 29199-2, #181), written from the specification: the
 * container of Annex A, the codestream of clause 8, and the encoding process of Annex D.
 *
 * Colour goes through the reversible colour transform of D.3.2 to YUV 4:4:4 or 4:2:0, alpha
 * becomes a plane of its own, and each plane goes through [JxrForward] and [JxrPlaneEncoder].
 * The pixel formats are 8bppGray, 24bppBGR and 32bppBGRA from a [KiteBitmap], and 16bppGray,
 * 48bppRGB and 64bppRGBA from a [KiteBitmap16]. CMYK ink (#189) goes through the transform of
 * D.3.3 to YUVK, as 32bppCMYK, 40bppCMYKAlpha, 64bppCMYK or 80bppCMYKAlpha.
 */
internal object JxrEncoder {

    /** The pictures this encoder takes: its colour planes (one or three, or four of ink) and alpha, raster order. */
    private class Source(
        val width: Int,
        val height: Int,
        val depth: Int,
        val color: Array<IntArray>,
        val alpha: IntArray?,
        /** The colour planes are the channels of an N-channel format, coded each on its own. */
        val nChannel: Boolean = false,
    ) {
        val cmyk: Boolean get() = color.size == 4 && !nChannel
    }

    /**
     * Encodes [n] channels of [depth] bits a sample, [samples] interleaved, as the N-channel
     * format of 3 to 8 channels (NCOMPONENT, T.832 Table A.6), with [alpha] after them when given.
     */
    fun encodeChannels(
        width: Int, height: Int, depth: Int, n: Int, samples: IntArray, alpha: IntArray?,
        options: JxrEncodeOptions, metadata: ImageMetadata, cancel: (() -> Unit)?,
    ): ByteArray {
        require(n in 3..8) { "an N-channel format holds 3 to 8 channels, not $n" }
        require(depth == 8 || depth == 16) { "an N-channel format of $depth bits" }
        require(width > 0 && height > 0) { "a JPEG XR image of $width by $height" }
        checkSize(width, height)
        val pixels = width * height
        require(samples.size.toLong() == pixels.toLong() * n) { "${samples.size} samples for $width by $height pixels of $n channels" }
        require(alpha == null || alpha.size == pixels) { "${alpha?.size} alpha samples for $width by $height pixels" }
        val color = Array(n) { c -> IntArray(pixels) { samples[it * n + c] } }
        return encode(Source(width, height, depth, color, alpha, nChannel = true), options, metadata, cancel)
    }

    /**
     * Encodes CMYK ink: [ink] holds cyan, magenta, yellow and black of each pixel, each from no
     * ink (0) to full (255), and [alpha] one straight sample a pixel when given. The file is
     * 32bppCMYK, or 40bppCMYKAlpha with alpha.
     */
    fun encodeCmyk(
        width: Int, height: Int, ink: ByteArray, alpha: ByteArray?,
        options: JxrEncodeOptions, metadata: ImageMetadata, cancel: (() -> Unit)?,
    ): ByteArray {
        val n = checkInk(width, height, ink.size, alpha?.size, options)
        val color = Array(4) { c -> IntArray(n) { ink[it * 4 + c].toInt() and 0xFF } }
        val a = alpha?.let { s -> IntArray(n) { s[it].toInt() and 0xFF } }
        return encode(Source(width, height, 8, color, a), options, metadata, cancel)
    }

    /** [encodeCmyk] at 16 bits a sample, from no ink (0) to full (65535): 64bppCMYK, or 80bppCMYKAlpha. */
    fun encodeCmyk16(
        width: Int, height: Int, ink: ShortArray, alpha: ShortArray?,
        options: JxrEncodeOptions, metadata: ImageMetadata, cancel: (() -> Unit)?,
    ): ByteArray {
        val n = checkInk(width, height, ink.size, alpha?.size, options)
        val color = Array(4) { c -> IntArray(n) { ink[it * 4 + c].toInt() and 0xFFFF } }
        val a = alpha?.let { s -> IntArray(n) { s[it].toInt() and 0xFFFF } }
        return encode(Source(width, height, 16, color, a), options, metadata, cancel)
    }

    /** The pixels of a CMYK encode, once its sizes and options are checked. */
    private fun checkInk(width: Int, height: Int, inkSize: Int, alphaSize: Int?, options: JxrEncodeOptions): Int {
        require(width > 0 && height > 0) { "a JPEG XR image of $width by $height" }
        checkSize(width, height)
        val n = width * height
        require(inkSize.toLong() == n * 4L) { "$inkSize ink samples for $width by $height pixels, which need ${n * 4L}" }
        require(alphaSize == null || alphaSize == n) { "$alphaSize alpha samples for $width by $height pixels" }
        require(options.chroma != JxrChroma.Half) { "CMYK keeps every sample: YUVK has no 4:2:0 form" }
        return n
    }

    fun encode(bitmap: KiteBitmap, options: JxrEncodeOptions, metadata: ImageMetadata, cancel: (() -> Unit)?): ByteArray {
        checkSize(bitmap.width, bitmap.height)
        val argb = bitmap.argb
        var gray = true
        var opaque = true
        for (p in argb) {
            if (p ushr 24 != 0xFF) opaque = false
            val r = (p shr 16) and 0xFF
            if (r != (p shr 8) and 0xFF || r != p and 0xFF) gray = false
        }
        // Gray with alpha has no pixel format of its own, so it goes as BGRA.
        val channels = if (gray && opaque) 1 else 3
        val n = argb.size
        val color = Array(channels) { c ->
            val shift = if (channels == 1) 0 else 16 - 8 * c
            IntArray(n) { (argb[it] shr shift) and 0xFF }
        }
        val alpha = if (opaque) null else IntArray(n) { argb[it] ushr 24 }
        return encode(Source(bitmap.width, bitmap.height, 8, color, alpha), options, metadata, cancel)
    }

    fun encode16(bitmap: KiteBitmap16, options: JxrEncodeOptions, metadata: ImageMetadata, cancel: (() -> Unit)?): ByteArray {
        checkSize(bitmap.width, bitmap.height)
        val s = bitmap.samples
        val ch = bitmap.channels
        val n = bitmap.width * bitmap.height
        val colorChannels = if (ch <= 2) 1 else 3
        // Gray with alpha has no pixel format of its own, so it goes as RGBA.
        val planes = if (ch == 2) 3 else colorChannels
        val color = Array(planes) { c ->
            val from = if (colorChannels == 1) 0 else c
            IntArray(n) { s[it * ch + from].toInt() and 0xFFFF }
        }
        val alpha = if (bitmap.hasAlpha) IntArray(n) { s[it * ch + ch - 1].toInt() and 0xFFFF } else null
        return encode(Source(bitmap.width, bitmap.height, 16, color, alpha), options, metadata, cancel)
    }

    private fun checkSize(w: Int, h: Int) {
        // The working planes take a few Ints a sample: keep a multiple of the pixel ceiling's memory.
        require(w.toLong() * h <= MAX_PIXELS) { "a JPEG XR encode of $w by $h passes the ceiling of $MAX_PIXELS pixels" }
    }

    private const val MAX_PIXELS = 1L shl 28

    // --- Planning ------------------------------------------------------------------------------

    /**
     * What one codestream codes: its colour format, overlap, scaling, and the quantizer index of
     * luma and of chroma, which every band shares, for the frame or for each tile in raster order.
     */
    private class Plan(
        val cf: Int,
        val overlap: Int,
        val scaled: Boolean,
        val index: IntArray,
        val tileIndices: Array<IntArray>?,
        /** What the highpass quantizer adds to a magnitude before dividing, in 64ths of a step. */
        val hpRound: Int = 32,
    )

    private fun clamp(i: Int) = i.coerceIn(0, 255)

    /**
     * The quantizer index of luma at [quality], and of chroma at [chromaQuality] when given. The
     * indices were measured so that each quality lands near `JxrEncApp -q` of the same value in
     * SSIMULACRA 2 on the photo scenes; the step doubles every 16 indices (T.832 8.6.1).
     */
    private fun indices(quality: Int, chromaQuality: Int?, depth: Int, cf: Int): IntArray {
        val y = lumaIndex(quality, depth)
        val c = if (chromaQuality != null) lumaIndex(chromaQuality, depth) else y + chromaOffset(quality, cf)
        // YUVK's K is black less half of Y (T.832 D.3.3), a picture of its own, so it takes luma's index.
        if (cf == JxrColor.CMYK) return intArrayOf(clamp(y), clamp(c), clamp(c), clamp(y))
        // N channels name no luma or chroma, so every one takes luma's index.
        if (cf == JxrColor.NCOMPONENT) return intArrayOf(clamp(y))
        return intArrayOf(clamp(y), clamp(c))
    }

    /** Luma index by quality, linear between the anchors of [QUALITY_ANCHORS] at every tenth. */
    private fun lumaIndex(quality: Int, depth: Int): Int {
        val i = quality / 10
        val f = quality % 10
        val a = QUALITY_ANCHORS[i]
        val b = if (i == 10) a else QUALITY_ANCHORS[i + 1]
        val idx = (a * (10 - f) + b * f + 5) / 10
        if (depth == 8) return idx
        // 16-bit samples carry 8 more bits: the index whose step is 256 times as long.
        val want = JxrPlane.remapQP(idx, JxrPlane.SHIFT_ZERO, true).toLong() * 256
        var j = idx
        while (j < 255 && JxrPlane.remapQP(j, JxrPlane.SHIFT_ZERO, true) < want) j++
        return j
    }

    /**
     * The highpass rounding at [quality]: a wide dead zone, which drops the small coefficients that
     * cost more bits than they bring, narrowing from quality 70 up, where the steps are fine. Both
     * were measured on the photo scenes, as the size at equal SSIMULACRA 2.
     */
    private fun highpassRounding(quality: Int): Int = if (quality <= 70) 2 else 2 + (quality - 70)

    /**
     * How many indices coarser chroma is than luma: chroma of 4:4:4 carries less of what the eye
     * sees the coarser the picture, and 4:2:0 chroma has already lost half its resolution.
     */
    private fun chromaOffset(quality: Int, cf: Int): Int = if (cf == JxrColor.YUV_420) 4 else 16 + (100 - quality) / 5

    /** The luma quantizer index at qualities 0, 10, ..., 100, measured against `JxrEncApp -q` as [indices] says. */
    private val QUALITY_ANCHORS = intArrayOf(67, 60, 51, 47, 42, 37, 30, 23, 18, 10, 2)

    // --- Encoding ------------------------------------------------------------------------------

    private fun encode(src: Source, o: JxrEncodeOptions, metadata: ImageMetadata, cancel: (() -> Unit)?): ByteArray {
        val w = src.width
        val h = src.height
        val gray = src.color.size == 1
        val cmyk = src.cmyk
        val mbW = (w + 15) / 16
        val mbH = (h + 15) / 16
        val tileX = tileStarts(mbW, o.tileSize / 16)
        val tileY = tileStarts(mbH, o.tileSize / 16)
        val tiles = tileX.size * tileY.size
        require(tileX.size <= 4096 && tileY.size <= 4096) { "tileSize ${o.tileSize} gives more than 4096 tiles across or down" }
        require(o.tileQualities == null || o.tileQualities.size == tiles) {
            "tileQualities has ${o.tileQualities?.size} values for $tiles tiles"
        }
        val lossless = o.lossless
        val cf = when {
            gray -> JxrColor.Y_ONLY
            cmyk -> JxrColor.CMYK
            src.nChannel -> JxrColor.NCOMPONENT
            lossless || src.depth == 16 -> JxrColor.YUV_444
            o.chroma == JxrChroma.Half -> JxrColor.YUV_420
            o.chroma == JxrChroma.Full -> JxrColor.YUV_444
            o.quality >= 50 -> JxrColor.YUV_444
            else -> JxrColor.YUV_420
        }
        // jxrlib needs two macroblocks across for subsampled chroma under two levels of overlap.
        var overlap = o.overlap ?: if (lossless) 0 else if (o.quality >= 50) 1 else 2
        if (overlap == 2 && cf == JxrColor.YUV_420 && mbW < 2) overlap = 1
        val plan = if (lossless) {
            Plan(cf, overlap, false, intArrayOf(1, 1), null)
        } else {
            val q = indices(o.quality, o.chromaQuality, src.depth, cf)
            val perTile = o.tileQualities?.let { t -> Array(t.size) { indices(t[it], o.chromaQuality, src.depth, cf) } }
            Plan(cf, overlap, true, q, perTile, highpassRounding(o.quality))
        }
        val alpha = src.alpha
        val alphaPlan = if (alpha == null) {
            null
        } else if (o.alphaLossless) {
            // Interleaved alpha is written out with the colour plane's scaling, so it shares the colour's scaled flag.
            val scaled = !o.planarAlpha && plan.scaled
            val one = intArrayOf(if (scaled) 0 else 1)
            Plan(JxrColor.Y_ONLY, overlap, scaled, one, null)
        } else {
            val a = intArrayOf(clamp(lumaIndex(o.alphaQuality, src.depth)))
            Plan(JxrColor.Y_ONLY, overlap, true, a, null, highpassRounding(o.alphaQuality))
        }
        val interleaved = alpha != null && !o.planarAlpha
        val image = codestream(
            src, src.color, plan, if (interleaved) alpha else null, if (interleaved) alphaPlan else null,
            outputColor = if (src.nChannel) JxrColor.NCOMPONENT else if (gray) JxrColor.Y_ONLY else if (cmyk) JxrColor.CMYK else JxrColor.RGB, tileX, tileY, o.frequencyOrder, cancel,
        )
        val planar = if (alpha != null && !interleaved) {
            codestream(src, arrayOf(alpha), alphaPlan!!, null, null, JxrColor.Y_ONLY, tileX, tileY, o.frequencyOrder, cancel)
        } else {
            null
        }
        val format = when {
            cmyk -> if (src.depth == 8) (if (alpha == null) 0x1C else 0x2C) else if (alpha == null) 0x1F else 0x2D
            src.nChannel -> src.color.size - 3 + if (src.depth == 8) (if (alpha == null) 0x20 else 0x2E) else if (alpha == null) 0x26 else 0x34
            src.depth == 8 && gray -> 0x08
            src.depth == 8 && alpha == null -> 0x0C
            src.depth == 8 -> 0x0F
            gray -> 0x0B
            alpha == null -> 0x15
            else -> 0x16
        }
        return JxrContainerWriter.write(w, h, format, image, planar, metadata)
    }

    /** The first macroblock of each tile along a side of [mbs] macroblocks, [per] a tile (0 for one tile). */
    private fun tileStarts(mbs: Int, per: Int): IntArray {
        if (per == 0 || per >= mbs) return intArrayOf(0)
        return IntArray((mbs + per - 1) / per) { it * per }
    }

    /**
     * One codestream (clause 8.2): [colors] coded as [plan] says, with an interleaved alpha plane
     * when [alpha] is given.
     */
    private fun codestream(
        src: Source, colors: Array<IntArray>, plan: Plan, alpha: IntArray?, alphaPlan: Plan?, outputColor: Int,
        tileX: IntArray, tileY: IntArray, frequency: Boolean, cancel: (() -> Unit)?,
    ): ByteArray {
        val w = src.width
        val h = src.height
        val mbW = (w + 15) / 16
        val mbH = (h + 15) / 16
        val primary = planeEncoder(src, colors, plan, mbW, mbH, tileX, tileY, cancel)
        val second = if (alpha != null) planeEncoder(src, arrayOf(alpha), alphaPlan!!, mbW, mbH, tileX, tileY, cancel) else null
        val numTiles = tileX.size * tileY.size
        val bands = if (frequency) 4 else 1
        val writers = Array(numTiles * bands) { JxrBitWriter(4096) }
        for (t in 0 until numTiles) {
            for (b in 0 until bands) {
                val wr = writers[t * bands + b]
                wr.bits(1, 24)
                wr.bits(t and 0xFF, 8)
            }
        }
        val planes = listOfNotNull(primary, second)
        for (y in 0 until mbH) {
            cancel?.invoke()
            for (x in 0 until mbW) {
                for (p in planes) {
                    p.encoder.tilePosition(x, y)
                    val tile = p.encoder.currentTileRow * tileX.size + p.encoder.currentTileColumn
                    if (x == 0 && y == tileY[p.encoder.currentTileRow]) {
                        for (ctx in p.encoder.contexts) ctx.reset()
                    }
                    val out = if (frequency) {
                        JxrPlaneEncoder.Packets(writers[tile * 4], writers[tile * 4 + 1], writers[tile * 4 + 2], writers[tile * 4 + 3])
                    } else {
                        val one = writers[tile]
                        JxrPlaneEncoder.Packets(one, one, one, one)
                    }
                    if (x == tileX[p.encoder.currentTileColumn] && y == tileY[p.encoder.currentTileRow] && p === primary) {
                        writeTileHeaders(primary, second, tile, out)
                    }
                    p.encoder.encode(x, y, out, tile)
                }
            }
            for (p in planes) p.encoder.endRow()
        }
        val packets = writers.map { it.toByteArray() }
        // Spatial order keeps each tile's packet; frequency order lays out every DC packet first, then lowpass and so on.
        val order = if (frequency) (0 until 4).flatMap { b -> (0 until numTiles).map { it * 4 + b } } else packets.indices.toList()
        val offsets = LongArray(packets.size)
        var at = 0L
        for (i in order) {
            offsets[i] = at
            at += packets[i].size
        }
        val head = JxrBitWriter(256)
        writeImageHeader(head, w, h, tileX, tileY, plan.overlap, outputColor, src.depth, alpha != null, frequency)
        writePlaneHeader(head, plan, primary.channels, src.depth)
        if (second != null) writePlaneHeader(head, alphaPlan!!, 1, src.depth)
        head.bits(1, 16)
        for (off in offsets) writeVlw(head, off)
        writeVlw(head, 0)
        val header = head.toByteArray()
        val out = ByteArray(header.size + at.toInt())
        header.copyInto(out)
        var p = header.size
        for (i in order) {
            packets[i].copyInto(out, p)
            p += packets[i].size
        }
        return out
    }

    /** The quantizers of the tile at [tile] when they change from tile to tile (TILE_HEADER_DC, _LOWPASS and _HIGHPASS). */
    private fun writeTileHeaders(primary: PlaneJob, second: PlaneJob?, tile: Int, out: JxrPlaneEncoder.Packets) {
        for (p in listOfNotNull(primary, second)) {
            val t = p.plan.tileIndices ?: continue
            writeQuantizer(out.dc, t[tile], p.channels)
        }
        for (p in listOfNotNull(primary, second)) {
            val t = p.plan.tileIndices ?: continue
            out.lp.bit(false) // USE_DC_QP_FLAG
            out.lp.bits(0, 4) // NUM_LP_QPS - 1
            writeQuantizer(out.lp, t[tile], p.channels)
        }
        for (p in listOfNotNull(primary, second)) {
            val t = p.plan.tileIndices ?: continue
            out.hp.bit(false) // USE_LP_QP_FLAG
            out.hp.bits(0, 4) // NUM_HP_QPS - 1
            writeQuantizer(out.hp, t[tile], p.channels)
        }
    }

    private class PlaneJob(val encoder: JxrPlaneEncoder, val plan: Plan, val channels: Int)

    /** The forward transform of each channel of [colors] and the coder of the plane they make. */
    private fun planeEncoder(
        src: Source, colors: Array<IntArray>, plan: Plan, mbW: Int, mbH: Int, tileX: IntArray, tileY: IntArray, cancel: (() -> Unit)?,
    ): PlaneJob {
        val w = src.width
        val h = src.height
        val ew = mbW * 16
        val eh = mbH * 16
        val shift = if (plan.scaled) JxrPlane.SHIFT_ZERO + JxrPlane.QP_FRAC_BITS else 0
        val bias = if (src.depth == 8) 128 else 32768
        val channels = if (plan.cf == JxrColor.Y_ONLY) 1 else if (plan.cf == JxrColor.CMYK || plan.cf == JxrColor.NCOMPONENT) colors.size else 3
        // The samples, centred on 0, scaled, and extended to whole macroblocks by repeating the last row and column (D.3.5).
        fun extended(c: IntArray): IntArray {
            val e = IntArray(ew * eh)
            for (y in 0 until eh) {
                val sy = minOf(y, h - 1) * w
                val row = y * ew
                for (x in 0 until ew) e[row + x] = (c[sy + minOf(x, w - 1)] - bias) shl shift
            }
            return e
        }
        val planes: Array<IntArray> = if (channels == 1 || plan.cf == JxrColor.NCOMPONENT) {
            Array(channels) { extended(colors[it]) }
        } else if (plan.cf == JxrColor.CMYK) {
            // AddBias (Table 188) adds half the bias to c, m and y and takes it from k, so the
            // encoder takes it off first (D.3.1); extended() has taken the whole bias off all four.
            val half = (bias shr 1) shl shift
            val c = extended(colors[0])
            val m = extended(colors[1])
            val ye = extended(colors[2])
            val k = extended(colors[3])
            // FwdColorFmtConvert2 (Table D.7), in place: c becomes Y, m U, y V and k K. Table D.7
            // prints a plus where its comments, and the inverse of Table 186, have a minus; the
            // comments are what InvColorFmtConvert3 undoes.
            for (i in c.indices) {
                val cc = c[i] + half
                val mm = m[i] + half
                val yy = ye[i] + half
                val kk = k[i] + (bias shl shift) + half
                val v = cc - yy
                val u = cc - mm - (v shr 1)
                val y = kk - mm - (u shr 1)
                c[i] = y
                m[i] = u
                ye[i] = v
                k[i] = kk - (y shr 1)
            }
            arrayOf(c, m, ye, k)
        } else {
            val r = extended(colors[0])
            val g = extended(colors[1])
            val b = extended(colors[2])
            // FwdColorFmtConvert1 (Table D.6), in place: r becomes Y, g U and b V.
            for (i in r.indices) {
                val v = b[i] - r[i]
                val t = r[i] - g[i] + ((v + 1) shr 1)
                r[i] = g[i] + (t shr 1)
                g[i] = -t
                b[i] = v
            }
            arrayOf(r, g, b)
        }
        cancel?.invoke()
        val coef = Array(channels) { ch ->
            val c = if (ch > 0 && plan.cf == JxrColor.YUV_420) {
                val half = downsample(planes[ch], ew, eh)
                transformChannel(half, ew / 2, eh / 2, mbW, mbH, chroma420 = true, plan.overlap, plan.scaled)
            } else {
                transformChannel(planes[ch], ew, eh, mbW, mbH, chroma420 = false, plan.overlap, plan.scaled && ch > 0)
            }
            // Each plane goes once its coefficients are out, so at most one plane and its coefficients overlap.
            planes[ch] = IntArray(0)
            cancel?.invoke()
            c
        }
        val dcSteps = IntArray(channels)
        val lpSteps = IntArray(channels)
        val hpSteps = IntArray(channels)
        val tileSteps = plan.tileIndices?.let { all -> Array(all.size) { steps(all[it], channels, plan.scaled) } }
        val frame = steps(plan.index, channels, plan.scaled)
        for (ch in 0 until channels) {
            dcSteps[ch] = frame[0][ch]
            lpSteps[ch] = frame[1][ch]
            hpSteps[ch] = frame[2][ch]
        }
        // Rounding of the quantizer in 64ths of a step: DC and lowpass to the nearest, highpass with a dead zone.
        val lossless = !plan.scaled
        val encoder = JxrPlaneEncoder(
            plan.cf, channels, mbW, mbH, tileX, tileY, coef, dcSteps, lpSteps, hpSteps,
            dcRound = 32, lpRound = if (lossless) 32 else 28, hpRound = plan.hpRound, tileSteps,
        )
        return PlaneJob(encoder, plan, channels)
    }

    /** The quantization steps of DC, lowpass and highpass for each channel from indices of luma and chroma (formatQuantizer). */
    private fun steps(index: IntArray, channels: Int, scaled: Boolean): Array<IntArray> {
        fun idx(ch: Int) = if (index.size == channels) index[ch] else if (ch == 0 || index.size == 1) index[0] else index[1]
        val dc = IntArray(channels) { JxrPlane.remapQP(idx(it), if (it > 0) JxrPlane.SHIFT_ZERO - 1 else JxrPlane.SHIFT_ZERO, scaled) }
        val hp = IntArray(channels) { JxrPlane.remapQP(idx(it), JxrPlane.SHIFT_ZERO, scaled) }
        return arrayOf(dc, dc.copyOf(), hp)
    }

    /** Chroma halved across and down by a [1, 2, 1] filter centred on the even samples, where the decoder's interpolation puts them. */
    private fun downsample(p: IntArray, w: Int, h: Int): IntArray {
        val hw = w / 2
        val hh = h / 2
        val tmp = IntArray(hw * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until hw) {
                val c = 2 * x
                val l = p[row + maxOf(c - 1, 0)]
                val r = p[row + minOf(c + 1, w - 1)]
                tmp[y * hw + x] = l + 2 * p[row + c] + r
            }
        }
        val out = IntArray(hw * hh)
        for (y in 0 until hh) {
            val r = 2 * y
            val up = maxOf(r - 1, 0)
            val dn = minOf(r + 1, h - 1)
            for (x in 0 until hw) {
                val s = tmp[up * hw + x] + 2 * tmp[r * hw + x] + tmp[dn * hw + x]
                out[y * hw + x] = (s + 8) shr 4
            }
        }
        return out
    }

    /**
     * A channel of [pw] by [ph] samples through the two levels of the forward transform, into
     * the decoder's macroblock layout: the samples of each macroblock in [JxrCodestream.IDX_CC]
     * order, the coefficients of each block where its samples were, and the DC and lowpass of
     * the macroblock where its blocks' DCs were.
     */
    private fun transformChannel(
        plane: IntArray, pw: Int, ph: Int, mbW: Int, mbH: Int, chroma420: Boolean, overlap: Int, halveDC: Boolean,
    ): IntArray {
        val side = if (chroma420) 8 else 16
        val stride = side * side
        val order = if (chroma420) JxrCodestream.IDX_CC_420 else JxrCodestream.IDX_CC
        if (overlap >= 1) JxrForward.preFilterPlane(plane, pw, ph)
        val out = IntArray(mbW * mbH * stride)
        for (my in 0 until mbH) {
            for (mx in 0 until mbW) {
                val base = (my * mbW + mx) * stride
                for (r in 0 until side) {
                    val from = (my * side + r) * pw + mx * side
                    for (c in 0 until side) out[base + order[r * side + c]] = plane[from + c]
                }
                for (b in 0 until stride step 16) JxrForward.fct4x4(out, base + b)
            }
        }
        // The DC plane: one value a block, in raster order across the whole channel.
        val blocksPerMb = side / 4
        val dw = pw / 4
        val dh = ph / 4
        val dc = IntArray(dw * dh)
        fun at(bx: Int, by: Int): Int {
            val mb = (by / blocksPerMb) * mbW + bx / blocksPerMb
            val n = (bx % blocksPerMb) * blocksPerMb + by % blocksPerMb
            return mb * stride + n * 16
        }
        for (by in 0 until dh) for (bx in 0 until dw) dc[by * dw + bx] = out[at(bx, by)]
        if (overlap == 2) {
            if (chroma420) JxrForward.preFilterChroma420(dc, dw, dh) else JxrForward.preFilterPlane(dc, dw, dh)
        }
        // The decoder doubles scaled chroma after the second level (clause 9.9.2), so it is halved before.
        for (by in 0 until dh) for (bx in 0 until dw) {
            val v = dc[by * dw + bx]
            out[at(bx, by)] = if (halveDC) (v + 1) shr 1 else v
        }
        for (mb in 0 until mbW * mbH) {
            val base = mb * stride
            if (chroma420) JxrTransform.dct2x2dn(out, base, base + 32, base + 16, base + 48) else JxrForward.fct4x4Stage2(out, base)
        }
        return out
    }

    // --- Headers -------------------------------------------------------------------------------

    /** IMAGE_HEADER (clause 8.3), up to the byte boundary before the plane headers. */
    private fun writeImageHeader(
        w: JxrBitWriter, width: Int, height: Int, tileX: IntArray, tileY: IntArray, overlap: Int, outputColor: Int,
        depth: Int, alphaPlane: Boolean, frequency: Boolean,
    ) {
        w.bytes("WMPHOTO".encodeToByteArray() + byteArrayOf(0))
        val tiling = tileX.size > 1 || tileY.size > 1
        val tileSides = (1 until tileX.size).map { tileX[it] - tileX[it - 1] } + (1 until tileY.size).map { tileY[it] - tileY[it - 1] }
        val short = width <= 0x10000 && height <= 0x10000 && tileSides.all { it < 256 }
        w.bits(1, 4) // RESERVED_B
        w.bit(false) // HARD_TILING_FLAG: soft tiles, overlap runs across tile edges
        w.bits(1, 3) // RESERVED_C
        w.bit(tiling)
        w.bit(frequency)
        w.bits(0, 3) // SPATIAL_XFRM_SUBORDINATE: the container's Transformation tag turns the picture
        w.bit(true) // INDEX_TABLE_PRESENT_FLAG
        w.bits(overlap, 2)
        w.bit(short)
        w.bit(true) // LONG_WORD_FLAG: values may pass 16 bits
        w.bit(false) // WINDOWING_FLAG
        w.bit(false) // TRIM_FLEXBITS_FLAG
        w.bit(false) // RESERVED_D
        w.bit(false) // RED_BLUE_NOT_SWAPPED_FLAG, which only packed formats read
        w.bit(false) // PREMULTIPLIED_ALPHA_FLAG
        w.bit(alphaPlane)
        w.bits(outputColor, 4)
        w.bits(if (depth == 8) JxrDepth.BD_8 else JxrDepth.BD_16, 4)
        val sizeBits = if (short) 16 else 32
        w.bits(width - 1, sizeBits)
        w.bits(height - 1, sizeBits)
        if (tiling) {
            w.bits(tileX.size - 1, 12)
            w.bits(tileY.size - 1, 12)
        }
        val tileBits = if (short) 8 else 16
        for (i in 1 until tileX.size) w.bits(tileX[i] - tileX[i - 1], tileBits)
        for (i in 1 until tileY.size) w.bits(tileY[i] - tileY[i - 1], tileBits)
        w.alignToByte()
    }

    /** IMAGE_PLANE_HEADER (clause 8.4): every band present, frame quantizers unless they go per tile. */
    private fun writePlaneHeader(w: JxrBitWriter, plan: Plan, channels: Int, depth: Int) {
        w.bits(plan.cf, 3)
        w.bit(plan.scaled)
        w.bits(JxrBands.ALL, 4)
        when (plan.cf) {
            // Chroma centred on the even luma samples (CHROMA_CENTERING 0), where the downsampling centres it.
            JxrColor.YUV_420 -> w.bits(0, 8)
            JxrColor.YUV_444 -> w.bits(0, 8)
            JxrColor.NCOMPONENT -> {
                w.bits(channels - 1, 4) // NUM_COMPONENTS_MINUS1
                w.bits(0, 4) // RESERVED_H
            }
        }
        if (depth == 16) w.bits(0, 8) // SHIFT_BITS
        val frame = plan.tileIndices == null
        w.bit(frame) // DC_FRAME_UNIFORM_FLAG
        if (frame) writeQuantizer(w, plan.index, channels)
        w.bit(false) // USE_DC_QP_FLAG
        w.bit(frame) // LP_FRAME_UNIFORM_FLAG
        if (frame) writeQuantizer(w, plan.index, channels)
        w.bit(false) // USE_LP_QP_FLAG
        w.bit(frame) // HP_FRAME_UNIFORM_FLAG
        if (frame) writeQuantizer(w, plan.index, channels)
        w.alignToByte()
    }

    /** A quantizer index for each channel: one for all, one for luma and one for chroma, or one each (COMPONENT_MODE). */
    private fun writeQuantizer(w: JxrBitWriter, index: IntArray, channels: Int) {
        val luma = index[0]
        val chroma = if (index.size > 1) index[1] else luma
        if (channels > 2 && index.size == channels && index.any { it != luma } && (2 until channels).any { index[it] != chroma }) {
            w.bits(2, 2)
            for (i in index) w.bits(i, 8)
        } else if (channels > 1) {
            if (chroma == luma) {
                w.bits(0, 2)
                w.bits(luma, 8)
            } else {
                w.bits(1, 2)
                w.bits(luma, 8)
                w.bits(chroma, 8)
            }
        } else {
            w.bits(luma, 8)
        }
    }

    /** VLW_ESC (clause 8.2.4): a size in 2 bytes, or 0xFB and 4 bytes. */
    private fun writeVlw(w: JxrBitWriter, v: Long) {
        if (v < 0xFB00) {
            w.bits(v.toInt(), 16)
        } else {
            w.bits(0xFB, 8)
            w.bits(v.toInt(), 32)
        }
    }
}
