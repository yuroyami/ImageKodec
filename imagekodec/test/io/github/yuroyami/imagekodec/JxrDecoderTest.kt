package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.jxr.JxrDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * JPEG XR files that jxrlib wrote (#125), on every target: each must give exactly the samples
 * jxrlib's JxrDecApp reads, whose hashes [JxrFixtures] holds, and damage must fail cleanly.
 * `JxrOracleTest` compares many more files with JxrDecApp itself on the JVM.
 */
class JxrDecoderTest {

    /** The FNV-1a hash of [values], each as four bytes, least significant first, as tools/jxr_fixtures.py hashes them. */
    private fun fnv(values: IntArray): Long {
        var h = -0x340d631b7bdddcdbL
        for (v in values) for (k in 0 until 4) h = (h xor ((v ushr (8 * k)) and 255).toLong()) * 0x100000001b3L
        return h
    }

    /** The decoded samples in the order JxrDecApp writes a TIFF: gray, or red, green, blue and alpha; a packed pixel as one value. */
    private fun samples(data: ByteArray): IntArray = samples(JxrDecoder.decodeRaw(data))

    private fun samples(d: JxrDecoder.Decoded): IntArray {
        val r = d.raster
        val pf = d.format
        if (r.slots == 1 && pf.colorFormat != 0) return r.samples.copyOf()
        val color = if (pf.colorFormat == 0) 1 else 3
        val channels = color + if (d.alpha) 1 else 0
        return IntArray(r.width * r.height * channels) { i ->
            val c = i % channels
            r.samples[(i / channels) * r.slots + if (c < color && pf.bgr) 2 - c else c]
        }
    }

    @Test
    fun theFixturesReadAsJxrDecAppReadsThem() {
        assertEquals(JxrFixtures.hashes.keys, JxrFixtures.all.map { it.first }.toSet())
        for ((name, data) in JxrFixtures.all) {
            assertEquals(JxrFixtures.hashes.getValue(name), fnv(samples(data)), name)
        }
    }

    @Test
    fun theThumbnailsReadAsJxrDecAppReadsThem() {
        var checked = 0
        for ((name, data) in JxrFixtures.all) {
            for (reduction in intArrayOf(2, 4, 8)) {
                val want = JxrFixtures.reducedHashes["$name/$reduction"]
                val d = JxrDecoder.decodeRawReduced(data, reduction)
                if (want == null) {
                    assertEquals(null, d, "$name/$reduction")
                    continue
                }
                assertEquals(want, fnv(samples(d ?: throw AssertionError("$name/$reduction gives no thumbnail"))), "$name/$reduction")
                val info = ImageKodec.probe(data)
                val bitmap = ImageKodec.decodeReduced(data, reduction)
                assertEquals((info.width + reduction - 1) / reduction, bitmap.width, "$name/$reduction")
                assertEquals((info.height + reduction - 1) / reduction, bitmap.height, "$name/$reduction")
                checked++
            }
        }
        assertEquals(JxrFixtures.reducedHashes.size, checked)
    }

    @Test
    fun aSizeJxrlibRoundsOtherwiseReducesAfterAFullDecode() {
        // A thumbnail 2 by 1 of 16 by 1: jxrlib keeps the whole image, so the facade averages.
        assertEquals(0, JxrDecoder.thumbnailScale(16, 1, 8))
        assertEquals(8, JxrDecoder.thumbnailScale(83, 61, 8))
        assertEquals(4, JxrDecoder.thumbnailScale(40, 24, 4))
        // The facade averages the full decode there, as it does for formats that do not reduce inside.
        val narrow = JxrFixtures.rgbLossless
        assertEquals(2, JxrDecoder.thumbnailScale(40, 24, 2))
        val reduced = ImageKodec.decodeReduced(narrow, 8)
        assertEquals(5, reduced.width)
        assertEquals(3, reduced.height)
    }

    @Test
    fun probeReadsTheHeaders() {
        val expected = mapOf(
            "rgbLossless" to listOf(40, 24, 8, 0),
            "bgrTiles" to listOf(40, 40, 8, 0),
            "gray" to listOf(24, 20, 8, 0),
            "bgraPlanar" to listOf(24, 20, 8, 1),
            "bgraInterleaved" to listOf(24, 20, 8, 1),
            "rgba64" to listOf(24, 20, 16, 1),
            "rgbHalf" to listOf(24, 20, 16, 0),
            "rgb565" to listOf(24, 20, 6, 0),
        )
        for ((name, want) in expected) {
            val data = JxrFixtures.all.first { it.first == name }.second
            assertEquals(ImageFormat.JXR, ImageFormat.sniff(data), name)
            val info = ImageKodec.probe(data)
            assertEquals(want, listOf(info.width, info.height, info.bitDepth, if (info.hasAlpha) 1 else 0), name)
            assertTrue(info.isDecodable, name)
            assertEquals(if (name == "gray") 1 else 3, info.colorChannels, name)
        }
    }

    @Test
    fun theFacadeGivesThePixels() {
        val gray = ImageKodec.decode(JxrFixtures.gray)
        val raw = JxrDecoder.decodeRaw(JxrFixtures.gray).raster.samples
        for (i in gray.argb.indices) assertEquals(gray(raw[i]), gray.argb[i])
        val bgra = ImageKodec.decode(JxrFixtures.bgraInterleaved)
        val s = JxrDecoder.decodeRaw(JxrFixtures.bgraInterleaved).raster.samples
        for (i in bgra.argb.indices) assertEquals(argb(s[4 * i + 3], s[4 * i + 2], s[4 * i + 1], s[4 * i]), bgra.argb[i])
        // A 16-bit file keeps its samples, and its 8-bit picture is their high byte.
        val wide = ImageKodec.decode16(JxrFixtures.rgba64)
        assertEquals(4, wide.channels)
        assertEquals(JxrDecoder.decodeRaw(JxrFixtures.rgba64).raster.samples.toList(), wide.samples.map { it.toInt() and 0xFFFF })
        assertEquals(wide.toBitmap().argb.toList(), ImageKodec.decode(JxrFixtures.rgba64).argb.toList())
        for ((name, data) in JxrFixtures.all) {
            assertEquals(ImageKodec.decode16(data).toBitmap().argb.toList(), ImageKodec.decode(data).argb.toList(), name)
        }
    }

    /** Puts pixel format [last] of the 6fddc324 family, whose GUID JxrEncApp writes at byte 8, in place of the file's own. */
    private fun relabel(data: ByteArray, last: Int): ByteArray = data.copyOf().also { it[8 + 15] = last.toByte() }

    @Test
    fun premultipliedColourIsDividedByItsAlpha() {
        val data = relabel(JxrFixtures.bgraPlanar, 0x10)
        val s = JxrDecoder.decodeRaw(data).raster.samples
        val bitmap = ImageKodec.decode(data)
        for (i in bitmap.argb.indices) {
            val a = s[4 * i + 3]
            fun straight(v: Int) = if (a == 0) 0 else minOf(255, (v * 255 + a / 2) / a)
            assertEquals(argb(a, straight(s[4 * i + 2]), straight(s[4 * i + 1]), straight(s[4 * i])), bitmap.argb[i])
        }
    }

    @Test
    fun theTransformationTagIsTheOrientation() {
        val data = JxrFixtures.rgbLossless.copyOf()
        val ifd = data[4].toInt() and 0xFF
        val entries = data[ifd].toInt() and 0xFF
        var done = false
        for (i in 0 until entries) {
            val at = ifd + 2 + 12 * i
            if ((data[at].toInt() and 0xFF) == 0x02 && (data[at + 1].toInt() and 0xFF) == 0xBC) {
                data[at + 8] = 4 // O_RCW: a quarter turn clockwise
                done = true
            }
        }
        assertTrue(done, "JxrEncApp wrote no Transformation tag")
        assertEquals(Orientation.Rotate90, ImageKodec.probe(data).orientation)
        val turned = ImageKodec.decode(data, applyOrientation = true)
        assertEquals(24, turned.width)
        assertEquals(40, turned.height)
    }

    @Test
    fun aFileCutShortAnywhereFailsWithADecodeError() {
        for ((name, data) in JxrFixtures.all) {
            var length = 0
            while (length < data.size) {
                val cut = data.copyOf(length)
                assertFailsWith<ImageDecodeException>("$name cut to $length of ${data.size} bytes") { ImageKodec.decode(cut) }
                try {
                    ImageKodec.probe(cut)
                } catch (_: ImageDecodeException) {
                }
                length += if (length < 200) 1 else 7
            }
        }
    }

    @Test
    fun aBadTileIndexFailsWithADecodeError() {
        val data = JxrFixtures.bgrTiles
        // JxrEncApp writes the short header for a small image: two bytes of width, two of height,
        // three of tile counts, then a byte for the width of each tile column but the last.
        val start = 8 + 16 + 2 + 12 * (data[8 + 16].toInt() and 0xFF)
        val codestream = (0 until data.size - 8).first { at ->
            "WMPHOTO".encodeToByteArray().withIndex().all { (k, b) -> data[at + k] == b } && at >= start
        }
        assertTrue((data[codestream + 10].toInt() and 0x80) != 0, "the header is not the short one")
        for (width in listOf(0, 7, 255)) {
            val bad = data.copyOf()
            bad[codestream + 8 + 11] = width.toByte()
            assertFailsWith<ImageDecodeException>("a tile column $width macroblocks wide") { ImageKodec.decode(bad) }
        }
    }

    @Test
    fun otherDataIsNotJxr() {
        assertEquals(null, ImageFormat.sniff(byteArrayOf(0x49, 0x49, 0xBC.toByte(), 2)))
        assertEquals(ImageFormat.JXR, ImageFormat.sniff(byteArrayOf(0x49, 0x49, 0xBC.toByte(), 0)))
        assertFailsWith<ImageDecodeException> { ImageKodec.decode(byteArrayOf(0x49, 0x49, 0xBC.toByte(), 1, 8, 0, 0, 0, 0, 0)) }
    }
}
