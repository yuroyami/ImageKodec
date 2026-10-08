package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.avif.AvifContainer
import io.github.yuroyami.imagekodec.codec.avif.AvifPlanes
import io.github.yuroyami.imagekodec.codec.avif.AvifProperty
import io.github.yuroyami.imagekodec.codec.avif.AvifSampleTransform
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AVIF through the facade (#41): the libavif fixtures as libavif reads them, and files built
 * here around their AV1 data for what avifenc never writes: data in `idat` or in scattered
 * extents, `altr` alternatives, essential properties, sample transforms, overlays, tone-mapped
 * images and hand-made grids.
 */
class AvifDecoderTest {

    private fun fnv(b: KiteBitmap): Long {
        var h = -0x340d631b7bdddcdbL
        for (px in b.argb) for (k in 0 until 4) h = (h xor ((px ushr (24 - 8 * k)) and 255).toLong()) * 0x100000001b3L
        return h
    }

    @Test
    fun theFixturesReadAsLibavifReadsThem() {
        class Expect(val hash: Long, val width: Int, val height: Int, val depth: Int, val alpha: Boolean, val orientation: Orientation, val frames: Int)
        val expected = mapOf(
            "plain" to Expect(0xad01fdbc63e1b638uL.toLong(), 24, 16, 8, false, Orientation.Normal, 1),
            "alpha" to Expect(0xa55791907da1b9f1uL.toLong(), 24, 16, 8, true, Orientation.Normal, 1),
            "prem" to Expect(0x719b5edba1d6fa4cL, 24, 16, 10, true, Orientation.Normal, 1),
            "grid" to Expect(0x6247cdf1f1209299L, 128, 64, 8, false, Orientation.Normal, 1),
            // A quarter turn anticlockwise and then top for bottom is the EXIF transpose.
            "oriented" to Expect(0x243e897f1ed7fd1L, 20, 12, 8, false, Orientation.Transpose, 1),
            "mono12" to Expect(0xb66ade456f86807L, 24, 16, 12, false, Orientation.Normal, 1),
            "seq" to Expect(0xb1227882e34c209buL.toLong(), 24, 16, 8, false, Orientation.Normal, 2),
        )
        for ((name, data) in AvifFixtures.all) {
            val e = expected.getValue(name)
            assertEquals(ImageFormat.AVIF, ImageFormat.sniff(data), name)
            val info = ImageKodec.probe(data)
            assertTrue(info.isDecodable, "$name: ${info.unsupportedReason}")
            assertEquals(e.width to e.height, info.width to info.height, "$name size")
            assertEquals(e.depth, info.bitDepth, "$name depth")
            assertEquals(e.alpha, info.hasAlpha, "$name alpha")
            assertEquals(e.orientation, info.orientation, "$name orientation")
            assertEquals(e.frames, info.frameCount, "$name frames")
            val bitmap = ImageKodec.decode(data)
            assertEquals(e.hash, fnv(bitmap), "$name differs from libavif")
            assertContentEquals(bitmap.argb, ImageKodec.decode16(data).toBitmap().argb, "$name: decode16 does not narrow to decode")
        }
        val oriented = ImageKodec.decode(AvifFixtures.oriented, applyOrientation = true)
        assertEquals(12 to 20, oriented.width to oriented.height)
    }

    @Test
    fun aProfileIsReportedFromTheSequenceHeaderWithoutColr() {
        // avifenc writes an nclx box: BT.601 matrix, sRGB transfer, BT.709 primaries, full range.
        assertEquals(listOf(1, 13, 6, 1), ImageKodec.probe(AvifFixtures.plain).colorProfile?.cicp?.toList())
        val bare = Heif().apply { add(1, "av01", plainData, listOf(av1C(plainConfig) to true, ispe(24, 16))) }.build()
        // libavif's encoder writes the same code points into the sequence header.
        assertEquals(listOf(1, 13, 6, 1), ImageKodec.probe(bare).colorProfile?.cicp?.toList())
        assertEquals(fnv(ImageKodec.decode(AvifFixtures.plain)), fnv(ImageKodec.decode(bare)))
    }

    @Test
    fun theFirstBytesOfAFileAreEnoughToProbeIt() {
        val c = AvifContainer.parse(AvifFixtures.alpha)
        // Cut inside the first item's data: the boxes are all there, the samples are not.
        val cut = AvifFixtures.alpha.copyOf(AvifFixtures.alpha.size - 300)
        assertEquals(ImageFormat.AVIF, ImageFormat.sniff(cut))
        val info = ImageKodec.probe(cut)
        assertEquals(24 to 16, info.width to info.height)
        assertTrue(info.hasAlpha)
        assertTrue(c.items.isNotEmpty())
        assertFailsWith<ImageDecodeException> { ImageKodec.decode(cut) }
        // Twelve bytes name the major brand; a compatible brand needs the whole ftyp.
        assertEquals(ImageFormat.AVIF, ImageFormat.sniff(AvifFixtures.plain.copyOf(12)))
        val compatible = Heif(major = "mif1", brands = listOf("mif1", "miaf", "avif")).apply {
            add(1, "av01", plainData, listOf(av1C(plainConfig) to true, ispe(24, 16)))
        }.build()
        assertEquals(ImageFormat.AVIF, ImageFormat.sniff(compatible))
        assertNull(ImageFormat.sniff(Heif(major = "heic", brands = listOf("mif1", "heic")).build()))
    }

    @Test
    fun itemDataInIdatOrInScatteredExtentsReadsTheSame() {
        val want = fnv(ImageKodec.decode(AvifFixtures.plain))
        val inIdat = Heif().apply { add(1, "av01", plainData, plainProperties(), idat = true) }.build()
        assertEquals(want, fnv(ImageKodec.decode(inIdat)))
        val scattered = Heif().apply { add(1, "av01", plainData, plainProperties(), extents = 3) }.build()
        assertEquals(want, fnv(ImageKodec.decode(scattered)))
    }

    @Test
    fun anAltrGroupFallsBackToAnItemThisReaderShows() {
        val want = fnv(ImageKodec.decode(AvifFixtures.plain))
        val hevc = box("hvcC", ByteArray(23))
        val fallback = Heif().apply {
            add(1, "hvc1", ByteArray(16), listOf(hevc to true, ispe(24, 16)))
            add(2, "av01", plainData, plainProperties())
            groups += "altr" to longArrayOf(1, 2)
        }.build()
        assertTrue(ImageKodec.probe(fallback).isDecodable)
        assertEquals(want, fnv(ImageKodec.decode(fallback)))
        val alone = Heif().apply { add(1, "hvc1", ByteArray(16), listOf(hevc to true, ispe(24, 16))) }.build()
        val info = ImageKodec.probe(alone)
        assertFalse(info.isDecodable)
        assertTrue(info.unsupportedReason!!.contains("hvc1"), info.unsupportedReason)
        assertFailsWith<UnsupportedImageException> { ImageKodec.decode(alone) }
    }

    @Test
    fun onlyAnUnknownEssentialPropertyRefusesAnItem() {
        val unknown = box("xyzw", byteArrayOf(1, 2, 3))
        val essential = Heif().apply { add(1, "av01", plainData, plainProperties() + (unknown to true)) }.build()
        val info = ImageKodec.probe(essential)
        assertFalse(info.isDecodable)
        assertTrue(info.unsupportedReason!!.contains("xyzw"), info.unsupportedReason)
        assertFailsWith<UnsupportedImageException> { ImageKodec.decode(essential) }
        val optional = Heif().apply { add(1, "av01", plainData, plainProperties() + (unknown to false)) }.build()
        assertEquals(fnv(ImageKodec.decode(AvifFixtures.plain)), fnv(ImageKodec.decode(optional)))
    }

    @Test
    fun aSampleTransformComputesFromItsInputs() {
        // (a + b) / 2 of two copies of the card is the card.
        val expression = byteArrayOf(0x02, 5, 1, 2, 128.toByte(), 0) + be32(2) + byteArrayOf(131.toByte())
        val file = Heif().apply {
            add(1, "sato", expression, listOf(ispe(24, 16), pixi(8, 8, 8), nclx(1, 13, 6, true)))
            add(2, "av01", plainData, plainProperties(), hidden = true)
            add(3, "av01", plainData, plainProperties(), hidden = true)
            references += Triple("dimg", 1L, longArrayOf(2, 3))
        }.build()
        val info = ImageKodec.probe(file)
        assertTrue(info.isDecodable, info.unsupportedReason)
        assertEquals(8, info.bitDepth)
        assertEquals(fnv(ImageKodec.decode(AvifFixtures.plain)), fnv(ImageKodec.decode(file)))
    }

    @Test
    fun sampleTransformArithmeticSaturates() {
        fun eval(bits: Int, vararg tokens: Any, depth: Int = 16, input: Int = 100): Int {
            val bytes = ArrayList<Byte>()
            bytes += (when (bits) { 8 -> 0; 16 -> 1; 32 -> 2; else -> 3 }).toByte()
            bytes += tokens.size.toByte()
            for (t in tokens) {
                if (t is Long) {
                    bytes += 0
                    for (k in bits / 8 - 1 downTo 0) bytes += (t ushr (8 * k)).toByte()
                } else {
                    bytes += (t as Int).toByte()
                }
            }
            val plane = AvifPlanes(1, 1, 8, 0, 0, arrayOf(shortArrayOf(input.toShort())), intArrayOf(1), true)
            return AvifSampleTransform.apply(bytes.toByteArray(), listOf(plane), depth, true).sample(0, 0)
        }
        assertEquals(100 * 257, eval(32, 1, 257L, 130))
        // 16-bit arithmetic stops at 32767, and the output at its own range.
        assertEquals(32767, eval(16, 1, 1000L, 130))
        assertEquals(0, eval(16, 0L, 1, 129))
        // Division by zero gives the left operand; a negative power of 2 truncates to 0.
        assertEquals(100, eval(32, 1, 0L, 131))
        assertEquals(0, eval(32, 2L, -3L, 135))
        assertEquals(1024, eval(32, 2L, 10L, 135))
        assertEquals(65535, eval(64, 3L, 1000000L, 135))
        // bsr: the index of the top set bit; negation and absolute value of the 64-bit minimum saturate.
        assertEquals(6, eval(32, 1, 67))
        assertEquals(65535, eval(64, Long.MIN_VALUE, 65))
        assertEquals(100, eval(8, 1, 64, 64))
        // An expression that leaves two values, or reads an input it does not have, is refused.
        assertFailsWith<ImageDecodeException> { eval(32, 1, 1) }
        assertFailsWith<ImageDecodeException> { eval(32, 2) }
    }

    @Test
    fun aToneMappedImageShowsItsBaseImage() {
        val file = Heif().apply {
            add(1, "tmap", byteArrayOf(0, 0, 0, 0), listOf(ispe(24, 16)))
            add(2, "av01", plainData, plainProperties(), hidden = true)
            add(3, "av01", monoData, listOf(av1C(monoConfig) to true, ispe(24, 16)), hidden = true)
            references += Triple("dimg", 1L, longArrayOf(2, 3))
        }.build()
        assertEquals(fnv(ImageKodec.decode(AvifFixtures.plain)), fnv(ImageKodec.decode(file)))
    }

    @Test
    fun aHandMadeGridJoinsItsTilesAndCrops() {
        val file = Heif().apply {
            add(1, "grid", byteArrayOf(0, 0, 1, 1, 0, 46, 0, 30), listOf(ispe(46, 30)))
            for (id in 2..5) add(id, "av01", plainData, plainProperties(), hidden = true)
            references += Triple("dimg", 1L, longArrayOf(2, 3, 4, 5))
        }.build()
        val grid = ImageKodec.decode(file)
        assertEquals(46 to 30, grid.width to grid.height)
        val tile = ImageKodec.decode(AvifFixtures.plain)
        // Away from the seams, where chroma is upsampled from the neighbouring tile, and from the cropped
        // right and bottom edges, where it is not, each tile reads as it does alone.
        for (y in 0 until 30) for (x in 0 until 46) {
            val tx = x % 24
            val ty = y % 16
            if (tx == 0 || tx == 23 || ty == 0 || ty == 15 || x == 45 || y == 29) continue
            assertEquals(tile[tx, ty], grid[x, y], "($x, $y)")
        }
    }

    @Test
    fun anOverlayDrawsItsInputsOverItsFill() {
        val file = Heif().apply {
            // Opaque red, 40 by 30, then the card at (2, 3) and the card with alpha at (20, 10), partly outside.
            val data = byteArrayOf(0, 0, -1, -1, 0, 0, 0, 0, -1, -1, 0, 40, 0, 30, 0, 2, 0, 3, 0, 20, 0, 10)
            add(1, "iovl", data, listOf(ispe(40, 30)))
            add(2, "av01", plainData, plainProperties(), hidden = true)
            add(3, "av01", alphaData, alphaColorProperties(), hidden = true)
            add(4, "av01", alphaPlaneData, alphaPlaneProperties(), hidden = true)
            references += Triple("dimg", 1L, longArrayOf(2, 3))
            references += Triple("auxl", 4L, longArrayOf(3))
        }.build()
        val out = ImageKodec.decode(file)
        assertEquals(40 to 30, out.width to out.height)
        assertEquals(0xFFFF0000.toInt(), out[0, 0])
        val card = ImageKodec.decode(AvifFixtures.plain)
        assertEquals(card[5, 4], out[7, 7])
        val glass = ImageKodec.decode(AvifFixtures.alpha)
        // The card with alpha lies partly over the opaque card, partly over the red fill.
        for ((x, y) in listOf(0 to 0, 3 to 5, 10 to 8, 19 to 15, 15 to 2)) {
            val s = glass[x, y]
            val cx = 20 + x
            val cy = 10 + y
            val under = if (cx in 2 until 26 && cy in 3 until 19) card[cx - 2, cy - 3] else 0xFFFF0000.toInt()
            val a = (s ushr 24) / 255.0
            val o = out[cx, cy]
            assertEquals(255, o ushr 24, "alpha at ($x, $y)")
            for (shift in listOf(16, 8, 0)) {
                val want = ((s ushr shift) and 255) * a + ((under ushr shift) and 255) * (1 - a)
                assertTrue(kotlin.math.abs(((o ushr shift) and 255) - want) <= 1.5, "channel $shift at ($x, $y)")
            }
        }
    }

    @Test
    fun aCleanApertureThatNamesNoWholeRectangleIsLeftOut() {
        val half = box("clap", be32(41) + be32(2) + be32(16) + be32(1) + be32(0) + be32(1) + be32(0) + be32(1))
        val file = Heif().apply { add(1, "av01", plainData, plainProperties() + (half to true)) }.build()
        assertEquals(24 to 16, ImageKodec.probe(file).let { it.width to it.height })
        assertEquals(fnv(ImageKodec.decode(AvifFixtures.plain)), fnv(ImageKodec.decode(file)))
    }

    @Test
    fun brokenStructuresFailAsDecodeErrors() {
        assertFailsWith<ImageDecodeException> { ImageKodec.decode(Heif().apply { primary = null; add(1, "av01", plainData, plainProperties()) }.build()) }
        assertFailsWith<ImageDecodeException> { ImageKodec.decode(Heif().apply { primary = 9 }.build()) }
        val noConfig = Heif().apply { add(1, "av01", plainData, listOf(ispe(24, 16))) }.build()
        assertFalse(ImageKodec.probe(noConfig).isDecodable)
        val badGrid = Heif().apply {
            add(1, "grid", byteArrayOf(0, 0, 1, 1, 0, 100, 0, 30), listOf(ispe(100, 30)))
            for (id in 2..5) add(id, "av01", plainData, plainProperties(), hidden = true)
            references += Triple("dimg", 1L, longArrayOf(2, 3, 4, 5))
        }.build()
        assertFailsWith<ImageDecodeException> { ImageKodec.decode(badGrid) }
    }

    // ---- building files ---------------------------------------------------------------

    private val plainContainer = AvifContainer.parse(AvifFixtures.plain)
    private val plainItem = plainContainer.items.getValue(plainContainer.primary!!)
    private val plainData = plainContainer.itemData(plainItem)
    private val plainConfig = plainItem.property<AvifProperty.Av1Config>()!!
    private val monoContainer = AvifContainer.parse(AvifFixtures.mono12)
    private val monoItem = monoContainer.items.getValue(monoContainer.primary!!)
    private val monoData = monoContainer.itemData(monoItem)
    private val monoConfig = monoItem.property<AvifProperty.Av1Config>()!!
    private val alphaContainer = AvifContainer.parse(AvifFixtures.alpha)
    private val alphaItem = alphaContainer.items.getValue(alphaContainer.primary!!)
    private val alphaData = alphaContainer.itemData(alphaItem)
    private val alphaPlaneItem = alphaContainer.items.values.first { it.id != alphaItem.id }
    private val alphaPlaneData = alphaContainer.itemData(alphaPlaneItem)

    private fun plainProperties() = listOf(av1C(plainConfig) to true, ispe(24, 16), nclx(1, 13, 6, true))

    private fun alphaColorProperties() =
        listOf(av1C(alphaItem.property<AvifProperty.Av1Config>()!!) to true, ispe(24, 16), nclx(1, 13, 6, true))

    private fun alphaPlaneProperties() = listOf(
        av1C(alphaPlaneItem.property<AvifProperty.Av1Config>()!!) to true, ispe(24, 16),
        fullBox("auxC", 0, 0, "urn:mpeg:mpegB:cicp:systems:auxiliary:alpha".encodeToByteArray() + byteArrayOf(0)) to false,
    )

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray) = be32(8 + payload.size) + type.encodeToByteArray() + payload
    private fun fullBox(type: String, version: Int, flags: Int, payload: ByteArray) =
        box(type, byteArrayOf(version.toByte(), (flags ushr 16).toByte(), (flags ushr 8).toByte(), flags.toByte()) + payload)

    private fun ispe(w: Int, h: Int) = fullBox("ispe", 0, 0, be32(w) + be32(h)) to false
    private fun pixi(vararg depths: Int) = fullBox("pixi", 0, 0, byteArrayOf(depths.size.toByte()) + ByteArray(depths.size) { depths[it].toByte() }) to false
    private fun nclx(p: Int, t: Int, m: Int, full: Boolean) =
        box("colr", "nclx".encodeToByteArray() + be16(p) + be16(t) + be16(m) + byteArrayOf(if (full) 0x80.toByte() else 0)) to false

    private fun av1C(c: AvifProperty.Av1Config): ByteArray {
        val flags = (if (c.highBitdepth) 0x40 else 0) or (if (c.twelveBit) 0x20 else 0) or (if (c.monochrome) 0x10 else 0) or
            (c.subX shl 3) or (c.subY shl 2)
        return box("av1C", byteArrayOf(0x81.toByte(), (c.profile shl 5).toByte(), flags.toByte(), 0) + c.configObus)
    }

    /** A HEIF file of items, with each item's properties, references and an `altr` group. */
    private class Heif(val major: String = "avif", val brands: List<String> = listOf("avif", "mif1", "miaf")) {
        class Item(
            val id: Int, val type: String, val data: ByteArray, val properties: List<Pair<ByteArray, Boolean>>,
            val hidden: Boolean, val idat: Boolean, val extents: Int,
        )

        val items = ArrayList<Item>()
        val references = ArrayList<Triple<String, Long, LongArray>>()
        val groups = ArrayList<Pair<String, LongArray>>()
        var primary: Int? = 1

        fun add(
            id: Int, type: String, data: ByteArray, properties: List<Pair<ByteArray, Boolean>>,
            hidden: Boolean = false, idat: Boolean = false, extents: Int = 1,
        ) {
            items += Item(id, type, data, properties, hidden, idat, extents)
        }

        private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
        private fun box(type: String, payload: ByteArray) = be32(8 + payload.size) + type.encodeToByteArray() + payload
        private fun fullBox(type: String, version: Int, flags: Int, payload: ByteArray) =
            box(type, byteArrayOf(version.toByte(), (flags ushr 16).toByte(), (flags ushr 8).toByte(), flags.toByte()) + payload)

        fun build(): ByteArray {
            val ftyp = box("ftyp", major.encodeToByteArray() + be32(0) + brands.joinToString("").encodeToByteArray())
            // Each item's data in its extents; the extents go into mdat last first, so only iloc puts them in order.
            val pieces = items.map { item ->
                val n = item.extents
                List(n) { k -> item.data.copyOfRange(item.data.size * k / n, item.data.size * (k + 1) / n) }
            }
            fun meta(mdatStart: Int): ByteArray {
                var idat = ByteArray(0)
                var mdatAt = mdatStart + 8
                val extentsOf = HashMap<Int, List<Pair<Int, Int>>>()
                val idatOf = HashMap<Int, Int>()
                for ((i, item) in items.withIndex()) {
                    if (item.idat) {
                        idatOf[item.id] = idat.size
                        idat += item.data
                    }
                }
                val order = items.indices.filter { !items[it].idat }
                val offsets = HashMap<Pair<Int, Int>, Int>()
                for (i in order) for (k in pieces[i].indices.reversed()) {
                    offsets[i to k] = mdatAt
                    mdatAt += pieces[i][k].size
                }
                for (i in order) extentsOf[items[i].id] = pieces[i].indices.map { k -> offsets.getValue(i to k) to pieces[i][k].size }
                var iloc = be16(0x4400) + be16(items.size)
                for (item in items) {
                    iloc += be16(item.id) + be16(if (item.idat) 1 else 0) + be16(0)
                    if (item.idat) {
                        iloc += be16(1) + be32(idatOf.getValue(item.id)) + be32(item.data.size)
                    } else {
                        val e = extentsOf.getValue(item.id)
                        iloc += be16(e.size)
                        for ((o, l) in e) iloc += be32(o) + be32(l)
                    }
                }
                var iinf = be16(items.size)
                for (item in items) {
                    iinf += fullBox("infe", 2, if (item.hidden) 1 else 0, be16(item.id) + be16(0) + item.type.encodeToByteArray() + byteArrayOf(0))
                }
                var ipco = ByteArray(0)
                var ipma = be32(items.size)
                var index = 0
                for (item in items) {
                    ipma += be16(item.id) + byteArrayOf(item.properties.size.toByte())
                    for ((p, essential) in item.properties) {
                        ipco += p
                        index++
                        ipma += byteArrayOf(((if (essential) 0x80 else 0) or index).toByte())
                    }
                }
                var iref = ByteArray(0)
                for ((type, from, to) in references) {
                    iref += box(type, be16(from.toInt()) + be16(to.size) + to.fold(ByteArray(0)) { a, t -> a + be16(t.toInt()) })
                }
                var children = fullBox("hdlr", 0, 0, be32(0) + "pict".encodeToByteArray() + ByteArray(12) + byteArrayOf(0))
                primary?.let { children += fullBox("pitm", 0, 0, be16(it)) }
                children += fullBox("iloc", 1, 0, iloc)
                children += fullBox("iinf", 0, 0, iinf)
                if (references.isNotEmpty()) children += fullBox("iref", 0, 0, iref)
                children += box("iprp", box("ipco", ipco) + fullBox("ipma", 0, 0, ipma))
                if (idat.isNotEmpty()) children += box("idat", idat)
                if (groups.isNotEmpty()) {
                    var grpl = ByteArray(0)
                    for ((n, g) in groups.withIndex()) {
                        grpl += fullBox(g.first, 0, 0, be32(100 + n) + be32(g.second.size) + g.second.fold(ByteArray(0)) { a, t -> a + be32(t.toInt()) })
                    }
                    children += box("grpl", grpl)
                }
                return fullBox("meta", 0, 0, children)
            }
            val size = meta(0).size
            val meta = meta(ftyp.size + size)
            var mdat = ByteArray(0)
            for (i in items.indices.filter { !items[it].idat }) for (k in pieces[i].indices.reversed()) mdat += pieces[i][k]
            return ftyp + meta + box("mdat", mdat)
        }
    }
}
