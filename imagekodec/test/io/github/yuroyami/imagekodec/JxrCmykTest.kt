package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.jxr.JxrDecoder
import io.github.yuroyami.imagekodec.codec.jxr.JxrEncoder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * JPEG XR CMYK and the N-channel formats (#189) on every target: lossless files of 32bppCMYK,
 * 40bppCMYKAlpha, 64bppCMYK, 80bppCMYKAlpha and of 3 to 8 channels give the samples back, lossy
 * ones come close, the bytes and the decoded pixels are the same everywhere, and the cancellation
 * check stops the encode. `JxrCmykOracleTest` holds the files to jxrlib's `JxrDecApp`.
 */
class JxrCmykTest {

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

    private fun bytes(v: IntArray) = ByteArray(v.size) { v[it].toByte() }

    private fun shorts(v: IntArray) = ShortArray(v.size) { v[it].toShort() }

    @Test
    fun losslessGivesTheInkBack() {
        for ((w, h) in listOf(1 to 1, 1 to 37, 37 to 1, 16 to 16, 33 to 19)) {
            for (seed in 0 until 2) {
                val (ink, alpha) = JxrCmykFixtures.ink(w, h, seed, alpha = true)
                val ink16 = JxrCmykFixtures.ink16(w, h, seed)
                val alpha16 = IntArray(w * h) { (alpha[it].toInt() and 0xFF) * 257 }
                for (overlap in 0..2) {
                    val o = JxrEncodeOptions(lossless = true, overlap = overlap, planarAlpha = overlap != 1)
                    val tag = "$w x $h seed $seed overlap $overlap"
                    var d = JxrDecoder.decodeRaw(ImageKodec.encodeJxrCmyk(w, h, ink, o))
                    assertEquals("32bppCMYK", d.format.name)
                    assertContentEquals(IntArray(ink.size) { ink[it].toInt() and 0xFF }, d.raster.samples, "32bppCMYK $tag")
                    d = JxrDecoder.decodeRaw(JxrEncoder.encodeCmyk(w, h, ink, alpha, o, ImageMetadata(), null))
                    assertEquals("40bppCMYKAlpha", d.format.name)
                    assertContentEquals(IntArray(w * h * 5) { i -> (if (i % 5 == 4) alpha[i / 5] else ink[i / 5 * 4 + i % 5]).toInt() and 0xFF }, d.raster.samples, "40bppCMYKAlpha $tag")
                    d = JxrDecoder.decodeRaw(ImageKodec.encodeJxrCmyk(w, h, shorts(ink16), o))
                    assertEquals("64bppCMYK", d.format.name)
                    assertContentEquals(ink16, d.raster.samples, "64bppCMYK $tag")
                    d = JxrDecoder.decodeRaw(JxrEncoder.encodeCmyk16(w, h, shorts(ink16), shorts(alpha16), o, ImageMetadata(), null))
                    assertEquals("80bppCMYKAlpha", d.format.name)
                    assertContentEquals(IntArray(w * h * 5) { i -> if (i % 5 == 4) alpha16[i / 5] else ink16[i / 5 * 4 + i % 5] }, d.raster.samples, "80bppCMYKAlpha $tag")
                }
            }
        }
    }

    /** Lossy ink lands near the input, and `decode` draws it as a CMYK TIFF is drawn. */
    @Test
    fun lossyComesClose() {
        val w = 48
        val h = 40
        val (ink, _) = JxrCmykFixtures.ink(w, h, 7, alpha = false)
        for (q in listOf(95, 75)) {
            val got = JxrDecoder.decodeRaw(ImageKodec.encodeJxrCmyk(w, h, ink, JxrEncodeOptions(quality = q))).raster.samples
            var sum = 0L
            for (i in got.indices) sum += kotlin.math.abs(got[i] - (ink[i].toInt() and 0xFF))
            val mean = sum.toDouble() / got.size
            assertTrue(mean < (if (q == 95) 2.0 else 5.0), "quality $q: mean error $mean")
        }
        val jxr = ImageKodec.encodeJxrCmyk(w, h, ink, JxrEncodeOptions(lossless = true))
        val rgb = ImageKodec.decode(jxr)
        for (i in 0 until w * h) {
            val k = 255 - (ink[i * 4 + 3].toInt() and 0xFF)
            fun ch(c: Int) = k * (255 - (ink[i * 4 + c].toInt() and 0xFF)) / 255
            assertEquals((0xFF shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2), rgb.argb[i], "pixel $i")
        }
        assertEquals(4, ImageKodec.probe(jxr).colorChannels)
        assertTrue(ImageKodec.probe(jxr).isDecodable)
    }

    @Test
    fun outputIsTheSameEverywhere() {
        val w = 40
        val h = 33
        val (ink, alpha) = JxrCmykFixtures.ink(w, h, 3, alpha = true)
        val ink16 = shorts(JxrCmykFixtures.ink16(w, h, 3))
        val lossy = ImageKodec.encodeJxrCmyk(w, h, ink, JxrEncodeOptions(quality = 70))
        val hashes = listOf(
            fnv(ImageKodec.encodeJxrCmyk(w, h, ink, JxrEncodeOptions(lossless = true))),
            fnv(lossy),
            fnv(JxrEncoder.encodeCmyk(w, h, ink, alpha, JxrEncodeOptions(quality = 40, planarAlpha = false, tileSize = 16, frequencyOrder = true), ImageMetadata(), null)),
            fnv(ImageKodec.encodeJxrCmyk(w, h, ink16, JxrEncodeOptions(quality = 60, overlap = 2))),
            fnv(ImageKodec.decode(lossy).argb),
            fnv(ImageKodec.decode16(ImageKodec.encodeJxrCmyk(w, h, ink16, JxrEncodeOptions(quality = 60))).samples.let { s -> IntArray(s.size) { s[it].toInt() } }),
        )
        assertEquals(EXPECTED_HASHES, hashes)
    }

    @Test
    fun nChannelLosslessGivesTheSamplesBack() {
        for ((w, h) in listOf(1 to 1, 1 to 20, 20 to 1, 23 to 17)) {
            for (n in 3..8) for (depth in listOf(8, 16)) {
                val max = if (depth == 8) 255 else 65535
                val src = IntArray(w * h * n) { i -> ((i / n) * (19 + i % n * 11) + (i % n) * 29) % (max + 1) }
                val a = IntArray(w * h) { (it * 13 + n) % (max + 1) }
                val o = JxrEncodeOptions(lossless = true)
                var d = JxrDecoder.decodeRaw(JxrEncoder.encodeChannels(w, h, depth, n, src, null, o, ImageMetadata(), null))
                assertEquals(n, d.format.colorChannels)
                assertContentEquals(src, d.raster.samples, "$w x $h, $n channels, $depth bits")
                d = JxrDecoder.decodeRaw(JxrEncoder.encodeChannels(w, h, depth, n, src, a, o, ImageMetadata(), null))
                assertTrue(d.alpha)
                assertContentEquals(IntArray(w * h * (n + 1)) { i -> if (i % (n + 1) == n) a[i / (n + 1)] else src[i / (n + 1) * n + i % (n + 1)] }, d.raster.samples, "$w x $h, $n channels and alpha, $depth bits")
            }
        }
        // Three channels are RGB and more are CMYK, the rest dropped (T.832 Annex C).
        val rgb = IntArray(3) { 40 * it + 10 }
        assertEquals(0xFF0A325A.toInt(), ImageKodec.decode(JxrEncoder.encodeChannels(1, 1, 8, 3, rgb, null, JxrEncodeOptions(lossless = true), ImageMetadata(), null)).argb[0])
        val six = intArrayOf(0, 255, 0, 0, 77, 99)
        assertEquals(0xFFFF00FF.toInt(), ImageKodec.decode(JxrEncoder.encodeChannels(1, 1, 8, 6, six, null, JxrEncodeOptions(lossless = true), ImageMetadata(), null)).argb[0])
        assertFailsWith<IllegalArgumentException> { JxrEncoder.encodeChannels(2, 2, 8, 9, IntArray(36), null, JxrEncodeOptions(), ImageMetadata(), null) }
        val w = 40
        val h = 24
        val src = IntArray(w * h * 5) { (it * 23 + it / 5 * 7) % 256 }
        val hashes = listOf(
            fnv(JxrEncoder.encodeChannels(w, h, 8, 5, src, null, JxrEncodeOptions(quality = 65), ImageMetadata(), null)),
            fnv(JxrEncoder.encodeChannels(w, h, 16, 5, IntArray(src.size) { src[it] * 255 }, src.copyOf(w * h), JxrEncodeOptions(quality = 35, overlap = 2), ImageMetadata(), null)),
        )
        assertEquals(N_CHANNEL_HASHES, hashes)
    }

    @Test
    fun cancellationCheckStopsTheEncode() {
        class Stop : RuntimeException("stop")
        val (ink, _) = JxrCmykFixtures.ink(64, 64, 1, alpha = false)
        var calls = 0
        val plain = ImageKodec.encodeJxrCmyk(64, 64, ink, JxrEncodeOptions(quality = 60), cancellationCheck = { calls++ })
        assertContentEquals(ImageKodec.encodeJxrCmyk(64, 64, ink, JxrEncodeOptions(quality = 60)), plain)
        assertTrue(calls >= 4, "the check ran $calls times")
        val stop = Stop()
        var seen = 0
        val thrown = assertFailsWith<Stop> {
            ImageKodec.encodeJxrCmyk(64, 64, ink, JxrEncodeOptions(quality = 60), cancellationCheck = { if (++seen == 3) throw stop })
        }
        assertSame(stop, thrown)
    }

    @Test
    fun argumentsOutOfRangeAreRefused() {
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeJxrCmyk(2, 2, ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeJxrCmyk(0, 2, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeJxrCmyk(2, 2, ShortArray(17)) }
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeJxrCmyk(65536, 65536, ByteArray(4)) }
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeJxrCmyk(2, 2, ByteArray(16), JxrEncodeOptions(chroma = JxrChroma.Half)) }
    }

    private companion object {
        val N_CHANNEL_HASHES = listOf(3818969034653509690L, -6718133918471397281L)
        val EXPECTED_HASHES = listOf(
            6633006389945681630L, 1574794023083949133L, 3822049310298783550L,
            -3433521292003511450L, 4315239135805266195L, -2988490688366539861L,
        )
    }
}
