package io.github.yuroyami.imagekodec

import kotlin.random.Random

/**
 * The picture and the cjxl settings that the JPEG XL benchmarks measure on the JVM and on
 * linuxX64 (#111). Both write the same files, since `Random` gives the same numbers on every target.
 */
internal object JxlBenchmarkPicture {
    const val WIDTH = 3000
    const val HEIGHT = 2000

    /** The name of each file and the options cjxl writes it with. */
    val cases = listOf("lossy, -d 1" to listOf("-d", "1", "-e", "3"), "lossless, -d 0" to listOf("-d", "0", "-e", "1"))

    /** A photo-like picture: gradients, edges and noise, so neither coder has an easy time. */
    fun picture(): KiteBitmap {
        val random = Random(111)
        return KiteBitmap(WIDTH, HEIGHT, IntArray(WIDTH * HEIGHT) {
            val x = it % WIDTH
            val y = it / WIDTH
            val edge = if ((x / 97 + y / 61) % 3 == 0) 60 else 0
            val r = (x * 255 / WIDTH + edge + random.nextInt(20)).coerceIn(0, 255)
            val g = (y * 255 / HEIGHT + random.nextInt(20)).coerceIn(0, 255)
            val b = ((x + y) * 128 / (WIDTH + HEIGHT) + edge + random.nextInt(20)).coerceIn(0, 255)
            0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        })
    }
}
