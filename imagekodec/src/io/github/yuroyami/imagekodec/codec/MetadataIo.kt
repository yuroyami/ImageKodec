package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.ImageMetadata
import io.github.yuroyami.imagekodec.codec.avif.AvifContainer
import io.github.yuroyami.imagekodec.codec.avif.AvifProperty
import io.github.yuroyami.imagekodec.codec.jxl.JxlContainer
import io.github.yuroyami.imagekodec.codec.jxl.JxlDecoder
import io.github.yuroyami.imagekodec.codec.jxr.JxrContainerWriter
import io.github.yuroyami.imagekodec.internal.brotli.Brotli
import io.github.yuroyami.imagekodec.internal.brotli.BrotliException
import io.github.yuroyami.imagekodec.internal.flate.ByteArrayBuilder
import io.github.yuroyami.imagekodec.internal.flate.Zlib

/**
 * Reads and writes the EXIF, XMP and ICC metadata of JPEG, PNG and WebP files (#129): JPEG APP1
 * `Exif` and Adobe XMP segments and APP2 `ICC_PROFILE` chunks (Exif 2.32, 4.5.4; XMP Part 3,
 * 1.1.3; ICC.1:2022, B.4), PNG `eXIf`, `iTXt` with the keyword `XML:com.adobe.xmp`, and `iCCP`
 * (PNG, Third Edition, 11.3), the `EXIF`, `XMP ` and `ICCP` chunks of extended WebP, the
 * `Exif` and XMP items and the ICC `colr` of the primary image of a HEIF file (#149), and the UUID
 * boxes and colour specification of a JP2 file, which [Jp2Boxes] reads.
 */
internal object MetadataIo {

