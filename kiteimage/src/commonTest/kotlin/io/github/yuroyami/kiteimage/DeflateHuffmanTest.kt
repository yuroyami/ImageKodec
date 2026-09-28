package io.github.yuroyami.kiteimage

import io.github.yuroyami.kiteimage.internal.flate.Deflater
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The deflate encoder limits Huffman code lengths to 15 bits (7 for the code
 * length code). A limit that leaves the code over-subscribed makes stock zlib
 * reject the stream, so these tests check the lengths themselves and then a real
 * round trip through the encoder.
 */
class DeflateHuffmanTest {

    /** Kraft sum in units of 2^-maxLen. A complete prefix code sums to exactly 2^maxLen. */
    private fun kraft(lengths: IntArray, maxLen: Int): Long =
        lengths.filter { it > 0 }.sumOf { 1L shl (maxLen - it) }

    private fun assertLegalCode(freq: IntArray, maxLen: Int, label: String) {
        val lengths = Deflater.codeLengths(freq, maxLen)
        val used = freq.indices.filter { freq[it] > 0 }
        for (i in freq.indices) {
            if (freq[i] == 0) assertEquals(0, lengths[i], "$label: unused symbol $i has a code")
            else assertTrue(lengths[i] in 1..maxLen, "$label: symbol $i has length ${lengths[i]}, limit $maxLen")
        }
        if (used.size >= 2) {
            assertEquals(1L shl maxLen, kraft(lengths, maxLen), "$label: code is not complete")
        }
        // The least frequent symbols take the longest codes.
        for (a in used) for (b in used) {
            if (freq[a] > freq[b]) assertTrue(lengths[a] <= lengths[b], "$label: symbol $a is more frequent than $b but longer")
        }
    }

    @Test
    fun fibonacciWeightsBuildTheDeepestTree() {
        // Fibonacci weights force a tree as deep as it can get for the symbol count.
        for (maxLen in intArrayOf(7, 15)) {
            for (n in 3..30) {
                val freq = IntArray(n)
                var a = 1
                var b = 1
                for (i in 0 until n) {
                    freq[i] = a
                    val next = a + b
                    a = b
                    b = next
                }
                assertLegalCode(freq, maxLen, "fibonacci n=$n limit=$maxLen")
            }
        }
    }

    @Test
    fun skewedRandomWeightsStayLegal() {
        val random = Random(20260928)
        // The 7-bit limit belongs to the 19-symbol code length alphabet, the 15-bit
        // limit to the literal alphabet. A larger alphabet cannot fit the 7-bit limit.
        repeat(400) { trial ->
            for ((maxLen, alphabet) in listOf(7 to 19, 15 to 286)) {
                val used = random.nextInt(2, alphabet + 1)
                val ratio = 1.15 + random.nextDouble()
                val freq = IntArray(alphabet)
                var weight = 1.0
                for (symbol in (0 until alphabet).shuffled(random).take(used)) {
                    freq[symbol] = weight.toInt().coerceAtLeast(1)
                    weight = minOf(weight * ratio, 1.0e6)
                }
                assertLegalCode(freq, maxLen, "random trial $trial limit=$maxLen")
            }
        }
    }

    @Test
    fun aDetailedImageSurvivesAPngRoundTrip() {
        // A gradient with light noise has a wide residual histogram, which is what
        // pushed the literal tree past the limit. 448 by 448 was the smallest failure.
        val w = 448
        val h = 448
        // xorshift32 in plain Int arithmetic, so every target draws the same noise.
        var state = 1
        fun noise(): Int {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            return state and 7
        }
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val r = (x * 255 / w + noise()) and 0xFF
            val g = (y * 255 / h + noise()) and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or ((x xor y) and 0xFF)
        }
        val decoded = KiteImage.decode(KiteImage.encodePng(KiteBitmap(w, h, px)))
        assertEquals(w, decoded.width)
        assertEquals(h, decoded.height)
        for (i in px.indices) assertEquals(px[i], decoded.argb[i], "pixel $i")
    }
}
