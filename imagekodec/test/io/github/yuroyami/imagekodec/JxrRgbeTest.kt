package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.jxr.JxrDecoder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** JPEG XR's 32bppRGBE (#189): JxrDecApp's samples, and the picture they give. */
class JxrRgbeTest {

    @Test
    fun rgbeGivesJxrDecAppsSamples() {
        val info = ImageKodec.probe(JxrFixtures.rgbe)
        assertTrue(info.isDecodable, info.unsupportedReason)
        assertEquals(3, info.colorChannels)
        val raw = JxrDecoder.decodeRaw(JxrFixtures.rgbe).raster.samples
        for (i in JxrFixtures.rgbeSamples.indices) assertEquals(JxrFixtures.rgbeSamples[i].toInt() and 0xFF, raw[i], "sample $i")
    }

    @Test
    fun aMantissaTimesTwoToTheExponentLess136IsLinearLight() {
        val wide = ImageKodec.decode16(JxrFixtures.rgbe)
        val narrow = ImageKodec.decode(JxrFixtures.rgbe)
        val s = JxrFixtures.rgbeSamples
        for (i in 0 until wide.width * wide.height) for (c in 0 until 3) {
            val e = s[i * 4 + 3].toInt() and 0xFF
            val linear = if (e == 0) 0.0 else (s[i * 4 + c].toInt() and 0xFF) * 2.0.pow(e - 136)
            val encoded = if (linear <= 0.0031308) linear * 12.92 else 1.055 * linear.pow(1 / 2.4) - 0.055
            val want = (encoded.coerceIn(0.0, 1.0) * 65535 + 0.5).toInt()
            val got = wide.samples[i * 3 + c].toInt() and 0xFFFF
            assertTrue(abs(want - got) <= 1, "pixel $i channel $c: $got, the formula gives $want")
            assertEquals(got ushr 8, (narrow.argb[i] shr (16 - 8 * c)) and 0xFF)
        }
    }

    @Test
    fun aCutRgbeFileIsADecodeError() {
        val cut = JxrFixtures.rgbe.copyOf(JxrFixtures.rgbe.size - 200)
        assertFailsWith<ImageDecodeException> { ImageKodec.decode(cut) }
    }
}
