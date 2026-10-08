package io.github.yuroyami.imagekodec

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CMYK TIFFs (photometric 5, InkSet 1). Without a profile they draw as libtiff's RGBA interface
 * draws them (putRGBcontig8bitCMYKtile: each colorant's absence times black's, over 255,
 * truncated), which `TiffOracleTest` checks against tiff2rgba; with one, `IccOracleTest` holds
 * the conversion to lcms2.
 */
class TiffCmykTest {

    private val random = Random(5)
    private val w = 7
    private val h = 5
    private val samples = IntArray(w * h * 4) { if (it < 16) intArrayOf(0, 255, 128, 64)[it % 4] * (it / 4 % 2) else random.nextInt(256) }

    private fun libtiff(c: Int, m: Int, y: Int, k: Int): Int {
        val white = 255 - k
        return argb(0xFF, white * (255 - c) / 255, white * (255 - m) / 255, white * (255 - y) / 255)
    }

    @Test
    fun eightBitInkDrawsAsLibtiffDrawsIt() {
        val decoded = ImageKodec.decode(CmykTiffs.tiff(w, h, samples))
        for (i in 0 until w * h) {
            assertEquals(libtiff(samples[4 * i], samples[4 * i + 1], samples[4 * i + 2], samples[4 * i + 3]), decoded.argb[i], "pixel $i")
        }
        val info = ImageKodec.probe(CmykTiffs.tiff(w, h, samples))
        assertTrue(info.isDecodable)
        assertFalse(info.hasAlpha)
        // Separate planes read the same.
        assertContentEquals(decoded.argb, ImageKodec.decode(CmykTiffs.tiff(w, h, CmykTiffs.planes(samples, 4), planar = true)).argb)
        // decode16 widens exactly what decode draws.
        assertContentEquals(decoded.argb, ImageKodec.decode16(CmykTiffs.tiff(w, h, samples)).toBitmap().argb)
    }

    @Test
    fun sixteenBitInkKeepsItsPrecision() {
        val wide = IntArray(w * h * 4) { random.nextInt(65536) }
        val file = CmykTiffs.tiff(w, h, wide, bits = 16)
        val decoded = ImageKodec.decode16(file)
        assertEquals(3, decoded.channels)
        for (i in 0 until w * h) for (c in 0 until 3) {
            val want = ((65535L - wide[4 * i + 3]) * (65535 - wide[4 * i + c]) + 32767) / 65535
            assertEquals(want.toInt(), decoded.samples[3 * i + c].toInt() and 0xFFFF, "pixel $i, channel $c")
        }
        assertContentEquals(decoded.toBitmap().argb, ImageKodec.decode(file).argb)
    }

    @Test
    fun alphaRidesAlongStraightOrAssociated() {
        fun alphaOf(i: Int) = if (i == 0) 0 else 128 + 3 * i
        val inkAndAlpha = IntArray(w * h * 5) { i -> if (i % 5 == 4) alphaOf(i / 5) else samples[(i / 5) * 4 + i % 5] }
        val straight = ImageKodec.decode(CmykTiffs.tiff(w, h, inkAndAlpha, extraSamples = 2))
        assertTrue(ImageKodec.probe(CmykTiffs.tiff(w, h, inkAndAlpha, extraSamples = 2)).hasAlpha)
        for (i in 0 until w * h) {
            val want = libtiff(samples[4 * i], samples[4 * i + 1], samples[4 * i + 2], samples[4 * i + 3]) and 0xFFFFFF
            assertEquals((alphaOf(i) shl 24) or want, straight.argb[i], "pixel $i")
        }
        // Associated alpha: ink premultiplied by alpha comes back divided out, to within its rounding.
        val premultiplied = IntArray(inkAndAlpha.size) { i ->
            val a = inkAndAlpha[i - i % 5 + 4]
            if (i % 5 == 4) a else (inkAndAlpha[i] * a + 127) / 255
        }
        val associated = ImageKodec.decode(CmykTiffs.tiff(w, h, premultiplied, extraSamples = 1))
        assertEquals(0, associated.argb[0], "no alpha leaves no colour")
        for (i in 1 until w * h) {
            assertEquals(alphaOf(i), associated.argb[i] ushr 24)
            for (shift in intArrayOf(16, 8, 0)) {
                val d = ((associated.argb[i] shr shift) and 0xFF) - ((straight.argb[i] shr shift) and 0xFF)
                assertTrue(d in -3..3, "associated pixel $i is ${associated.argb[i].toUInt().toString(16)}, straight ${straight.argb[i].toUInt().toString(16)}")
            }
        }
    }

    @Test
    fun inksOtherThanCmykAreTypedRefusals() {
        for ((file, reason) in listOf(
            CmykTiffs.tiff(w, h, samples, inkSet = 2) to "InkSet 2",
            CmykTiffs.tiff(w, h, samples, inks = 3) to "3 inks",
        )) {
            val info = ImageKodec.probe(file)
            assertFalse(info.isDecodable)
            assertTrue(reason in info.unsupportedReason.orEmpty(), info.unsupportedReason)
            val error = assertFailsWith<UnsupportedImageException> { ImageKodec.decode(file) }
            assertTrue(reason in error.message.orEmpty(), error.message)
        }
        // The explicit defaults read as CMYK.
        assertContentEquals(ImageKodec.decode(CmykTiffs.tiff(w, h, samples)).argb, ImageKodec.decode(CmykTiffs.tiff(w, h, samples, inkSet = 1, inks = 4)).argb)
    }

    @Test
    fun aCmykProfileConvertsTheInk() {
        val icc = ColorConversionTest().cmykV2
        val file = CmykTiffs.tiff(w, h, samples, icc = icc)
        val ink = ByteArray(samples.size) { samples[it].toByte() }
        for (intent in RenderingIntent.entries) {
            val want = ImageKodec.convertCmykToSrgb(w, h, ink, ColorProfile(icc = icc), intent)!!
            assertContentEquals(want.argb, ImageKodec.decode(file, colorTarget = ColorTarget.Srgb(intent)).argb, "$intent")
        }
        // Without a target, the ink draws as libtiff draws it.
        assertContentEquals(ImageKodec.decode(CmykTiffs.tiff(w, h, samples)).argb, ImageKodec.decode(file).argb)
    }

    @Test
    fun aProfileThatIsNotCmykLeavesTheInkAsLibtiffDrawsIt() {
        // An RGB profile on a CMYK page does not fit it, so Srgb changes nothing.
        val file = CmykTiffs.tiff(w, h, samples, icc = ColorConversionTest().displayP3)
        assertContentEquals(ImageKodec.decode(file).argb, ImageKodec.decode(file, colorTarget = ColorTarget.Srgb()).argb)
    }
}
