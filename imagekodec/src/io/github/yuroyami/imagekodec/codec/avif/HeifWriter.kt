package io.github.yuroyami.imagekodec.codec.avif

/**
 * Writes a HEIF file of still images (ISO/IEC 23008-12, over the box structure of ISO/IEC 14496-12):
 * `ftyp`, then `meta` with `hdlr`, `pitm`, `iloc`, `iinf`, `iref`, `iprp` (`ipco` and `ipma`) and
 * `idat`, then `mdat` with the data of the items in the order they were added (#174). Image sequences
 * add a `moov` box of tracks before `mdat` (#177), whose samples follow the items' data, one chunk a track;
 * an item may share its data with a sample, as the first picture of a sequence does.
 *
 * A property is stored once in `ipco` however many items it is associated with, as the tiles of a grid
 * share their configuration and colour. Item IDs, references and associations switch to their 32-bit
 * forms only when an ID passes 16 bits, and `ipma` to 15-bit indices only past 127 properties.
 */
internal class HeifWriter {

    private class Association(val index: Int, val essential: Boolean)

    private class Item(
        val id: Int,
        val type: String,
        val data: ByteArray,
        val hidden: Boolean,
        val contentType: String?,
        val inIdat: Boolean,
        val associations: List<Association>,
        /** The track index and sample whose bytes are this item's data, or null for data of its own. */
        val sample: Pair<Int, Int>? = null,
    ) {
        /** Whether the item's data is written in `mdat` as its own bytes. */
        val ownMdat: Boolean get() = !inIdat && sample == null
    }

    /**
     * An image sequence track (#177): the sample entry box of its `stsd`, its samples in decoding order with their
     * [durations] in [timescale] units, [compositionOffsets] where pictures are reordered, which samples are [sync]
     * samples, its picture size, how many times it plays ([playCount], 0 for ever), its [handler] (`pict`, or `auxv`
     * for an auxiliary sequence such as alpha) and the tracks its auxiliary images belong to ([auxiliaryOf]).
     */
    class Track(
        val sampleEntry: ByteArray,
        val samples: List<ByteArray>,
        val durations: LongArray,
        val compositionOffsets: LongArray?,
        val sync: BooleanArray,
        val width: Int,
        val height: Int,
        val timescale: Long,
        val playCount: Long,
        val handler: String = "pict",
        val auxiliaryOf: IntArray? = null,
    ) {
        init {
            require(samples.isNotEmpty() && durations.size == samples.size && sync.size == samples.size) { "a track's samples, durations and sync flags" }
            require(compositionOffsets == null || compositionOffsets.size == samples.size) { "a track's composition offsets" }
            require(timescale in 1..0xFFFFFFFFL) { "a track timescale of $timescale" }
        }

        val bytes: Long get() = samples.sumOf { it.size.toLong() }
    }

    private val items = ArrayList<Item>()
    private val properties = ArrayList<ByteArray>()
    private val propertyIndex = HashMap<Int, MutableList<Int>>()
    private val references = ArrayList<Triple<String, Int, IntArray>>()
    private val tracks = ArrayList<Track>()

    /** Adds [track] and returns its track ID: 1 for the first, and so on. */
    fun addTrack(track: Track): Int {
        tracks += track
        return tracks.size
    }

    /** The ID of the primary item. */
    var primary: Int = 0

    /** The 1-based `ipco` index of the property box [box], adding it the first time it is seen. */
    private fun property(box: ByteArray): Int {
        val hash = box.contentHashCode()
        val same = propertyIndex.getOrPut(hash) { ArrayList() }
        for (i in same) if (properties[i - 1].contentEquals(box)) return i
        properties += box
        same += properties.size
        return properties.size
    }

    /**
     * Adds an item of [type] whose content is [data], kept in `idat` when [inIdat] (as a grid's small
     * description is) and in `mdat` otherwise, with [itemProperties] (each a whole box and whether it is
     * essential) associated in the order given. Returns the item's ID: 1 for the first, and so on.
     */
    fun addItem(
        type: String,
        data: ByteArray,
        itemProperties: List<Pair<ByteArray, Boolean>>,
        hidden: Boolean = false,
        contentType: String? = null,
        inIdat: Boolean = false,
        /** The ID of a track added before, and the index of its sample, whose bytes [data] are (#177). */
        sharedSample: Pair<Int, Int>? = null,
    ): Int {
        require(type.length == 4) { "item type '$type'" }
        sharedSample?.let { (track, sample) ->
            require(track in 1..tracks.size && tracks[track - 1].samples[sample] === data) { "item data shared with sample $sample of track $track" }
        }
        val id = items.size + 1
        val shared = sharedSample?.let { (track, sample) -> (track - 1) to sample }
        items += Item(id, type, data, hidden, contentType, inIdat, itemProperties.map { (box, essential) -> Association(property(box), essential) }, shared)
        return id
    }

