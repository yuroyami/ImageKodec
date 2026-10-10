package io.github.yuroyami.imagekodec.codec.jxl

import io.github.yuroyami.imagekodec.ColorProfile
import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.ImageFormat
import io.github.yuroyami.imagekodec.ImageInfo
import io.github.yuroyami.imagekodec.KiteAnimation
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.KiteBitmap16
import io.github.yuroyami.imagekodec.KiteFrame
import io.github.yuroyami.imagekodec.Orientation
import io.github.yuroyami.imagekodec.internal.Budget

/**
 * JPEG XL (ISO/IEC 18181): reads the container, the image header and the frames of a file,
 * and gives its pixels, its animation or its description.
 */
internal object JxlDecoder {

    /** The headers of a file and where its first frame starts. */
    internal class Stream(
        val codestream: JxlContainer.Codestream,
        val header: JxlImageHeader,
        val icc: ByteArray?,
        val firstFrame: Int,
    )

    /** Reads everything before the first frame. A profile cut short by the end of a peeked file is left out when [lenient]. */
    internal fun open(data: ByteArray, lenient: Boolean = false): Stream {
        val cs = JxlContainer.codestream(data)
        if (cs.end - cs.start < 2 || cs.data[cs.start] != 0xFF.toByte() || cs.data[cs.start + 1] != 0x0A.toByte()) {
            jxlFail("codestream does not start with its signature")
        }
        val br = JxlBitReader(cs.data, cs.start + 2, cs.end)
        val header = JxlImageHeader.read(br)
        var icc: ByteArray? = null
        var first = cs.end
        try {
            if (header.color.wantIcc) icc = JxlIcc.read(br)
            br.zeroPadToByte()
            first = br.bytePosition
        } catch (e: ImageDecodeException) {
            // The first bytes of a file may stop inside its profile. Any other fault is the file's own.
            if (!lenient || !br.ranOut) throw e
        }
        return Stream(cs, header, icc, first)
    }

    /** Colour and alpha at the pixel ceiling. An image of more channels has to be smaller. */
    private const val MAX_SAMPLES = 4 * Budget.MAX_PIXELS

    /** Why an image or a frame of [width] by [height] is refused, or null: its pixels first, then the samples of all its channels. */
    internal fun refusal(h: JxlImageHeader, width: Int, height: Int): String? = when {
        !Budget.fitsAbsolute(width, height) -> "$width by $height passes the pixel ceiling"
        (3L + h.extraChannels.size) * width * height > MAX_SAMPLES ->
            "$width by $height with ${h.extraChannels.size} extra channels passes the sample ceiling"
        else -> null
    }

    fun probe(data: ByteArray): ImageInfo {
        val s = open(data, lenient = true)
        val h = s.header
        val frames = if (h.animation != null) shownFrames(s) else 1
        val reason = refusal(h, h.width, h.height)?.let { "JPEG XL image of $it" }
        return ImageInfo(
            format = ImageFormat.JXL,
            width = h.width,
            height = h.height,
            bitDepth = h.bitDepth.bits,
            hasAlpha = h.alphaChannel >= 0,
            frameCount = frames,
            loopCount = if (h.animation != null) h.animation.numLoops else 1,
            orientation = Orientation.fromExif(h.orientation),
            isDecodable = reason == null,
            unsupportedReason = reason,
            colorProfile = colorProfile(h, s.icc),
        ).also { it.colorChannels = if (h.color.isGray) 1 else 3 }
    }

    /** The first frame a viewer shows, as 8-bit ARGB. */
    fun decode(data: ByteArray): KiteBitmap {
        val s = open(data)
        var out: KiteBitmap? = null
        frames(s) { color, extra, _ ->
            out = toBitmap(s.header, color, extra)
            false
        }
        return out ?: jxlFail("no frame to show")
    }

    /** The first frame a viewer shows, with every bit the file stores, up to 16 a sample. */
    fun decode16(data: ByteArray): KiteBitmap16 {
        val s = open(data)
        var out: KiteBitmap16? = null
        frames(s) { color, extra, _ ->
            out = toBitmap16(s.header, color, extra)
            false
        }
        return out ?: jxlFail("no frame to show")
    }

