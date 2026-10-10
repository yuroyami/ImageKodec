package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The TIFF writer (#9): every kind of page it writes reads back exactly, alone or as one page of many. */
class TiffEncoderTest {

    private fun card(w: Int, h: Int, alpha: Boolean, gray: Boolean): KiteBitmap = KiteBitmap(w, h, IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val a = if (alpha) (x * 13 + y * 7) and 0xFF else 0xFF
        val r = (x * 255 / maxOf(1, w - 1))
        if (gray) argb(a, r, r, r) else argb(a, r, (y * 255 / maxOf(1, h - 1)), (x * y) and 0xFF)
    })

    @Test
    fun everyKindOfPageReadsBackExactly() {
        for (alpha in listOf(false, true)) for (gray in listOf(false, true)) {
            val src = card(37, 23, alpha, gray)
            val data = ImageKodec.encodeTiff(src)
            assertEquals(ImageFormat.TIFF, ImageKodec.detect(data))
            assertContentEquals(src.argb, ImageKodec.decode(data).argb, "alpha $alpha gray $gray")
            val info = ImageKodec.probe(data)
            assertEquals(alpha, info.hasAlpha, "alpha $alpha gray $gray")
            assertEquals(1, info.pageCount)
        }
    }

    @Test
    fun anImageOfManyStripsReadsBackExactly() {
        // 8 KiB strips: 300 RGB pixels a row is 9 rows a strip, so 211 rows take 24 strips.
        val src = card(300, 211, alpha = false, gray = false)
        assertContentEquals(src.argb, ImageKodec.decode(ImageKodec.encodeTiff(src)).argb)
        // A row longer than a strip still makes one row a strip.
        val wide = card(3000, 5, alpha = true, gray = false)
        assertContentEquals(wide.argb, ImageKodec.decode(ImageKodec.encodeTiff(wide)).argb)
    }

    @Test
    fun pagesReadBackInOrder() {
        val pages = listOf(
            card(20, 10, alpha = false, gray = false),
            card(7, 31, alpha = true, gray = false),
            card(16, 16, alpha = false, gray = true),
        )
        val data = ImageKodec.encodeTiff(pages)
        assertEquals(3, ImageKodec.probe(data).pageCount)
        for ((i, page) in pages.withIndex()) {
            assertContentEquals(page.argb, ImageKodec.decodePage(data, i).argb, "page $i")
            assertEquals(page.width to page.height, ImageKodec.probePage(data, i).let { it.width to it.height }, "page $i size")
        }
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeTiff(emptyList<KiteBitmap>()) }
    }

    @Test
    fun sixteenBitSamplesReadBackExactly() {
        for (channels in 1..4) {
            val w = 29
            val h = 19
            val samples = ShortArray(w * h * channels) { i -> ((i * 2654435761L) ushr 7).toInt().toShort() }
            val src = KiteBitmap16(w, h, channels, samples)
            val back = ImageKodec.decode16(ImageKodec.encodeTiff16(src))
            assertEquals(channels, back.channels, "$channels channels")
            assertContentEquals(samples, back.samples, "$channels channels")
        }
        val pages = listOf(
            KiteBitmap16(5, 4, 3, ShortArray(60) { (it * 1000).toShort() }),
            KiteBitmap16(3, 6, 2, ShortArray(36) { (it * 1800 + 7).toShort() }),
        )
        val data = ImageKodec.encodeTiff16(pages)
        for ((i, page) in pages.withIndex()) assertContentEquals(page.samples, ImageKodec.decode16(data, page = i).samples, "16-bit page $i")
    }
}