    /** A reference of [type] from item [from] to items [to], in that order. */
    fun reference(type: String, from: Int, to: IntArray) {
        require(to.size in 1..0xFFFF) { "a '$type' reference to ${to.size} items" }
        references += Triple(type, from, to)
    }

    /** The file, its `ftyp` naming [majorBrand] and [compatibleBrands]. */
    fun build(majorBrand: String, compatibleBrands: List<String>): ByteArray {
        val dataSize = items.filter { it.sample == null }.sumOf { it.data.size.toLong() } + tracks.sumOf { it.bytes }
        // Checked before any offset is computed in 32 bits.
        require(dataSize <= MAX_FILE - (1L shl 24)) { "HEIF items of $dataSize bytes pass what one array holds" }
        val ftyp = Sink().apply {
            ascii(majorBrand)
            u32(0)
            for (b in compatibleBrands) ascii(b)
        }.box("ftyp")
        val meta0 = meta(0)
        val moov0 = if (tracks.isEmpty()) ByteArray(0) else moov(0)
        val mdatStart = ftyp.size + meta0.size + moov0.size
        val meta = meta(mdatStart + 8)
        check(meta.size == meta0.size) { "the meta box changed size with its offsets" }
        val moov = if (tracks.isEmpty()) moov0 else moov(mdatStart + 8L)
        check(moov.size == moov0.size) { "the moov box changed size with its offsets" }
        val mdatSize = 8L + items.filter { it.ownMdat }.sumOf { it.data.size.toLong() } + tracks.sumOf { it.bytes }
        require(mdatStart + mdatSize <= MAX_FILE) { "a HEIF file of ${mdatStart + mdatSize} bytes passes what one array holds" }
        val out = ByteArray((mdatStart + mdatSize).toInt())
        ftyp.copyInto(out, 0)
        meta.copyInto(out, ftyp.size)
        moov.copyInto(out, ftyp.size + meta.size)
        var at = mdatStart
        out[at] = (mdatSize ushr 24).toByte()
        out[at + 1] = (mdatSize ushr 16).toByte()
        out[at + 2] = (mdatSize ushr 8).toByte()
        out[at + 3] = mdatSize.toByte()
        "mdat".encodeToByteArray().copyInto(out, at + 4)
        at += 8
        for (item in items) if (item.ownMdat) {
            item.data.copyInto(out, at)
            at += item.data.size
        }
        for (track in tracks) for (sample in track.samples) {
            sample.copyInto(out, at)
            at += sample.size
        }
        return out
    }

    /** Where each track's samples start, the items' own data being first from [mdatData]. */
    private fun trackStarts(mdatData: Long): LongArray {
        var at = mdatData + items.filter { it.ownMdat }.sumOf { it.data.size.toLong() }
        return LongArray(tracks.size) { t -> at.also { at += tracks[t].bytes } }
    }

    /** The file offset of sample [sample] of track index [track], the tracks' data starting at [starts]. */
    private fun sampleOffset(starts: LongArray, track: Int, sample: Int): Long {
        var at = starts[track]
        for (i in 0 until sample) at += tracks[track].samples[i].size
        return at
    }

