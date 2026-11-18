package io.github.yuroyami.imagekodec.codec.jxr

import io.github.yuroyami.imagekodec.ImageMetadata

/**
 * The file container of T.832 Annex A around one or two codestreams: the header, one image file
 * directory, the pixel format GUID, and the metadata of an [ImageMetadata].
 *
 * The ICC profile goes in tag 0x8773 and the XMP packet in tag 0x02BC, as the notes to Table A.4
 * say. The EXIF block's orientation becomes the Transformation tag, its descriptive text tags
 * (make, model, date and the others Table A.4 lists) become tags of the directory, and its EXIF
 * and GPS directories are copied after it, rewritten in little-endian order with their offsets
 * moved, behind tags 0x8769 and 0x8825.
 */
internal object JxrContainerWriter {

    private const val TYPE_BYTE = 1
    private const val TYPE_UTF8 = 2
    private const val TYPE_ULONG = 4
    private const val TYPE_UNDEFINED = 7
    private const val TYPE_FLOAT = 11

    /** A directory entry: its value inline when 4 bytes or fewer, otherwise [data] placed after the directory. */
    private class Entry(val tag: Int, val type: Int, val count: Int, val value: Int = 0, val data: ByteArray? = null, val pointsTo: Int = -1)

    /**
     * The file of a [width] by [height] image in pixel format [format] (the last byte of the
     * 6fddc324-4e03-4bfe-b185-3d77768dc9xx GUIDs), with codestream [image] and the planar alpha
     * codestream [alpha].
     */
    fun write(width: Int, height: Int, format: Int, image: ByteArray, alpha: ByteArray?, metadata: ImageMetadata): ByteArray {
        val exif = metadata.exif?.let { ExifBlock.read(it) }
        val entries = ArrayList<Entry>()
        exif?.text?.forEach { (tag, text) -> entries += Entry(tag, TYPE_UTF8, text.size, data = text) }
        metadata.xmp?.let { val b = it.encodeToByteArray(); entries += Entry(0x02BC, TYPE_BYTE, b.size, data = b) }
        // The EXIF and GPS directories go in after the directory and its data, at offsets fixed below.
        if (exif?.exifIfd != null) entries += Entry(0x8769, TYPE_ULONG, 1, pointsTo = 0)
        metadata.icc?.let { entries += Entry(0x8773, TYPE_UNDEFINED, it.size, data = it) }
        if (exif?.gpsIfd != null) entries += Entry(0x8825, TYPE_ULONG, 1, pointsTo = 1)
        entries += Entry(0xBC01, TYPE_BYTE, 16, data = guid(format))
        entries += Entry(0xBC02, TYPE_ULONG, 1, value = transformation(exif?.orientation ?: 1))
        entries += Entry(0xBC80, TYPE_ULONG, 1, value = width)
        entries += Entry(0xBC81, TYPE_ULONG, 1, value = height)
        // 96 dots per inch, as a FLOAT.
        entries += Entry(0xBC82, TYPE_FLOAT, 1, value = 0x42C00000)
        entries += Entry(0xBC83, TYPE_FLOAT, 1, value = 0x42C00000)
        entries += Entry(0xBCC0, TYPE_ULONG, 1, pointsTo = 2)
        entries += Entry(0xBCC1, TYPE_ULONG, 1, value = image.size)
        if (alpha != null) {
            entries += Entry(0xBCC2, TYPE_ULONG, 1, pointsTo = 3)
            entries += Entry(0xBCC3, TYPE_ULONG, 1, value = alpha.size)
        }
        entries.sortBy { it.tag }

        val ifdAt = 8
        var at = ifdAt + 2 + 12 * entries.size + 4
        val dataAt = IntArray(entries.size)
        for ((i, e) in entries.withIndex()) {
            val d = e.data ?: continue
            if (d.size > 4) {
                dataAt[i] = at
                at += d.size + (d.size and 1)
            }
        }
        val exifAt = at
        val exifBytes = exif?.exifIfd?.let { exif.directory(it, exifAt) }
        if (exifBytes != null) at += exifBytes.size
        val gpsAt = at
        val gpsBytes = exif?.gpsIfd?.let { exif.directory(it, gpsAt) }
        if (gpsBytes != null) at += gpsBytes.size
        val imageAt = at
        val alphaAt = imageAt + image.size + (image.size and 1)
        val total = alphaAt.toLong() + (alpha?.size ?: 0)
        require(total < 0xFFFFFFFFL) { "a JPEG XR file of $total bytes passes the 4 GB its offsets reach" }

        val out = ByteArray(total.toInt())
        fun u16(p: Int, v: Int) {
            out[p] = v.toByte()
            out[p + 1] = (v shr 8).toByte()
        }
        fun u32(p: Int, v: Int) {
            u16(p, v)
            u16(p + 2, v ushr 16)
        }
        out[0] = 0x49
        out[1] = 0x49
        out[2] = 0xBC.toByte()
        out[3] = 1
        u32(4, ifdAt)
        u16(ifdAt, entries.size)
        for ((i, e) in entries.withIndex()) {
            val p = ifdAt + 2 + 12 * i
            u16(p, e.tag)
            u16(p + 2, e.type)
            u32(p + 4, e.count)
            val d = e.data
            val v = when {
                e.pointsTo == 0 -> exifAt
                e.pointsTo == 1 -> gpsAt
                e.pointsTo == 2 -> imageAt
                e.pointsTo == 3 -> alphaAt
                d != null && d.size > 4 -> dataAt[i].also { d.copyInto(out, it) }
                d != null -> {
                    d.copyInto(out, p + 8)
                    null
                }
                else -> e.value
            }
            if (v != null) u32(p + 8, v)
        }
        u32(ifdAt + 2 + 12 * entries.size, 0)
        exifBytes?.copyInto(out, exifAt)
        gpsBytes?.copyInto(out, gpsAt)
        image.copyInto(out, imageAt)
        alpha?.copyInto(out, alphaAt)
        return out
    }

