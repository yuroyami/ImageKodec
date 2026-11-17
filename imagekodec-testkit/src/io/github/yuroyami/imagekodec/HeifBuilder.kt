package io.github.yuroyami.imagekodec

/**
 * A HEIF file of items, with each item's properties, references and entity groups, and of tracks, for
 * tests. Without items and a primary item it has no `meta` box, as an image sequence from ffmpeg has none.
 */
class HeifBuilder(val major: String = "avif", val brands: List<String> = listOf("avif", "mif1", "miaf")) {
    class Item(
        val id: Int, val type: String, val data: ByteArray, val properties: List<Pair<ByteArray, Boolean>>,
        val hidden: Boolean, val idat: Boolean, val extents: Int, val contentType: String? = null,
    )

    /**
     * A track of an image sequence: [entryBoxes] are the boxes inside its sample entry, such as its
     * configuration and `auxi`; [ctts] the runs of its `ctts` box as (sample count, offset), written in
     * version [cttsVersion]; [references] its `tref` boxes; [repeat] sets the repetition flag of an
     * edit list that spans [editDuration], or leaves the edit list out when null.
     */
    class Track(
        val id: Int, val handler: String, val entry: String, val width: Int, val height: Int, val entryBoxes: ByteArray,
        val samples: List<ByteArray>, val durations: List<Int>, val timescale: Int, val ctts: List<Pair<Int, Int>>?,
        val cttsVersion: Int, val references: List<Pair<String, IntArray>>, val repeat: Boolean?, val editDuration: Int,
    )

    val items = ArrayList<Item>()
    val tracks = ArrayList<Track>()
    val references = ArrayList<Triple<String, Long, LongArray>>()
    val groups = ArrayList<Pair<String, LongArray>>()
    var primary: Int? = 1

    fun add(
        id: Int, type: String, data: ByteArray, properties: List<Pair<ByteArray, Boolean>>,
        hidden: Boolean = false, idat: Boolean = false, extents: Int = 1, contentType: String? = null,
    ) {
        items += Item(id, type, data, properties, hidden, idat, extents, contentType)
    }

