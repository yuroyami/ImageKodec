package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ExactScalingTest {
    @Test
    fun exactDimensionsDoNotFitTheAspectRatioASecondTime() {
        val source = KiteBitmap(841, 499, IntArray(841 * 499) { 0xff408020.toInt() })
        val output = source.downscaledTo(128, 75)
        assertEquals(128 to 75, output.width to output.height)
        assertEquals(setOf(0xff408020.toInt()), output.argb.toSet())
    }

    @Test
    fun everySourceSampleCountsByThePartOfItInsideTheBin() {
        for (width in 2..9) for (height in 2..7) {
            val source = KiteBitmap(width, height, IntArray(width * height) { i ->
                argb((i * 61) and 255, (i * 37) and 255, (i * 13) and 255, (i * 79) and 255)
            })
            for (dw in 1..width) for (dh in 1..height) {
                if (dw == width && dh == height) continue
                val actual = source.downscaledTo(dw, dh)
                assertEquals(dw to dh, actual.width to actual.height)
                assertContentEquals(areaAverage(source, dw, dh), actual.argb, "$width x $height -> $dw x $dh")
            }
        }
    }

    @Test
    fun aThinLineKeepsTheSameContrastWhereverItFalls() {
        fun gray(v: Int) = argb(255, v, v, v)
        fun red(row: IntArray, dw: Int) = KiteBitmap(row.size, 1, row).downscaledTo(dw, 1).argb.map { (it ushr 16) and 255 }
        // The cases of #122: whole-pixel bins gave the line full contrast at x = 2 and 5, half at 1 and 4.
        assertEquals(listOf(170, 0), red(intArrayOf(gray(255), gray(0), gray(0)), 2))
        assertEquals(listOf(0, 170), red(intArrayOf(gray(0), gray(0), gray(255)), 2))
        val expected = mapOf(1 to listOf(170, 170, 255, 255), 2 to listOf(255, 85, 255, 255), 4 to listOf(255, 255, 170, 170), 5 to listOf(255, 255, 255, 85))
        for ((at, want) in expected) {
            assertEquals(want, red(IntArray(6) { if (it == at) gray(0) else gray(255) }, 4), "line at $at")
        }
    }

    @Test
    fun unchangedSizeReturnsTheSourceAndInvalidSizesFailBeforeAllocation() {
        val source = KiteBitmap(3, 2, IntArray(6))
        val animation = KiteAnimation(3, 2, listOf(KiteFrame(source, 100, 10)), 1)
        assertSame(source, source.downscaledTo(3, 2))
        assertSame(animation, animation.downscaledTo(3, 2))
        for ((w, h) in listOf(0 to 1, 1 to -1, 4 to 1, 1 to 3, Int.MAX_VALUE to Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { source.downscaledTo(w, h) }
            assertFailsWith<IllegalArgumentException> { animation.downscaledTo(w, h) }
        }
    }

    @Test
    fun exactAnimationScalingPreservesAllPlaybackFields() {
        val animation = KiteAnimation(5, 3, listOf(
            KiteFrame(KiteBitmap(5, 3, IntArray(15) { 0xff408020.toInt() }), 100, 10),
            KiteFrame(KiteBitmap(5, 3, IntArray(15) { 0xff0000ff.toInt() }), 250, 25),
        ), 4294967295L)
        val output = animation.downscaledTo(3, 2)
        assertEquals(3 to 2, output.width to output.height)
        assertEquals(4294967295L, output.loopCount)
        assertEquals(listOf(100, 250), output.frames.map { it.delayMillis })
        assertEquals(listOf(10, 25), output.frames.map { it.delayRawCentiseconds })
        for (i in output.frames.indices) {
            assertEquals(3 to 2, output.frames[i].bitmap.width to output.frames[i].bitmap.height)
            assertContentEquals(IntArray(6) { animation.frames[i].bitmap[0, 0] }, output.frames[i].bitmap.argb)
        }
    }
}

/**
 * The exact fractional-area mean, written apart from the filter: the overlap of each source
 * pixel's interval with each bin's, both as fractions of the source, by cross-multiplying.
 */
internal fun areaAverage(source: KiteBitmap, dw: Int, dh: Int): IntArray {
    val sw = source.width; val sh = source.height
    // Overlap of [a/n, (a+1)/n) with [b/m, (b+1)/m), in units of 1/(n*m).
    fun overlap(a: Int, n: Int, b: Int, m: Int): Long =
        maxOf(0L, minOf((a + 1).toLong() * m, (b + 1).toLong() * n) - maxOf(a.toLong() * m, b.toLong() * n))
    return IntArray(dw * dh) { bin ->
        val bx = bin % dw; val by = bin / dw
        var a = 0L; var r = 0L; var g = 0L; var b = 0L
        for (y in 0 until sh) for (x in 0 until sw) {
            val w = overlap(x, sw, bx, dw) * overlap(y, sh, by, dh)
            if (w == 0L) continue
            val p = source[x, y]
            val wa = w * (p ushr 24)
            a += wa; r += wa * ((p ushr 16) and 255); g += wa * ((p ushr 8) and 255); b += wa * (p and 255)
        }
        val total = sw.toLong() * sh
        if (a == 0L) 0 else argb(((a + total / 2) / total).toInt(), ((r + a / 2) / a).toInt(), ((g + a / 2) / a).toInt(), ((b + a / 2) / a).toInt())
    }
}
