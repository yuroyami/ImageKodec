package io.github.yuroyami.imagekodec

/** A HEIF file of items, with each item's properties, references and entity groups, for tests. */
class HeifBuilder(val major: String = "avif", val brands: List<String> = listOf("avif", "mif1", "miaf")) {
    class Item(
        val id: Int, val type: String, val data: ByteArray, val properties: List<Pair<ByteArray, Boolean>>,
        val hidden: Boolean, val idat: Boolean, val extents: Int, val contentType: String? = null,
    )

    val items = ArrayList<Item>()
    val references = ArrayList<Triple<String, Long, LongArray>>()
    val groups = ArrayList<Pair<String, LongArray>>()
    var primary: Int? = 1

    fun add(
        id: Int, type: String, data: ByteArray, properties: List<Pair<ByteArray, Boolean>>,
        hidden: Boolean = false, idat: Boolean = false, extents: Int = 1, contentType: String? = null,
    ) {
        items += Item(id, type, data, properties, hidden, idat, extents, contentType)
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
        val size = meta(0).size
        val meta = meta(ftyp.size + size)
        var mdat = ByteArray(0)
        for (i in items.indices.filter { !items[it].idat }) for (k in pieces[i].indices.reversed()) mdat += pieces[i][k]
        return ftyp + meta + box("mdat", mdat)
    }
}
