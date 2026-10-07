package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WebP vectors produced by libwebp's own `cwebp` 1.6.0 from images whose pixels
 * follow a closed-form rule, and cross-checked against `dwebp` before being
 * pinned here. So each expectation below is simultaneously the generator's
 * formula and the reference decoder's output: if this file passes, our decode
 * agrees with libwebp exactly.
 *
 * A broader sweep (many images, every transform, both bit depths of the palette
 * packing) runs against the real tools in `WebpOracleTest` when they are
 * installed; these embedded vectors keep the coverage on targets that have no
 * subprocess at all.
 */
class WebpDecoderTest {

    /** 16x12 lossless RGB, pixel (x, y) = (x*15, y*20, (x*y) and 255). */
    private val LOSSLESS_RGB = "524946462e000000574542505650384c220000002f0fc00200b93244f43f7651ffe87f8048dba622eedfeed8f13c4c404c005c07eb3f"

    /** 8x8 lossless with real alpha: (x*30, y*30, 128, (x+y)*15). */
    private val LOSSLESS_ALPHA = "524946462e000000574542505650384c210000002f07c00110b98ce87fec220adeff809080b0c4ffa19a03e33180fb300188b6dd0000"

    /** 16x16 in four colours: hits the colour-indexing transform and its bundling. */
    private val LOSSLESS_PALETTE = "524946463a000000574542505650384c2d0000002f0fc003001f201048da1f7a8df9171014f93fdafc075f248030db6892939ce4fd8e21a2ff31e94bfb347f930f00"

    /** Three-frame 8x8 animation, 100 ms each, looping forever. */
    private val ANIMATION = "52494646f200000057454250565038580a00000002000000070000070000414e494d06000000ffffffff0000414e4d4638000000000000000000070000070000640000025650384c200000002f07c00100b93244f43f7611d1ff0061b65149ce1f74af23188f8809c01efa0f414e4d463e000000000000000000070000070000640000005650384c260000002f07c00100b93244f43f7611d1ff0061b65149ce1f74af231008a43892991ead9800971ee83f414e4d4640000000000000000000070000070000640000005650384c270000002f07c00100b93244f43f7611d1ff0061b65149ce1f74af2310082491cc3eead0c604b8f440ff0100"

    /** 24x16 lossy: the codec this build declines. */
    private val LOSSY = "52494646ca0000005745425056503820be000000b005009d012a180010003e91389747a5a32221300800b012096c009d32847037807e3070811800d906feeab801a5955163ddf5e2b10000feef8d0ad5ae3d66257114627f597e714a374736f00111b0b69a46c1cb3ebd7512fd5ac6bf60021b9b78de832966bfff7764cadb83cb160c3fff9959353623cc3e9e8be57f82da025358929678ba8d58a3ce0e44b6b318fc037fbc2f4cbd6545ca38af729417d9e85d83611029c08a5d64b7bc959ed2b02d8b85c22881f7040eb4911411700000"

    /** 16x16 lossy with a separate alpha chunk. */
    private val LOSSY_ALPHA = "524946468200000057454250565038580a000000100000000f00000f0000414c504815000000010ff094ff888820102066ccd873ed20a2ff15305e005650382046000000d001009d012a1000100001402625b00274010eb589a80000fefe92532bfabaf61b2bfe6d7311f2d9de894ae0d53cb87ed1c9dd7fbe5d7ffe5e99eabfffeb4fcf4b6fef830000"

    // --- sniffing and probing ----------------------------------------------------

    @Test
    fun webpIsSniffed() {
        assertEquals(ImageFormat.WEBP, ImageKodec.detect(hex(LOSSLESS_RGB)))
        assertEquals(ImageFormat.WEBP, ImageKodec.detect(hex(ANIMATION)))
        assertEquals(ImageFormat.WEBP, ImageKodec.detect(hex(LOSSY)))
    }

    @Test
    fun probeReadsTheHeadersWithoutDecoding() {
        val rgb = ImageKodec.probe(hex(LOSSLESS_RGB))
        assertEquals(16, rgb.width)
        assertEquals(12, rgb.height)
        assertEquals(1, rgb.frameCount)
        assertTrue(rgb.isDecodable)

        val anim = ImageKodec.probe(hex(ANIMATION))
        assertEquals(8, anim.width)
        assertEquals(8, anim.height)
        assertEquals(3, anim.frameCount)
        assertEquals(0L, anim.loopCount)
        assertTrue(anim.isAnimated)

        val alpha = ImageKodec.probe(hex(LOSSLESS_ALPHA))
        assertTrue(alpha.hasAlpha)
    }

