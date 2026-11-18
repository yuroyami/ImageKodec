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
 * 48bppRGB and 64bppRGBA from a [KiteBitmap16].
 */
internal object JxrEncoder {

    /** The pictures this encoder takes: its colour planes (one or three) and alpha, raster order. */
    private class Source(
        val width: Int,
        val height: Int,
        val depth: Int,
        val color: Array<IntArray>,
        val alpha: IntArray?,
    )

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

    /** What one codestream codes: its colour format, overlap and the quantizer indices of each band and channel. */
    private class Plan(
        val cf: Int,
        val overlap: Int,
        val scaled: Boolean,
        val dc: IntArray,
        val lp: IntArray,
        val hp: IntArray,
        /** Per tile, raster order, or null for one set for the frame. */
        val tileIndices: Array<IntArray>?,
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
        return intArrayOf(clamp(y), clamp(c))
    }

    /** Luma index by quality, linear between the anchors of [QUALITY_ANCHORS] at every tenth. */
    private fun lumaIndex(quality: Int, depth: Int): Int {
        val i = quality / 10
        val f = quality % 10
        val a = QUALITY_ANCHORS[i]
        val b = if (i == 10) a else QUALITY_ANCHORS[i + 1]
        val idx = (a * (10 - f) + b * f + 5) / 10
        // 16-bit samples carry 8 more bits, which 8 doublings of the step, 128 indices, take back.
        return if (depth == 16) idx + 128 else idx
    }

    private fun chromaOffset(quality: Int, cf: Int): Int = if (cf == JxrColor.YUV_420) 4 else 12 + (100 - quality) / 10

    /** The luma quantizer index at qualities 0, 10, ..., 100. */
    private val QUALITY_ANCHORS = intArrayOf(68, 60, 54, 50, 46, 41, 34, 28, 20, 13, 4)

    // --- Encoding ------------------------------------------------------------------------------

    private fun encode(src: Source, o: JxrEncodeOptions, metadata: ImageMetadata, cancel: (() -> Unit)?): ByteArray {
        val w = src.width
        val h = src.height
        val gray = src.color.size == 1
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
            Plan(cf, overlap, false, intArrayOf(1, 1), intArrayOf(1, 1), intArrayOf(1, 1), null)
        } else {
            val q = indices(o.quality, o.chromaQuality, src.depth, cf)
            val perTile = o.tileQualities?.let { t -> Array(t.size) { indices(t[it], o.chromaQuality, src.depth, cf) } }
            Plan(cf, overlap, true, q, q, q, perTile)
        }
        val alpha = src.alpha
        val alphaPlan = if (alpha == null) {
            null
        } else if (o.alphaLossless) {
            // Interleaved alpha is written out with the colour plane's scaling, so it shares the colour's scaled flag.
            val scaled = !o.planarAlpha && plan.scaled
            val one = intArrayOf(if (scaled) 0 else 1)
            Plan(JxrColor.Y_ONLY, overlap, scaled, one, one, one, null)
        } else {
            val a = intArrayOf(clamp(lumaIndex(o.alphaQuality, src.depth)))
            Plan(JxrColor.Y_ONLY, overlap, true, a, a, a, null)
        }
        val interleaved = alpha != null && !o.planarAlpha
        val image = codestream(
            src, src.color, plan, if (interleaved) alpha else null, if (interleaved) alphaPlan else null,
            outputColor = if (gray) JxrColor.Y_ONLY else JxrColor.RGB, tileX, tileY, o.frequencyOrder, cancel,
        )
        val planar = if (alpha != null && !interleaved) {
            codestream(src, arrayOf(alpha), alphaPlan!!, null, null, JxrColor.Y_ONLY, tileX, tileY, o.frequencyOrder, cancel)
        } else {
            null
        }
        val format = when {
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
        val channels = if (plan.cf == JxrColor.Y_ONLY) 1 else 3
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
        val planes: Array<IntArray> = if (channels == 1) {
            arrayOf(extended(colors[0]))
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
            if (ch > 0 && plan.cf == JxrColor.YUV_420) {
                val half = downsample(planes[ch], ew, eh)
                transformChannel(half, ew / 2, eh / 2, mbW, mbH, chroma420 = true, plan.overlap, plan.scaled)
            } else {
                transformChannel(planes[ch], ew, eh, mbW, mbH, chroma420 = false, plan.overlap, plan.scaled && ch > 0)
            }
        }
        val dcSteps = IntArray(channels)
        val lpSteps = IntArray(channels)
        val hpSteps = IntArray(channels)
        val tileSteps = plan.tileIndices?.let { all -> Array(all.size) { steps(all[it], channels, plan.scaled) } }
        val frame = steps(plan.dc, channels, plan.scaled)
        for (ch in 0 until channels) {
            dcSteps[ch] = frame[0][ch]
            lpSteps[ch] = frame[1][ch]
            hpSteps[ch] = frame[2][ch]
        }
        // Rounding of the quantizer in 64ths of a step: DC and lowpass to the nearest, highpass with a dead zone.
        val lossless = !plan.scaled && plan.dc[0] <= 1
        val encoder = JxrPlaneEncoder(
            plan.cf, channels, mbW, mbH, tileX, tileY, coef, dcSteps, lpSteps, hpSteps,
            dcRound = 32, lpRound = 32, hpRound = if (lossless) 32 else 22, tileSteps,
        )
        return PlaneJob(encoder, plan, channels)
    }

    /** The quantization steps of DC, lowpass and highpass for each channel from indices of luma and chroma (formatQuantizer). */
    private fun steps(index: IntArray, channels: Int, scaled: Boolean): Array<IntArray> {
        fun idx(ch: Int) = if (ch == 0 || index.size == 1) index[0] else index[1]
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
        }
        if (depth == 16) w.bits(0, 8) // SHIFT_BITS
        val frame = plan.tileIndices == null
        w.bit(frame) // DC_FRAME_UNIFORM_FLAG
        if (frame) writeQuantizer(w, plan.dc, channels)
        w.bit(false) // USE_DC_QP_FLAG
        w.bit(frame) // LP_FRAME_UNIFORM_FLAG
        if (frame) writeQuantizer(w, plan.lp, channels)
        w.bit(false) // USE_LP_QP_FLAG
        w.bit(frame) // HP_FRAME_UNIFORM_FLAG
        if (frame) writeQuantizer(w, plan.hp, channels)
        w.alignToByte()
    }

    /** A quantizer index for each channel: one for all, or one for luma and one for chroma (COMPONENT_MODE). */
    private fun writeQuantizer(w: JxrBitWriter, index: IntArray, channels: Int) {
        val luma = index[0]
        val chroma = if (index.size > 1) index[1] else luma
        if (channels > 1) {
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
