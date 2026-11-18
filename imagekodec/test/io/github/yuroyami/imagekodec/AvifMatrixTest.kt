package io.github.yuroyami.imagekodec

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AVIF's constant luminance and ICtCp matrices (#189) against zimg's reading, and what stays refused. */
class AvifMatrixTest {

    @Test
    fun constantLuminanceAndIctcpMatchZimg() {
        for ((name, file, rgb) in listOf(
            Triple("BT.2020 constant luminance", AvifMatrixFixtures.m10t14, AvifMatrixFixtures.m10t14Rgb),
            Triple("chromaticity-derived constant luminance", AvifMatrixFixtures.m13t1, AvifMatrixFixtures.m13t1Rgb),
            Triple("ICtCp, PQ", AvifMatrixFixtures.m14t16, AvifMatrixFixtures.m14t16Rgb),
            Triple("ICtCp, HLG", AvifMatrixFixtures.m14t18, AvifMatrixFixtures.m14t18Rgb),
        )) {
            assertTrue(ImageKodec.probe(file).isDecodable, name)
            val decoded = ImageKodec.decode(file)
            for (i in decoded.argb.indices) for (c in 0 until 3) {
                val want = rgb[i * 3 + c].toInt() and 0xFF
                val got = (decoded.argb[i] shr (16 - 8 * c)) and 0xFF
                assertTrue(abs(want - got) <= 1, "$name pixel $i channel $c: $got, zimg $want")
            }
        }
    }

    /** [file] with its nclx box's matrix and transfer replaced. */
    private fun recoded(file: ByteArray, transfer: Int, matrix: Int): ByteArray {
        val out = file.copyOf()
        val at = (0 until out.size - 4).first { out[it] == 'n'.code.toByte() && out[it + 1] == 'c'.code.toByte() && out[it + 2] == 'l'.code.toByte() && out[it + 3] == 'x'.code.toByte() }
        out[at + 7] = transfer.toByte()
        out[at + 9] = matrix.toByte()
        return out
    }

    @Test
    fun whatNoReaderConvertsStaysRefused() {
        for ((file, reason) in listOf(
            AvifMatrixFixtures.m11 to "SMPTE ST 2085",
            // Constant luminance through the sRGB curve, and ICtCp through BT.709's, which zimg refuses too.
            recoded(AvifMatrixFixtures.m10t14, 13, 10) to "constant luminance",
            recoded(AvifMatrixFixtures.m14t16, 1, 14) to "ICtCp",
            recoded(AvifMatrixFixtures.m10t14, 14, 15) to "matrix coefficients 15",
        )) {
            val info = ImageKodec.probe(file)
            assertFalse(info.isDecodable, reason)
            assertTrue(reason in info.unsupportedReason.orEmpty(), info.unsupportedReason)
            assertFailsWith<UnsupportedImageException>(reason) { ImageKodec.decode(file) }
        }
    }
}
