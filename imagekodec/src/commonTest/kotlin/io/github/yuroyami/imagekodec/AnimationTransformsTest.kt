package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AnimationTransformsTest {
    private fun check(width: Int, height: Int, order: IntArray, transform: (KiteAnimation) -> KiteAnimation) {
        val frames = listOf(
            KiteFrame(KiteBitmap(3, 2, IntArray(6) { 0xff000001.toInt() + it }), 100, 10),
            KiteFrame(KiteBitmap(3, 2, IntArray(6) { 0xff00000b.toInt() + it }), 250, 25),
        )
        val output = transform(KiteAnimation(3, 2, frames, 4294967295L))
        assertEquals(width to height, output.width to output.height)
        assertEquals(4294967295L, output.loopCount)
        assertEquals(listOf(100, 250), output.frames.map { it.delayMillis })
        assertEquals(listOf(10, 25), output.frames.map { it.delayRawCentiseconds })
        for (i in output.frames.indices) {
            assertEquals(width to height, output.frames[i].bitmap.width to output.frames[i].bitmap.height)
            assertContentEquals(IntArray(6) { 0xff000000.toInt() + i * 10 + order[it] }, output.frames[i].bitmap.argb)
        }
        assertContentEquals(IntArray(6) { 0xff000001.toInt() + it }, frames.first().bitmap.argb)
    }

    // The source rows are ABC / DEF; explicit destination rows distinguish
    // every reflection, including the two operations that swap the canvas axes.
    @Test
    fun horizontalFlipProducesCbaFed() = check(3, 2, intArrayOf(3, 2, 1, 6, 5, 4)) { it.flippedHorizontal() }

    @Test
    fun verticalFlipProducesDefAbc() = check(3, 2, intArrayOf(4, 5, 6, 1, 2, 3)) { it.flippedVertical() }

    @Test
    fun transposeProducesAdBeCf() = check(2, 3, intArrayOf(1, 4, 2, 5, 3, 6)) { it.transposed() }

    @Test
    fun transverseProducesFcEbDa() = check(2, 3, intArrayOf(6, 3, 5, 2, 4, 1)) { it.transversed() }
}
