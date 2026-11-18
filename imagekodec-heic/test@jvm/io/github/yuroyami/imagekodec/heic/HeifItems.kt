package io.github.yuroyami.imagekodec.heic

/**
 * The items of a HEIF file, read apart from the core's reader so that the oracle tests can hand each
 * coded item to another decoder: its type, its data, its properties and its references.
 */
internal class HeifItems(file: ByteArray) {

    class Item(val id: Int, val type: String) {
        var data: ByteArray = ByteArray(0)
        val properties = ArrayList<Pair<String, ByteArray>>()
        val references = ArrayList<Pair<String, List<Int>>>()

        /** The payload of the first property of [type], without its box header. */
        fun property(type: String): ByteArray? = properties.firstOrNull { it.first == type }?.second

        fun referencesTo(type: String): List<Int> = references.filter { it.first == type }.flatMap { it.second }
    }

    val items = LinkedHashMap<Int, Item>()
    var primary: Int = -1
        private set

    private class Box(val type: String, val start: Int, val end: Int)

    private fun boxes(b: ByteArray, from: Int, to: Int): List<Box> {
        val out = ArrayList<Box>()
        var p = from
        while (p + 8 <= to) {
            var size = u32(b, p)
            var header = 8
            if (size == 1L) {
                size = (u32(b, p + 8) shl 32) or u32(b, p + 12)
                header = 16
            } else if (size == 0L) {
                size = (to - p).toLong()
            }
            val type = String(CharArray(4) { (b[p + 4 + it].toInt() and 0xFF).toChar() })
            out += Box(type, p + header, (p + size).toInt())
            p += size.toInt()
        }
        return out
    }

    init {
        val meta = boxes(file, 0, file.size).first { it.type == "meta" }
        val children = boxes(file, meta.start + 4, meta.end)
        fun child(type: String) = children.firstOrNull { it.type == type }
        child("pitm")?.let { primary = if (file[it.start].toInt() == 0) u16(file, it.start + 4) else u32(file, it.start + 4).toInt() }
        child("iinf")!!.let { box ->
            val v = file[box.start].toInt()
            for (infe in boxes(file, box.start + 4 + if (v == 0) 2 else 4, box.end)) {
                val iv = file[infe.start].toInt()
                val id = if (iv >= 3) u32(file, infe.start + 4).toInt() else u16(file, infe.start + 4)
                val at = infe.start + 4 + (if (iv >= 3) 4 else 2) + 2
                items[id] = Item(id, String(CharArray(4) { (file[at + it].toInt() and 0xFF).toChar() }))
            }
        }
        val idat = child("idat")
        child("iloc")!!.let { box ->
            var p = box.start
            val v = file[p].toInt()
            p += 4
            val offsetSize = (file[p].toInt() shr 4) and 15
            val lengthSize = file[p].toInt() and 15
            val baseSize = (file[p + 1].toInt() shr 4) and 15
            val indexSize = if (v >= 1) file[p + 1].toInt() and 15 else 0
            p += 2
            fun read(n: Int): Long {
                var x = 0L
                repeat(n) { x = (x shl 8) or (file[p++].toLong() and 0xFF) }
                return x
            }
            val count = read(if (v < 2) 2 else 4).toInt()
            repeat(count) {
                val id = read(if (v < 2) 2 else 4).toInt()
                val method = if (v >= 1) read(2).toInt() and 15 else 0
                read(2)
                val base = read(baseSize)
                val extents = read(2).toInt()
                val out = java.io.ByteArrayOutputStream()
                repeat(extents) {
                    read(indexSize)
                    val offset = base + read(offsetSize)
                    val length = read(lengthSize).toInt()
                    val origin = if (method == 1) idat!!.start.toLong() else 0L
                    out.write(file, (origin + offset).toInt(), length)
                }
                items[id]?.data = out.toByteArray()
            }
        }
        child("iref")?.let { box ->
            val wide = file[box.start].toInt() != 0
            val n = if (wide) 4 else 2
            fun id(at: Int) = if (wide) u32(file, at).toInt() else u16(file, at)
            for (ref in boxes(file, box.start + 4, box.end)) {
                val from = id(ref.start)
                val count = u16(file, ref.start + n)
                items[from]?.references?.add(ref.type to List(count) { id(ref.start + n + 2 + it * n) })
            }
        }
        child("iprp")?.let { iprp ->
            val parts = boxes(file, iprp.start, iprp.end)
            val ipco = parts.first { it.type == "ipco" }
            val properties = boxes(file, ipco.start, ipco.end).map { it.type to file.copyOfRange(it.start, it.end) }
            for (ipma in parts.filter { it.type == "ipma" }) {
                val v = file[ipma.start].toInt()
                val wideIndex = (file[ipma.start + 3].toInt() and 1) != 0
                var p = ipma.start + 4
                val entries = u32(file, p).toInt()
                p += 4
                repeat(entries) {
                    val id = if (v < 1) u16(file, p).also { p += 2 } else u32(file, p).toInt().also { p += 4 }
                    val associations = file[p++].toInt() and 0xFF
                    repeat(associations) {
                        val index = if (wideIndex) (u16(file, p) and 0x7FFF).also { p += 2 } else (file[p++].toInt() and 0x7F)
                        if (index > 0) items[id]?.properties?.add(properties[index - 1])
                    }
                }
            }
        }
    }

    private fun u16(b: ByteArray, p: Int) = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, p: Int) = ((u16(b, p).toLong()) shl 16) or u16(b, p + 2).toLong()
}