    /** The `meta` box, with the data of the `mdat` items from offset [mdatData] of the file. */
    private fun meta(mdatData: Int): ByteArray {
        val wideIds = items.size > 0xFFFF
        val children = Sink()
        // hdlr: pre_defined, the handler type 'pict', three reserved words and an empty name (ISO/IEC 14496-12 8.4.3).
        children.bytes(Sink().apply { u32(0); ascii("pict"); u32(0); u32(0); u32(0); u8(0) }.fullBox("hdlr", 0, 0))
        children.bytes(Sink().apply { id(primary, wideIds) }.fullBox("pitm", if (wideIds) 1 else 0, 0))
        // iloc version 1 for construction_method, 2 for 32-bit IDs; 4-byte offsets and lengths, no base offset.
        val iloc = Sink()
        iloc.u16(0x4400)
        if (wideIds) iloc.u32(items.size) else iloc.u16(items.size)
        var mdatAt = mdatData
        var idatAt = 0
        val idat = Sink()
        val starts = trackStarts(mdatData.toLong())
        for (item in items) {
            iloc.id(item.id, wideIds)
            iloc.u16(if (item.inIdat) 1 else 0)
            iloc.u16(0)
            iloc.u16(1)
            if (item.inIdat) {
                iloc.u32(idatAt)
                idat.bytes(item.data)
                idatAt += item.data.size
            } else if (item.sample != null) {
                iloc.u32(sampleOffset(starts, item.sample.first, item.sample.second).toInt())
            } else {
                iloc.u32(mdatAt)
                mdatAt += item.data.size
            }
            iloc.u32(item.data.size)
        }
        children.bytes(iloc.fullBox("iloc", if (wideIds) 2 else 1, 0))
        val iinf = Sink()
        if (wideIds) iinf.u32(items.size) else iinf.u16(items.size)
        for (item in items) {
            // infe version 2, or 3 for a 32-bit ID: the ID, no protection, the type, an empty name (ISO/IEC 23008-12 9.2).
            val infe = Sink()
            infe.id(item.id, wideIds)
            infe.u16(0)
            infe.ascii(item.type)
            infe.u8(0)
            item.contentType?.let { infe.ascii(it); infe.u8(0) }
            iinf.bytes(infe.fullBox("infe", if (wideIds) 3 else 2, if (item.hidden) 1 else 0))
        }
        children.bytes(iinf.fullBox("iinf", if (wideIds) 1 else 0, 0))
        if (references.isNotEmpty()) {
            val iref = Sink()
            for ((type, from, to) in references) {
                val r = Sink()
                r.id(from, wideIds)
                r.u16(to.size)
                for (t in to) r.id(t, wideIds)
                iref.bytes(r.box(type))
            }
            children.bytes(iref.fullBox("iref", if (wideIds) 1 else 0, 0))
        }
        val ipco = Sink()
        for (p in properties) ipco.bytes(p)
        val wideIndex = properties.size > 127
        val ipma = Sink()
        val associated = items.filter { it.associations.isNotEmpty() }
        ipma.u32(associated.size)
        for (item in associated) {
            ipma.id(item.id, wideIds)
            require(item.associations.size <= 255) { "item ${item.id} has ${item.associations.size} properties" }
            ipma.u8(item.associations.size)
            for (a in item.associations) {
                if (wideIndex) ipma.u16((if (a.essential) 0x8000 else 0) or a.index) else ipma.u8((if (a.essential) 0x80 else 0) or a.index)
            }
        }
        children.bytes(Sink().apply { bytes(ipco.box("ipco")); bytes(ipma.fullBox("ipma", if (wideIds) 1 else 0, if (wideIndex) 1 else 0)) }.box("iprp"))
        if (idatAt > 0) children.bytes(idat.box("idat"))
        return children.fullBox("meta", 0, 0)
    }

