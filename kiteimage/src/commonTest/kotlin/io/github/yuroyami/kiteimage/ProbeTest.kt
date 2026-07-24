package io.github.yuroyami.kiteimage

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

    // PNG vectors (shared with PngDecoderTest).
    private val GRAY8_4X4 = "89504e470d0a1a0a0000000d49484452000000040000000408000000008c9ac1a2000000184944415478da6360e0129163d4e0e2e262d2000216100b000d5701704e711c6a0000000049454e44ae426082"
    private val RGBA8_2X2 = "89504e470d0a1a0a0000000d494844520000000200000002080600000072b60d24000000174944415478da63f8cfc0f01f081b1881b4c3ffff0c07003ee3077ce9dbb2f80000000049454e44ae426082"
    private val GRAY16_2X1 = "89504e470d0a1a0a0000000d494844520000000200000001100000000081d9fc150000000d4944415478da631032f9cf000002e70146a0a59e520000000049454e44ae426082"
    private val PAL8_TRNS_4X2 = "89504e470d0a1a0a0000000d494844520000000400000002080300000048768d510000000c504c5445ff000000ff000000ffffff00d6028f7b0000000274524e5300809b2b4e18000000124944415478da6360606462666066626400000046000da400597b0000000049454e44ae426082"

    // GIF vectors (shared with GifDecoderTest).
    private val GIF_STATIC_3X2 = "47494638396103000200910000ff000000ff000000ffffff002c00000000030002000002050443710453003b"
    private val GIF_ANIM_2F = "47494638396102000200910000ff000000ff000000ffffff0021ff0b4e45545343415045322e30030103000021f90404050000002c0000000002000200000204044110050021f90404000000002c000000000200020000020414455105003b"
    private val GIF_TRANSPARENT = "47494638396102000200910000ff000000ff000000ffffff0021f90401000001002c000000000200020000020404c37005003b"

    private fun allVectors(): List<Pair<String, ByteArray>> = listOf(
        "gray8 png" to hex(GRAY8_4X4),
        "rgba8 png" to hex(RGBA8_2X2),
        "gray16 png" to hex(GRAY16_2X1),
        "palette png" to hex(PAL8_TRNS_4X2),
        "static gif" to hex(GIF_STATIC_3X2),
        "animated gif" to hex(GIF_ANIM_2F),
        "jpeg" to KiteImage.encodeJpeg(sampleBitmap(9, 5), quality = 80),
        "png roundtrip" to KiteImage.encodePng(sampleBitmap(7, 3)),
    )

    private fun sampleBitmap(w: Int, h: Int) = KiteBitmap(w, h, IntArray(w * h) { i ->
        argb(0xFF, (i * 7) and 0xFF, (i * 13) and 0xFF, (i * 29) and 0xFF)
    })

    @Test
    fun probedDimensionsMatchTheDecoder() {
        for ((name, bytes) in allVectors()) {
            val info = KiteImage.probe(bytes)
            val bitmap = KiteImage.decode(bytes)
            assertEquals(bitmap.width, info.width, "$name width")
            assertEquals(bitmap.height, info.height, "$name height")
        }
    }

    @Test
    fun probedFormatMatchesTheSniff() {
        for ((name, bytes) in allVectors()) {
            assertEquals(KiteImage.detect(bytes), KiteImage.probe(bytes).format, "$name format")
        }
    }

    @Test
    fun probedFrameCountAndLoopMatchTheAnimationDecoder() {
        for ((name, bytes) in allVectors()) {
            val info = KiteImage.probe(bytes)
            val anim = KiteImage.decodeAnimation(bytes)
            assertEquals(anim.frames.size, info.frameCount, "$name frame count")
            if (anim.isAnimated) {
                assertEquals(anim.loopCount, info.loopCount, "$name loop count")
            }
        }
    }

    @Test
    fun animatedGifIsReportedAnimated() {
        val info = KiteImage.probe(hex(GIF_ANIM_2F))
        assertTrue(info.isAnimated)
        assertEquals(2, info.frameCount)
        assertFalse(KiteImage.probe(hex(GIF_STATIC_3X2)).isAnimated)
    }

    @Test
    fun bitDepthComesFromTheHeaderNotTheOutput() {
        // The decoder narrows 16-bit samples to 8; the probe reports what's stored.
        assertEquals(16, KiteImage.probe(hex(GRAY16_2X1)).bitDepth)
        assertEquals(8, KiteImage.probe(hex(GRAY8_4X4)).bitDepth)
    }

    @Test
    fun declaredAlphaTracksTheContainer() {
        assertTrue(KiteImage.probe(hex(RGBA8_2X2)).hasAlpha, "colour type 6")
        assertTrue(KiteImage.probe(hex(PAL8_TRNS_4X2)).hasAlpha, "palette with tRNS")
        assertTrue(KiteImage.probe(hex(GIF_TRANSPARENT)).hasAlpha, "gif transparent index")
        assertFalse(KiteImage.probe(hex(GRAY8_4X4)).hasAlpha, "plain gray")
        assertFalse(KiteImage.probe(hex(GIF_STATIC_3X2)).hasAlpha, "gif without transparency")
        assertFalse(KiteImage.probe(KiteImage.encodeJpeg(sampleBitmap(4, 4))).hasAlpha, "jpeg has no alpha")
    }

    @Test
    fun everySupportedVectorReportsDecodable() {
        for ((name, bytes) in allVectors()) {
            val info = KiteImage.probe(bytes)
            assertTrue(info.isDecodable, "$name should be decodable: ${info.unsupportedReason}")
            assertNull(info.unsupportedReason, "$name reason")
        }
    }

    @Test
    fun unknownInputFails() {
        assertFailsWith<ImageDecodeException> { KiteImage.probe(byteArrayOf(1, 2, 3, 4)) }
        assertNull(KiteImage.probeOrNull(byteArrayOf(1, 2, 3, 4)))
        assertNull(KiteImage.probeOrNull(ByteArray(0)))
    }

    @Test
    fun truncatedHeaderFailsCleanly() {
        val png = hex(GRAY8_4X4)
        // Cut inside IHDR: still recognisably a PNG, no longer probeable.
        assertFailsWith<ImageDecodeException> { KiteImage.probe(png.copyOf(18)) }
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
        val base = KiteImage.encodeJpeg(sampleBitmap(8, 4), quality = 80)
        assertEquals(Orientation.Normal, KiteImage.probe(base).orientation)
        for (o in Orientation.entries) {
            val tagged = jpegWithOrientation(base, o.exifValue)
            assertEquals(o, KiteImage.probe(tagged).orientation, "orientation ${o.exifValue}")
        }
    }

    @Test
    fun garbageOrientationValueFallsBackToNormal() {
        val base = KiteImage.encodeJpeg(sampleBitmap(4, 4), quality = 80)
        assertEquals(Orientation.Normal, KiteImage.probe(jpegWithOrientation(base, 99)).orientation)
        assertEquals(Orientation.Normal, KiteImage.probe(jpegWithOrientation(base, 0)).orientation)
    }

    @Test
    fun displayDimensionsAccountForOrientation() {
        val base = KiteImage.encodeJpeg(sampleBitmap(8, 4), quality = 80)
        val info = KiteImage.probe(jpegWithOrientation(base, Orientation.Rotate90.exifValue))
        assertEquals(8, info.width)
        assertEquals(4, info.height)
        assertEquals(4, info.displayWidth)
        assertEquals(8, info.displayHeight)
    }

    @Test
    fun decodeCanApplyOrientation() {
        val base = KiteImage.encodeJpeg(sampleBitmap(8, 4), quality = 80)
        val tagged = jpegWithOrientation(base, Orientation.Rotate90.exifValue)

        val raw = KiteImage.decode(tagged)
        assertEquals(8, raw.width)
        assertEquals(4, raw.height)

        val upright = KiteImage.decode(tagged, applyOrientation = true)
        assertEquals(4, upright.width)
        assertEquals(8, upright.height)
        // Same pixels, quarter turn clockwise.
        assertEquals(raw[0, 0], upright[upright.width - 1, 0])
    }

    @Test
    fun animationDecodeCanApplyOrientation() {
        // GIF carries no EXIF, so this proves the no-tag path leaves frames alone.
        val anim = KiteImage.decodeAnimation(hex(GIF_ANIM_2F), applyOrientation = true)
        assertEquals(2, anim.width)
        assertEquals(2, anim.height)
        assertEquals(2, anim.frames.size)
    }

    @Test
    fun brokenExifDoesNotBreakTheProbe() {
        val base = KiteImage.encodeJpeg(sampleBitmap(4, 4), quality = 80)
        // APP1 that claims to be EXIF but carries a truncated TIFF header.
        val payload = "Exif".encodeToByteArray() + byteArrayOf(0, 0, 'I'.code.toByte(), 'I'.code.toByte())
        val len = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((len shr 8) and 0xFF).toByte(), (len and 0xFF).toByte()) + payload
        val spliced = base.copyOfRange(0, 2) + app1 + base.copyOfRange(2, base.size)

        val info = KiteImage.probe(spliced)
        assertEquals(Orientation.Normal, info.orientation)
        assertEquals(4, info.width)
    }
}
