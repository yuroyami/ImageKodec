package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.JpxDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The JP2 header's palette, channel definitions and colour specification applied as T.800
 * Annex I.5.3 orders them. Each used to be ignored, so the samples came back as if they were
 * RGB or gray (#57). The expected colours are OpenJPEG's, which `JpxOracleTest` also checks.
 */
class Jp2ColorTest {

    private fun pixel(r: JpxDecoder.Result, x: Int, y: Int): List<Int> {
        val n = r.pixelBytes.size / (r.width * r.height)
        return List(n) { r.pixelBytes[(y * r.width + x) * n + it].toInt() and 0xFF }
    }

    private fun everyPixel(name: String, bytes: ByteArray, expected: (x: Int, y: Int) -> List<Int>) {
        val r = JpxDecoder.decodeForFacade(bytes, 1)
        for (y in 0 until r.height) for (x in 0 until r.width) assertEquals(expected(x, y), pixel(r, x, y), "$name at ($x, $y)")
    }

    @Test
    fun syccConvertsToRgb() {
        everyPixel("sYCC, #57", Jp2ColorFixtures.syccIssue) { _, _ -> listOf(255, 87, 0) }
        everyPixel("sYCC", Jp2ColorFixtures.sycc) { _, _ -> listOf(228, 99, 15) }
        everyPixel("e-sYCC", Jp2ColorFixtures.esycc) { _, _ -> listOf(229, 99, 15) }
    }

    @Test
    fun channelDefinitionsReorderTheColours() =
        everyPixel("swapped associations", Jp2ColorFixtures.swapped) { _, _ -> listOf(240, 16, 128) }

    @Test
    fun aPaletteMapsItsIndices() {
        val colors = listOf(listOf(255, 0, 0), listOf(0, 255, 0), listOf(0, 0, 255), listOf(255, 255, 0))
        everyPixel("palette", Jp2ColorFixtures.palette) { x, _ -> colors[(x / 8) % 4] }
        assertEquals("DeviceRGB", JpxDecoder.decodeForFacade(Jp2ColorFixtures.palette, 1).colorSpace)
    }

    @Test
    fun inksConvertToRgb() {
        val cmyk = listOf(listOf(255, 255, 255), listOf(0, 0, 0), listOf(52, 216, 158), listOf(14, 4, 11))
        everyPixel("CMYK", Jp2ColorFixtures.cmyk) { x, y -> cmyk[(y / 8) * 2 + x / 8] }
        val cmy = listOf(listOf(255, 255, 255), listOf(0, 0, 0), listOf(55, 225, 165), listOf(240, 75, 195))
        everyPixel("CMY", Jp2ColorFixtures.cmy) { x, y -> cmy[(y / 8) * 2 + x / 8] }
    }

    @Test
    fun opacityIsTheAlphaPlaneAndPremultipliedOpacityIsDividedOut() {
        val straight = JpxDecoder.decodeForFacade(Jp2ColorFixtures.straight, 1)
        val premultiplied = JpxDecoder.decodeForFacade(Jp2ColorFixtures.premultiplied, 1)
        for (r in listOf(straight, premultiplied)) {
            assertEquals("DeviceGray", r.colorSpace)
            for (a in assertNotNull(r.alpha)) assertEquals(128, a.toInt() and 0xFF)
        }
        for (g in straight.pixelBytes) assertEquals(64, g.toInt() and 0xFF)
        // 64 premultiplied by an opacity of 128 is a straight 128.
        for (g in premultiplied.pixelBytes) assertEquals(128, g.toInt() and 0xFF)
        assertTrue(ImageKodec.probe(Jp2ColorFixtures.straight).hasAlpha)
    }

    @Test
    fun anIccProfileChoosesTheChannelsAndLeavesTheSamples() {
        everyPixel("ICC gray", Jp2ColorFixtures.iccGray) { x, _ -> listOf((x * 16) % 256) }
        assertEquals("DeviceGray", JpxDecoder.decodeForFacade(Jp2ColorFixtures.iccGray, 1).colorSpace)
    }

    @Test
    fun aColourSpaceThisDecoderCannotConvertIsRefusedByName() {
        for ((bytes, reason) in listOf(
            Jp2ColorFixtures.lab to "JPEG 2000 with the enumerated colour space 14 (CIELab)",
            Jp2ColorFixtures.iccLab to "JPEG 2000 with an ICC profile of colour space 'Lab'",
        )) {
            val info = ImageKodec.probe(bytes)
            assertFalse(info.isDecodable, reason)
            assertEquals(reason, info.unsupportedReason)
            val e = assertFailsWith<UnsupportedImageException> { ImageKodec.decode(bytes) }
            assertTrue(e.message.orEmpty().startsWith(reason), e.message)
            assertNull(JpxDecoder.decode(bytes))
        }
    }
}
