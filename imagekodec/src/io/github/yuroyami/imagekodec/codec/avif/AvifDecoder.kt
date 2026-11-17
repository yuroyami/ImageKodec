package io.github.yuroyami.imagekodec.codec.avif

import io.github.yuroyami.imagekodec.ColorProfile
import io.github.yuroyami.imagekodec.ExperimentalHeifCodecApi
import io.github.yuroyami.imagekodec.HeifCodec
import io.github.yuroyami.imagekodec.HeifCodecInfo
import io.github.yuroyami.imagekodec.HeifCodecSession
import io.github.yuroyami.imagekodec.HeifPlanes
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
import io.github.yuroyami.imagekodec.codec.avif.AvifContainer.Companion.DIMG
import io.github.yuroyami.imagekodec.codec.avif.AvifContainer.Companion.GRID
import io.github.yuroyami.imagekodec.codec.avif.AvifContainer.Companion.HEV1
import io.github.yuroyami.imagekodec.codec.avif.AvifContainer.Companion.HVC1
import io.github.yuroyami.imagekodec.codec.avif.AvifContainer.Companion.HVCC
import io.github.yuroyami.imagekodec.codec.avif.AvifContainer.Companion.IDEN
import io.github.yuroyami.imagekodec.codec.avif.AvifContainer.Companion.MIAF
import io.github.yuroyami.imagekodec.codec.avif.AvifContainer.Companion.LHV1
import io.github.yuroyami.imagekodec.codec.avif.AvifContainer.Companion.IOVL
import io.github.yuroyami.imagekodec.codec.avif.AvifContainer.Companion.PICT
import io.github.yuroyami.imagekodec.codec.avif.AvifContainer.Companion.PREM
import io.github.yuroyami.imagekodec.codec.avif.AvifContainer.Companion.SATO
import io.github.yuroyami.imagekodec.codec.avif.AvifContainer.Companion.THMB
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
@OptIn(ExperimentalHeifCodecApi::class)
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
        val contentLight: IntArray? = null,
        val masteringDisplay: IntArray? = null,
    )

    fun probe(data: ByteArray): ImageInfo = labelled(data) {
        val p = plan(data, probing = true)
        val pages = if (p.track != null) emptyList() else pageItems(p.c)
        info(data, p, maxOf(1, pages.size)).also { info ->
            stereoPair(p.c, pages)?.let { (left, right) ->
                info.stereoLeftPage = left
                info.stereoRightPage = right
            }
        }
    }

    /** What [probe] says of page [page], a top-level image of the file (#168). */
    fun probePage(data: ByteArray, page: Int): ImageInfo = labelled(data) {
        if (page == 0) return@labelled probe(data)
        val c = AvifContainer.parse(data)
        val pages = pageItems(c)
        require(page < pages.size) { "page $page asked of a HEIF file of ${maxOf(1, pages.size)} pages" }
        info(data, itemPlan(c, base(c, pages[page], 0)), pages.size)
    }

    /** Page [page] of [data]: the primary image for 0, else another top-level image (#168). */
    fun decodePage(data: ByteArray, page: Int, wide: Boolean, cancel: (() -> Unit)?): Pair<KiteBitmap, KiteBitmap16?> = labelled(data) {
        val p = if (page == 0) {
            plan(data, probing = false)
        } else {
            val c = AvifContainer.parse(data)
            val pages = pageItems(c)
            require(page < pages.size) { "page $page asked of a HEIF file of ${maxOf(1, pages.size)} pages" }
            itemPlan(c, base(c, pages[page], 0))
        }
        val r = render(p, wide, cancel)
        r.bitmap to r.wide
    }

    /**
     * The top-level images of a HEIF file, the primary first: image items that are not hidden and
     * that no thumbnail, auxiliary, description or derivation reference uses, and of an `altr`
     * group only the member the primary is (#168).
     */
    private fun pageItems(c: AvifContainer): List<AvifItem> {
        val used = HashSet<Long>()
        for (r in c.references) {
            when (r.type) {
                THMB, AUXL, AvifContainer.CDSC -> used += r.from
                DIMG -> used.addAll(r.to.toList())
            }
        }
        for (g in c.groups) if (g.type == ALTR) used.addAll(g.entities.filter { it != c.primary })
        val images = c.items.values.filter { item ->
            !item.hidden && item.id !in used &&
                (item.type in DERIVED_TYPES || codecOf(item) != null || item.type == HVC1 || item.type == LHV1)
        }
        val primary = images.firstOrNull { it.id == c.primary }
        return listOfNotNull(primary) + images.filter { it !== primary }.sortedBy { it.id }
    }

    /** The pages of the left and right images of a `ster` entity group, ISO/IEC 23008-12 6.8.6.2. */
    private fun stereoPair(c: AvifContainer, pages: List<AvifItem>): Pair<Int, Int>? {
        val group = c.groups.firstOrNull { it.type == STER && it.entities.size == 2 } ?: return null
        val left = pages.indexOfFirst { it.id == group.entities[0] }
        val right = pages.indexOfFirst { it.id == group.entities[1] }
        return if (left >= 0 && right >= 0) left to right else null
    }

    private val STER = fourcc("ster")

    /** The description [probe] gives of plan [p]. */
    private fun info(data: ByteArray, p: Plan, pageCount: Int): ImageInfo {
        val thumb = p.item?.let { thumbnails(p.c, it) }?.maxByOrNull { it.second.first.toLong() * it.second.second }
        return ImageInfo(
            format = AvifContainer.heifFormat(data) ?: ImageFormat.AVIF,
            width = p.crop?.get(2) ?: p.width,
            height = p.crop?.get(3) ?: p.height,
            bitDepth = p.bitDepth,
            hasAlpha = p.alphaItem != null || p.alphaTrack != null,
            frameCount = p.frameCount,
            loopCount = p.loopCount,
            orientation = p.orientation,
            isDecodable = p.unsupported == null,
            unsupportedReason = p.unsupported,
            pageCount = pageCount,
            colorProfile = if (p.icc != null || p.cicp != null || p.contentLight != null || p.masteringDisplay != null) {
                ColorProfile(
                    icc = p.icc, cicp = p.cicp?.toIntArray(),
                    contentLight = p.contentLight, masteringDisplay = p.masteringDisplay,
                )
            } else {
                null
            },
        ).also { info ->
            if (thumb != null) {
                info.thumbnailWidth = thumb.second.first
                info.thumbnailHeight = thumb.second.second
            }
        }
    }

    /** [cancel] runs once a row of superblocks of each AV1 picture the image is made of (#123). */
    fun decode(data: ByteArray, cancel: (() -> Unit)? = null): KiteBitmap = labelled(data) { render(plan(data, probing = false), wide = false, cancel).bitmap }

    fun decode16(data: ByteArray, cancel: (() -> Unit)? = null): KiteBitmap16 = labelled(data) { render(plan(data, probing = false), wide = true, cancel).wide!! }

    /** The reason [unsupported] gives a HEVC item while no codec reads it. */
    const val NEEDS_HEIC_MODULE = "HEIC item of type 'hvc1' needs the imagekodec-heic module: add it and call ImageKodecHeic.install()"

    /**
     * Runs [block] and names the format of a HEIC file in its errors. The reader is shared with
     * AVIF, whose name its messages start with.
     */
    private inline fun <T> labelled(data: ByteArray, block: () -> T): T {
        try {
            return block()
        } catch (e: ImageDecodeException) {
            val message = e.message ?: throw e
            if (!message.startsWith("AVIF") || AvifContainer.heifFormat(data) != ImageFormat.HEIC) throw e
            val text = "HEIC" + message.removePrefix("AVIF")
            throw if (e is UnsupportedImageException) UnsupportedImageException(text, e) else ImageDecodeException(text, e)
        }
    }

    /**
     * Every frame of an AV1 image sequence with its duration, or a still image as one frame. The
     * frames decode in order from the first sample, as each predicts from the ones before it, and
     * the alpha track decodes beside them sample by sample. A frame coded at another size than
     * the first, which AV1 allows mid-sequence, is scaled to the first frame's size, as a player
     * shows every frame in one window.
     */
    fun decodeAnimation(data: ByteArray, maxFrames: Int, cancellationCheck: (() -> Unit)?): KiteAnimation =
        labelled(data) { animation(data, maxFrames, cancellationCheck) }

    private fun animation(data: ByteArray, maxFrames: Int, cancellationCheck: (() -> Unit)?): KiteAnimation {
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

    /** A codec session for the samples of [track], configured by its configuration property. */
    private fun sequenceDecoder(track: AvifTrack): HeifCodecSession {
        val codec = trackCodec(track)
        return codec.open(configuration(track.properties, codec))
    }

    /** The planes of the frame sample [index] of [track] shows. */
    private fun sample(decoder: HeifCodecSession, c: AvifContainer, track: AvifTrack, index: Int): AvifPlanes {
        val pic = decoder.decode(c.sampleData(track, index), null)
            ?: throw ImageDecodeException("AVIF: sample $index of track ${track.id} shows no frame")
        return planesOf(pic)
    }

    // ---- codecs -----------------------------------------------------------------------

    /** The registered codec of [item]'s type, or null for a derived image or an unknown type. */
    private fun codecOf(item: AvifItem): HeifCodec? = HeifCodecs.forItem(item.type)

    private fun trackCodec(track: AvifTrack): HeifCodec = HeifCodecs.forSampleEntry(track.sampleEntry)
        ?: throw UnsupportedImageException("AVIF track of sample entry '${fourccName(track.sampleEntry)}'")

    /** The payload of the property among [properties] that configures [codec], or null. */
    private fun configuration(properties: List<AvifProperty>, codec: HeifCodec): ByteArray? {
        val type = fourcc(codec.configurationType)
        for (p in properties) {
            if (p is AvifProperty.Av1Config && type == AV1C) return p.payload
            if (p is AvifProperty.Other && p.type == type) return p.payload
        }
        return null
    }

    private val AV1C = fourcc("av1C")

    /** What [codec] reads from [item]'s configuration and data, or null when the data is cut off. */
    private fun inspect(c: AvifContainer, item: AvifItem, codec: HeifCodec): HeifCodecInfo? {
        val data = c.itemDataOrNull(item) ?: return null
        return codec.inspect(configuration(item.properties, codec), data)
    }

    private fun HeifCodecInfo.cicp(): AvifCicp? = matrixCoefficients?.let {
        AvifCicp(colorPrimaries ?: 2, transferCharacteristics ?: 2, it, fullRange ?: false)
    }

    /**
     * [p] as the planes the container reader works on. A chroma depth that differs from the luma
     * depth, which Main 10 allows, is scaled to the luma depth.
     */
    private fun planesOf(p: HeifPlanes): AvifPlanes {
        if (p.width <= 0 || p.height <= 0 || p.width.toLong() * p.height > Budget.MAX_PIXELS) {
            throw ImageDecodeException("AVIF: a codec gave a picture of ${p.width} by ${p.height}")
        }
        val planes = if (p.planes.size == 3 && p.bitDepthChroma != p.bitDepthLuma) {
            val shift = p.bitDepthLuma - p.bitDepthChroma
            arrayOf(p.planes[0]) + Array(2) { k ->
                val src = p.planes[k + 1]
                ShortArray(src.size) { i ->
                    val v = src[i].toInt() and 0xFFFF
                    (if (shift > 0) v shl shift else v ushr -shift).toShort()
                }
            }
        } else {
            p.planes
        }
        return AvifPlanes(p.width, p.height, p.bitDepthLuma, p.subX, p.subY, planes, p.strides, p.fullRange)
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

    // ---- thumbnails ----------------------------------------------------------------

    /** The thumbnail [decodeThumbnail] gives: its picture, its own orientation and colour. */
    class Thumbnail(val bitmap: KiteBitmap, val orientation: Orientation, val icc: ByteArray?, val cicp: IntArray?)

    /** The items that a `thmb` reference names as thumbnails of [item], each with its size after its clean aperture. */
    private fun thumbnails(c: AvifContainer, item: AvifItem): List<Pair<AvifItem, Pair<Int, Int>>> =
        c.referencesTo(item.id, THMB).mapNotNull { c.items[it] }.mapNotNull { t ->
            val size = try {
                val (w, h) = itemSize(c, t, 0)
                val crop = geometry(c, t, w, h).first
                if (crop != null) crop[2] to crop[3] else w to h
            } catch (_: ImageDecodeException) {
                return@mapNotNull null
            }
            t to size
        }

    /**
     * The thumbnail of the primary item to show for a box of [maxWidth] by [maxHeight]: the smallest
     * that reaches the box in one side, else the largest, among those this build decodes (#150).
     * Null when the file has none; [UnsupportedImageException] when it has only ones it cannot decode.
     */
    fun thumbnail(data: ByteArray, maxWidth: Int, maxHeight: Int, cancel: (() -> Unit)?): Thumbnail? = labelled(data) {
        val c = AvifContainer.parse(data)
        val primary = c.primary?.let { c.items[it] } ?: return@labelled null
        val all = thumbnails(c, primary)
        if (all.isEmpty()) return@labelled null
        val plans = all.map { (t, _) -> itemPlan(c, t) }
        val usable = plans.filter { it.unsupported == null }
        if (usable.isEmpty()) throw UnsupportedImageException(plans.first().unsupported!!)
        fun w(p: Plan) = p.crop?.get(2) ?: p.width
        fun h(p: Plan) = p.crop?.get(3) ?: p.height
        val covering = usable.filter { w(it) >= maxWidth || h(it) >= maxHeight }
        val chosen = covering.minByOrNull { w(it).toLong() * h(it) } ?: usable.maxBy { w(it).toLong() * h(it) }
        val shown = render(chosen, wide = false, cancel).bitmap
        Thumbnail(shown, chosen.orientation, chosen.icc, chosen.cicp?.toIntArray())
    }

    // ---- choosing what to show ------------------------------------------------------

    /**
     * The plan for [data]. With [probing], an item whose data lies past the end of [data], as in
     * the first bytes of a file a loader peeks at, is described from its properties alone.
     */
    private fun plan(data: ByteArray, probing: Boolean): Plan {
        val c = AvifContainer.parse(data)
        val track = c.tracks.firstOrNull { HeifCodecs.forSampleEntry(it.sampleEntry) != null && it.handler == PICT && it.sizes.isNotEmpty() }
        if (track != null) return trackPlan(c, track, probing)
        if (c.tracks.any { (it.sampleEntry == HVC1 || it.sampleEntry == HEV1) && it.handler == PICT && it.sizes.isNotEmpty() } && c.primary == null) {
            throw UnsupportedImageException(NEEDS_HEIC_MODULE)
        }
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

    private val DERIVED_TYPES = setOf(GRID, IOVL, SATO, TMAP, IDEN)

    private fun inputs(c: AvifContainer, item: AvifItem): List<AvifItem> =
        c.referencesFrom(item.id, DIMG).map { id ->
            c.items[id] ?: throw ImageDecodeException("AVIF: derived item ${item.id} refers to a missing item $id")
        }

    /** Why [item] cannot be shown, from its description in the container. Each item and depth is checked once. */
    private fun unsupported(c: AvifContainer, item: AvifItem, depth: Int): String? {
        val key = item.id * (MAX_DEPTH + 2) + depth
        if (c.checked.containsKey(key)) return c.checked[key]
        return uncheckedReason(c, item, depth).also { c.checked[key] = it }
    }

    private fun uncheckedReason(c: AvifContainer, item: AvifItem, depth: Int): String? {
        if (depth > MAX_DEPTH) return "AVIF derived images nested more than $MAX_DEPTH deep"
        val codec = codecOf(item)
        if (item.type == HVC1 && codec == null) return NEEDS_HEIC_MODULE
        if (item.type == LHV1) return "HEIC with layered HEVC ('lhv1')"
        if (item.type !in DERIVED_TYPES && codec == null) return "AVIF item of type '${fourccName(item.type)}'"
        item.unknownEssential.firstOrNull()?.let { return "AVIF item property '${fourccName(it)}' marked essential" }
        if (codec != null) {
            // A JPEG item may carry its tables in the stream, so 'jpgC' is optional (ISO/IEC 23008-12 H.3).
            return if (configuration(item.properties, codec) == null && codec !== JpegItemCodec) {
                "AVIF item ${item.id} of type '${codec.itemType}' without '${codec.configurationType}'"
            } else {
                null
            }
        }
        return when (item.type) {
            GRID, IOVL, SATO, TMAP, IDEN -> {
                val ids = c.referencesFrom(item.id, DIMG)
                if (ids.isEmpty()) return "AVIF derived item ${item.id} without inputs"
                if (item.type == IDEN && ids.size != 1) return "AVIF identity item ${item.id} with ${ids.size} inputs"
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
        val (crop, orientation) = geometry(c, item, size.first, size.second)
        return Plan(
            c, item, null, alpha, null, premultiplied, size.first, size.second, itemDepth(c, item, 0), crop,
            orientation,
            frameCount = 1, loopCount = 1, icc = itemIcc(c, item, 0), cicp = cicp, unsupported = reason,
            contentLight = itemProperty<AvifProperty.ContentLight>(c, item)?.let { intArrayOf(it.maxContent * 10000, it.maxFrameAverage * 10000) },
            masteringDisplay = itemProperty<AvifProperty.MasteringDisplay>(c, item)?.values,
        )
    }

    private fun trackPlan(c: AvifContainer, track: AvifTrack, probing: Boolean): Plan {
        val codec = trackCodec(track)
        val config = configuration(track.properties, codec)
        val alpha = c.tracks.firstOrNull { t ->
            t !== track && HeifCodecs.forSampleEntry(t.sampleEntry) != null && (t.auxType == ALPHA_URN || t.auxType == ALPHA_URN_HEVC) &&
                t.references.any { it.type == AUXL && track.id in it.to }
        }
        val first = c.sampleDataOrNull(track, 0)
        if (first == null && !probing) throw ImageDecodeException("AVIF: the first frame of track ${track.id} extends past the end of the file")
        val headers = first?.let { codec.inspect(config, it) }
        val nclx = track.properties.firstOrNull { it is AvifProperty.Nclx } as AvifProperty.Nclx?
        val cicp = nclx?.let { AvifCicp(it.primaries, it.transfer, it.matrix, it.fullRange) } ?: headers?.cicp()
        // A sequence's first frame is a key frame: what follows it is what decodeAnimation reads.
        val reason = headers?.unsupportedReason ?: cicp?.let { AvifColor.unsupported(it.matrix) }
        val premultiplied = alpha != null &&
            (track.references.any { it.type == PREM && alpha.id in it.to } || alpha.references.any { it.type == PREM && track.id in it.to })
        val width = headers?.width?.takeIf { it > 0 } ?: track.width
        val height = headers?.height?.takeIf { it > 0 } ?: track.height
        if (width <= 0 || height <= 0) throw ImageDecodeException("AVIF: track ${track.id} has no size")
        val crop = (track.properties.firstOrNull { it is AvifProperty.CleanAperture } as AvifProperty.CleanAperture?)
            ?.let { cleanAperture(it, width, height) }
        return Plan(
            c, null, track, null, alpha, premultiplied, width, height,
            headers?.bitDepthLuma ?: config?.let { codec.inspect(it, null).bitDepthLuma } ?: 8, crop,
            orientation(
                track.properties.firstOrNull { it is AvifProperty.Rotation } as AvifProperty.Rotation?,
                track.properties.firstOrNull { it is AvifProperty.Mirror } as AvifProperty.Mirror?,
            ),
            frameCount = track.sizes.size, loopCount = track.playCount,
            icc = (track.properties.firstOrNull { it is AvifProperty.Icc } as AvifProperty.Icc?)?.profile,
            cicp = cicp, unsupported = reason,
            contentLight = (track.properties.firstOrNull { it is AvifProperty.ContentLight } as AvifProperty.ContentLight?)
                ?.let { intArrayOf(it.maxContent * 10000, it.maxFrameAverage * 10000) },
            masteringDisplay = (track.properties.firstOrNull { it is AvifProperty.MasteringDisplay } as AvifProperty.MasteringDisplay?)?.values,
        )
    }

    /** Why the coded data of [item], or of an item it is derived from, cannot be decoded. Each item and depth is read once. */
    private fun frames(c: AvifContainer, item: AvifItem, depth: Int): String? {
        val key = item.id * (MAX_DEPTH + 2) + depth
        if (c.framesChecked.containsKey(key)) return c.framesChecked[key]
        return uncheckedFrames(c, item, depth).also { c.framesChecked[key] = it }
    }

    private fun uncheckedFrames(c: AvifContainer, item: AvifItem, depth: Int): String? {
        if (depth > MAX_DEPTH) return null
        val codec = codecOf(item)
        if (codec != null) {
            // Data a probe cannot see yet is checked when it decodes.
            return inspect(c, item, codec)?.unsupportedReason
        }
        val ids = c.referencesFrom(item.id, DIMG)
        val used = if (item.type == TMAP) ids.take(1) else ids
        return used.firstNotNullOfOrNull { id -> c.items[id]?.let { frames(c, it, depth + 1) } }
    }

    private fun itemSize(c: AvifContainer, item: AvifItem, depth: Int): Pair<Int, Int> {
        if (depth > MAX_DEPTH) throw UnsupportedImageException("AVIF derived images nested more than $MAX_DEPTH deep")
        when (item.type) {
            GRID -> c.itemDataOrNull(item)?.let { return AvifGrid.read(it).let { g -> g.width to g.height } }
            IDEN -> c.referencesFrom(item.id, DIMG).firstOrNull()?.let { c.items[it] }?.let { return itemSize(c, it, depth + 1) }
            IOVL -> c.itemDataOrNull(item)?.let { return AvifOverlay.read(it, 0).let { o -> o.width to o.height } }
            TMAP -> return itemSize(c, base(c, item, depth), depth + 1)
        }
        val ispe = item.property<AvifProperty.Extent>()
        if (ispe != null) {
            // MIAF (ISO/IEC 23000-22, 7.3.6.3) has the ispe of a coded item match its reconstructed
            // size. Where a damaged file disagrees, the frame is what decodes, so probe reports it (#124).
            val codec = codecOf(item)
            if (codec != null) {
                val coded = try {
                    inspect(c, item, codec)
                } catch (_: ImageDecodeException) {
                    null
                }
                if (coded != null && coded.width > 0 && coded.height > 0) return shownSize(ispe, checkedSize(item, coded))
            }
            // The same ceiling as a grid or an overlay, so probe promises no size past it.
            if (ispe.width !in 1..MAX_SIDE || ispe.height !in 1..MAX_SIDE || ispe.width * ispe.height > Budget.MAX_PIXELS) {
                throw ImageDecodeException("AVIF: item ${item.id} is ${ispe.width} by ${ispe.height}")
            }
            return ispe.width.toInt() to ispe.height.toInt()
        }
        val codec = codecOf(item)
        if (codec != null) {
            val h = inspect(c, item, codec) ?: throw ImageDecodeException("AVIF: item ${item.id} has no 'ispe' and its data is cut off")
            return checkedSize(item, h)
        }
        val first = c.referencesFrom(item.id, DIMG).firstOrNull()?.let { c.items[it] }
            ?: throw ImageDecodeException("AVIF: item ${item.id} has no size")
        return itemSize(c, first, depth + 1)
    }

    private const val MAX_SIDE = 65536L

    /**
     * The size a coded item shows: its 'ispe' where the picture is larger in both directions, as Sony's
     * HEIC thumbnails code 1664 by 1088 samples under an ispe of 1616 by 1080 with no conformance
     * window, else the coded size (#150). The samples past the ispe are the encoder's padding.
     */
    private fun shownSize(ispe: AvifProperty.Extent?, coded: Pair<Int, Int>): Pair<Int, Int> {
        if (ispe == null || ispe.width < 1 || ispe.height < 1) return coded
        // Compared as Long: an ispe past 2^31 must not wrap to a negative size.
        return if (ispe.width <= coded.first && ispe.height <= coded.second) ispe.width.toInt() to ispe.height.toInt() else coded
    }

    /** [p] cut to the [size] that [shownSize] gives; the planes stay, read through their strides. */
    private fun shown(p: AvifPlanes, size: Pair<Int, Int>): AvifPlanes =
        if (size.first == p.width && size.second == p.height) p
        else AvifPlanes(size.first, size.second, p.depth, p.subX, p.subY, p.planes, p.strides, p.fullRange)

    /** The coded size of [item], within the same ceiling as its 'ispe'. */
    private fun checkedSize(item: AvifItem, h: HeifCodecInfo): Pair<Int, Int> {
        if (h.width !in 1..MAX_SIDE || h.height !in 1..MAX_SIDE || h.width.toLong() * h.height > Budget.MAX_PIXELS) {
            throw ImageDecodeException("AVIF: item ${item.id} codes a picture of ${h.width} by ${h.height}")
        }
        return h.width to h.height
    }

    private fun itemDepth(c: AvifContainer, item: AvifItem, depth: Int): Int {
        if (depth > MAX_DEPTH) return 8
        if (item.type == SATO) item.property<AvifProperty.PixelInfo>()?.depths?.firstOrNull()?.let { return it }
        codecOf(item)?.let { codec -> configuration(item.properties, codec)?.let { return codec.inspect(it, null).bitDepthLuma } }
        // An HEVC item's depth is in the fixed header of its 'hvcC', which the core reads itself.
        if (item.type == HVC1) {
            item.properties.firstNotNullOfOrNull { p -> (p as? AvifProperty.Other)?.takeIf { it.type == HVCC }?.payload }
                ?.let { AvifContainer.hevcConfigDepths(it) }?.let { return it[1] }
        }
        val next = if (item.type == TMAP) base(c, item, depth) else c.referencesFrom(item.id, DIMG).firstOrNull()?.let { c.items[it] }
        return next?.let { itemDepth(c, it, depth + 1) } ?: 8
    }

    /**
     * The crop and orientation of [item]'s transformative properties: MIAF's fixed order when the
     * file has the `miaf` brand, else the order of their association (#167).
     */
    private fun geometry(c: AvifContainer, item: AvifItem, width: Int, height: Int): Pair<IntArray?, Orientation> {
        val steps = if (MIAF in c.brands) {
            listOfNotNull(item.property<AvifProperty.CleanAperture>(), item.property<AvifProperty.Rotation>(), item.property<AvifProperty.Mirror>())
        } else {
            item.properties.filter { it is AvifProperty.CleanAperture || it is AvifProperty.Rotation || it is AvifProperty.Mirror }
        }
        return HeifGeometry.fold(width, height, steps, ::cleanAperture, ::orientation)
    }

    /** The colour code points of [item]: its own `nclx`, else its first input's, else its AV1 sequence header's. */
    private fun itemCicp(c: AvifContainer, item: AvifItem, depth: Int): AvifCicp? {
        if (depth > MAX_DEPTH) return null
        // A JPEG item's samples are the RGB its decoder gives, whatever an nclx says of its YCbCr.
        if (item.type == AvifContainer.JPEG) return AvifCicp(item.property<AvifProperty.Nclx>()?.primaries ?: 2, 13, 0, true)
        item.property<AvifProperty.Nclx>()?.let { return AvifCicp(it.primaries, it.transfer, it.matrix, it.fullRange) }
        val codec = codecOf(item)
        if (codec != null) return inspect(c, item, codec)?.cicp()
        val first = c.referencesFrom(item.id, DIMG).firstOrNull()?.let { c.items[it] } ?: return null
        return itemCicp(c, first, depth + 1)
    }

    /** [item]'s property of type [T], or that of the first image a derived image is made from. */
    private inline fun <reified T : AvifProperty> itemProperty(c: AvifContainer, item: AvifItem): T? {
        var at = item
        repeat(MAX_DEPTH + 1) {
            at.property<T>()?.let { return it }
            if (codecOf(at) != null) return null
            at = c.referencesFrom(at.id, DIMG).firstOrNull()?.let { c.items[it] } ?: return null
        }
        return null
    }

    private fun itemIcc(c: AvifContainer, item: AvifItem, depth: Int): ByteArray? {
        item.property<AvifProperty.Icc>()?.let { return it.profile }
        if (codecOf(item) != null || depth > MAX_DEPTH) return null
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

    private fun render(p: Plan, wide: Boolean, cancel: (() -> Unit)? = null): Rendered {
        p.unsupported?.let { throw UnsupportedImageException(it) }
        val c = p.c
        val out = if (p.item?.type == IOVL) {
            overlay(c, p.item, cancel)
        } else {
            val color = if (p.track != null) trackPlanes(c, p.track, cancel) else planes(c, p.item!!, 0, cancel)
            val alpha = if (p.alphaTrack != null) trackPlanes(c, p.alphaTrack, cancel) else p.alphaItem?.let { planes(c, it, 0, cancel) }
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

    private fun trackPlanes(c: AvifContainer, track: AvifTrack, cancel: (() -> Unit)? = null): AvifPlanes {
        val codec = trackCodec(track)
        return still(codec, configuration(track.properties, codec), c.sampleData(track, 0), 0, 0xFFFF, cancel)
    }

    /** The one picture that [data] codes, decoded by [codec]. */
    private fun still(codec: HeifCodec, config: ByteArray?, data: ByteArray, operatingPoint: Int, layer: Int, cancel: (() -> Unit)?): AvifPlanes {
        val session = if (codec === Av1Codec) Av1Codec.open(config, operatingPoint, layer, still = true) else codec.open(config)
        return planesOf(session.decode(data, cancel) ?: throw ImageDecodeException("AVIF: an item of type '${codec.itemType}' shows no picture"))
    }

    /** The YUV or gray planes of [item], before conversion to RGB. */
    private fun planes(c: AvifContainer, item: AvifItem, depth: Int, cancel: (() -> Unit)? = null): AvifPlanes {
        // An item that several derived images name, or one names twice, decodes once.
        if (c.inputUses(item.id) < 2) return uncachedPlanes(c, item, depth, cancel)
        return c.decoded.getOrPut(item.id) { uncachedPlanes(c, item, depth, cancel) }
    }

    private fun uncachedPlanes(c: AvifContainer, item: AvifItem, depth: Int, cancel: (() -> Unit)?): AvifPlanes {
        if (depth > MAX_DEPTH) throw UnsupportedImageException("AVIF derived images nested more than $MAX_DEPTH deep")
        return when (item.type) {
            in DERIVED_TYPES -> derivedPlanes(c, item, depth, cancel)
            else -> {
                val codec = codecOf(item) ?: throw UnsupportedImageException("AVIF item of type '${fourccName(item.type)}'")
                val config = configuration(item.properties, codec)
                if (config == null && codec !== JpegItemCodec) throw ImageDecodeException("AVIF: item ${item.id} has no '${codec.configurationType}'")
                val data = c.itemData(item)
                // The coded size, within the ceiling, before the codec allocates for it.
                checkedSize(item, codec.inspect(config, data))
                val planes = still(
                    codec, config, data,
                    item.property<AvifProperty.OperatingPoint>()?.index ?: 0,
                    item.property<AvifProperty.LayerSelector>()?.layer ?: 0xFFFF,
                    cancel,
                )
                shown(planes, shownSize(item.property<AvifProperty.Extent>(), planes.width to planes.height))
            }
        }
    }

    private fun derivedPlanes(c: AvifContainer, item: AvifItem, depth: Int, cancel: (() -> Unit)?): AvifPlanes {
        return when (item.type) {
            GRID -> {
                val tiles = inputs(c, item)
                AvifGrid.assemble(AvifGrid.read(c.itemData(item)), tiles.size) { planes(c, tiles[it], depth + 1, cancel) }
            }
            SATO -> {
                val sources = inputs(c, item).map { planes(c, it, depth + 1, cancel) }
                val bits = item.property<AvifProperty.PixelInfo>()?.depths?.firstOrNull()
                    ?: throw ImageDecodeException("AVIF: sample transform item ${item.id} has no 'pixi'")
                val full = item.property<AvifProperty.Nclx>()?.fullRange ?: sources.first().fullRange
                AvifSampleTransform.apply(c.itemData(item), sources, bits, full)
            }
            TMAP -> planes(c, base(c, item, depth), depth + 1, cancel)
            IDEN -> planes(c, inputs(c, item).first(), depth + 1, cancel)
            else -> throw UnsupportedImageException("AVIF item of type '${fourccName(item.type)}'")
        }
    }

    /**
     * An overlay: its canvas filled with its colour, then each input drawn at its offset, in
     * order, over what is under it. The inputs are converted to RGB with their own colour
     * properties first, so this works in 16 bits and narrows once.
     */
    private fun overlay(c: AvifContainer, item: AvifItem, cancel: (() -> Unit)? = null): Rendered {
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
            val color = planes(c, shown, 1, cancel)
            val rendered = convert(color, alpha?.let { planes(c, it, 1, cancel) }, premultiplied, itemCicp(c, shown, 0) ?: AvifCicp(2, 2, 2, color.fullRange), wide = true)
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
