package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.JpegEncoder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [ImageKodec.repackJpeg] writes the coefficients of a JPEG again with tables built for it (#132):
 * the output is smaller and decodes to the same pixels, baseline or progressive.
 */
class JpegRepackTest {

    /** A photo-like image: gradients, edges and noise, at a size that leaves partial MCUs. */
    private fun photo(width: Int, height: Int, seed: Int): KiteBitmap {
        val random = Random(seed)
        return KiteBitmap(width, height, IntArray(width * height) {
            val x = it % width
            val y = it / width
            val edge = if ((x / 13 + y / 9) % 3 == 0) 60 else 0
            val r = (x * 255 / width + edge + random.nextInt(24)).coerceIn(0, 255)
            val g = (y * 255 / height + random.nextInt(24)).coerceIn(0, 255)
            val b = ((x + y) * 128 / (width + height) + edge + random.nextInt(24)).coerceIn(0, 255)
            0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        })
    }

    private fun check(input: ByteArray, what: String) {
        val pixels = ImageKodec.decode(input).argb
        for (progressive in listOf(false, true)) {
            val out = ImageKodec.repackJpeg(input, progressive)
            assertTrue(out.size < input.size, "$what, progressive=$progressive: ${out.size} is not smaller than ${input.size}")
            assertContentEquals(pixels, ImageKodec.decode(out).argb, "$what, progressive=$progressive: the pixels changed")
            assertEquals(if (progressive) 0xC2 else 0xC0, frameMarker(out), what)
            // A repacked file read back gives the same coefficients again.
            val again = ImageKodec.repackJpeg(out, !progressive)
            assertContentEquals(pixels, ImageKodec.decode(again).argb, "$what: repacked twice")
        }
    }

    private fun frameMarker(data: ByteArray): Int {
        var at = 2
        while (true) {
            val marker = data[at + 1].toInt() and 0xFF
            if (marker in 0xC0..0xC2) return marker
            at += 2 + (((data[at + 2].toInt() and 0xFF) shl 8) or (data[at + 3].toInt() and 0xFF))
        }
    }

    @Test
    fun the_encoders_output_gets_smaller_with_the_same_pixels() {
        check(ImageKodec.encodeJpeg(photo(203, 117, 1), 50), "4:2:0 at quality 50")
        check(ImageKodec.encodeJpeg(photo(99, 141, 2), 90), "4:2:0 at quality 90")
        check(ImageKodec.encodeJpeg(photo(64, 40, 3), 97), "4:4:4 at quality 97")
        check(JpegEncoder.encode(photo(77, 53, 4), 85, null, gray = true), "gray")
    }

    @Test
    fun segments_and_a_gain_map_after_the_image_stay() {
        val gainXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
            """<rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0" hdrgm:GainMapMax="1"/></rdf:RDF></x:xmpmeta>"""
        val gainMap = ImageKodec.encodeJpeg(photo(30, 20, 5), 90, ImageMetadata(xmp = gainXmp))
        val icc = ByteArray(300) { (it * 3).toByte() }.also { it[2] = 1; it[3] = 44 }
        val file = ImageKodec.encodeJpeg(photo(120, 80, 6), 90, ImageMetadata(icc = icc, gainMap = GainMap(gainMap)))
        val out = ImageKodec.repackJpeg(file)
        assertTrue(out.size < file.size)
        val metadata = ImageKodec.readMetadata(out)
        assertContentEquals(icc, metadata.icc)
        assertContentEquals(gainMap, assertNotNull(metadata.gainMap).jpeg, "the gain map, found through the moved MPF offsets")
        assertContentEquals(ImageKodec.decode(file).argb, ImageKodec.decode(out).argb)
    }

    @Test
    fun what_it_cannot_shrink_comes_back_as_it_is() {
        val png = ImageKodec.encodePng(photo(8, 8, 7))
        assertSame(png, ImageKodec.repackJpeg(png))
        val truncated = ImageKodec.encodeJpeg(photo(40, 40, 8), 90).let { it.copyOf(it.size / 3) }
        assertSame(truncated, ImageKodec.repackJpeg(truncated))
        val optimal = ImageKodec.repackJpeg(ImageKodec.encodeJpeg(photo(40, 40, 9), 90))
        assertSame(optimal, ImageKodec.repackJpeg(optimal), "already optimal tables")
    }
}