    fun addTrack(
        id: Int, entry: String, width: Int, height: Int, entryBoxes: ByteArray, samples: List<ByteArray>,
        durations: List<Int> = List(samples.size) { 1 }, timescale: Int = 10, handler: String = "pict",
        ctts: List<Pair<Int, Int>>? = null, cttsVersion: Int = 0, references: List<Pair<String, IntArray>> = emptyList(),
        repeat: Boolean? = null, editDuration: Int = durations.sum(),
    ) {
        tracks += Track(id, handler, entry, width, height, entryBoxes, samples, durations, timescale, ctts, cttsVersion, references, repeat, editDuration)
    }

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray) = be32(8 + payload.size) + type.encodeToByteArray() + payload
    private fun fullBox(type: String, version: Int, flags: Int, payload: ByteArray) =
        box(type, byteArrayOf(version.toByte(), (flags ushr 16).toByte(), (flags ushr 8).toByte(), flags.toByte()) + payload)

    fun build(): ByteArray {
        val ftyp = box("ftyp", major.encodeToByteArray() + be32(0) + brands.joinToString("").encodeToByteArray())
        // Each item's data in its extents; the extents go into mdat last first, so only iloc puts them in order.
        val pieces = items.map { item ->
            val n = item.extents
            List(n) { k -> item.data.copyOfRange(item.data.size * k / n, item.data.size * (k + 1) / n) }
        }
        fun meta(mdatStart: Int): ByteArray {
            var idat = ByteArray(0)
            var mdatAt = mdatStart + 8
            val extentsOf = HashMap<Int, List<Pair<Int, Int>>>()
            val idatOf = HashMap<Int, Int>()
            for ((i, item) in items.withIndex()) {
                if (item.idat) {
                    idatOf[item.id] = idat.size
                    idat += item.data
                }
            }
            val order = items.indices.filter { !items[it].idat }
            val offsets = HashMap<Pair<Int, Int>, Int>()
            for (i in order) for (k in pieces[i].indices.reversed()) {
                offsets[i to k] = mdatAt
                mdatAt += pieces[i][k].size
            }
            for (i in order) extentsOf[items[i].id] = pieces[i].indices.map { k -> offsets.getValue(i to k) to pieces[i][k].size }
            var iloc = be16(0x4400) + be16(items.size)
            for (item in items) {
                iloc += be16(item.id) + be16(if (item.idat) 1 else 0) + be16(0)
                if (item.idat) {
                    iloc += be16(1) + be32(idatOf.getValue(item.id)) + be32(item.data.size)
                } else {
                    val e = extentsOf.getValue(item.id)
                    iloc += be16(e.size)
                    for ((o, l) in e) iloc += be32(o) + be32(l)
                }
            }
            var iinf = be16(items.size)
            for (item in items) {
                iinf += fullBox("infe", 2, if (item.hidden) 1 else 0, be16(item.id) + be16(0) + item.type.encodeToByteArray() + byteArrayOf(0) +
                        (item.contentType?.let { it.encodeToByteArray() + byteArrayOf(0) } ?: ByteArray(0)))
            }
            var ipco = ByteArray(0)
            var ipma = be32(items.size)
            var index = 0
            // Past 127 properties an index takes 15 bits, which ipma's flag 1 asks for.
            val wide = items.sumOf { it.properties.size } > 127
            for (item in items) {
                ipma += be16(item.id) + byteArrayOf(item.properties.size.toByte())
                for ((p, essential) in item.properties) {
                    ipco += p
                    index++
                    ipma += if (wide) be16((if (essential) 0x8000 else 0) or index) else byteArrayOf(((if (essential) 0x80 else 0) or index).toByte())
                }
            }
            var iref = ByteArray(0)
            for ((type, from, to) in references) {
                iref += box(type, be16(from.toInt()) + be16(to.size) + to.fold(ByteArray(0)) { a, t -> a + be16(t.toInt()) })
            }
            var children = fullBox("hdlr", 0, 0, be32(0) + "pict".encodeToByteArray() + ByteArray(12) + byteArrayOf(0))
            primary?.let { children += fullBox("pitm", 0, 0, be16(it)) }
            children += fullBox("iloc", 1, 0, iloc)
            children += fullBox("iinf", 0, 0, iinf)
            if (references.isNotEmpty()) children += fullBox("iref", 0, 0, iref)
            children += box("iprp", box("ipco", ipco) + fullBox("ipma", 0, if (wide) 1 else 0, ipma))
            if (idat.isNotEmpty()) children += box("idat", idat)
            if (groups.isNotEmpty()) {
                var grpl = ByteArray(0)
                for ((n, g) in groups.withIndex()) {
                    grpl += fullBox(g.first, 0, 0, be32(100 + n) + be32(g.second.size) + g.second.fold(ByteArray(0)) { a, t -> a + be32(t.toInt()) })
                }
                children += box("grpl", grpl)
            }
            return fullBox("meta", 0, 0, children)
        }
        val withMeta = items.isNotEmpty() || primary != null || tracks.isEmpty()
        val size = if (withMeta) meta(0).size else 0
        val moovSize = moov(0).size
        val mdatStart = ftyp.size + size + moovSize
        val meta = if (withMeta) meta(mdatStart) else ByteArray(0)
        var mdat = ByteArray(0)
        for (i in items.indices.filter { !items[it].idat }) for (k in pieces[i].indices.reversed()) mdat += pieces[i][k]
        val moov = moov(mdatStart + 8 + mdat.size)
        for (t in tracks) for (sample in t.samples) mdat += sample
        return ftyp + meta + moov + box("mdat", mdat)
    }

    /** The `moov` box of [tracks], whose samples lie one after another from [samplesAt], or nothing without tracks. */
    private fun moov(samplesAt: Int): ByteArray {
        if (tracks.isEmpty()) return ByteArray(0)
        var at = samplesAt
        var traks = ByteArray(0)
        for (t in tracks) {
            val duration = t.durations.sum()
            val tkhd = fullBox("tkhd", 0, 3, ByteArray(8) + be32(t.id) + ByteArray(4) + be32(duration) + ByteArray(52) + be32(t.width shl 16) + be32(t.height shl 16))
            var tref = ByteArray(0)
            for ((type, ids) in t.references) tref += box(type, ids.fold(ByteArray(0)) { a, v -> a + be32(v) })
            val edts = t.repeat?.let { box("edts", fullBox("elst", 0, if (it) 1 else 0, be32(1) + be32(t.editDuration) + be32(0) + be32(0x10000))) } ?: ByteArray(0)
            val mdhd = fullBox("mdhd", 0, 0, ByteArray(8) + be32(t.timescale) + be32(duration) + ByteArray(4))
            val hdlr = fullBox("hdlr", 0, 0, be32(0) + t.handler.encodeToByteArray() + ByteArray(12) + byteArrayOf(0))
            // A VisualSampleEntry (ISO/IEC 14496-12 12.1.3): its fixed fields, then its boxes.
            val entry = box(
                t.entry,
                ByteArray(6) + be16(1) + ByteArray(16) + be16(t.width) + be16(t.height) + be32(0x480000) + be32(0x480000) +
                    ByteArray(4) + be16(1) + ByteArray(32) + be16(0x18) + be16(0xFFFF) + t.entryBoxes,
            )
            val stsd = fullBox("stsd", 0, 0, be32(1) + entry)
            val stts = fullBox("stts", 0, 0, be32(t.durations.size) + t.durations.fold(ByteArray(0)) { a, d -> a + be32(1) + be32(d) })
            val ctts = t.ctts?.let { runs -> fullBox("ctts", t.cttsVersion, 0, be32(runs.size) + runs.fold(ByteArray(0)) { a, (n, o) -> a + be32(n) + be32(o) }) } ?: ByteArray(0)
            val stsc = fullBox("stsc", 0, 0, be32(1) + be32(1) + be32(t.samples.size) + be32(1))
            val stsz = fullBox("stsz", 0, 0, be32(0) + be32(t.samples.size) + t.samples.fold(ByteArray(0)) { a, s -> a + be32(s.size) })
            val stco = fullBox("stco", 0, 0, be32(1) + be32(at))
            at += t.samples.sumOf { it.size }
            val stbl = box("stbl", stsd + stts + ctts + stsc + stsz + stco)
            val mdia = box("mdia", mdhd + hdlr + box("minf", stbl))
            traks += box("trak", tkhd + (if (tref.isEmpty()) ByteArray(0) else box("tref", tref)) + edts + mdia)
        }
        return box("moov", traks)
    }
}
