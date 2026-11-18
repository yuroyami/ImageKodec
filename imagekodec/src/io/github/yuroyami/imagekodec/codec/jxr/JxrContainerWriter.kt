package io.github.yuroyami.imagekodec.codec.jxr

import io.github.yuroyami.imagekodec.ImageMetadata
import io.github.yuroyami.imagekodec.codec.ExifDirectories

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
        val ifd0 = metadata.exif?.let { ExifDirectories.parseBlock(it) }.orEmpty()
        val exifIfd = ifd0.firstOrNull { it.tag == ExifDirectories.EXIF_IFD }?.sub
        val gpsIfd = ifd0.firstOrNull { it.tag == ExifDirectories.GPS_IFD }?.sub
        val entries = ArrayList<Entry>()
        for (f in ifd0) if (f.tag in TEXT_TAGS && f.type == 2 && f.count <= 65535) entries += Entry(f.tag, TYPE_UTF8, f.count, data = f.value)
        metadata.xmp?.let { val b = it.encodeToByteArray(); entries += Entry(0x02BC, TYPE_BYTE, b.size, data = b) }
        // The EXIF and GPS directories go in after the directory and its data, at offsets fixed below.
        if (exifIfd != null) entries += Entry(0x8769, TYPE_ULONG, 1, pointsTo = 0)
        metadata.icc?.let { entries += Entry(0x8773, TYPE_UNDEFINED, it.size, data = it) }
        if (gpsIfd != null) entries += Entry(0x8825, TYPE_ULONG, 1, pointsTo = 1)
        entries += Entry(0xBC01, TYPE_BYTE, 16, data = guid(format))
        val orientation = ExifDirectories.short(ifd0.firstOrNull { it.tag == 0x0112 }) ?: 1
        entries += Entry(0xBC02, TYPE_ULONG, 1, value = transformation(orientation))
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
        val exifBytes = exifIfd?.let { ExifDirectories.serialize(it, exifAt) }
        if (exifBytes != null) at += exifBytes.size
        val gpsAt = at
        val gpsBytes = gpsIfd?.let { ExifDirectories.serialize(it, gpsAt) }
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

    /** The text tags of IFD0 that Table A.4 has a place for. */
    val TEXT_TAGS: Set<Int> = setOf(0x010D, 0x010E, 0x010F, 0x0110, 0x011D, 0x0131, 0x0132, 0x013B, 0x013C, 0x8298)

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

    /** The EXIF orientation of a Transformation tag, the inverse of [transformation]. */
    fun exifOrientation(transformation: Int): Int = when (transformation) {
        1 -> 4
        2 -> 2
        3 -> 3
        4 -> 6
        5 -> 7
        6 -> 5
        7 -> 8
        else -> 1
    }
}
