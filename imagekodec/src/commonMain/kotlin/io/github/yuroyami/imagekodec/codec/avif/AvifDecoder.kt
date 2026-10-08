package io.github.yuroyami.imagekodec.codec.avif

import io.github.yuroyami.imagekodec.ColorProfile
import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.ImageFormat
import io.github.yuroyami.imagekodec.ImageInfo
import io.github.yuroyami.imagekodec.KiteAnimation
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.KiteBitmap16
import io.github.yuroyami.imagekodec.KiteFrame
import io.github.yuroyami.imagekodec.Orientation
import io.github.yuroyami.imagekodec.UnsupportedImageException
import io.github.yuroyami.imagekodec.internal.Budget
import io.github.yuroyami.imagekodec.codec.avif.AvifContainer.Companion.ALPHA_URN
import io.github.yuroyami.imagekodec.codec.avif.AvifContainer.Companion.ALPHA_URN_HEVC
import io.github.yuroyami.imagekodec.codec.avif.AvifContainer.Companion.ALTR
import io.github.yuroyami.imagekodec.codec.avif.AvifContainer.Companion.AUXL
import io.github.yuroyami.imagekodec.codec.avif.AvifContainer.Companion.AV01
import io.github.yuroyami.imagekodec.codec.avif.AvifContainer.Companion.DIMG
import io.github.yuroyami.imagekodec.codec.avif.AvifContainer.Companion.GRID
import io.github.yuroyami.imagekodec.codec.avif.AvifContainer.Companion.IOVL
import io.github.yuroyami.imagekodec.codec.avif.AvifContainer.Companion.PICT
import io.github.yuroyami.imagekodec.codec.avif.AvifContainer.Companion.PREM
import io.github.yuroyami.imagekodec.codec.avif.AvifContainer.Companion.SATO
import io.github.yuroyami.imagekodec.codec.avif.AvifContainer.Companion.TMAP

/**
 * AVIF (AV1 Image File Format 1.1): picks what to show, decodes its AV1 data, builds a derived
 * image from its inputs, converts it to RGB and joins its alpha.
 *
 * What to show follows libavif: the first frame of an AV1 image sequence when the file has
 * one, else the primary item, or the first item of the primary item's `altr` group this reader
 * can show. A `grid` joins its tiles before the conversion to RGB, as libavif and libheif do; a
 * sample transform (`sato`) computes its samples from its inputs'; a tone-mapped image (`tmap`)
 * shows its base image, as a reader that does not tone map does; and an overlay (`iovl`) draws
 * its inputs in RGB on its canvas.
 *
 * The clean aperture (`clap`) crops the image. Rotation (`irot`) and mirroring (`imir`) are to an
 * AVIF what the EXIF orientation is to a JPEG, so they are reported as [ImageInfo.orientation]
 * and applied when a caller asks. All three apply in the order MIAF fixes: crop, rotate, mirror.
 */
internal object AvifDecoder {

    /** What [decode] shows, and what [probe] says of it. */
    private class Plan(
        val c: AvifContainer,
        val item: AvifItem?,
        val track: AvifTrack?,
        val alphaItem: AvifItem?,
        val alphaTrack: AvifTrack?,
        val premultiplied: Boolean,
        val width: Int,
        val height: Int,
        val bitDepth: Int,
        val crop: IntArray?,
        val orientation: Orientation,
        val frameCount: Int,
        val loopCount: Long,
        val icc: ByteArray?,
        val cicp: AvifCicp?,
        val unsupported: String?,
    )

    fun probe(data: ByteArray): ImageInfo {
        val p = plan(data, probing = true)
        return ImageInfo(
            format = ImageFormat.AVIF,
            width = p.crop?.get(2) ?: p.width,
            height = p.crop?.get(3) ?: p.height,
            bitDepth = p.bitDepth,
            hasAlpha = p.alphaItem != null || p.alphaTrack != null,
            frameCount = p.frameCount,
            loopCount = p.loopCount,
            orientation = p.orientation,
            isDecodable = p.unsupported == null,
            unsupportedReason = p.unsupported,
            colorProfile = if (p.icc != null || p.cicp != null) ColorProfile(icc = p.icc, cicp = p.cicp?.toIntArray()) else null,
        )
    }

