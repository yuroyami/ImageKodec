package io.github.yuroyami.imagekodec

/** The EXIF block and XMP packet the metadata tests write into every format (#186). */
internal object MetadataFixtures {

    /** A directory entry of [exif]: a value in big-endian order, or the entries of the directory it points to. */
    private class E(val tag: Int, val type: Int, val count: Int, val value: ByteArray = ByteArray(0), val sub: List<E>? = null)

    private fun ascii(tag: Int, text: String) = (text + "\u0000").encodeToByteArray().let { E(tag, 2, it.size, it) }
    private fun short(tag: Int, v: Int) = E(tag, 3, 1, byteArrayOf((v shr 8).toByte(), v.toByte()))
    private fun rationals(tag: Int, vararg v: Int) = E(tag, 5, v.size / 2, ByteArray(v.size * 4) { (v[it / 4] shr (24 - 8 * (it % 4))).toByte() })

    /**
     * A big-endian EXIF block as a camera writes one: IFD0 with the camera, the orientation
     * [orientation], the resolution and the date, an EXIF directory with an Interoperability
     * directory, and a GPS directory.
     */
    fun exif(orientation: Int = 6): ByteArray {
        val interop = listOf(ascii(1, "R98"), E(2, 7, 4, "0100".encodeToByteArray()))
        val exif = listOf(
            rationals(33434, 1, 250),
            short(34855, 200),
            E(36864, 7, 4, "0232".encodeToByteArray()),
            ascii(36867, "2026:11:09 08:30:00"),
            E(37500, 7, 11, "KITE\u0000NOTE\u0000\u0001".encodeToByteArray()),
            E(40965, 4, 1, sub = interop),
        )
        val gps = listOf(E(0, 1, 4, byteArrayOf(2, 3, 0, 0)), ascii(1, "N"), rationals(2, 51, 1, 30, 1, 0, 1))
        val ifd0 = listOf(
            ascii(271, "Kite"), ascii(272, "Oracle One"), short(274, orientation), rationals(282, 300, 1),
            rationals(283, 300, 1), short(296, 2), ascii(306, "2026:11:09 08:30:00"),
            E(34665, 4, 1, sub = exif), E(34853, 4, 1, sub = gps),
        )
        val out = ArrayList<Byte>()
        fun put(bytes: ByteArray) { for (b in bytes) out += b }
        fun u16(v: Int) = put(byteArrayOf((v shr 8).toByte(), v.toByte()))
        fun u32(v: Int) = put(byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()))
        fun size(dir: List<E>): Int = 6 + 12 * dir.size + dir.sumOf { e -> e.sub?.let { size(it) } ?: if (e.value.size > 4) e.value.size + (e.value.size and 1) else 0 }
        fun write(dir: List<E>, at: Int) {
            var data = at + 6 + 12 * dir.size
            val later = ArrayList<Pair<E, Int>>()
            u16(dir.size)
            for (e in dir) {
                u16(e.tag); u16(e.type); u32(e.count)
                when {
                    e.sub != null -> { u32(data); later += e to data; data += size(e.sub) }
                    e.value.size > 4 -> { u32(data); later += e to data; data += e.value.size + (e.value.size and 1) }
                    else -> put(e.value.copyOf(4))
                }
            }
            u32(0)
            for ((e, where) in later) {
                if (e.sub != null) write(e.sub, where) else { put(e.value); if (e.value.size and 1 == 1) out += 0 }
            }
        }
        put("MM".encodeToByteArray()); u16(42); u32(8)
        write(ifd0, 8)
        return out.toByteArray()
    }

    /** An XMP packet with a byte order mark in its header and letters outside ASCII in its title. */
    const val XMP: String = "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?><x:xmpmeta xmlns:x=\"adobe:ns:meta/\">" +
        "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
        "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">Harbour at dawn, ünïcödé</rdf:li></rdf:Alt></dc:title>" +
        "</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>"
}
