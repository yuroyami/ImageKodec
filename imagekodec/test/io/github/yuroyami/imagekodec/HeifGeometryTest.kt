package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.avif.AvifContainer
import io.github.yuroyami.imagekodec.codec.avif.AvifProperty
import io.github.yuroyami.imagekodec.codec.avif.HeifGeometry
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Transforms in association order, `iden` items and `jpeg` items (#167). */
class HeifGeometryTest {

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray) = be32(8 + payload.size) + type.encodeToByteArray() + payload
    private fun ispe(w: Int, h: Int) = box("ispe", ByteArray(4) + be32(w) + be32(h)) to false
    private val irot = box("irot", byteArrayOf(1)) to true                 // a quarter turn anticlockwise
    private val imir = box("imir", byteArrayOf(1)) to true                 // left for right
    private val clap = box("clap", be32(12) + be32(1) + be32(10) + be32(1) + be32(1) + be32(1) + be32(0) + be32(1)) to true

    /** The AV1 data and av1C box of the 24 by 16 card at 4:4:4 that AvifFixtures.oriented holds. */
    private val card: Pair<ByteArray, Pair<ByteArray, Boolean>> = run {
        val c = AvifContainer.parse(AvifFixtures.oriented)
        val item = c.items[c.primary!!]!!
        val config = item.property<AvifProperty.Av1Config>()!!
        c.itemData(item) to (box("av1C", config.payload) to true)
    }

    private fun file(steps: List<Pair<ByteArray, Boolean>>, brands: List<String>) = HeifBuilder("avif", brands).apply {
        add(1, "av01", card.first, listOf(card.second, ispe(24, 16)) + steps)
    }.build()

    private val base: KiteBitmap by lazy { ImageKodec.decode(file(emptyList(), listOf("avif", "mif1"))) }

    /** [b] with the step [step] applied, as ISO/IEC 23008-12 6.5 defines each property. */
    private fun apply(b: KiteBitmap, step: Pair<ByteArray, Boolean>): KiteBitmap = when (step) {
        irot -> b.rotated270()
        imir -> b.flippedHorizontal()
        else -> b.cropped((b.width - 12) / 2 + 1, (b.height - 10) / 2, 12, 10)
    }

    @Test
    fun orientationsComposeAsAGroup() {
        for (a in Orientation.entries) {
            assertEquals(Orientation.Normal, HeifGeometry.compose(a, HeifGeometry.inverse(a)), "$a")
            assertEquals(a, HeifGeometry.compose(a, Orientation.Normal))
            assertEquals(a, HeifGeometry.compose(Orientation.Normal, a))
        }
        assertEquals(Orientation.Rotate180, HeifGeometry.compose(Orientation.Rotate90, Orientation.Rotate90))
        assertEquals(Orientation.Normal, HeifGeometry.compose(Orientation.FlipHorizontal, Orientation.FlipHorizontal))
    }

    @Test
    fun withoutMiafEveryOrderOfTheTransformsApplies() {
        val steps = listOf(clap, irot, imir)
        val orders = listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))
        for (order in orders) {
            val chosen = order.map { steps[it] }
            val want = chosen.fold(base) { b, s -> apply(b, s) }
            val got = ImageKodec.decode(file(chosen, listOf("avif", "mif1")), applyOrientation = true)
            assertEquals(want.width to want.height, got.width to got.height, "order $order")
            assertContentEquals(want.argb, got.argb, "order $order")
        }
    }

    @Test
    fun withMiafTheOrderIsFixed() {
        val want = listOf(clap, irot, imir).fold(base) { b, s -> apply(b, s) }
        val got = ImageKodec.decode(file(listOf(imir, irot, clap), listOf("avif", "mif1", "miaf")), applyOrientation = true)
        assertContentEquals(want.argb, got.argb)
    }

    @Test
    fun anExifOrientationBesideIrotTurnsThePictureOnce() {
        // libheif keeps the EXIF orientation beside irot; a reader takes irot alone (#149).
        val tiff = byteArrayOf(0x49, 0x49, 0x2A, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0)
        val file = HeifBuilder("avif", listOf("avif", "mif1", "miaf")).apply {
            add(1, "av01", card.first, listOf(card.second, ispe(24, 16), irot))
            add(2, "Exif", ByteArray(4) + tiff, emptyList(), hidden = true)
            references += Triple("cdsc", 2L, longArrayOf(1))
        }.build()
        assertEquals(6, ImageKodec.readMetadata(file).exif?.let { it[18].toInt() })
        assertContentEquals(base.rotated270().argb, ImageKodec.decode(file, applyOrientation = true).argb)
    }

    @Test
    fun anIdentityItemShowsItsInputWithItsOwnTransforms() {
        val iden = HeifBuilder("avif", listOf("avif", "mif1")).apply {
            add(1, "iden", ByteArray(0), listOf(ispe(24, 16), irot))
            add(2, "av01", card.first, listOf(card.second, ispe(24, 16)), hidden = true)
            references += Triple("dimg", 1L, longArrayOf(2))
        }.build()
        val got = ImageKodec.decode(iden, applyOrientation = true)
        assertContentEquals(base.rotated270().argb, got.argb)
    }

    @Test
    fun aJpegItemDecodesAsItsJpeg() {
        val picture = KiteBitmap(16, 8, IntArray(128) { 0xFF000000.toInt() or (it * 0x010203) })
        val jpeg = ImageKodec.encodeJpeg(picture, 90)
        val heif = HeifBuilder("mif1", listOf("mif1")).apply { add(1, "jpeg", jpeg, listOf(ispe(16, 8))) }.build()
        assertEquals(ImageFormat.HEIC, ImageFormat.sniff(heif))
        assertContentEquals(ImageKodec.decode(jpeg).argb, ImageKodec.decode(heif).argb)
    }
}
