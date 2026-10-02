package io.github.yuroyami.imagekodec

/**
 * A bilevel test page for the JBIG2 tests: five glyph shapes, each one connected component,
 * stamped on a grid. Repeated glyphs are pixel-identical, so jbig2enc's symbol coder, which merges
 * look-alike glyphs, merges only exact copies and loses no pixel. It does not keep every glyph in
 * place, though: its text region puts some of them a pixel up or to the left, and jbig2dec decodes
 * the same moved page, so the symbol-mode tests compare against jbig2dec rather than this page.
 *
 * The page is built here rather than stored, so the committed vectors and the jbig2enc oracle in
 * `jvmTest` compare against the same pixels.
 */
internal object Jbig2Page {
    const val WIDTH = 157
    const val HEIGHT = 61

    private const val GLYPH = 11

    /** Glyph [kind] (0..4) covers ([x], [y]) inside its 11 by 11 cell. */
    private fun glyph(kind: Int, x: Int, y: Int): Boolean {
        val c = GLYPH / 2
        return when (kind) {
            0 -> x == 0 || y == 0 || x == GLYPH - 1 || y == GLYPH - 1          // a square ring
            1 -> x == c || y == c                                                // a cross
            2 -> x <= 2 || y >= GLYPH - 3                                        // an L
            3 -> kotlin.math.abs(x - c) + kotlin.math.abs(y - c) <= c            // a diamond
            else -> y == 0 || x == c                                             // a T
        }
    }

    /** True where the page is black, row-major. */
    val black: BooleanArray = BooleanArray(WIDTH * HEIGHT).also { page ->
        var n = 0
        for (row in 0 until 3) for (col in 0 until 9) {
            val kind = (n * 7 + row) % 5
            n++
            val x0 = 4 + col * 17
            val y0 = 4 + row * 19
            for (y in 0 until GLYPH) for (x in 0 until GLYPH) {
                if (glyph(kind, x, y)) page[(y0 + y) * WIDTH + x0 + x] = true
            }
        }
    }

    /** The page as [Jbig2Decoder] returns it: rows packed MSB first, padded to a byte, a set bit white. */
    fun packed(): ByteArray {
        val stride = (WIDTH + 7) / 8
        val out = ByteArray(stride * HEIGHT)
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
            if (!black[y * WIDTH + x]) {
                val i = y * stride + (x shr 3)
                out[i] = (out[i].toInt() or (0x80 ushr (x and 7))).toByte()
            }
        }
        return out
    }

    /** The page as a [KiteBitmap], black on white, for writing a source file. */
    fun bitmap(): KiteBitmap =
        KiteBitmap(WIDTH, HEIGHT, IntArray(WIDTH * HEIGHT) { if (black[it]) 0xFF000000.toInt() else -1 })
}
