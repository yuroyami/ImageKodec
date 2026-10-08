package io.github.yuroyami.imagekodec.codec.avif

import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.UnsupportedImageException

/** An item property of a HEIF file (ISO/IEC 23008-12 section 6.5) that AVIF reading needs. */
internal sealed class AvifProperty {
    /** `av1C`: the AV1 codec configuration, with any configuration OBUs it carries. */
    class Av1Config(
        val profile: Int,
        val highBitdepth: Boolean,
        val twelveBit: Boolean,
        val monochrome: Boolean,
        val subX: Int,
        val subY: Int,
        val configObus: ByteArray,
    ) : AvifProperty() {
        val bitDepth: Int get() = if (twelveBit) 12 else if (highBitdepth) 10 else 8
    }

    /** `ispe`: the image's width and height. */
    class Extent(val width: Long, val height: Long) : AvifProperty()

    /** `pixi`: the bits of each channel. */
    class PixelInfo(val depths: IntArray) : AvifProperty()

    /** `colr` of type `nclx`: the coding-independent code points of ITU-T H.273. */
    class Nclx(val primaries: Int, val transfer: Int, val matrix: Int, val fullRange: Boolean) : AvifProperty()

    /** `colr` of type `prof` or `rICC`: an ICC profile. */
    class Icc(val profile: ByteArray) : AvifProperty()

    /** `auxC`: the kind of auxiliary image, as a URN. */
    class AuxType(val urn: String) : AvifProperty()

    /** `irot`: a turn of [angle] times 90 degrees anticlockwise. */
    class Rotation(val angle: Int) : AvifProperty()

    /** `imir`: a mirror, top and bottom exchanged for axis 0, left and right for 1. */
    class Mirror(val axis: Int) : AvifProperty()

    /** `clap`: the clean aperture, as four fractions. */
    class CleanAperture(
        val widthN: Long, val widthD: Long, val heightN: Long, val heightD: Long,
        val horizOffN: Long, val horizOffD: Long, val vertOffN: Long, val vertOffD: Long,
    ) : AvifProperty()

    /** `a1op`: the AV1 operating point to decode. */
    class OperatingPoint(val index: Int) : AvifProperty()

    /** `lsel`: the spatial layer to show, or 0xFFFF for the highest. */
    class LayerSelector(val layer: Int) : AvifProperty()

    /** Any other property: [known] when its meaning needs no action from a reader that shows the image. */
    class Other(val type: Int, val known: Boolean) : AvifProperty()
}

/** Where an item's bytes are (`iloc`, ISO/IEC 14496-12 section 8.11.3). */
internal class AvifLocation(val method: Int, val dataReference: Int, val baseOffset: Long, val extents: List<LongArray>)

/** An item of the `meta` box: its [id], its four-character [type], and its properties in association order. */
internal class AvifItem(val id: Long, val type: Int, val hidden: Boolean) {
    val properties = ArrayList<AvifProperty>()

    /** The essential properties this reader does not know, which make the item undecodable. */
    val unknownEssential = ArrayList<Int>()
    var location: AvifLocation? = null

    inline fun <reified T : AvifProperty> property(): T? = properties.firstOrNull { it is T } as T?
}

/** An `iref` entry: [from] refers to each of [to] with reference [type]. */
internal class AvifReference(val type: Int, val from: Long, val to: LongArray)

/** An entity group of `grpl` (ISO/IEC 14496-12 section 8.18). */
internal class AvifGroup(val type: Int, val id: Long, val entities: LongArray)

/** A track of `moov`, with its sample table resolved to the byte span of each sample. */
internal class AvifTrack(
    val id: Long,
    val handler: Int,
    val timescale: Long,
    val sampleEntry: Int,
    val width: Int,
    val height: Int,
    val properties: List<AvifProperty>,
    val auxType: String?,
    val references: List<AvifReference>,
    val offsets: LongArray,
    val sizes: LongArray,
    val durations: LongArray,
    val sync: Set<Int>?,
    /**
     * How many times the sequence plays, 0 for ever: an edit list that repeats plays as many times
     * as its segment fits the track's duration, rounded up, and for ever past Int.MAX_VALUE plays or
     * with an indefinite duration, as libavif counts it; one that does not repeat plays once; and
     * with no edit list, which leaves it open, a sequence loops, as browsers play it.
     */
    val playCount: Long,
)

