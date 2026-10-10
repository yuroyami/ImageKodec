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
    fun everySourceSampleContributesToItsDestinationBin() {
        for (width in 2..9) for (height in 2..7) {
            val source = KiteBitmap(width, height, IntArray(width * height) { i ->
                argb((i * 61) and 255, (i * 37) and 255, (i * 13) and 255, (i * 79) and 255)
            })
            for (dw in 1..width) for (dh in 1..height) {
                if (dw == width && dh == height) continue
                val sums = Array(dw * dh) { LongArray(5) }
                for (y in 0 until height) for (x in 0 until width) {
                    val bin = sums[(y * dh / height) * dw + x * dw / width]
                    val p = source[x, y]
                    val a = (p ushr 24).toLong()
                    bin[0] += a
                    bin[1] += ((p ushr 16) and 255) * a
                    bin[2] += ((p ushr 8) and 255) * a
                    bin[3] += (p and 255) * a
                    bin[4]++
                }
                val expected = IntArray(dw * dh) { i ->
                    val s = sums[i]
                    if (s[0] == 0L) 0 else argb(
                        ((s[0] + s[4] / 2) / s[4]).toInt(),
                        ((s[1] + s[0] / 2) / s[0]).toInt(),
                        ((s[2] + s[0] / 2) / s[0]).toInt(),
                        ((s[3] + s[0] / 2) / s[0]).toInt(),
                    )
                }
                val actual = source.downscaledTo(dw, dh)
                assertEquals(dw to dh, actual.width to actual.height)
                assertContentEquals(expected, actual.argb, "$width x $height -> $dw x $dh")
            }
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
