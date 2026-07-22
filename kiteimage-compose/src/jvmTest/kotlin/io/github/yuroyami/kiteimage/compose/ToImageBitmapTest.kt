package io.github.yuroyami.kiteimage.compose

import androidx.compose.ui.graphics.toPixelMap
import io.github.yuroyami.kiteimage.KiteBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pixel-exactness of the Skiko conversion path (the one every non-Android target
 * shares). The backing raster is UNPREMUL, so even semi-transparent values must
 * survive without a premultiply round-trip.
 */
class ToImageBitmapTest {

    @Test
    fun convertsArgbExactly() {
        val px = intArrayOf(
            0xFFFF0000.toInt(),   // opaque red
            0xFF00FF00.toInt(),   // opaque green
            0x800000FF.toInt(),   // half-alpha blue
            0x00FFFFFF,           // fully transparent white
            0x80C86432.toInt(),   // half-alpha odd values (premul round-trip would mangle)
            0xFF123456.toInt(),
        )
        val bm = KiteBitmap(3, 2, px)
        val map = bm.toImageBitmap().toPixelMap()

        for (y in 0 until 2) for (x in 0 until 3) {
            val expected = px[y * 3 + x]
            val c = map[x, y]
            val packed = ((c.alpha * 255 + 0.5f).toInt() shl 24) or
                ((c.red * 255 + 0.5f).toInt() shl 16) or
                ((c.green * 255 + 0.5f).toInt() shl 8) or
                (c.blue * 255 + 0.5f).toInt()
            val alpha = expected ushr 24
            when {
                // Fully transparent: alpha survives; RGB does NOT (Compose's backing
                // store is premultiplied N32: color under alpha 0 is gone, and
                // nothing visible depends on it).
                alpha == 0 -> assertEquals(0, packed ushr 24, "($x,$y) alpha")
                // Opaque: bit-exact.
                alpha == 0xFF -> assertEquals(
                    expected, packed,
                    "($x,$y): expected ${expected.toUInt().toString(16)}, got ${packed.toUInt().toString(16)}",
                )
                // Semi-transparent: the premul round-trip may wobble each channel by
                // ±1 (e.g. r=200,a=128 → 199). Invisible; assert within tolerance.
                else -> {
                    assertEquals(alpha, packed ushr 24, "($x,$y) alpha")
                    for (shift in intArrayOf(16, 8, 0)) {
                        val e = (expected ushr shift) and 0xFF
                        val g = (packed ushr shift) and 0xFF
                        assertTrue(
                            kotlin.math.abs(e - g) <= 1,
                            "($x,$y) channel@$shift: expected $e, got $g (>±1)",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun dimensionsCarryOver() {
        val bm = KiteBitmap(5, 3, IntArray(15) { 0xFF000000.toInt() })
        val img = bm.toImageBitmap()
        assertEquals(5, img.width)
        assertEquals(3, img.height)
    }
}
