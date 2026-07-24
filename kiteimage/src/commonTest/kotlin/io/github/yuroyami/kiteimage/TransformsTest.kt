package io.github.yuroyami.kiteimage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Transforms are pure index remaps, so every expectation here is exact. Pixels
 * encode their own source coordinate (`0xFF00XXYY`), which makes a wrong
 * remap point straight at the offending mapping instead of "some colours moved".
 */
class TransformsTest {

    /** width×height bitmap whose pixel at (x, y) is 0xFF00_XXYY. */
    private fun coords(width: Int, height: Int): KiteBitmap =
        KiteBitmap(width, height, IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            (0xFF shl 24) or (x shl 8) or y
        })

    private fun px(x: Int, y: Int): Int = (0xFF shl 24) or (x shl 8) or y

    @Test
    fun rotate90MapsCornersClockwise() {
        val src = coords(3, 2)          // 3 wide, 2 tall
        val out = src.rotated90()
        assertEquals(2, out.width)
        assertEquals(3, out.height)
        // Top-left of the source becomes top-right of the result.
        assertEquals(px(0, 0), out[out.width - 1, 0])
        // dst(x, y) == src(y, height - 1 - x)
        for (y in 0 until out.height) {
            for (x in 0 until out.width) {
                assertEquals(px(y, src.height - 1 - x), out[x, y], "rotate90 at ($x, $y)")
            }
        }
    }

    @Test
    fun rotate270IsTheInverseOf90() {
        val src = coords(5, 3)
        val there = src.rotated90().rotated270()
        assertEquals(src.width, there.width)
        assertEquals(src.height, there.height)
        for (y in 0 until src.height) for (x in 0 until src.width) {
            assertEquals(src[x, y], there[x, y], "roundtrip at ($x, $y)")
        }
    }

    @Test
    fun fourQuarterTurnsAreIdentity() {
        val src = coords(4, 7)
        val out = src.rotated90().rotated90().rotated90().rotated90()
        assertEquals(src.width, out.width)
        assertEquals(src.height, out.height)
        for (i in src.argb.indices) assertEquals(src.argb[i], out.argb[i], "at $i")
    }

    @Test
    fun rotate180EqualsTwoQuarterTurns() {
        val src = coords(6, 4)
        val a = src.rotated180()
        val b = src.rotated90().rotated90()
        for (i in a.argb.indices) assertEquals(b.argb[i], a.argb[i], "at $i")
    }

    @Test
    fun flipsMirrorTheRightAxis() {
        val src = coords(4, 3)
        val h = src.flippedHorizontal()
        val v = src.flippedVertical()
        for (y in 0 until 3) for (x in 0 until 4) {
            assertEquals(px(3 - x, y), h[x, y], "flipH at ($x, $y)")
            assertEquals(px(x, 2 - y), v[x, y], "flipV at ($x, $y)")
        }
    }

    @Test
    fun flipsAreSelfInverse() {
        val src = coords(5, 5)
        for (i in src.argb.indices) {
            assertEquals(src.argb[i], src.flippedHorizontal().flippedHorizontal().argb[i])
            assertEquals(src.argb[i], src.flippedVertical().flippedVertical().argb[i])
        }
    }

    @Test
    fun transposeSwapsRowsAndColumns() {
        val src = coords(3, 5)
        val out = src.transposed()
        assertEquals(5, out.width)
        assertEquals(3, out.height)
        for (y in 0 until out.height) for (x in 0 until out.width) {
            assertEquals(px(y, x), out[x, y], "transpose at ($x, $y)")
        }
    }

    @Test
    fun transverseIsTransposeRotatedHalfTurn() {
        val src = coords(4, 6)
        val a = src.transversed()
        val b = src.transposed().rotated180()
        assertEquals(b.width, a.width)
        assertEquals(b.height, a.height)
        for (i in a.argb.indices) assertEquals(b.argb[i], a.argb[i], "at $i")
    }

    @Test
    fun everyExifOrientationHasTheExpectedShape() {
        val src = coords(4, 2)
        for (o in Orientation.entries) {
            val out = src.oriented(o)
            val expectW = if (o.swapsAxes) src.height else src.width
            val expectH = if (o.swapsAxes) src.width else src.height
            assertEquals(expectW, out.width, "$o width")
            assertEquals(expectH, out.height, "$o height")
        }
    }

    @Test
    fun normalOrientationDoesNotCopy() {
        val src = coords(2, 2)
        assertSame(src, src.oriented(Orientation.Normal))
    }

    @Test
    fun orientationRoundTripsThroughItsExifValue() {
        for (o in Orientation.entries) {
            assertEquals(o, Orientation.fromExif(o.exifValue))
        }
        assertEquals(Orientation.Normal, Orientation.fromExif(0))
        assertEquals(Orientation.Normal, Orientation.fromExif(9))
        assertEquals(Orientation.Normal, Orientation.fromExif(-1))
    }

    @Test
    fun cropTakesTheRequestedWindow() {
        val src = coords(6, 5)
        val out = src.cropped(2, 1, 3, 2)
        assertEquals(3, out.width)
        assertEquals(2, out.height)
        for (y in 0 until 2) for (x in 0 until 3) {
            assertEquals(px(x + 2, y + 1), out[x, y], "crop at ($x, $y)")
        }
    }

    @Test
    fun fullSizeCropDoesNotCopy() {
        val src = coords(3, 3)
        assertSame(src, src.cropped(0, 0, 3, 3))
    }

    @Test
    fun cropOutsideBoundsFails() {
        val src = coords(4, 4)
        assertFailsWith<IllegalArgumentException> { src.cropped(-1, 0, 2, 2) }
        assertFailsWith<IllegalArgumentException> { src.cropped(3, 0, 2, 2) }
        assertFailsWith<IllegalArgumentException> { src.cropped(0, 0, 0, 2) }
        assertFailsWith<IllegalArgumentException> { src.cropped(0, 3, 2, 2) }
    }

    @Test
    fun animationTransformsKeepTimingAndCoverEveryFrame() {
        val frames = List(3) { i ->
            KiteFrame(coords(4, 2), delayMillis = 40 + i, delayRawCentiseconds = 4)
        }
        val anim = KiteAnimation(4, 2, frames, loopCount = 0)
        val out = anim.rotated90()

        assertEquals(2, out.width)
        assertEquals(4, out.height)
        assertEquals(0, out.loopCount)
        assertEquals(3, out.frames.size)
        out.frames.forEachIndexed { i, f ->
            assertEquals(40 + i, f.delayMillis, "frame $i delay")
            assertEquals(2, f.bitmap.width)
            assertEquals(4, f.bitmap.height)
        }
        assertTrue(out.isAnimated)
    }

    @Test
    fun animationNormalOrientationIsANoOp() {
        val anim = KiteAnimation(2, 2, listOf(KiteFrame(coords(2, 2), 0, 0)), loopCount = 1)
        assertSame(anim, anim.oriented(Orientation.Normal))
    }
}
