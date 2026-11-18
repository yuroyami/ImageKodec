package io.github.yuroyami.imagekodec.codec.jxl

import io.github.yuroyami.imagekodec.ImageMetadata
import io.github.yuroyami.imagekodec.JxlEncodeOptions
import io.github.yuroyami.imagekodec.KiteAnimation
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.KiteBitmap16

/**
 * The samples of one picture to encode: [colors] colour planes (gray, or red, green and blue),
 * then alpha when [alpha] is set, each of [w] by [h] samples of [bits] bits.
 */
internal class JxlEncPicture(val w: Int, val h: Int, val bits: Int, val colors: Int, val alpha: Boolean, val planes: Array<IntArray>) {
    companion object {
        fun of(bitmap: KiteBitmap, keepAlpha: Boolean = bitmap.hasTransparency(), allowGray: Boolean = true): JxlEncPicture {
            val argb = bitmap.argb
            val gray = allowGray && argb.all { p -> ((p ushr 16) and 0xFF) == (p and 0xFF) && ((p ushr 8) and 0xFF) == (p and 0xFF) }
            val colors = if (gray) 1 else 3
            val planes = Array(colors + if (keepAlpha) 1 else 0) { IntArray(argb.size) }
            for (i in argb.indices) {
                val p = argb[i]
                if (gray) {
                    planes[0][i] = p and 0xFF
                } else {
                    planes[0][i] = (p ushr 16) and 0xFF
                    planes[1][i] = (p ushr 8) and 0xFF
                    planes[2][i] = p and 0xFF
                }
                if (keepAlpha) planes[colors][i] = p ushr 24
            }
            return JxlEncPicture(bitmap.width, bitmap.height, 8, colors, keepAlpha, planes)
        }

        fun of(bitmap: KiteBitmap16): JxlEncPicture {
            val n = bitmap.width * bitmap.height
            val ch = bitmap.channels
            val planes = Array(ch) { c -> IntArray(n) { bitmap.samples[it * ch + c].toInt() and 0xFFFF } }
            val colors = if (ch <= 2) 1 else 3
            return JxlEncPicture(bitmap.width, bitmap.height, 16, colors, bitmap.hasAlpha, planes)
        }
    }
}

/**
 * Writes JPEG XL (ISO/IEC 18181-1 and -2) from the encoder's own design: Modular for lossless
 * pictures and for alpha, VarDCT in XYB for lossy colour, and frames with durations for
 * animations. It reuses the decoder's definitions of what both sides share.
 */
internal object JxlEncoder {

    /** The largest side a frame may have, which keeps every count below the decoder's ceilings. */
    private const val MAX_SIDE = 1 shl 18

    fun encode(bitmap: KiteBitmap, options: JxlEncodeOptions, metadata: ImageMetadata, cancel: (() -> Unit)?): ByteArray =
        encodePictures(listOf(JxlEncPicture.of(bitmap)), null, 0, options, metadata, cancel)

    fun encode16(bitmap: KiteBitmap16, options: JxlEncodeOptions, metadata: ImageMetadata, cancel: (() -> Unit)?): ByteArray =
        encodePictures(listOf(JxlEncPicture.of(bitmap)), null, 0, options, metadata, cancel)

    fun encode(animation: KiteAnimation, options: JxlEncodeOptions, metadata: ImageMetadata, cancel: (() -> Unit)?): ByteArray {
        for (f in animation.frames) {
            require(f.bitmap.width == animation.width && f.bitmap.height == animation.height) {
                "JPEG XL: frame of ${f.bitmap.width}x${f.bitmap.height} on a canvas of ${animation.width}x${animation.height}"
            }
            require(f.delayMillis >= 0) { "JPEG XL: frame delay ${f.delayMillis} ms is negative" }
        }
        require(animation.loopCount <= 0xFFFFFFFFL) { "JPEG XL: play count ${animation.loopCount} does not fit 32 bits" }
        // Every frame shares one header, so alpha and colour are decided over all of them.
        val alpha = animation.frames.any { it.bitmap.hasTransparency() }
        val gray = animation.frames.all { fr -> fr.bitmap.argb.all { p -> ((p ushr 16) and 0xFF) == (p and 0xFF) && ((p ushr 8) and 0xFF) == (p and 0xFF) } }
        val pictures = animation.frames.map { JxlEncPicture.of(it.bitmap, alpha, gray) }
        val durations = LongArray(animation.frames.size) { animation.frames[it].delayMillis.toLong() }
        return encodePictures(pictures, durations, animation.loopCount, options, metadata, cancel)
    }

