package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Probing must agree with decoding: for every vector here the header-only answer
 * is cross-checked against what the full decoder actually produces. That is a
 * stronger test than pinning numbers, because it stays honest when a decoder
 * changes.
 */
class ProbeTest {

    @Test
    fun extremeTiffOffsetsFailCleanlyInBothByteOrders() {
        for (littleEndian in listOf(true, false)) {
            for (offset in listOf(0x7FFFFFFD, 0x7FFFFFFE, 0x7FFFFFFF, -1)) {
                val bytes = tiffHeaderWithOffset(offset, littleEndian) + ByteArray(8)
                assertFailsWith<ImageDecodeException> { ImageKodec.probe(bytes) }
                assertNull(ImageKodec.probeOrNull(bytes))
            }
        }
    }

    @Test
    fun extremeExifOffsetsLeavePixelsAndOrientationAlone() {
        val base = ImageKodec.encodeJpeg(sampleBitmap(4, 3))
        val expected = ImageKodec.decode(base)
        for (littleEndian in listOf(true, false)) {
            for (offset in listOf(0x7FFFFFF7, 0x7FFFFFF8, 0x7FFFFFF9, 0x7FFFFFFA, Int.MAX_VALUE, -1)) {
                val bytes = jpegWithTiffHeader(base, tiffHeaderWithOffset(offset, littleEndian))
                assertEquals(Orientation.Normal, ImageKodec.probe(bytes).orientation)
                assertEquals(Orientation.Normal, ImageKodec.probeOrNull(bytes)?.orientation)
                assertTrue(expected.argb.contentEquals(ImageKodec.decode(bytes, applyOrientation = true).argb))
                assertTrue(expected.argb.contentEquals(ImageKodec.decodeAnimation(bytes, applyOrientation = true).frames[0].bitmap.argb))
            }
        }
    }

    // PNG vectors (shared with PngDecoderTest).
    private val GRAY8_4X4 = "89504e470d0a1a0a0000000d49484452000000040000000408000000008c9ac1a2000000184944415478da6360e0129163d4e0e2e262d2000216100b000d5701704e711c6a0000000049454e44ae426082"
    private val RGBA8_2X2 = "89504e470d0a1a0a0000000d494844520000000200000002080600000072b60d24000000174944415478da63f8cfc0f01f081b1881b4c3ffff0c07003ee3077ce9dbb2f80000000049454e44ae426082"
    private val GRAY16_2X1 = "89504e470d0a1a0a0000000d494844520000000200000001100000000081d9fc150000000d4944415478da631032f9cf000002e70146a0a59e520000000049454e44ae426082"
    private val PAL8_TRNS_4X2 = "89504e470d0a1a0a0000000d494844520000000400000002080300000048768d510000000c504c5445ff000000ff000000ffffff00d6028f7b0000000274524e5300809b2b4e18000000124944415478da6360606462666066626400000046000da400597b0000000049454e44ae426082"

    // GIF vectors (shared with GifDecoderTest).
    private val GIF_STATIC_3X2 = "47494638396103000200910000ff000000ff000000ffffff002c00000000030002000002050443710453003b"
    private val GIF_ANIM_2F = "47494638396102000200910000ff000000ff000000ffffff0021ff0b4e45545343415045322e30030103000021f90404050000002c0000000002000200000204044110050021f90404000000002c000000000200020000020414455105003b"
    private val GIF_TRANSPARENT = "47494638396102000200910000ff000000ff000000ffffff0021f90401000001002c000000000200020000020404c37005003b"

