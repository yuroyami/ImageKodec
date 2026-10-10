package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * A TIFF is a chain of image file directories, one a page (#7): the header points
 * at the first, and each ends with the offset of the next, or zero.
 */
class TiffPagesTest {

    internal class Page(val width: Int, val height: Int, val gray: Int, val orientation: Int = 0)

    internal val pages = listOf(Page(5, 3, 10), Page(7, 2, 120), Page(4, 6, 250))

    /** An 8-bit gray page as the decoder gives it back. */
    private fun pixel(gray: Int) = argb(0xFF, gray, gray, gray)

    /**
     * Uncompressed 8-bit gray pages, each directory followed by its pixels. [next]
     * gives the offset a directory links to, from its index and every directory's offset.
     */
    internal fun tiff(
        pages: List<Page>,
        littleEndian: Boolean = true,
        next: (index: Int, directories: IntArray) -> Long = { i, d -> if (i + 1 < d.size) d[i + 1].toLong() else 0L },
    ): ByteArray {
        fun entries(p: Page) = if (p.orientation != 0) 10 else 9
        val directories = IntArray(pages.size)
        var size = 8
        for ((i, p) in pages.withIndex()) {
            directories[i] = size
            size += 2 + 12 * entries(p) + 4 + p.width * p.height
            size += size and 1
        }
        val out = ByteArray(size)
        var at = 0
        fun u8(v: Int) { out[at++] = v.toByte() }
        fun u16(v: Int) = if (littleEndian) { u8(v); u8(v ushr 8) } else { u8(v ushr 8); u8(v) }
        fun u32(v: Long) = if (littleEndian) { u16(v.toInt()); u16((v ushr 16).toInt()) } else { u16((v ushr 16).toInt()); u16(v.toInt()) }
        fun short(tag: Int, v: Int) { u16(tag); u16(3); u32(1); u16(v); u16(0) }
        fun long(tag: Int, v: Int) { u16(tag); u16(4); u32(1); u32(v.toLong()) }

        if (littleEndian) { u8('I'.code); u8('I'.code) } else { u8('M'.code); u8('M'.code) }
        u16(42)
        u32(directories.firstOrNull()?.toLong() ?: 0L)
        for ((i, p) in pages.withIndex()) {
            at = directories[i]
            val pixelsAt = at + 2 + 12 * entries(p) + 4
            u16(entries(p))
            short(256, p.width)
            short(257, p.height)
            short(258, 8)
            short(259, 1)
            short(262, 1)
            long(273, pixelsAt)
            if (p.orientation != 0) short(274, p.orientation)
            short(277, 1)
            short(278, p.height)
            long(279, p.width * p.height)
            u32(next(i, directories))
            repeat(p.width * p.height) { u8(p.gray) }
        }
        return out
    }

    @Test
    fun everyPageProbesAndDecodesInBothByteOrders() {
        for (littleEndian in listOf(true, false)) {
            val bytes = tiff(pages, littleEndian)
            val info = ImageKodec.probe(bytes)
            assertEquals(3, info.pageCount)
            assertEquals(1, info.frameCount, "pages are not frames")
            assertFalse(info.isAnimated)
            assertEquals(5, info.width)
            for ((i, p) in pages.withIndex()) {
                val page = ImageKodec.probePage(bytes, i)
                assertEquals(3, page.pageCount)
                assertEquals(p.width, page.width, "page $i width")
                assertEquals(p.height, page.height, "page $i height")
                val bitmap = ImageKodec.decodePage(bytes, i)
                assertEquals(p.width, bitmap.width)
                assertEquals(p.height, bitmap.height)
                assertContentEquals(IntArray(p.width * p.height) { pixel(p.gray) }, bitmap.argb, "page $i pixels")
            }
            assertContentEquals(ImageKodec.decodePage(bytes, 0).argb, ImageKodec.decode(bytes).argb)
            assertEquals(1, ImageKodec.decodeAnimation(bytes).frames.size)
            assertFailsWith<IllegalArgumentException> { ImageKodec.decodePage(bytes, 3) }
            assertFailsWith<IllegalArgumentException> { ImageKodec.decodePage(bytes, -1) }
            assertFailsWith<IllegalArgumentException> { ImageKodec.probePage(bytes, 3) }
        }
    }

    @Test
    fun aChainThatLoopsEndsAtTheFirstDirectoryItRevisits() {
        // The last page links back to the first, and a page that links to itself.
        val loop = tiff(pages) { i, d -> if (i + 1 < d.size) d[i + 1].toLong() else d[0].toLong() }
        assertEquals(3, ImageKodec.probe(loop).pageCount)
        assertContentEquals(IntArray(4 * 6) { pixel(250) }, ImageKodec.decodePage(loop, 2).argb)
        assertFailsWith<IllegalArgumentException> { ImageKodec.decodePage(loop, 3) }

        val self = tiff(pages) { _, d -> d[1].toLong() }   // the first links to the second, the second to itself
        assertEquals(2, ImageKodec.probe(self).pageCount)
    }

    @Test
    fun aLinkThatLeadsNowhereKeepsThePagesBeforeIt() {
        for (target in listOf(0x7FFF_FFF0L, 0xFFFF_FFFFL, 4L)) {
            val bytes = tiff(pages) { i, d -> if (i == 0) d[1].toLong() else if (i == 1) target else 0L }
            assertEquals(2, ImageKodec.probe(bytes).pageCount, "link to $target")
            assertContentEquals(IntArray(7 * 2) { pixel(120) }, ImageKodec.decodePage(bytes, 1).argb)
        }
        // The link of the first page is cut off: that page is all there is, and probing it still works.
        val whole = tiff(pages)
        val cut = whole.copyOf(8 + 2 + 12 * 9 + 2)
        assertEquals(1, ImageKodec.probe(cut).pageCount)
    }

    @Test
    fun eachPageKeepsItsOwnOrientation() {
        val bytes = tiff(listOf(Page(5, 3, 10), Page(7, 2, 120, orientation = 6)))
        assertEquals(Orientation.Normal, ImageKodec.probe(bytes).orientation)
        assertEquals(Orientation.Rotate90, ImageKodec.probePage(bytes, 1).orientation)
        val turned = ImageKodec.decodePage(bytes, 1, applyOrientation = true)
        assertEquals(2, turned.width)
        assertEquals(7, turned.height)
        assertEquals(7, ImageKodec.decodePage(bytes, 1).width)
    }

    @Test
    fun otherFormatsHaveOnePage() {
        val png = ImageKodec.encodePng(KiteBitmap(3, 2, IntArray(6) { argb(0xFF, it * 40, 0, 0) }))
        assertEquals(1, ImageKodec.probe(png).pageCount)
        assertEquals(3, ImageKodec.probePage(png, 0).width)
        assertContentEquals(ImageKodec.decode(png).argb, ImageKodec.decodePage(png, 0).argb)
        assertFailsWith<IllegalArgumentException> { ImageKodec.decodePage(png, 1) }
        assertFailsWith<IllegalArgumentException> { ImageKodec.probePage(png, 1) }
        // Data that is no image fails as a decode does, whatever page is asked for.
        assertFailsWith<ImageDecodeException> { ImageKodec.decodePage(ByteArray(16), 1) }
    }
}
