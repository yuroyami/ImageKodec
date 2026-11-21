package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The PNG calls that take no [PngEncodeOptions] keep their output byte for byte (#182). The
 * hashes were taken after Deflate moved to ArchiveKodec, so a change to the shared
 * writer that moves a byte of these files fails here on every target.
 */
class PngEncoderCompatTest {

    private fun fnv(bytes: ByteArray): Long {
        var h = -0x340d631b7bdddcdbL
        for (b in bytes) h = (h xor (b.toLong() and 255)) * 0x100000001b3L
        return h
    }

    private fun card(w: Int, h: Int, alpha: Boolean): KiteBitmap = KiteBitmap(w, h, IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val a = if (alpha) ((x + y) * 7) and 0xFF else 0xFF
        argb(a, x * 255 / maxOf(1, w - 1), y * 255 / maxOf(1, h - 1), ((x + y) * 3) and 0xFF)
    })

    private fun frame(t: Int): KiteBitmap = KiteBitmap(31, 17, IntArray(31 * 17) { i ->
        val x = i % 31
        val y = i / 31
        val inSquare = x - 3 * t in 4..12 && y - t in 3..9
        if (inSquare) argb(0xFF, 250, 20 + 30 * t, 40) else argb((x * 17 + y * 5) and 0xFF, x * 7 and 0xFF, y * 11 and 0xFF, 90)
    })

    private fun hashes(): List<Long> {
        val scene = PhotoScenes.bokeh(96, 64).bitmap
        val few = KiteBitmap(40, 30, IntArray(40 * 30) { i -> argb(0xFF, (i % 7) * 30, (i / 40 % 5) * 50, 77) })
        val animation = KiteAnimation(31, 17, List(4) { KiteFrame(frame(it), 40 + it, 4) }, 0)
        val metadata = ImageMetadata(exif = null, xmp = "<x:xmpmeta/>", icc = null)
        return listOf(
            fnv(ImageKodec.encodePng(card(31, 7, alpha = false))),
            fnv(ImageKodec.encodePng(card(9, 5, alpha = true))),
            fnv(ImageKodec.encodePng(scene)),
            fnv(ImageKodec.encodePng(few)),
            fnv(ImageKodec.encodePng(animation)),
            fnv(ImageKodec.encodePng(card(16, 16, alpha = false), metadata)),
        )
    }

    @Test
    fun outputWithoutOptionsIsUnchanged() {
        val got = hashes()
        assertEquals(EXPECTED, got.map { it.toULong().toString(16) })
    }

    private companion object {
        val EXPECTED = listOf(
            "3791aaf65b179dfc", "f64246667bb12bb0", "95afc1f53cebf84a",
            "d31b3e5f79c8a7ed", "daf62eb1f5baf2a0", "99e054b1c0d7760e",
        )
    }
}
