package io.github.yuroyami.imagekodec.codec.avif

import io.github.yuroyami.imagekodec.ExperimentalHeifCodecApi
import io.github.yuroyami.imagekodec.HeifChroma
import io.github.yuroyami.imagekodec.HeifCodedPicture
import io.github.yuroyami.imagekodec.HeifCodedSample
import io.github.yuroyami.imagekodec.HeifEncodeOptions
import io.github.yuroyami.imagekodec.HeifEncodeParameters
import io.github.yuroyami.imagekodec.HeifEncoder
import io.github.yuroyami.imagekodec.HeifPlanes
import io.github.yuroyami.imagekodec.ImageMetadata
import io.github.yuroyami.imagekodec.KiteAnimation
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.KiteBitmap16
import io.github.yuroyami.imagekodec.Orientation
import io.github.yuroyami.imagekodec.UnsupportedImageException
import io.github.yuroyami.imagekodec.codec.MetadataIo
import io.github.yuroyami.imagekodec.internal.Budget
import io.github.yuroyami.imagekodec.internal.Tiles
import io.github.yuroyami.imagekodec.scaled

/**
 * A HEIF file of one still image (#174): RGB to planes ([HeifPlaneMaker]), the planes coded by the
 * registered [HeifEncoder] of the item type, one item or a `grid` of tiles, and the items written by
 * [HeifWriter] with an alpha auxiliary image, a thumbnail and the metadata the caller gives. An
 * animation is an image sequence track instead ([encodeSequence], #177).
 */
@OptIn(ExperimentalHeifCodecApi::class)
internal object HeifEncode {

    const val NEEDS_HEIC_MODULE = "HEIC encoding needs the 'hvc1' encoder of the imagekodec-heic module: add it and call ImageKodecHeic.install()"

    /**
     * [bitmap] or [bitmap16] as a HEIF file of items of [itemType]. Throws IllegalArgumentException for a
     * picture too large for a reader, and UnsupportedImageException when no encoder of [itemType] is registered.
     */
    fun encode(bitmap: KiteBitmap?, bitmap16: KiteBitmap16?, options: HeifEncodeOptions, metadata: ImageMetadata, itemType: String, cancel: (() -> Unit)?): ByteArray {
        val source = if (bitmap != null) HeifRgbSource(bitmap) else HeifRgbSource(bitmap16!!)
        val w = source.width
        val h = source.height
        require(w <= MAX_SIDE && h <= MAX_SIDE && w.toLong() * h <= Budget.MAX_PIXELS) { "HEIF: a picture of $w by $h passes the size a reader accepts" }
        val tile = options.tileSize
        val grid = tile != null && (w > tile || h > tile)
        if (grid) {
            val columns = (w + tile - 1) / tile
            val rows = (h + tile - 1) / tile
            require(columns <= 256 && rows <= 256 && columns * rows < 65536) {
                "HEIF: a picture of $w by $h needs $columns by $rows tiles of $tile, more than a grid holds (256 by 256): use larger tiles"
            }
        }
        val encoder = HeifCodecs.encoder(itemType) ?: throw UnsupportedImageException(if (itemType == "hvc1") NEEDS_HEIC_MODULE else "HEIF encoding of '$itemType' needs its encoder registered")
        val depth = options.bitDepth ?: when {
            bitmap != null -> 8
            options.lossless -> 12
            else -> 10
        }
        val writer = Writing(encoder, options, metadata, cancel)
        val primary = writer.image(source, depth, if (grid) tile else null)
        writer.heif.primary = primary
        val longest = maxOf(w, h)
        val thumb = options.thumbnailSize
        if (thumb != null && longest > thumb) {
            val small = (bitmap ?: bitmap16!!.toBitmap()).scaled(thumb, thumb)
            val t = writer.image(HeifRgbSource(small), 8, null)
            writer.heif.reference("thmb", t, intArrayOf(primary))
        }
        writer.metadata(primary)
        return writer.build()
    }

    const val NEEDS_HEIC_SEQUENCE = "HEIC image sequences need a sequence encoder for 'hvc1', and the registered one codes still pictures only"

