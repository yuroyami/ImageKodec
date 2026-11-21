package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.CcittFax
import io.github.yuroyami.imagekodec.codec.CcittOptions
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The uncompressed mode of T.4 4.2.3 and T.6 (#189), which TIFF allows with T4Options bit 1. No
 * installed tool writes or reads it (libtiff and ffmpeg both stop at it), so the streams are written
 * here from T.4 Table 5 and the short run codes of Table 2, and each must decode to the page it was
 * written from.
 */
class CcittUncompressedTest {

    private val width = 23
    private val eol = "000000000001"

    // T.4 Table 2: the terminating codes the rows below use.
    private val white = mapOf(2 to "0111", 3 to "1000", 4 to "1011")
    private val black = mapOf(2 to "11", 3 to "10")

    /** [pixels] in the uncompressed mode, ending with the exit code whose T bit is [next]. */
    private fun uncompressed(pixels: List<Int>, next: Int): String {
        val out = StringBuilder()
        var whites = 0
        for (p in pixels) {
            if (p == 0) {
                whites++
                if (whites == 5) { out.append("000001"); whites = 0 }
            } else {
                out.append("0".repeat(whites)).append('1')
                whites = 0
            }
        }
        return out.append("0".repeat(6 + whites)).append('1').append(next).toString()
    }

    private fun page(seed: Int): List<List<Int>> {
        val random = Random(seed)
        val first = List(width) { if (random.nextInt(3) == 0) 1 else 0 }
        // Row 2 ends in three white pixels and two black ones, which ordinary codes give.
        val third = List(18) { random.nextInt(2) } + listOf(0, 0, 0, 1, 1)
        // Row 3 ends in three black and two white pixels after an exit to black.
        val fourth = List(18) { if (it % 7 == 0) 1 else 0 } + listOf(1, 1, 1, 0, 0)
        return listOf(first, first, third, fourth)
    }

    private fun changes(row: List<Int>): Int = row.indices.count { (if (it == 0) 0 else row[it - 1]) != row[it] }

    /** The rows of [page] in two-dimensional coding (T.6), each starting from the row above. */
    private fun twoDimensional(page: List<List<Int>>): List<String> = listOf(
        "0000001111" + uncompressed(page[0], 0),
        // The row above again: one V0 for each of its changing elements, and one to reach the end.
        "1".repeat(changes(page[1]) + 1),
        // An exit to white, then horizontal mode: white 3 and black 2.
        "0000001111" + uncompressed(page[2].take(18), 0) + "001" + white.getValue(3) + black.getValue(2),
        // An exit to black, then horizontal mode from black: black 3 and white 2.
        "0000001111" + uncompressed(page[3].take(18), 1) + "001" + black.getValue(3) + white.getValue(2),
    )

    /** The rows of [page] in one-dimensional coding, each wholly or partly uncompressed. */
    private fun oneDimensional(page: List<List<Int>>): List<String> = listOf(
        "000000001111" + uncompressed(page[0], 0),
        "000000001111" + uncompressed(page[1].take(20), 1) + black.getValue(3),
        "000000001111" + uncompressed(page[2].take(18), 0) + white.getValue(3) + black.getValue(2),
        "000000001111" + uncompressed(page[3].take(18), 1) + black.getValue(3) + white.getValue(2),
    )

    private fun packed(page: List<List<Int>>): ByteArray {
        val bytes = (width + 7) / 8
        val out = ByteArray(bytes * page.size)
        for ((y, row) in page.withIndex()) for ((x, p) in row.withIndex()) {
            if (p == 1) out[y * bytes + x / 8] = (out[y * bytes + x / 8].toInt() or (0x80 ushr (x % 8))).toByte()
        }
        return out
    }

    /** A Group 4 TIFF with uncompressed rows, for `FuzzTest`. */
    internal fun seed(): ByteArray = faxTiff(faxBits(twoDimensional(page(3)).joinToString("")), 4, width = width, height = 4)

    private fun options(rows: Int, endOfLine: Boolean = false) = CcittOptions(width, rows, false, true, false, endOfLine)

    @Test
    fun everyCodingDecodesItsUncompressedRows() {
        for (seed in 1..20) {
            val page = page(seed).let { if (seed % 2 == 0) it else listOf(it[0], it[1], it[2], it[3]) }
            // The one-dimensional page has row 1 end in three black pixels.
            val pageOneD = listOf(page[0], page[1].take(20) + listOf(1, 1, 1), page[2], page[3])
            val expected = packed(page)
            assertContentEquals(expected, CcittFax.decode(faxBits(twoDimensional(page).joinToString("")), -1, options(4)), "G4 $seed")
            assertContentEquals(packed(pageOneD), CcittFax.decode(faxBits(oneDimensional(pageOneD).joinToString("")), 0, options(4)), "1D $seed")
            // Mixed coding: a tag bit after each EOL, 1 for a one-dimensional row and 0 for a two-dimensional one.
            val mixed = listOf(
                eol + "1" + oneDimensional(page)[0], eol + "0" + twoDimensional(page)[1],
                eol + "0" + twoDimensional(page)[2], eol + "1" + oneDimensional(page)[3],
            ).joinToString("")
            assertContentEquals(expected, CcittFax.decode(faxBits(mixed), 2, options(4, endOfLine = true)), "mixed $seed")

            // In a TIFF, with T4Options bit 1 for Group 3, and as Group 4.
            val tiff = faxTiff(faxBits(mixed), 3, photometric = 0, width = width, height = 4, t4Options = 3)
            val g4 = faxTiff(faxBits(twoDimensional(page).joinToString("")), 4, photometric = 0, width = width, height = 4)
            for (bytes in listOf(tiff, g4)) {
                assertTrue(ImageKodec.probe(bytes).isDecodable)
                val image = ImageKodec.decode(bytes)
                for (y in 0 until 4) for (x in 0 until width) {
                    assertTrue(image[x, y] == gray(if (page[y][x] == 1) 0 else 255), "TIFF $seed ($x, $y)")
                }
            }
        }
    }

    @Test
    fun damagedUncompressedModeIsADecodeError() {
        val page = page(7)
        val row = twoDimensional(page)[0]
        // Cut inside the mode, an image pattern of eleven zeros, and pixels past the row.
        for (bits in listOf(
            row.substring(0, 20),
            "0000001111" + "00000000000" + "1",
            "0000001111" + "1".repeat(width + 1) + "00000010",
            "000000001111" + "000000000001",
        )) {
            assertFailsWith<ImageDecodeException>(bits) { CcittFax.decode(faxBits(bits), if (bits.startsWith("00000000")) 0 else -1, options(1)) }
        }
    }
}