    /**
     * Encodes [pictures] as one still (no [durations]) or as an animation in ticks of one
     * millisecond. A frame that differs from the one before only inside a rectangle is coded
     * as that rectangle over the frame before.
     */
    private fun encodePictures(
        pictures: List<JxlEncPicture>,
        durations: LongArray?,
        loops: Long,
        options: JxlEncodeOptions,
        metadata: ImageMetadata,
        cancel: (() -> Unit)?,
    ): ByteArray {
        val first = pictures[0]
        require(first.w <= MAX_SIDE && first.h <= MAX_SIDE) { "JPEG XL: ${first.w}x${first.h} passes the encoder's side limit of $MAX_SIDE" }
        val lossless = options.lossless
        val image = JxlEncImage(
            width = first.w,
            height = first.h,
            bits = first.bits,
            gray = first.colors == 1 && lossless,
            alpha = first.alpha,
            xyb = !lossless,
            animation = if (durations != null) intArrayOf(1000, 1) else null,
            loops = loops,
            icc = metadata.icc,
        )
        val bw = JxlBitWriter(1 shl 16)
        JxlEncHeaders.writeImageHeader(bw, image)
        var previous: JxlEncPicture? = null
        for ((index, picture) in pictures.withIndex()) {
            cancel?.invoke()
            val isLast = index == pictures.size - 1
            val duration = durations?.get(index) ?: 0L
            // In an animation each frame is kept, so the next may cover only what changed.
            val save = if (durations != null && !isLast) 1 else 0
            var rect = intArrayOf(0, 0, picture.w, picture.h)
            if (previous != null) rect = changedRect(previous, picture) ?: intArrayOf(0, 0, 1, 1)
            val cropped = rect[0] != 0 || rect[1] != 0 || rect[2] != picture.w || rect[3] != picture.h
            val part = if (cropped) crop(picture, rect) else picture
            val frame = JxlEncFrame(
                modular = lossless,
                x0 = rect[0],
                y0 = rect[1],
                cropWidth = if (cropped) rect[2] else 0,
                cropHeight = if (cropped) rect[3] else 0,
                source = 1,
                duration = duration,
                isLast = isLast,
                saveAsReference = save,
            )
            if (lossless) writeModularFrame(bw, image, frame, part, options.effort, cancel)
            else JxlVarDctEncoder.writeFrame(bw, image, frame, part, options, cancel)
            previous = picture
        }
        val codestream = bw.toByteArray()
        val exif = metadata.exif
        val xmp = metadata.xmp?.encodeToByteArray()
        if (exif == null && xmp == null && !options.container) return codestream
        return JxlEncHeaders.container(codestream, exif, xmp)
    }

