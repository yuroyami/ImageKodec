package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.jxl.JxlDecoder
import io.github.yuroyami.imagekodec.codec.jxl.JxlEncoder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * JPEG XL CMYK (#189) on every target: lossless CMYK files, their black in a kBlack extra channel,
 * give the ink back at 8 and 16 bits with and without alpha, `decode` converts the ink through
 * the file's CMYK profile at the intent its header names, and the bytes and pixels are the same
 * everywhere. `JxlCmykOracleTest` holds the stored samples to djxl, and `IccOracleTest` the
 * conversion to lcms2.
 */
class JxlCmykTest {

    private val icc = ColorConversionTest().cmykV4

    private fun fnv(d: ByteArray): Long {
        var h = -0x340d631b7bdddcdbL
        for (b in d) {
            h = h xor (b.toLong() and 0xFF)
            h *= 0x100000001b3L
        }
        return h
    }

    private fun fnv(d: IntArray): Long {
        var h = -0x340d631b7bdddcdbL
        for (v in d) {
            h = h xor (v.toLong() and 0xFFFFFFFFL)
            h *= 0x100000001b3L
        }
        return h
    }

    private fun ink8(w: Int, h: Int, seed: Int): IntArray {
        val (b, _) = JxrCmykFixtures.ink(w, h, seed, alpha = false)
        return IntArray(b.size) { b[it].toInt() and 0xFF }
    }

    private val lossless = JxlEncodeOptions(lossless = true)

    @Test
    fun losslessGivesTheInkBack() {
        for ((w, h) in listOf(1 to 1, 1 to 29, 29 to 1, 33 to 19)) {
            for (bits in listOf(8, 16)) for (withAlpha in listOf(false, true)) {
                val max = (1 shl bits) - 1
                val ink = if (bits == 8) ink8(w, h, w * h) else JxrCmykFixtures.ink16(w, h, w * h)
                val alpha = if (withAlpha) IntArray(w * h) { (it * 41 + 7) % (max + 1) } else null
                val tag = "$w x $h, $bits bits, alpha $withAlpha"
                val jxl = JxlEncoder.encodeCmyk(w, h, bits, ink, alpha, icc, lossless, ImageMetadata(), null)
                val info = ImageKodec.probe(jxl)
                assertTrue(info.isDecodable, "$tag: ${info.unsupportedReason}")
                assertEquals(4, info.colorChannels, tag)
                assertEquals(withAlpha, info.hasAlpha, tag)
                val back = JxlDecoder.decodeInk(jxl, wide = true)!!
                assertContentEquals(IntArray(ink.size) { if (bits == 8) ink[it] * 257 else ink[it] }, IntArray(ink.size) { back.ink16!![it].toInt() and 0xFFFF }, tag)
                if (alpha != null) assertContentEquals(IntArray(alpha.size) { if (bits == 8) alpha[it] * 257 else alpha[it] }, IntArray(alpha.size) { back.alpha16!![it].toInt() and 0xFFFF }, tag)
                if (bits == 8) {
                    // decode converts through the profile at its header's intent, perceptual here.
                    val bytes = ByteArray(ink.size) { ink[it].toByte() }
                    val want = ImageKodec.convertCmykToSrgb(w, h, bytes, ColorProfile(icc = icc), RenderingIntent.Perceptual)!!
                    val got = ImageKodec.decode(jxl)
                    for (i in 0 until w * h) {
                        val a = if (alpha != null) alpha[i] else 255
                        assertEquals((a shl 24) or (want.argb[i] and 0xFFFFFF), got.argb[i], "$tag: pixel $i")
                    }
                }
            }
        }
    }

    @Test
    fun outputIsTheSameEverywhere() {
        val w = 40
        val h = 27
        val ink = ink8(w, h, 5)
        val alpha = IntArray(w * h) { (it * 3) % 256 }
        val jxl = JxlEncoder.encodeCmyk(w, h, 8, ink, alpha, icc, lossless, ImageMetadata(), null)
        val jxl16 = JxlEncoder.encodeCmyk(w, h, 16, JxrCmykFixtures.ink16(w, h, 5), null, icc, lossless, ImageMetadata(), null)
        val hashes = listOf(
            fnv(jxl),
            fnv(jxl16),
            fnv(ImageKodec.decode(jxl).argb),
            fnv(ImageKodec.decode16(jxl16).samples.let { s -> IntArray(s.size) { s[it].toInt() } }),
        )
        assertEquals(EXPECTED_HASHES, hashes)
    }

    @Test
    fun aBlackChannelNeedsACmykProfile() {
        val ink = ink8(4, 4, 1)
        // A profile of RGB data cannot carry ink: the file is refused by name, as before #189.
        val rgb = ColorConversionTest().displayP3
        assertFailsWith<IllegalArgumentException> { JxlEncoder.encodeCmyk(4, 4, 8, ink, null, rgb, lossless, ImageMetadata(), null) }
        val jxl = JxlEncoder.encodeCmyk(4, 4, 8, ink, null, rgb, lossless, ImageMetadata(), null, checkProfile = false)
        val info = ImageKodec.probe(jxl)
        assertTrue(!info.isDecodable)
        assertEquals("JPEG XL with a black channel and no CMYK profile", info.unsupportedReason)
        assertFailsWith<UnsupportedImageException> { ImageKodec.decode(jxl) }
        assertFailsWith<IllegalArgumentException> { JxlEncoder.encodeCmyk(4, 4, 8, ink, null, icc, JxlEncodeOptions(), ImageMetadata(), null) }
        assertFailsWith<IllegalArgumentException> { JxlEncoder.encodeCmyk(4, 4, 12, ink, null, icc, lossless, ImageMetadata(), null) }
        assertFailsWith<IllegalArgumentException> { JxlEncoder.encodeCmyk(4, 4, 8, IntArray(63), null, icc, lossless, ImageMetadata(), null) }
    }

    @Test
    fun cancellationCheckStopsTheEncode() {
        class Stop : RuntimeException("stop")
        val ink = ink8(64, 48, 2)
        val stop = Stop()
        val thrown = assertFailsWith<Stop> {
            JxlEncoder.encodeCmyk(64, 48, 8, ink, null, icc, lossless, ImageMetadata(), { throw stop })
        }
        assertSame(stop, thrown)
    }

    private companion object {
        val EXPECTED_HASHES = listOf(-4412933830827501821L, -8082894832294574211L, -7242576441068613524L, 4284803722714148436L)
    }
}
