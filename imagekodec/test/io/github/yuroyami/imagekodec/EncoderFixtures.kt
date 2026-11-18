package io.github.yuroyami.imagekodec

/** Fixed pictures for the TIFF and BMP encoder tests (#184), the same on every target. */
internal object EncoderFixtures {

    /** A colour card: ramps across and down, a product term, and alpha from both when [alpha]. */
    fun card(w: Int, h: Int, alpha: Boolean, gray: Boolean, seed: Int = 0): KiteBitmap = KiteBitmap(w, h, IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val a = if (alpha) (x * 13 + y * 7 + seed) and 0xFF else 0xFF
        val r = (x * 255 / maxOf(1, w - 1) + seed) and 0xFF
        if (gray) argb(a, r, r, r) else argb(a, r, (y * 255 / maxOf(1, h - 1)) xor seed and 0xFF, (x * y + seed) and 0xFF)
    })

    fun wide(w: Int, h: Int, channels: Int): KiteBitmap16 =
        KiteBitmap16(w, h, channels, ShortArray(w * h * channels) { i -> ((i * 2654435761L) ushr 7).toInt().toShort() })

    /** The output of the calls that existed before #184, for pictures of every kind they write. */
    fun legacyHashes(): List<Long> = listOf(
        BilevelPages.fnv(ImageKodec.encodeTiff(card(37, 23, alpha = false, gray = false))),
        BilevelPages.fnv(ImageKodec.encodeTiff(card(300, 211, alpha = true, gray = false, seed = 9))),
        BilevelPages.fnv(ImageKodec.encodeTiff(card(16, 16, alpha = false, gray = true))),
        BilevelPages.fnv(ImageKodec.encodeTiff(listOf(card(20, 10, false, false), card(7, 31, true, true, 3)))),
        BilevelPages.fnv(ImageKodec.encodeTiff16(wide(29, 19, 1))),
        BilevelPages.fnv(ImageKodec.encodeTiff16(wide(29, 19, 4))),
        BilevelPages.fnv(ImageKodec.encodeTiff16(listOf(wide(5, 4, 3), wide(3, 6, 2)))),
        BilevelPages.fnv(ImageKodec.encodeBmp(card(37, 23, alpha = false, gray = false))),
        BilevelPages.fnv(ImageKodec.encodeBmp(card(37, 23, alpha = true, gray = false))),
    )
}
