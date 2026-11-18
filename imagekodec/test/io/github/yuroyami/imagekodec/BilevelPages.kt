package io.github.yuroyami.imagekodec

/**
 * Black-and-white pages for the CCITT and JBIG2 encoder tests, built from a fixed seed so that every
 * target sees the same pixels: blank and solid pages, the narrowest and shortest pages, widths on
 * either side of a byte, a fax-width page, noise, and lines of glyph-like marks that stand in for a
 * scanned page of text.
 */
internal object BilevelPages {

    class Page(val name: String, val width: Int, val height: Int, val black: BooleanArray) {
        fun bitmap(): KiteBitmap =
            KiteBitmap(width, height, IntArray(width * height) { if (black[it]) 0xFF000000.toInt() else -1 })

        /** Rows packed MSB first and padded to a byte, a set bit black when [blackIs1], padding clear. */
        fun packed(blackIs1: Boolean): ByteArray {
            val stride = (width + 7) / 8
            val out = ByteArray(stride * height)
            for (y in 0 until height) for (x in 0 until width) {
                if (black[y * width + x] == blackIs1) {
                    val i = y * stride + (x shr 3)
                    out[i] = (out[i].toInt() or (0x80 ushr (x and 7))).toByte()
                }
            }
            return out
        }
    }

    /** A linear congruential generator (Knuth's MMIX constants, top bits), the same on every target. */
    class Random(seed: Long) {
        private var state = seed
        fun next(bound: Int): Int {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 33) % bound).toInt()
        }
    }

    fun page(name: String, width: Int, height: Int, isBlack: (Int, Int) -> Boolean): Page =
        Page(name, width, height, BooleanArray(width * height) { isBlack(it % width, it / width) })

    fun noise(width: Int, height: Int, seed: Long): Page {
        val random = Random(seed)
        return Page("noise ${width}x$height", width, height, BooleanArray(width * height) { random.next(2) == 1 })
    }

    /**
     * Lines of words, each word a run of glyphs drawn from stems, bars, bowls and dots, with the
     * spacing and the long white runs of a page of print.
     */
    fun text(width: Int, height: Int, seed: Long): Page {
        val random = Random(seed)
        val black = BooleanArray(width * height)
        fun set(x: Int, y: Int) { if (x in 0 until width && y in 0 until height) black[y * width + x] = true }
        var baseline = 14
        while (baseline < height - 2) {
            var x = 6 + random.next(10)
            while (x < width - 12) {
                val letters = 1 + random.next(8)
                repeat(letters) {
                    val w = 4 + random.next(5)
                    val h = 7 + random.next(4)
                    val top = baseline - h
                    when (random.next(5)) {
                        0 -> { for (y in top until baseline) { set(x, y); set(x + 1, y) } }
                        1 -> { for (y in top until baseline) set(x, y); for (i in 0 until w) set(x + i, top + h / 2) }
                        2 -> for (y in top + 3 until baseline) for (i in 0 until w) {
                            val dx = 2 * i - (w - 1); val dy = 2 * (y - top - 3) - (h - 4)
                            val r = dx * dx + dy * dy
                            if (r in (w - 2) * (w - 2)..w * w + 4) set(x + i, y)
                        }
                        3 -> { for (i in 0 until w) { set(x + i, top + 3); set(x + i, baseline - 1) }; set(x, top) }
                        else -> { for (y in top + 3 until baseline) set(x + w - 1, y); set(x + w - 1, top) }
                    }
                    x += w + 1 + random.next(2)
                }
                x += 4 + random.next(6)
            }
            baseline += 15 + random.next(4)
        }
        return Page("text ${width}x$height", width, height, black)
    }

    /** The edge pages and a few ordinary ones, small enough to code in every framing on every target. */
    val edges: List<Page> by lazy {
        listOf(
            page("white 64x5", 64, 5) { _, _ -> false },
            page("black 64x5", 64, 5) { _, _ -> true },
            page("one column", 1, 40) { _, y -> y % 3 != 0 },
            page("one row", 300, 1) { x, _ -> (x / 7) % 2 == 0 || x == 299 },
            page("width 7", 7, 9) { x, y -> (x + y) % 3 == 0 },
            page("width 8", 8, 9) { x, y -> x == y || x == 7 - y },
            page("width 9", 9, 9) { x, y -> x == 0 || x == 8 || y == 4 },
            page("1728 bars", 1728, 6) { x, y -> (x / (y + 1)) % 5 == 0 || x > 1700 },
            page("long runs", 3000, 4) { x, y -> if (y % 2 == 0) x >= 2600 else x < 2570 || x == 2999 },
            noise(37, 23, 7),
            text(1728, 40, 11),
            text(157, 61, 3),
        )
    }

    /** FNV-1a, 64 bits, for the committed hashes of encoder output. */
    fun fnv(bytes: ByteArray): Long {
        var h = -0x340d631b7bdddcdbL
        for (b in bytes) {
            h = h xor (b.toLong() and 0xFF)
            h *= 0x100000001b3L
        }
        return h
    }
}
