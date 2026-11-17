package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.avif.HeifCodecs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The embedded thumbnails of HEIF files and JPEG files (#150). */
@OptIn(ExperimentalHeifCodecApi::class)
class ThumbnailTest {

    @AfterTest
    fun forgetTheCodec() = HeifCodecs.reset()

    /** `tst1` items whose `tstC` gives the size, decoding to one gray level, their only data byte. */
    private object SizedCodec : HeifCodec {
        override val itemType = "tst1"
        override val sampleEntryTypes = setOf("tst1")
        override val configurationType = "tstC"
        private fun size(c: ByteArray?) = (c!![0].toInt() and 0xFF) to (c[1].toInt() and 0xFF)
        override fun inspect(configuration: ByteArray?, data: ByteArray?): HeifCodecInfo {
            val (w, h) = size(configuration)
            return HeifCodecInfo(w, h, 8, 8, chromaFormat = 0)
        }
        override fun open(configuration: ByteArray?): HeifCodecSession = object : HeifCodecSession {
            override fun decode(sample: ByteArray, cancellationCheck: (() -> Unit)?): HeifPlanes {
                val (w, h) = size(configuration)
                return HeifPlanes(w, h, 8, 8, 0, 0, arrayOf(ShortArray(w * h) { (sample[0].toInt() and 0xFF).toShort() }), intArrayOf(w), true)
            }
        }
    }

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray) = be32(8 + payload.size) + type.encodeToByteArray() + payload
    private fun ispe(w: Int, h: Int) = box("ispe", ByteArray(4) + be32(w) + be32(h)) to false
    private fun sized(w: Int, h: Int) = listOf(box("tstC", byteArrayOf(w.toByte(), h.toByte())) to true, ispe(w, h))

    /** A 64 by 64 primary with thumbnails of 16, 32 and, of a type nothing decodes, 48 samples a side. */
    private fun withThumbnails() = HeifBuilder("avif", listOf("avif", "mif1")).apply {
        add(1, "tst1", byteArrayOf(10), sized(64, 64))
        add(2, "tst1", byteArrayOf(20), sized(16, 16), hidden = true)
        add(3, "tst1", byteArrayOf(30), sized(32, 32), hidden = true)
        add(4, "zzzz", byteArrayOf(40), listOf(ispe(48, 48)), hidden = true)
        for (t in 2L..4L) references += Triple("thmb", t, longArrayOf(1))
    }.build()

    private fun level(b: KiteBitmap) = b[0, 0] and 0xFF

    @Test
    fun theSmallestThumbnailThatReachesTheBoxIsChosen() {
        ImageKodec.registerHeifCodec(SizedCodec)
        val file = withThumbnails()
        assertEquals(16, ImageKodec.decodeThumbnail(file, 8, 8)!!.width)
        assertEquals(30, level(ImageKodec.decodeThumbnail(file, 20, 20)!!))
        // None reaches the box: the largest that decodes, not the 48 nothing reads.
        assertEquals(32, ImageKodec.decodeThumbnail(file, 100, 100)!!.width)
        assertEquals(32, ImageKodec.decodeThumbnail(file)!!.width)
        val info = ImageKodec.probe(file)
        assertEquals(48 to 48, info.thumbnailWidth to info.thumbnailHeight)
    }

    @Test
    fun aPictureLargerThanItsIspeShowsTheIspe() {
        ImageKodec.registerHeifCodec(SizedCodec)
        // Sony codes a 1616 by 1080 thumbnail as 1664 by 1088 samples, with no conformance window.
        val file = HeifBuilder("avif", listOf("avif", "mif1")).apply {
            add(1, "tst1", byteArrayOf(10), listOf(box("tstC", byteArrayOf(40, 30)) to true, ispe(36, 24)))
            add(2, "tst1", byteArrayOf(20), listOf(box("tstC", byteArrayOf(24, 20)) to true, ispe(18, 14)), hidden = true)
            references += Triple("thmb", 2L, longArrayOf(1))
        }.build()
        val info = ImageKodec.probe(file)
        assertEquals(36 to 24, info.width to info.height)
        assertEquals(18 to 14, info.thumbnailWidth to info.thumbnailHeight)
        assertEquals(36 to 24, ImageKodec.decode(file).let { it.width to it.height })
        assertEquals(18 to 14, ImageKodec.decodeThumbnail(file)!!.let { it.width to it.height })
        // An ispe larger than the picture is damage, and the picture is what shows (#124).
        val larger = HeifBuilder("avif", listOf("avif", "mif1")).apply { add(1, "tst1", byteArrayOf(10), listOf(box("tstC", byteArrayOf(8, 8)) to true, ispe(16, 16))) }.build()
        assertEquals(8 to 8, ImageKodec.decode(larger).let { it.width to it.height })
    }

    @Test
    fun aFileWithoutThumbnailsGivesNull() {
        ImageKodec.registerHeifCodec(SizedCodec)
        val file = HeifBuilder("avif", listOf("avif", "mif1")).apply { add(1, "tst1", byteArrayOf(10), sized(8, 8)) }.build()
        assertNull(ImageKodec.decodeThumbnail(file))
        assertEquals(0, ImageKodec.probe(file).thumbnailWidth)
        assertNull(ImageKodec.decodeThumbnail(ImageKodec.encodePng(KiteBitmap(2, 2, IntArray(4)))))
    }

    @Test
    fun aJpegGivesTheThumbnailInItsExif() {
        val thumb = ImageKodec.encodeJpeg(KiteBitmap(8, 6, IntArray(48) { 0xFF336699.toInt() }), 90)
        // A little-endian TIFF: IFD0 with no entries, then IFD1 with the thumbnail's offset and length.
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
        fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
        val ifd1At = 8 + 2 + 4
        val dataAt = ifd1At + 2 + 2 * 12 + 4
        val tiff = byteArrayOf(0x49, 0x49, 0x2A, 0) + le32(8) + le16(0) + le32(ifd1At) +
            le16(2) + le16(0x0201) + le16(4) + le32(1) + le32(dataAt) + le16(0x0202) + le16(4) + le32(1) + le32(thumb.size) + le32(0) + thumb
        val photo = ImageKodec.encodeJpeg(KiteBitmap(40, 30, IntArray(1200) { 0xFF808080.toInt() }), 90, ImageMetadata(exif = tiff))
        val got = assertNotNull(ImageKodec.decodeThumbnail(photo))
        assertContentEquals(ImageKodec.decode(thumb).argb, got.argb)
        val info = ImageKodec.probe(photo)
        assertEquals(8 to 6, info.thumbnailWidth to info.thumbnailHeight)
        assertNull(ImageKodec.decodeThumbnail(ImageKodec.encodeJpeg(KiteBitmap(4, 4, IntArray(16)), 90)))
    }
}
