package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A photo's EXIF block, XMP packet and ICC profile survive a decode and a re-encode to JPEG, PNG
 * and WebP, read back by [ImageKodec.readMetadata] (#129).
 */
class MetadataRoundTripTest {

    /** A little-endian EXIF block whose IFD0 holds an orientation of [orientation] and a DateTime. */
    private fun exif(orientation: Int): ByteArray {
        val date = "2026:11:09 08:30:00\u0000".encodeToByteArray()
        val out = ArrayList<Byte>()
        fun u16(v: Int) { out += v.toByte(); out += (v ushr 8).toByte() }
        fun u32(v: Int) { u16(v and 0xFFFF); u16(v ushr 16) }
        out += 0x49; out += 0x49; u16(42); u32(8)
        u16(2)
        // Orientation, SHORT, 1 value.
        u16(0x0112); u16(3); u32(1); u16(orientation); u16(0)
        // DateTime, ASCII, 20 values, at offset 8 + 2 + 24 + 4.
        u16(0x0132); u16(2); u32(date.size); u32(8 + 2 + 24 + 4)
        u32(0)
        for (b in date) out += b
        return out.toByteArray()
    }

    private val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
        """<rdf:Description xmlns:dc="http://purl.org/dc/elements/1.1/" dc:format="image/jpeg"/></rdf:RDF></x:xmpmeta>"""

    /** An ICC profile of [size] bytes whose header gives its size, as libpng and browsers check. */
    private fun icc(size: Int): ByteArray = ByteArray(size) { (it * 7).toByte() }.also {
        it[0] = (size ushr 24).toByte(); it[1] = (size ushr 16).toByte(); it[2] = (size ushr 8).toByte(); it[3] = size.toByte()
    }

    private val bitmap = KiteBitmap(4, 3, IntArray(12) { 0xFF000000.toInt() or (it * 0x101010) })

    private fun assertSame(expected: ImageMetadata, actual: ImageMetadata, what: String) {
        assertContentEquals(expected.exif, actual.exif, "$what: the EXIF block")
        assertEquals(expected.xmp, actual.xmp, "$what: the XMP packet")
        assertContentEquals(expected.icc, actual.icc, "$what: the ICC profile")
    }

    @Test
    fun jpeg_png_and_webp_keep_the_metadata() {
        val metadata = ImageMetadata(exif(6), xmp, icc(600))
        val jpeg = ImageKodec.encodeJpeg(bitmap, 90, metadata)
        assertSame(metadata, ImageKodec.readMetadata(jpeg), "JPEG")
        assertEquals(4, ImageKodec.decode(jpeg).width)
        assertEquals(Orientation.Rotate90, ImageKodec.probe(jpeg).orientation)

        val png = ImageKodec.encodePng(bitmap, metadata)
        assertSame(metadata, ImageKodec.readMetadata(png), "PNG")
        assertContentEquals(bitmap.argb, ImageKodec.decode(png).argb)

        val webp = ImageKodec.encodeWebp(bitmap, metadata)
        assertSame(metadata, ImageKodec.readMetadata(webp), "WebP")
        assertContentEquals(bitmap.argb, ImageKodec.decode(webp).argb)
    }

    @Test
    fun a_profile_longer_than_one_segment_spans_several() {
        val metadata = ImageMetadata(icc = icc(150_000))
        val jpeg = ImageKodec.encodeJpeg(bitmap, 90, metadata)
        assertContentEquals(metadata.icc, ImageKodec.readMetadata(jpeg).icc)
    }

    @Test
    fun upright_metadata_sets_the_orientation_to_1() {
        val upright = ImageMetadata(exif(6), xmp).upright()
        val jpeg = ImageKodec.encodeJpeg(bitmap, 90, upright)
        assertEquals(Orientation.Normal, ImageKodec.probe(jpeg).orientation)
        assertEquals(xmp, upright.xmp)
    }

    @Test
    fun a_file_without_metadata_gives_none() {
        val plain = ImageKodec.readMetadata(ImageKodec.encodePng(bitmap))
        assertNull(plain.exif)
        assertNull(plain.xmp)
        assertNull(plain.icc)
    }
}