    fun decode(data: ByteArray): KiteBitmap = render(plan(data, probing = false), wide = false).bitmap

    fun decode16(data: ByteArray): KiteBitmap16 = render(plan(data, probing = false), wide = true).wide!!

    /**
     * Every frame of an AV1 image sequence with its duration, or a still image as one frame. The
     * frames decode in order from the first sample, as each predicts from the ones before it, and
     * the alpha track decodes beside them sample by sample. A frame coded at another size than
     * the first, which AV1 allows mid-sequence, is scaled to the first frame's size, as a player
     * shows every frame in one window.
     */
    fun decodeAnimation(data: ByteArray, maxFrames: Int, cancellationCheck: (() -> Unit)?): KiteAnimation {
        val p = plan(data, probing = false)
        val track = p.track
        if (track == null) {
            val still = render(p, wide = false).bitmap
            return KiteAnimation(still.width, still.height, listOf(KiteFrame(still, delayMillis = 0, delayRawCentiseconds = 0)), loopCount = 1)
        }
        p.unsupported?.let { throw UnsupportedImageException(it) }
        val c = p.c
        val count = minOf(track.sizes.size, maxFrames)
        val width = p.crop?.get(2) ?: p.width
        val height = p.crop?.get(3) ?: p.height
        if (!Budget.framesFitAbsolute(width, height, count)) {
            throw ImageDecodeException("AVIF: $count frames of ${width}x$height exceed the output safety limit")
        }
        val color = sequenceDecoder(track)
        val alphaTrack = p.alphaTrack
        val alpha = alphaTrack?.let { sequenceDecoder(it) }
        val frames = ArrayList<KiteFrame>(count)
        for (i in 0 until count) {
            val colorPlanes = sample(color, c, track, i)
            val alphaPlanes = if (alphaTrack != null && alpha != null) {
                if (i >= alphaTrack.sizes.size) throw ImageDecodeException("AVIF: the alpha track has ${alphaTrack.sizes.size} samples for ${track.sizes.size} frames")
                sample(alpha, c, alphaTrack, i)
            } else null
            var bitmap = convert(colorPlanes, alphaPlanes, p.premultiplied, p.cicp ?: AvifCicp(2, 2, 2, colorPlanes.fullRange), wide = false).bitmap
            if (bitmap.width != p.width || bitmap.height != p.height) bitmap = resampled(bitmap, p.width, p.height)
            p.crop?.let { bitmap = cropped(bitmap, it) }
            val timescale = if (track.timescale > 0) track.timescale else 1L
            val duration = if (i < track.durations.size) track.durations[i] else 0L
            val millis = ((duration * 1000 + timescale / 2) / timescale).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            frames += KiteFrame(
                bitmap,
                // A duration of 0 means as fast as possible; clamp it as browsers do.
                delayMillis = if (millis <= 10) 100 else millis,
                delayRawCentiseconds = ((duration * 100 + timescale / 2) / timescale).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
            if (i + 1 < count) cancellationCheck?.invoke()
        }
        return KiteAnimation(width, height, frames, p.loopCount)
    }

    /** An AV1 decoder for the samples of [track], configured by its `av1C`. */
    private fun sequenceDecoder(track: AvifTrack): Av1Decoder {
        val config = track.properties.firstOrNull { it is AvifProperty.Av1Config } as AvifProperty.Av1Config?
        return Av1Decoder().also { d -> config?.configObus?.takeIf { it.isNotEmpty() }?.let { d.configure(it) } }
    }

    /** The planes of the frame sample [index] of [track] shows. */
    private fun sample(decoder: Av1Decoder, c: AvifContainer, track: AvifTrack, index: Int): AvifPlanes {
        val pic = decoder.decodeTemporalUnit(c.sampleData(track, index))
            ?: throw ImageDecodeException("AVIF: sample $index of track ${track.id} shows no frame")
        return AvifPlanes(pic.width, pic.height, pic.bitDepth, pic.subX, pic.subY, pic.planes, pic.strides, pic.seq.colorRange)
    }

    /** [b] resampled to [w] by [h], bilinearly, with the sample centres aligned. */
    private fun resampled(b: KiteBitmap, w: Int, h: Int): KiteBitmap {
        val out = IntArray(w * h)
        val src = b.argb
        for (y in 0 until h) {
            val fy = ((y + 0.5) * b.height / h - 0.5).coerceIn(0.0, (b.height - 1).toDouble())
            val y0 = fy.toInt()
            val y1 = minOf(y0 + 1, b.height - 1)
            val wy = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5) * b.width / w - 0.5).coerceIn(0.0, (b.width - 1).toDouble())
                val x0 = fx.toInt()
                val x1 = minOf(x0 + 1, b.width - 1)
                val wx = fx - x0
                var argb = 0
                for (shift in intArrayOf(24, 16, 8, 0)) {
                    fun ch(px: Int) = (src[px] ushr shift) and 255
                    val top = ch(y0 * b.width + x0) * (1 - wx) + ch(y0 * b.width + x1) * wx
                    val bottom = ch(y1 * b.width + x0) * (1 - wx) + ch(y1 * b.width + x1) * wx
                    argb = argb or ((top * (1 - wy) + bottom * wy + 0.5).toInt().coerceIn(0, 255) shl shift)
                }
                out[y * w + x] = argb
            }
        }
        return KiteBitmap(w, h, out)
    }

    // ---- choosing what to show ------------------------------------------------------

    /**
     * The plan for [data]. With [probing], an item whose data lies past the end of [data], as in
     * the first bytes of a file a loader peeks at, is described from its properties alone.
     */
    private fun plan(data: ByteArray, probing: Boolean): Plan {
        val c = AvifContainer.parse(data)
        val track = c.tracks.firstOrNull { it.sampleEntry == AV01 && it.handler == PICT && it.sizes.isNotEmpty() }
        if (track != null) return trackPlan(c, track, probing)
        val primaryId = c.primary ?: throw ImageDecodeException("AVIF: no primary item and no image sequence")
        val primary = c.items[primaryId] ?: throw ImageDecodeException("AVIF: the primary item $primaryId is not described in 'iinf'")
        // An 'altr' group lists alternatives in order of preference: show the first this reader can.
        val group = c.groups.firstOrNull { it.type == ALTR && primaryId in it.entities }
        val candidates = (group?.entities?.toList()?.mapNotNull { c.items[it] } ?: emptyList()).ifEmpty { listOf(primary) }
        val chosen = candidates.firstOrNull { unsupported(c, it, 0) == null } ?: primary
        return itemPlan(c, base(c, chosen, 0))
    }

    /** The item whose samples [item] shows: a tone-mapped image shows its base image. */
    private fun base(c: AvifContainer, item: AvifItem, depth: Int): AvifItem {
        if (item.type != TMAP) return item
        if (depth > MAX_DEPTH) throw UnsupportedImageException("AVIF derived images nested more than $MAX_DEPTH deep")
        val base = c.referencesFrom(item.id, DIMG).firstOrNull()?.let { c.items[it] }
            ?: throw ImageDecodeException("AVIF: tone-mapped item ${item.id} has no base image")
        return base(c, base, depth + 1)
    }

    private const val MAX_DEPTH = 8

    private val KNOWN_TYPES = setOf(AV01, GRID, IOVL, SATO, TMAP)

    private fun inputs(c: AvifContainer, item: AvifItem): List<AvifItem> =
        c.referencesFrom(item.id, DIMG).map { id ->
            c.items[id] ?: throw ImageDecodeException("AVIF: derived item ${item.id} refers to a missing item $id")
        }

    /** Why [item] cannot be shown, from its description in the container. */
    private fun unsupported(c: AvifContainer, item: AvifItem, depth: Int): String? {
        if (depth > MAX_DEPTH) return "AVIF derived images nested more than $MAX_DEPTH deep"
        if (item.type !in KNOWN_TYPES) return "AVIF item of type '${fourccName(item.type)}'"
        item.unknownEssential.firstOrNull()?.let { return "AVIF item property '${fourccName(it)}' marked essential" }
        return when (item.type) {
            AV01 -> if (item.property<AvifProperty.Av1Config>() == null) "AVIF AV1 item ${item.id} without 'av1C'" else null
            GRID, IOVL, SATO, TMAP -> {
                val ids = c.referencesFrom(item.id, DIMG)
                if (ids.isEmpty()) return "AVIF derived item ${item.id} without inputs"
                if (item.type == TMAP) {
                    return c.items[ids[0]]?.let { unsupported(c, it, depth + 1) } ?: "AVIF tone-mapped item without its base image"
                }
                if (item.type == SATO) c.itemDataOrNull(item)?.let { d -> AvifSampleTransform.unsupported(d, ids.size)?.let { return it } }
                for (id in ids) {
                    val input = c.items[id] ?: return "AVIF derived item ${item.id} refers to a missing item $id"
                    unsupported(c, input, depth + 1)?.let { return it }
                }
                null
            }
            else -> "AVIF item of type '${fourccName(item.type)}'"
        }
    }

    private fun alphaOf(c: AvifContainer, item: AvifItem): AvifItem? =
        c.referencesTo(item.id, AUXL).mapNotNull { c.items[it] }.firstOrNull {
            val urn = it.property<AvifProperty.AuxType>()?.urn
            urn == ALPHA_URN || urn == ALPHA_URN_HEVC
        }

    private fun itemPlan(c: AvifContainer, item: AvifItem): Plan {
        var reason = unsupported(c, item, 0)
        val alpha = alphaOf(c, item)
        if (reason == null && alpha != null) reason = unsupported(c, alpha, 0)
        val size = itemSize(c, item, 0)
        val cicp = itemCicp(c, item, 0)
        if (reason == null) reason = cicp?.let { AvifColor.unsupported(it.matrix) }
        if (reason == null) reason = frames(c, item, 0)
        if (reason == null && alpha != null) reason = frames(c, alpha, 0)
        val premultiplied = alpha != null && alpha.id in c.referencesFrom(item.id, PREM)
        val crop = item.property<AvifProperty.CleanAperture>()?.let { cleanAperture(it, size.first, size.second) }
        return Plan(
            c, item, null, alpha, null, premultiplied, size.first, size.second, itemDepth(c, item, 0), crop,
            orientation(item.property(), item.property()),
            frameCount = 1, loopCount = 1, icc = itemIcc(c, item, 0), cicp = cicp, unsupported = reason,
        )
    }

    private fun trackPlan(c: AvifContainer, track: AvifTrack, probing: Boolean): Plan {
        val config = track.properties.firstOrNull { it is AvifProperty.Av1Config } as AvifProperty.Av1Config?
        val alpha = c.tracks.firstOrNull { t ->
            t !== track && t.sampleEntry == AV01 && (t.auxType == ALPHA_URN || t.auxType == ALPHA_URN_HEVC) &&
                t.references.any { it.type == AUXL && track.id in it.to }
        }
        val first = c.sampleDataOrNull(track, 0)
        if (first == null && !probing) throw ImageDecodeException("AVIF: the first frame of track ${track.id} extends past the end of the file")
        val headers = first?.let { Av1Decoder.inspect(config?.configObus, it) }
        val nclx = track.properties.firstOrNull { it is AvifProperty.Nclx } as AvifProperty.Nclx?
        val cicp = nclx?.let { AvifCicp(it.primaries, it.transfer, it.matrix, it.fullRange) }
            ?: headers?.seq?.let { AvifCicp(it.colorPrimaries, it.transferCharacteristics, it.matrixCoefficients, it.colorRange) }
        // A sequence's first frame is a key frame: what follows it is what decodeAnimation reads.
        val reason = headers?.unsupported ?: cicp?.let { AvifColor.unsupported(it.matrix) }
        val premultiplied = alpha != null &&
            (track.references.any { it.type == PREM && alpha.id in it.to } || alpha.references.any { it.type == PREM && track.id in it.to })
        val width = headers?.width ?: track.width
        val height = headers?.height ?: track.height
        if (width <= 0 || height <= 0) throw ImageDecodeException("AVIF: track ${track.id} has no size")
        val crop = (track.properties.firstOrNull { it is AvifProperty.CleanAperture } as AvifProperty.CleanAperture?)
            ?.let { cleanAperture(it, width, height) }
        return Plan(
            c, null, track, null, alpha, premultiplied, width, height,
            headers?.seq?.bitDepth ?: config?.bitDepth ?: 8, crop,
            orientation(
                track.properties.firstOrNull { it is AvifProperty.Rotation } as AvifProperty.Rotation?,
                track.properties.firstOrNull { it is AvifProperty.Mirror } as AvifProperty.Mirror?,
            ),
            frameCount = track.sizes.size, loopCount = track.playCount,
            icc = (track.properties.firstOrNull { it is AvifProperty.Icc } as AvifProperty.Icc?)?.profile,
            cicp = cicp, unsupported = reason,
        )
    }

    /** Why the AV1 frames of [item], or of an item it is derived from, cannot be decoded. */
    private fun frames(c: AvifContainer, item: AvifItem, depth: Int): String? {
        if (depth > MAX_DEPTH) return null
        if (item.type == AV01) {
            // Data a probe cannot see yet is checked when it decodes.
            val data = c.itemDataOrNull(item) ?: return null
            return Av1Decoder.inspect(item.property<AvifProperty.Av1Config>()?.configObus, data).unsupported
        }
        val ids = c.referencesFrom(item.id, DIMG)
        val used = if (item.type == TMAP) ids.take(1) else ids
        return used.firstNotNullOfOrNull { id -> c.items[id]?.let { frames(c, it, depth + 1) } }
    }

    private fun itemSize(c: AvifContainer, item: AvifItem, depth: Int): Pair<Int, Int> {
        if (depth > MAX_DEPTH) throw UnsupportedImageException("AVIF derived images nested more than $MAX_DEPTH deep")
        when (item.type) {
            GRID -> c.itemDataOrNull(item)?.let { return AvifGrid.read(it).let { g -> g.width to g.height } }
            IOVL -> c.itemDataOrNull(item)?.let { return AvifOverlay.read(it, 0).let { o -> o.width to o.height } }
            TMAP -> return itemSize(c, base(c, item, depth), depth + 1)
        }
        val ispe = item.property<AvifProperty.Extent>()
        if (ispe != null) {
            if (ispe.width !in 1..MAX_SIDE || ispe.height !in 1..MAX_SIDE) {
                throw ImageDecodeException("AVIF: item ${item.id} is ${ispe.width} by ${ispe.height}")
            }
            return ispe.width.toInt() to ispe.height.toInt()
        }
        if (item.type == AV01) {
            val data = c.itemDataOrNull(item) ?: throw ImageDecodeException("AVIF: item ${item.id} has no 'ispe' and its data is cut off")
            val h = Av1Decoder.inspect(item.property<AvifProperty.Av1Config>()?.configObus, data)
            return h.width to h.height
        }
        val first = c.referencesFrom(item.id, DIMG).firstOrNull()?.let { c.items[it] }
            ?: throw ImageDecodeException("AVIF: item ${item.id} has no size")
        return itemSize(c, first, depth + 1)
    }

    private const val MAX_SIDE = 65536L

    private fun itemDepth(c: AvifContainer, item: AvifItem, depth: Int): Int {
        if (depth > MAX_DEPTH) return 8
        if (item.type == SATO) item.property<AvifProperty.PixelInfo>()?.depths?.firstOrNull()?.let { return it }
        item.property<AvifProperty.Av1Config>()?.let { return it.bitDepth }
        val next = if (item.type == TMAP) base(c, item, depth) else c.referencesFrom(item.id, DIMG).firstOrNull()?.let { c.items[it] }
        return next?.let { itemDepth(c, it, depth + 1) } ?: 8
    }

    /** The colour code points of [item]: its own `nclx`, else its first input's, else its AV1 sequence header's. */
    private fun itemCicp(c: AvifContainer, item: AvifItem, depth: Int): AvifCicp? {
        if (depth > MAX_DEPTH) return null
        item.property<AvifProperty.Nclx>()?.let { return AvifCicp(it.primaries, it.transfer, it.matrix, it.fullRange) }
        if (item.type == AV01) {
            val data = c.itemDataOrNull(item) ?: return null
            val seq = Av1Decoder.inspect(item.property<AvifProperty.Av1Config>()?.configObus, data).seq
            return AvifCicp(seq.colorPrimaries, seq.transferCharacteristics, seq.matrixCoefficients, seq.colorRange)
        }
        val first = c.referencesFrom(item.id, DIMG).firstOrNull()?.let { c.items[it] } ?: return null
        return itemCicp(c, first, depth + 1)
    }

    private fun itemIcc(c: AvifContainer, item: AvifItem, depth: Int): ByteArray? {
        item.property<AvifProperty.Icc>()?.let { return it.profile }
        if (item.type == AV01 || depth > MAX_DEPTH) return null
        val first = c.referencesFrom(item.id, DIMG).firstOrNull()?.let { c.items[it] } ?: return null
        return itemIcc(c, first, depth + 1)
    }

    /**
     * The orientation that displays an image turned [rotation] times 90 degrees anticlockwise
     * and then mirrored, top for bottom on axis 0 and left for right on axis 1, as an EXIF
     * orientation: the image turned clockwise by some quarter turns, then flipped left for right.
     */
    private fun orientation(rotation: AvifProperty.Rotation?, mirror: AvifProperty.Mirror?): Orientation {
        // A turn of a quarters anticlockwise is -a clockwise.
        var turns = (-(rotation?.angle ?: 0)) and 3
        var flipped = false
        if (mirror != null) {
            flipped = true
            // Top for bottom is left for right after a half turn.
            if (mirror.axis == 0) turns = (turns + 2) and 3
        }
        return when (turns) {
            0 -> if (flipped) Orientation.FlipHorizontal else Orientation.Normal
            1 -> if (flipped) Orientation.Transpose else Orientation.Rotate90
            2 -> if (flipped) Orientation.FlipVertical else Orientation.Rotate180
            else -> if (flipped) Orientation.Transverse else Orientation.Rotate270
        }
    }

    /**
     * The rectangle of a clean aperture (ISO/IEC 14496-12 section 12.1.4) as libavif derives it:
     * centred on the image's centre plus the offset, with whole-sample sides and corner. One that
     * names no such rectangle inside the image is left out, as a reader that cannot apply it would.
     */
    private fun cleanAperture(p: AvifProperty.CleanAperture, width: Int, height: Int): IntArray? {
        if (p.widthD <= 0 || p.heightD <= 0 || p.horizOffD <= 0 || p.vertOffD <= 0 || p.widthN < 0 || p.heightN < 0) return null
        if (p.widthN % p.widthD != 0L || p.heightN % p.heightD != 0L) return null
        val w = p.widthN / p.widthD
        val h = p.heightN / p.heightD
        // left = width / 2 + horizOff - w / 2, kept in halves of a sample over horizOffD.
        val left2 = (width - w) * p.horizOffD + 2 * p.horizOffN
        val top2 = (height - h) * p.vertOffD + 2 * p.vertOffN
        if (left2 % (2 * p.horizOffD) != 0L || top2 % (2 * p.vertOffD) != 0L) return null
        val left = left2 / (2 * p.horizOffD)
        val top = top2 / (2 * p.vertOffD)
        if (w <= 0 || h <= 0 || left < 0 || top < 0 || left + w > width || top + h > height) return null
        if (left == 0L && top == 0L && w == width.toLong() && h == height.toLong()) return null
        return intArrayOf(left.toInt(), top.toInt(), w.toInt(), h.toInt())
    }

    // ---- decoding -------------------------------------------------------------------

    private class Rendered(val bitmap: KiteBitmap, val wide: KiteBitmap16?)

    private fun render(p: Plan, wide: Boolean): Rendered {
        p.unsupported?.let { throw UnsupportedImageException(it) }
        val c = p.c
        val out = if (p.item?.type == IOVL) {
            overlay(c, p.item)
        } else {
            val color = if (p.track != null) trackPlanes(c, p.track) else planes(c, p.item!!, 0)
            val alpha = if (p.alphaTrack != null) trackPlanes(c, p.alphaTrack) else p.alphaItem?.let { planes(c, it, 0) }
            convert(color, alpha, p.premultiplied, p.cicp ?: AvifCicp(2, 2, 2, color.fullRange), wide)
        }
        val crop = p.crop?.takeIf { it[0] + it[2] <= out.bitmap.width && it[1] + it[3] <= out.bitmap.height } ?: return out
        return Rendered(cropped(out.bitmap, crop), out.wide?.let { cropped(it, crop) })
    }

    private fun convert(color: AvifPlanes, alpha: AvifPlanes?, premultiplied: Boolean, cicp: AvifCicp, wide: Boolean): Rendered {
        val a = alpha?.let { fullRangeAlpha(it) }
        if (a != null && (a.width != color.width || a.height != color.height)) {
            throw ImageDecodeException("AVIF: the alpha image is ${a.width} by ${a.height}, the colour image ${color.width} by ${color.height}")
        }
        val out8 = IntArray(color.width * color.height)
        val channels = if (a != null) 4 else 3
        val out16 = if (wide) ShortArray(color.width * color.height * channels) else null
        AvifColor.convert(color, cicp, a, premultiplied, out8, out16)
        return Rendered(KiteBitmap(color.width, color.height, out8), out16?.let { KiteBitmap16(color.width, color.height, channels, it) })
    }

    /** An alpha plane in full range: limited range was allowed by AVIF 1.0, and libavif stretches it. */
    private fun fullRangeAlpha(p: AvifPlanes): AvifPlanes {
        if (p.fullRange) return p
        val plane = p.planes[0]
        val out = ShortArray(plane.size) { AvifColor.limitedToFull(p.depth, plane[it].toInt() and 0xFFFF).toShort() }
        return AvifPlanes(p.width, p.height, p.depth, p.subX, p.subY, arrayOf(out), p.strides.copyOf(1), true)
    }

    private fun cropped(b: KiteBitmap, r: IntArray): KiteBitmap {
        val (x0, y0, w, h) = r.toList()
        val out = IntArray(w * h)
        for (y in 0 until h) b.argb.copyInto(out, y * w, (y0 + y) * b.width + x0, (y0 + y) * b.width + x0 + w)
        return KiteBitmap(w, h, out)
    }

    private fun cropped(b: KiteBitmap16, r: IntArray): KiteBitmap16 {
        val (x0, y0, w, h) = r.toList()
        val ch = b.channels
        val out = ShortArray(w * h * ch)
        for (y in 0 until h) b.samples.copyInto(out, y * w * ch, ((y0 + y) * b.width + x0) * ch, ((y0 + y) * b.width + x0 + w) * ch)
        return KiteBitmap16(w, h, ch, out)
    }

    private fun trackPlanes(c: AvifContainer, track: AvifTrack): AvifPlanes {
        val config = track.properties.firstOrNull { it is AvifProperty.Av1Config } as AvifProperty.Av1Config?
        return av1(config?.configObus, c.sampleData(track, 0), 0, 0xFFFF)
    }

    private fun av1(config: ByteArray?, data: ByteArray, operatingPoint: Int, layer: Int): AvifPlanes {
        val pic = Av1Decoder(operatingPoint).decodeStill(config, data, layer)
        return AvifPlanes(pic.width, pic.height, pic.bitDepth, pic.subX, pic.subY, pic.planes, pic.strides, pic.seq.colorRange)
    }

    /** The YUV or gray planes of [item], before conversion to RGB. */
    private fun planes(c: AvifContainer, item: AvifItem, depth: Int): AvifPlanes {
        if (depth > MAX_DEPTH) throw UnsupportedImageException("AVIF derived images nested more than $MAX_DEPTH deep")
        return when (item.type) {
            AV01 -> {
                val config = item.property<AvifProperty.Av1Config>() ?: throw ImageDecodeException("AVIF: item ${item.id} has no 'av1C'")
                av1(
                    config.configObus, c.itemData(item),
                    item.property<AvifProperty.OperatingPoint>()?.index ?: 0,
                    item.property<AvifProperty.LayerSelector>()?.layer ?: 0xFFFF,
                )
            }
            GRID -> {
                val tiles = inputs(c, item)
                AvifGrid.assemble(AvifGrid.read(c.itemData(item)), tiles.size) { planes(c, tiles[it], depth + 1) }
            }
            SATO -> {
                val sources = inputs(c, item).map { planes(c, it, depth + 1) }
                val bits = item.property<AvifProperty.PixelInfo>()?.depths?.firstOrNull()
                    ?: throw ImageDecodeException("AVIF: sample transform item ${item.id} has no 'pixi'")
                val full = item.property<AvifProperty.Nclx>()?.fullRange ?: sources.first().fullRange
                AvifSampleTransform.apply(c.itemData(item), sources, bits, full)
            }
            TMAP -> planes(c, base(c, item, depth), depth + 1)
            else -> throw UnsupportedImageException("AVIF item of type '${fourccName(item.type)}'")
        }
    }

    /**
     * An overlay: its canvas filled with its colour, then each input drawn at its offset, in
     * order, over what is under it. The inputs are converted to RGB with their own colour
     * properties first, so this works in 16 bits and narrows once.
     */
    private fun overlay(c: AvifContainer, item: AvifItem): Rendered {
        val sources = inputs(c, item)
        val ov = AvifOverlay.read(c.itemData(item), sources.size)
        val w = ov.width
        val h = ov.height
        val canvas = ShortArray(w * h * 4)
        for (i in 0 until w * h) for (k in 0 until 4) canvas[i * 4 + k] = ov.fill[k].toShort()
        for ((index, source) in sources.withIndex()) {
            val shown = base(c, source, 0)
            val alpha = alphaOf(c, shown)
            val premultiplied = alpha != null && alpha.id in c.referencesFrom(shown.id, PREM)
            val color = planes(c, shown, 1)
            val rendered = convert(color, alpha?.let { planes(c, it, 1) }, premultiplied, itemCicp(c, shown, 0) ?: AvifCicp(2, 2, 2, color.fullRange), wide = true)
            val src = rendered.wide!!
            val (ox, oy) = ov.offsets[index]
            for (y in 0 until src.height) {
                val cy = oy + y
                if (cy < 0 || cy >= h) continue
                for (x in 0 until src.width) {
                    val cx = ox + x
                    if (cx < 0 || cx >= w) continue
                    val s = (y * src.width + x) * src.channels
                    val d = ((cy * w + cx) * 4).toInt()
                    val sa = if (src.channels == 4) src.samples[s + 3].toInt() and 0xFFFF else 65535
                    if (sa == 65535) {
                        for (k in 0 until 3) canvas[d + k] = src.samples[s + k]
                        canvas[d + 3] = (-1).toShort()
                        continue
                    }
                    // Source over, in straight alpha, with alphas as fractions of 1.
                    val a = sa / 65535.0
                    val b = (canvas[d + 3].toInt() and 0xFFFF) / 65535.0 * (1 - a)
                    val outA = a + b
                    for (k in 0 until 3) {
                        val sc = (src.samples[s + k].toInt() and 0xFFFF).toDouble()
                        val dc = (canvas[d + k].toInt() and 0xFFFF).toDouble()
                        val v = if (outA == 0.0) 0.0 else (sc * a + dc * b) / outA
                        canvas[d + k] = (v + 0.5).toInt().coerceIn(0, 65535).toShort()
                    }
                    canvas[d + 3] = (outA * 65535 + 0.5).toInt().coerceIn(0, 65535).toShort()
                }
            }
        }
        val opaque = (0 until w * h).all { canvas[it * 4 + 3] == (-1).toShort() }
        val channels = if (opaque) 3 else 4
        val samples = if (opaque) ShortArray(w * h * 3) { canvas[(it / 3) * 4 + it % 3] } else canvas
        val wideBitmap = KiteBitmap16(w, h, channels, samples)
        return Rendered(wideBitmap.toBitmap(), wideBitmap)
    }
}