    private val EXIF_SIGNATURE = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0) // "Exif\0\0"
    private const val XMP_SIGNATURE = "http://ns.adobe.com/xap/1.0/\u0000"
    private const val XMP_KEYWORD = "XML:com.adobe.xmp"
    private const val MAX_TEXT = 16L shl 20

    /** The application identifier and authentication code of the XMP and ICC extensions of a GIF file. */
    const val GIF_XMP = "XMP DataXMP"
    const val GIF_ICC = "ICCRGBG1012"

    /** The length of the trailer after the XMP packet of a GIF file, the block end included. */
    const val GIF_XMP_TRAILER = 258

    /** LCS_PROFILE_EMBEDDED, `MBED`, the colour space of a BMP that embeds its profile. */
    const val PROFILE_EMBEDDED = 0x4D424544

    fun read(data: ByteArray): ImageMetadata = when {
        data.size >= 3 && (data[0].toInt() and 0xFF) == 0xFF && (data[1].toInt() and 0xFF) == 0xD8 -> readJpeg(data)
        data.size >= 8 && (data[0].toInt() and 0xFF) == 0x89 && data[1].toInt() == 0x50 && data[2].toInt() == 0x4E && data[3].toInt() == 0x47 -> readPng(data)
        data.size >= 12 && ascii(data, 0, 4) == "RIFF" && ascii(data, 8, 4) == "WEBP" -> readWebp(data)
        AvifContainer.heifFormat(data) != null -> readHeif(data)
        data.size >= 12 && be32(data, 0) == 12 && ascii(data, 4, 4) == "jP  " -> Jp2Boxes.read(data)
        data.size >= 8 && (ascii(data, 0, 4) == "II*\u0000" || ascii(data, 0, 4) == "MM\u0000*") -> readTiff(data)
        data.size >= 8 && ascii(data, 0, 2) == "II" && (data[2].toInt() and 0xFF) == 0xBC -> readJxr(data)
        JxlContainer.isCodestream(data) || JxlContainer.isContainer(data) -> readJxl(data)
        data.size >= 13 && ascii(data, 0, 4) == "GIF8" -> readGif(data)
        data.size >= 18 && ascii(data, 0, 2) == "BM" -> readBmp(data)
        else -> ImageMetadata()
    }

    /**
     * The metadata of a TIFF file's first page (#186): XMP (tag 700) and InterColorProfile (tag
     * 34675), and, when the page points to an EXIF or GPS directory, an EXIF block built of those
     * directories and the descriptive tags of the page ([ExifDirectories.DESCRIPTIVE]).
     */
    private fun readTiff(data: ByteArray): ImageMetadata {
        val little = data[0].toInt() == 0x49
        val ifd0 = (if (little) le32(data, 4) else be32(data, 4)).toLong() and 0xFFFFFFFFL
        val fields = ExifDirectories.read(data, little, ifd0, 0) ?: return ImageMetadata()
        val xmp = fields.firstOrNull { it.tag == 700 && (it.type == 1 || it.type == 7) }?.value?.decodeToString()
        val icc = fields.firstOrNull { it.tag == 34675 && (it.type == 7 || it.type == 1) }?.value
        val exif = exifOf(fields.filter { it.tag in ExifDirectories.DESCRIPTIVE }, fields)
        return ImageMetadata(exif, xmp, icc)
    }

    /**
     * The metadata of a JPEG XR file (#186): its XMP (tag 0x02BC) and ICC profile (tag 0x8773),
     * and, when it points to an EXIF or GPS directory, an EXIF block of those directories, its
     * text tags and the orientation its Transformation tag gives.
     */
    private fun readJxr(data: ByteArray): ImageMetadata {
        val fields = ExifDirectories.read(data, true, le32(data, 4).toLong() and 0xFFFFFFFFL, 0) ?: return ImageMetadata()
        val xmp = fields.firstOrNull { it.tag == 0x02BC }?.value?.decodeToString()
        val icc = fields.firstOrNull { it.tag == 0x8773 }?.value
        val text = fields.filter { it.tag in JxrContainerWriter.TEXT_TAGS && it.type == 2 }
        val transformation = fields.firstOrNull { it.tag == 0xBC02 }?.let { f ->
            if (f.type == 3) ExifDirectories.short(f) else if (f.type == 4 && f.count == 1) le32(f.value, 0) else null
        } ?: 0
        // ExifTool writes an Orientation tag into the directory itself; without one, the Transformation tag gives it.
        val orientation = ExifDirectories.short(fields.firstOrNull { it.tag == 0x0112 }) ?: JxrContainerWriter.exifOrientation(transformation)
        val tags = if (orientation == 1) text else text + ExifDirectories.Field(0x0112, 3, 1, byteArrayOf(orientation.toByte(), 0, 0, 0))
        return ImageMetadata(exifOf(tags, fields), xmp, icc)
    }

    /** An EXIF block of IFD0 [tags] and the EXIF and GPS directories among [fields], or null when there are neither. */
    private fun exifOf(tags: List<ExifDirectories.Field>, fields: List<ExifDirectories.Field>): ByteArray? {
        val pointers = fields.filter { it.tag == ExifDirectories.EXIF_IFD || it.tag == ExifDirectories.GPS_IFD }
        if (pointers.isEmpty()) return null
        return ExifDirectories.block(tags + pointers)
    }

    /**
     * The metadata of a JPEG XL file (#186): the `Exif` and `xml ` boxes of its container, also
     * when a `brob` box holds one compressed with Brotli (ISO/IEC 18181-2, 9.1), and the ICC
     * profile of its codestream, which its image header says is there (18181-1, E.4).
     */
    private fun readJxl(data: ByteArray): ImageMetadata {
        var exif: ByteArray? = null
        var xmp: String? = null
        fun box(type: String, body: ByteArray) {
            when (type) {
                // A 4-byte offset from its end to the TIFF header (18181-2, 9.1).
                "Exif" -> if (exif == null && body.size >= 4) {
                    val offset = be32(body, 0).toLong() and 0xFFFFFFFFL
                    if (offset <= body.size - 4L - 8) exif = body.copyOfRange(4 + offset.toInt(), body.size)
                }
                "xml " -> if (xmp == null) xmp = body.decodeToString()
            }
        }
        if (JxlContainer.isContainer(data)) {
            var at = 0L
            while (at + 8 <= data.size) {
                var size = be32(data, at.toInt()).toLong() and 0xFFFFFFFFL
                val type = ascii(data, at.toInt() + 4, 4)
                var header = 8
                if (size == 1L) {
                    if (at + 16 > data.size) break
                    size = ((be32(data, at.toInt() + 8).toLong() and 0xFFFFFFFFL) shl 32) or (be32(data, at.toInt() + 12).toLong() and 0xFFFFFFFFL)
                    header = 16
                }
                val end = if (size == 0L) data.size.toLong() else at + size
                if ((size != 0L && size < header) || end > data.size || end < at) break
                if (type == "Exif" || type == "xml " || type == "brob") {
                    val body = data.copyOfRange((at + header).toInt(), end.toInt())
                    if (type != "brob") {
                        box(type, body)
                    } else if (body.size >= 4) {
                        val inner = ascii(body, 0, 4)
                        if ((inner == "Exif" && exif == null) || (inner == "xml " && xmp == null)) {
                            try {
                                box(inner, Brotli.decompress(body.copyOfRange(4, body.size), MAX_TEXT.toInt()))
                            } catch (_: BrotliException) {
                                // A damaged compressed box is left out, as a damaged chunk is.
                            }
                        }
                    }
                }
                if (size == 0L) break
                at = end
            }
        }
        val icc = try {
            JxlDecoder.open(data).icc
        } catch (_: Exception) {
            null
        }
        return ImageMetadata(exif, xmp, icc)
    }

    /**
     * The metadata of a GIF file (#186): the XMP application extension (`XMP DataXMP`, XMP Part 3,
     * 1.1.2), whose packet runs raw up to a 258-byte trailer that makes a reader of sub-blocks skip
     * it, and the ICC application extension (`ICCRGBG1` `012`, ICC.1:2022, B.6), whose profile is
     * in sub-blocks.
     */
    private fun readGif(data: ByteArray): ImageMetadata {
        var xmp: String? = null
        var icc: ByteArray? = null
        var at = 13
        val flags = data[10].toInt() and 0xFF
        if (flags and 0x80 != 0) at += 3 * (2 shl (flags and 7))
        fun skipBlocks(): Boolean {
            while (at < data.size) {
                val n = data[at].toInt() and 0xFF
                at += 1 + n
                if (n == 0) return true
            }
            return false
        }
        while (at < data.size) {
            when (data[at].toInt() and 0xFF) {
                0x21 -> {
                    if (at + 2 > data.size) break
                    val label = data[at + 1].toInt() and 0xFF
                    at += 2
                    if (label == 0xFF && at + 12 <= data.size && data[at].toInt() == 11) {
                        val id = ascii(data, at + 1, 11)
                        if (id == GIF_XMP) {
                            val end = gifXmpEnd(data, at + 12)
                            if (end < 0) break
                            if (xmp == null) xmp = data.copyOfRange(at + 12, end).decodeToString()
                            at = end + GIF_XMP_TRAILER
                            continue
                        }
                        if (id == GIF_ICC && icc == null) {
                            at += 12
                            val out = ByteArrayBuilder(1024)
                            while (at < data.size) {
                                val n = data[at].toInt() and 0xFF
                                if (n == 0 || at + 1 + n > data.size) break
                                out.append(data, at + 1, n)
                                at += 1 + n
                            }
                            if (at < data.size && data[at].toInt() == 0) icc = out.toByteArray()
                        }
                    }
                    if (!skipBlocks()) break
                }
                0x2C -> {
                    if (at + 10 > data.size) break
                    val local = data[at + 9].toInt() and 0xFF
                    at += 10
                    if (local and 0x80 != 0) at += 3 * (2 shl (local and 7))
                    // The LZW minimum code size, then the image data.
                    at++
                    if (!skipBlocks()) break
                }
                else -> break
            }
        }
        return ImageMetadata(xmp = xmp, icc = icc)
    }

    /** Where the trailer of a GIF XMP packet that starts at [from] begins: 1, then 255 down to 0, then the block end; -1 for none. */
    private fun gifXmpEnd(data: ByteArray, from: Int): Int {
        var i = from
        while (i + GIF_XMP_TRAILER <= data.size) {
            if (data[i].toInt() == 1 && (data[i + 1].toInt() and 0xFF) == 0xFF) {
                var k = 0
                while (k < 256 && (data[i + 1 + k].toInt() and 0xFF) == 255 - k) k++
                if (k == 256 && data[i + 257].toInt() == 0) return i
            }
            i++
        }
        return -1
    }

    /**
     * The ICC profile a BITMAPV5HEADER embeds (#186): colour space PROFILE_EMBEDDED (`MBED`), the
     * profile bV5ProfileData bytes from the start of the header and bV5ProfileSize long.
     */
    private fun readBmp(data: ByteArray): ImageMetadata {
        if (le32(data, 14) != 124 || data.size < 14 + 124) return ImageMetadata()
        if (le32(data, 14 + 56) != PROFILE_EMBEDDED) return ImageMetadata()
        val offset = le32(data, 14 + 112).toLong() and 0xFFFFFFFFL
        val size = le32(data, 14 + 116).toLong() and 0xFFFFFFFFL
        if (size == 0L || 14 + offset + size > data.size) return ImageMetadata()
        return ImageMetadata(icc = data.copyOfRange((14 + offset).toInt(), (14 + offset + size).toInt()))
    }

    /**
     * The metadata of a HEIF file's primary image: the `Exif` and `mime` items that a `cdsc`
     * reference links to it, and its ICC `colr`, or that of the first image it is derived from.
     */
    private fun readHeif(data: ByteArray): ImageMetadata {
        val c = try {
            AvifContainer.parse(data)
        } catch (_: ImageDecodeException) {
            return ImageMetadata()
        }
        val primary = c.primary ?: return ImageMetadata()
        val described = c.referencesTo(primary, AvifContainer.CDSC).mapNotNull { c.items[it] }
        val exif = described.firstOrNull { it.type == AvifContainer.EXIF }?.let { item ->
            val body = c.itemDataOrNull(item) ?: return@let null
            if (body.size < 4) return@let null
            // ISO/IEC 23008-12 A.2.1: a 4-byte offset from its end to the TIFF header, 6 after "Exif\0\0".
            val offset = ((body[0].toLong() and 0xFF) shl 24) or ((body[1].toLong() and 0xFF) shl 16) or
                ((body[2].toLong() and 0xFF) shl 8) or (body[3].toLong() and 0xFF)
            if (offset > body.size - 4L - 8) null else body.copyOfRange(4 + offset.toInt(), body.size)
        }
        val xmp = described.firstOrNull { it.type == AvifContainer.MIME && it.contentType == "application/rdf+xml" }
            ?.let { c.itemDataOrNull(it)?.decodeToString() }
        var icc: ByteArray? = null
        var at = c.items[primary]
        repeat(9) {
            val item = at ?: return@repeat
            if (icc == null) icc = item.property<AvifProperty.Icc>()?.profile
            at = c.referencesFrom(item.id, AvifContainer.DIMG).firstOrNull()?.let { c.items[it] }
        }
        return ImageMetadata(exif = exif, xmp = xmp, icc = icc)
    }

    private fun readJpeg(data: ByteArray): ImageMetadata {
        var exif: ByteArray? = null
        var xmp: String? = null
        var at = 2
        while (at + 4 <= data.size) {
            if ((data[at].toInt() and 0xFF) != 0xFF) break
            val marker = data[at + 1].toInt() and 0xFF
            if (marker == 0xFF) { at++; continue }
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { at += 2; continue }
            if (marker == 0xDA || marker == 0xD9) break
            val length = ((data[at + 2].toInt() and 0xFF) shl 8) or (data[at + 3].toInt() and 0xFF)
            if (length < 2 || at + 2 + length > data.size) break
            val start = at + 4
            val end = at + 2 + length
            if (marker == 0xE1) {
                if (exif == null && startsWith(data, start, end, EXIF_SIGNATURE)) exif = data.copyOfRange(start + 6, end)
                else if (xmp == null && startsWith(data, start, end, XMP_SIGNATURE.encodeToByteArray())) {
                    xmp = data.copyOfRange(start + XMP_SIGNATURE.length, end).decodeToString()
                }
            }
            at = end
        }
        return ImageMetadata(exif, xmp, ColorChunks.jpegIcc(data), UltraHdr.find(data))
    }

    private fun readPng(data: ByteArray): ImageMetadata {
        var exif: ByteArray? = null
        var xmp: String? = null
        var icc: ByteArray? = null
        var at = 8
        while (at + 12 <= data.size) {
            val length = be32(data, at)
            if (length < 0 || at + 12L + length > data.size) break
            val type = ascii(data, at + 4, 4)
            val body = data.copyOfRange(at + 8, at + 8 + length)
            when (type) {
                "eXIf" -> if (exif == null) exif = body
                "iTXt" -> if (xmp == null) xmp = pngXmp(body)
                "iCCP" -> if (icc == null) icc = pngIcc(body)
                "IEND" -> break
            }
            at += 12 + length
        }
        return ImageMetadata(exif, xmp, icc)
    }

    private fun pngXmp(body: ByteArray): String? {
        val nul = body.indexOf(0)
        if (nul < 0 || body.copyOfRange(0, nul).decodeToString() != XMP_KEYWORD || nul + 3 > body.size) return null
        val compressed = body[nul + 1].toInt() != 0
        var at = nul + 3
        // The language tag and the translated keyword, each ended by a 0.
        repeat(2) {
            while (at < body.size && body[at].toInt() != 0) at++
            at++
        }
        if (at > body.size) return null
        val text = body.copyOfRange(at, body.size)
        return runCatching { if (compressed) Zlib.decompress(text, MAX_TEXT, 0, checkAdler = false).decodeToString() else text.decodeToString() }.getOrNull()
    }

    private fun pngIcc(body: ByteArray): ByteArray? {
        val nul = body.indexOf(0)
        if (nul !in 1..79 || nul + 2 > body.size || body[nul + 1].toInt() != 0) return null
        return runCatching { Zlib.decompress(body.copyOfRange(nul + 2, body.size), MAX_TEXT, 0, checkAdler = false) }.getOrNull()
    }

    private fun readWebp(data: ByteArray): ImageMetadata {
        var exif: ByteArray? = null
        var xmp: String? = null
        var icc: ByteArray? = null
        var at = 12
        while (at + 8 <= data.size) {
            val type = ascii(data, at, 4)
            val length = le32(data, at + 4)
            if (length < 0 || at + 8L + length > data.size) break
            val body = data.copyOfRange(at + 8, at + 8 + length)
            when (type) {
                // Some writers keep the JPEG signature in front of the TIFF structure.
                "EXIF" -> if (exif == null) exif = if (startsWith(body, 0, body.size, EXIF_SIGNATURE)) body.copyOfRange(6, body.size) else body
                "XMP " -> if (xmp == null) xmp = body.decodeToString()
                "ICCP" -> if (icc == null) icc = body
            }
            at += 8 + length + (length and 1)
        }
        return ImageMetadata(exif, xmp, icc)
    }

    /** The APP1 and APP2 segments of [metadata], each that fits a segment: an XMP packet or EXIF block too long for one is left out. */
    fun jpegSegments(metadata: ImageMetadata): ByteArray {
        val out = ByteArrayBuilder(256)
        fun segment(marker: Int, parts: List<ByteArray>) {
            val length = 2 + parts.sumOf { it.size }
            out.append(byteArrayOf(0xFF.toByte(), marker.toByte(), (length ushr 8).toByte(), length.toByte()))
            for (p in parts) out.append(p)
        }
        metadata.exif?.let { if (it.size + 8 <= 0xFFFF) segment(0xE1, listOf(EXIF_SIGNATURE, it)) }
        metadata.xmp?.let {
            val packet = it.encodeToByteArray()
            val signature = XMP_SIGNATURE.encodeToByteArray()
            if (packet.size + signature.size + 2 <= 0xFFFF) segment(0xE1, listOf(signature, packet))
        }
        metadata.icc?.let { profile ->
            val chunk = 0xFFFF - 2 - 14
            val count = (profile.size + chunk - 1) / chunk
            if (count in 1..255) {
                for (i in 0 until count) {
                    val part = profile.copyOfRange(i * chunk, minOf(profile.size, (i + 1) * chunk))
                    segment(0xE2, listOf("ICC_PROFILE\u0000".encodeToByteArray(), byteArrayOf((i + 1).toByte(), count.toByte()), part))
                }
            }
        }
        return out.toByteArray()
    }

    /** The PNG chunks of [metadata], each as its type and body: `iCCP`, `eXIf` and `iTXt`. */
    fun pngChunks(metadata: ImageMetadata): List<Pair<String, ByteArray>> = buildList {
        metadata.icc?.let { add("iCCP" to "ICC Profile".encodeToByteArray() + byteArrayOf(0, 0) + Zlib.compress(it)) }
        metadata.exif?.let { add("eXIf" to it) }
        metadata.xmp?.let { add("iTXt" to XMP_KEYWORD.encodeToByteArray() + byteArrayOf(0, 0, 0, 0, 0) + it.encodeToByteArray()) }
    }

    /**
     * [exif] with its IFD0 orientation set to [orientation], or null when it has none. The
     * orientation is a SHORT of one value, which sits in the entry itself (TIFF 6.0, section 2).
     */
    fun withOrientation(exif: ByteArray, orientation: Int): ByteArray? {
        val at = orientationAt(exif) ?: return null
        val copy = exif.copyOf()
        if (copy[0].toInt() == 0x49) { copy[at] = orientation.toByte(); copy[at + 1] = 0 } else { copy[at] = 0; copy[at + 1] = orientation.toByte() }
        return copy
    }

    /** The IFD0 orientation of [exif], or null when it has none. */
    fun orientation(exif: ByteArray): Int? {
        val at = orientationAt(exif) ?: return null
        return if (exif[0].toInt() == 0x49) (exif[at].toInt() and 0xFF) or ((exif[at + 1].toInt() and 0xFF) shl 8)
            else ((exif[at].toInt() and 0xFF) shl 8) or (exif[at + 1].toInt() and 0xFF)
    }

    /** Where the value of the IFD0 orientation entry of [exif] sits, or null when it has none. */
    private fun orientationAt(exif: ByteArray): Int? {
        if (exif.size < 8) return null
        val little = exif[0].toInt() == 0x49 && exif[1].toInt() == 0x49
        if (!little && !(exif[0].toInt() == 0x4D && exif[1].toInt() == 0x4D)) return null
        fun u16(at: Int): Int = if (little) (exif[at].toInt() and 0xFF) or ((exif[at + 1].toInt() and 0xFF) shl 8)
            else ((exif[at].toInt() and 0xFF) shl 8) or (exif[at + 1].toInt() and 0xFF)
        fun u32(at: Int): Long = if (little) (u16(at).toLong() or (u16(at + 2).toLong() shl 16)) else ((u16(at).toLong() shl 16) or u16(at + 2).toLong())
        val ifd = u32(4)
        if (ifd < 8 || ifd + 2 > exif.size) return null
        val count = u16(ifd.toInt())
        for (i in 0 until count) {
            val entry = ifd.toInt() + 2 + i * 12
            if (entry + 12 > exif.size) return null
            if (u16(entry) != 0x0112 || u16(entry + 2) != 3) continue
            return entry + 8
        }
        return null
    }

    private fun startsWith(d: ByteArray, from: Int, end: Int, prefix: ByteArray): Boolean {
        if (end - from < prefix.size) return false
        for (i in prefix.indices) if (d[from + i] != prefix[i]) return false
        return true
    }

    private fun ascii(d: ByteArray, at: Int, n: Int): String = CharArray(n) { (d[at + it].toInt() and 0xFF).toChar() }.concatToString()

    private fun be32(d: ByteArray, at: Int): Int = ((d[at].toInt() and 0xFF) shl 24) or ((d[at + 1].toInt() and 0xFF) shl 16) or
        ((d[at + 2].toInt() and 0xFF) shl 8) or (d[at + 3].toInt() and 0xFF)

    private fun le32(d: ByteArray, at: Int): Int = (d[at].toInt() and 0xFF) or ((d[at + 1].toInt() and 0xFF) shl 8) or
        ((d[at + 2].toInt() and 0xFF) shl 16) or ((d[at + 3].toInt() and 0xFF) shl 24)
}
