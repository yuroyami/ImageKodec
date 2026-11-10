package io.github.yuroyami.imagekodec

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [ImageKodec.ssimulacra2] (#134): 100 for an image against itself, and lower the worse a JPEG gets. */
class Ssimulacra2Test {

    private fun scene(width: Int, height: Int) = KiteBitmap(width, height, IntArray(width * height) {
        val x = it % width
        val y = it / width
        val r = (128 + 100 * sin(x / 9.0)).toInt()
        val g = (128 + 90 * cos(y / 7.0)).toInt()
        val b = (x + y) * 255 / (width + height)
        0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    })

    @Test
    fun an_image_scores_100_against_itself() {
        val image = scene(161, 97)
        assertEquals(100.0, ImageKodec.ssimulacra2(image, image))
        assertEquals(100.0, ImageKodec.ssimulacra2(image, KiteBitmap(161, 97, image.argb.copyOf())))
        // Below 8 pixels no scale runs, as in the reference, and the score is 100.
        val tiny = scene(7, 30)
        assertEquals(100.0, ImageKodec.ssimulacra2(tiny, KiteBitmap(7, 30, IntArray(210))))
    }

    @Test
    fun a_worse_jpeg_scores_lower() {
        val image = scene(203, 131)
        val scores = listOf(97, 85, 60, 25).map { ImageKodec.ssimulacra2(image, ImageKodec.decode(ImageKodec.encodeJpeg(image, it))) }
        for (i in 1 until scores.size) assertTrue(scores[i] < scores[i - 1], "the scores do not fall with the quality: $scores")
        assertTrue(scores[0] in 80.0..100.0 && scores.last() < scores[0] - 10, "$scores")
    }

    /**
     * libjxl 0.11.1's `tools/ssimulacra2_main.cc` blends both images onto 0.1 and onto 0.9 when
     * the original has alpha, and prints the worse score. Without alpha in the original, only the
     * distorted image is blended, onto 0.5. The values are `tools/ssimulacra2_ref.py`'s for these
     * pixels: 11.154 here, where a blend onto 0.1 alone gives 11.15, onto 0.9 alone 19.23 and onto
     * 0.5 25.41, and 23.143 the other way round.
     */
    @Test
    fun alpha_in_the_original_takes_the_worse_of_a_dark_and_a_bright_background() {
        val image = scene(64, 40)
        val veiled = KiteBitmap(64, 40, IntArray(64 * 40) {
            val a = if (it % 64 < 24 && it / 64 < 20) 96 else 255
            (image.argb[it] and 0xFFFFFF) or (a shl 24)
        })
        assertEquals(11.154, ImageKodec.ssimulacra2(veiled, image), 0.1)
        assertEquals(23.143, ImageKodec.ssimulacra2(image, veiled), 0.1)
    }

    @Test
    fun images_of_two_sizes_cannot_be_compared() {
        assertFailsWith<IllegalArgumentException> { ImageKodec.ssimulacra2(scene(20, 20), scene(20, 21)) }
    }
}