    /** Every frame a viewer shows, the first [maxFrames] of them, with how long each stays. */
    fun decodeAnimation(data: ByteArray, maxFrames: Int, cancellationCheck: (() -> Unit)?): KiteAnimation {
        val s = open(data)
        val h = s.header
        val animation = h.animation
        val count = if (animation == null) 1 else minOf(maxFrames, shownFrames(s))
        if (!Budget.framesFitAbsolute(h.width, h.height, count)) {
            jxlFail("$count frames of ${h.width} by ${h.height} pass the pixel ceiling")
        }
        val out = ArrayList<KiteFrame>(count)
        frames(s) { color, extra, fh ->
            var millis = 0
            var centiseconds = 0
            if (animation != null) {
                // A frame lasts its ticks divided by the ticks in a second.
                val exact = (fh.duration * 1000 * animation.tpsDenominator + animation.tpsNumerator / 2) / animation.tpsNumerator
                val stored = minOf(exact, Int.MAX_VALUE.toLong() - 5).toInt()
                // A frame of 10 ms or less means "as fast as possible"; browsers show it for 100 ms.
                millis = if (stored <= 10) 100 else stored
                centiseconds = (stored + 5) / 10
            }
            out.add(KiteFrame(toBitmap(h, color, extra), millis, centiseconds))
            if (out.size < count) cancellationCheck?.invoke()
            // The count is of the frames whose headers are there. A file cut before its last frame fails on the next one.
            out.size < maxFrames
        }
        if (out.isEmpty()) jxlFail("no frame to show")
        return KiteAnimation(h.width, h.height, out, if (animation != null) animation.numLoops else 1)
    }

    /** The header of every frame of [data] in file order, the preview left out, read without the samples. The tests ask it which coding tools a file uses. */
    internal fun frameHeaders(data: ByteArray): List<JxlFrameHeader> {
        val s = open(data)
        val out = ArrayList<JxlFrameHeader>()
        var at = s.firstFrame
        var preview = s.header.havePreview
        while (at < s.codestream.end) {
            val br = JxlBitReader(s.codestream.data, at, s.codestream.end)
            val fh = JxlFrameHeader.read(br, s.header, preview)
            at = JxlToc.read(br, fh.numTocEntries).end
            if (preview) {
                preview = false
                continue
            }
            out.add(fh)
            if (fh.isLast) break
        }
        return out
    }

    /**
     * How many frames of [s] a viewer shows, from the frame headers alone. A file that is cut
     * short, or damaged after a frame, has the frames before that point, and one at least.
     */
    private fun shownFrames(s: Stream): Int {
        val h = s.header
        var frames = 0
        try {
            var at = s.firstFrame
            var preview = h.havePreview
            while (at < s.codestream.end) {
                val br = JxlBitReader(s.codestream.data, at, s.codestream.end)
                val fh = JxlFrameHeader.read(br, h, preview)
                val toc = JxlToc.read(br, fh.numTocEntries)
                at = toc.end
                if (preview) {
                    preview = false
                    continue
                }
                val shown = fh.type == JxlFrameHeader.REGULAR_FRAME || fh.type == JxlFrameHeader.SKIP_PROGRESSIVE
                if (shown && (fh.duration != 0L || fh.isLast)) frames++
                if (fh.isLast) break
            }
        } catch (_: ImageDecodeException) {
        }
        return maxOf(frames, 1)
    }