    /**
     * The `moov` box of the tracks (ISO/IEC 14496-12 8.2 to 8.7), their samples from offset [mdatData] of the file
     * after the items' own data: `mvhd`, then a `trak` each with `tkhd`, `tref` for an auxiliary track, `edts` with
     * the play count, and `mdia` with `mdhd`, `hdlr`, and `minf` with `vmhd`, `dinf` and the sample table. A track's
     * samples are one chunk. The movie takes the first track's timescale, which every track here shares.
     */
    private fun moov(mdatData: Long): ByteArray {
        val starts = trackStarts(mdatData)
        val timescale = tracks[0].timescale
        val once = LongArray(tracks.size) { t -> tracks[t].durations.sum() }
        // How long each track plays with its repetitions: all ones, unknown, when it plays for ever.
        val presented = LongArray(tracks.size) { t -> if (tracks[t].playCount == 0L) -1L else once[t] * tracks[t].playCount }
        val out = Sink()
        val mvhd = Sink()
        val movieDuration = if (presented.any { it == -1L }) -1L else presented.max()
        val wideMovie = movieDuration > 0xFFFFFFFFL
        times(mvhd, wideMovie)
        mvhd.u32(timescale.toInt())
        duration(mvhd, movieDuration, wideMovie)
        mvhd.u32(0x00010000) // rate 1.0
        mvhd.u16(0x0100) // volume 1.0
        mvhd.u16(0)
        mvhd.u32(0)
        mvhd.u32(0)
        matrix(mvhd)
        for (i in 0 until 6) mvhd.u32(0) // pre_defined
        mvhd.u32(tracks.size + 1) // next_track_ID
        out.bytes(mvhd.fullBox("mvhd", if (wideMovie) 1 else 0, 0))
        for ((t, track) in tracks.withIndex()) {
            val trak = Sink()
            val tkhd = Sink()
            val wide = presented[t] > 0xFFFFFFFFL
            times(tkhd, wide)
            tkhd.u32(t + 1)
            tkhd.u32(0)
            duration(tkhd, presented[t], wide)
            tkhd.u32(0)
            tkhd.u32(0)
            tkhd.u32(0) // layer and alternate_group
            tkhd.u32(0) // volume and reserved
            matrix(tkhd)
            tkhd.u32(track.width shl 16)
            tkhd.u32(track.height shl 16)
            // flags: track_enabled and track_in_movie.
            trak.bytes(tkhd.fullBox("tkhd", if (wide) 1 else 0, 3))
            track.auxiliaryOf?.let { of ->
                val auxl = Sink().apply { for (id in of) u32(id) }.box("auxl")
                trak.bytes(Sink().apply { bytes(auxl) }.box("tref"))
            }
            trak.bytes(editList(once[t]))
            val mdia = Sink()
            val mdhd = Sink()
            val wideMedia = once[t] > 0xFFFFFFFFL
            times(mdhd, wideMedia)
            mdhd.u32(track.timescale.toInt())
            duration(mdhd, once[t], wideMedia)
            mdhd.u16(0x55C4) // language 'und'
            mdhd.u16(0)
            mdia.bytes(mdhd.fullBox("mdhd", if (wideMedia) 1 else 0, 0))
            mdia.bytes(Sink().apply { u32(0); ascii(track.handler); u32(0); u32(0); u32(0); u8(0) }.fullBox("hdlr", 0, 0))
            val minf = Sink()
            minf.bytes(Sink().apply { u16(0); u16(0); u16(0); u16(0) }.fullBox("vmhd", 0, 1))
            // A data reference to this file: a 'url ' entry of flags 1 and no location.
            val dref = Sink().apply { u32(1); bytes(Sink().fullBox("url ", 0, 1)) }.fullBox("dref", 0, 0)
            minf.bytes(Sink().apply { bytes(dref) }.box("dinf"))
            minf.bytes(sampleTable(track, starts[t]))
            mdia.bytes(minf.box("minf"))
            trak.bytes(mdia.box("mdia"))
            out.bytes(trak.box("trak"))
        }
        return out.box("moov")
    }

    /**
     * The `edts` box: one edit of the whole track, [once] long, with flags 1 (repeat), as libavif writes a play
     * count: a reader takes the plays as the track's duration in `tkhd` over the edit's, rounded up, and a track
     * that plays for ever has the duration of all ones.
     */
    private fun editList(once: Long): ByteArray {
        val wide = once > 0xFFFFFFFFL
        val elst = Sink()
        elst.u32(1)
        duration(elst, once, wide)
        if (wide) {
            elst.u32(0)
            elst.u32(0)
        } else {
            elst.u32(0) // media_time
        }
        elst.u16(1) // media_rate_integer
        elst.u16(0)
        return Sink().apply { bytes(elst.fullBox("elst", if (wide) 1 else 0, 1)) }.box("edts")
    }

