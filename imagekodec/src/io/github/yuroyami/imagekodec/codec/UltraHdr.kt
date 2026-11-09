package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.GainMap
import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.ImageMetadata
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.Orientation
import io.github.yuroyami.imagekodec.internal.flate.ByteArrayBuilder
import io.github.yuroyami.imagekodec.oriented

/**
 * The gain map of an Ultra HDR JPEG (#130). The main image carries a Multi-Picture Format
 * segment, an `APP2` that starts `MPF\0` and holds a TIFF structure whose MP Entry (tag `B002`)
 * gives the size and offset of each image, counted from the start of that TIFF structure (CIPA
 * DC-007, 5.2.3). The gain map is the image after the main one, with its parameters in an `hdrgm`
 * XMP packet (Android's Ultra HDR image format 1.0) or in an ISO 21496-1 `APP2` segment.
 */
internal object UltraHdr {

    private val MPF = byteArrayOf(0x4D, 0x50, 0x46, 0)
    private val ISO = "urn:iso:std:iso:ts:21496:-1\u0000".encodeToByteArray()
    private val XMP = "http://ns.adobe.com/xap/1.0/\u0000".encodeToByteArray()
    private val EXIF = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0)

    /** The MPF segment this writes, from its marker to its end. */
    private const val MPF_SEGMENT = 90

    /** The JFIF header [JpegEncoder] writes after SOI, before the metadata segments. */
    private const val JFIF_END = 20

    private class Segment(val marker: Int, val start: Int, val body: Int, val end: Int)

    /** The marker segments of [data] before its first scan. */
    private fun segments(data: ByteArray): List<Segment> = buildList {
        if (data.size < 4 || (data[0].toInt() and 0xFF) != 0xFF || (data[1].toInt() and 0xFF) != 0xD8) return@buildList
        var at = 2
        while (at + 4 <= data.size) {
            if ((data[at].toInt() and 0xFF) != 0xFF) break
            val marker = data[at + 1].toInt() and 0xFF
            if (marker == 0xFF) { at++; continue }
            if (marker == 0x01 || marker in 0xD0..0xD7) { at += 2; continue }
            if (marker == 0xDA || marker == 0xD9) break
            val length = u16(data, at + 2)
            if (length < 2 || at + 2 + length > data.size) break
            add(Segment(marker, at, at + 4, at + 2 + length))
            at += 2 + length
        }
    }

    fun find(data: ByteArray): GainMap? {
        val segments = segments(data)
        val mpf = segments.firstOrNull { it.marker == 0xE2 && startsWith(data, it.body, it.end, MPF) } ?: return null
        val iso = segments.firstOrNull { it.marker == 0xE2 && startsWith(data, it.body, it.end, ISO) }
            ?.let { data.copyOfRange(it.body, it.end) }
        val tiff = mpf.body + MPF.size
        for ((offset, size) in entries(data, tiff, mpf.end).drop(1)) {
            val from = tiff + offset
            val to = from + size
            if (offset <= 0 || size <= 4 || to > data.size) continue
            if ((data[from.toInt()].toInt() and 0xFF) != 0xFF || (data[from.toInt() + 1].toInt() and 0xFF) != 0xD8) continue
            val image = data.copyOfRange(from.toInt(), to.toInt())
            if (!isGainMap(image)) continue
            return runCatching { GainMap(image, iso) }.getOrNull()
        }
        return null
    }

    /**
     * [primary], a rewrite of the first image of [original] that ended at [originalEnd], with its
     * MPF segment moved to the new end: the first entry gets the new size, and every later image,
     * which stays after the first, its new offset from the TIFF header (#132). A file with no MPF
     * segment comes back as it is.
     */
    fun shiftMpf(original: ByteArray, originalEnd: Int, primary: ByteArray): ByteArray {
        val before = segments(original).firstOrNull { it.marker == 0xE2 && startsWith(original, it.body, it.end, MPF) } ?: return primary
        val after = segments(primary).firstOrNull { it.marker == 0xE2 && startsWith(primary, it.body, it.end, MPF) } ?: return primary
        val oldTiff = before.body + MPF.size
        val newTiff = after.body + MPF.size
        val entries = entryPositions(primary, newTiff, after.end)
        if (entries.isEmpty()) return primary
        val little = primary[newTiff].toInt() == 0x49
        val out = primary.copyOf()
        fun read(at: Int): Long = if (little) (0..3).fold(0L) { v, i -> v or ((out[at + i].toLong() and 0xFF) shl (8 * i)) }
            else (0..3).fold(0L) { v, i -> (v shl 8) or (out[at + i].toLong() and 0xFF) }
        fun write(at: Int, value: Long) {
            for (i in 0..3) out[at + i] = (value ushr (if (little) 8 * i else 24 - 8 * i)).toByte()
        }
        for ((n, at) in entries.withIndex()) {
            if (n == 0) { write(at + 4, primary.size.toLong()); continue }
            val offset = read(at + 8)
            if (offset == 0L) continue
            val absolute = oldTiff + offset - originalEnd + primary.size
            write(at + 8, absolute - newTiff)
        }
        return out
    }

    /** Where each 16-byte MP Entry of the TIFF structure at [tiff] starts. */
    private fun entryPositions(data: ByteArray, tiff: Int, end: Int): List<Int> {
        if (tiff + 8 > end) return emptyList()
        val little = data[tiff].toInt() == 0x49 && data[tiff + 1].toInt() == 0x49
        if (!little && !(data[tiff].toInt() == 0x4D && data[tiff + 1].toInt() == 0x4D)) return emptyList()
        fun short(at: Int): Int = if (little) (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8) else u16(data, at)
        fun long(at: Int): Long = if (little) short(at).toLong() or (short(at + 2).toLong() shl 16) else (short(at).toLong() shl 16) or short(at + 2).toLong()
        val ifd = tiff + long(tiff + 4)
        if (ifd + 2 > end) return emptyList()
        val count = short(ifd.toInt())
        for (i in 0 until count) {
            val entry = ifd.toInt() + 2 + i * 12
            if (entry + 12 > end) return emptyList()
            if (short(entry) != 0xB002) continue
            val bytes = long(entry + 4)
            val values = tiff + long(entry + 8)
            if (bytes < 16 || values + bytes > end) return emptyList()
            return List((bytes / 16).toInt()) { values.toInt() + it * 16 }
        }
        return emptyList()
    }

    /** The offset and size of each image the MP Entry of the TIFF structure at [tiff] lists. */
    private fun entries(data: ByteArray, tiff: Int, end: Int): List<Pair<Long, Long>> {
        val little = data[tiff].toInt() == 0x49
        fun long(at: Int): Long = if (little) (0..3).fold(0L) { v, k -> v or ((data[at + k].toLong() and 0xFF) shl (8 * k)) }
            else (0..3).fold(0L) { v, k -> (v shl 8) or (data[at + k].toLong() and 0xFF) }
        return entryPositions(data, tiff, end).map { long(it + 8) to long(it + 4) }
    }

    private fun isGainMap(image: ByteArray): Boolean = segments(image).any {
        (it.marker == 0xE2 && startsWith(image, it.body, it.end, ISO)) ||
            (it.marker == 0xE1 && startsWith(image, it.body, it.end, XMP) &&
                image.copyOfRange(it.body, it.end).decodeToString().contains("hdrgm:"))
    }

    /**
     * [bitmap] with [metadata]: the main image, then the gain map unchanged after it when it
     * still covers [bitmap], with an MPF segment that points to it. A gain map of another aspect
     * ratio is left out together with the HDR fields of the XMP packet, so that no reader looks
     * for a gain map that is not there.
     */
    fun encode(bitmap: KiteBitmap, quality: Int, metadata: ImageMetadata): ByteArray {
        val gainMap = metadata.gainMap?.takeIf { covers(it, bitmap) }
        val xmp = metadata.xmp?.let { if (gainMap != null) withDirectory(it, gainMap.jpeg.size) else withoutHdr(it) }
        val head = ByteArrayBuilder(256)
        head.append(MetadataIo.jpegSegments(ImageMetadata(metadata.exif, xmp, metadata.icc)))
        if (gainMap == null) return JpegEncoder.encode(bitmap, quality, head.toByteArray(), gray = false)
        gainMap.primaryIso?.takeIf { it.size + 2 <= 0xFFFF }?.let {
            head.append(byteArrayOf(0xFF.toByte(), 0xE2.toByte(), ((it.size + 2) ushr 8).toByte(), (it.size + 2).toByte()))
            head.append(it)
        }
        head.append(mpf(0, 0, 0))
        val segments = head.toByteArray()
        val primary = JpegEncoder.encode(bitmap, quality, segments, gray = false)
        val at = JFIF_END + segments.size - MPF_SEGMENT
        val tiff = at + 4 + MPF.size
        mpf(primary.size, primary.size - tiff, gainMap.jpeg.size).copyInto(primary, at)
        return primary + gainMap.jpeg
    }

    /**
     * A gain map covers a bitmap of the same aspect ratio, to within a pixel of the gain map:
     * a reader scales it to the main image (Ultra HDR 1.0, "Gain map"). A turned or cropped
     * image has another one.
     */
    private fun covers(gainMap: GainMap, bitmap: KiteBitmap): Boolean =
        kotlin.math.abs(gainMap.width.toLong() * bitmap.height - gainMap.height.toLong() * bitmap.width) <= bitmap.width.toLong() + bitmap.height

    /** The MPF segment of a main image of [primary] bytes and a gain map of [size] bytes at [offset] from its TIFF header. */
    private fun mpf(primary: Int, offset: Int, size: Int): ByteArray {
        val out = ByteArrayBuilder(MPF_SEGMENT)
        fun b(vararg v: Int) { for (x in v) out.append(x.toByte()) }
        fun long(v: Int) = b(v ushr 24, v ushr 16, v ushr 8, v)
        b(0xFF, 0xE2, 0, MPF_SEGMENT - 2)
        out.append(MPF)
        b(0x4D, 0x4D, 0, 0x2A); long(8)
        b(0, 3)
        b(0xB0, 0x00, 0, 7); long(4); b(0x30, 0x31, 0x30, 0x30) // MP Format Version "0100"
        b(0xB0, 0x01, 0, 4); long(1); long(2) // Number of Images
        b(0xB0, 0x02, 0, 7); long(32); long(50) // MP Entry, after this IFD
        long(0)
        long(0x030000); long(primary); long(0); b(0, 0, 0, 0) // Baseline MP Primary Image
        long(0); long(size); long(offset); b(0, 0, 0, 0)
        return out.toByteArray()
    }

    private val DIRECTORY = Regex("""<Container:Directory>[\s\S]*?</Container:Directory>""")

    /** [xmp] with a `Container:Directory` that lists the main image and the gain map of [length] bytes, as Ultra HDR 1.0 writes it. */
    private fun withDirectory(xmp: String, length: Int): String = DIRECTORY.replace(xmp) {
        if (!it.value.contains("GainMap")) it.value
        else """<Container:Directory><rdf:Seq><rdf:li rdf:parseType="Resource"><Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg"/></rdf:li>""" +
            """<rdf:li rdf:parseType="Resource"><Container:Item Item:Semantic="GainMap" Item:Mime="image/jpeg" Item:Length="$length"/></rdf:li></rdf:Seq></Container:Directory>"""
    }

    private val HDR_FIELD = Regex("""\s+(?:xmlns:)?hdrgm(?::\w+)?="[^"]*"""")
    private val HDR_ELEMENT = Regex("""<hdrgm:(\w+)\b[^>]*?(?:/>|>[\s\S]*?</hdrgm:\1>)""")

    /**
     * [xmp] without its `hdrgm` fields and without a `Container:Directory` that names a gain map.
     * The packet of a gain map itself, which gives `hdrgm:GainMapMax`, stays as it is: encoding
     * a gain map writes its parameters.
     */
    private fun withoutHdr(xmp: String): String {
        if (!xmp.contains("hdrgm") && !xmp.contains("GainMap")) return xmp
        if (xmp.contains("hdrgm:GainMapMax")) return xmp
        return DIRECTORY.replace(HDR_ELEMENT.replace(HDR_FIELD.replace(xmp, ""), "")) { if (it.value.contains("GainMap")) "" else it.value }
    }

    /**
     * [gainMap] turned by [orientation], as the main image is turned, with its own XMP and ISO
     * 21496-1 segments, or null when it cannot be decoded. A gray gain map stays single-channel.
     */
    fun oriented(gainMap: GainMap, orientation: Orientation): GainMap? = runCatching {
        val bitmap = ImageKodec.decode(gainMap.jpeg).oriented(orientation)
        val gray = bitmap.argb.all { ((it ushr 16) and 0xFF) == (it and 0xFF) && ((it ushr 8) and 0xFF) == (it and 0xFF) }
        val own = ByteArrayBuilder(1024)
        for (s in segments(gainMap.jpeg)) {
            if (s.marker !in 0xE1..0xEF) continue
            if (s.marker == 0xE1 && startsWith(gainMap.jpeg, s.body, s.end, EXIF)) continue
            own.append(gainMap.jpeg.copyOfRange(s.start, s.end))
        }
        GainMap(JpegEncoder.encode(bitmap, 95, own.toByteArray(), gray), gainMap.primaryIso)
    }.getOrNull()

    private fun u16(d: ByteArray, at: Int): Int = ((d[at].toInt() and 0xFF) shl 8) or (d[at + 1].toInt() and 0xFF)

    private fun startsWith(d: ByteArray, from: Int, end: Int, prefix: ByteArray): Boolean {
        if (end - from < prefix.size) return false
        for (i in prefix.indices) if (d[from + i] != prefix[i]) return false
        return true
    }
}