    /**
     * Decodes the frames of [s] in order and gives [onFrame] each image a viewer shows:
     * the colour planes in the image's colour encoding, the extra planes and the header of
     * the frame that completed it. It stops when [onFrame] returns false.
     */
    private fun frames(s: Stream, onFrame: (Array<JxlPlane>, Array<JxlPlane>, JxlFrameHeader) -> Boolean) {
        val h = s.header
        // A frame may be smaller than the image, and it is blended into a canvas of the image's size.
        refusal(h, h.width, h.height)?.let { jxlFail("image of $it") }
        val cs = s.codestream
        val store = JxlFrameStore()
        val decoder = JxlFrameDecoder(h, store, cs.data, cs.end)
        var at = s.firstFrame
        // The preview is a frame of its own before the image. Its table of contents says where it ends.
        if (h.havePreview) at = decoder.skip(at, preview = true)
        val hasSpot = h.extraChannels.any { it.type == JxlExtraChannel.SPOT_COLOR }
        while (true) {
            if (at >= cs.end) jxlFail("data ends before the last frame")
            val frame = decoder.decode(at)
            at = frame.end
            val fh = frame.header
            if (fh.dcLevel != 0) {
                store.lfFrames[fh.dcLevel - 1] = frame.color
            } else {
                if (fh.canBeReferenced && fh.saveBeforeColorTransform) {
                    store.references[fh.saveAsReference] = JxlReference(frame.color, frame.extra, true)
                }
                if (fh.needsColorTransform) {
                    var color = frame.color
                    var extra = frame.extra
                    // XYB first becomes linear light. The transfer function follows once nothing more needs linear light.
                    var linear = false
                    when (fh.colorTransform) {
                        JxlFrameHeader.YCBCR -> color = JxlColor.ycbcrToRgb(color)
                        JxlFrameHeader.XYB -> {
                            color = JxlColor.xybToLinear(color, h)
                            linear = true
                        }
                    }
                    val blend = JxlBlend.needsBlending(fh)
                    val saved = fh.canBeReferenced && !fh.saveBeforeColorTransform
                    if (linear && (blend || saved)) {
                        color = JxlColor.linearToEncoded(color, h)
                        linear = false
                    }
                    if (blend) {
                        val blended = JxlBlend.blend(JxlFrame(fh, color, extra, at), h, store)
                        color = blended.first
                        extra = blended.second
                    }
                    if (saved) store.references[fh.saveAsReference] = JxlReference(color, extra, false)
                    val regular = fh.type == JxlFrameHeader.REGULAR_FRAME || fh.type == JxlFrameHeader.SKIP_PROGRESSIVE
                    if (regular && (fh.isLast || fh.duration != 0L)) {
                        if (hasSpot) color = JxlColor.renderSpotColors(color, extra, h)
                        if (linear) color = JxlColor.linearToEncoded(color, h)
                        if (!onFrame(color, extra, fh)) return
                    }
                }
            }
            if (fh.isLast) return
        }
    }

    /** A sample of 0 to 1 as a whole number of 0 to [max], rounded the way libjxl rounds its output. */
    private fun quantize(v: Float, max: Double): Int {
        val s = (v.toDouble() * max).toFloat().toDouble()
        if (!(s > 0.0)) return 0
        if (s >= max) return max.toInt()
        return kotlin.math.round(s).toInt()
    }

    /**
     * A sample as 16 bits. A channel of 8 bits or fewer gives its 8-bit value twice, and one
     * of 9 to 15 bits gives its own value with its high bits repeated below, so no channel
     * claims bits the file does not store.
     */
    private fun sample16(v: Float, depth: JxlBitDepth): Int {
        if (depth.floatingPoint || depth.bits >= 16) return quantize(v, 65535.0)
        if (depth.bits <= 8) return quantize(v, 255.0) * 257
        val s = quantize(v, ((1 shl depth.bits) - 1).toDouble())
        return (s shl (16 - depth.bits)) or (s ushr (2 * depth.bits - 16))
    }

    /** The image with the channels it stores: gray or RGB, then alpha when the image has it. */
    private fun toBitmap16(h: JxlImageHeader, color: Array<JxlPlane>, extra: Array<JxlPlane>): KiteBitmap16 {
        val w = h.width
        val n = w * h.height
        if (color[0].w != w || color[0].data.size != n) jxlFail("frame of ${color[0].w} by ${color[0].h} is not the image")
        val alphaIndex = h.alphaChannel
        val a = if (alphaIndex >= 0) extra[alphaIndex].data else null
        val alphaDepth = if (alphaIndex >= 0) h.extraChannels[alphaIndex].bitDepth else h.bitDepth
        val premultiplied = alphaIndex >= 0 && h.extraChannels[alphaIndex].alphaAssociated
        val colors = if (h.color.isGray) 1 else 3
        val channels = colors + (if (a != null) 1 else 0)
        val out = ShortArray(n * channels)
        var at = 0
        for (i in 0 until n) {
            // KiteBitmap16 holds straight alpha.
            val m = if (a != null && premultiplied) 1.0 / maxOf(SMALL_ALPHA, a[i].toDouble()) else 1.0
            for (c in 0 until colors) {
                val v = color[c].data[i]
                out[at++] = sample16(if (m == 1.0) v else (v * m).toFloat(), h.bitDepth).toShort()
            }
            if (a != null) out[at++] = sample16(a[i], alphaDepth).toShort()
        }
        return KiteBitmap16(w, h.height, channels, out)
    }

