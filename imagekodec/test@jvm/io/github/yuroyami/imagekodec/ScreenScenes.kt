package io.github.yuroyami.imagekodec

import kotlin.random.Random

/**
 * Screen content for the encoder tests (#176): a page of text in a few colours, and a desktop of windows, icons and
 * text over a gradient, drawn from a small pixel font so that the same glyphs repeat at many places; and a blur, for
 * pictures whose detail a lens softened.
 */
object ScreenScenes {

    /** Ten glyphs of 5 by 7 pixels, a row of 5 bits each, high bit leftmost. */
    private val glyphs = arrayOf(
        intArrayOf(0, 0, 14, 1, 15, 17, 15), // a
        intArrayOf(0, 0, 14, 17, 31, 16, 14), // e
        intArrayOf(4, 0, 12, 4, 4, 4, 14), // i
        intArrayOf(0, 0, 22, 25, 17, 17, 17), // n
        intArrayOf(0, 0, 14, 17, 17, 17, 14), // o
        intArrayOf(0, 0, 22, 25, 16, 16, 16), // r
        intArrayOf(0, 0, 15, 16, 14, 1, 30), // s
        intArrayOf(8, 8, 28, 8, 8, 9, 6), // t
        intArrayOf(12, 4, 4, 4, 4, 4, 14), // l
        intArrayOf(16, 16, 22, 25, 17, 17, 17), // h
    )

    private fun glyph(out: IntArray, w: Int, h: Int, g: Int, x0: Int, y0: Int, scale: Int, argb: Int) {
        for (row in 0 until 7) for (col in 0 until 5) {
            if ((glyphs[g][row] shr (4 - col)) and 1 == 0) continue
            for (dy in 0 until scale) for (dx in 0 until scale) {
                val x = x0 + col * scale + dx
                val y = y0 + row * scale + dy
                if (x in 0 until w && y in 0 until h) out[y * w + x] = argb
            }
        }
    }

    /** Lines of words in [x0] until [x1] and [y0] until [y1], most black, some in colour, from [seed]. */
    private fun write(out: IntArray, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int, seed: Int, scale: Int) {
        val random = Random(seed)
        val colours = intArrayOf(0xFF202020.toInt(), 0xFF202020.toInt(), 0xFF202020.toInt(), 0xFFC02020.toInt(), 0xFF2050C0.toInt())
        val advance = 6 * scale
        var y = y0
        while (y + 7 * scale <= y1) {
            var x = x0
            while (true) {
                val letters = 2 + random.nextInt(6)
                if (x + letters * advance > x1) break
                val colour = colours[random.nextInt(colours.size)]
                for (k in 0 until letters) glyph(out, w, h, random.nextInt(glyphs.size), x + k * advance, y, scale, colour)
                x += (letters + 1) * advance
            }
            y += 10 * scale
        }
    }

    /** A page of text: words of the small font at twice its size, black and in two colours, on white. */
    fun text(w: Int, h: Int): KiteBitmap {
        val out = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        write(out, w, h, 8, 8, w - 8, h - 8, 7, 2)
        return KiteBitmap(w, h, out)
    }

    /** A desktop: a gradient, three windows with title bars and text, and a row of icons. */
    fun desktop(w: Int, h: Int): KiteBitmap {
        val out = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val r = 40 + x * 60 / w
            val g = 70 + y * 80 / h
            val b = 140 + (x + y) * 60 / (w + h)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val icon = IntArray(16 * 16) { i ->
            val x = i % 16
            val y = i / 16
            when {
                x == 0 || y == 0 || x == 15 || y == 15 -> 0xFF303030.toInt()
                (x - 8) * (x - 8) + (y - 8) * (y - 8) < 20 -> 0xFFE0B030.toInt()
                else -> 0xFFF0F0F0.toInt()
            }
        }
        for (k in 0 until (w - 16) / 40) {
            for (i in 0 until 256) out[(h - 28 + i / 16) * w + 12 + k * 40 + i % 16] = icon[i]
        }
        val windows = arrayOf(intArrayOf(20, 20, w / 2, h / 2), intArrayOf(w / 3, h / 4, w - 24, h - 48), intArrayOf(40, h / 2 + 10, w / 2 + 20, h - 40))
        for ((n, win) in windows.withIndex()) {
            val (x0, y0, x1, y1) = win.toList()
            for (y in y0 until y1) for (x in x0 until x1) {
                out[y * w + x] = if (y < y0 + 14) 0xFF3060A0.toInt() else 0xFFF8F8F4.toInt()
            }
            write(out, w, h, x0 + 6, y0 + 20, x1 - 6, y1 - 4, 11 + n, 1)
        }
        return KiteBitmap(w, h, out)
    }

    /** [b] blurred by a five-tap binomial kernel each way, its edges repeated. */
    fun blurred(b: KiteBitmap): KiteBitmap {
        val w = b.width
        val h = b.height
        val kernel = intArrayOf(1, 4, 6, 4, 1)
        fun channel(shift: Int): IntArray {
            val mid = IntArray(w * h) { i ->
                val x = i % w
                val y = i / w
                var s = 0
                for (t in 0 until 5) s += kernel[t] * ((b.argb[y * w + (x + t - 2).coerceIn(0, w - 1)] shr shift) and 0xFF)
                s
            }
            return IntArray(w * h) { i ->
                val x = i % w
                val y = i / w
                var s = 0
                for (t in 0 until 5) s += kernel[t] * mid[(y + t - 2).coerceIn(0, h - 1) * w + x]
                (s + 128) shr 8
            }
        }
        val r = channel(16)
        val g = channel(8)
        val bl = channel(0)
        return KiteBitmap(w, h, IntArray(w * h) { (0xFF shl 24) or (r[it] shl 16) or (g[it] shl 8) or bl[it] })
    }
}