    /** The smallest rectangle (x, y, width, height) outside which [b] equals [a], or null when they are equal. */
    private fun changedRect(a: JxlEncPicture, b: JxlEncPicture): IntArray? {
        val w = a.w
        var x0 = w
        var y0 = a.h
        var x1 = -1
        var y1 = -1
        for (y in 0 until a.h) {
            for (x in 0 until w) {
                val i = y * w + x
                var same = true
                for (c in a.planes.indices) if (a.planes[c][i] != b.planes[c][i]) {
                    same = false
                    break
                }
                if (same) continue
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                y1 = y
            }
        }
        if (x1 < 0) return null
        return intArrayOf(x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }

    private fun crop(p: JxlEncPicture, r: IntArray): JxlEncPicture {
        val planes = Array(p.planes.size) { c ->
            val out = IntArray(r[2] * r[3])
            for (y in 0 until r[3]) p.planes[c].copyInto(out, y * r[2], (r[1] + y) * p.w + r[0], (r[1] + y) * p.w + r[0] + r[2])
            out
        }
        return JxlEncPicture(r[2], r[3], p.bits, p.colors, p.alpha, planes)
    }

    /** The dimensions a decoder computes for a frame of [w] by [h]. */
    fun frameDim(w: Int, h: Int, modular: Boolean, groupSizeShift: Int = 1) = JxlFrameDim(w, h, groupSizeShift, 0, 0, modular, 1)

    /** Writes one lossless Modular frame of [picture]: its header, table of contents and sections. */
    private fun writeModularFrame(bw: JxlBitWriter, image: JxlEncImage, frame: JxlEncFrame, picture: JxlEncPicture, effort: Int, cancel: (() -> Unit)?) {
        JxlEncHeaders.writeFrameHeader(bw, image, frame)
        val dim = frameDim(picture.w, picture.h, true)
        val sections = modularSections(dim, picture, image.gray, effort, cancel)
        JxlEncHeaders.writeSections(bw, sections)
    }

    private fun modularSections(dim: JxlFrameDim, p: JxlEncPicture, gray: Boolean, effort: Int, cancel: (() -> Unit)?): List<ByteArray> {
        // The frame keeps one colour channel for a gray image and three otherwise.
        val colorPlanes = if (gray) 1 else 3
        val src = ArrayList<IntArray>()
        for (c in 0 until colorPlanes) src.add(p.planes[if (p.colors == 1) 0 else c])
        if (p.alpha) src.add(p.planes[p.colors])
        val candidates = JxlModularTransforms.candidates(src, p.w, p.h, colorPlanes, p.bits, effort)
        var best: List<ByteArray>? = null
        for (cand in candidates) {
            cancel?.invoke()
            val sections = layoutModular(dim, cand, effort, cancel)
            if (best == null || sections.sumOf { it.size } < best.sumOf { it.size }) best = sections
        }
        return best!!
    }

    /** The sections of a Modular frame whose channels, after their transforms, are [t]. */
    private fun layoutModular(dim: JxlFrameDim, t: JxlModularTransforms.Result, effort: Int, cancel: (() -> Unit)?): List<ByteArray> {
        val asm = JxlFrameAssembler(dim, effort)
        val lfQuant = JxlBitWriter(1)
        lfQuant.bool(true)
        asm.global.add(lfQuant)
        asm.global.add(JxlFrameAssembler.TreeMarker)
        val channels = t.channels
        var codedEnd = channels.size
        for (i in channels.indices) {
            if (i >= t.metaChannels && (channels[i].w > dim.groupDim || channels[i].h > dim.groupDim)) {
                codedEnd = i
                break
            }
        }
        asm.global.add(JxlModStream(0, channels, t.transforms, codedEnd))
        addGroupStreams(asm, dim, channels, codedEnd)
        return asm.finish(cancel)
    }

    /** Adds to each group's section the parts of [channels] from [begin] on that lie in the group. */
    fun addGroupStreams(asm: JxlFrameAssembler, dim: JxlFrameDim, channels: List<JxlChannel>, begin: Int) {
        if (begin >= channels.size) return
        val size = dim.groupDim
        for (g in 0 until dim.numGroups) {
            val gx = (g % dim.xsizeGroups) * size
            val gy = (g / dim.xsizeGroups) * size
            val part = ArrayList<JxlChannel>()
            for (c in begin until channels.size) {
                val fc = channels[c]
                val rx = gx shr fc.hshift
                val ry = gy shr fc.vshift
                val rw = minOf(size shr fc.hshift, fc.w - rx)
                val rh = minOf(size shr fc.vshift, fc.h - ry)
                if (rw <= 0 || rh <= 0) continue
                val ch = JxlChannel(rw, rh, fc.hshift, fc.vshift)
                val from = fc.data
                val to = ch.data
                for (y in 0 until rh) from.copyInto(to, y * rw, (ry + y) * fc.w + rx, (ry + y) * fc.w + rx + rw)
                part.add(ch)
            }
            if (part.isNotEmpty()) asm.group(g).add(JxlModStream(asm.passGroupStream(g), part))
        }
    }
}