    @Test
    fun lossyIsReportedUndecodableUpFront() {
        val info = ImageKodec.probe(hex(LOSSY))
        assertEquals(24, info.width)
        assertEquals(16, info.height)
        assertFalse(info.isDecodable)
        val reason = info.unsupportedReason
        assertTrue(reason != null && "lossy" in reason, "expected a reason naming the codec, got $reason")

        val alpha = ImageKodec.probe(hex(LOSSY_ALPHA))
        assertEquals(16, alpha.width)
        assertFalse(alpha.isDecodable)
        assertTrue(alpha.hasAlpha)
    }

    /**
     * An animated WebP whose one frame is lossy VP8. libwebp writes these
     * routinely, and the image chunk lives inside the ANMF body, so a probe that
     * only reads top-level chunks calls the file decodable and is then wrong.
     */
    private fun lossyAnimation(): ByteArray {
        fun u16le(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        fun u24le(v: Int) = u16le(v) + byteArrayOf(((v shr 16) and 0xFF).toByte())
        fun u32le(v: Int) = u16le(v) + u16le(v shr 16)
        fun chunk(tag: String, body: ByteArray) =
            tag.encodeToByteArray() + u32le(body.size) +
                (if (body.size % 2 == 1) body + byteArrayOf(0) else body)

        // A VP8 key-frame header: 3-byte frame tag, the 9D 01 2A start code, then
        // two 14-bit dimensions. Nothing reads past it before the refusal.
        val vp8 = chunk(
            "VP8 ",
            byteArrayOf(0x30, 0x00, 0x00, 0x9D.toByte(), 0x01, 0x2A) + u16le(8) + u16le(8),
        )
        val anmf = chunk(
            "ANMF",
            u24le(0) + u24le(0) + u24le(7) + u24le(7) + u24le(100) + byteArrayOf(0) + vp8,
        )
        val vp8x = chunk("VP8X", byteArrayOf(0x02, 0, 0, 0) + u24le(7) + u24le(7))
        val anim = chunk("ANIM", u32le(0) + u16le(0))
        val body = "WEBP".encodeToByteArray() + vp8x + anim + anmf
        return "RIFF".encodeToByteArray() + u32le(body.size) + body
    }

    @Test
    fun framesAreCountedAgainstTheAbsoluteOutputSize() {
        // The first ANMF chunk of the ANIMATION vector starts at byte 44 and takes 64 bytes:
        // an 8 by 8 lossless frame at (0, 0). Twenty 4096 by 4096 composites exceed
        // the absolute output limit even though every rectangle fits the canvas.
        fun u16le(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        fun u24le(v: Int) = u16le(v) + byteArrayOf(((v shr 16) and 0xFF).toByte())
        fun u32le(v: Int) = u16le(v) + u16le(v shr 16)
        fun chunk(tag: String, body: ByteArray) = tag.encodeToByteArray() + u32le(body.size) + body

        val frame = hex(ANIMATION).copyOfRange(44, 44 + 64)
        var body = "WEBP".encodeToByteArray() +
            chunk("VP8X", byteArrayOf(0x02, 0, 0, 0) + u24le(4095) + u24le(4095)) +
            chunk("ANIM", u32le(0) + u16le(0))
        repeat(20) { body += frame }
        val bytes = "RIFF".encodeToByteArray() + u32le(body.size) + body
        val e = assertFailsWith<ImageDecodeException> { ImageKodec.decodeAnimation(bytes) }
        assertTrue(e.message!!.contains("output safety limit"), "message should name the problem: ${e.message}")
    }

    @Test
    fun lossyAnimationFramesAreReportedUndecodableUpFront() {
        val bytes = lossyAnimation()
        val info = ImageKodec.probe(bytes)
        assertEquals(8, info.width)
        assertEquals(1, info.frameCount)
        assertFalse(info.isDecodable, "a lossy animation frame is still lossy VP8")
        assertTrue("lossy" in info.unsupportedReason.orEmpty(), info.unsupportedReason.orEmpty())

        assertFailsWith<UnsupportedImageException> { ImageKodec.decodeAnimation(bytes) }
        assertFailsWith<UnsupportedImageException> { ImageKodec.decode(bytes) }
    }

    @Test
    fun lossyDecodeFailsByNameNotByCrash() {
        val e = assertFailsWith<UnsupportedImageException> { ImageKodec.decode(hex(LOSSY)) }
        assertTrue("VP8" in e.message.orEmpty(), e.message.orEmpty())
        assertFailsWith<UnsupportedImageException> { ImageKodec.decode(hex(LOSSY_ALPHA)) }
    }

    // --- lossless pixels ----------------------------------------------------------

    @Test
    fun losslessRgbMatchesTheReferenceDecode() {
        val bm = ImageKodec.decode(hex(LOSSLESS_RGB))
        assertEquals(16, bm.width)
        assertEquals(12, bm.height)
        for (y in 0 until 12) for (x in 0 until 16) {
            assertEquals(argb(0xFF, x * 15, y * 20, (x * y) and 0xFF), bm[x, y], "($x, $y)")
        }
    }

    @Test
    fun losslessAlphaMatchesTheReferenceDecode() {
        val bm = ImageKodec.decode(hex(LOSSLESS_ALPHA))
        assertEquals(8, bm.width)
        assertEquals(8, bm.height)
        for (y in 0 until 8) for (x in 0 until 8) {
            assertEquals(argb(((x + y) * 15) and 0xFF, x * 30, y * 30, 128), bm[x, y], "($x, $y)")
        }
    }

    @Test
    fun colourIndexedImageUnpacksCorrectly() {
        val palette = intArrayOf(
            argb(0xFF, 0xFF, 0, 0), argb(0xFF, 0, 0xFF, 0),
            argb(0xFF, 0, 0, 0xFF), argb(0xFF, 0xFF, 0xFF, 0),
        )
        val bm = ImageKodec.decode(hex(LOSSLESS_PALETTE))
        assertEquals(16, bm.width)
        assertEquals(16, bm.height)
        for (y in 0 until 16) for (x in 0 until 16) {
            assertEquals(palette[(x / 4 + y / 4) % 4], bm[x, y], "($x, $y)")
        }
    }

    @Test
    fun stillDecodesAsASingleFrameAnimation() {
        val anim = ImageKodec.decodeAnimation(hex(LOSSLESS_RGB))
        assertEquals(1, anim.frames.size)
        assertFalse(anim.isAnimated)
        assertEquals(16, anim.width)
    }

    // --- animation ----------------------------------------------------------------

    @Test
    fun animationDecodesEveryFrame() {
        val anim = ImageKodec.decodeAnimation(hex(ANIMATION))
        assertEquals(3, anim.frames.size)
        assertEquals(8, anim.width)
        assertEquals(8, anim.height)
        assertEquals(0L, anim.loopCount)
        assertTrue(anim.isAnimated)

        for (i in 0 until 3) {
            val bm = anim.frames[i].bitmap
            assertEquals(100, anim.frames[i].delayMillis, "frame $i delay")
            for (y in 0 until 8) for (x in 0 until 8) {
                val expect = argb(0xFF, (x * 30 + i * 80) and 0xFF, (y * 30) and 0xFF, (i * 90) and 0xFF)
                assertEquals(expect, bm[x, y], "frame $i ($x, $y)")
            }
        }
        assertEquals(300, anim.durationMillis)
    }

    @Test
    fun stillDecodeOfAnAnimationGivesTheFirstFrame() {
        val bm = ImageKodec.decode(hex(ANIMATION))
        assertEquals(8, bm.width)
        assertEquals(argb(0xFF, 0, 0, 0), bm[0, 0])
    }

    @Test
    fun cancellationStopsBetweenFrames() {
        class Abort : RuntimeException()
        var calls = 0
        assertFailsWith<Abort> {
            ImageKodec.decodeAnimation(hex(ANIMATION)) {
                calls++
                throw Abort()
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun probeAgreesWithTheAnimationDecoder() {
        for (v in listOf(LOSSLESS_RGB, LOSSLESS_ALPHA, LOSSLESS_PALETTE, ANIMATION)) {
            val info = ImageKodec.probe(hex(v))
            val anim = ImageKodec.decodeAnimation(hex(v))
            assertEquals(anim.frames.size, info.frameCount, "frame count")
            assertEquals(anim.width, info.width, "width")
            assertEquals(anim.height, info.height, "height")
        }
    }

    // --- malformed input -----------------------------------------------------------

    @Test
    fun truncatedFilesThrowDecodeErrorsNotCrashes() {
        // Every cut loses part of the image data the chunk declares, so none may decode to pixels the
        // file does not hold (#79, #102).
        val whole = hex(LOSSLESS_RGB)
        for (cut in intArrayOf(4, 11, 13, 20, 30, whole.size - 1)) {
            assertFailsWith<ImageDecodeException>("cut at $cut") { ImageKodec.decode(whole.copyOf(cut)) }
        }
    }

    @Test
    fun aRiffThatIsNotWebpIsRejected() {
        val bytes = hex(LOSSLESS_RGB).copyOf()
        bytes[8] = 'X'.code.toByte()
        // Sniffing no longer recognises it, so this is an unknown format.
        assertFailsWith<ImageDecodeException> { ImageKodec.decode(bytes) }
    }

    // --- a probe over the start of a file -----------------------------------------------------
    // The Coil binding probes the first 64 KiB and claims the file when it is decodable, so the
    // answer for a prefix has to be right or say that it cannot tell.

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun le24(v: Int) = le16(v) + byteArrayOf(((v shr 16) and 0xFF).toByte())
    private fun le32(v: Int) = le16(v) + le16(v shr 16)
    private fun chunk(tag: String, body: ByteArray) = tag.encodeToByteArray() + le32(body.size) + body

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = "WEBP".encodeToByteArray() + chunks.reduce { a, b -> a + b }
        return "RIFF".encodeToByteArray() + le32(body.size) + body
    }

    /** A 16 by 16 VP8 key frame header, then [padding] bytes that no probe reads. */
    private fun lossyChunk(padding: Int) = chunk("VP8 ", byteArrayOf(0x30, 0, 0, 0x9D.toByte(), 0x01, 0x2A) + le16(16) + le16(16) + ByteArray(padding))

    /** A 16 by 16 VP8L header (signature, then 14-bit width and height minus one), then [padding] bytes. */
    private fun losslessChunk(padding: Int) = chunk("VP8L", byteArrayOf(0x2F, 0x0F, 0xC0.toByte(), 0x03, 0x00) + ByteArray(padding))

    private val vp8x = chunk("VP8X", byteArrayOf(0x20, 0, 0, 0) + le24(15) + le24(15))

    @Test
    fun aLossyChunkCutOffMidwayIsStillLossy() {
        // VP8X, then a VP8 chunk that the data stops in the middle of. Its first bytes are there.
        val cut = riff(vp8x, lossyChunk(padding = 5000)).copyOf(12 + 18 + 8 + 100)
        val info = ImageKodec.probe(cut)
        assertFalse(info.isDecodable)
        assertTrue("lossy" in info.unsupportedReason.orEmpty(), info.unsupportedReason.orEmpty())
    }

    @Test
    fun aSimpleFileCutOffInsideItsImageChunkIsStillProbed() {
        // No VP8X: the probe read nothing from a chunk that ran past the data, and threw.
        val lossy = ImageKodec.probe(riff(lossyChunk(padding = 5000)).copyOf(12 + 8 + 100))
        assertEquals(16, lossy.width)
        assertFalse(lossy.isDecodable)
        assertTrue("lossy" in lossy.unsupportedReason.orEmpty(), lossy.unsupportedReason.orEmpty())

        val lossless = ImageKodec.probe(riff(losslessChunk(padding = 5000)).copyOf(12 + 8 + 100))
        assertEquals(16, lossless.width)
        assertEquals(16, lossless.height)
        assertTrue(lossless.isDecodable, lossless.unsupportedReason.orEmpty())
    }

    @Test
    fun aPrefixThatEndsBeforeTheImageChunkIsNotDecodable() {
        // A 70,000-byte profile pushes the image chunk past the first 64 KiB. Nothing in the prefix says
        // whether the image is lossy, so the probe must not say it can decode it.
        val file = riff(vp8x, chunk("ICCP", ByteArray(70_000)), lossyChunk(padding = 2000))
        val info = ImageKodec.probe(file.copyOf(65_536))
        assertEquals(16, info.width)
        assertFalse(info.isDecodable)
        assertTrue("image chunk" in info.unsupportedReason.orEmpty(), info.unsupportedReason.orEmpty())
        // The whole file is read correctly.
        assertTrue("lossy" in ImageKodec.probe(file).unsupportedReason.orEmpty())
    }
}
