package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Checks on the public value types that use sizes a caller supplies. */
class PublicTypesTest {

    @Test
    fun aBitmapWhoseSidesOverflowAnIntIsRejected() {
        // 65536 * 65536 wraps to 0 in an Int, so an empty array used to pass the check.
        assertFailsWith<IllegalArgumentException> { KiteBitmap(65536, 65536, IntArray(0)) }
        // 65537 * 65537 wraps to 131073, so an array of that size passed too.
        assertFailsWith<IllegalArgumentException> { KiteBitmap(65537, 65537, IntArray(131073)) }
    }

    @Test
    fun theDurationOfALongAnimationSaturatesInsteadOfWrappingNegative() {
        val bitmap = KiteBitmap(1, 1, IntArray(1))
        // A GIF can state 655.35 s for a frame, so 4000 frames pass Int.MAX_VALUE milliseconds.
        val long = KiteAnimation(1, 1, List(4000) { KiteFrame(bitmap, 655_350, 65_535) }, loopCount = 0)
        assertEquals(Int.MAX_VALUE, long.durationMillis)

        val short = KiteAnimation(1, 1, List(3) { KiteFrame(bitmap, 100, 10) }, loopCount = 0)
        assertEquals(300, short.durationMillis)
    }
}
