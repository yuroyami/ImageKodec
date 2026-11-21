package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** readMetadata gives the EXIF, XMP and ICC of a HEIF file's primary image (#149). */
class HeifMetadataTest {

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray) = be32(8 + payload.size) + type.encodeToByteArray() + payload
    private fun ispe(w: Int, h: Int) = box("ispe", ByteArray(4) + be32(w) + be32(h)) to false
    private val tiff = byteArrayOf(0x49, 0x49, 0x2A, 0, 8, 0, 0, 0, 0, 0)
    private val icc = ByteArray(132) { (it * 3).toByte() }
    private val xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>"

    /** A primary item of [type] with an ICC colr, an Exif item whose offset is [offset], an XMP item and their cdsc references. */
    private fun file(major: String, brands: List<String>, type: String, offset: Int) = HeifBuilder(major, brands).apply {
        val config = if (type == "hvc1") box("hvcC", AnnexB.hvcC(emptyList())) else box("av1C", byteArrayOf(0x81.toByte(), 0, 0, 0))
        add(1, type, byteArrayOf(0, 0, 0, 0), listOf(config to true, ispe(16, 16), box("colr", "prof".encodeToByteArray() + icc) to false))
        val exifPayload = be32(offset) + (if (offset == 6) "Exif".encodeToByteArray() + byteArrayOf(0, 0) else ByteArray(0)) + tiff
        add(2, "Exif", exifPayload, emptyList(), hidden = true)
        add(3, "mime", xmp.encodeToByteArray(), emptyList(), hidden = true, contentType = "application/rdf+xml")
        // A metadata item that describes another item stays out.
        add(4, "Exif", be32(0) + byteArrayOf(0x4D, 0x4D, 0, 0x2A, 0, 0, 0, 8), emptyList(), hidden = true)
        add(5, type, byteArrayOf(0, 0, 0, 0), listOf(config to true, ispe(8, 8)), hidden = true)
        references += Triple("cdsc", 2L, longArrayOf(1))
        references += Triple("cdsc", 3L, longArrayOf(1))
        references += Triple("cdsc", 4L, longArrayOf(5))
    }.build()

    @Test
    fun aHeicGivesItsThreePayloads() {
        for (offset in listOf(0, 6)) {
            val m = ImageKodec.readMetadata(file("heic", listOf("mif1", "heic"), "hvc1", offset))
            assertContentEquals(tiff, m.exif, "offset $offset")
            assertEquals(xmp, m.xmp)
            assertContentEquals(icc, m.icc)
        }
    }

    @Test
    fun anAvifGivesThemToo() {
        val m = ImageKodec.readMetadata(file("avif", listOf("mif1", "avif"), "av01", 6))
        assertContentEquals(tiff, m.exif)
        assertEquals(xmp, m.xmp)
        assertContentEquals(icc, m.icc)
    }

    @Test
    fun aFileWithoutMetadataGivesNone() {
        val bare = HeifBuilder("heic", listOf("mif1", "heic")).apply {
            add(1, "hvc1", ByteArray(4), listOf(box("hvcC", AnnexB.hvcC(emptyList())) to true, ispe(16, 16)))
        }.build()
        val m = ImageKodec.readMetadata(bare)
        assertNull(m.exif)
        assertNull(m.xmp)
        assertNull(m.icc)
    }
}
