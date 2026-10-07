package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.JpegCmykFixtures.BLOCKS
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Four-component JPEGs. libjpeg reads them as CMYK under Adobe transform 0 or with no Adobe
 * marker, and as YCCK under any other transform; their samples follow Adobe's inverted
 * convention, so a stored value of 255 is no ink. `JpegCmykOracleTest` checks the same files
 * against ImageMagick.
 */
class JpegCmykTest {

    /** Adobe's inverted CMYK to RGB: the stored C times the stored K, over 255, rounded. */
    private fun expected(cmyk: IntArray): Int {
        fun times(a: Int, b: Int) = (a * b + 127) / 255
        val k = cmyk[3]
        return argb(0xFF, times(cmyk[0], k), times(cmyk[1], k), times(cmyk[2], k))
    }

    private fun assertBlocks(bitmap: KiteBitmap, tolerance: Int, name: String) {
        assertEquals(32, bitmap.width)
        assertEquals(16, bitmap.height)
        for (y in 0 until 16) for (x in 0 until 32) {
            val want = expected(BLOCKS[(y / 8) * 4 + x / 8])
            val got = bitmap[x, y]
            for (shift in intArrayOf(16, 8, 0)) {
                val d = abs(((want shr shift) and 0xFF) - ((got shr shift) and 0xFF))
                assertTrue(d <= tolerance, "$name at ($x, $y): ${got.toUInt().toString(16)}, wanted ${want.toUInt().toString(16)}")
            }
        }
    }

    @Test
    fun adobeCmykMultipliesInK() = assertBlocks(ImageKodec.decode(JpegCmykFixtures.blocksAdobeCmyk), 1, "transform 0")

    @Test
    fun aFileWithoutAnAdobeMarkerIsCmyk() {
        val bare = ImageKodec.decode(JpegCmykFixtures.blocksBare)
        assertBlocks(bare, 1, "no marker")
        // libjpeg wrote the same scans into both files, so the pixels agree exactly.
        assertContentEquals(ImageKodec.decode(JpegCmykFixtures.blocksAdobeCmyk).argb, bare.argb)
    }

    @Test
    fun theSplitKeepsItsContrastWithoutAnAdobeMarker() {
        // #65: K was dropped, so both halves came out the same gray.
        val bare = ImageKodec.decode(JpegCmykFixtures.splitBare)
        assertContentEquals(ImageKodec.decode(JpegCmykFixtures.splitAdobeCmyk).argb, bare.argb)
        for (y in 0 until 16) {
            for (x in 0 until 6) assertTrue((bare[x, y] and 0xFF) <= 4, "left half at ($x, $y): ${bare[x, y].toUInt().toString(16)}")
            for (x in 10 until 16) assertTrue(abs((bare[x, y] and 0xFF) - 128) <= 4, "right half at ($x, $y): ${bare[x, y].toUInt().toString(16)}")
        }
    }

    @Test
    fun adobeYcckConvertsToTheSameInk() = assertBlocks(ImageKodec.decode(JpegCmykFixtures.blocksAdobeYcck), 3, "transform 2")

    @Test
    fun anyOtherAdobeTransformIsYcck() {
        val ycck = JpegCmykFixtures.blocksAdobeYcck
        val transform = indexOfAdobeMarker(ycck) + 11
        assertEquals(2, ycck[transform].toInt())
        val reference = ImageKodec.decode(ycck).argb
        for (value in intArrayOf(1, 3, 255)) {
            val patched = ycck.copyOf().also { it[transform] = value.toByte() }
            assertContentEquals(reference, ImageKodec.decode(patched).argb, "transform $value")
        }
    }

    private fun indexOfAdobeMarker(bytes: ByteArray): Int {
        val tag = "Adobe".encodeToByteArray()
        for (i in 0..bytes.size - tag.size) {
            if (tag.indices.all { bytes[i + it] == tag[it] }) return i
        }
        error("no Adobe marker")
    }
}