    /** A vector of every format probe reads, alpha and animation included, so each agreement test covers them all (#82). */
    private fun allVectors(): List<Pair<String, ByteArray>> = listOf(
        "gray8 png" to hex(GRAY8_4X4),
        "rgba8 png" to hex(RGBA8_2X2),
        "gray16 png" to hex(GRAY16_2X1),
        "palette png" to hex(PAL8_TRNS_4X2),
        "static gif" to hex(GIF_STATIC_3X2),
        "animated gif" to hex(GIF_ANIM_2F),
        "transparent gif" to hex(GIF_TRANSPARENT),
        "jpeg" to ImageKodec.encodeJpeg(sampleBitmap(9, 5), quality = 80),
        "png roundtrip" to ImageKodec.encodePng(sampleBitmap(7, 3)),
        "bmp" to ImageKodec.encodeBmp(sampleBitmap(5, 4)),
        "bmp with alpha" to ImageKodec.encodeBmp(KiteBitmap(3, 2, IntArray(6) { argb(it * 40, 10, 20, 30) })),
        "tiff" to tiffBlock(1, ByteArray(32) { (it * 9).toByte() }),
        "webp lossless" to constantWebp(6, 4, argb(0xFF, 10, 200, 30)),
        "webp with alpha" to constantExtendedWebp(6, 4, listOf(argb(0x40, 10, 200, 30)), animated = false),
        "animated webp" to constantExtendedWebp(6, 4, listOf(argb(0xFF, 1, 2, 3), argb(0xFF, 4, 5, 6)), animated = true),
        "jp2" to hex(JP2),
        "jp2 palette" to Jp2ColorFixtures.palette,
        "jp2 opacity" to Jp2ColorFixtures.straight,
        "j2k rgba" to Jp2AlphaFixtures.rgbaCodestream,
        "j2k gray and alpha" to Jp2AlphaFixtures.grayAlphaCodestream,
        "jp2 rgba" to Jp2AlphaFixtures.rgbaJp2,
    )

    private fun sampleBitmap(w: Int, h: Int) = KiteBitmap(w, h, IntArray(w * h) { i ->
        argb(0xFF, (i * 7) and 0xFF, (i * 13) and 0xFF, (i * 29) and 0xFF)
    })

    @Test
    fun probedDimensionsMatchTheDecoder() {
        for ((name, bytes) in allVectors()) {
            val info = ImageKodec.probe(bytes)
            val bitmap = ImageKodec.decode(bytes)
            assertEquals(bitmap.width, info.width, "$name width")
            assertEquals(bitmap.height, info.height, "$name height")
        }
    }

    @Test
    fun probedFormatMatchesTheSniff() {
        for ((name, bytes) in allVectors()) {
            assertEquals(ImageKodec.detect(bytes), ImageKodec.probe(bytes).format, "$name format")
        }
    }

    @Test
    fun probedFrameCountAndLoopMatchTheAnimationDecoder() {
        for ((name, bytes) in allVectors()) {
            val info = ImageKodec.probe(bytes)
            val anim = ImageKodec.decodeAnimation(bytes)
            assertEquals(anim.frames.size, info.frameCount, "$name frame count")
            if (anim.isAnimated) {
                assertEquals(anim.loopCount, info.loopCount, "$name loop count")
            }
        }
    }

    @Test
    fun anImageWithoutDeclaredAlphaDecodesOpaque() {
        // The decoder never invents transparency the header does not declare.
        for ((name, bytes) in allVectors()) {
            if (!ImageKodec.probe(bytes).hasAlpha) assertFalse(ImageKodec.decode(bytes).hasTransparency(), "$name")
        }
    }

    @Test
    fun jpeg2000AlphaFollowsOneRuleOnBothSides() {
        // An unlabelled component beyond the colours is opacity, in a bare codestream or a JP2 file,
        // as OpenJPEG writes and Pillow reads it (#82). The probe and the decoder agree on each file.
        for ((name, bytes) in allVectors().filter { ImageKodec.detect(it.second) == ImageFormat.JP2 }) {
            val decoded = io.github.yuroyami.imagekodec.codec.JpxDecoder.decodeForFacade(bytes, 1)
            assertEquals(decoded.alpha != null, ImageKodec.probe(bytes).hasAlpha, name)
        }
        for (bytes in listOf(Jp2AlphaFixtures.rgbaCodestream, Jp2AlphaFixtures.grayAlphaCodestream, Jp2AlphaFixtures.rgbaJp2)) {
            val bitmap = ImageKodec.decode(bytes)
            for (y in 0 until 16) for (x in 0 until 24) {
                assertEquals(if (x < 12) 0 else 255, bitmap[x, y] ushr 24, "opacity at ($x, $y)")
            }
        }
    }