    /** The image at 8 bits a channel: the high byte of each sample of [toBitmap16]. */
    private fun toBitmap(h: JxlImageHeader, color: Array<JxlPlane>, extra: Array<JxlPlane>): KiteBitmap {
        val w = h.width
        val n = w * h.height
        if (color[0].w != w || color[0].data.size != n) jxlFail("frame of ${color[0].w} by ${color[0].h} is not the image")
        val gray = h.color.isGray
        val r = color[0].data
        val g = color[if (gray) 0 else 1].data
        val b = color[if (gray) 0 else 2].data
        val alphaIndex = h.alphaChannel
        val a = if (alphaIndex >= 0) extra[alphaIndex].data else null
        val alphaDepth = if (alphaIndex >= 0) h.extraChannels[alphaIndex].bitDepth else h.bitDepth
        val premultiplied = alphaIndex >= 0 && h.extraChannels[alphaIndex].alphaAssociated
        val depth = h.bitDepth
        val out = IntArray(n)
        for (i in 0 until n) {
            var rv = r[i]
            var gv = g[i]
            var bv = b[i]
            var av = 255
            if (a != null) {
                av = sample16(a[i], alphaDepth) ushr 8
                if (premultiplied) {
                    // KiteBitmap holds straight alpha.
                    val m = 1.0 / maxOf(SMALL_ALPHA, a[i].toDouble())
                    rv = (rv * m).toFloat()
                    gv = (gv * m).toFloat()
                    bv = (bv * m).toFloat()
                }
            }
            out[i] = (av shl 24) or ((sample16(rv, depth) ushr 8) shl 16) or ((sample16(gv, depth) ushr 8) shl 8) or (sample16(bv, depth) ushr 8)
        }
        return KiteBitmap(w, h.height, out)
    }

    /** The alpha below which libjxl does not divide by it. */
    private const val SMALL_ALPHA = 1.0 / (1 shl 26)

    /** What the decoded samples are in: the embedded profile, or the code points of the header's colour encoding. */
    internal fun colorProfile(h: JxlImageHeader, icc: ByteArray?): ColorProfile {
        // An XYB image is converted to the encoding its header names, or to sRGB when it has only a profile,
        // because nothing here applies a profile. Any other image keeps the samples it stores.
        val c = if (h.xybEncoded) JxlColor.outputEncoding(h) else h.color
        if (c.wantIcc) return if (icc == null) ColorProfile(cicp = intArrayOf(1, 13, 0, 1)) else ColorProfile(icc = icc)
        val primaries = when {
            c.isGray -> if (c.whitePoint == JxlColorEncoding.WHITE_D65) 1 else 2
            c.primaries == JxlColorEncoding.PRIMARIES_SRGB && c.whitePoint == JxlColorEncoding.WHITE_D65 -> 1
            c.primaries == JxlColorEncoding.PRIMARIES_2100 && c.whitePoint == JxlColorEncoding.WHITE_D65 -> 9
            c.primaries == JxlColorEncoding.PRIMARIES_P3 && c.whitePoint == JxlColorEncoding.WHITE_D65 -> 12
            c.primaries == JxlColorEncoding.PRIMARIES_P3 && c.whitePoint == JxlColorEncoding.WHITE_DCI -> 11
            else -> 2
        }
        // The transfer functions' numbers are those of ITU-T H.273; a pure gamma has a code point for 2.2 and 2.8 only.
        val transfer = when {
            !c.haveGamma -> c.transferFunction
            (c.gamma + 50) / 100 == 45455 -> 4
            (c.gamma + 50) / 100 == 35714 -> 5
            else -> 2
        }
        return ColorProfile(
            cicp = intArrayOf(primaries, transfer, 0, 1),
            gamma = if (c.haveGamma) (c.gamma + 50) / 100 else null,
        )
    }
}
