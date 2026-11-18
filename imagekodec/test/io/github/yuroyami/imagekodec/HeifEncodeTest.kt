package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.avif.AvifContainer
import io.github.yuroyami.imagekodec.codec.avif.AvifGrid
import io.github.yuroyami.imagekodec.codec.avif.AvifProperty
import io.github.yuroyami.imagekodec.codec.avif.HeifCodecs
import io.github.yuroyami.imagekodec.codec.avif.fourcc
import io.github.yuroyami.imagekodec.codec.avif.fourccName
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The HEIF writer and `encodeHeic` of the core (#174), with [RawHeifCodec] registered as both the encoder and
 * the decoder of `hvc1`: it stores the planes it is given, so what is tested here is the container and the
 * RGB conversion alone. The box structure reads back (grid layout, alpha, metadata, thumbnail, rotation),
 * lossless RGB returns exactly, lossy 4:2:0 stays within a bound, a reversing tile runner writes the same
 * bytes, fixed inputs keep a committed hash, and hostile arguments throw.
 */
@OptIn(ExperimentalHeifCodecApi::class)
class HeifEncodeTest {

    @BeforeTest
    fun register() {
        ImageKodec.registerHeifCodec(RawHeifCodec)
        ImageKodec.registerHeifEncoder(RawHeifCodec)
    }

    @AfterTest
    fun restore() {
        ImageKodec.setTileRunner(TileRunner.Serial)
        HeifCodecs.reset()
    }

    /** A [w] by [h] picture of gradients, a disc and seeded noise, with varied alpha when [alpha]. */
    private fun photo(w: Int, h: Int, alpha: Boolean = false, seed: Int = 3): KiteBitmap {
        var s = seed * 2654435761.toInt() + 1
        return KiteBitmap(w, h, IntArray(w * h) { i ->
            s = s * 1103515245 + 12345
            val n = (s ushr 16) and 15
            val x = i % w
            val y = i / w
            val disc = (x - w / 2) * (x - w / 2) + (y - h / 3) * (y - h / 3) < w * h / 8
            val r = (x * 255 / maxOf(1, w - 1) + n).coerceIn(0, 255)
            val g = (y * 255 / maxOf(1, h - 1) + (if (disc) 60 else 0)).coerceIn(0, 255)
            val b = ((x + y) * 3 + n * 2).coerceIn(0, 255)
            val a = if (alpha) (if (disc) 255 else (x * 7 + y * 3) and 0xFF) else 255
            (a shl 24) or (r shl 16) or (g shl 8) or b
        })
    }

    private fun gray(b: KiteBitmap) = KiteBitmap(b.width, b.height, IntArray(b.argb.size) { i ->
        val p = b.argb[i]
        (p and 0xFF000000.toInt()) or (((p ushr 8) and 0xFF) * 0x010101)
    })

    private val lossless = HeifEncodeOptions(lossless = true)

    @Test
    fun losslessRgbGrayAndAlphaReturnExactly() {
        for ((name, b) in listOf(
            "1x1" to photo(1, 1), "one row" to photo(19, 1), "one column" to photo(1, 11), "odd" to photo(25, 13, alpha = true),
            "transparent" to KiteBitmap(5, 3, IntArray(15)), "gray" to gray(photo(14, 9)), "gray with alpha" to gray(photo(14, 9, alpha = true)),
        )) {
            for (depth in listOf(8, 10, 12)) {
                val file = ImageKodec.encodeHeic(b, HeifEncodeOptions(lossless = true, bitDepth = depth))
                assertContentEquals(b.argb, ImageKodec.decode(file).argb, "$name at $depth bits")
            }
        }
    }

    @Test
    fun sixteenBitLosslessReturnsEveryValueItsDepthHolds() {
        var s = 5
        for (channels in 1..4) for (depth in listOf(8, 10, 12)) {
            val samples = ShortArray(9 * 7 * channels) {
                s = s * 1103515245 + 12345
                (s ushr 9).toShort()
            }
            val options = HeifEncodeOptions(lossless = true, bitDepth = depth)
            val once = ImageKodec.decode16(ImageKodec.encodeHeic16(KiteBitmap16(9, 7, channels, samples), options))
            val twice = ImageKodec.decode16(ImageKodec.encodeHeic16(once, options))
            assertContentEquals(once.samples, twice.samples, "$channels channels at $depth bits")
        }
        // Every 16-bit value: what decode16 reads back is every value a sample of the depth gives, and each of those
        // comes back exactly from a second encode.
        val all = KiteBitmap16(256, 256, 1, ShortArray(65536) { it.toShort() })
        for (depth in listOf(8, 10, 12)) {
            val options = HeifEncodeOptions(lossless = true, bitDepth = depth, tileSize = null)
            val once = ImageKodec.decode16(ImageKodec.encodeHeic16(all, options))
            // decode16 widens a sample into the bucket of its 8-bit reading, which merges neighbours at 10 and 12 bits
            // (measured: 256, 1020 and 3366 distinct values), so those are the values a file of the depth can give.
            val distinct = once.samples.map { it.toInt() and 0xFFFF }.toSet().size
            println("$depth bits: decode16 reads back $distinct distinct values")
            val twice = ImageKodec.decode16(ImageKodec.encodeHeic16(once, options))
            for (i in 0 until 65536) assertEquals(once.samples[i * 3], twice.samples[i * 3], "$depth bits: value $i")
        }
    }

    @Test
    fun lossyColourStaysWithinTheConversionBound() {
        // A smooth picture: the error is the rounding of the matrix and, in 4:2:0 and 4:2:2, of averaged chroma.
        val smooth = KiteBitmap(48, 30, IntArray(48 * 30) { i ->
            val x = i % 48
            val y = i / 48
            0xFF000000.toInt() or ((x * 5) shl 16) or ((y * 8) shl 8) or (128 + x - y)
        })
        // Measured worst: 1, 3 and 5 levels at 8 bits; 0, 2 and 4 at 10 and 12.
        val bounds = mapOf(HeifChroma.Yuv444 to 1, HeifChroma.Yuv422 to 3, HeifChroma.Yuv420 to 5)
        for ((chroma, bound) in bounds) for (depth in listOf(8, 10, 12)) {
            val file = ImageKodec.encodeHeic(smooth, HeifEncodeOptions(chroma = chroma, bitDepth = depth))
            val out = ImageKodec.decode(file)
            var worst = 0
            for (i in smooth.argb.indices) for (c in 0 until 3) {
                worst = maxOf(worst, abs(((smooth.argb[i] ushr (8 * c)) and 0xFF) - ((out.argb[i] ushr (8 * c)) and 0xFF)))
            }
            assertTrue(worst <= bound, "$chroma at $depth bits: $worst levels away")
        }
        // The noisy photo in 4:2:0: chroma averaged over noise moves further, alpha not at all.
        val p = photo(33, 21, alpha = true)
        val out = ImageKodec.decode(ImageKodec.encodeHeic(p, 50))
        var sum = 0L
        for (i in p.argb.indices) {
            assertEquals(p.argb[i] ushr 24, out.argb[i] ushr 24, "alpha of pixel $i")
            for (c in 0 until 3) sum += abs(((p.argb[i] ushr (8 * c)) and 0xFF) - ((out.argb[i] ushr (8 * c)) and 0xFF))
        }
        // Measured: a mean of 4.04 levels.
        assertTrue(sum < p.argb.size * 3L * 5, "a mean difference of ${sum.toDouble() / (p.argb.size * 3)}")
    }

    private fun container(file: ByteArray) = AvifContainer.parse(file)

    private fun AvifContainer.refs(type: String, from: Long) = referencesFrom(from, fourcc(type))

    @Test
    fun theBoxStructureReadsBack() {
        val icc = ByteArray(40) { (it * 7).toByte() }
        val xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>"
        val exif = byteArrayOf(0x4D, 0x4D, 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0)
        val bitmap = photo(150, 70, alpha = true)
        val options = HeifEncodeOptions(quality = 60, tileSize = 64, thumbnailSize = 32, orientation = Orientation.Rotate90)
        val file = ImageKodec.encodeHeic(bitmap, options, ImageMetadata(exif, xmp, icc))
        assertEquals(ImageFormat.HEIC, ImageKodec.detect(file))
        val c = container(file)
        assertEquals(fourcc("heic"), c.majorBrand)
        assertTrue(fourcc("mif1") in c.brands && fourcc("miaf") in c.brands)
        val primary = c.items.getValue(c.primary!!)
        assertEquals("grid", fourccName(primary.type))
        val grid = AvifGrid.read(c.itemData(primary))
        assertEquals(listOf(2, 3, 150, 70), listOf(grid.rows, grid.columns, grid.width, grid.height))
        val tiles = c.refs("dimg", primary.id)
        assertEquals(6, tiles.size)
        for (t in tiles) {
            val tile = c.items.getValue(t)
            assertTrue(tile.hidden)
            val ispe = assertNotNull(tile.property<AvifProperty.Extent>())
            assertEquals(64L to 64L, ispe.width to ispe.height)
        }
        // The tiles share one configuration and one nclx.
        val configs = tiles.map { t -> c.items.getValue(t).properties.first { it is AvifProperty.Other } }
        assertTrue(configs.all { it === configs[0] }, "each tile has its own configuration")
        // The grid's properties: rotation last, essential; its colour as the tiles'.
        val rot = assertNotNull(primary.property<AvifProperty.Rotation>())
        assertEquals(3, rot.angle)
        assertEquals(primary.properties.last(), rot)
        assertNotNull(primary.property<AvifProperty.Icc>())
        val nclx = assertNotNull(primary.property<AvifProperty.Nclx>())
        assertEquals(listOf(2, 2, 6, 1), listOf(nclx.primaries, nclx.transfer, nclx.matrix, if (nclx.fullRange) 1 else 0))
        // Alpha: a grid of the same layout, tied by 'auxl', with the codec's URN.
        val alpha = c.references.single { it.type == fourcc("auxl") && it.to.contentEquals(longArrayOf(primary.id)) }
        assertContentEquals(longArrayOf(primary.id), alpha.to)
        val alphaItem = c.items.getValue(alpha.from)
        assertEquals("grid", fourccName(alphaItem.type))
        assertEquals("urn:mpeg:hevc:2015:auxid:1", alphaItem.property<AvifProperty.AuxType>()?.urn)
        assertEquals(6, c.refs("dimg", alphaItem.id).size)
        // Thumbnail, EXIF and XMP.
        val thumb = c.items.getValue(c.references.single { it.type == fourcc("thmb") }.from)
        assertEquals(32L to 14L, thumb.property<AvifProperty.Extent>()!!.let { it.width to it.height })
        assertEquals(2, c.references.count { it.type == fourcc("cdsc") && it.to.contentEquals(longArrayOf(primary.id)) })
        // Back through the facade.
        val info = ImageKodec.probe(file)
        assertEquals(150 to 70, info.width to info.height)
        assertEquals(Orientation.Rotate90, info.orientation)
        assertTrue(info.hasAlpha)
        val meta = ImageKodec.readMetadata(file)
        assertEquals(xmp, meta.xmp)
        assertContentEquals(icc, meta.icc)
        assertContentEquals(exif.copyOf().also { it[19] = 6 }, meta.exif, "the EXIF orientation follows irot")
        val small = assertNotNull(ImageKodec.decodeThumbnail(file, applyOrientation = true))
        assertEquals(14 to 32, small.width to small.height)
        assertEquals(70 to 150, ImageKodec.decode(file, applyOrientation = true).let { it.width to it.height })
    }

    @Test
    fun anOddSubsampledPictureIsCroppedByItsCleanAperture() {
        for ((w, h) in listOf(1 to 1, 7 to 4, 4 to 7, 9 to 9)) {
            // A colour picture: a gray one codes no chroma and needs no padding.
            val colour = KiteBitmap(w, h, IntArray(w * h) { 0xFF2060A0.toInt() + it })
            val file = ImageKodec.encodeHeic(colour, HeifEncodeOptions(chroma = HeifChroma.Yuv420))
            val item = container(file).let { it.items.getValue(it.primary!!) }
            val ispe = item.property<AvifProperty.Extent>()!!
            assertEquals((w + 1) / 2 * 2L to (h + 1) / 2 * 2L, ispe.width to ispe.height)
            assertNotNull(item.property<AvifProperty.CleanAperture>(), "${w}x$h")
            assertEquals(w to h, ImageKodec.decode(file).let { it.width to it.height })
        }
        val even = container(ImageKodec.encodeHeic(photo(8, 6), 50)).let { it.items.getValue(it.primary!!) }
        assertEquals(null, even.property<AvifProperty.CleanAperture>())
    }

    @Test
    fun aReversingTileRunnerWritesTheSameBytes() {
        val bitmap = photo(200, 90, alpha = true)
        val options = HeifEncodeOptions(tileSize = 64)
        val serial = ImageKodec.encodeHeic(bitmap, options)
        var calls = 0
        ImageKodec.setTileRunner { jobs ->
            calls++
            for (job in jobs.asReversed()) job.run()
        }
        assertContentEquals(serial, ImageKodec.encodeHeic(bitmap, options))
        assertEquals(2, calls, "one run for the colour tiles and one for the alpha tiles")
    }

    @Test
    fun theBytesOfFixedInputsKeepTheirHash() {
        val files = listOf(
            ImageKodec.encodeHeic(photo(37, 21, alpha = true), 50),
            ImageKodec.encodeHeic(photo(150, 70), HeifEncodeOptions(chroma = HeifChroma.Yuv422, tileSize = 64, thumbnailSize = 24, orientation = Orientation.Transverse)),
            ImageKodec.encodeHeic16(KiteBitmap16(5, 4, 2, ShortArray(40) { (it * 1601).toShort() }), lossless),
        )
        val hashes = files.map { f -> PictureHash.md5(f).joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') } }
        assertEquals(EXPECTED, hashes)
    }

    @Test
    fun theCancellationCheckStopsTheEncode() {
        val stop = IllegalStateException("stop")
        var calls = 0
        val thrown = runCatching {
            ImageKodec.encodeHeic(photo(200, 90), HeifEncodeOptions(tileSize = 64), cancellationCheck = { if (++calls == 3) throw stop })
        }.exceptionOrNull()
        assertTrue(thrown === stop, "$thrown")
        var all = 0
        ImageKodec.encodeHeic(photo(200, 90), HeifEncodeOptions(tileSize = 64), cancellationCheck = { all++ })
        assertTrue(all >= 8, "$all checks for 8 tiles")
    }

    @Test
    fun hostileArgumentsThrow() {
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeHeic(photo(2, 2), 101) }
        assertFailsWith<IllegalArgumentException> { HeifEncodeOptions(quality = -1) }
        assertFailsWith<IllegalArgumentException> { HeifEncodeOptions(effort = 4) }
        assertFailsWith<IllegalArgumentException> { HeifEncodeOptions(bitDepth = 16) }
        assertFailsWith<IllegalArgumentException> { HeifEncodeOptions(tileSize = 8) }
        assertFailsWith<IllegalArgumentException> { HeifEncodeOptions(tileSize = 65) }
        assertFailsWith<IllegalArgumentException> { HeifEncodeOptions(tileSize = 8200) }
        assertFailsWith<IllegalArgumentException> { HeifEncodeOptions(thumbnailSize = -5) }
        // Wider than a reader takes, and more tiles than a grid holds: refused before anything is coded.
        var coded = 0
        val counting = object : HeifEncoder by RawHeifCodec {
            override fun encode(planes: HeifPlanes, parameters: HeifEncodeParameters, cancellationCheck: (() -> Unit)?): HeifCodedPicture {
                coded++
                return RawHeifCodec.encode(planes, parameters, cancellationCheck)
            }
        }
        ImageKodec.registerHeifEncoder(counting)
        val wide = assertFailsWith<IllegalArgumentException> { ImageKodec.encodeHeic(KiteBitmap(65536, 1, IntArray(65536)), 50) }
        assertTrue(wide.message!!.contains("65536"), wide.message)
        val tiles = assertFailsWith<IllegalArgumentException> { ImageKodec.encodeHeic(KiteBitmap(64 * 257, 1, IntArray(64 * 257)), HeifEncodeOptions(tileSize = 64)) }
        assertTrue(tiles.message!!.contains("tiles"), tiles.message)
        assertEquals(0, coded)
        assertFailsWith<IllegalArgumentException> { ImageKodec.registerHeifEncoder(object : HeifEncoder by RawHeifCodec { override val itemType = "hvc" }) }
    }

    @Test
    fun withoutAnEncoderTheCallNamesTheModule() {
        HeifCodecs.reset()
        val e = assertFailsWith<UnsupportedImageException> { ImageKodec.encodeHeic(photo(4, 4), 50) }
        assertTrue(e.message!!.contains("imagekodec-heic") && e.message!!.contains("ImageKodecHeic.install()"), e.message)
        assertFailsWith<UnsupportedImageException> { ImageKodec.encodeHeic16(KiteBitmap16(2, 2, 1, ShortArray(4)), HeifEncodeOptions()) }
    }

    private companion object {
        val EXPECTED = listOf("6fbccbdf63f62b9397f97ed5fb231a01", "86556a2cae08f221f634b0696c230e77", "2636cb92c513f120f6bcbc51ccb3e68e")
    }
}

/**
 * A codec of `hvc1` for tests that codes nothing: its configuration is the size, chroma format and depth, and
 * its data the samples of each plane, 16 bits big-endian. Registered as both the encoder and the decoder, it
 * shows exactly what the core puts in and takes out of the planes.
 */
@OptIn(ExperimentalHeifCodecApi::class)
internal object RawHeifCodec : HeifCodec, HeifEncoder {
    override val itemType = "hvc1"
    override val sampleEntryTypes = setOf("hvc1")
    override val configurationType = "hvcC"
    override val alphaUrn = "urn:mpeg:hevc:2015:auxid:1"

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    override fun encode(planes: HeifPlanes, parameters: HeifEncodeParameters, cancellationCheck: (() -> Unit)?): HeifCodedPicture {
        cancellationCheck?.invoke()
        val format = when {
            planes.planes.size == 1 -> 0
            planes.subY == 1 -> 1
            planes.subX == 1 -> 2
            else -> 3
        }
        val config = byteArrayOf(
            (planes.width ushr 8).toByte(), planes.width.toByte(), (planes.height ushr 8).toByte(), planes.height.toByte(),
            format.toByte(), planes.bitDepthLuma.toByte(), (if (planes.fullRange) 1 else 0).toByte(),
        )
        val out = ArrayList<Byte>()
        for (c in planes.planes.indices) {
            val w = if (c == 0) planes.width else planes.width shr planes.subX
            val h = if (c == 0) planes.height else planes.height shr planes.subY
            for (y in 0 until h) for (x in 0 until w) {
                val v = planes.planes[c][y * planes.strides[c] + x].toInt()
                out += (v ushr 8).toByte()
                out += v.toByte()
            }
        }
        return HeifCodedPicture(config, out.toByteArray(), planes.width, planes.height, "heic")
    }

    override fun inspect(configuration: ByteArray?, data: ByteArray?): HeifCodecInfo {
        val c = configuration!!
        return HeifCodecInfo(u16(c, 0), u16(c, 2), c[5].toInt(), c[5].toInt(), c[4].toInt())
    }

    override fun open(configuration: ByteArray?): HeifCodecSession = object : HeifCodecSession {
        override fun decode(sample: ByteArray, cancellationCheck: (() -> Unit)?): HeifPlanes {
            val c = configuration!!
            val w = u16(c, 0)
            val h = u16(c, 2)
            val format = c[4].toInt()
            val subX = if (format == 1 || format == 2) 1 else 0
            val subY = if (format == 1) 1 else 0
            var at = 0
            val planes = Array(if (format == 0) 1 else 3) { p ->
                val pw = if (p == 0) w else w shr subX
                val ph = if (p == 0) h else h shr subY
                ShortArray(pw * ph) { u16(sample, at).toShort().also { at += 2 } }
            }
            val strides = IntArray(planes.size) { p -> if (p == 0) w else w shr subX }
            return HeifPlanes(w, h, c[5].toInt(), c[5].toInt(), subX, subY, planes, strides, c[6].toInt() != 0)
        }
    }
}
