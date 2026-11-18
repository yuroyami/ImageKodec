package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.ImageMetadata

/**
 * The JP2 file format of T.800 Annex I around a codestream: the signature box, the file type box,
 * the JP2 header box with the image header, the colour specification and, for alpha, the channel
 * definitions, then the metadata in UUID boxes and the contiguous codestream box.
 *
 * XMP goes in the UUID box that XMP Part 3 (1.1.4) assigns to JPEG 2000, and EXIF, its TIFF
 * structure alone, in the UUID box that exiftool reads as `JpgTiffExif->JP2`; exiftool warns of a
 * non-standard box when the `Exif\0\0` signature of a JPEG APP1 segment comes first. An ICC profile replaces the enumerated colour space when its data colour space matches
 * the image's (I.5.3.3, method 2, a restricted profile).
 */
internal object Jp2Boxes {

    private val XMP_UUID = byteArrayOf(
        0xBE.toByte(), 0x7A, 0xCF.toByte(), 0xCB.toByte(), 0x97.toByte(), 0xA9.toByte(), 0x42, 0xE8.toByte(),
        0x9C.toByte(), 0x71, 0x99.toByte(), 0x94.toByte(), 0x91.toByte(), 0xE3.toByte(), 0xAF.toByte(), 0xAC.toByte(),
    )

    /** "JpgTiffExif->JP2" in ASCII. */
    private val EXIF_UUID = "JpgTiffExif->JP2".encodeToByteArray()

    private val EXIF_SIGNATURE = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0)

    fun file(
        width: Int, height: Int, components: Int, precision: Int, gray: Boolean, alpha: Boolean,
        metadata: ImageMetadata, codestream: ByteArray,
    ): ByteArray {
        val out = Jp2ByteBuffer(codestream.size + 256)
        header(out, width, height, components, precision, gray, alpha, metadata)
        out.u32(8L + codestream.size)
        out.add(type("jp2c"))
        out.add(codestream)
        return out.toByteArray()
    }

    /** The bytes [file] adds to a codestream. */
    fun overhead(components: Int, alpha: Boolean, metadata: ImageMetadata): Int {
        val out = Jp2ByteBuffer(256)
        header(out, 1, 1, components, 8, components <= 2, alpha, metadata)
        return out.size + 8
    }

    private fun header(
        out: Jp2ByteBuffer, width: Int, height: Int, components: Int, precision: Int, gray: Boolean, alpha: Boolean,
        metadata: ImageMetadata,
    ) {
        out.u32(12); out.add(type("jP  ")); out.u32(0x0D0A870AL)
        out.u32(20); out.add(type("ftyp")); out.add(type("jp2 ")); out.u32(0); out.add(type("jp2 "))

        val jp2h = Jp2ByteBuffer(128)
        // ihdr (I.5.3.1): compression type 7, the colour space known, no intellectual property box.
        jp2h.u32(22); jp2h.add(type("ihdr"))
        jp2h.u32(height.toLong()); jp2h.u32(width.toLong())
        jp2h.u16(components); jp2h.add(precision - 1); jp2h.add(7); jp2h.add(0); jp2h.add(0)
        val icc = metadata.icc?.takeIf { profileFits(it, gray) }
        if (icc != null) {
            jp2h.u32(11L + icc.size); jp2h.add(type("colr")); jp2h.add(2); jp2h.add(0); jp2h.add(0); jp2h.add(icc)
        } else {
            jp2h.u32(15); jp2h.add(type("colr")); jp2h.add(1); jp2h.add(0); jp2h.add(0); jp2h.u32(if (gray) 17L else 16L)
        }
        if (alpha) {
            // cdef (I.5.3.6): each colour channel its own colour, the last one opacity for the whole image.
            jp2h.u32(10L + 6 * components); jp2h.add(type("cdef")); jp2h.u16(components)
            for (c in 0 until components) {
                jp2h.u16(c)
                if (c == components - 1) { jp2h.u16(1); jp2h.u16(0) } else { jp2h.u16(0); jp2h.u16(c + 1) }
            }
        }
        out.u32(8L + jp2h.size); out.add(type("jp2h")); out.add(jp2h.toByteArray())

        metadata.exif?.let { exif ->
            out.u32(8L + 16 + exif.size); out.add(type("uuid")); out.add(EXIF_UUID); out.add(exif)
        }
        metadata.xmp?.let { xmp ->
            val bytes = xmp.encodeToByteArray()
            out.u32(8L + 16 + bytes.size); out.add(type("uuid")); out.add(XMP_UUID); out.add(bytes)
        }
    }

    /** Whether [icc]'s data colour space (ICC.1 7.2.6) is that of a gray or an RGB image. */
    private fun profileFits(icc: ByteArray, gray: Boolean): Boolean {
        if (icc.size < 128) return false
        val space = icc.copyOfRange(16, 20).decodeToString()
        return if (gray) space == "GRAY" else space == "RGB "
    }

    private fun type(name: String): ByteArray = name.encodeToByteArray()

    /** The XMP packet of a JP2 file's XMP UUID box, the EXIF block of its EXIF UUID box and its ICC profile, for `ImageKodec.readMetadata`. */
    fun read(data: ByteArray): ImageMetadata {
        var exif: ByteArray? = null
        var xmp: String? = null
        var p = 0
        while (p + 8 <= data.size) {
            val len = u32(data, p)
            val type = data.copyOfRange(p + 4, p + 8).decodeToString()
            val end = when {
                len == 0L -> data.size.toLong()
                len < 8 -> break
                else -> p + len
            }
            if (end > data.size) break
            if (type == "uuid" && end - p >= 24) {
                val id = data.copyOfRange(p + 8, p + 24)
                val body = data.copyOfRange(p + 24, end.toInt())
                if (xmp == null && id.contentEquals(XMP_UUID)) xmp = body.decodeToString()
                if (exif == null && id.contentEquals(EXIF_UUID)) {
                    val signed = body.size >= 6 && body.copyOfRange(0, 6).contentEquals(EXIF_SIGNATURE)
                    exif = if (signed) body.copyOfRange(6, body.size) else body
                }
            }
            // ExifTool writes the UUID boxes after the codestream, so the walk goes on past it (#186).
            if (len == 0L) break
            p = end.toInt()
        }
        val icc = try {
            Jp2Color.parse(data)?.colors?.firstOrNull { it.icc != null }?.icc
        } catch (_: Exception) {
            null
        }
        return ImageMetadata(exif, xmp, icc)
    }

    private fun u32(d: ByteArray, p: Int): Long =
        ((d[p].toLong() and 0xFF) shl 24) or ((d[p + 1].toLong() and 0xFF) shl 16) or
            ((d[p + 2].toLong() and 0xFF) shl 8) or (d[p + 3].toLong() and 0xFF)
}