    /** The GUID 6fddc324-4e03-4bfe-b185-3d77768dc9[format], stored as Windows stores one. */
    private fun guid(format: Int): ByteArray = byteArrayOf(
        0x24, 0xC3.toByte(), 0xDD.toByte(), 0x6F, 0x03, 0x4E, 0xFE.toByte(), 0x4B,
        0xB1.toByte(), 0x85.toByte(), 0x3D, 0x77, 0x76, 0x8D.toByte(), 0xC9.toByte(), format.toByte(),
    )

    /** The Transformation tag (Table A.4, SPATIAL_XFRM_PRIMARY) of an EXIF orientation: rotation first, then the flips. */
    private fun transformation(exifOrientation: Int): Int = when (exifOrientation) {
        4 -> 1
        2 -> 2
        3 -> 3
        6 -> 4
        7 -> 5
        5 -> 6
        8 -> 7
        else -> 0
    }

    /** What the container takes from an EXIF block: a TIFF structure, as an APP1 `Exif` segment holds after its 6-byte prefix. */
    private class ExifBlock(
        val data: ByteArray,
        val little: Boolean,
        val orientation: Int,
        val text: List<Pair<Int, ByteArray>>,
        val exifIfd: Int?,
        val gpsIfd: Int?,
    ) {
        fun u16(at: Int): Int = if (little) (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
        else ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)

        fun u32(at: Int): Long = if (little) u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        else (u16(at).toLong() shl 16) or u16(at + 2).toLong()

        fun fits(at: Long, n: Long) = at >= 0 && n >= 0 && at + n <= data.size

        /**
         * The directory at [ifd] rewritten little endian to stand at offset [base] of the file:
         * its entries, then their values longer than 4 bytes, then the Interoperability directory
         * it points to, if any. Entries of unknown type, or whose values lie outside the block,
         * are left out.
         */
        fun directory(ifd: Int, base: Int, depth: Int = 0): ByteArray {
            if (!fits(ifd.toLong(), 2)) return emptyDirectory()
            val n = u16(ifd)
            if (!fits(ifd + 2L, 12L * n)) return emptyDirectory()
            class Item(val tag: Int, val type: Int, val count: Int, val bytes: ByteArray, val sub: Int?)
            val items = ArrayList<Item>()
            for (i in 0 until n) {
                val p = ifd + 2 + 12 * i
                val tag = u16(p)
                val type = u16(p + 2)
                val count = u32(p + 4)
                val size = TYPE_SIZE.getOrNull(type) ?: 0
                if (size == 0 || count <= 0 || count > data.size) continue
                val length = count * size
                val from = if (length <= 4) p + 8L else u32(p + 8)
                if (!fits(from, length)) continue
                // The Interoperability directory points from the EXIF directory, and is copied the same way.
                if (tag == 0xA005 && count == 1L && depth == 0) {
                    items += Item(tag, TYPE_ULONG, 1, ByteArray(4), u32(p + 8).toInt())
                    continue
                }
                val bytes = ByteArray(length.toInt())
                // Each element in little-endian order: a rational is two 4-byte halves.
                val unit = if (type == 5 || type == 10) 4 else size
                for (k in 0 until length.toInt() step unit) {
                    for (b in 0 until unit) bytes[k + b] = data[(from + k + if (little) b else unit - 1 - b).toInt()]
                }
                items += Item(tag, type, count.toInt(), bytes, null)
            }
            items.sortBy { it.tag }
            var at = base + 2 + 12 * items.size + 4
            val valueAt = IntArray(items.size)
            for ((i, it) in items.withIndex()) {
                if (it.bytes.size > 4) {
                    valueAt[i] = at
                    at += it.bytes.size + (it.bytes.size and 1)
                }
            }
            val subs = ArrayList<ByteArray>()
            for ((i, it) in items.withIndex()) {
                if (it.sub != null) {
                    valueAt[i] = at
                    val sub = directory(it.sub, at, depth + 1)
                    subs += sub
                    at += sub.size
                }
            }
            val out = ByteArray(at - base)
            fun put16(p: Int, v: Int) {
                out[p] = v.toByte()
                out[p + 1] = (v shr 8).toByte()
            }
            fun put32(p: Int, v: Int) {
                put16(p, v)
                put16(p + 2, v ushr 16)
            }
            put16(0, items.size)
            for ((i, it) in items.withIndex()) {
                val p = 2 + 12 * i
                put16(p, it.tag)
                put16(p + 2, it.type)
                put32(p + 4, it.count)
                if (it.sub != null || it.bytes.size > 4) {
                    put32(p + 8, valueAt[i])
                    if (it.sub == null) it.bytes.copyInto(out, valueAt[i] - base)
                } else {
                    it.bytes.copyInto(out, p + 8)
                }
            }
            put32(2 + 12 * items.size, 0)
            var subAt = 0
            for ((i, it) in items.withIndex()) {
                if (it.sub != null) {
                    subs[subAt++].copyInto(out, valueAt[i] - base)
                }
            }
            return out
        }

        private fun emptyDirectory() = ByteArray(6)

        companion object {
            /** Bytes of an element of each TIFF type; 0 for none known. */
            private val TYPE_SIZE = intArrayOf(0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8, 4)

            /** The text tags of IFD0 that Table A.4 has a place for. */
            private val TEXT_TAGS = setOf(0x010D, 0x010E, 0x010F, 0x0110, 0x011D, 0x0131, 0x0132, 0x013B, 0x013C, 0x8298)

            /** The parts of [exif] the container takes, or null when it is not a TIFF structure. */
            fun read(exif: ByteArray): ExifBlock? {
                if (exif.size < 8) return null
                val little = when {
                    exif[0] == 'I'.code.toByte() && exif[1] == 'I'.code.toByte() -> true
                    exif[0] == 'M'.code.toByte() && exif[1] == 'M'.code.toByte() -> false
                    else -> return null
                }
                val probe = ExifBlock(exif, little, 1, emptyList(), null, null)
                val ifd0 = probe.u32(4)
                if (!probe.fits(ifd0, 2)) return null
                val n = probe.u16(ifd0.toInt())
                if (!probe.fits(ifd0 + 2, 12L * n)) return null
                var orientation = 1
                var exifIfd: Int? = null
                var gpsIfd: Int? = null
                val text = ArrayList<Pair<Int, ByteArray>>()
                for (i in 0 until n) {
                    val p = ifd0.toInt() + 2 + 12 * i
                    val tag = probe.u16(p)
                    val type = probe.u16(p + 2)
                    val count = probe.u32(p + 4)
                    when {
                        tag == 0x0112 && type == 3 && count == 1L -> orientation = probe.u16(p + 8)
                        tag == 0x8769 && count == 1L -> exifIfd = probe.u32(p + 8).toInt().takeIf { probe.fits(it.toLong(), 2) }
                        tag == 0x8825 && count == 1L -> gpsIfd = probe.u32(p + 8).toInt().takeIf { probe.fits(it.toLong(), 2) }
                        tag in TEXT_TAGS && type == 2 && count in 1L..65535L -> {
                            val from = if (count <= 4) p + 8L else probe.u32(p + 8)
                            if (probe.fits(from, count)) text += tag to exif.copyOfRange(from.toInt(), (from + count).toInt())
                        }
                    }
                }
                return ExifBlock(exif, little, orientation, text, exifIfd, gpsIfd)
            }
        }
    }
}
