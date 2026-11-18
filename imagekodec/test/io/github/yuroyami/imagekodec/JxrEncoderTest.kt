package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class JxrEncoderTest {

    private fun scene(w: Int, h: Int, seed: Int, alpha: Boolean = false, gray: Boolean = false): KiteBitmap {
        var s = seed
        fun rnd(): Int {
            s = s * 1103515245 + 12345
            return (s ushr 16) and 0xFF
        }
        val argb = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val noise = if (x in w / 4 until w / 2) rnd() else 0
            val r = (x * 7 + y * 3 + noise) and 0xFF
            val g = if (gray) r else (x * 2 + y * 9 + noise / 2) and 0xFF
            val b = if (gray) r else (255 - x * 5 + y) and 0xFF
            val a = if (alpha) (x * 11 + y * 13) and 0xFF else 0xFF
            (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return KiteBitmap(w, h, argb)
    }

    @Test
    fun losslessRoundTrips() {
        for ((w, h) in listOf(1 to 1, 16 to 16, 37 to 21, 1 to 40, 40 to 1, 70 to 50)) {
            for (alpha in listOf(false, true)) for (gray in listOf(false, true)) {
                val src = scene(w, h, w * 31 + h, alpha, gray)
                for (overlap in 0..2) {
                    val jxr = ImageKodec.encodeJxr(src, JxrEncodeOptions(lossless = true, overlap = overlap, planarAlpha = overlap != 1))
                    val back = ImageKodec.decode(jxr)
                    assertEquals(w, back.width)
                    assertContentEquals(src.argb, back.argb, "$w x $h alpha $alpha gray $gray overlap $overlap")
                }
            }
        }
    }
}
