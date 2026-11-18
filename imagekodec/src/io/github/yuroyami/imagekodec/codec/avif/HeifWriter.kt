package io.github.yuroyami.imagekodec.codec.avif

/**
 * Writes a HEIF file of still images (ISO/IEC 23008-12, over the box structure of ISO/IEC 14496-12):
 * `ftyp`, then `meta` with `hdlr`, `pitm`, `iloc`, `iinf`, `iref`, `iprp` (`ipco` and `ipma`) and
 * `idat`, then `mdat` with the data of the items in the order they were added (#174).
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
    )

    private val items = ArrayList<Item>()
    private val properties = ArrayList<ByteArray>()
    private val propertyIndex = HashMap<Int, MutableList<Int>>()
    private val references = ArrayList<Triple<String, Int, IntArray>>()

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
    ): Int {
        require(type.length == 4) { "item type '$type'" }
        val id = items.size + 1
        items += Item(id, type, data, hidden, contentType, inIdat, itemProperties.map { (box, essential) -> Association(property(box), essential) })
        return id
    }

    /** A reference of [type] from item [from] to items [to], in that order. */
    fun reference(type: String, from: Int, to: IntArray) {
        require(to.size in 1..0xFFFF) { "a '$type' reference to ${to.size} items" }
        references += Triple(type, from, to)
    }

    /** The file, its `ftyp` naming [majorBrand] and [compatibleBrands]. */
    fun build(majorBrand: String, compatibleBrands: List<String>): ByteArray {
        val dataSize = items.sumOf { it.data.size.toLong() }
        // Checked before any offset is computed in 32 bits.
        require(dataSize <= MAX_FILE - (1L shl 24)) { "HEIF items of $dataSize bytes pass what one array holds" }
        val ftyp = Sink().apply {
            ascii(majorBrand)
            u32(0)
            for (b in compatibleBrands) ascii(b)
        }.box("ftyp")
        val meta0 = meta(0)
        val mdatStart = ftyp.size + meta0.size
        val meta = meta(mdatStart + 8)
        check(meta.size == meta0.size) { "the meta box changed size with its offsets" }
        val mdatSize = 8L + items.filter { !it.inIdat }.sumOf { it.data.size.toLong() }
        require(mdatStart + mdatSize <= MAX_FILE) { "a HEIF file of ${mdatStart + mdatSize} bytes passes what one array holds" }
        val out = ByteArray((mdatStart + mdatSize).toInt())
        ftyp.copyInto(out, 0)
        meta.copyInto(out, ftyp.size)
        var at = mdatStart
        out[at] = (mdatSize ushr 24).toByte()
        out[at + 1] = (mdatSize ushr 16).toByte()
        out[at + 2] = (mdatSize ushr 8).toByte()
        out[at + 3] = mdatSize.toByte()
        "mdat".encodeToByteArray().copyInto(out, at + 4)
        at += 8
        for (item in items) if (!item.inIdat) {
            item.data.copyInto(out, at)
            at += item.data.size
        }
        return out
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
        for (item in items) {
            iloc.id(item.id, wideIds)
            iloc.u16(if (item.inIdat) 1 else 0)
            iloc.u16(0)
            iloc.u16(1)
            if (item.inIdat) {
                iloc.u32(idatAt)
                idat.bytes(item.data)
                idatAt += item.data.size
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