    /** The timescale of a sequence's track: milliseconds, the unit of [KiteFrame.delayMillis]. */
    private const val TIMESCALE = 1000L

    /**
     * [animation] as a HEIF image sequence (#177): one track of the pictures, coded by the sequence encoder of the
     * registered [HeifEncoder] of [itemType] ([HeifEncoder.openSequence]), each frame lasting its delay in
     * milliseconds (1 for a delay of 0), the play count in the edit list, an auxiliary alpha track when any frame
     * has alpha, and the first picture also as the primary item, sharing the first sample's bytes, so a reader of
     * still images shows it, as libavif writes a sequence. Throws IllegalArgumentException for an animation a
     * reader cannot take, and UnsupportedImageException when no encoder of [itemType], or no sequence encoder, is
     * registered.
     */
    fun encodeSequence(animation: KiteAnimation, options: HeifEncodeOptions, metadata: ImageMetadata, itemType: String, cancel: (() -> Unit)?): ByteArray {
        val w = animation.width
        val h = animation.height
        require(w in 1..MAX_SIDE && h in 1..MAX_SIDE && w.toLong() * h <= Budget.MAX_PIXELS) { "HEIF: a sequence of $w by $h passes the size a reader accepts" }
        for ((i, f) in animation.frames.withIndex()) {
            require(f.bitmap.width == w && f.bitmap.height == h) { "HEIF: frame $i is ${f.bitmap.width} by ${f.bitmap.height} in an animation of $w by $h" }
            require(f.delayMillis >= 0) { "HEIF: frame $i has a delay of ${f.delayMillis} ms" }
        }
        val encoder = HeifCodecs.encoder(itemType) ?: throw UnsupportedImageException(if (itemType == "hvc1") NEEDS_HEIC_MODULE else "HEIF encoding of '$itemType' needs its encoder registered")
        val sources = animation.frames.map { HeifRgbSource(it.bitmap) }
        val gray = sources.all { it.gray }
        val alpha = sources.any { it.alpha }
        val depth = options.bitDepth ?: 8
        val lossless = options.lossless
        val subX = if (gray || lossless || options.chroma == HeifChroma.Yuv444) 0 else 1
        val subY = if (gray || lossless || options.chroma != HeifChroma.Yuv420) 0 else 1
        // A subsampled picture of odd size codes one more column or row and crops it with 'clap', as a still does.
        val cw = (w + subX) shr subX shl subX
        val ch = (h + subY) shr subY shl subY
        val icc = metadata.icc
        val matrix = if (lossless && !gray) 0 else 6
        val colour = HeifEncodeParameters(
            options.quality, lossless, options.effort,
            colorPrimaries = if (icc != null) 2 else 1, transferCharacteristics = if (icc != null) 2 else 13, matrixCoefficients = matrix,
        )
        val colourCoder = encoder.openSequence(colour, options.keyFrameInterval)
            ?: throw UnsupportedImageException(if (itemType == "hvc1") NEEDS_HEIC_SEQUENCE else "HEIF image sequences of '$itemType' need a sequence encoder")
        val alphaCoder = if (alpha) encoder.openSequence(HeifEncodeParameters(options.quality, lossless, options.effort), options.keyFrameInterval)!! else null
        val colourSamples = ArrayList<HeifCodedSample>()
        val alphaSamples = ArrayList<HeifCodedSample>()
        for (source in sources) {
            cancel?.invoke()
            val maker = HeifPlaneMaker(source, depth, lossless, subX, subY, gray)
            colourSamples += colourCoder.encode(maker.colour(0, 0, cw, ch), cancel)
            if (alphaCoder != null) alphaSamples += alphaCoder.encode(maker.alpha(0, 0, cw, ch), cancel)
        }
        colourSamples += colourCoder.finish(cancel)
        if (alphaCoder != null) alphaSamples += alphaCoder.finish(cancel)
        val durations = LongArray(animation.frames.size) { maxOf(1, animation.frames[it].delayMillis).toLong() }
        val heif = HeifWriter()
        val nclx = colr(colour.colorPrimaries!!, colour.transferCharacteristics!!, matrix)
        val prof = icc?.let { box("colr", "prof".encodeToByteArray() + it) }
        val crop = if (cw != w || ch != h) clap(w, h, cw, ch) else null
        val geometry = orientation(options.orientation)
        val colourConfig = box(encoder.configurationType, colourCoder.configuration)
        val colourEntry = sampleEntry(colourCoder.sampleEntryType, cw, ch, listOf(colourConfig, nclx) + listOfNotNull(prof, ccst(), crop) + geometry.map { it.first })
        val colourTrack = heif.addTrack(track(colourSamples, durations, colourEntry, cw, ch, animation.loopCount, "pict", null))
        val first = colourSamples.indexOfFirst { it.frameIndex == 0 }
        val channels = if (gray) 1 else 3
        val primary = heif.addItem(
            encoder.itemType, colourSamples[first].data,
            listOf(colourConfig to true, ispe(cw, ch) to false, pixi(channels, depth) to false, nclx to false) +
                listOfNotNull(prof?.let { it to false }, crop?.let { it to true }) + geometry,
            sharedSample = colourTrack to first,
        )
        heif.primary = primary
        if (alphaCoder != null) {
            val alphaConfig = box(encoder.configurationType, alphaCoder.configuration)
            val auxi = fullBox("auxi", encoder.alphaUrn.encodeToByteArray() + byteArrayOf(0))
            val alphaEntry = sampleEntry(alphaCoder.sampleEntryType, cw, ch, listOf(alphaConfig, auxi, ccst()) + listOfNotNull(crop))
            val alphaTrack = heif.addTrack(track(alphaSamples, durations, alphaEntry, cw, ch, animation.loopCount, "auxv", intArrayOf(colourTrack)))
            val firstAlpha = alphaSamples.indexOfFirst { it.frameIndex == 0 }
            val alphaItem = heif.addItem(
                encoder.itemType, alphaSamples[firstAlpha].data,
                listOf(alphaConfig to true, ispe(cw, ch) to false, pixi(1, depth) to false, fullBox("auxC", encoder.alphaUrn.encodeToByteArray() + byteArrayOf(0)) to false) +
                    listOfNotNull(crop?.let { it to true }),
                hidden = true, sharedSample = alphaTrack to firstAlpha,
            )
            heif.reference("auxl", alphaItem, intArrayOf(primary))
        }
        Writing(encoder, options, metadata, cancel, heif).metadata(primary)
        // AVIF 1.1 section 6 and ISO/IEC 23008-12 Annex B: the sequence brand first, the still image brand beside it.
        // libheif 1.17 refuses a file whose major brand is HEVC's sequence brand 'hevc' or 'hevx' ("unsupported
        // HEIF/AVIF file type"), so a HEIC sequence leads with the brand of its first picture, which is the
        // primary item, and lists the sequence brand among the compatible ones. AVIF leads with 'avis'.
        val major = if (itemType == "hvc1") colourCoder.imageBrand else colourCoder.brand
        return heif.build(major, listOf(colourCoder.imageBrand, colourCoder.brand, "msf1", "iso8", "mif1", "miaf"))
    }

