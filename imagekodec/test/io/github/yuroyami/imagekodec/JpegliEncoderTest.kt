package io.github.yuroyami.imagekodec

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [JpegEncoderKind.Jpegli] (#133) on every target, without its oracle: the port writes the
 * files pyjpegli wrote for [JpegliVectors] byte for byte, its frame is the baseline 4:2:0 frame
 * jpegli's defaults give, it decodes close to its source, and it carries the metadata of #129
 * and #130 as the default encoder does. `JpegliOracleTest` holds it to pyjpegli on photo-sized
 * scenes where pyjpegli is installed.
 */
class JpegliEncoderTest {

    private class Segment(val marker: Int, val body: ByteArray)

    /** The marker segments of [jpeg] up to and including the scan header. */
    private fun segments(jpeg: ByteArray): List<Segment> = buildList {
        var at = 2
        while (true) {
            assertEquals(0xFF, jpeg[at].toInt() and 0xFF, "a marker at $at")
            val marker = jpeg[at + 1].toInt() and 0xFF
            val length = ((jpeg[at + 2].toInt() and 0xFF) shl 8) or (jpeg[at + 3].toInt() and 0xFF)
            add(Segment(marker, jpeg.copyOfRange(at + 4, at + 2 + length)))
            if (marker == 0xDA) break
            at += 2 + length
        }
    }

    private fun firstDifference(a: ByteArray, b: ByteArray): Int = (0 until minOf(a.size, b.size)).firstOrNull { a[it] != b[it] } ?: minOf(a.size, b.size)

    @Test
    fun the_files_are_jpeglis_byte_for_byte() {
        val cases = listOf(
            Triple(PhotoScenes.bokeh(37, 29), 75, JpegliVectors.BOKEH_37X29_Q75),
            Triple(PhotoScenes.foliage(64, 48), 40, JpegliVectors.FOLIAGE_64X48_Q40),
            Triple(PhotoScenes.city(96, 40), 95, JpegliVectors.CITY_96X40_Q95),
        )
        for ((scene, quality, expected) in cases) {
            val actual = ImageKodec.encodeJpeg(scene.bitmap, quality, JpegEncoderKind.Jpegli)
            assertTrue(
                actual.contentEquals(expected),
                "${scene.name} at $quality: ${actual.size} bytes, jpegli wrote ${expected.size}; first difference at byte ${firstDifference(actual, expected)}",
            )
        }
    }

    @Test
    fun the_frame_is_baseline_4_2_0_with_jpeglis_tables_and_no_jfif_header() {
        val scene = PhotoScenes.landscape(70, 45)
        val jpeg = ImageKodec.encodeJpeg(scene.bitmap, 90, JpegEncoderKind.Jpegli)
        val segments = segments(jpeg)
        assertEquals(listOf(0xDB, 0xC0, 0xC4, 0xDA), segments.map { it.marker }, "SOI, then DQT, SOF0, DHT and SOS")
        assertEquals(0xFFD9, ((jpeg[jpeg.size - 2].toInt() and 0xFF) shl 8) or (jpeg[jpeg.size - 1].toInt() and 0xFF), "EOI")

        // Two 8-bit tables, luma then chroma, each in zigzag order.
        val dqt = segments[0].body
        assertEquals(2 * 65, dqt.size)
        assertEquals(0x00, dqt[0].toInt())
        assertEquals(0x01, dqt[65].toInt())
        // jpegli's tables at quality 90 (distance 1.0), as pyjpegli 0.3.2 writes them.
        assertContentEquals(
            intArrayOf(3, 4, 4, 6, 4, 6, 6, 6, 6, 6, 7, 6, 6, 6, 7, 7, 7, 7, 7, 7, 7, 8, 7, 8, 7, 8, 7, 8, 8, 9, 8, 9, 9, 8, 9, 8, 9, 8, 10, 10, 10, 8, 9, 9, 10, 10, 10, 10, 9, 10, 12, 12, 12, 10, 12, 11, 11, 12, 13, 12, 13, 11, 11, 9),
            IntArray(64) { dqt[1 + it].toInt() and 0xFF },
            "the luma table",
        )
        assertContentEquals(
            intArrayOf(3, 3, 3, 5, 4, 5, 5, 5, 5, 5, 6, 4, 5, 4, 6, 5, 5, 5, 5, 5, 5, 6, 5, 5, 4, 5, 5, 6, 7, 6, 5, 6, 6, 5, 6, 7, 6, 6, 6, 4, 6, 6, 6, 6, 6, 7, 7, 6, 6, 7, 5, 6, 5, 7, 7, 7, 7, 7, 10, 11, 10, 10, 10, 82),
            IntArray(64) { dqt[66 + it].toInt() and 0xFF },
            "the chroma table",
        )

        val sof = segments[1].body.map { it.toInt() and 0xFF }
        assertEquals(listOf(8, 0, 45, 0, 70, 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1), sof, "8-bit, 70x45, Y at 2x2 with table 0, Cb and Cr at 1x1 with table 1")

        // The example tables of T.81 Annex K: luma DC and AC, then chroma DC and AC.
        val dht = segments[2].body
        assertEquals(listOf(0x00, 0x10, 0x01, 0x11), listOf(0, 29, 29 + 179, 29 + 179 + 29).map { dht[it].toInt() and 0xFF })
        assertEquals(29 + 179 + 29 + 179, dht.size)

        assertEquals(listOf(3, 1, 0x00, 2, 0x11, 3, 0x11, 0, 63, 0), segments[3].body.map { it.toInt() and 0xFF }, "one interleaved scan of 0..63")
    }

    @Test
    fun the_decode_is_close_to_the_source() {
        for (scene in listOf(PhotoScenes.landscape(160, 120), PhotoScenes.portrait(96, 128), PhotoScenes.bokeh(101, 67))) {
            val source = scene.bitmap
            val decoded = ImageKodec.decode(ImageKodec.encodeJpeg(source, 90, JpegEncoderKind.Jpegli))
            assertEquals(source.width, decoded.width)
            assertEquals(source.height, decoded.height)
            var error = 0L
            for (i in source.argb.indices) {
                val a = source.argb[i]
                val b = decoded.argb[i]
                for (shift in intArrayOf(0, 8, 16)) error += abs(((a ushr shift) and 0xFF) - ((b ushr shift) and 0xFF))
            }
            val mean = error.toDouble() / (3 * source.argb.size)
            assertTrue(mean < 4.0, "${scene.name}: a mean error of $mean levels")
            val score = ImageKodec.ssimulacra2(source, decoded)
            assertTrue(score > 60, "${scene.name}: SSIMULACRA 2 of $score at quality 90")
        }
    }

    @Test
    fun it_is_smaller_than_the_fast_encoder_at_the_same_score() {
        val source = PhotoScenes.landscape(256, 192).bitmap
        val jpegli = ImageKodec.encodeJpeg(source, 90, JpegEncoderKind.Jpegli)
        val target = ImageKodec.ssimulacra2(source, ImageKodec.decode(jpegli))
        // The lowest quality at which the fast encoder scores as well: its score rises with its quality.
        var lo = 1
        var hi = 100
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (ImageKodec.ssimulacra2(source, ImageKodec.decode(ImageKodec.encodeJpeg(source, mid))) >= target) hi = mid else lo = mid + 1
        }
        val fast = ImageKodec.encodeJpeg(source, lo)
        assertTrue(jpegli.size < fast.size, "Jpegli ${jpegli.size} bytes at SSIMULACRA 2 $target, the fast encoder ${fast.size} at its quality $lo")
    }

    private val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
        """<rdf:Description xmlns:dc="http://purl.org/dc/elements/1.1/" dc:format="image/jpeg"/></rdf:RDF></x:xmpmeta>"""

    private val exif = byteArrayOf(0x49, 0x49, 42, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    private val icc = ByteArray(300) { (it * 7).toByte() }.also { it[0] = 0; it[1] = 0; it[2] = 1; it[3] = 44 }

    @Test
    fun the_metadata_follows_soi_and_reads_back() {
        val bitmap = PhotoScenes.bokeh(40, 30).bitmap
        val plain = ImageKodec.encodeJpeg(bitmap, 80, JpegEncoderKind.Jpegli)
        val jpeg = ImageKodec.encodeJpeg(bitmap, 80, ImageMetadata(exif, xmp, icc), JpegEncoderKind.Jpegli)
        val markers = segments(jpeg).map { it.marker }
        assertEquals(listOf(0xE1, 0xE1, 0xE2, 0xDB, 0xC0, 0xC4, 0xDA), markers, "EXIF, XMP and ICC right after SOI, then jpegli's segments")
        val back = ImageKodec.readMetadata(jpeg)
        assertContentEquals(exif, back.exif)
        assertEquals(xmp, back.xmp)
        assertContentEquals(icc, back.icc)
        val head = jpeg.size - plain.size
        assertContentEquals(plain.copyOfRange(2, plain.size), jpeg.copyOfRange(2 + head, jpeg.size), "the image itself is the one written without metadata")
    }

    @Test
    fun a_gain_map_goes_after_the_image_with_its_offsets() {
        val gainMapXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
            """<rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0" hdrgm:GainMapMax="2.3" """ +
            """hdrgm:HDRCapacityMax="2.3"/></rdf:RDF></x:xmpmeta>"""
        val gainMapJpeg = ImageKodec.encodeJpeg(KiteBitmap(16, 12, IntArray(192) { 0xFF000000.toInt() or ((it % 16) * 16 * 0x010101) }), 95, ImageMetadata(xmp = gainMapXmp))
        val bitmap = PhotoScenes.landscape(64, 48).bitmap
        val file = ImageKodec.encodeJpeg(bitmap, 85, ImageMetadata(exif, xmp, gainMap = GainMap(gainMapJpeg)), JpegEncoderKind.Jpegli)
        assertContentEquals(gainMapJpeg, file.copyOfRange(file.size - gainMapJpeg.size, file.size), "the gain map, unchanged, at the end")
        val gainMap = assertNotNull(ImageKodec.readMetadata(file).gainMap, "the MPF segment leads to the gain map")
        assertContentEquals(gainMapJpeg, gainMap.jpeg)
        assertEquals(bitmap.width, ImageKodec.decode(file).width)
    }

    @Test
    fun a_quality_outside_1_to_100_is_refused() {
        val bitmap = PhotoScenes.bokeh(8, 8).bitmap
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeJpeg(bitmap, 0, JpegEncoderKind.Jpegli) }
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeJpeg(bitmap, 101, JpegEncoderKind.Jpegli) }
    }
}
