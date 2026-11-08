package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.JpxDecoder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [ImageKodec.decodeJpxComponents]: the components as the codestream stores them (#121). */
class JpxComponentsTest {

    /** The samples Pillow 12.3 was given for both fixtures below: an 8 by 8 RGBA image. */
    private fun expected(x: Int, y: Int, c: Int): Int = when (c) {
        0 -> (x * 31 + y * 7) % 256
        1 -> (x * 13 + y * 41) % 256
        2 -> (255 - x * 29 - y * 3).mod(256)
        else -> (x * y * 9 + 5) % 256
    }

    @Test
    fun aLosslessFourComponentFileGivesItsSamplesBack() {
        // Pillow saves RGBA as a raw codestream of four components, and in a JP2 whose colr box,
        // patched here, names CMYK (enumerated 12). The decode reads the first as RGBA and converts the
        // second to RGB; the components are the samples either way.
        for ((name, data) in listOf("raw codestream" to JPX_RGBA_J2K, "JP2 with colr CMYK" to JPX_CMYK_JP2)) {
            val c = ImageKodec.decodeJpxComponents(data)
            assertEquals(8, c.width, name)
            assertEquals(8, c.height, name)
            assertEquals(4, c.componentCount, name)
            assertContentEquals(intArrayOf(8, 8, 8, 8), c.bitDepths, name)
            for (y in 0 until 8) for (x in 0 until 8) for (k in 0 until 4) {
                assertEquals(expected(x, y, k), c[x, y, k], "$name ($x, $y) component $k")
            }
        }
        assertNull(ImageKodec.decodeJpxComponents(JPX_RGBA_J2K).colorSpecification)
        val spec = assertNotNull(ImageKodec.decodeJpxComponents(JPX_CMYK_JP2).colorSpecification)
        assertEquals(1, spec.method)
        assertEquals(12, spec.enumerated)
        assertNull(spec.iccProfile)
    }

    @Test
    fun theColorStageOfTheComponentsGivesTheDecode() {
        val fixtures = listOf(
            "JP2" to hex(JP2),
            "syccIssue" to Jp2ColorFixtures.syccIssue, "swapped" to Jp2ColorFixtures.swapped,
            "palette" to Jp2ColorFixtures.palette, "sycc" to Jp2ColorFixtures.sycc, "esycc" to Jp2ColorFixtures.esycc,
            "cmyk" to Jp2ColorFixtures.cmyk, "cmy" to Jp2ColorFixtures.cmy, "premultiplied" to Jp2ColorFixtures.premultiplied,
            "straight" to Jp2ColorFixtures.straight, "iccGray" to Jp2ColorFixtures.iccGray, "lab" to Jp2ColorFixtures.lab,
            "iccLab" to Jp2ColorFixtures.iccLab, "rgbaCodestream" to Jp2AlphaFixtures.rgbaCodestream,
            "grayAlphaCodestream" to Jp2AlphaFixtures.grayAlphaCodestream, "rgbaJp2" to Jp2AlphaFixtures.rgbaJp2,
            "rgba j2k" to JPX_RGBA_J2K, "cmyk jp2" to JPX_CMYK_JP2,
        )
        var compared = 0
        for ((name, data) in fixtures) for (reduction in listOf(1, 2, 4, 8)) {
            val label = "$name at 1/$reduction"
            val decoded = JpxDecoder.decode(data, reduction)
            val components = runCatching { ImageKodec.decodeJpxComponents(data, reduction) }.getOrNull()
            if (components == null) {
                assertNull(decoded, "$label: the decode reads a file the components refuse")
                continue
            }
            // A colour box the decoder cannot apply refuses the decode, not the components. A decode
            // that averages levels a component lacks averages after its colour stage, which is no
            // linear map for CMYK or YCC, so only an exact reduction can match.
            if (decoded == null || components.bitDepths.any { it != 8 } || !JpxDecoder.reducesExactly(data, reduction)) continue
            val staged = JpxDecoder.colorStage(data, components)
            assertEquals(decoded.width to decoded.height, staged.width to staged.height, label)
            assertContentEquals(decoded.pixelBytes, staged.pixelBytes, label)
            assertContentEquals(decoded.alpha, staged.alpha, label)
            compared++
        }
        assertTrue(compared >= 40, "only $compared decodes were compared")
    }

    @Test
    fun theArgumentsAreChecked() {
        assertFailsWith<IllegalArgumentException> { ImageKodec.decodeJpxComponents(JPX_RGBA_J2K, 3) }
        assertFailsWith<ImageDecodeException> { ImageKodec.decodeJpxComponents(ImageKodec.encodePng(KiteBitmap(1, 1, IntArray(1)))) }
        assertFailsWith<ImageDecodeException> { ImageKodec.decodeJpxComponents(JPX_RGBA_J2K.copyOf(60)) }
        val half = ImageKodec.decodeJpxComponents(JPX_RGBA_J2K, 2)
        assertEquals(4 to 4, half.width to half.height)
    }
}

internal val JPX_RGBA_J2K = hex(
    "ff4fff510032000000000008000000080000000000000000000000080000000800000000000000000004070101070101" +
    "070101070101ff52000c00000001000304040001ff5c000d4040484850484850484850ff640025000143726561746564" +
    "206279204f70656e4a5045472076657273696f6e20322e352e34ff90000a0000000001110001ff93c7d404059fc3e704" +
    "096fc7d404015fcfb4080567cfc00a0f9c0c01d04004730007c1f3829f80143ed020003f0457056fcfc00a07c808074f" +
    "097fc7da051f68147e00400417040f046fc7da0907ce0e1f68180d9bc27f0b98f31083a0c1f3849f80343ed0400a9393" +
    "b9105bf0c1237f0c3106e3c7da0701f0840e0b3b0c14c1f38583e70b0fb4100d90df605f0b7c71a7bf103483cccfc01a" +
    "1f503c7e006024bffe5d231023c8a3076ffb7f23c547c7da133f00c8fc01400139432d189cd124d101b2de680475c330" +
    "773a587f04fdf396a3c1f384807430216c75be00ce7fcfc02a7e0151f8040023152f4fe0e02c747f331fbc45125a3193" +
    "1316101a143770d5193994ffd9",
)
internal val JPX_CMYK_JP2 = hex(
    "0000000c6a5020200d0a870a00000014667479706a703220000000006a7032200000004f6a7032680000001669686472" +
    "00000008000000080004070700000000000f636f6c720100000000000c00000022636465660004000000000001000100" +
    "000002000200000003000300010000000001956a703263ff4fff51003200000000000800000008000000000000000000" +
    "0000080000000800000000000000000004070101070101070101070101ff52000c00000001000304040001ff5c000d40" +
    "40484850484850484850ff640025000143726561746564206279204f70656e4a5045472076657273696f6e20322e352e" +
    "34ff90000a0000000001110001ff93c7d404059fc3e704096fc7d404015fcfb4080567cfc00a0f9c0c01d04004730007" +
    "c1f3829f80143ed020003f0457056fcfc00a07c808074f097fc7da051f68147e00400417040f046fc7da0907ce0e1f68" +
    "180d9bc27f0b98f31083a0c1f3849f80343ed0400a9393b9105bf0c1237f0c3106e3c7da0701f0840e0b3b0c14c1f385" +
    "83e70b0fb4100d90df605f0b7c71a7bf103483cccfc01a1f503c7e006024bffe5d231023c8a3076ffb7f23c547c7da13" +
    "3f00c8fc01400139432d189cd124d101b2de680475c330773a587f04fdf396a3c1f384807430216c75be00ce7fcfc02a" +
    "7e0151f8040023152f4fe0e02c747f331fbc45125a31931316101a143770d5193994ffd9",
)