/**
 * The structure of an AVIF file (AV1 Image File Format 1.1, over HEIF and ISO base media):
 * its brands, the items of its `meta` box with their locations, properties and references,
 * its entity groups, and the tracks of an image sequence.
 */
internal class AvifContainer(
    val data: ByteArray,
    val majorBrand: Int,
    val brands: Set<Int>,
    val items: Map<Long, AvifItem>,
    val primary: Long?,
    val references: List<AvifReference>,
    val groups: List<AvifGroup>,
    val tracks: List<AvifTrack>,
    private val idat: IsoBox?,
) {
    fun referencesFrom(id: Long, type: Int): List<Long> =
        references.filter { it.type == type && it.from == id }.flatMap { it.to.toList() }

    fun referencesTo(id: Long, type: Int): List<Long> =
        references.filter { it.type == type && id in it.to }.map { it.from }

    /** The bytes of [item], or null when they reach past the end of the data, as in a file not yet read whole. */
    fun itemDataOrNull(item: AvifItem): ByteArray? = try {
        itemData(item)
    } catch (_: Truncated) {
        null
    }

    /** The bytes of sample [index] of [track], or null when they reach past the end of the data. */
    fun sampleDataOrNull(track: AvifTrack, index: Int): ByteArray? = try {
        sampleData(track, index)
    } catch (_: Truncated) {
        null
    }

    /** Data reaching past the end of the file. */
    private class Truncated(message: String) : ImageDecodeException(message)

    /** The bytes of [item], its extents joined in order. */
    fun itemData(item: AvifItem): ByteArray {
        val loc = item.location ?: throw ImageDecodeException("AVIF: item ${item.id} has no location")
        if (loc.dataReference != 0) throw UnsupportedImageException("AVIF item data in another file")
        val (source, sourceStart, sourceEnd) = when (loc.method) {
            0 -> Triple(data, 0, data.size)
            1 -> {
                val box = idat ?: throw ImageDecodeException("AVIF: item ${item.id} is in an 'idat' box the file lacks")
                Triple(data, box.start, box.end)
            }
            else -> throw UnsupportedImageException("AVIF item data built from other items (iloc construction method ${loc.method})")
        }
        val sourceSize = (sourceEnd - sourceStart).toLong()
        var total = 0L
        val spans = loc.extents.map { e ->
            val offset = loc.baseOffset + e[0]
            val length = if (e[1] == 0L) sourceSize - offset else e[1]
            if (offset < 0 || length < 0) throw ImageDecodeException("AVIF: item ${item.id} has a negative extent")
            if (offset > sourceSize || length > sourceSize - offset) {
                if (loc.method == 1) throw ImageDecodeException("AVIF: item ${item.id} extends past its 'idat' box")
                throw Truncated("AVIF: item ${item.id} extends past the end of the file")
            }
            total += length
            offset to length
        }
        if (total > sourceSize) throw Truncated("AVIF: item ${item.id} is larger than the file")
        val out = ByteArray(total.toInt())
        var at = 0
        for ((offset, length) in spans) {
            source.copyInto(out, at, sourceStart + offset.toInt(), sourceStart + offset.toInt() + length.toInt())
            at += length.toInt()
        }
        return out
    }

    /** The bytes of sample [index] of [track]. */
    fun sampleData(track: AvifTrack, index: Int): ByteArray {
        val offset = track.offsets[index]
        val size = track.sizes[index]
        if (offset < 0 || offset > data.size || size > data.size - offset) {
            throw Truncated("AVIF: sample $index of track ${track.id} extends past the end of the file")
        }
        return data.copyOfRange(offset.toInt(), (offset + size).toInt())
    }

    companion object {
        val FTYP = fourcc("ftyp")
        val META = fourcc("meta")
        val MOOV = fourcc("moov")
        val AVIF = fourcc("avif")
        val AVIS = fourcc("avis")
        val AV01 = fourcc("av01")
        val GRID = fourcc("grid")
        val IOVL = fourcc("iovl")
        val TMAP = fourcc("tmap")
        val SATO = fourcc("sato")
        val PICT = fourcc("pict")
        val AUXV = fourcc("auxv")
        val AUXL = fourcc("auxl")
        val DIMG = fourcc("dimg")
        val PREM = fourcc("prem")
        val ALTR = fourcc("altr")

        const val ALPHA_URN = "urn:mpeg:mpegB:cicp:systems:auxiliary:alpha"
        const val ALPHA_URN_HEVC = "urn:mpeg:hevc:2015:auxid:1"

        /**
         * True when [data] starts with an `ftyp` box naming the `avif` or `avis` brand, as its major
         * brand or among its compatible brands, as libavif checks. A box cut short is read as far as
         * it goes, so the first bytes of a file are enough when the major brand says it.
         */
        fun isAvif(data: ByteArray): Boolean {
            fun u32(at: Int): Long {
                var v = 0L
                for (i in 0 until 4) v = (v shl 8) or (data[at + i].toLong() and 0xFF)
                return v
            }
            if (data.size < 12 || u32(4).toInt() != FTYP) return false
            val major = u32(8).toInt()
            if (major == AVIF || major == AVIS) return true
            val size = u32(0)
            if (size < 16) return false
            val end = minOf(size, data.size.toLong()).toInt()
            var at = 16
            while (at + 4 <= end) {
                val brand = u32(at).toInt()
                if (brand == AVIF || brand == AVIS) return true
                at += 4
            }
            return false
        }

        /** The descriptive properties whose meaning a reader that shows the image may leave aside. */
        private val KNOWN_DESCRIPTIVE = setOf(
            "pasp", "clli", "mdcv", "cclv", "amve", "a1lx", "rloc", "udes", "altt", "ccst", "reve", "ndwt",
        ).map { fourcc(it) }.toSet()

        fun parse(data: ByteArray): AvifContainer {
            val top = IsoReader(data, 0, data.size, "file")
            val boxes = ArrayList<IsoBox>()
            // A file cut short still has its leading boxes: keep those whole, as a reader of a
            // partial download does, and let an item reaching past the end fail when it is read.
            while (top.pos + 8 <= data.size) {
                val start = top.pos
                var size = top.u32()
                val type = top.fourcc()
                if (size == 1L) {
                    if (top.remaining < 8) break
                    size = top.u64()
                } else if (size == 0L) {
                    size = (data.size - start).toLong()
                }
                val header = top.pos - start
                if (size < header) throw ImageDecodeException("AVIF: a '${fourccName(type)}' box of $size bytes")
                if (size > data.size - start) break
                boxes += IsoBox(type, top.pos, start + size.toInt())
                top.pos = start + size.toInt()
            }
            val ftyp = boxes.firstOrNull() ?: throw ImageDecodeException("AVIF: no 'ftyp' box")
            if (ftyp.type != FTYP) throw ImageDecodeException("AVIF: the file starts with '${fourccName(ftyp.type)}', not 'ftyp'")
            val fr = IsoReader(data, ftyp)
            val major = fr.fourcc()
            fr.u32()
            val brands = HashSet<Int>()
            brands += major
            while (fr.remaining >= 4) brands += fr.fourcc()

            val items = LinkedHashMap<Long, AvifItem>()
            val references = ArrayList<AvifReference>()
            val groups = ArrayList<AvifGroup>()
            var primary: Long? = null
            var idat: IsoBox? = null
            val metas = boxes.filter { it.type == META }
            if (metas.size > 1) throw ImageDecodeException("AVIF: more than one 'meta' box")
            metas.firstOrNull()?.let { meta ->
                val children = IsoReader.children(data, meta, full = true)
                val hdlr = children.firstOrNull { it.type == fourcc("hdlr") } ?: throw ImageDecodeException("AVIF: 'meta' has no 'hdlr' box")
                val hr = IsoReader(data, hdlr)
                hr.fullBox()
                hr.u32()
                val handler = hr.fourcc()
                if (handler != PICT) throw ImageDecodeException("AVIF: the 'meta' handler is '${fourccName(handler)}', not 'pict'")
                for (box in children) {
                    when (fourccName(box.type)) {
                        "pitm" -> {
                            val r = IsoReader(data, box)
                            val (version, _) = r.fullBox()
                            primary = if (version == 0) r.u16().toLong() else r.u32()
                        }
                        "iinf" -> readItemInfo(data, box, items)
                        "idat" -> idat = box
                        "iref" -> readReferences(data, box, references)
                        "grpl" -> for (g in IsoReader.children(data, box)) {
                            val r = IsoReader(data, g)
                            r.fullBox()
                            val id = r.u32()
                            val count = r.u32()
                            if (count > r.remaining / 4) r.fail("lists $count entities")
                            groups += AvifGroup(g.type, id, LongArray(count.toInt()) { r.u32() })
                        }
                        else -> {}
                    }
                }
                // Locations and properties attach to the items iinf declared.
                children.firstOrNull { it.type == fourcc("iloc") }?.let { readLocations(data, it, items) }
                children.firstOrNull { it.type == fourcc("iprp") }?.let { readProperties(data, it, items) }
            }
            val tracks = boxes.filter { it.type == MOOV }.flatMap { readTracks(data, it) }
            return AvifContainer(data, major, brands, items, primary, references, groups, tracks, idat)
        }

        private fun readItemInfo(data: ByteArray, box: IsoBox, items: MutableMap<Long, AvifItem>) {
            val r = IsoReader(data, box)
            val (version, _) = r.fullBox()
            if (version == 0) r.u16() else r.u32()
            for (infe in r.boxes()) {
                if (infe.type != fourcc("infe")) continue
                val ir = IsoReader(data, infe)
                val (v, flags) = ir.fullBox()
                // Versions 0 and 1 carry no item type, and no AVIF image is described with them.
                if (v < 2) continue
                val id = if (v == 2) ir.u16().toLong() else ir.u32()
                ir.u16()
                val type = ir.fourcc()
                if (id in items) ir.fail("declares item $id twice")
                items[id] = AvifItem(id, type, (flags and 1) != 0)
            }
        }

        private fun readReferences(data: ByteArray, box: IsoBox, out: MutableList<AvifReference>) {
            val r = IsoReader(data, box)
            val (version, _) = r.fullBox()
            for (ref in r.boxes()) {
                val rr = IsoReader(data, ref)
                val from = if (version == 0) rr.u16().toLong() else rr.u32()
                val count = rr.u16()
                val to = LongArray(count) { if (version == 0) rr.u16().toLong() else rr.u32() }
                out += AvifReference(ref.type, from, to)
            }
        }

        private fun readLocations(data: ByteArray, box: IsoBox, items: Map<Long, AvifItem>) {
            val r = IsoReader(data, box)
            val (version, _) = r.fullBox()
            if (version > 2) r.fail("version $version")
            val sizes = r.u16()
            val offsetSize = sizes ushr 12
            val lengthSize = (sizes ushr 8) and 15
            val baseOffsetSize = (sizes ushr 4) and 15
            val indexSize = if (version >= 1) sizes and 15 else 0
            val count = if (version < 2) r.u16().toLong() else r.u32()
            for (i in 0 until count) {
                val id = if (version < 2) r.u16().toLong() else r.u32()
                val method = if (version >= 1) r.u16() and 15 else 0
                val dataReference = r.u16()
                val baseOffset = r.sized(baseOffsetSize)
                val extentCount = r.u16()
                val extents = ArrayList<LongArray>(minOf(extentCount, r.remaining))
                for (e in 0 until extentCount) {
                    r.sized(indexSize)
                    val offset = r.sized(offsetSize)
                    val length = r.sized(lengthSize)
                    extents += longArrayOf(offset, length)
                }
                val item = items[id] ?: continue
                if (item.location != null) r.fail("locates item $id twice")
                item.location = AvifLocation(method, dataReference, baseOffset, extents)
            }
        }

        private fun readProperties(data: ByteArray, box: IsoBox, items: Map<Long, AvifItem>) {
            val children = IsoReader.children(data, box)
            val ipco = children.firstOrNull { it.type == fourcc("ipco") } ?: throw ImageDecodeException("AVIF: 'iprp' has no 'ipco' box")
            val properties = IsoReader.children(data, ipco).map { readProperty(data, it) }
            for (ipma in children.filter { it.type == fourcc("ipma") }) {
                val r = IsoReader(data, ipma)
                val (version, flags) = r.fullBox()
                val count = r.u32()
                for (i in 0 until count) {
                    val id = if (version < 1) r.u16().toLong() else r.u32()
                    val associations = r.u8()
                    val item = items[id]
                    for (a in 0 until associations) {
                        val essential: Boolean
                        val index: Int
                        if ((flags and 1) != 0) {
                            val v = r.u16()
                            essential = (v and 0x8000) != 0
                            index = v and 0x7FFF
                        } else {
                            val v = r.u8()
                            essential = (v and 0x80) != 0
                            index = v and 0x7F
                        }
                        if (index == 0 || item == null) continue
                        if (index > properties.size) r.fail("associates property $index of ${properties.size}")
                        val p = properties[index - 1]
                        item.properties += p
                        if (essential && p is AvifProperty.Other && !p.known) item.unknownEssential += p.type
                    }
                }
            }
        }

        fun readProperty(data: ByteArray, box: IsoBox): AvifProperty {
            val r = IsoReader(data, box)
            return when (fourccName(box.type)) {
                "av1C" -> {
                    val b0 = r.u8()
                    if (b0 != 0x81) r.fail("has marker and version $b0, not 0x81")
                    val b1 = r.u8()
                    val b2 = r.u8()
                    r.u8()
                    AvifProperty.Av1Config(
                        profile = b1 ushr 5,
                        highBitdepth = (b2 and 0x40) != 0,
                        twelveBit = (b2 and 0x20) != 0,
                        monochrome = (b2 and 0x10) != 0,
                        subX = (b2 ushr 3) and 1,
                        subY = (b2 ushr 2) and 1,
                        configObus = r.bytes(r.remaining),
                    )
                }
                "ispe" -> {
                    r.fullBox()
                    AvifProperty.Extent(r.u32(), r.u32())
                }
                "pixi" -> {
                    r.fullBox()
                    val n = r.u8()
                    AvifProperty.PixelInfo(IntArray(n) { r.u8() })
                }
                "colr" -> when (fourccName(r.fourcc())) {
                    "nclx" -> {
                        val p = r.u16()
                        val t = r.u16()
                        val m = r.u16()
                        AvifProperty.Nclx(p, t, m, (r.u8() and 0x80) != 0)
                    }
                    "prof", "rICC" -> AvifProperty.Icc(r.bytes(r.remaining))
                    else -> AvifProperty.Other(box.type, known = true)
                }
                "auxC" -> {
                    r.fullBox()
                    AvifProperty.AuxType(r.string())
                }
                "irot" -> AvifProperty.Rotation(r.u8() and 3)
                "imir" -> AvifProperty.Mirror(r.u8() and 1)
                "clap" -> AvifProperty.CleanAperture(
                    r.u32(), r.u32(), r.u32(), r.u32(),
                    r.s32().toLong(), r.u32(), r.s32().toLong(), r.u32(),
                )
                "a1op" -> AvifProperty.OperatingPoint(r.u8())
                "lsel" -> AvifProperty.LayerSelector(r.u16())
                else -> AvifProperty.Other(box.type, known = box.type in KNOWN_DESCRIPTIVE)
            }
        }

        private fun readTracks(data: ByteArray, moov: IsoBox): List<AvifTrack> {
            val out = ArrayList<AvifTrack>()
            for (trak in IsoReader.children(data, moov)) {
                if (trak.type != fourcc("trak")) continue
                out += readTrack(data, trak) ?: continue
            }
            return out
        }

        private fun child(data: ByteArray, box: IsoBox?, type: String, full: Boolean = false): IsoBox? =
            box?.let { b -> IsoReader.children(data, b, full).firstOrNull { it.type == fourcc(type) } }

        /** A 64-bit count, or -1 when it does not fit 63 bits, as the indefinite duration of all ones does not. */
        private fun wide(r: IsoReader): Long {
            val hi = r.u32()
            val lo = r.u32()
            return if (hi >= 0x80000000L) -1L else (hi shl 32) or lo
        }

        private fun readTrack(data: ByteArray, trak: IsoBox): AvifTrack? {
            val tkhd = child(data, trak, "tkhd") ?: return null
            val tr = IsoReader(data, tkhd)
            val (tv, _) = tr.fullBox()
            tr.skip(if (tv == 1) 16 else 8)
            val id = tr.u32()
            tr.skip(4)
            // A duration of all ones is indefinite.
            val trackDuration = if (tv == 1) wide(tr) else tr.u32().let { if (it == 0xFFFFFFFFL) -1L else it }
            val references = ArrayList<AvifReference>()
            child(data, trak, "tref")?.let { tref ->
                for (ref in IsoReader.children(data, tref)) {
                    val rr = IsoReader(data, ref)
                    references += AvifReference(ref.type, id, LongArray(rr.remaining / 4) { rr.u32() })
                }
            }
            var playCount = 0L
            child(data, child(data, trak, "edts"), "elst")?.let {
                val er = IsoReader(data, it)
                val (ev, flags) = er.fullBox()
                playCount = if ((flags and 1) == 0) 1L else {
                    val segment = if (er.remaining >= 4 + (if (ev == 1) 8 else 4)) {
                        er.u32()
                        if (ev == 1) wide(er) else er.u32()
                    } else 0L
                    // libavif reads a repetition count past Int.MAX_VALUE as for ever.
                    val plays = if (trackDuration <= 0L || segment <= 0L) 0L
                    else trackDuration / segment + (if (trackDuration % segment != 0L) 1 else 0)
                    if (plays - 1 > Int.MAX_VALUE) 0L else plays
                }
            }
            val mdia = child(data, trak, "mdia") ?: return null
            val mdhd = child(data, mdia, "mdhd") ?: return null
            val mr = IsoReader(data, mdhd)
            val (mv, _) = mr.fullBox()
            mr.skip(if (mv == 1) 16 else 8)
            val timescale = mr.u32()
            val hdlr = child(data, mdia, "hdlr") ?: return null
            val hr = IsoReader(data, hdlr)
            hr.fullBox()
            hr.u32()
            val handler = hr.fourcc()
            val stbl = child(data, child(data, mdia, "minf"), "stbl") ?: return null
            val stsd = child(data, stbl, "stsd") ?: return null
            val sr = IsoReader(data, stsd)
            sr.fullBox()
            val entries = sr.u32()
            if (entries < 1) return null
            val entry = sr.boxes().firstOrNull() ?: return null
            val properties = ArrayList<AvifProperty>()
            var auxType: String? = null
            var width = 0
            var height = 0
            if (entry.type == AV01) {
                val er = IsoReader(data, entry)
                // The VisualSampleEntry fields up to its child boxes, with the size among them.
                er.skip(24)
                width = er.u16()
                height = er.u16()
                er.skip(50)
                for (b in er.boxes()) {
                    if (b.type == fourcc("auxi")) {
                        val ar = IsoReader(data, b)
                        ar.fullBox()
                        auxType = ar.string()
                    } else {
                        properties += readProperty(data, b)
                    }
                }
            }
            // The sample table: sizes, chunk offsets and how samples fill the chunks.
            val stsz = child(data, stbl, "stsz")
            val sizes: LongArray = if (stsz != null) {
                val r = IsoReader(data, stsz)
                r.fullBox()
                val fixed = r.u32()
                val count = r.u32()
                if (fixed == 0L && count > r.remaining / 4) r.fail("lists $count samples")
                if (fixed != 0L && count > data.size) r.fail("lists $count samples")
                LongArray(count.toInt()) { if (fixed != 0L) fixed else r.u32() }
            } else {
                val stz2 = child(data, stbl, "stz2") ?: return null
                val r = IsoReader(data, stz2)
                r.fullBox()
                r.skip(3)
                val field = r.u8()
                val count = r.u32()
                if (field !in listOf(4, 8, 16) || count > r.remaining.toLong() * 8 / field) r.fail("lists $count samples of $field bits")
                var nibble = -1
                LongArray(count.toInt()) {
                    when (field) {
                        16 -> r.u16().toLong()
                        8 -> r.u8().toLong()
                        else -> if (nibble < 0) {
                            val b = r.u8()
                            nibble = b and 15
                            (b ushr 4).toLong()
                        } else {
                            val v = nibble.toLong()
                            nibble = -1
                            v
                        }
                    }
                }
            }
            val chunkOffsets: LongArray = child(data, stbl, "stco")?.let {
                val r = IsoReader(data, it)
                r.fullBox()
                val n = r.u32()
                if (n > r.remaining / 4) r.fail("lists $n chunks")
                LongArray(n.toInt()) { r.u32() }
            } ?: child(data, stbl, "co64")?.let {
                val r = IsoReader(data, it)
                r.fullBox()
                val n = r.u32()
                if (n > r.remaining / 8) r.fail("lists $n chunks")
                LongArray(n.toInt()) { r.u64() }
            } ?: return null
            val stsc = child(data, stbl, "stsc") ?: return null
            val cr = IsoReader(data, stsc)
            cr.fullBox()
            val runs = cr.u32()
            if (runs > cr.remaining / 12) cr.fail("lists $runs runs")
            val firstChunks = LongArray(runs.toInt())
            val perChunk = LongArray(runs.toInt())
            for (i in 0 until runs.toInt()) {
                firstChunks[i] = cr.u32()
                perChunk[i] = cr.u32()
                cr.u32()
            }
            val offsets = LongArray(sizes.size)
            var sample = 0
            var run = 0
            for (chunk in chunkOffsets.indices) {
                while (run + 1 < firstChunks.size && firstChunks[run + 1] <= chunk + 1) run++
                if (firstChunks.isEmpty()) break
                var offset = chunkOffsets[chunk]
                var n = 0L
                while (n < perChunk[run] && sample < sizes.size) {
                    offsets[sample] = offset
                    offset += sizes[sample]
                    sample++
                    n++
                }
                if (sample == sizes.size) break
            }
            if (sample < sizes.size) throw ImageDecodeException("AVIF: track $id places $sample of its ${sizes.size} samples")
            val durations = LongArray(sizes.size)
            child(data, stbl, "stts")?.let {
                val r = IsoReader(data, it)
                r.fullBox()
                val n = r.u32()
                if (n > r.remaining / 8) r.fail("lists $n runs")
                var s = 0
                for (i in 0 until n.toInt()) {
                    val count = r.u32()
                    val delta = r.u32()
                    var k = 0L
                    while (k < count && s < durations.size) {
                        durations[s++] = delta
                        k++
                    }
                }
            }
            val sync = child(data, stbl, "stss")?.let {
                val r = IsoReader(data, it)
                r.fullBox()
                val n = r.u32()
                if (n > r.remaining / 4) r.fail("lists $n sync samples")
                (0 until n.toInt()).map { r.u32().toInt() - 1 }.toSet()
            }
            return AvifTrack(id, handler, timescale, entry.type, width, height, properties, auxType, references, offsets, sizes, durations, sync, playCount)
        }
    }
}
