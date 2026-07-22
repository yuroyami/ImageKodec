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
}
