package io.github.yuroyami.imagekodec.compose

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import io.github.yuroyami.imagekodec.KiteBitmap
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Android path of [toImageBitmap], through `Bitmap.createBitmap` (#90), keeps the fidelity
 * contract of the Skiko path in ToImageBitmapTest: opaque pixels exact, semi-transparent ones
 * within one level a channel after Android's premultiplied store, and alpha always exact.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29, 35])
class ToImageBitmapAndroidTest {

    private fun assertSame(expected: Int, actual: Int, at: String) {
        val alpha = expected ushr 24
        assertEquals(alpha, actual ushr 24, "$at alpha")
        when (alpha) {
            0 -> {}
            0xFF -> assertEquals(expected, actual, "$at: expected ${expected.toUInt().toString(16)}, got ${actual.toUInt().toString(16)}")
            else -> for (shift in intArrayOf(16, 8, 0)) {
                val e = (expected ushr shift) and 0xFF
                val a = (actual ushr shift) and 0xFF
                assertTrue(kotlin.math.abs(e - a) <= 1, "$at channel $shift: $e became $a")
            }
        }
    }

    @Test
    fun converts_argb_with_the_same_fidelity_as_the_skiko_path() {
        val px = intArrayOf(
            0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0x800000FF.toInt(),
            0x00FFFFFF, 0x80C86432.toInt(), 0xFF123456.toInt(),
        )
        val image = KiteBitmap(3, 2, px).toImageBitmap()
        assertEquals(3, image.width)
        assertEquals(2, image.height)
        val bitmap = image.asAndroidBitmap()
        for (y in 0 until 2) for (x in 0 until 3) assertSame(px[y * 3 + x], bitmap.getPixel(x, y), "($x,$y)")
    }

    @Test
    fun rows_keep_their_order_and_the_common_pixel_map_agrees() {
        val random = Random(90)
        val w = 17
        val h = 9
        val px = IntArray(w * h) { 0xFF000000.toInt() or random.nextInt(0x1000000) }
        val image = KiteBitmap(w, h, px).toImageBitmap()
        val bitmap = image.asAndroidBitmap()
        val map = image.toPixelMap()
        for (y in 0 until h) for (x in 0 until w) {
            assertEquals(px[y * w + x], bitmap.getPixel(x, y), "($x,$y)")
            val c = map[x, y]
            val packed = (0xFF shl 24) or ((c.red * 255 + 0.5f).toInt() shl 16) or ((c.green * 255 + 0.5f).toInt() shl 8) or (c.blue * 255 + 0.5f).toInt()
            assertEquals(px[y * w + x], packed, "pixel map ($x,$y)")
        }
    }
}
