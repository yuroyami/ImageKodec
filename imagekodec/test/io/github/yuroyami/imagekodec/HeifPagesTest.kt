package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.avif.AvifContainer
import io.github.yuroyami.imagekodec.codec.avif.AvifProperty
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The top-level images of a HEIF file are its pages, and a `ster` group names a stereo pair (#168). */
class HeifPagesTest {

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray) = be32(8 + payload.size) + type.encodeToByteArray() + payload
    private fun ispe(w: Int, h: Int) = box("ispe", ByteArray(4) + be32(w) + be32(h)) to false

    /** The AV1 item data and av1C box of an AVIF fixture's primary image. */
    private fun av1(file: ByteArray): Pair<ByteArray, Pair<ByteArray, Boolean>> {
        val c = AvifContainer.parse(file)
        val item = c.items[c.primary!!]!!
        return c.itemData(item) to (box("av1C", item.property<AvifProperty.Av1Config>()!!.payload) to true)
    }

    private val left = av1(AvifFixtures.plain)
    private val right = av1(AvifFixtures.oriented)

    private fun pair(stereo: Boolean) = HeifBuilder("avif", listOf("avif", "mif1")).apply {
        add(1, "av01", left.first, listOf(left.second, ispe(24, 16)))
        add(2, "av01", right.first, listOf(right.second, ispe(24, 16)))
        // An Exif item and a hidden image are no pages.
        add(3, "Exif", ByteArray(12), emptyList(), hidden = true)
        add(4, "av01", left.first, listOf(left.second, ispe(24, 16)), hidden = true)
        if (stereo) groups += "ster" to longArrayOf(1, 2)
    }.build()

    @Test
    fun eachTopLevelImageIsAPage() {
        val file = pair(stereo = true)
        val info = ImageKodec.probe(file)
        assertEquals(2, info.pageCount)
        assertEquals(0 to 1, info.stereoLeftPage to info.stereoRightPage)
        assertEquals(2, ImageKodec.probePage(file, 1).pageCount)
        val alone = HeifBuilder("avif", listOf("avif", "mif1")).apply { add(1, "av01", right.first, listOf(right.second, ispe(24, 16))) }.build()
        assertContentEquals(ImageKodec.decode(alone).argb, ImageKodec.decodePage(file, 1).argb)
        assertContentEquals(ImageKodec.decode(file).argb, ImageKodec.decodePage(file, 0).argb)
        assertContentEquals(ImageKodec.decode16(alone).samples, ImageKodec.decode16(file, page = 1).samples)
        assertFailsWith<IllegalArgumentException> { ImageKodec.decodePage(file, 2) }
    }

    @Test
    fun withoutAGroupThereIsNoStereoPair() {
        val info = ImageKodec.probe(pair(stereo = false))
        assertEquals(2, info.pageCount)
        assertEquals(-1 to -1, info.stereoLeftPage to info.stereoRightPage)
    }

    @Test
    fun anAlternativeOfThePrimaryIsNoPage() {
        val file = HeifBuilder("avif", listOf("avif", "mif1")).apply {
            add(1, "av01", left.first, listOf(left.second, ispe(24, 16)))
            add(2, "av01", right.first, listOf(right.second, ispe(24, 16)))
            groups += "altr" to longArrayOf(1, 2)
        }.build()
        assertEquals(1, ImageKodec.probe(file).pageCount)
        assertEquals(1, ImageKodec.probe(AvifFixtures.grid).pageCount)
    }
}