    @Test
    fun pngDimensionsOutsideTheFormatAreRefusedOnBothSides() {
        // IHDR holds unsigned 32-bit sides, but the format allows 1 to 2^31 - 1, and the probe used
        // to hand back a negative width (#82).
        for ((w, h) in listOf(-1 to 1, Int.MIN_VALUE to 1, 0 to 5, 5 to 0)) {
            val png = PNG_SIGNATURE + pngHeader(w, h, 8, 0) + pngChunk("IDAT", ByteArray(0)) + pngChunk("IEND", ByteArray(0))
            assertFailsWith<ImageDecodeException>("${w}x$h") { ImageKodec.probe(png) }
            assertFailsWith<ImageDecodeException>("${w}x$h") { ImageKodec.decode(png) }
        }
        // Past this decoder's limits, but a size PNG allows: probe names the refusal the decode throws.
        val wide = PNG_SIGNATURE + pngHeader(1 shl 25, 1, 8, 0) + pngChunk("IDAT", ByteArray(0)) + pngChunk("IEND", ByteArray(0))
        val info = ImageKodec.probe(wide)
        assertFalse(info.isDecodable)
        val e = assertFailsWith<ImageDecodeException> { ImageKodec.decode(wide) }
        assertEquals(info.unsupportedReason, e.message)
    }

    @Test
    fun animatedGifIsReportedAnimated() {
        val info = ImageKodec.probe(hex(GIF_ANIM_2F))
        assertTrue(info.isAnimated)
        assertEquals(2, info.frameCount)
        assertFalse(ImageKodec.probe(hex(GIF_STATIC_3X2)).isAnimated)
    }

    @Test
    fun bitDepthComesFromTheHeaderNotTheOutput() {
        // The decoder narrows 16-bit samples to 8; the probe reports what's stored.
        assertEquals(16, ImageKodec.probe(hex(GRAY16_2X1)).bitDepth)
        assertEquals(8, ImageKodec.probe(hex(GRAY8_4X4)).bitDepth)
    }

    @Test
    fun declaredAlphaTracksTheContainer() {
        assertTrue(ImageKodec.probe(hex(RGBA8_2X2)).hasAlpha, "colour type 6")
        assertTrue(ImageKodec.probe(hex(PAL8_TRNS_4X2)).hasAlpha, "palette with tRNS")
        assertTrue(ImageKodec.probe(hex(GIF_TRANSPARENT)).hasAlpha, "gif transparent index")
        assertFalse(ImageKodec.probe(hex(GRAY8_4X4)).hasAlpha, "plain gray")
        assertFalse(ImageKodec.probe(hex(GIF_STATIC_3X2)).hasAlpha, "gif without transparency")
        assertFalse(ImageKodec.probe(ImageKodec.encodeJpeg(sampleBitmap(4, 4))).hasAlpha, "jpeg has no alpha")
    }

    @Test
    fun everySupportedVectorReportsDecodable() {
        for ((name, bytes) in allVectors()) {
            val info = ImageKodec.probe(bytes)
            assertTrue(info.isDecodable, "$name should be decodable: ${info.unsupportedReason}")
            assertNull(info.unsupportedReason, "$name reason")
        }
    }

    @Test
    fun unknownInputFails() {
        assertFailsWith<ImageDecodeException> { ImageKodec.probe(byteArrayOf(1, 2, 3, 4)) }
        assertNull(ImageKodec.probeOrNull(byteArrayOf(1, 2, 3, 4)))
        assertNull(ImageKodec.probeOrNull(ByteArray(0)))
    }

    @Test
    fun truncatedHeaderFailsCleanly() {
        val png = hex(GRAY8_4X4)
        // Cut inside IHDR: still recognisably a PNG, no longer probeable.
        assertFailsWith<ImageDecodeException> { ImageKodec.probe(png.copyOf(18)) }
    }

    // --- EXIF orientation -------------------------------------------------------

