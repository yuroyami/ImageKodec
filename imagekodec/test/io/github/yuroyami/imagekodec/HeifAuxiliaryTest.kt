package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.AppleMakerNote
import io.github.yuroyami.imagekodec.codec.avif.Av1Codec
import io.github.yuroyami.imagekodec.codec.avif.AvifContainer
import io.github.yuroyami.imagekodec.codec.avif.AvifProperty
import io.github.yuroyami.imagekodec.codec.avif.HeifCodecs
import kotlin.math.pow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The auxiliary images (#154) and the gain maps (#153) of HEIF files built here: alpha and depth
 * items around the AV1 data of the libavif fixtures and the hashed tiles of [HashTileCodec], and
 * `tmap` items whose payloads are written field by field from the layout of ISO 21496-1 as libavif
 * reads it, since no tool here writes one (libavif 1.0.4 has no gain map support).
 */
@OptIn(ExperimentalHeifCodecApi::class)
class HeifAuxiliaryTest {

    @AfterTest
    fun forgetTheCodec() = HeifCodecs.reset()

    @Test
    fun anAlphaAndADepthItemAreListedWithTheirUrnsSizesAndSubtypes() {
        val file = AuxiliaryFiles.alphaAndDepth()
        val list = ImageKodec.auxiliaryImages(file)
        assertEquals(2, list.size, "$list")
        val (alpha, depth) = list
        assertEquals(AuxiliaryFiles.ALPHA, alpha.urn)
        assertEquals(24 to 16, alpha.width to alpha.height)
        assertEquals(8, alpha.bitDepth)
        assertEquals(0, alpha.subtype.size)
        assertEquals(AuxiliaryFiles.DEPTH, depth.urn)
        assertEquals(24 to 16, depth.width to depth.height)
        assertEquals(12, depth.bitDepth)
        assertContentEquals(AuxiliaryFiles.DEPTH_SUBTYPE, depth.subtype)
        // The listing decodes nothing, and the alpha still joins the picture.
        assertTrue(ImageKodec.probe(file).hasAlpha)
    }

    @Test
    fun theDepthDecodeEqualsTheDirectDecodeOfItsItem() {
        val file = AuxiliaryFiles.alphaAndDepth()
        val depth = ImageKodec.decodeAuxiliaryImage(file, 1)
        val mono = AvifContainer.parse(AvifFixtures.mono12).let { c -> c.items.getValue(c.primary!!) to c }
        val config = mono.first.property<AvifProperty.Av1Config>()!!
        val planes = assertNotNull(Av1Codec.open(config.payload).decode(mono.second.itemData(mono.first), null))
        assertEquals(12, planes.bitDepthLuma)
        assertEquals(1 to 24 * 16, depth.channels to depth.width * depth.height)
        for (y in 0 until 16) for (x in 0 until 24) {
            val v = planes.planes[0][y * planes.strides[0] + x].toLong()
            assertEquals(((v * 65535 + 2047) / 4095).toInt(), depth[x, y, 0], "($x, $y)")
        }
        // The alpha item is the alpha channel decode16 joins, an 8-bit full-range sample v as v * 257.
        val alpha = ImageKodec.decodeAuxiliaryImage(file, 0)
        val joined = ImageKodec.decode16(AvifFixtures.alpha)
        assertEquals(4, joined.channels)
        for (i in 0 until 24 * 16) assertEquals(joined.samples[i * 4 + 3], alpha.samples[i], "pixel $i")
        assertFailsWith<IllegalArgumentException> { ImageKodec.decodeAuxiliaryImage(file, 2) }
        assertFailsWith<IllegalArgumentException> { ImageKodec.decodeAuxiliaryImage(file, -1) }
    }

    @Test
    fun theLibavifAlphaFixtureListsItsAlpha() {
        val list = ImageKodec.auxiliaryImages(AvifFixtures.alpha)
        assertEquals(listOf(AuxiliaryFiles.ALPHA), list.map { it.urn })
        assertEquals(listOf(24 to 16), list.map { it.width to it.height })
    }

    @Test
    fun aDepthOfTenBitsKeepsItsSamples() {
        ImageKodec.registerHeifCodec(HashTileCodec)
        val file = HeifBuilder().apply {
            add(1, "tsg1", byteArrayOf(0, 1), HashTileCodec.properties(20, 12, format = 1, depth = 8, fullRange = true))
            add(2, "tsg1", byteArrayOf(0, 2), HashTileCodec.properties(10, 6, format = 0, depth = 10, fullRange = false) + AuxiliaryFiles.auxC(AuxiliaryFiles.DEPTH_CICP), hidden = true)
            references += Triple("auxl", 2L, longArrayOf(1))
        }.build()
        val entry = ImageKodec.auxiliaryImages(file).single()
        assertEquals(AuxiliaryFiles.DEPTH_CICP, entry.urn)
        assertEquals(Triple(10, 6, 10), Triple(entry.width, entry.height, entry.bitDepth))
        val planes = assertNotNull(HashTileCodec.open(HashTileCodec.properties(10, 6, 0, 10, false)[0].first.copyOfRange(8, 16)).decode(byteArrayOf(0, 2), null))
        val bitmap = ImageKodec.decodeAuxiliaryImage(file, 0)
        for (y in 0 until 6) for (x in 0 until 10) {
            val v = planes.planes[0][y * planes.strides[0] + x].toLong()
            // Limited range is not stretched: v * 65535 / 1023, rounded.
            assertEquals(((v * 65535 + 511) / 1023).toInt(), bitmap[x, y, 0], "($x, $y)")
        }
    }

    @Test
    fun filesWithoutAuxiliaryImagesOrGainMapsGiveNothing() {
        assertEquals(emptyList(), ImageKodec.auxiliaryImages(AvifFixtures.plain))
        assertNull(ImageKodec.decodeGainMap(AvifFixtures.plain))
        assertNull(ImageKodec.decodeGainMap(AvifFixtures.alpha))
        val png = ImageKodec.encodePng(KiteBitmap(2, 2, IntArray(4) { -1 }))
        assertEquals(emptyList(), ImageKodec.auxiliaryImages(png))
        assertNull(ImageKodec.decodeGainMap(png))
        assertFailsWith<IllegalArgumentException> { ImageKodec.decodeAuxiliaryImage(png, 0) }
        assertEquals(emptyList(), ImageKodec.auxiliaryImages(ByteArray(3)))
        assertNull(ImageKodec.decodeGainMap(ByteArray(3)))
        assertNull(ImageKodec.readMetadata(AuxiliaryFiles.toneMapped()).gainMap, "readMetadata decodes no picture")
    }

    @Test
    fun aToneMappedItemGivesItsGainMapAndParameters() {
        val payload = AuxiliaryFiles.metadata(multichannel = true, useBase = true)
        val gm = assertNotNull(ImageKodec.decodeGainMap(AuxiliaryFiles.toneMapped(payload)))
        assertEquals(HeifGainMap.Form.Iso21496, gm.form)
        assertNull(gm.appleMakerNote33)
        assertNull(gm.appleHeadroom)
        val m = assertNotNull(gm.metadata)
        assertEquals(Triple(0, 0, 0), Triple(m.version, m.minimumVersion, m.writerVersion))
        assertEquals(3, m.channelCount)
        assertTrue(m.useBaseColorSpace)
        assertEquals(0.0, m.baseHdrHeadroom)
        assertEquals(2.5, m.alternateHdrHeadroom)
        assertContentEquals(doubleArrayOf(-0.5, -0.25, -0.125), m.gainMapMin)
        assertContentEquals(doubleArrayOf(3.0, 2.75, 2.5), m.gainMapMax)
        assertContentEquals(doubleArrayOf(1.0, 0.5, 2.0), m.gamma)
        assertContentEquals(doubleArrayOf(1.0 / 64, 2.0 / 64, 3.0 / 64), m.baseOffset)
        assertContentEquals(doubleArrayOf(-1.0 / 64, -2.0 / 64, -3.0 / 64), m.alternateOffset)
        assertContentEquals(payload, m.raw)
        // The gain map is the mono12 picture, one channel, and the shown image is still the base image.
        assertEquals(Triple(24, 16, 1), Triple(gm.bitmap.width, gm.bitmap.height, gm.bitmap.channels))
        val mono = ImageKodec.decode16(AvifFixtures.mono12)
        for (i in 0 until 24 * 16) assertEquals(mono.samples[i * 3], gm.bitmap.samples[i], "pixel $i")
        assertContentEquals(ImageKodec.decode(AvifFixtures.plain).argb, ImageKodec.decode(AuxiliaryFiles.toneMapped(payload)).argb)

        val single = assertNotNull(ImageKodec.decodeGainMap(AuxiliaryFiles.toneMapped(AuxiliaryFiles.metadata(multichannel = false, useBase = false)))?.metadata)
        assertEquals(1, single.channelCount)
        assertEquals(false, single.useBaseColorSpace)
        assertContentEquals(doubleArrayOf(-0.5), single.gainMapMin)
    }

    @Test
    fun aGainMapInColourIsRgbAndOneInGrayIsOneChannel() {
        ImageKodec.registerHeifCodec(HashTileCodec)
        fun file(format: Int) = HeifBuilder().apply {
            add(1, "tmap", AuxiliaryFiles.metadata(multichannel = format != 0, useBase = false), listOf(HashTileCodec.properties(20, 12, 1, 8, true)[1]))
            add(2, "tsg1", byteArrayOf(0, 1), HashTileCodec.properties(20, 12, format = 1, depth = 8, fullRange = true), hidden = true)
            // An identity matrix, so red is Cr, green Y and blue Cb, each v * 257 at full range.
            add(3, "tsg1", byteArrayOf(0, 7), HashTileCodec.properties(10, 6, format = format, depth = 8, fullRange = true, matrix = 0), hidden = true)
            references += Triple("dimg", 1L, longArrayOf(2, 3))
        }.build()
        val planes = HashTileCodec.open(HashTileCodec.properties(10, 6, 3, 8, true, matrix = 0)[0].first.copyOfRange(8, 16)).decode(byteArrayOf(0, 7), null)!!
        val rgb = assertNotNull(ImageKodec.decodeGainMap(file(3))).bitmap
        assertEquals(3, rgb.channels)
        for (y in 0 until 6) for (x in 0 until 10) {
            fun p(c: Int) = (planes.planes[c][y * planes.strides[c] + x].toInt() and 0xFF) * 257
            assertEquals(listOf(p(2), p(0), p(1)), List(3) { rgb[x, y, it] }, "($x, $y)")
        }
        val gray = assertNotNull(ImageKodec.decodeGainMap(file(0))).bitmap
        assertEquals(1, gray.channels)
        for (y in 0 until 6) for (x in 0 until 10) {
            assertEquals((planes.planes[0][y * planes.strides[0] + x].toInt() and 0xFF) * 257, gray[x, y, 0], "($x, $y)")
        }
    }

    @Test
    fun aToneMappedItemBesideAPrimaryBaseImageIsFound() {
        // As in an altr group whose preferred member is the tmap, with the base image as the primary item.
        val file = HeifBuilder().apply {
            primary = 2
            add(1, "tmap", AuxiliaryFiles.metadata(multichannel = false, useBase = false), listOf(AuxiliaryFiles.ispe(24, 16)))
            add(2, "av01", AuxiliaryFiles.plainData, AuxiliaryFiles.plainProperties())
            add(3, "av01", AuxiliaryFiles.monoData, AuxiliaryFiles.monoProperties(), hidden = true)
            references += Triple("dimg", 1L, longArrayOf(2, 3))
            groups += "altr" to longArrayOf(1, 2)
        }.build()
        val gm = assertNotNull(ImageKodec.decodeGainMap(file))
        assertEquals(24 to 16, gm.bitmap.width to gm.bitmap.height)
    }

    /** An EXIF block, big-endian, whose EXIF IFD holds an Apple MakerNote with tags 33 and 48 as FLOATs. */
    private fun appleExif(maker33: Float, maker48: Float): ByteArray {
        fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
        fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        fun entry(tag: Int, type: Int, count: Int, value: Int) = be16(tag) + be16(type) + be32(count) + be32(value)
        val note = "Apple iOS".encodeToByteArray() + byteArrayOf(0, 0, 1) + "MM".encodeToByteArray() + be16(2) +
            entry(0x21, 11, 1, maker33.toRawBits()) + entry(0x30, 11, 1, maker48.toRawBits()) + be32(0)
        return "MM".encodeToByteArray() + be16(42) + be32(8) +
            be16(1) + entry(0x8769, 4, 1, 26) + be32(0) +
            be16(1) + entry(0x927C, 7, note.size, 44) + be32(0) + note
    }

    @Test
    fun anAppleGainMapGivesItsMakerNoteTagsAndHeadroom() {
        ImageKodec.registerHeifCodec(HashTileCodec)
        val file = HeifBuilder("heic", listOf("mif1", "heic")).apply {
            add(1, "tsg1", byteArrayOf(0, 1), HashTileCodec.properties(20, 12, format = 1, depth = 8, fullRange = true))
            add(2, "tsg1", byteArrayOf(0, 2), HashTileCodec.properties(10, 6, format = 0, depth = 8, fullRange = true) + AuxiliaryFiles.auxC("urn:com:apple:photo:2020:aux:hdrgainmap"), hidden = true)
            add(3, "Exif", ByteArray(4) + appleExif(1.25f, 0.005f), emptyList(), hidden = true)
            references += Triple("auxl", 2L, longArrayOf(1))
            references += Triple("cdsc", 3L, longArrayOf(1))
        }.build()
        // 0.005 as the 32-bit float the EXIF stores, widened. Kotlin/JS keeps a Float literal at
        // 64 bits, so `0.005f.toDouble()` is 0.005 there and the stored value is not.
        val tag48 = 0.004999999888241291
        val gm = assertNotNull(ImageKodec.decodeGainMap(file))
        assertEquals(HeifGainMap.Form.Apple, gm.form)
        assertNull(gm.metadata)
        assertEquals(1.25, gm.appleMakerNote33)
        assertEquals(tag48, gm.appleMakerNote48)
        assertEquals(2.0.pow(-70.0 * tag48 + 3.0), gm.appleHeadroom)
        assertContentEquals(gm.bitmap.samples, ImageKodec.decodeAuxiliaryImage(file, 0).samples)
        // The other three lines of Apple's formula, and its floor of no stops.
        assertEquals(2.0.pow(-0.101 * 0.5 + 1.601), AppleMakerNote.headroom(0.5, 0.5))
        assertEquals(2.0.pow(-20.0 * 0.01 + 1.8), AppleMakerNote.headroom(0.5, 0.01))
        assertEquals(2.0.pow(-0.303 * 2.0 + 2.303), AppleMakerNote.headroom(1.0, 2.0))
        assertEquals(1.0, AppleMakerNote.headroom(1.0, 100.0))
        // A block cut anywhere gives what it can read, never an exception.
        val exif = appleExif(1.25f, 0.005f)
        for (cut in 0 until exif.size) AppleMakerNote.tags(exif.copyOf(cut), setOf(33, 48))
        assertEquals(mapOf(33 to 1.25, 48 to tag48), AppleMakerNote.tags(exif, setOf(33, 48)))
    }

    @Test
    fun theMetadataVersionsAreHeldAsLibavifHoldsThem() {
        val v0 = AuxiliaryFiles.metadata(multichannel = false, useBase = false)
        // A later writer may add fields that a reader of version 0 skips; version 0 may not.
        val later = v0.copyOf().also { it[4] = 1 } + byteArrayOf(9, 9)
        assertEquals(1, ImageKodec.decodeGainMap(AuxiliaryFiles.toneMapped(later))!!.metadata!!.writerVersion)
        assertFailsWith<ImageDecodeException> { ImageKodec.decodeGainMap(AuxiliaryFiles.toneMapped(v0 + byteArrayOf(9))) }
        val newer = v0.copyOf().also { it[2] = 1; it[4] = 1 }
        assertFailsWith<UnsupportedImageException> { ImageKodec.decodeGainMap(AuxiliaryFiles.toneMapped(newer)) }
        assertFailsWith<UnsupportedImageException> { ImageKodec.decodeGainMap(AuxiliaryFiles.toneMapped(v0.copyOf().also { it[0] = 1 })) }
        // A zero denominator: the base headroom's.
        assertFailsWith<ImageDecodeException> { ImageKodec.decodeGainMap(AuxiliaryFiles.toneMapped(v0.copyOf().also { for (k in 10..13) it[k] = 0 })) }
    }

    /** Every outcome of a hostile file is a result or an [ImageDecodeException]. */
    private fun clean(label: String, body: () -> Unit) {
        try {
            body()
        } catch (_: ImageDecodeException) {
        } catch (t: Throwable) {
            throw AssertionError("$label leaked ${t::class.simpleName}: ${t.message}", t)
        }
    }

    @Test
    fun hostileFilesFailCleanly() {
        val v0 = AuxiliaryFiles.metadata(multichannel = true, useBase = false)
        for (cut in 0 until v0.size) {
            assertFailsWith<ImageDecodeException>("cut at $cut") { ImageKodec.decodeGainMap(AuxiliaryFiles.toneMapped(v0.copyOf(cut))) }
        }
        // A tmap with one input.
        val oneInput = HeifBuilder().apply {
            add(1, "tmap", v0, listOf(AuxiliaryFiles.ispe(24, 16)))
            add(2, "av01", AuxiliaryFiles.plainData, AuxiliaryFiles.plainProperties(), hidden = true)
            references += Triple("dimg", 1L, longArrayOf(2))
        }.build()
        assertFailsWith<ImageDecodeException> { ImageKodec.decodeGainMap(oneInput) }
        // A tmap that is its own gain map, and one whose gain map is missing.
        for (inputs in listOf(longArrayOf(2, 1), longArrayOf(2, 9))) {
            val file = HeifBuilder().apply {
                add(1, "tmap", v0, listOf(AuxiliaryFiles.ispe(24, 16)))
                add(2, "av01", AuxiliaryFiles.plainData, AuxiliaryFiles.plainProperties(), hidden = true)
                references += Triple("dimg", 1L, inputs)
            }.build()
            assertFailsWith<ImageDecodeException>(inputs.toList().toString()) { ImageKodec.decodeGainMap(file) }
        }
        // An auxl from the primary item to itself is no auxiliary image.
        val self = HeifBuilder().apply {
            add(1, "av01", AuxiliaryFiles.plainData, AuxiliaryFiles.plainProperties() + AuxiliaryFiles.auxC(AuxiliaryFiles.ALPHA))
            references += Triple("auxl", 1L, longArrayOf(1))
        }.build()
        assertEquals(emptyList(), ImageKodec.auxiliaryImages(self))
        clean("self decode") { ImageKodec.decode(self) }
        clean("self gain map") { ImageKodec.decodeGainMap(self) }
        // An auxiliary identity item derived from itself, and an Apple gain map with no data.
        val loop = HeifBuilder().apply {
            add(1, "av01", AuxiliaryFiles.plainData, AuxiliaryFiles.plainProperties())
            add(2, "iden", ByteArray(0), listOf(AuxiliaryFiles.ispe(24, 16), AuxiliaryFiles.auxC(AuxiliaryFiles.DEPTH)), hidden = true)
            add(3, "av01", ByteArray(0), AuxiliaryFiles.monoProperties() + AuxiliaryFiles.auxC("urn:com:apple:photo:2020:aux:hdrgainmap"), hidden = true)
            references += Triple("auxl", 2L, longArrayOf(1))
            references += Triple("auxl", 3L, longArrayOf(1))
            references += Triple("dimg", 2L, longArrayOf(2))
        }.build()
        // The size of the identity item cannot be read, so neither can the list.
        assertFailsWith<ImageDecodeException> { ImageKodec.auxiliaryImages(loop) }
        assertFailsWith<ImageDecodeException> { ImageKodec.decodeAuxiliaryImage(loop, 0) }
        assertFailsWith<ImageDecodeException> { ImageKodec.decodeAuxiliaryImage(loop, 1) }
        assertFailsWith<ImageDecodeException> { ImageKodec.decodeGainMap(loop) }
        // The file cut or damaged across its length fails cleanly; FuzzTest mutates it further.
        val whole = AuxiliaryFiles.everything()
        for (at in whole.indices step maxOf(1, whole.size / 200)) {
            val flipped = whole.copyOf().also { it[at] = (it[at].toInt() xor 0x5A).toByte() }
            for ((label, bytes) in listOf("flip@$at" to flipped, "cut@$at" to whole.copyOf(at))) {
                var listed = 0
                clean("$label list") { listed = ImageKodec.auxiliaryImages(bytes).size }
                for (i in 0 until listed) clean("$label aux $i") { ImageKodec.decodeAuxiliaryImage(bytes, i) }
                clean("$label gain map") { ImageKodec.decodeGainMap(bytes) }
            }
        }
    }
}

/** HEIF files for [HeifAuxiliaryTest] and the `FuzzTest` corpus, around the AV1 data of the libavif fixtures. */
internal object AuxiliaryFiles {
    const val ALPHA = "urn:mpeg:mpegB:cicp:systems:auxiliary:alpha"
    const val DEPTH = "urn:mpeg:hevc:2015:auxid:2"
    const val DEPTH_CICP = "urn:mpeg:mpegB:cicp:systems:auxiliary:depth"

    /** Subtype bytes after the URN, shaped as an Apple depth image's: a length, then a SEI NAL unit. */
    val DEPTH_SUBTYPE = byteArrayOf(0, 0, 0, 5, 0x4E, 1, -79, 9, 53)

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray) = be32(8 + payload.size) + type.encodeToByteArray() + payload

    fun ispe(w: Int, h: Int) = box("ispe", ByteArray(4) + be32(w) + be32(h)) to false
    fun auxC(urn: String, subtype: ByteArray = ByteArray(0)) = box("auxC", ByteArray(4) + urn.encodeToByteArray() + byteArrayOf(0) + subtype) to false

    private fun item(fixture: ByteArray, primary: Boolean): Pair<ByteArray, AvifProperty.Av1Config> {
        val c = AvifContainer.parse(fixture)
        val item = if (primary) c.items.getValue(c.primary!!) else c.items.values.first { it.id != c.primary }
        return c.itemData(item) to item.property<AvifProperty.Av1Config>()!!
    }

    private fun av1C(c: AvifProperty.Av1Config) = box("av1C", c.payload) to true
    private fun nclx(p: Int, t: Int, m: Int, full: Boolean) =
        box("colr", "nclx".encodeToByteArray() + byteArrayOf(0, p.toByte(), 0, t.toByte(), 0, m.toByte(), if (full) 0x80.toByte() else 0)) to false

    private val plain = item(AvifFixtures.plain, primary = true)
    private val mono = item(AvifFixtures.mono12, primary = true)
    private val alpha = item(AvifFixtures.alpha, primary = false)
    private val alphaColor = item(AvifFixtures.alpha, primary = true)

    val plainData: ByteArray get() = plain.first
    val monoData: ByteArray get() = mono.first
    fun plainProperties() = listOf(av1C(plain.second), ispe(24, 16), nclx(1, 13, 6, true))
    fun monoProperties() = listOf(av1C(mono.second), ispe(24, 16))

    /** The alpha fixture's card and alpha, with the mono12 picture as a depth image. */
    fun alphaAndDepth(): ByteArray = HeifBuilder().apply {
        add(1, "av01", alphaColor.first, listOf(av1C(alphaColor.second), ispe(24, 16), nclx(1, 13, 6, true)))
        add(2, "av01", alpha.first, listOf(av1C(alpha.second), ispe(24, 16), auxC(ALPHA)), hidden = true)
        add(3, "av01", mono.first, monoProperties() + auxC(DEPTH, DEPTH_SUBTYPE), hidden = true)
        references += Triple("auxl", 2L, longArrayOf(1))
        references += Triple("auxl", 3L, longArrayOf(1))
    }.build()

    /**
     * A `tmap` payload, field by field: version 0, minimum version 0, writer version 0, the flags,
     * base headroom 0/1, alternate headroom 5/2, and for each channel c from 0 the gain map minimum
     * -1/2^(c+1), maximum (12-c)/4, gamma 2/2, 1/2 or 4/2, base offset (c+1)/64 and alternate
     * offset -(c+1)/64.
     */
    fun metadata(multichannel: Boolean, useBase: Boolean): ByteArray {
        var out = byteArrayOf(0, 0, 0, 0, 0, ((if (multichannel) 0x80 else 0) or (if (useBase) 0x40 else 0)).toByte())
        out += be32(0) + be32(1) + be32(5) + be32(2)
        for (c in 0 until (if (multichannel) 3 else 1)) {
            out += be32(-1) + be32(2 shl c)
            out += be32(12 - c) + be32(4)
            out += be32(intArrayOf(2, 1, 4)[c]) + be32(2)
            out += be32(c + 1) + be32(64)
            out += be32(-(c + 1)) + be32(64)
        }
        return out
    }

    /** A primary `tmap` over the plain card, with the mono12 picture as its gain map. */
    fun toneMapped(payload: ByteArray = metadata(multichannel = true, useBase = false)): ByteArray = HeifBuilder().apply {
        add(1, "tmap", payload, listOf(ispe(24, 16)))
        add(2, "av01", plain.first, plainProperties(), hidden = true)
        add(3, "av01", mono.first, monoProperties(), hidden = true)
        references += Triple("dimg", 1L, longArrayOf(2, 3))
    }.build()

    /** A primary `tmap` whose base image has an alpha and a depth item, for the fuzz corpus. */
    fun everything(): ByteArray = HeifBuilder().apply {
        add(1, "tmap", metadata(multichannel = true, useBase = true), listOf(ispe(24, 16)))
        add(2, "av01", alphaColor.first, listOf(av1C(alphaColor.second), ispe(24, 16), nclx(1, 13, 6, true)), hidden = true)
        add(3, "av01", mono.first, monoProperties(), hidden = true)
        add(4, "av01", alpha.first, listOf(av1C(alpha.second), ispe(24, 16), auxC(ALPHA)), hidden = true)
        add(5, "av01", mono.first, monoProperties() + auxC(DEPTH, DEPTH_SUBTYPE), hidden = true)
        references += Triple("dimg", 1L, longArrayOf(2, 3))
        references += Triple("auxl", 4L, longArrayOf(2))
        references += Triple("auxl", 5L, longArrayOf(1))
    }.build()
}
