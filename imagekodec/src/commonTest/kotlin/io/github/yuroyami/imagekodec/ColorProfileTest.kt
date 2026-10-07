package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.internal.flate.Crc32
import io.github.yuroyami.imagekodec.internal.flate.Zlib
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What each format declares about its color space reaches [ImageInfo.colorProfile]
 * byte for byte, and decoding does not change (#16).
 */
class ColorProfileTest {

    /**
     * A v4 ICC profile header, its tag table and a `desc` tag of type `mluc` holding
     * [description]: enough for a reader to name the profile.
     */
    internal fun profile(description: String, version4: Boolean = true): ByteArray {
        val text = if (version4) {
            val utf16 = ByteArray(description.length * 2) { i ->
                val c = description[i / 2].code
                (if (i % 2 == 0) c ushr 8 else c).toByte()
            }
            "mluc".encodeToByteArray() + ByteArray(4) + be32(1) + be32(12) + "enUS".encodeToByteArray() +
                be32(utf16.size) + be32(28) + utf16
        } else {
            "desc".encodeToByteArray() + ByteArray(4) + be32(description.length + 1) +
                description.encodeToByteArray() + byteArrayOf(0) + ByteArray(12 + 67)
        }
        val tagAt = 128 + 4 + 12
        val size = tagAt + text.size
        val header = ByteArray(128)
        be32(size).copyInto(header, 0)
        header[8] = if (version4) 4 else 2
        "mntr".encodeToByteArray().copyInto(header, 12)
        "RGB ".encodeToByteArray().copyInto(header, 16)
        "XYZ ".encodeToByteArray().copyInto(header, 20)
        "acsp".encodeToByteArray().copyInto(header, 36)
        return header + be32(1) + "desc".encodeToByteArray() + be32(tagAt) + be32(text.size) + text
    }

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private val sample = KiteBitmap(6, 4, IntArray(24) { argb(0xFF, it * 10, 255 - it * 10, it * 3) })

    @Test
    fun theProfileNamesItself() {
        assertEquals("Display P3 test", ColorProfile(icc = profile("Display P3 test")).iccDescription)
        assertEquals("Adobe RGB (1998)", ColorProfile(icc = profile("Adobe RGB (1998)", version4 = false)).iccDescription)
        assertEquals("RGB", ColorProfile(icc = profile("x")).iccColorSpace)
        assertNull(ColorProfile(icc = ByteArray(40)).iccDescription)
        assertNull(ColorProfile(icc = profile("x").copyOf(140)).iccDescription)
    }

    // --- PNG ------------------------------------------------------------------------

    private fun chunk(type: String, body: ByteArray): ByteArray {
        val crc = Crc32()
        crc.update(type.encodeToByteArray())
        crc.update(body)
        return be32(body.size) + type.encodeToByteArray() + body + be32(crc.value().toInt())
    }

    /** [png] with [chunks] put right after its IHDR. */
    private fun withChunks(png: ByteArray, vararg chunks: ByteArray): ByteArray {
        val ihdrEnd = 8 + 8 + 13 + 4
        return png.copyOf(ihdrEnd) + chunks.fold(ByteArray(0)) { a, b -> a + b } + png.copyOfRange(ihdrEnd, png.size)
    }

    /** A PNG with every color chunk, and a JPEG with its profile in two APP2 segments: seeds for the fuzz suite. */
    internal fun seeds(): List<Pair<String, ByteArray>> {
        val icc = profile("Fuzz")
        val png = withChunks(
            ImageKodec.encodePng(sample),
            chunk("iCCP", "Fuzz".encodeToByteArray() + byteArrayOf(0, 0) + Zlib.compress(icc)),
            chunk("sRGB", byteArrayOf(0)), chunk("gAMA", be32(45455)), chunk("cICP", byteArrayOf(1, 13, 0, 1)),
        )
        val jpeg = ImageKodec.encodeJpeg(sample, quality = 80)
        val half = icc.size / 2
        val tagged = jpeg.copyOf(2) + app2(1, 2, icc.copyOf(half)) + app2(2, 2, icc.copyOfRange(half, icc.size)) +
            jpeg.copyOfRange(2, jpeg.size)
        return listOf("png-color" to png, "jpeg-icc" to tagged)
    }

    @Test
    fun pngReportsEveryColorChunk() {
        val plain = ImageKodec.encodePng(sample)
        val icc = profile("Display P3 test")
        val chrm = intArrayOf(31270, 32900, 68000, 32000, 26500, 69000, 15000, 6000)
        val bytes = withChunks(
            plain,
            chunk("iCCP", "Display P3".encodeToByteArray() + byteArrayOf(0, 0) + Zlib.compress(icc)),
            chunk("sRGB", byteArrayOf(1)),
            chunk("gAMA", be32(45455)),
            chunk("cHRM", chrm.map { be32(it) }.reduce { a, b -> a + b }),
            chunk("cICP", byteArrayOf(12, 13, 0, 1)),
        )
        val color = ImageKodec.probe(bytes).colorProfile!!
        assertContentEquals(icc, color.icc)
        assertEquals("Display P3", color.iccName)
        assertEquals("Display P3 test", color.iccDescription)
        assertEquals(1, color.srgbIntent)
        assertEquals(45455, color.gamma)
        assertContentEquals(chrm, color.chromaticities)
        assertContentEquals(intArrayOf(12, 13, 0, 1), color.cicp)
        // Reported, not applied.
        assertContentEquals(ImageKodec.decode(plain).argb, ImageKodec.decode(bytes).argb)
        assertNull(ImageKodec.probe(plain).colorProfile)
    }

    @Test
    fun aDamagedColorChunkIsIgnored() {
        val plain = ImageKodec.encodePng(sample)
        val icc = profile("x")
        val cases = listOf(
            chunk("iCCP", "x".encodeToByteArray() + byteArrayOf(0, 0) + byteArrayOf(1, 2, 3)),          // not zlib
            chunk("iCCP", "x".encodeToByteArray() + byteArrayOf(0, 0) + Zlib.compress(icc.copyOf(icc.size - 1))), // length disagrees
            chunk("iCCP", byteArrayOf(0, 0) + Zlib.compress(icc)),                                         // no name
            chunk("sRGB", byteArrayOf(9)),
            chunk("gAMA", be32(0)),
        )
        for (c in cases) {
            val bytes = withChunks(plain, c)
            assertNull(ImageKodec.probe(bytes).colorProfile)
            assertContentEquals(ImageKodec.decode(plain).argb, ImageKodec.decode(bytes).argb)
        }
    }

    // --- JPEG -----------------------------------------------------------------------

    private fun app2(sequence: Int, count: Int, part: ByteArray): ByteArray {
        val body = "ICC_PROFILE".encodeToByteArray() + byteArrayOf(0, sequence.toByte(), count.toByte()) + part
        val length = body.size + 2
        return byteArrayOf(0xFF.toByte(), 0xE2.toByte(), (length ushr 8).toByte(), length.toByte()) + body
    }

    @Test
    fun jpegJoinsItsProfileSegmentsInSequenceOrder() {
        val jpeg = ImageKodec.encodeJpeg(sample, quality = 90)
        val icc = profile("Split across segments")
        val half = icc.size / 2
        fun withSegments(vararg segments: ByteArray) =
            jpeg.copyOf(2) + segments.fold(ByteArray(0)) { a, b -> a + b } + jpeg.copyOfRange(2, jpeg.size)
        val outOfOrder = withSegments(app2(2, 2, icc.copyOfRange(half, icc.size)), app2(1, 2, icc.copyOf(half)))
        assertContentEquals(icc, ImageKodec.probe(outOfOrder).colorProfile!!.icc)
        assertContentEquals(ImageKodec.decode(jpeg).argb, ImageKodec.decode(outOfOrder).argb)
        // A missing or repeated segment leaves no profile, as libjpeg refuses one.
        assertNull(ImageKodec.probe(withSegments(app2(1, 2, icc.copyOf(half)))).colorProfile)
        assertNull(ImageKodec.probe(withSegments(app2(1, 2, icc.copyOf(half)), app2(1, 2, icc.copyOf(half)))).colorProfile)
    }

    @Test
    fun jpeg2000ReportsTheProfileInItsColourSpecification() {
        val jp2 = Jp2ColorFixtures.iccGray
        // The colr box: its length, 'colr', then method 2, precedence, approximation and the profile.
        val at = (0 until jp2.size - 4).first { jp2.copyOfRange(it, it + 4).decodeToString() == "colr" } - 4
        val length = ((jp2[at].toInt() and 0xFF) shl 24) or ((jp2[at + 1].toInt() and 0xFF) shl 16) or
            ((jp2[at + 2].toInt() and 0xFF) shl 8) or (jp2[at + 3].toInt() and 0xFF)
        val color = ImageKodec.probe(jp2).colorProfile!!
        assertContentEquals(jp2.copyOfRange(at + 11, at + length), color.icc)
        assertEquals("GRAY", color.iccColorSpace)
        assertNull(ImageKodec.probe(Jp2ColorFixtures.syccIssue).colorProfile, "an enumerated colour space holds no profile")
    }

    // --- TIFF, GIF, BMP ---------------------------------------------------------------

    @Test
    fun tiffGifAndBmpCarryTheirProfiles() {
        val icc = profile("Elsewhere")

        // TIFF: tag 34675 on the page.
        val tiff = TiffPagesTest().tiff(listOf(TiffPagesTest.Page(3, 2, 50)))
        val count = tiff[8].toInt() and 0xFF
        val ifd = 8
        // Rebuild the directory with one more entry pointing at the profile, appended to the file.
        val entries = ByteArray(count * 12) { tiff[ifd + 2 + it] }
        val newIfd = tiff.size
        val profileAt = newIfd + 2 + (count + 1) * 12 + 4
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
        fun le32(v: Int) = le16(v) + le16(v ushr 16)
        val tagged = tiff.copyOf().also { le32(newIfd).copyInto(it, 4) } +
            le16(count + 1) + entries + le16(34675) + le16(7) + le32(icc.size) + le32(profileAt) + le32(0) + icc
        assertContentEquals(icc, ImageKodec.probe(tagged).colorProfile!!.icc)
        assertContentEquals(ImageKodec.decode(tiff).argb, ImageKodec.decode(tagged).argb)

        // GIF: an ICCRGBG1 012 application extension before the image.
        val gif = ImageKodec.encodeGif(sample)
        val headerEnd = 13 + 3 * (2 shl (gif[10].toInt() and 7))
        val blocks = icc.toList().chunked(255).fold(ByteArray(0)) { a, part -> a + byteArrayOf(part.size.toByte()) + part.toByteArray() }
        val extension = byteArrayOf(0x21, 0xFF.toByte(), 11) + "ICCRGBG1012".encodeToByteArray() + blocks + byteArrayOf(0)
        val gifTagged = gif.copyOf(headerEnd) + extension + gif.copyOfRange(headerEnd, gif.size)
        assertContentEquals(icc, ImageKodec.probe(gifTagged).colorProfile!!.icc)
        assertContentEquals(ImageKodec.decode(gif).argb, ImageKodec.decode(gifTagged).argb)

        // BMP: a V5 header whose profile follows the pixels.
        val pixels = byteArrayOf(10, 20, 30, 0)                       // one BGR pixel, padded
        val headerSize = 124
        val pixelsAt = 14 + headerSize
        val profileOffset = headerSize + pixels.size                  // from the header's start
        val header = ByteArray(headerSize).also { h ->
            le32(headerSize).copyInto(h, 0); le32(1).copyInto(h, 4); le32(1).copyInto(h, 8)
            le16(1).copyInto(h, 12); le16(24).copyInto(h, 14)
            le32(0x4D424544).copyInto(h, 56)                          // 'MBED'
            le32(profileOffset).copyInto(h, 112); le32(icc.size).copyInto(h, 116)
        }
        val fileSize = pixelsAt + pixels.size + icc.size
        val bmp = byteArrayOf('B'.code.toByte(), 'M'.code.toByte()) + le32(fileSize) + le32(0) + le32(pixelsAt) +
            header + pixels + icc
        assertContentEquals(icc, ImageKodec.probe(bmp).colorProfile!!.icc)
        assertEquals(argb(0xFF, 30, 20, 10), ImageKodec.decode(bmp)[0, 0])
    }
}