    /**
     * A track of [samples], in decoding order, whose pictures last [durations] in display order: each sample's
     * decoding time is the display time of the picture at its place, and its composition offset moves it to the
     * display time of the picture it codes, so a sequence without reordering has none.
     */
    private fun track(
        samples: List<HeifCodedSample>, durations: LongArray, entry: ByteArray, w: Int, h: Int, playCount: Long, handler: String, auxiliaryOf: IntArray?,
    ): HeifWriter.Track {
        val n = durations.size
        check(samples.size == n && samples.map { it.frameIndex }.sorted() == (0 until n).toList()) {
            "the sequence encoder coded ${samples.size} samples for $n pictures"
        }
        check(samples[0].sync) { "the first sample of a sequence is not a sync sample" }
        val start = LongArray(n + 1)
        for (j in 0 until n) start[j + 1] = start[j] + durations[j]
        val offsets = LongArray(n) { i -> start[samples[i].frameIndex] - start[i] }
        return HeifWriter.Track(
            entry, samples.map { it.data }, durations.copyOf(), if (offsets.all { it == 0L }) null else offsets,
            BooleanArray(n) { samples[it].sync }, w, h, TIMESCALE, playCount, handler, auxiliaryOf,
        )
    }

    /**
     * A VisualSampleEntry of [type] (ISO/IEC 14496-12 12.1.3) for pictures of [w] by [h], with [children]: six
     * reserved bytes, data reference 1, the size, 72 dpi, one frame a sample, no compressor name, depth 24.
     */
    private fun sampleEntry(type: String, w: Int, h: Int, children: List<ByteArray>): ByteArray {
        val s = HeifWriter.Sink()
        for (i in 0 until 6) s.u8(0)
        s.u16(1) // data_reference_index
        s.u16(0)
        s.u16(0)
        for (i in 0 until 3) s.u32(0)
        s.u16(w)
        s.u16(h)
        s.u32(0x00480000)
        s.u32(0x00480000)
        s.u32(0)
        s.u16(1) // frame_count
        for (i in 0 until 32) s.u8(0) // compressorname
        s.u16(0x0018)
        s.u16(0xFFFF) // pre_defined -1
        for (c in children) s.bytes(c)
        return s.box(type)
    }

