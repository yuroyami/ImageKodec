package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The gain map of an Ultra HDR JPEG survives a decode and a re-encode with its metadata (#130):
 * [ImageKodec.readMetadata] finds it through the MPF segment, and `encodeJpeg` writes it after the
 * new main image with an MPF segment and an XMP directory that point to it.
 */
class GainMapTest {

    private val primaryXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
        """<rdf:Description xmlns:Container="http://ns.google.com/photos/1.0/container/" xmlns:Item="http://ns.google.com/photos/1.0/container/item/" """ +
        """xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0"><Container:Directory><rdf:Seq>""" +
        """<rdf:li rdf:parseType="Resource"><Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg"/></rdf:li>""" +
        """<rdf:li rdf:parseType="Resource"><Container:Item Item:Semantic="GainMap" Item:Mime="image/jpeg" Item:Length="1"/></rdf:li>""" +
        """</rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""

    private val gainMapXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
        """<rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0" hdrgm:GainMapMax="2.3" """ +
        """hdrgm:HDRCapacityMax="2.3"/></rdf:RDF></x:xmpmeta>"""

    private val iso = "urn:iso:std:iso:ts:21496:-1\u0000".encodeToByteArray() + byteArrayOf(0, 0, 0, 0)

    private fun bitmap(width: Int, height: Int, gray: Boolean = false) = KiteBitmap(width, height, IntArray(width * height) {
        val x = it % width
        val y = it / width
        val v = (x * 255 / width) and 0xFF
        if (gray) 0xFF000000.toInt() or (v * 0x010101) else 0xFF000000.toInt() or (v shl 16) or ((y * 255 / height) shl 8) or 0x40
    })

    /** A gain map whose left half is dark and right half bright, so a turn shows. */
    private val gainMapJpeg = ImageKodec.encodeJpeg(bitmap(16, 12, gray = true), 95, ImageMetadata(xmp = gainMapXmp))

    private fun exif(orientation: Int): ByteArray {
        val out = ArrayList<Byte>()
        fun u16(v: Int) { out += v.toByte(); out += (v ushr 8).toByte() }
        out += 0x49; out += 0x49; u16(42); u16(8); u16(0)
        u16(1); u16(0x0112); u16(3); u16(1); u16(0); u16(orientation); u16(0)
        u16(0); u16(0)
        return out.toByteArray()
    }

    private fun ultraHdr(width: Int = 64, height: Int = 48, exif: ByteArray? = null): ByteArray =
        ImageKodec.encodeJpeg(bitmap(width, height), 90, ImageMetadata(exif, primaryXmp, gainMap = GainMap(gainMapJpeg, iso)))

    /** The offset and size of the second image in the MPF segment of [data], read by hand. */
    private fun secondImage(data: ByteArray): Pair<Int, Int> {
        fun u16(at: Int) = ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)
        fun u32(at: Int) = (u16(at) shl 16) or u16(at + 2)
        var at = 2
        while (u16(at) != 0xFFE2 || data.copyOfRange(at + 4, at + 8).decodeToString() != "MPF\u0000") at += 2 + u16(at + 2)
        val tiff = at + 8
        val entry = tiff + 50 + 16
        return tiff + u32(entry + 8) to u32(entry + 4)
    }

    @Test
    fun the_gain_map_comes_back_unchanged_after_the_main_image() {
        val file = ultraHdr()
        val (offset, size) = secondImage(file)
        assertEquals(file.size - gainMapJpeg.size, offset, "the MPF offset of the gain map")
        assertEquals(gainMapJpeg.size, size)
        assertContentEquals(gainMapJpeg, file.copyOfRange(offset, offset + size))

        val metadata = ImageKodec.readMetadata(file)
        val gainMap = assertNotNull(metadata.gainMap)
        assertContentEquals(gainMapJpeg, gainMap.jpeg)
        assertContentEquals(iso, gainMap.primaryIso)
        assertEquals(16, gainMap.width)
        assertTrue(metadata.xmp!!.contains("""Item:Semantic="GainMap" Item:Mime="image/jpeg" Item:Length="${gainMapJpeg.size}""""), metadata.xmp)
        assertTrue(metadata.xmp!!.contains("""hdrgm:Version="1.0""""))
        assertEquals(64, ImageKodec.decode(file).width)

        // A second re-encode, of a smaller image of the same shape, keeps it again.
        val again = ImageKodec.encodeJpeg(bitmap(32, 24), 80, metadata)
        assertContentEquals(gainMapJpeg, ImageKodec.readMetadata(again).gainMap?.jpeg)
    }

    @Test
    fun a_crop_drops_the_gain_map_and_its_hdr_fields() {
        val metadata = ImageKodec.readMetadata(ultraHdr())
        val cropped = ImageKodec.encodeJpeg(bitmap(64, 30), 90, metadata)
        val back = ImageKodec.readMetadata(cropped)
        assertNull(back.gainMap)
        assertFalse(back.xmp!!.contains("hdrgm"), back.xmp)
        assertFalse(back.xmp!!.contains("GainMap"), back.xmp)
        assertTrue(back.xmp!!.contains("rdf:Description"))

        // Metadata that names a gain map it does not carry loses the names too.
        val bare = ImageKodec.readMetadata(ImageKodec.encodeJpeg(bitmap(8, 8), 90, ImageMetadata(xmp = primaryXmp)))
        assertFalse(bare.xmp!!.contains("hdrgm"), bare.xmp)
    }

    @Test
    fun upright_turns_the_gain_map_with_the_pixels() {
        val file = ultraHdr(exif = exif(6))
        val upright = ImageKodec.readMetadata(file).upright()
        val gainMap = assertNotNull(upright.gainMap)
        assertEquals(12, gainMap.width)
        assertEquals(16, gainMap.height)
        // A quarter turn clockwise puts the dark left column on top and the bright right one at the bottom.
        val turned = ImageKodec.decode(gainMap.jpeg)
        assertTrue((turned.argb[0] and 0xFF) < 60, "top left after the turn")
        assertTrue((turned.argb[15 * 12] and 0xFF) > 200, "bottom left after the turn")
        assertTrue(ImageKodec.readMetadata(gainMap.jpeg).xmp!!.contains("hdrgm:GainMapMax"), "the gain map keeps its parameters")
        assertEquals(1, gainMap.jpeg.let { sofComponents(it) }, "a gray gain map stays single-channel")

        val out = ImageKodec.encodeJpeg(ImageKodec.decode(file, applyOrientation = true), 90, upright)
        assertEquals(48, ImageKodec.decode(out).width)
        assertContentEquals(gainMap.jpeg, ImageKodec.readMetadata(out).gainMap?.jpeg)
    }

    @Test
    fun a_second_image_that_is_no_gain_map_is_not_one() {
        // A plain MPO-style pair: the second image has no hdrgm fields and no ISO 21496-1 segment.
        val plain = ImageKodec.encodeJpeg(bitmap(16, 12), 90)
        val file = ImageKodec.encodeJpeg(bitmap(64, 48), 90, ImageMetadata(gainMap = GainMap(plain)))
        assertEquals(plain.size, secondImage(file).second)
        assertNull(ImageKodec.readMetadata(file).gainMap)
    }

    private fun sofComponents(data: ByteArray): Int {
        var at = 2
        while (true) {
            val marker = data[at + 1].toInt() and 0xFF
            val length = ((data[at + 2].toInt() and 0xFF) shl 8) or (data[at + 3].toInt() and 0xFF)
            if (marker == 0xC0) return data[at + 9].toInt()
            at += 2 + length
        }
    }
}
