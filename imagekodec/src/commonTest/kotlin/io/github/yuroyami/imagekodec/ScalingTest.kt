package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ScalingTest {

    @Test
    fun nonExactRatiosFillTheLimitingSide() {
        val cases = listOf(
            intArrayOf(22, 1, 15, 15, 15, 1),
            intArrayOf(1370, 767, 200, 200, 200, 111),
            intArrayOf(1765, 608, 256, 256, 256, 88),
            intArrayOf(499, 473, 128, 128, 128, 121),
            intArrayOf(841, 499, 128, 128, 128, 75),
        )
        for (case in cases) {
            val w = case[0]; val h = case[1]
            val mw = case[2]; val mh = case[3]
            val dw = case[4]; val dh = case[5]
            for (transpose in listOf(false, true)) {
                val source = KiteBitmap(if (transpose) h else w, if (transpose) w else h, IntArray(w * h))
                val scaled = source.scaled(if (transpose) mh else mw, if (transpose) mw else mh)
                assertEquals(if (transpose) dh else dw, scaled.width)
                assertEquals(if (transpose) dw else dh, scaled.height)
            }
        }
    }

    @Test
    fun longRowsAndColumnsDoNotOverflow() {
        val pixels = IntArray(50000) { gray(it and 255) }
        val row = KiteBitmap(50000, 1, pixels).scaled(49999, 1)
        val column = KiteBitmap(1, 50000, pixels).scaled(1, 49999)
        assertEquals(49999, row.width)
        assertEquals(49999, column.height)
        assertEquals(gray(1), row[0, 0])
        assertEquals(pixels.last(), row[49998, 0])
        assertContentEquals(row.argb, column.argb)
    }

    @Test
    fun destinationBinsMatchIndependentSourceAccumulation() {
        for (w in 2..19) for (h in 2..13) {
            val source = KiteBitmap(w, h, IntArray(w * h) { i -> argb((i * 61) and 255, (i * 37) and 255, (i * 13) and 255, (i * 79) and 255) })
            val output = source.scaled(maxOf(1, w - 3), maxOf(1, h - 2))
            val sums = Array(output.argb.size) { LongArray(5) }
            for (y in 0 until h) for (x in 0 until w) {
                val bin = sums[(y.toLong() * output.height / h).toInt() * output.width + (x.toLong() * output.width / w).toInt()]
                val pixel = source[x, y]
                val alpha = (pixel ushr 24).toLong()
                bin[0] += alpha
                bin[1] += ((pixel ushr 16) and 255) * alpha
                bin[2] += ((pixel ushr 8) and 255) * alpha
                bin[3] += (pixel and 255) * alpha
                bin[4]++
            }
            val expected = IntArray(sums.size) { i ->
                val s = sums[i]
                if (s[0] == 0L) 0 else argb(
                    ((s[0] + s[4] / 2) / s[4]).toInt(),
                    ((s[1] + s[0] / 2) / s[0]).toInt(),
                    ((s[2] + s[0] / 2) / s[0]).toInt(),
                    ((s[3] + s[0] / 2) / s[0]).toInt(),
                )
            }
            assertContentEquals(expected, output.argb, "$w x $h")
        }
    }

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