    /** The sample table of [track], its one chunk at file offset [start]. */
    private fun sampleTable(track: Track, start: Long): ByteArray {
        val stbl = Sink()
        stbl.bytes(Sink().apply { u32(1); bytes(track.sampleEntry) }.fullBox("stsd", 0, 0))
        val times = runs(track.durations)
        stbl.bytes(Sink().apply { u32(times.size / 2); for (v in times) u32(v.toInt()) }.fullBox("stts", 0, 0))
        track.compositionOffsets?.takeIf { o -> o.any { it != 0L } }?.let { offsets ->
            val r = runs(offsets)
            // Version 1 holds signed offsets, which a picture shown before one decoded earlier needs.
            val signed = offsets.any { it < 0 }
            stbl.bytes(Sink().apply { u32(r.size / 2); for (v in r) u32(v.toInt()) }.fullBox("ctts", if (signed) 1 else 0, 0))
        }
        stbl.bytes(Sink().apply { u32(1); u32(1); u32(track.samples.size); u32(1) }.fullBox("stsc", 0, 0))
        stbl.bytes(Sink().apply { u32(0); u32(track.samples.size); for (sm in track.samples) u32(sm.size) }.fullBox("stsz", 0, 0))
        if (start > 0xFFFFFFFFL) {
            stbl.bytes(Sink().apply { u32(1); u32((start ushr 32).toInt()); u32(start.toInt()) }.fullBox("co64", 0, 0))
        } else {
            stbl.bytes(Sink().apply { u32(1); u32(start.toInt()) }.fullBox("stco", 0, 0))
        }
        // Without 'stss' every sample is a sync sample.
        if (!track.sync.all { it }) {
            val numbers = track.sync.indices.filter { track.sync[it] }
            stbl.bytes(Sink().apply { u32(numbers.size); for (n in numbers) u32(n + 1) }.fullBox("stss", 0, 0))
        }
        return stbl.box("stbl")
    }

    /** [values] as runs of a count and a value, as `stts` and `ctts` store them. */
    private fun runs(values: LongArray): LongArray {
        val out = ArrayList<Long>()
        var i = 0
        while (i < values.size) {
            var j = i
            while (j < values.size && values[j] == values[i]) j++
            out += (j - i).toLong()
            out += values[i]
            i = j
        }
        return out.toLongArray()
    }

    /** creation_time and modification_time, 0, so the same input gives the same bytes. */
    private fun times(s: Sink, wide: Boolean) {
        for (i in 0 until if (wide) 4 else 2) s.u32(0)
    }

    /** A duration in 64 bits when [wide], else in 32; -1 is all ones, the unknown duration. */
    private fun duration(s: Sink, v: Long, wide: Boolean) {
        if (wide) {
            s.u32((v ushr 32).toInt())
            s.u32(v.toInt())
        } else {
            s.u32(v.toInt())
        }
    }

    /** The unity matrix of `mvhd` and `tkhd`. */
    private fun matrix(s: Sink) {
        for (v in intArrayOf(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000)) s.u32(v)
    }


    /** A growing run of bytes, written big-endian, as boxes store their fields. */
    class Sink {
        private var buf = ByteArray(64)
        var size = 0
            private set

        private fun room(n: Int) {
            if (size + n <= buf.size) return
            buf = buf.copyOf(maxOf(buf.size * 2, size + n))
        }

        fun u8(v: Int) {
            room(1)
            buf[size++] = v.toByte()
        }

        fun u16(v: Int) {
            u8(v ushr 8)
            u8(v)
        }

        fun u32(v: Int) {
            u16(v ushr 16)
            u16(v)
        }

        fun id(v: Int, wide: Boolean) = if (wide) u32(v) else u16(v)

        fun ascii(s: String) = bytes(s.encodeToByteArray())

        fun bytes(b: ByteArray) {
            room(b.size)
            b.copyInto(buf, size)
            size += b.size
        }

        fun toByteArray(): ByteArray = buf.copyOf(size)

        /** These bytes as the payload of a box of [type]. */
        fun box(type: String): ByteArray {
            val out = Sink()
            out.u32(8 + size)
            out.ascii(type)
            out.bytes(toByteArray())
            return out.toByteArray()
        }

        /** These bytes as the payload of a full box of [type], after its [version] and 24-bit [flags]. */
        fun fullBox(type: String, version: Int, flags: Int): ByteArray {
            val out = Sink()
            out.u32(12 + size)
            out.ascii(type)
            out.u8(version)
            out.u8(flags ushr 16)
            out.u16(flags)
            out.bytes(toByteArray())
            return out.toByteArray()
        }
    }

    companion object {
        /** The largest file a ByteArray holds. */
        private const val MAX_FILE = Int.MAX_VALUE - 8L
    }
}
