package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.ImageMetadata
import io.github.yuroyami.imagekodec.internal.flate.ByteArrayBuilder
import io.github.yuroyami.imagekodec.internal.flate.Zlib

/**
 * Reads and writes the EXIF, XMP and ICC metadata of JPEG, PNG and WebP files (#129): JPEG APP1
 * `Exif` and Adobe XMP segments and APP2 `ICC_PROFILE` chunks (Exif 2.32, 4.5.4; XMP Part 3,
 * 1.1.3; ICC.1:2022, B.4), PNG `eXIf`, `iTXt` with the keyword `XML:com.adobe.xmp`, and `iCCP`
 * (PNG, Third Edition, 11.3), and the `EXIF`, `XMP ` and `ICCP` chunks of extended WebP.
 */
internal object MetadataIo {

    private val EXIF_SIGNATURE = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0) // "Exif\0\0"
    private const val XMP_SIGNATURE = "http://ns.adobe.com/xap/1.0/\u0000"
    private const val XMP_KEYWORD = "XML:com.adobe.xmp"
    private const val MAX_TEXT = 16L shl 20

    fun read(data: ByteArray): ImageMetadata = when {
        data.size >= 3 && (data[0].toInt() and 0xFF) == 0xFF && (data[1].toInt() and 0xFF) == 0xD8 -> readJpeg(data)
        data.size >= 8 && (data[0].toInt() and 0xFF) == 0x89 && data[1].toInt() == 0x50 && data[2].toInt() == 0x4E && data[3].toInt() == 0x47 -> readPng(data)
        data.size >= 12 && ascii(data, 0, 4) == "RIFF" && ascii(data, 8, 4) == "WEBP" -> readWebp(data)
        else -> ImageMetadata()
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
        return runCatching { if (compressed) Zlib.decompress(text, MAX_TEXT, 0).decodeToString() else text.decodeToString() }.getOrNull()
    }

    private fun pngIcc(body: ByteArray): ByteArray? {
        val nul = body.indexOf(0)
        if (nul !in 1..79 || nul + 2 > body.size || body[nul + 1].toInt() != 0) return null
        return runCatching { Zlib.decompress(body.copyOfRange(nul + 2, body.size), MAX_TEXT, 0) }.getOrNull()
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
