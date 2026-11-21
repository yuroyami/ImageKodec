package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.ExifDirectories
import io.github.yuroyami.imagekodec.codec.jxr.JxrContainerWriter
import io.github.yuroyami.archivekodec.checksum.Crc32
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The EXIF block, XMP packet and ICC profile through every encoder that has a place for them, read
 * back by [ImageKodec.readMetadata] (#186).
 *
 * JPEG, PNG, WebP, JPEG 2000, JPEG XL and AVIF keep each block byte for byte. A TIFF or JPEG XR
 * file takes an EXIF block apart into its own directory, so the block read back is rebuilt: its
 * EXIF, GPS and Interoperability directories hold the same values, and a block read back once
 * comes back byte for byte from then on. A GIF keeps the XMP and the profile, a BMP the profile.
 */
class MetadataEveryFormatTest {

    /** An RGB ICC profile of 3000 bytes whose header gives its size and colour space, as readers check. */
    private val icc = ByteArray(3000) { (it * 7 + 3).toByte() }.also {
        it[0] = 0; it[1] = 0; it[2] = (3000 shr 8).toByte(); it[3] = 3000.toByte()
        "RGB ".encodeToByteArray().copyInto(it, 16)
        "acsp".encodeToByteArray().copyInto(it, 36)
    }

    private val xmp = MetadataFixtures.XMP

    private val bitmap = KiteBitmap(9, 5, IntArray(45) { 0xFF000000.toInt() or (it * 0x050307) })

    private val metadata = ImageMetadata(MetadataFixtures.exif(), xmp, icc)

    /** Every entry of each directory of [exif], keyed by directory and tag, as type, count and value. */
    private fun entries(exif: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun walk(name: String, fields: List<ExifDirectories.Field>) {
            for (f in fields) {
                val sub = f.sub
                if (sub != null) {
                    walk(
                        when (f.tag) {
                            ExifDirectories.EXIF_IFD -> "exif"
                            ExifDirectories.GPS_IFD -> "gps"
                            else -> "interop"
                        },
                        sub,
                    )
                } else {
                    out["$name.${f.tag}"] = "${f.type}/${f.count}/" + f.value.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
                }
            }
        }
        walk("ifd0", assertNotNull(ExifDirectories.parseBlock(exif), "a TIFF structure"))
        return out
    }

    /**
     * Checks a block that [format] rebuilt: the same EXIF, GPS and Interoperability entries as
     * [metadata]'s, the IFD0 entries [kept] the same, and the block read back again unchanged.
     */
    private fun assertRebuilt(format: String, read: ByteArray?, kept: Set<Int>, again: (ImageMetadata) -> ByteArray) {
        val block = assertNotNull(read, "$format: the EXIF block")
        val want = entries(metadata.exif!!)
        val got = entries(block)
        assertEquals(want.filterKeys { !it.startsWith("ifd0") }, got.filterKeys { !it.startsWith("ifd0") }, "$format: the EXIF and GPS directories")
        for (tag in kept) assertEquals(want["ifd0.$tag"], got["ifd0.$tag"], "$format: IFD0 tag $tag")
        val second = ImageKodec.readMetadata(again(ImageMetadata(block, xmp, icc))).exif
        assertContentEquals(block, second, "$format: a rebuilt block comes back byte for byte")
    }

    private fun assertKept(format: String, file: ByteArray, exif: Boolean = true, xmp: Boolean = true) {
        val m = ImageKodec.readMetadata(file)
        if (exif) assertContentEquals(metadata.exif, m.exif, "$format: the EXIF block")
        if (xmp) assertEquals(this.xmp, m.xmp, "$format: the XMP packet")
        assertContentEquals(icc, m.icc, "$format: the ICC profile")
    }

    @Test
    fun formats_that_store_the_blocks_whole_keep_them_byte_for_byte() {
        assertKept("JPEG", ImageKodec.encodeJpeg(bitmap, JpegEncodeOptions(), metadata))
        assertKept("PNG", ImageKodec.encodePng(bitmap, PngEncodeOptions(), metadata))
        assertKept("WebP lossless", ImageKodec.encodeWebp(bitmap, WebpEncodeOptions(lossless = true), metadata))
        assertKept("WebP lossy", ImageKodec.encodeWebp(bitmap, WebpEncodeOptions(quality = 80), metadata))
        assertKept("JP2", ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(lossless = true), metadata))
        val jxl = ImageKodec.encodeJxl(bitmap, JxlEncodeOptions(lossless = true), metadata)
        assertKept("JPEG XL", jxl)
        assertContentEquals(bitmap.argb, ImageKodec.decode(jxl).argb, "JPEG XL: the pixels")
        // A HEIF writer turns the EXIF orientation to its irot, so the two agree here.
        assertKept("AVIF", ImageKodec.encodeAvif(bitmap, HeifEncodeOptions(lossless = true, effort = 1, orientation = Orientation.Rotate90), metadata))
    }

    @Test
    fun tiff_rebuilds_the_exif_block_and_keeps_xmp_and_icc() {
        for (options in listOf(TiffEncodeOptions(), TiffEncodeOptions(compression = TiffCompression.Lzw, tileWidth = 16, tileLength = 16))) {
            val tiff = ImageKodec.encodeTiff(bitmap, options, metadata)
            assertKept("TIFF", tiff, exif = false)
            assertRebuilt("TIFF", ImageKodec.readMetadata(tiff).exif, setOf(271, 272, 274, 282, 283, 296, 306)) {
                ImageKodec.encodeTiff(bitmap, options, it)
            }
            assertEquals(Orientation.Rotate90, ImageKodec.probe(tiff).orientation, "TIFF: the orientation joins the page")
            assertContentEquals(bitmap.argb, ImageKodec.decode(tiff).argb, "TIFF: the pixels")
        }
        val wide = KiteBitmap16(3, 2, 3, ShortArray(18) { (it * 3001).toShort() })
        val pages = ImageKodec.encodeTiff16(listOf(wide, wide), TiffEncodeOptions(), metadata)
        assertKept("TIFF 16", pages, exif = false)
        assertNotNull(ImageKodec.readMetadata(pages).exif)
        // A page that points to neither an EXIF nor a GPS directory gives no block.
        assertNull(ImageKodec.readMetadata(ImageKodec.encodeTiff(bitmap, TiffEncodeOptions(), ImageMetadata(xmp = xmp))).exif)
    }

    @Test
    fun jpeg_xr_rebuilds_the_exif_block_and_keeps_xmp_and_icc() {
        val jxr = ImageKodec.encodeJxr(bitmap, JxrEncodeOptions(lossless = true), metadata)
        assertKept("JPEG XR", jxr, exif = false)
        assertRebuilt("JPEG XR", ImageKodec.readMetadata(jxr).exif, JxrContainerWriter.TEXT_TAGS + 274) {
            ImageKodec.encodeJxr(bitmap, JxrEncodeOptions(lossless = true), it)
        }
    }

    @Test
    fun gif_keeps_xmp_and_icc_and_its_pixels() {
        val plain = ImageKodec.encodeGif(bitmap)
        val gif = ImageKodec.encodeGif(bitmap, metadata)
        assertKept("GIF", gif, exif = false)
        assertNull(ImageKodec.readMetadata(gif).exif)
        assertContentEquals(ImageKodec.decode(plain).argb, ImageKodec.decode(gif).argb)
        assertContentEquals(plain, ImageKodec.encodeGif(bitmap, ImageMetadata()), "no metadata, the same bytes")
        val animation = KiteAnimation(9, 5, listOf(KiteFrame(bitmap, 100, 10), KiteFrame(bitmap.flippedHorizontal(), 100, 10)), loopCount = 0)
        val animated = ImageKodec.encodeGif(animation, metadata)
        assertKept("animated GIF", animated, exif = false)
        assertEquals(2, ImageKodec.decodeAnimation(animated).frames.size)
    }

    @Test
    fun bmp_keeps_the_profile_at_every_depth() {
        val few = KiteBitmap(9, 5, IntArray(45) { if ((it / 3) % 2 == 0) 0xFF102030.toInt() else 0xFFF0E0D0.toInt() })
        val cases = listOf(
            bitmap to BmpEncodeOptions(),
            KiteBitmap(9, 5, IntArray(45) { (it * 5 shl 24) or 0x405060 }) to BmpEncodeOptions(),
            few to BmpEncodeOptions(bitsPerPixel = 1),
            few to BmpEncodeOptions(bitsPerPixel = 4, rle = true),
            few to BmpEncodeOptions(bitsPerPixel = 8, rle = true),
            bitmap to BmpEncodeOptions(bitsPerPixel = 16),
        )
        for ((b, options) in cases) {
            val plain = ImageKodec.encodeBmp(b, options)
            val bmp = ImageKodec.encodeBmp(b, options, metadata)
            val m = ImageKodec.readMetadata(bmp)
            assertContentEquals(icc, m.icc, "$options: the profile")
            assertNull(m.exif)
            assertNull(m.xmp)
            assertContentEquals(ImageKodec.decode(plain).argb, ImageKodec.decode(bmp).argb, "$options: the pixels")
            assertContentEquals(plain, ImageKodec.encodeBmp(b, options, ImageMetadata()), "$options: no profile, the same bytes")
        }
    }

    @Test
    fun jpeg_xl_reads_brotli_compressed_boxes() {
        val codestream = ImageKodec.encodeJxl(bitmap, JxlEncodeOptions(lossless = true))
        fun box(type: String, body: ByteArray): ByteArray {
            val n = 8 + body.size
            return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + type.encodeToByteArray() + body
        }
        val compressed = Base64.decode(BROTLI_XMP_STREAM)
        val file = box("JXL ", byteArrayOf(0x0D, 0x0A, 0x87.toByte(), 0x0A)) +
            box("ftyp", "jxl ".encodeToByteArray() + ByteArray(4) + "jxl ".encodeToByteArray()) +
            box("brob", "xml ".encodeToByteArray() + compressed) +
            box("brob", "Exif".encodeToByteArray() + byteArrayOf(1, 2, 3)) +
            box("jxlc", codestream)
        val m = ImageKodec.readMetadata(file)
        val text = assertNotNull(m.xmp)
        assertEquals(BROTLI_XMP_CRC, Crc32().also { it.update(text.encodeToByteArray()) }.value())
        assertNull(m.exif, "a damaged compressed box is left out")
        assertContentEquals(bitmap.argb, ImageKodec.decode(file).argb)
    }

    @Test
    fun cut_and_damaged_files_never_throw() {
        val files = listOf(
            ImageKodec.encodeTiff(bitmap, TiffEncodeOptions(), metadata),
            ImageKodec.encodeJxr(bitmap, JxrEncodeOptions(lossless = true), metadata),
            ImageKodec.encodeJxl(bitmap, JxlEncodeOptions(lossless = true), metadata),
            ImageKodec.encodeGif(bitmap, metadata),
            ImageKodec.encodeBmp(bitmap, BmpEncodeOptions(), metadata),
        )
        for (file in files) {
            for (cut in 0..file.size step 7) ImageKodec.readMetadata(file.copyOf(cut))
            for (at in file.indices step 3) {
                val bad = file.copyOf()
                bad[at] = (bad[at].toInt() xor 0x5A).toByte()
                ImageKodec.readMetadata(bad)
            }
        }
    }

    @Test
    fun the_bytes_of_fixed_inputs_keep_their_hash() {
        fun crc(b: ByteArray) = Crc32().also { it.update(b) }.value()
        val got = listOf(
            crc(ImageKodec.encodeTiff(bitmap, TiffEncodeOptions(), metadata)),
            crc(ImageKodec.encodeGif(bitmap, metadata)),
            crc(ImageKodec.encodeBmp(bitmap, BmpEncodeOptions(), metadata)),
            crc(assertNotNull(ImageKodec.readMetadata(ImageKodec.encodeTiff(bitmap, TiffEncodeOptions(), metadata)).exif)),
            crc(assertNotNull(ImageKodec.readMetadata(ImageKodec.encodeJxr(bitmap, JxrEncodeOptions(lossless = true), metadata)).exif)),
        )
        assertEquals(listOf(1976783363L, 3118404835L, 4268635881L, 2815323811L, 2910795420L), got)
    }
}
