package io.github.yuroyami.kiteimage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ScalingTest {

    @Test
    fun integerRatioAveragesExactly() {
        // 4x4 → 2x2: each dest pixel = mean of its 2x2 block.
        val px = IntArray(16) { i -> argb(0xFF, i * 16, 255 - i * 16, i * 8) }
        val bm = KiteBitmap(4, 4, px).scaled(2, 2)
        assertEquals(2, bm.width)
        assertEquals(2, bm.height)
        // top-left block = indices 0,1,4,5
        val ids = intArrayOf(0, 1, 4, 5)
        val r = (ids.sumOf { it * 16 } + 2) / 4
        val g = (ids.sumOf { 255 - it * 16 } + 2) / 4
        val b = (ids.sumOf { it * 8 } + 2) / 4
        assertEquals(argb(0xFF, r, g, b), bm[0, 0])
    }

    @Test
    fun aspectRatioKeptAndFloored() {
        val bm = KiteBitmap(100, 50, IntArray(5000) { 0xFF000000.toInt() }).scaled(10, 10)
        assertEquals(10, bm.width)
        assertEquals(5, bm.height)
    }

    @Test
    fun neverUpscalesReturnsSameInstance() {
        val src = KiteBitmap(3, 3, IntArray(9) { 0xFF102030.toInt() })
        assertSame(src, src.scaled(100, 100))
    }

    @Test
    fun alphaAverages() {
        val px = intArrayOf(
            argb(0, 100, 100, 100), argb(0xFF, 100, 100, 100),
            argb(0, 100, 100, 100), argb(0xFF, 100, 100, 100),
        )
        val bm = KiteBitmap(2, 2, px).scaled(1, 1)
        assertEquals(argb(128, 100, 100, 100), bm[0, 0])
    }

    @Test
    fun tinyTargetNeverZero() {
        val bm = KiteBitmap(1000, 10, IntArray(10000) { 0xFF000000.toInt() }).scaled(5, 5)
        assertEquals(5, bm.width)
        assertEquals(1, bm.height)   // floor would give 0; clamped to 1
    }

    @Test
    fun transparentPixelsDontBleedColorIntoBins() {
        // Two fully transparent BLACK pixels + two opaque WHITE ones. Unweighted
        // averaging would darken the result to gray; alpha-weighted averaging
        // must keep the visible color pure white (the halo fix).
        val px = intArrayOf(
            argb(0, 0, 0, 0), argb(0xFF, 255, 255, 255),
            argb(0, 0, 0, 0), argb(0xFF, 255, 255, 255),
        )
        val bm = KiteBitmap(2, 2, px).scaled(1, 1)
        assertEquals(argb(128, 255, 255, 255), bm[0, 0])
    }

    @Test
    fun fullyTransparentBinStaysFullyTransparent() {
        val px = IntArray(4) { argb(0, 200, 100, 50) }
        val bm = KiteBitmap(2, 2, px).scaled(1, 1)
        assertEquals(0, bm[0, 0])
    }

    @Test
    fun animationScalingPreservesTimingAndLoops() {
        val frames = listOf(
            KiteFrame(KiteBitmap(4, 4, IntArray(16) { 0xFFFF0000.toInt() }), delayMillis = 100, delayRawCentiseconds = 10),
            KiteFrame(KiteBitmap(4, 4, IntArray(16) { 0xFF0000FF.toInt() }), delayMillis = 250, delayRawCentiseconds = 25),
        )
        val anim = KiteAnimation(4, 4, frames, loopCount = 3).scaled(2, 2)
        assertEquals(2, anim.width)
        assertEquals(2, anim.height)
        assertEquals(2, anim.frames.size)
        assertEquals(3, anim.loopCount)
        assertEquals(100, anim.frames[0].delayMillis)
        assertEquals(25, anim.frames[1].delayRawCentiseconds)
        assertEquals(0xFFFF0000.toInt(), anim.frames[0].bitmap[0, 0])
        assertEquals(0xFF0000FF.toInt(), anim.frames[1].bitmap[1, 1])
        // Already fits: same instance, no copy.
        assertSame(anim, anim.scaled(10, 10))
    }
}