    /**
     * `ccst` (ISO/IEC 23008-12 7.2.3.4), which an image sequence's sample entry carries: pictures may predict from
     * others (all_ref_pics_intra 0), intra prediction is used, and any number of references (15).
     */
    private fun ccst(): ByteArray = fullBox("ccst", be32((1 shl 30) or (15 shl 26)))

    /** The largest side of a grid a reader accepts, 16 bits of `ispe` and the grid's output size. */
    private const val MAX_SIDE = 65535

    private class Writing(
        val encoder: HeifEncoder,
        val options: HeifEncodeOptions,
        val metadata: ImageMetadata,
        val cancel: (() -> Unit)?,
        val heif: HeifWriter = HeifWriter(),
    ) {

        /**
         * Codes [source] as an image item, a grid of [tile] when not null, with its alpha as an auxiliary
         * image, and returns the item's ID.
         */
        fun image(source: HeifRgbSource, depth: Int, tile: Int?): Int {
            val gray = source.gray
            val lossless = options.lossless
            val subX = if (gray || lossless || options.chroma == HeifChroma.Yuv444) 0 else 1
            val subY = if (gray || lossless || options.chroma != HeifChroma.Yuv420) 0 else 1
            val maker = HeifPlaneMaker(source, depth, lossless, subX, subY)
            val icc = metadata.icc
            // sRGB as libavif and libheif write it: BT.709 primaries, the sRGB curve, BT.601's matrix,
            // full range; the identity matrix for lossless colour. With an ICC profile, which gives the
            // colour space, the primaries and curve are left unspecified.
            val matrix = if (lossless && !gray) 0 else 6
            val colour = HeifEncodeParameters(
                options.quality, lossless, options.effort,
                colorPrimaries = if (icc != null) 2 else 1, transferCharacteristics = if (icc != null) 2 else 13, matrixCoefficients = matrix,
            )
            val alphaParameters = HeifEncodeParameters(options.quality, lossless, options.effort)
            val nclx = colr(colour.colorPrimaries!!, colour.transferCharacteristics!!, matrix)
            val prof = icc?.let { box("colr", "prof".encodeToByteArray() + it) }
            val channels = if (gray) 1 else 3
            val geometry = orientation(options.orientation)
            if (tile == null) {
                // A subsampled picture of odd size codes one more column or row and crops it with 'clap'.
                val cw = (source.width + subX) shr subX shl subX
                val ch = (source.height + subY) shr subY shl subY
                val coded = code(maker.colour(0, 0, cw, ch), colour)
                val crop = if (cw != source.width || ch != source.height) clap(source.width, source.height, cw, ch) else null
                val props = configured(coded) + listOf(ispe(cw, ch) to false, pixi(channels, depth) to false, nclx to false) +
                    listOfNotNull(prof?.let { it to false }, crop?.let { it to true }) + geometry
                val id = heif.addItem(encoder.itemType, coded.data, props)
                if (source.alpha) {
                    val a = code(maker.alpha(0, 0, cw, ch), alphaParameters)
                    val alphaProps = configured(a) + listOf(ispe(cw, ch) to false, pixi(1, depth) to false, auxC() to false) +
                        listOfNotNull(crop?.let { it to true })
                    val alpha = heif.addItem(encoder.itemType, a.data, alphaProps, hidden = true)
                    heif.reference("auxl", alpha, intArrayOf(id))
                }
                return id
            }
            val columns = (source.width + tile - 1) / tile
            val rows = (source.height + tile - 1) / tile
            val description = gridDescription(rows, columns, source.width, source.height)
            val tileProps = listOf(ispe(tile, tile) to false, pixi(channels, depth) to false, nclx to false) + listOfNotNull(prof?.let { it to false })
            // The grid's own properties: its output size, then its colour, then its geometry (MIAF, ISO/IEC 23000-22, orders
            // clean aperture, rotation, mirror). libheif 1.17.6 converts a grid by its tiles' colour, so the tiles
            // carry the same 'colr' boxes, stored once.
            val gridProps = listOf(ispe(source.width, source.height) to false, pixi(channels, depth) to false, nclx to false) +
                listOfNotNull(prof?.let { it to false }) + geometry
            val id = heif.addItem("grid", description, gridProps, inIdat = true)
            val tiles = codeTiles(rows * columns) { k -> code(maker.colour((k % columns) * tile, (k / columns) * tile, tile, tile), colour) }
            val tileIds = IntArray(tiles.size) { k -> heif.addItem(encoder.itemType, tiles[k].data, configured(tiles[k]) + tileProps, hidden = true) }
            heif.reference("dimg", id, tileIds)
            if (source.alpha) {
                val alphaGrid = heif.addItem("grid", description, listOf(ispe(source.width, source.height) to false, pixi(1, depth) to false, auxC() to false), hidden = true, inIdat = true)
                val alphaTiles = codeTiles(rows * columns) { k -> code(maker.alpha((k % columns) * tile, (k / columns) * tile, tile, tile), alphaParameters) }
                val alphaIds = IntArray(alphaTiles.size) { k ->
                    heif.addItem(encoder.itemType, alphaTiles[k].data, configured(alphaTiles[k]) + listOf(ispe(tile, tile) to false, pixi(1, depth) to false), hidden = true)
                }
                heif.reference("dimg", alphaGrid, alphaIds)
                heif.reference("auxl", alphaGrid, intArrayOf(id))
            }
            return id
        }

        /** The coded pictures of [count] tiles, each made by [job], through the caller's tile runner (#152). */
        private fun codeTiles(count: Int, job: (Int) -> HeifCodedPicture): Array<HeifCodedPicture> {
            val out = arrayOfNulls<HeifCodedPicture>(count)
            Tiles.run(count) { k ->
                cancel?.invoke()
                out[k] = job(k)
            }
            return Array(count) { out[it]!! }
        }

        private fun code(planes: HeifPlanes, parameters: HeifEncodeParameters): HeifCodedPicture {
            val coded = encoder.encode(planes, parameters, cancel)
            check(coded.width == planes.width && coded.height == planes.height) {
                "the '${encoder.itemType}' encoder coded ${planes.width} by ${planes.height} as ${coded.width} by ${coded.height}"
            }
            brands += coded.brand
            return coded
        }

        private val brands = LinkedHashSet<String>()

        private fun configured(coded: HeifCodedPicture): List<Pair<ByteArray, Boolean>> =
            listOf(box(encoder.configurationType, coded.configuration) to true)

        private fun auxC(): ByteArray = fullBox("auxC", encoder.alphaUrn.encodeToByteArray() + byteArrayOf(0))

        /** The `Exif` and XMP items of [metadata], each described ('cdsc') as metadata of item [primary]. */
        fun metadata(primary: Int) {
            metadata.exif?.let { block ->
                // The EXIF orientation follows irot and imir, so a reader of either sees one orientation.
                val exif = if (MetadataIo.orientation(block) != null) MetadataIo.withOrientation(block, options.orientation.exifValue) ?: block else block
                // ISO/IEC 23008-12 A.2.1: the offset from the end of these 4 bytes to the TIFF header, after "Exif\0\0" as cameras write it.
                val body = byteArrayOf(0, 0, 0, 6) + "Exif".encodeToByteArray() + byteArrayOf(0, 0) + exif
                heif.reference("cdsc", heif.addItem("Exif", body, emptyList()), intArrayOf(primary))
            }
            metadata.xmp?.let { xmp ->
                // ISO/IEC 23008-12 A.3: XMP is a 'mime' item of content type application/rdf+xml.
                heif.reference("cdsc", heif.addItem("mime", xmp.encodeToByteArray(), emptyList(), contentType = "application/rdf+xml"), intArrayOf(primary))
            }
        }

        /**
         * The file. The major brand is the primary image's codec brand (ISO/IEC 23008-12 Annex B: `heic` for
         * the Main and Main Still Picture profiles, `heix` for Main 10 and the range extensions), and the
         * compatible brands add `mif1`, every codec brand used, and `miaf`.
         */
        fun build(): ByteArray {
            return heif.build(brands.first(), listOf("mif1") + brands + "miaf")
        }
    }

