package io.github.yuroyami.imagekodec

/**
 * CMYK test pictures for the JPEG XR CMYK tests (#189), the same on every target: smooth ramps of
 * each ink, a block of noise, and full and empty ink at the corners, so that the reversible
 * transform meets its extremes.
 */
internal object JxrCmykFixtures {

    /** A generator of [Int]s from [seed], the same on every target. */
    private class Lcg(seed: Int) {
        private var s = seed * 1103515245 + 12345

        fun next(bound: Int): Int {
            s = s * 1103515245 + 12345
            return ((s ushr 8) and 0xFFFF) % bound
        }
    }

    /** One ink sample from 0 to [max] at pixel [x], [y] of [w] by [h], channel [c]. */
    private fun sample(x: Int, y: Int, w: Int, h: Int, c: Int, max: Int, rng: Lcg): Int {
        if (x == 0 && y == 0) return max
        if (x == w - 1 && y == h - 1) return 0
        if (x in w / 4 until w / 2 && y in h / 4 until h / 2) return rng.next(max + 1)
        val t = when (c) {
            0 -> x * max / maxOf(1, w - 1)
            1 -> y * max / maxOf(1, h - 1)
            2 -> (x + y) * max / maxOf(1, w + h - 2)
            else -> if ((x / 4 + y / 4) % 2 == 0) max / 5 else max - max / 7
        }
        return t
    }

    /** 8-bit ink, four samples a pixel, and an alpha ramp a pixel when [alpha] (else an empty array). */
    fun ink(w: Int, h: Int, seed: Int, alpha: Boolean): Pair<ByteArray, ByteArray> {
        val rng = Lcg(seed)
        val ink = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until 4) {
            ink[(y * w + x) * 4 + c] = sample(x, y, w, h, c, 255, rng).toByte()
        }
        val a = if (alpha) ByteArray(w * h) { i -> (if (i % 7 == 0) 0 else 255 - (i * 3) % 256).toByte() } else ByteArray(0)
        return ink to a
    }

    /** 16-bit ink, four samples a pixel, from 0 to 65535. */
    fun ink16(w: Int, h: Int, seed: Int): IntArray {
        val rng = Lcg(seed + 100)
        val ink = IntArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until 4) {
            ink[(y * w + x) * 4 + c] = sample(x, y, w, h, c, 65535, rng)
        }
        return ink
    }
}
