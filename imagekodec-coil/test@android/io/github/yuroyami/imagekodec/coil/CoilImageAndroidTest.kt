package io.github.yuroyami.imagekodec.coil

import coil3.BitmapImage
import io.github.yuroyami.imagekodec.KiteBitmap
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Android path of the Coil conversion (#90): a decoded bitmap becomes a Coil image of the
 * same size and pixels, opaque ones exact and semi-transparent ones within one level, and keeps
 * the shareable flag Coil caches by.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29, 35])
class CoilImageAndroidTest {

    @Test
    fun a_bitmap_becomes_a_coil_image_of_the_same_pixels() {
        val px = intArrayOf(0xFFFF0000.toInt(), 0x80C86432.toInt(), 0xFF123456.toInt(), 0x00FFFFFF)
        for (shareable in listOf(true, false)) {
            val image = assertIs<BitmapImage>(KiteBitmap(2, 2, px).toCoilImage(shareable))
            assertEquals(shareable, image.shareable)
            assertEquals(2, image.width)
            assertEquals(2, image.height)
            val bitmap = image.bitmap
            for (i in px.indices) {
                val expected = px[i]
                val actual = bitmap.getPixel(i % 2, i / 2)
                assertEquals(expected ushr 24, actual ushr 24, "pixel $i alpha")
                when (expected ushr 24) {
                    0 -> {}
                    0xFF -> assertEquals(expected, actual, "pixel $i")
                    else -> for (shift in intArrayOf(16, 8, 0)) {
                        assertTrue(abs(((expected ushr shift) and 0xFF) - ((actual ushr shift) and 0xFF)) <= 1, "pixel $i channel $shift")
                    }
                }
            }
        }
    }
}