    /** The 'irot' and 'imir' that make a reader show the stored pixels turned as [o] says, as AvifDecoder reads them. */
    private fun orientation(o: Orientation): List<Pair<ByteArray, Boolean>> {
        // irot turns anticlockwise in quarters; imir axis 0 exchanges top and bottom, axis 1 left and right.
        val (angle, axis) = when (o) {
            Orientation.Normal -> null to null
            Orientation.Rotate90 -> 3 to null
            Orientation.Rotate180 -> 2 to null
            Orientation.Rotate270 -> 1 to null
            Orientation.FlipHorizontal -> null to 1
            Orientation.FlipVertical -> null to 0
            Orientation.Transpose -> 3 to 1
            Orientation.Transverse -> 1 to 1
        }
        // Transformative properties are essential (ISO/IEC 23008-12 6.5.1), rotation before mirror (MIAF, ISO/IEC 23000-22).
        return listOfNotNull(
            angle?.let { box("irot", byteArrayOf(it.toByte())) to true },
            axis?.let { box("imir", byteArrayOf(it.toByte())) to true },
        )
    }

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())

    private fun box(type: String, payload: ByteArray): ByteArray = HeifWriter.Sink().apply { bytes(payload) }.box(type)
    private fun fullBox(type: String, payload: ByteArray): ByteArray = HeifWriter.Sink().apply { bytes(payload) }.fullBox(type, 0, 0)

    /** `ispe`: every image item has one (ISO/IEC 23008-12 6.5.3, and MIAF, ISO/IEC 23000-22), the size its coded data decodes to. */
    private fun ispe(w: Int, h: Int) = fullBox("ispe", be32(w) + be32(h))

    /** `pixi`, which MIAF (ISO/IEC 23000-22) asks of every image item: [channels] of [depth] bits. */
    private fun pixi(channels: Int, depth: Int) = fullBox("pixi", byteArrayOf(channels.toByte()) + ByteArray(channels) { depth.toByte() })

    private fun colr(primaries: Int, transfer: Int, matrix: Int) =
        box("colr", "nclx".encodeToByteArray() + be16(primaries) + be16(transfer) + be16(matrix) + byteArrayOf(0x80.toByte()))

    /**
     * A clean aperture of [w] by [h] at the top left of a [cw] by [ch] picture: its centre sits half the cut
     * columns and rows left of and above the picture's (ISO/IEC 14496-12 12.1.4), as a fraction over 2.
     */
    private fun clap(w: Int, h: Int, cw: Int, ch: Int) =
        box("clap", be32(w) + be32(1) + be32(h) + be32(1) + be32(-(cw - w)) + be32(2) + be32(-(ch - h)) + be32(2))

    /** The payload of a `grid` item (ISO/IEC 23008-12 6.6.2.3.2), with 32-bit sizes only where 16 bits do not hold them. */
    private fun gridDescription(rows: Int, columns: Int, w: Int, h: Int): ByteArray {
        val wide = w > 0xFFFF || h > 0xFFFF
        return byteArrayOf(0, if (wide) 1 else 0, (rows - 1).toByte(), (columns - 1).toByte()) +
            if (wide) be32(w) + be32(h) else be16(w) + be16(h)
    }
}