    /**
     * Splice an EXIF APP1 segment carrying orientation [value] straight after the
     * JPEG SOI. Real cameras put it exactly here.
     */
    private fun jpegWithOrientation(base: ByteArray, value: Int): ByteArray {
        val tiff = buildList {
            addAll(listOf('I'.code, 'I'.code, 42, 0, 8, 0, 0, 0))      // little-endian, IFD0 at 8
            addAll(listOf(1, 0))                                       // one entry
            addAll(listOf(0x12, 0x01))                                 // tag 274
            addAll(listOf(3, 0))                                       // type SHORT
            addAll(listOf(1, 0, 0, 0))                                 // count 1
            addAll(listOf(value and 0xFF, 0, 0, 0))                    // inline value
            addAll(listOf(0, 0, 0, 0))                                 // no next IFD
        }.map { it.toByte() }
        val payload = "Exif".encodeToByteArray() + byteArrayOf(0, 0) + tiff.toByteArray()
        val len = payload.size + 2
        val app1 = byteArrayOf(
            0xFF.toByte(), 0xE1.toByte(),
            ((len shr 8) and 0xFF).toByte(), (len and 0xFF).toByte(),
        ) + payload
        return base.copyOfRange(0, 2) + app1 + base.copyOfRange(2, base.size)
    }

    @Test
    fun exifOrientationIsRead() {
        val base = ImageKodec.encodeJpeg(sampleBitmap(8, 4), quality = 80)
        assertEquals(Orientation.Normal, ImageKodec.probe(base).orientation)
        for (o in Orientation.entries) {
            val tagged = jpegWithOrientation(base, o.exifValue)
            assertEquals(o, ImageKodec.probe(tagged).orientation, "orientation ${o.exifValue}")
        }
    }

    @Test
    fun garbageOrientationValueFallsBackToNormal() {
        val base = ImageKodec.encodeJpeg(sampleBitmap(4, 4), quality = 80)
        assertEquals(Orientation.Normal, ImageKodec.probe(jpegWithOrientation(base, 99)).orientation)
        assertEquals(Orientation.Normal, ImageKodec.probe(jpegWithOrientation(base, 0)).orientation)
    }

    @Test
    fun displayDimensionsAccountForOrientation() {
        val base = ImageKodec.encodeJpeg(sampleBitmap(8, 4), quality = 80)
        val info = ImageKodec.probe(jpegWithOrientation(base, Orientation.Rotate90.exifValue))
        assertEquals(8, info.width)
        assertEquals(4, info.height)
        assertEquals(4, info.displayWidth)
        assertEquals(8, info.displayHeight)
    }

    @Test
    fun decodeCanApplyOrientation() {
        val base = ImageKodec.encodeJpeg(sampleBitmap(8, 4), quality = 80)
        val tagged = jpegWithOrientation(base, Orientation.Rotate90.exifValue)

        val raw = ImageKodec.decode(tagged)
        assertEquals(8, raw.width)
        assertEquals(4, raw.height)

        val upright = ImageKodec.decode(tagged, applyOrientation = true)
        assertEquals(4, upright.width)
        assertEquals(8, upright.height)
        // Same pixels, quarter turn clockwise.
        assertEquals(raw[0, 0], upright[upright.width - 1, 0])
    }

    @Test
    fun animationDecodeCanApplyOrientation() {
        // GIF carries no EXIF, so this proves the no-tag path leaves frames alone.
        val anim = ImageKodec.decodeAnimation(hex(GIF_ANIM_2F), applyOrientation = true)
        assertEquals(2, anim.width)
        assertEquals(2, anim.height)
        assertEquals(2, anim.frames.size)
    }

    @Test
    fun brokenExifDoesNotBreakTheProbe() {
        val base = ImageKodec.encodeJpeg(sampleBitmap(4, 4), quality = 80)
        // APP1 that claims to be EXIF but carries a truncated TIFF header.
        val payload = "Exif".encodeToByteArray() + byteArrayOf(0, 0, 'I'.code.toByte(), 'I'.code.toByte())
        val len = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((len shr 8) and 0xFF).toByte(), (len and 0xFF).toByte()) + payload
        val spliced = base.copyOfRange(0, 2) + app1 + base.copyOfRange(2, base.size)

        val info = ImageKodec.probe(spliced)
        assertEquals(Orientation.Normal, info.orientation)
        assertEquals(4, info.width)
    }
}
